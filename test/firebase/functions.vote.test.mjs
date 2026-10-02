import { after, before, beforeEach, test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createRequire } from 'node:module';

import { recordCommunityVoteHandler, seedLegacyVoteCounts } from '../../functions/lib/voteHandler.js';

const PROJECT_ID = 'aura-rules-test';
const VOTER_UID = 'voter-emulator';
const CONTENT_ID = 'WALLPAPER::COMMUNITY::cw_vote_target';
const requireFromFunctions = createRequire(new URL('../../functions/package.json', import.meta.url));
const { getApps, initializeApp, deleteApp } = requireFromFunctions('firebase-admin/app');
const { getDatabase } = requireFromFunctions('firebase-admin/database');

let app;

before(async () => {
  assert.ok(
    process.env.FIREBASE_DATABASE_EMULATOR_HOST,
    'functions vote emulator test must run under firebase emulators:exec --only database',
  );
  app = getApps()[0] ?? initializeApp({
    projectId: PROJECT_ID,
    databaseURL: `https://${PROJECT_ID}.firebaseio.com`,
  });
});

beforeEach(async () => {
  await getDatabase(app).ref().set(null);
});

after(async () => {
  if (app) {
    await deleteApp(app);
  }
});

function validRequest(overrides = {}) {
  return {
    auth: { uid: VOTER_UID },
    app: { appId: 'aura-emulator-test' },
    data: {
      operationId: `vote-emulator-${Date.now()}`,
      clientSentAt: Date.now() - 1_000,
      payload: {
        contentId: CONTENT_ID,
        ...overrides,
      },
    },
  };
}

async function readValue(path) {
  return (await getDatabase(app).ref(path).get()).val();
}

test('vote callable handler writes a public count, a private marker, quota, and dedupe rows', async () => {
  const result = await recordCommunityVoteHandler(validRequest());

  assert.equal(result.status, 'accepted');
  assert.equal(result.upvotes, 1);
  assert.equal(result.targetPath, `/vote_counts/${CONTENT_ID}`);

  assert.deepEqual(await readValue(`vote_counts/${CONTENT_ID}`), { upvotes: 1 });
  assert.equal(await readValue(`vote_markers/${VOTER_UID}/${CONTENT_ID}`), true);
  assert.equal(await readValue('vote_locks'), null);

  // Nothing that names the voter lands in the old public trees.
  assert.equal(await readValue('votes'), null);
  assert.equal(await readValue('voters'), null);

  const quotas = await readValue(`community_write_quotas/${VOTER_UID}`);
  const quotaDays = Object.keys(quotas);
  assert.equal(quotaDays.length, 1);
  assert.equal(quotas[quotaDays[0]].votes.count, 1);
  assert.equal(typeof quotas[quotaDays[0]].votes.lastAt, 'number');

  const dedupe = await readValue(`community_write_dedupe/${VOTER_UID}/votes`);
  const dedupeKeys = Object.keys(dedupe);
  assert.deepEqual(dedupeKeys, [CONTENT_ID]);
  assert.equal(dedupe[CONTENT_ID].targetPath, `/vote_counts/${CONTENT_ID}`);
});

test('repeat vote is idempotent through the private marker before quota changes', async () => {
  const first = await recordCommunityVoteHandler(validRequest());
  const second = await recordCommunityVoteHandler(validRequest());

  assert.equal(first.status, 'accepted');
  assert.equal(second.status, 'duplicate');
  assert.equal(second.targetPath, `/vote_counts/${CONTENT_ID}`);

  assert.equal((await readValue(`vote_counts/${CONTENT_ID}`)).upvotes, 1);
  assert.equal(await readValue(`vote_markers/${VOTER_UID}/${CONTENT_ID}`), true);

  const quotas = await readValue(`community_write_quotas/${VOTER_UID}`);
  const quotaDay = Object.keys(quotas)[0];
  assert.equal(quotas[quotaDay].votes.count, 1);
});

test('votes from different accounts each add one to the shared count', async () => {
  await recordCommunityVoteHandler(validRequest());
  const other = await recordCommunityVoteHandler({ ...validRequest(), auth: { uid: 'voter-two' } });

  assert.equal(other.status, 'accepted');
  assert.equal(other.upvotes, 2);
  assert.equal((await readValue(`vote_counts/${CONTENT_ID}`)).upvotes, 2);
  assert.deepEqual(Object.keys(await readValue('vote_markers')).sort(), [VOTER_UID, 'voter-two']);
});

test('first vote after the split starts from the legacy count and legacy voters stay deduped', async () => {
  await getDatabase(app).ref().update({
    [`votes/${CONTENT_ID}`]: { upvotes: 5, voters: { 'legacy-voter': true } },
    [`voters/${CONTENT_ID}/older-voter`]: true,
  });

  const fresh = await recordCommunityVoteHandler(validRequest());
  assert.equal(fresh.status, 'accepted');
  assert.equal(fresh.upvotes, 6);
  assert.equal((await readValue(`vote_counts/${CONTENT_ID}`)).upvotes, 6);

  for (const uid of ['legacy-voter', 'older-voter']) {
    const repeat = await recordCommunityVoteHandler({ ...validRequest(), auth: { uid } });
    assert.equal(repeat.status, 'duplicate');
  }
  assert.equal((await readValue(`vote_counts/${CONTENT_ID}`)).upvotes, 6);
});

test('a vote interrupted before it was counted can be cast again once its lock lapses', async () => {
  // What a run that died after taking the lock leaves behind: a lock, no marker, no count.
  await getDatabase(app).ref(`vote_locks/${VOTER_UID}/${CONTENT_ID}`).set({ at: Date.now() - 6 * 60 * 1_000 });

  const retry = await recordCommunityVoteHandler(validRequest());

  assert.equal(retry.status, 'accepted');
  assert.equal(retry.upvotes, 1);
  assert.equal(await readValue(`vote_markers/${VOTER_UID}/${CONTENT_ID}`), true);
  assert.equal(await readValue('vote_locks'), null);
});

test('a vote still being counted turns a second call away without counting or charging it', async () => {
  await getDatabase(app).ref(`vote_locks/${VOTER_UID}/${CONTENT_ID}`).set({ at: Date.now() });

  await assert.rejects(
    recordCommunityVoteHandler(validRequest()),
    (error) => error.code === 'aborted' && /still being counted/.test(error.message),
  );

  assert.equal(await readValue(`vote_counts/${CONTENT_ID}`), null);
  assert.equal(await readValue(`vote_markers/${VOTER_UID}/${CONTENT_ID}`), null);
  const quotas = await readValue(`community_write_quotas/${VOTER_UID}`) ?? {};
  for (const day of Object.values(quotas)) {
    assert.equal(day.votes?.count ?? 0, 0);
  }
});

test('a stored count that is not a whole number is cleaned up before the vote adds to it', async () => {
  await getDatabase(app).ref(`vote_counts/${CONTENT_ID}`).set({ upvotes: -4 });

  const result = await recordCommunityVoteHandler(validRequest());

  assert.equal(result.upvotes, 1);
  assert.deepEqual(await readValue(`vote_counts/${CONTENT_ID}`), { upvotes: 1 });
});

test('the seeding job fills missing counts from legacy rows and never lowers one already counted', async () => {
  const counted = 'WALLPAPER::COMMUNITY::cw_counted';
  await getDatabase(app).ref().update({
    [`votes/${CONTENT_ID}`]: { upvotes: 4, voters: { 'legacy-voter': true } },
    [`votes/${counted}`]: { upvotes: 9 },
    'votes/SOUND::FREESOUND::zero': { upvotes: 0 },
    'votes/SOUND::FREESOUND::crowd': { upvotes: 1, voters: { a: true, b: true } },
    'voters/SOUND::FREESOUND::crowd/c': true,
    'voters/SOUND::FREESOUND::voters_only/d': true,
    'community_wallpapers/vote_target': { name: 'Target', storagePath: 'wallpapers/owner/vote_target.jpg', votes: 1 },
  });
  // A vote cast after the split but before the job runs seeds from legacy and adds itself.
  const vote = await recordCommunityVoteHandler(validRequest({ contentId: counted }));
  assert.equal(vote.upvotes, 10);

  const firstRun = await seedLegacyVoteCounts(getDatabase(app).ref(), 2, 10);

  assert.equal(firstRun, 4);
  assert.deepEqual(await readValue(`vote_counts/${CONTENT_ID}`), { upvotes: 4 });
  assert.deepEqual(await readValue(`vote_counts/${counted}`), { upvotes: 10 });
  assert.deepEqual(await readValue('vote_counts/SOUND::FREESOUND::zero'), { upvotes: 0 });
  // A count never starts below the number of distinct legacy voters.
  assert.deepEqual(await readValue('vote_counts/SOUND::FREESOUND::crowd'), { upvotes: 3 });
  assert.deepEqual(await readValue('vote_counts/SOUND::FREESOUND::voters_only'), { upvotes: 1 });
  assert.equal(await readValue('community_wallpapers/vote_target/votes'), 4);
  assert.equal(await readValue('community_wallpapers/counted'), null);

  // A later vote adds to the seeded count, and a second run changes nothing.
  const next = await recordCommunityVoteHandler({ ...validRequest(), auth: { uid: 'voter-two' } });
  assert.equal(next.upvotes, 5);
  assert.equal(await seedLegacyVoteCounts(getDatabase(app).ref(), 2, 10), 0);
  assert.deepEqual(await readValue(`vote_counts/${CONTENT_ID}`), { upvotes: 5 });
});

test('the seeding job resumes where the last run stopped instead of re-walking the start', async () => {
  const ids = ['A', 'B', 'C', 'D', 'E'].map((suffix) => `SOUND::FREESOUND::seed_${suffix}`);
  await getDatabase(app).ref().update(Object.fromEntries(ids.map((id) => [`votes/${id}`, { upvotes: 2 }])));

  // Two keys per run: each run picks up after the last one instead of seeding the same head.
  assert.equal(await seedLegacyVoteCounts(getDatabase(app).ref(), 1, 2), 2);
  assert.equal(await readValue('vote_seed_cursor/votes'), ids[1]);
  assert.equal(await readValue(`vote_counts/${ids[2]}`), null);
  assert.equal(await seedLegacyVoteCounts(getDatabase(app).ref(), 1, 2), 2);
  assert.equal(await readValue('vote_seed_cursor/votes'), ids[3]);
  assert.equal(await seedLegacyVoteCounts(getDatabase(app).ref(), 1, 2), 1);

  // The last key ended the walk, so the cursor is cleared and every item has a count.
  assert.equal(await readValue('vote_seed_cursor/votes'), null);
  for (const id of ids) {
    assert.deepEqual(await readValue(`vote_counts/${id}`), { upvotes: 2 });
  }
});

test('a finished first root is not walked again while the second root resumes', async () => {
  const voteIds = ['A', 'B', 'C'].map((suffix) => `SOUND::FREESOUND::votes_${suffix}`);
  const voterIds = ['A', 'B', 'C'].map((suffix) => `SOUND::FREESOUND::voters_${suffix}`);
  await getDatabase(app).ref().update({
    ...Object.fromEntries(voteIds.map((id) => [`votes/${id}`, { upvotes: 2 }])),
    ...Object.fromEntries(voterIds.map((id) => [`voters/${id}/someone`, true])),
  });
  const run = () => seedLegacyVoteCounts(getDatabase(app).ref(), 1, 2);

  assert.equal(await run(), 2);
  assert.equal(await run(), 3);
  assert.deepEqual(await readValue('vote_seed_cursor'), { votes: true, voters: voterIds[1] });

  // /votes is done, so this run spends its batches on /voters instead of starting /votes over.
  assert.equal(await run(), 1);
  assert.deepEqual(await readValue(`vote_counts/${voterIds[2]}`), { upvotes: 1 });
  assert.equal(await readValue('vote_seed_cursor'), null);
});

test('the seeding job starts no batch once its deadline has passed', async () => {
  await getDatabase(app).ref('votes/SOUND::FREESOUND::late').set({ upvotes: 3 });

  assert.equal(await seedLegacyVoteCounts(getDatabase(app).ref(), 1, 10, 1_000, () => 1_000), 0);
  assert.equal(await readValue('vote_counts/SOUND::FREESOUND::late'), null);

  assert.equal(await seedLegacyVoteCounts(getDatabase(app).ref(), 1, 10, 1_000, () => 999), 1);
  assert.deepEqual(await readValue('vote_counts/SOUND::FREESOUND::late'), { upvotes: 3 });
});

test('community upload rows get the count mirrored into their votes field', async () => {
  await getDatabase(app).ref(`community_wallpapers/vote_target`).set({
    name: 'Target',
    storagePath: 'wallpapers/owner/vote_target.jpg',
    votes: 0,
  });

  await recordCommunityVoteHandler(validRequest());
  assert.equal(await readValue('community_wallpapers/vote_target/votes'), 1);

  // A vote on an upload whose row is gone must not recreate a stub row.
  const missing = 'SOUND::COMMUNITY::cu_deleted_upload';
  const result = await recordCommunityVoteHandler({
    ...validRequest({ contentId: missing }),
    auth: { uid: 'voter-three' },
  });
  assert.equal(result.status, 'accepted');
  assert.equal(await readValue('community_sounds/deleted_upload'), null);
});
