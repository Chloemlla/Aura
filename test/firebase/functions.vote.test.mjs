import { after, before, beforeEach, test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createRequire } from 'node:module';

import { recordCommunityVoteHandler } from '../../functions/lib/voteHandler.js';

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
