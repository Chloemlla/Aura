import { after, before, beforeEach, test } from 'node:test';
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

import {
  SHARED_COLLECTION_TTL_MILLIS,
  deleteExpiredSharedCollections,
  publishSharedCollectionHandler,
} from '../../functions/lib/collectionShareHandler.js';

const PROJECT_ID = 'aura-rules-test';
const SHARER_UID = 'sharer-emulator';
const requireFromFunctions = createRequire(new URL('../../functions/package.json', import.meta.url));
const { getApps, initializeApp, deleteApp } = requireFromFunctions('firebase-admin/app');
const { getDatabase } = requireFromFunctions('firebase-admin/database');

let app;

before(async () => {
  assert.ok(
    process.env.FIREBASE_DATABASE_EMULATOR_HOST,
    'functions collection share emulator test must run under firebase emulators:exec --only database',
  );
  app = getApps()[0] ?? initializeApp({
    projectId: PROJECT_ID,
    databaseURL: `https://${PROJECT_ID}.firebaseio.com`,
  });
  // The prune query needs the repo's createdAt index, which this namespace only has once loaded.
  const response = await fetch(
    `http://${process.env.FIREBASE_DATABASE_EMULATOR_HOST}/.settings/rules.json?ns=${PROJECT_ID}`,
    {
      method: 'PUT',
      headers: { Authorization: 'Bearer owner' },
      body: readFileSync('database.rules.json', 'utf8'),
    },
  );
  assert.equal(response.status, 200, await response.text());
});

beforeEach(async () => {
  await getDatabase(app).ref().set(null);
});

after(async () => {
  if (app) {
    await deleteApp(app);
  }
});

function collectionDocument(itemCount = 1) {
  return JSON.stringify({
    version: 1,
    exportedAt: Date.now() - 5_000,
    collectionName: 'Evening',
    items: Array.from({ length: itemCount }, (_, index) => ({
      wallpaperId: `wall-${index}`,
      source: 'PEXELS',
      thumbnailUrl: `https://example.com/thumb-${index}.jpg`,
      fullUrl: `https://example.com/full-${index}.jpg`,
      width: 1440,
      height: 2560,
    })),
  });
}

function validRequest(operationId = `collection_share_${Date.now()}_${Math.random()}`) {
  return {
    auth: { uid: SHARER_UID },
    app: { appId: 'aura-emulator-test' },
    data: {
      operationId,
      clientSentAt: Date.now() - 1_000,
      payload: {
        version: 1,
        document: collectionDocument(3),
        collectionName: 'Evening Set',
      },
    },
  };
}

async function readValue(path) {
  return (await getDatabase(app).ref(path).get()).val();
}

test('collection share handler stores the share, quota, and dedupe rows in the database emulator', async () => {
  const before = Date.now();
  const request = validRequest('collection_share_emulator_1');
  const result = await publishSharedCollectionHandler(request);

  assert.equal(result.status, 'accepted');
  assert.match(result.token, /^[0-9a-f]{32}$/);
  assert.equal(result.targetPath, `/shared_collections/${result.token}`);

  const share = await readValue(`shared_collections/${result.token}`);
  assert.equal(share.version, 1);
  assert.equal(share.payload, request.data.payload.document);
  assert.equal(share.collectionName, 'Evening Set');
  assert.equal(share.itemCount, 3);
  assert.equal(share.createdByUid, SHARER_UID);
  assert.ok(share.createdAt >= before);
  assert.equal(share.expiresAt, share.createdAt + SHARED_COLLECTION_TTL_MILLIS);
  assert.equal(result.expiresAt, share.expiresAt);

  const quotas = await readValue(`community_write_quotas/${SHARER_UID}`);
  const day = Object.keys(quotas)[0];
  assert.equal(quotas[day].collection_shares.count, 1);
  assert.equal(quotas[day].collection_shares.pending, undefined);

  const dedupe = await readValue(`community_write_dedupe/${SHARER_UID}/collection_shares/collection_share_emulator_1`);
  assert.equal(dedupe.targetPath, result.targetPath);

  const replay = await publishSharedCollectionHandler(validRequest('collection_share_emulator_1'));
  assert.equal(replay.status, 'duplicate');
  assert.equal(replay.token, result.token);
  assert.equal(Object.keys(await readValue('shared_collections')).length, 1);
});

test('a second share inside the cooldown is refused by the emulator quota ledger', async () => {
  await publishSharedCollectionHandler(validRequest());
  await assert.rejects(
    () => publishSharedCollectionHandler(validRequest()),
    (error) => {
      assert.equal(error.code, 'resource-exhausted');
      assert.equal(error.details.reason, 'cooldown');
      return true;
    },
  );
  assert.equal(Object.keys(await readValue('shared_collections')).length, 1);
});

test('the prune job deletes shares past their 30 days and keeps live ones', async () => {
  const now = Date.now();
  const db = getDatabase(app);
  const row = (createdAt, extra = {}) => ({
    version: 1,
    payload: collectionDocument(),
    collectionName: 'Evening',
    itemCount: 1,
    createdAt,
    createdByUid: SHARER_UID,
    ...extra,
  });
  await db.ref('shared_collections').set({
    expired1: row(now - SHARED_COLLECTION_TTL_MILLIS - 1_000, { expiresAt: now - 1_000 }),
    expired2: row(now - SHARED_COLLECTION_TTL_MILLIS - 2_000, { expiresAt: now - 2_000 }),
    legacyOld: row(now - SHARED_COLLECTION_TTL_MILLIS - 3_000),
    live: row(now - 60_000, { expiresAt: now - 60_000 + SHARED_COLLECTION_TTL_MILLIS }),
    legacyLive: row(now - 60_000),
  });

  const deleted = await deleteExpiredSharedCollections(db.ref(), now, 2);

  assert.equal(deleted, 3);
  assert.deepEqual(Object.keys(await readValue('shared_collections')).sort(), ['legacyLive', 'live']);
  assert.equal(await deleteExpiredSharedCollections(db.ref(), now), 0);
});
