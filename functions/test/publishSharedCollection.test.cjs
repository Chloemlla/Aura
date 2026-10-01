const assert = require("node:assert/strict");
const test = require("node:test");

const { buildDedupeMarker, evaluateCommunityQuotaAttempt, settledQuotaState } = require("../lib/quotaEngine.js");
const {
  MAX_SHARED_COLLECTION_DOCUMENT_BYTES,
  MAX_SHARED_COLLECTION_ITEMS,
  SHARED_COLLECTION_TTL_MILLIS,
  normalizeSharedCollectionPayload,
  publishSharedCollectionHandler,
} = require("../lib/collectionShareHandler.js");

const NOW = Date.UTC(2026, 8, 30, 12, 0, 0);

function collectionDocument(itemCount = 2, overrides = {}) {
  return JSON.stringify({
    version: 1,
    exportedAt: NOW - 5_000,
    collectionName: "Evening",
    items: Array.from({ length: itemCount }, (_, index) => ({
      wallpaperId: `wall-${index}`,
      source: "PEXELS",
      thumbnailUrl: `https://example.com/thumb-${index}.jpg`,
      fullUrl: `https://example.com/full-${index}.jpg`,
      width: 1440,
      height: 2560,
    })),
    ...overrides,
  });
}

function validRequest(overrides = {}) {
  return {
    auth: { uid: "sharer1" },
    app: { appId: "aura-test-app" },
    data: {
      operationId: "collection_share_op-1",
      clientSentAt: NOW - 1_000,
      payload: {
        version: 1,
        document: collectionDocument(),
        collectionName: "  Evening   Set ",
        ...overrides,
      },
    },
  };
}

class FakeSharedCollectionBackend {
  constructor(nowMillis = NOW) {
    this.now = nowMillis;
    this.dedupe = new Map();
    this.quotas = new Map();
    this.settlements = [];
    this.commitFailure = null;
    this.shares = new Map();
    this.tokens = 0;
  }

  nowMillis() {
    return this.now;
  }

  createShareToken() {
    this.tokens += 1;
    return `token${this.tokens}`;
  }

  async readDedupeMarker(uid, surfaceKey, dedupeKey) {
    return this.dedupe.get(`${uid}/${surfaceKey}/${dedupeKey}`) ?? null;
  }

  async reserveQuota(uid, dayKey, surface, nowMillis, dedupe, operationKey) {
    const key = `${uid}/${dayKey}/${surface.surfaceKey}`;
    const decision = evaluateCommunityQuotaAttempt({
      surface,
      nowMillis,
      quota: this.quotas.get(key) ?? {},
      dedupe,
      operationKey,
    });
    if (decision.status !== "duplicate") {
      this.quotas.set(key, decision.quota);
    }
    return decision;
  }

  async settleQuota(uid, dayKey, surface, reservation, settlement) {
    this.settlements.push(settlement);
    const key = `${uid}/${dayKey}/${surface.surfaceKey}`;
    const next = settledQuotaState(this.quotas.get(key) ?? {}, reservation, settlement, this.now);
    if (next !== null) this.quotas.set(key, next);
  }

  async commitShare(input) {
    if (this.commitFailure) throw this.commitFailure;
    this.shares.set(input.token, input.record);
    this.dedupe.set(`${input.uid}/${input.surfaceKey}/${input.dedupeKey}`, input.dedupeMarker);
  }
}

test("accepted share stores a server-owned record that expires in 30 days", async () => {
  const backend = new FakeSharedCollectionBackend();
  const result = await publishSharedCollectionHandler(validRequest(), backend);

  assert.deepEqual(result, {
    operationId: "collection_share_op-1",
    status: "accepted",
    token: "token1",
    targetPath: "/shared_collections/token1",
    expiresAt: NOW + SHARED_COLLECTION_TTL_MILLIS,
    serverTimeMillis: NOW,
  });
  assert.deepEqual(backend.shares.get("token1"), {
    version: 1,
    payload: collectionDocument(),
    collectionName: "Evening Set",
    itemCount: 2,
    createdAt: NOW,
    expiresAt: NOW + 30 * 24 * 60 * 60 * 1_000,
    createdByUid: "sharer1",
  });
  const quota = backend.quotas.get("sharer1/20260930/collection_shares");
  assert.equal(quota.count, 1);
  assert.equal(quota.pending, undefined);
  assert.deepEqual(backend.settlements, ["finalized"]);
  assert.equal(
    backend.dedupe.get("sharer1/collection_shares/collection_share_op-1").targetPath,
    "/shared_collections/token1",
  );
});

test("a retried share operation returns the first link without a second write", async () => {
  const backend = new FakeSharedCollectionBackend();
  backend.dedupe.set(
    "sharer1/collection_shares/collection_share_op-1",
    buildDedupeMarker({ nowMillis: NOW - 1_000, targetPath: "/shared_collections/earlier" }),
  );

  const result = await publishSharedCollectionHandler(validRequest(), backend);

  assert.equal(result.status, "duplicate");
  assert.equal(result.token, "earlier");
  assert.equal(result.targetPath, "/shared_collections/earlier");
  assert.equal(backend.shares.size, 0);
  assert.equal(backend.tokens, 0);
});

test("cooldown and daily limit refuse a share before anything is written", async () => {
  const cooldownBackend = new FakeSharedCollectionBackend();
  cooldownBackend.quotas.set("sharer1/20260930/collection_shares", { count: 1, lastAt: NOW - 10_000 });
  await assert.rejects(
    () => publishSharedCollectionHandler(validRequest(), cooldownBackend),
    (error) => {
      assert.equal(error.code, "resource-exhausted");
      assert.equal(error.details.reason, "cooldown");
      assert.equal(error.details.retryAfterMillis, 20_000);
      return true;
    },
  );
  assert.equal(cooldownBackend.shares.size, 0);

  const limitBackend = new FakeSharedCollectionBackend();
  limitBackend.quotas.set("sharer1/20260930/collection_shares", { count: 10, lastAt: NOW - 60_000 });
  await assert.rejects(
    () => publishSharedCollectionHandler(validRequest(), limitBackend),
    (error) => {
      assert.equal(error.code, "resource-exhausted");
      assert.equal(error.details.reason, "daily-limit");
      return true;
    },
  );
  assert.equal(limitBackend.shares.size, 0);
});

test("share callable requires Firebase Auth and App Check", async () => {
  const backend = new FakeSharedCollectionBackend();
  await assert.rejects(
    () => publishSharedCollectionHandler({ ...validRequest(), auth: undefined }, backend),
    { code: "unauthenticated" },
  );
  await assert.rejects(
    () => publishSharedCollectionHandler({ ...validRequest(), app: undefined }, backend),
    { code: "failed-precondition" },
  );
  assert.equal(backend.quotas.size, 0);
});

test("share payload refuses server fields and documents over the link limits", () => {
  for (const field of ["createdByUid", "createdAt", "expiresAt", "token", "itemCount"]) {
    assert.throws(
      () => normalizeSharedCollectionPayload({ version: 1, document: collectionDocument(), [field]: "x" }),
      (error) => error.code === "invalid-argument" && error.details.field === field,
      field,
    );
  }
  assert.throws(
    () => normalizeSharedCollectionPayload({ version: 2, document: collectionDocument() }),
    { code: "invalid-argument" },
  );
  assert.throws(
    () => normalizeSharedCollectionPayload({ version: 1, document: "not json" }),
    { code: "invalid-argument" },
  );
  assert.throws(
    () => normalizeSharedCollectionPayload({ version: 1, document: collectionDocument(0) }),
    { code: "invalid-argument" },
  );
  assert.throws(
    () => normalizeSharedCollectionPayload({ version: 1, document: collectionDocument(1, { version: 3 }) }),
    { code: "invalid-argument" },
  );
  assert.throws(
    () => normalizeSharedCollectionPayload({ version: 1, document: collectionDocument(1, { items: ["x"] }) }),
    { code: "invalid-argument" },
  );
  assert.throws(
    () => normalizeSharedCollectionPayload({
      version: 1,
      document: collectionDocument(MAX_SHARED_COLLECTION_ITEMS + 1),
    }),
    { code: "invalid-argument" },
  );
  assert.equal(
    normalizeSharedCollectionPayload({ version: 1, document: collectionDocument(MAX_SHARED_COLLECTION_ITEMS) })
      .itemCount,
    MAX_SHARED_COLLECTION_ITEMS,
  );

  // The cap counts UTF-8 bytes, so a short string of wide characters can still be too big.
  const padding = "é".repeat(MAX_SHARED_COLLECTION_DOCUMENT_BYTES / 2);
  assert.throws(
    () => normalizeSharedCollectionPayload({ version: 1, document: collectionDocument(1, { note: padding }) }),
    (error) => error.code === "invalid-argument" && /too large/.test(error.message),
  );
});

test("share name comes from the request, then the document, then a default", () => {
  assert.equal(
    normalizeSharedCollectionPayload({ version: 1, document: collectionDocument(), collectionName: " A\u0000  B " })
      .collectionName,
    "A B",
  );
  assert.equal(
    normalizeSharedCollectionPayload({ version: 1, document: collectionDocument() }).collectionName,
    "Evening",
  );
  assert.equal(
    normalizeSharedCollectionPayload({ version: 1, document: collectionDocument(1, { collectionName: "   " }) })
      .collectionName,
    "Shared collection",
  );
  assert.equal(
    normalizeSharedCollectionPayload({ version: 1, document: collectionDocument(), collectionName: "x".repeat(200) })
      .collectionName.length,
    80,
  );
});

test("a share whose write fails refunds its quota unit and cooldown", async () => {
  const backend = new FakeSharedCollectionBackend();
  backend.commitFailure = new Error("database unavailable");
  await assert.rejects(() => publishSharedCollectionHandler(validRequest(), backend), /database unavailable/);

  const refunded = backend.quotas.get("sharer1/20260930/collection_shares");
  assert.deepEqual(backend.settlements, ["released"]);
  assert.equal(refunded.count, 0);
  assert.equal(refunded.lastAt, undefined);
  assert.equal(refunded.pending, undefined);

  backend.commitFailure = null;
  backend.now = NOW + 1;
  const retry = validRequest();
  const result = await publishSharedCollectionHandler(
    { ...retry, data: { ...retry.data, operationId: "collection_share_retry" } },
    backend,
  );
  assert.equal(result.status, "accepted");
  assert.deepEqual(backend.settlements, ["released", "finalized"]);
});
