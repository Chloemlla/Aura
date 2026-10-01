import { after, before, beforeEach, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { strict as assert } from 'node:assert';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';

const PROJECT_ID = 'aura-rules-test';
const DAY_KEY = '20260606';
const MAX_COLLECTION_PAYLOAD_BYTES = 512 * 1024;

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    database: {
      rules: readFileSync('database.rules.json', 'utf8'),
    },
  });
});

beforeEach(async () => {
  await testEnv.clearDatabase();
});

after(async () => {
  await testEnv.cleanup();
});

function dbFor(uid, tokenOptions = {}) {
  return testEnv.authenticatedContext(uid, tokenOptions).database();
}

function adminDb() {
  return dbFor('rules-admin', { admin: true });
}

function unauthenticatedDb() {
  return testEnv.unauthenticatedContext().database();
}

function nowMs() {
  return Date.now() - 1000;
}

function soundMetadata(uid, overrides = {}) {
  return {
    name: 'Soft Bell',
    category: 'ringtone',
    tags: ['soft', 'alert'],
    downloadUrl: 'https://example.com/sounds/soft-bell.mp3',
    storagePath: `sounds/${uid}/soft-bell.mp3`,
    fileType: 'audio/mpeg',
    uploadedAt: nowMs(),
    uploaderId: uid,
    uploaderUid: uid,
    uploaderLabel: 'Uploader',
    license: 'CC0',
    rightsAttested: true,
    rightsAttestedAt: nowMs(),
    sourceUrl: '',
    votes: 0,
    ...overrides,
  };
}

function wallpaperMetadata(uid, overrides = {}) {
  return {
    name: 'Night Grid',
    category: 'abstract',
    tags: ['dark', 'minimal'],
    colors: ['#000000', '#FFFFFF'],
    thumbnailUrl: 'https://example.com/wallpapers/night-grid.jpg',
    fullUrl: 'https://example.com/wallpapers/night-grid.jpg',
    downloadUrl: 'https://example.com/wallpapers/night-grid.jpg',
    storagePath: `wallpapers/${uid}/night-grid.jpg`,
    width: 1440,
    height: 2560,
    fileSize: 123456,
    fileType: 'image/jpeg',
    uploadedAt: nowMs(),
    uploaderId: uid,
    uploaderUid: uid,
    uploaderLabel: 'Uploader',
    license: 'CC BY',
    rightsAttested: true,
    rightsAttestedAt: nowMs(),
    sourceUrl: 'https://example.com/source/night-grid',
    votes: 0,
    ...overrides,
  };
}

function ownerIndexPayload({ uploadId, uid, kind = 'sounds', overrides = {} }) {
  const isSound = kind === 'sounds';
  return {
    uploadId,
    publicId: `${isSound ? 'cu' : 'cw'}_${uploadId}`,
    contentType: isSound ? 'SOUND' : 'WALLPAPER',
    metadataPath: `/${isSound ? 'community_sounds' : 'community_wallpapers'}/${uploadId}`,
    storagePath: `${kind}/${uid}/${uploadId}.${isSound ? 'mp3' : 'jpg'}`,
    title: isSound ? 'Soft Bell' : 'Night Grid',
    createdAt: nowMs(),
    ...overrides,
  };
}

function uploadDeletionPayload({ uploadId, uid, kind = 'sounds', overrides = {} }) {
  const isSound = kind === 'sounds';
  const publicId = `${isSound ? 'cu' : 'cw'}_${uploadId}`;
  return {
    publicId,
    uploadId,
    contentType: isSound ? 'SOUND' : 'WALLPAPER',
    metadataPath: `/${isSound ? 'community_sounds' : 'community_wallpapers'}/${uploadId}`,
    storagePath: `${kind}/${uid}/${uploadId}.${isSound ? 'mp3' : 'jpg'}`,
    uploaderUid: uid,
    deletedByUid: uid,
    deletedAt: nowMs(),
    reason: 'OWNER_DELETE',
    ...overrides,
  };
}

function reportPayload(reporterUid, overrides = {}) {
  return {
    contentId: 'cu_sound_reported',
    contentKey: 'cu_sound_reported',
    contentType: 'SOUND',
    contentSource: 'COMMUNITY',
    reason: 'RIGHTS',
    note: 'This appears to use unlicensed audio.',
    sourceUrl: 'https://example.com/source',
    license: 'CC0',
    uploaderName: 'Uploader',
    reporterUid,
    reportedAt: nowMs(),
    status: 'OPEN',
    ...overrides,
  };
}

function takedownReceiptPayload(overrides = {}) {
  return {
    reportId: 'report1',
    contentId: 'SOUND::COMMUNITY::cu_sound1',
    contentType: 'SOUND',
    contentSource: 'COMMUNITY',
    reason: 'RIGHTS',
    action: 'HIDE',
    status: 'HIDDEN',
    uploadId: 'sound1',
    metadataPath: '/community_sounds/sound1',
    storagePath: 'sounds/sound-owner/sound1.mp3',
    uploaderUid: 'sound-owner',
    resolverUid: 'rules-admin',
    resolvedAt: nowMs(),
    note: 'Confirmed rights issue',
    ...overrides,
  };
}

function quotaPayload(overrides = {}) {
  const time = nowMs();
  return {
    count: 2,
    firstAt: time - 1000,
    lastAt: time,
    blockedCount: 1,
    lastBlockedAt: time,
    ...overrides,
  };
}

function dedupePayload(overrides = {}) {
  const time = nowMs();
  return {
    createdAt: time,
    expiresAt: time + 60_000,
    target: 'cu_sound_reported',
    ...overrides,
  };
}

function userBlockPayload({ blockerUid, blockedUid, overrides = {} }) {
  return {
    blockerUid,
    blockedUid,
    createdAt: nowMs(),
    reason: 'SPAM',
    ...overrides,
  };
}

function collectionPayload(createdByUid = 'collection-owner', overrides = {}) {
  return {
    version: 1,
    payload: JSON.stringify({
      version: 1,
      collectionName: 'Evening',
      items: [
        {
          wallpaperId: 'wall-1',
          source: 'PEXELS',
          thumbnailUrl: 'https://example.com/thumb.jpg',
          fullUrl: 'https://example.com/full.jpg',
          width: 1440,
          height: 2560,
        },
      ],
    }),
    collectionName: 'Evening',
    itemCount: 1,
    createdAt: nowMs(),
    createdByUid,
    ...overrides,
  };
}

async function seed(path, value) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.database().ref(path).set(value);
  });
}

test('community sound metadata is callable-owned while preserving owner deletion authority', async () => {
  const owner = dbFor('sound-owner');
  const other = dbFor('sound-other');
  const anonymous = unauthenticatedDb();
  const admin = adminDb();

  await assertFails(owner.ref('community_sounds/sound1').set(soundMetadata('sound-owner')));
  await assertSucceeds(admin.ref('community_sounds/sound1').set(soundMetadata('sound-owner')));
  await assertSucceeds(anonymous.ref('community_sounds/sound1').once('value'));
  await assertFails(anonymous.ref('community_sounds/anon').set(soundMetadata('sound-owner')));
  await assertFails(owner.ref('community_sounds/cross-owner').set(soundMetadata('sound-other')));
  await assertFails(admin.ref('community_sounds/bad-path').set(
    soundMetadata('sound-owner', { storagePath: 'wallpapers/sound-owner/not-a-sound.jpg' }),
  ));
  await assertFails(owner.ref('community_sounds/sound1').update({ name: 'Edited' }));

  await assertFails(other.ref('community_sounds/sound1').remove());
  await assertSucceeds(owner.ref('community_sounds/sound1').remove());

  await seed('community_sounds/adminDelete', soundMetadata('sound-owner'));
  await assertSucceeds(adminDb().ref('community_sounds/adminDelete').remove());
});

test('community wallpaper metadata is callable-owned while preserving owner deletion authority', async () => {
  const owner = dbFor('wall-owner');
  const other = dbFor('wall-other');
  const admin = adminDb();

  await assertFails(owner.ref('community_wallpapers/wall1').set(wallpaperMetadata('wall-owner')));
  await assertSucceeds(admin.ref('community_wallpapers/wall1').set(wallpaperMetadata('wall-owner')));
  await assertSucceeds(unauthenticatedDb().ref('community_wallpapers/wall1').once('value'));
  await assertFails(unauthenticatedDb().ref('community_wallpapers/anon').set(wallpaperMetadata('wall-owner')));
  await assertFails(owner.ref('community_wallpapers/cross-owner').set(wallpaperMetadata('wall-other')));
  await assertFails(admin.ref('community_wallpapers/bad-path').set(
    wallpaperMetadata('wall-owner', { storagePath: 'sounds/wall-owner/not-wallpaper.mp3' }),
  ));
  await assertFails(owner.ref('community_wallpapers/wall1').update({ name: 'Edited' }));

  await assertFails(other.ref('community_wallpapers/wall1').remove());
  await assertSucceeds(owner.ref('community_wallpapers/wall1').remove());
});

test('owner upload indexes are callable-owned while owners can delete their own index rows', async () => {
  const owner = dbFor('index-owner');
  const other = dbFor('index-other');
  const admin = adminDb();
  const payload = ownerIndexPayload({ uploadId: 'sound1', uid: 'index-owner' });
  const path = 'owner_uploads/index-owner/sounds/sound1';

  await assertFails(owner.ref(path).set(payload));
  await assertSucceeds(admin.ref(path).set(payload));
  await assertSucceeds(owner.ref(path).once('value'));
  await assertSucceeds(admin.ref(path).once('value'));
  await assertFails(other.ref(path).once('value'));
  await assertFails(other.ref(path).set(payload));
  await assertFails(owner.ref(path).set({ ...payload, uploadId: 'different' }));
  await assertFails(owner.ref(path).update({ title: 'Edited' }));
  await assertSucceeds(owner.ref(path).remove());
});

test('votes, follows, and creator profiles reject direct user writes', async () => {
  const user = dbFor('callable-user');
  const admin = adminDb();

  await assertFails(user.ref('votes/content1/upvotes').set(1));
  await assertSucceeds(admin.ref('votes/content1/upvotes').set(1));
  await assertFails(user.ref('votes/content1/voters/callable-user').set(true));
  await assertSucceeds(admin.ref('votes/content1/voters/callable-user').set(true));
  await assertFails(user.ref('voters/content1/callable-user').set(true));
  await assertSucceeds(admin.ref('voters/content1/callable-user').set(true));

  const followPayload = {
    creatorId: 'creator1',
    label: 'Creator One',
    followedAt: nowMs(),
  };
  await assertFails(user.ref('creator_follows/callable-user/creator1').set(followPayload));
  await assertSucceeds(admin.ref('creator_follows/callable-user/creator1').set(followPayload));

  const profilePayload = {
    profileUid: 'callable-user',
    displayName: 'Aura Maker',
    bio: 'Builds AMOLED packs',
    websiteUrl: 'https://example.com/profile',
    avatarUrl: '',
    createdAt: nowMs(),
    updatedAt: nowMs(),
  };
  await assertFails(user.ref('creator_profiles/callable-user').set(profilePayload));
  await assertSucceeds(admin.ref('creator_profiles/callable-user').set(profilePayload));
  await assertSucceeds(unauthenticatedDb().ref('creator_profiles/callable-user').once('value'));
});

const WALL_KEY = 'WALLPAPER::COMMUNITY::cw_wall1';
const SOUND_KEY = 'SOUND::COMMUNITY::cu_sound1';

async function seedVoteSchema() {
  await seed('vote_counts', {
    [WALL_KEY]: { upvotes: 4 },
    [SOUND_KEY]: { upvotes: 2 },
    'WALLPAPER::WALLHAVEN::zero': { upvotes: 0 },
  });
  await seed('vote_markers', {
    'voter-one': { [WALL_KEY]: true },
    'voter-two': { [SOUND_KEY]: true },
  });
  await seed('votes', { [WALL_KEY]: { upvotes: 4, voters: { 'voter-one': true } } });
  await seed('voters', { [WALL_KEY]: { 'voter-one': true } });
  await seed('creator_follows', {
    'voter-one': { creator1: { creatorId: 'creator1', label: 'Creator One', followedAt: nowMs() } },
  });
}

function childValues(snapshot) {
  const rows = [];
  snapshot.forEach((child) => {
    rows.push([child.key, child.child('upvotes').val()]);
  });
  return rows;
}

test('app vote count paths return nonzero public counts without any UID', async () => {
  await seedVoteSchema();
  const anonymous = unauthenticatedDb();
  const user = dbFor('voter-two');

  // VoteRepository.getVoteCount / getVoteCounts / getVoteCountsOnce: one row per item.
  const row = await assertSucceeds(anonymous.ref(`vote_counts/${WALL_KEY}/upvotes`).once('value'));
  assert.equal(row.val(), 4);

  // VoteRepository.getTopVotedIds: the leaderboard query the rules allow on the collection.
  const board = await assertSucceeds(
    user.ref('vote_counts').orderByChild('upvotes').limitToLast(50).once('value'),
  );
  assert.deepEqual(childValues(board), [
    ['WALLPAPER::WALLHAVEN::zero', 0],
    [SOUND_KEY, 2],
    [WALL_KEY, 4],
  ]);
  assert.doesNotMatch(JSON.stringify(board.val()), /voter-/);

  // Anything else on the collection is refused: a whole-tree read or an oversized page.
  await assertFails(anonymous.ref('vote_counts').once('value'));
  await assertFails(anonymous.ref('vote_counts').orderByChild('upvotes').limitToLast(201).once('value'));
  await assertFails(anonymous.ref('vote_counts').orderByKey().limitToLast(10).once('value'));
  await assertSucceeds(adminDb().ref('vote_counts').once('value'));
});

test('one account cannot enumerate another account vote or follow markers', async () => {
  await seedVoteSchema();
  const one = dbFor('voter-one');
  const two = dbFor('voter-two');
  const anonymous = unauthenticatedDb();

  const own = await assertSucceeds(one.ref('vote_markers/voter-one').once('value'));
  assert.deepEqual(own.val(), { [WALL_KEY]: true });
  await assertSucceeds(one.ref(`vote_markers/voter-one/${WALL_KEY}`).once('value'));
  await assertFails(two.ref('vote_markers/voter-one').once('value'));
  await assertFails(two.ref(`vote_markers/voter-one/${WALL_KEY}`).once('value'));
  await assertFails(two.ref('vote_markers').once('value'));
  await assertFails(anonymous.ref('vote_markers/voter-one').once('value'));
  // In-flight vote locks are server-only, even for the voter.
  await assertFails(one.ref('vote_locks/voter-one').once('value'));
  await assertFails(one.ref(`vote_locks/voter-one/${WALL_KEY}`).set({ at: 1 }));

  // Legacy trees that carried UIDs are admin-only; the old count leaf stays for older builds.
  await assertFails(two.ref('votes').once('value'));
  await assertFails(two.ref(`votes/${WALL_KEY}`).once('value'));
  await assertFails(two.ref(`votes/${WALL_KEY}/voters`).once('value'));
  await assertFails(two.ref('voters').once('value'));
  await assertFails(two.ref(`voters/${WALL_KEY}`).once('value'));
  const legacyCount = await assertSucceeds(anonymous.ref(`votes/${WALL_KEY}/upvotes`).once('value'));
  assert.equal(legacyCount.val(), 4);
  await assertSucceeds(adminDb().ref(`votes/${WALL_KEY}/voters`).once('value'));

  await assertSucceeds(one.ref('creator_follows/voter-one').once('value'));
  await assertFails(two.ref('creator_follows/voter-one').once('value'));
  await assertFails(two.ref('creator_follows').once('value'));
  await assertFails(anonymous.ref('creator_follows/voter-one').once('value'));
});

test('vote counts and markers are callable-owned and the count row cannot carry voters', async () => {
  const user = dbFor('voter-one');
  const admin = adminDb();

  await assertFails(user.ref(`vote_counts/${WALL_KEY}`).set({ upvotes: 1 }));
  await assertFails(user.ref(`vote_markers/voter-one/${WALL_KEY}`).set(true));
  await assertSucceeds(admin.ref(`vote_counts/${WALL_KEY}`).set({ upvotes: 1 }));
  await assertFails(admin.ref(`vote_counts/${WALL_KEY}`).set({ upvotes: -1 }));
  await assertFails(admin.ref(`vote_counts/${WALL_KEY}`).set({ upvotes: 2, voters: { 'voter-one': true } }));
  await assertSucceeds(admin.ref(`vote_markers/voter-one/${WALL_KEY}`).set(true));
  await assertFails(admin.ref(`vote_markers/voter-one/${SOUND_KEY}`).set('yes'));
});

function ownerDeleteUpdates({ uploadId, uid, kind = 'sounds', overrides = {} }) {
  const tombstone = uploadDeletionPayload({ uploadId, uid, kind, overrides });
  return {
    [`${kind === 'sounds' ? 'community_sounds' : 'community_wallpapers'}/${uploadId}`]: null,
    [`owner_uploads/${uid}/${kind}/${uploadId}`]: null,
    [`community_upload_deletions/${tombstone.publicId}`]: tombstone,
  };
}

async function seedUpload({ uploadId, uid, kind = 'sounds', storagePath }) {
  const isSound = kind === 'sounds';
  const path = storagePath ?? `${kind}/${uid}/${uploadId}.${isSound ? 'mp3' : 'jpg'}`;
  const metadata = isSound ? soundMetadata(uid, { storagePath: path }) : wallpaperMetadata(uid, { storagePath: path });
  await seed(`${isSound ? 'community_sounds' : 'community_wallpapers'}/${uploadId}`, metadata);
  await seed(`owner_uploads/${uid}/${kind}/${uploadId}`, ownerIndexPayload({ uploadId, uid, kind, overrides: { storagePath: path } }));
}

test('community upload deletion tombstones stay private and need the owner delete in the same write', async () => {
  const owner = dbFor('delete-owner');
  const other = dbFor('delete-other');
  const admin = adminDb();
  const path = 'community_upload_deletions/cu_sound1';
  const payload = uploadDeletionPayload({ uploadId: 'sound1', uid: 'delete-owner' });

  // With no upload behind it, a tombstone is fabricated.
  await assertFails(unauthenticatedDb().ref(path).set(payload));
  await assertFails(owner.ref(path).set(payload));

  await seedUpload({ uploadId: 'sound1', uid: 'delete-owner' });
  // The upload is still live after this write, so it is not a deletion.
  await assertFails(owner.ref(path).set(payload));
  await assertFails(other.ref().update(ownerDeleteUpdates({ uploadId: 'sound1', uid: 'delete-owner' })));
  // The tombstone must name the upload it replaces.
  await assertFails(owner.ref().update(ownerDeleteUpdates({
    uploadId: 'sound1',
    uid: 'delete-owner',
    overrides: { storagePath: 'sounds/delete-owner/another-file.mp3' },
  })));
  await assertFails(owner.ref().update({
    'community_sounds/sound1': null,
    'owner_uploads/delete-owner/sounds/sound1': null,
    'community_upload_deletions/cu_decoy': { ...payload, publicId: 'cu_decoy' },
  }));

  await assertSucceeds(owner.ref().update(ownerDeleteUpdates({ uploadId: 'sound1', uid: 'delete-owner' })));
  await assertFails(owner.ref(path).once('value'));
  await assertSucceeds(admin.ref(path).once('value'));
  await assertFails(owner.ref(path).update({ deletedAt: nowMs() }));

  await seedUpload({ uploadId: 'badpath', uid: 'delete-owner' });
  await assertFails(owner.ref().update(ownerDeleteUpdates({
    uploadId: 'badpath',
    uid: 'delete-owner',
    overrides: { storagePath: 'wallpapers/delete-owner/badpath.jpg' },
  })));

  await seedUpload({ uploadId: 'wrongowner', uid: 'delete-owner', storagePath: 'sounds/someone-else/wrongowner.mp3' });
  await assertFails(owner.ref().update(ownerDeleteUpdates({
    uploadId: 'wrongowner',
    uid: 'delete-owner',
    overrides: { storagePath: 'sounds/someone-else/wrongowner.mp3' },
  })));

  await seedUpload({ uploadId: 'badreason', uid: 'delete-owner' });
  await assertFails(owner.ref().update(ownerDeleteUpdates({
    uploadId: 'badreason',
    uid: 'delete-owner',
    overrides: { reason: 'ADMIN_TAKEDOWN' },
  })));

  await seedUpload({ uploadId: 'wall2', uid: 'delete-owner', kind: 'wallpapers' });
  await assertSucceeds(owner.ref().update(ownerDeleteUpdates({ uploadId: 'wall2', uid: 'delete-owner', kind: 'wallpapers' })));

  await assertSucceeds(admin.ref('community_upload_deletions/cw_wall1').set(
    uploadDeletionPayload({
      uploadId: 'wall1',
      uid: 'delete-owner',
      kind: 'wallpapers',
      overrides: {
        deletedByUid: 'rules-admin',
        reason: 'ADMIN_TAKEDOWN',
      },
    }),
  ));
});

test('community report intake is callable-owned and admin-readable', async () => {
  const reporter = dbFor('reporter1');
  const other = dbFor('reporter2');
  const admin = adminDb();
  const path = 'community_reports/report1';

  await assertFails(reporter.ref(path).set(reportPayload('reporter1', { uploaderUid: 'uploader-1' })));
  await assertSucceeds(admin.ref(path).set(reportPayload('reporter1', { uploaderUid: 'uploader-1' })));
  await assertFails(unauthenticatedDb().ref('community_reports/anon').set(reportPayload('reporter1')));
  await assertFails(other.ref('community_reports/report2').set(reportPayload('reporter1')));
  await assertFails(admin.ref('community_reports/report3').set(reportPayload('reporter1', { uploaderUid: 'u'.repeat(241) })));
  await assertSucceeds(admin.ref('community_reports/providerPolicy').set(
    reportPayload('reporter1', {
      contentId: 'WALLPAPER::PEXELS::pexels-123',
      contentKey: 'WALLPAPER::PEXELS::pexels-123',
      contentType: 'WALLPAPER',
      contentSource: 'PEXELS',
      reason: 'PROVIDER_POLICY',
      sourceUrl: 'https://www.pexels.com/photo/example-123/',
      license: 'Pexels License',
      uploaderUid: '',
    }),
  ));
  await assertFails(reporter.ref(path).once('value'));
  await assertSucceeds(admin.ref(path).once('value'));
  await assertFails(reporter.ref(path).update({ status: 'HIDDEN' }));
  await assertSucceeds(admin.ref(path).update({
    status: 'HIDDEN',
    resolverUid: 'rules-admin',
    resolvedAt: nowMs(),
    resolutionNote: 'Hidden during review',
  }));
});

test('admin-only report resolution receipts reject regular clients', async () => {
  const admin = adminDb();
  const reporter = dbFor('reporter1');
  const resolutionPath = 'community_report_resolutions/report1';
  const resolution = {
    reportId: 'report1',
    status: 'DISMISSED',
    resolverUid: 'rules-admin',
    resolvedAt: nowMs(),
    note: 'No policy issue found',
  };

  await assertFails(reporter.ref(resolutionPath).set(resolution));
  await assertSucceeds(admin.ref(resolutionPath).set(resolution));
  await assertFails(reporter.ref(resolutionPath).once('value'));
  await assertSucceeds(admin.ref(resolutionPath).once('value'));
});

test('admin-only takedown receipts must match current upload deletion handles', async () => {
  const admin = adminDb();
  const reporter = dbFor('reporter1');
  const receiptPath = 'community_takedown_receipts/report1';
  const receipt = takedownReceiptPayload();

  await seed('community_sounds/sound1', soundMetadata('sound-owner', {
    storagePath: 'sounds/sound-owner/sound1.mp3',
  }));
  await assertFails(reporter.ref(receiptPath).set(receipt));
  await assertFails(admin.ref(receiptPath).set({ ...receipt, reason: 'SPAM' }));
  await assertFails(admin.ref(receiptPath).set({ ...receipt, status: 'DISMISSED' }));
  await assertFails(admin.ref(receiptPath).set({
    ...receipt,
    storagePath: 'sounds/sound-owner/stale.mp3',
  }));
  await assertFails(admin.ref(receiptPath).set({
    ...receipt,
    metadataPath: '/community_wallpapers/sound1',
  }));
  await assertSucceeds(admin.ref(receiptPath).set(receipt));
  await assertFails(reporter.ref(receiptPath).once('value'));
  await assertSucceeds(admin.ref(receiptPath).once('value'));

  const deleteReceiptPath = 'community_takedown_receipts/delete1';
  await assertFails(admin.ref(deleteReceiptPath).set(takedownReceiptPayload({
    reportId: 'delete1',
    action: 'DELETE',
  })));
  await assertSucceeds(admin.ref(deleteReceiptPath).set(takedownReceiptPayload({
    reportId: 'delete1',
    action: 'DELETE',
    deleteState: 'STARTED',
  })));
  await assertSucceeds(admin.ref('community_sounds/sound1').remove());
  await assertSucceeds(admin.ref(deleteReceiptPath).update({
    deleteState: 'SUCCEEDED',
    deletedAt: nowMs(),
    storageDeleted: true,
    metadataDeleted: true,
  }));
  await assertFails(admin.ref('community_takedown_receipts/delete2').set(takedownReceiptPayload({
    reportId: 'delete2',
    action: 'DELETE',
    deleteState: 'STARTED',
  })));

  await seed('community_wallpapers/wall1', wallpaperMetadata('wall-owner', {
    storagePath: 'wallpapers/wall-owner/wall1.jpg',
  }));
  await assertSucceeds(admin.ref('community_takedown_receipts/report2').set(takedownReceiptPayload({
    reportId: 'report2',
    contentId: 'WALLPAPER::COMMUNITY::cw_wall1',
    contentType: 'WALLPAPER',
    uploadId: 'wall1',
    metadataPath: '/community_wallpapers/wall1',
    storagePath: 'wallpapers/wall-owner/wall1.jpg',
    uploaderUid: 'wall-owner',
  })));
});

test('community quota and dedupe ledgers are admin-only', async () => {
  const user = dbFor('quota-user');
  const admin = adminDb();
  const quotaPath = `community_write_quotas/quota-user/${DAY_KEY}/reports`;
  const dedupePath = 'community_write_dedupe/quota-user/reports/cu_sound_reported';

  await assertFails(user.ref(quotaPath).set(quotaPayload()));
  await assertFails(user.ref(dedupePath).set(dedupePayload()));
  await assertSucceeds(admin.ref(quotaPath).set(quotaPayload()));
  await assertSucceeds(admin.ref(dedupePath).set(dedupePayload()));
  await assertFails(user.ref(quotaPath).once('value'));
  await assertSucceeds(admin.ref(quotaPath).once('value'));
});

test('quota ledgers hold pending reservations and settlement counters in a fixed shape', async () => {
  const user = dbFor('quota-user');
  const admin = adminDb();
  const quotaPath = `community_write_quotas/quota-user/${DAY_KEY}/sound_uploads`;
  const time = nowMs();
  const settled = quotaPayload({
    pending: { 'sound_upload_9f2c': { at: time, prevLastAt: time - 1000 } },
    releasedCount: 1,
    lastReleasedAt: time,
    expiredCount: 2,
  });

  await assertFails(user.ref(quotaPath).set(settled));
  await assertSucceeds(admin.ref(quotaPath).set(settled));
  await assertSucceeds(admin.ref(`${quotaPath}/pending/takeover_op`).set({ at: time, cooldownAt: time - 5000 }));
  await assertFails(admin.ref(`${quotaPath}/pending/no_stamp`).set({ prevLastAt: time }));
  await assertFails(admin.ref(`${quotaPath}/pending/extra_field`).set({ at: time, uid: 'someone' }));
  await assertFails(admin.ref(`${quotaPath}/releasedCount`).set(-1));
});

test('community user block lists are callable-owned, private, and maintain an admin reverse index', async () => {
  const blocker = dbFor('blocker1');
  const blocked = dbFor('blocked1');
  const other = dbFor('block-other');
  const admin = adminDb();
  const listPath = 'community_user_blocks/blocker1/blocked1';
  const reversePath = 'community_blocked_by/blocked1/blocker1';
  const payload = userBlockPayload({ blockerUid: 'blocker1', blockedUid: 'blocked1' });

  await assertFails(unauthenticatedDb().ref(listPath).set(payload));
  await assertFails(other.ref(listPath).set(payload));
  await assertFails(blocker.ref(listPath).set(payload));
  await assertSucceeds(admin.ref(listPath).set(payload));
  await assertSucceeds(blocker.ref(listPath).once('value'));
  await assertFails(blocked.ref(listPath).once('value'));
  await assertSucceeds(admin.ref(listPath).once('value'));

  await assertFails(admin.ref('community_user_blocks/blocker1/blocked-mismatch').set(payload));
  await assertFails(admin.ref('community_user_blocks/blocker1/blocker1').set(
    userBlockPayload({ blockerUid: 'blocker1', blockedUid: 'blocker1' }),
  ));

  await assertFails(blocker.ref(reversePath).set(payload));
  await assertSucceeds(admin.ref(reversePath).set(payload));
  await assertFails(blocker.ref(reversePath).once('value'));
  await assertFails(blocked.ref(reversePath).once('value'));
  await assertSucceeds(admin.ref(reversePath).once('value'));
  await assertFails(other.ref(reversePath).set(payload));

  await assertFails(blocker.ref(listPath).remove());
  await assertFails(blocker.ref(reversePath).remove());
  await assertSucceeds(admin.ref(listPath).remove());
  await assertSucceeds(admin.ref(reversePath).remove());
});

test('collection shares are callable-written, readable for 30 days, and removable by their owner', async () => {
  const owner = dbFor('collection-owner');
  const other = dbFor('collection-other');
  const admin = adminDb();
  const anonymous = unauthenticatedDb();
  const path = 'shared_collections/token12345';
  const now = Date.now();
  const thirtyOneDays = 31 * 24 * 60 * 60 * 1000;

  // Clients can't write shares directly, owners included.
  await assertFails(owner.ref(path).set(collectionPayload('collection-owner')));
  await assertFails(anonymous.ref('shared_collections/anon12345').set(collectionPayload('collection-owner')));
  await assertFails(owner.ref('collection_shares/legacy12345').set(collectionPayload('collection-owner')));

  await assertSucceeds(admin.ref(path).set(collectionPayload('collection-owner', { expiresAt: now + 60_000 })));
  await assertSucceeds(anonymous.ref(path).once('value'));
  await assertSucceeds(anonymous.ref(`${path}/payload`).once('value'));
  await assertSucceeds(anonymous.ref('shared_collections/missing12345/payload').once('value'));
  await assertFails(anonymous.ref('shared_collections').once('value'));
  await assertFails(owner.ref(path).update({ collectionName: 'Evening Set' }));
  await assertFails(other.ref(path).remove());

  await assertFails(admin.ref('shared_collections/oversize1').set(
    collectionPayload('collection-owner', { payload: 'x'.repeat(MAX_COLLECTION_PAYLOAD_BYTES + 1) }),
  ));
  await assertFails(admin.ref('shared_collections/extra12345').set(
    collectionPayload('collection-owner', { ownerEmail: 'someone@example.com' }),
  ));
  await assertFails(admin.ref('shared_collections/longlife123').set(
    collectionPayload('collection-owner', { expiresAt: now + thirtyOneDays }),
  ));

  // A share stops resolving at expiresAt, and an older share without one after 30 days.
  await seed('shared_collections/expired12345', collectionPayload('collection-owner', {
    createdAt: now - thirtyOneDays,
    expiresAt: now - 1_000,
  }));
  await seed('shared_collections/legacyold123', collectionPayload('collection-owner', { createdAt: now - thirtyOneDays }));
  await seed('shared_collections/legacynew123', collectionPayload('collection-owner'));
  await assertFails(anonymous.ref('shared_collections/expired12345/payload').once('value'));
  await assertFails(anonymous.ref('shared_collections/legacyold123/payload').once('value'));
  await assertSucceeds(anonymous.ref('shared_collections/legacynew123/payload').once('value'));

  await assertSucceeds(owner.ref(path).remove());
});

test('test environment initialized database rules', () => {
  assert.ok(testEnv);
});
