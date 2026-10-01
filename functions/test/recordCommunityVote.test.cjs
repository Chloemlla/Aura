const assert = require("node:assert/strict");
const test = require("node:test");

const { buildDedupeMarker, evaluateCommunityQuotaAttempt, settledQuotaState } = require("../lib/quotaEngine.js");
const {
  communityUploadMetadataPath,
  normalizeVoteContentId,
  recordCommunityVoteHandler,
} = require("../lib/voteHandler.js");

const NOW = Date.UTC(2026, 5, 7, 12, 0, 0);

function validRequest(overrides = {}) {
  return {
    auth: { uid: "voter1" },
    app: { appId: "aura-test-app" },
    data: {
      operationId: "vote-op-1",
      clientSentAt: NOW - 1_000,
      payload: {
        contentId: "WALLPAPER::COMMUNITY::cw.one",
        ...overrides,
      },
    },
  };
}

class FakeVoteBackend {
  constructor(nowMillis = NOW) {
    this.now = nowMillis;
    this.dedupe = new Map();
    this.quotas = new Map();
    this.votes = new Map();
    this.legacyVoters = new Set();
    this.settlements = [];
    this.commitFailure = null;
    this.settleFailure = null;
    this.lostMarkerRace = false;
  }

  nowMillis() {
    return this.now;
  }

  async hasExistingVote(uid, contentId) {
    return this.votes.get(contentId)?.voters?.[uid] === true ||
      this.legacyVoters.has(`${contentId}/${uid}`);
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
    if (this.settleFailure) throw this.settleFailure;
    const key = `${uid}/${dayKey}/${surface.surfaceKey}`;
    const next = settledQuotaState(this.quotas.get(key) ?? {}, reservation, settlement, this.now);
    if (next !== null) this.quotas.set(key, next);
  }

  async commitVote(input) {
    if (this.commitFailure) throw this.commitFailure;
    // Another call from the same account claimed the private marker first.
    if (this.lostMarkerRace) return { status: "duplicate" };
    const existing = this.votes.get(input.contentId) ?? { upvotes: 0, voters: {} };
    if (existing.voters[input.uid] === true) {
      return { status: "duplicate" };
    }
    const next = {
      upvotes: existing.upvotes + 1,
      voters: {
        ...existing.voters,
        [input.uid]: true,
      },
    };
    this.votes.set(input.contentId, next);
    this.legacyVoters.add(`${input.contentId}/${input.uid}`);
    this.dedupe.set(`${input.uid}/${input.surfaceKey}/${input.dedupeKey}`, input.dedupeMarker);
    return {
      status: "accepted",
      upvotes: next.upvotes,
    };
  }
}

test("accepted vote increments tally and writes voter and dedupe markers", async () => {
  const backend = new FakeVoteBackend();
  const result = await recordCommunityVoteHandler(validRequest(), backend);

  assert.deepEqual(result, {
    operationId: "vote-op-1",
    status: "accepted",
    targetPath: "/vote_counts/WALLPAPER::COMMUNITY::cw_one",
    serverTimeMillis: NOW,
    upvotes: 1,
  });
  assert.equal(backend.votes.get("WALLPAPER::COMMUNITY::cw_one").voters.voter1, true);
  assert.equal(backend.legacyVoters.has("WALLPAPER::COMMUNITY::cw_one/voter1"), true);
  assert.equal(backend.quotas.get("voter1/20260607/votes").count, 1);
  assert.equal(
    backend.dedupe.get("voter1/votes/WALLPAPER::COMMUNITY::cw_one").targetPath,
    "/vote_counts/WALLPAPER::COMMUNITY::cw_one",
  );
});

test("an accepted vote keeps its unit and leaves no pending reservation", async () => {
  const backend = new FakeVoteBackend();
  await recordCommunityVoteHandler(validRequest(), backend);

  const quota = backend.quotas.get("voter1/20260607/votes");
  assert.deepEqual(backend.settlements, ["finalized"]);
  assert.equal(quota.count, 1);
  assert.equal(quota.lastAt, NOW);
  assert.equal(quota.pending, undefined);
});

test("a vote whose commit fails gives back its unit and cooldown and can retry at once", async () => {
  const backend = new FakeVoteBackend();
  backend.commitFailure = new Error("database unavailable");

  await assert.rejects(() => recordCommunityVoteHandler(validRequest(), backend), /database unavailable/);

  const refunded = backend.quotas.get("voter1/20260607/votes");
  assert.deepEqual(backend.settlements, ["released"]);
  assert.equal(refunded.count, 0);
  assert.equal(refunded.lastAt, undefined);
  assert.equal(refunded.releasedCount, 1);

  backend.commitFailure = null;
  backend.now = NOW + 1;
  const retry = await recordCommunityVoteHandler(
    { ...validRequest(), data: { ...validRequest().data, operationId: "vote-op-2" } },
    backend,
  );
  assert.equal(retry.status, "accepted");
  assert.equal(backend.quotas.get("voter1/20260607/votes").count, 1);
});

test("a vote that loses the marker race counts nothing and refunds its unit", async () => {
  const backend = new FakeVoteBackend();
  backend.lostMarkerRace = true;

  const result = await recordCommunityVoteHandler(validRequest(), backend);

  assert.equal(result.status, "duplicate");
  assert.deepEqual(backend.settlements, ["released"]);
  assert.equal(backend.quotas.get("voter1/20260607/votes").count, 0);
});

test("a failed settle never changes what the caller sees", async () => {
  const backend = new FakeVoteBackend();
  backend.settleFailure = new Error("ledger write failed");

  const result = await recordCommunityVoteHandler(validRequest(), backend);

  assert.equal(result.status, "accepted");
  assert.equal(result.upvotes, 1);

  // The commit's own error still reaches the caller when the refund cannot be written.
  backend.commitFailure = new Error("database unavailable");
  backend.now = NOW + 10_000;
  const second = validRequest({ contentId: "WALLPAPER::COMMUNITY::cw_two" });
  await assert.rejects(
    () => recordCommunityVoteHandler({ ...second, data: { ...second.data, operationId: "vote-op-2" } }, backend),
    /database unavailable/,
  );
});

test("a replay of a vote still in flight is held off without a second unit", async () => {
  const backend = new FakeVoteBackend();
  backend.settleFailure = new Error("process died before settling");
  await recordCommunityVoteHandler(validRequest(), backend);
  backend.settleFailure = null;
  backend.now = NOW + 30_000;

  await assert.rejects(
    () => recordCommunityVoteHandler(validRequest({ contentId: "WALLPAPER::COMMUNITY::cw_two" }), backend),
    (error) => {
      assert.equal(error.code, "resource-exhausted");
      assert.equal(error.details.reason, "in-progress");
      return true;
    },
  );
  assert.equal(backend.quotas.get("voter1/20260607/votes").count, 1);
});

test("existing voter marker returns duplicate before quota reservation", async () => {
  const backend = new FakeVoteBackend();
  backend.votes.set("WALLPAPER::COMMUNITY::cw_one", {
    upvotes: 3,
    voters: { voter1: true },
  });

  const result = await recordCommunityVoteHandler(validRequest(), backend);

  assert.equal(result.status, "duplicate");
  assert.equal(result.targetPath, "/vote_counts/WALLPAPER::COMMUNITY::cw_one");
  assert.equal(backend.quotas.size, 0);
  assert.equal(backend.votes.get("WALLPAPER::COMMUNITY::cw_one").upvotes, 3);
});

test("active dedupe marker returns duplicate before vote commit", async () => {
  const backend = new FakeVoteBackend();
  backend.dedupe.set(
    "voter1/votes/WALLPAPER::COMMUNITY::cw_one",
    buildDedupeMarker({
      nowMillis: NOW - 1_000,
      targetPath: "/votes/WALLPAPER::COMMUNITY::cw_one",
      ttlMillis: 5_000,
    }),
  );

  const result = await recordCommunityVoteHandler(validRequest(), backend);

  assert.equal(result.status, "duplicate");
  assert.equal(backend.votes.size, 0);
});

test("cooldown and daily-limit quota rejections do not commit votes", async () => {
  const cooldownBackend = new FakeVoteBackend();
  cooldownBackend.quotas.set("voter1/20260607/votes", {
    count: 1,
    lastAt: NOW - 1_000,
  });
  await assert.rejects(
    () => recordCommunityVoteHandler(validRequest(), cooldownBackend),
    (error) => {
      assert.equal(error.code, "resource-exhausted");
      assert.equal(error.details.reason, "cooldown");
      assert.equal(error.details.retryAfterMillis, 2_000);
      return true;
    },
  );
  assert.equal(cooldownBackend.votes.size, 0);

  const limitBackend = new FakeVoteBackend(Date.UTC(2026, 5, 7, 23, 59, 58));
  limitBackend.quotas.set("voter1/20260607/votes", {
    count: 100,
    lastAt: limitBackend.now - 3_000,
  });
  await assert.rejects(
    () => recordCommunityVoteHandler(validRequest(), limitBackend),
    (error) => {
      assert.equal(error.code, "resource-exhausted");
      assert.equal(error.details.reason, "daily-limit");
      assert.equal(error.details.retryAfterMillis, 2_000);
      return true;
    },
  );
  assert.equal(limitBackend.votes.size, 0);
});

test("callable identity requires Firebase Auth and App Check", async () => {
  const backend = new FakeVoteBackend();
  await assert.rejects(
    () => recordCommunityVoteHandler({ ...validRequest(), auth: undefined }, backend),
    { code: "unauthenticated" },
  );
  await assert.rejects(
    () => recordCommunityVoteHandler({ ...validRequest(), app: undefined }, backend),
    { code: "failed-precondition" },
  );
});

test("only community upload vote keys map to an upload metadata row", () => {
  assert.equal(communityUploadMetadataPath("SOUND::COMMUNITY::cu_-Nabc_12"), "community_sounds/-Nabc_12");
  assert.equal(communityUploadMetadataPath("WALLPAPER::COMMUNITY::cw_wall1"), "community_wallpapers/wall1");
  assert.equal(communityUploadMetadataPath("SOUND::COMMUNITY::cw_wall1"), null);
  assert.equal(communityUploadMetadataPath("WALLPAPER::WALLHAVEN::cw_wall1"), null);
  assert.equal(communityUploadMetadataPath("WALLPAPER::COMMUNITY::cw_a_b:c"), null);
  assert.equal(communityUploadMetadataPath("WALLPAPER::COMMUNITY::cw_"), null);
});

test("content IDs are sanitized and required", () => {
  assert.equal(normalizeVoteContentId(" a/b.c#d[e] "), "a_b_c_d_e_");
  assert.throws(
    () => normalizeVoteContentId("  "),
    { code: "invalid-argument" },
  );
});
