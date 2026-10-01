const assert = require("node:assert/strict");
const test = require("node:test");

const { surfaceByKey } = require("../lib/communityContract.js");
const {
  PENDING_RESERVATION_LEASE_MILLIS,
  buildDedupeMarker,
  evaluateCommunityQuotaAttempt,
  millisUntilNextUtcDay,
  quotaOperationKey,
  settledQuotaState,
  utcQuotaDayKey,
} = require("../lib/quotaEngine.js");

const reports = surfaceByKey("reports");

test("an accepted attempt with an operation key holds the unit as pending", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const earlier = now - reports.minIntervalMillis;
  const decision = evaluateCommunityQuotaAttempt({
    surface: reports,
    nowMillis: now,
    quota: { count: 2, firstAt: now - 60_000, lastAt: earlier },
    operationKey: "report_op-1",
  });

  assert.equal(decision.status, "accepted");
  assert.equal(decision.quota.count, 3);
  assert.equal(decision.quota.lastAt, now);
  assert.deepEqual(decision.quota.pending, { "report_op-1": { at: now, prevLastAt: earlier } });
  assert.deepEqual(decision.reservation, { operationKey: "report_op-1", reservedAt: now });
});

test("a first-ever reservation leaves prevLastAt out instead of storing undefined", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const decision = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: now, operationKey: "op" });

  assert.deepEqual(decision.quota.pending, { op: { at: now } });
  assert.equal(Object.keys(decision.quota.pending.op).includes("prevLastAt"), false);
});

test("a finalized reservation keeps the unit and drops only its pending entry", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const accepted = evaluateCommunityQuotaAttempt({
    surface: reports,
    nowMillis: now,
    quota: { count: 1, firstAt: now - 600_000, lastAt: now - 600_000 },
    operationKey: "op",
  });

  const settled = settledQuotaState(accepted.quota, accepted.reservation, "finalized", now + 500);

  assert.deepEqual(settled, { count: 2, firstAt: now - 600_000, lastAt: now });
});

test("a released reservation refunds the unit and the cooldown it started", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const before = { count: 1, firstAt: now - 600_000, lastAt: now - 600_000 };
  const accepted = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: now, quota: before, operationKey: "op" });

  const released = settledQuotaState(accepted.quota, accepted.reservation, "released", now + 500);

  assert.deepEqual(released, { ...before, releasedCount: 1, lastReleasedAt: now + 500 });
  // A retry straight away is accepted: no cooldown, and the day still has the refunded unit.
  const retry = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: now + 1_000, quota: released, operationKey: "op2" });
  assert.equal(retry.status, "accepted");
  assert.equal(retry.quota.count, 2);
});

test("releasing the very first reservation of the day clears lastAt entirely", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const accepted = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: now, operationKey: "op" });

  const released = settledQuotaState(accepted.quota, accepted.reservation, "released", now + 10);

  assert.equal(released.count, 0);
  assert.equal("lastAt" in released, false);
  assert.equal("pending" in released, false);
});

test("a release never rolls back a cooldown a newer reservation started", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const later = now + reports.minIntervalMillis;
  const first = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: now, operationKey: "a" });
  const second = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: later, quota: first.quota, operationKey: "b" });

  const released = settledQuotaState(second.quota, first.reservation, "released", later + 10);

  assert.equal(released.count, 1);
  assert.equal(released.lastAt, later);
  assert.deepEqual(Object.keys(released.pending), ["b"]);
});

test("settling a reservation the ledger no longer holds changes nothing", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const accepted = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: now, operationKey: "op" });
  const finalized = settledQuotaState(accepted.quota, accepted.reservation, "finalized", now + 1);

  assert.equal(settledQuotaState(finalized, accepted.reservation, "released", now + 2), null);
  assert.equal(settledQuotaState(accepted.quota, { operationKey: "op", reservedAt: now - 1 }, "released", now + 2), null);
});

test("a replay of a running operation is held off without taking a second unit", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const accepted = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: now, operationKey: "op" });

  const replay = evaluateCommunityQuotaAttempt({
    surface: reports,
    nowMillis: now + 10_000,
    quota: accepted.quota,
    operationKey: "op",
  });

  assert.equal(replay.status, "blocked");
  assert.equal(replay.reason, "in-progress");
  assert.equal(replay.retryAfterMillis, PENDING_RESERVATION_LEASE_MILLIS - 10_000);
  assert.equal(replay.quota.count, 1);
});

test("a replay after the lease takes over the dead run's unit instead of paying again", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const accepted = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: now, operationKey: "op" });
  const replayAt = now + PENDING_RESERVATION_LEASE_MILLIS;

  const replay = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: replayAt, quota: accepted.quota, operationKey: "op" });

  assert.equal(replay.status, "accepted");
  assert.equal(replay.quota.count, 1);
  assert.deepEqual(replay.reservation, { operationKey: "op", reservedAt: replayAt });
  assert.deepEqual(replay.quota.pending, { op: { at: replayAt, cooldownAt: now } });
  // The takeover settles like any reservation; the dead run's stamp no longer matches.
  assert.equal(settledQuotaState(replay.quota, accepted.reservation, "released", replayAt + 1), null);
  const released = settledQuotaState(replay.quota, replay.reservation, "released", replayAt + 1);
  assert.equal(released.count, 0);
  // The cooldown the dead run started is refunded with the unit.
  assert.equal("lastAt" in released, false);
});

test("a replay just after UTC midnight still waits for a run that started before it", () => {
  const started = Date.UTC(2026, 5, 7, 23, 59, 50);
  const yesterday = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: started, operationKey: "op" });
  const replayAt = Date.UTC(2026, 5, 8, 0, 0, 5);

  const replay = evaluateCommunityQuotaAttempt({
    surface: reports,
    nowMillis: replayAt,
    quota: {},
    previousDayQuota: yesterday.quota,
    operationKey: "op",
  });

  assert.equal(replay.status, "blocked");
  assert.equal(replay.reason, "in-progress");
  assert.equal(replay.retryAfterMillis, PENDING_RESERVATION_LEASE_MILLIS - 15_000);
  assert.equal(replay.quota.count ?? 0, 0);

  // Once yesterday's run is past its lease the replay goes ahead in today's ledger.
  const late = evaluateCommunityQuotaAttempt({
    surface: reports,
    nowMillis: started + PENDING_RESERVATION_LEASE_MILLIS,
    quota: {},
    previousDayQuota: yesterday.quota,
    operationKey: "op",
  });
  assert.equal(late.status, "accepted");
});

test("the cooldown from just before UTC midnight still applies just after it", () => {
  const votes = surfaceByKey("votes");
  const lastAt = Date.UTC(2026, 5, 7, 23, 59, 59);
  const nowMillis = lastAt + 1_000;

  const decision = evaluateCommunityQuotaAttempt({
    surface: votes,
    nowMillis,
    quota: {},
    previousDayQuota: { count: 4, lastAt },
    operationKey: "next",
  });

  assert.equal(decision.status, "blocked");
  assert.equal(decision.reason, "cooldown");
  assert.equal(decision.retryAfterMillis, votes.minIntervalMillis - 1_000);
});

test("other operations' stale reservations expire as spent on the next attempt", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const dead = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: now, operationKey: "dead" });
  const nextAt = now + PENDING_RESERVATION_LEASE_MILLIS;

  const next = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: nextAt, quota: dead.quota, operationKey: "fresh" });

  assert.equal(next.status, "accepted");
  assert.equal(next.quota.count, 2);
  assert.equal(next.quota.expiredCount, 1);
  assert.deepEqual(Object.keys(next.quota.pending), ["fresh"]);
});

test("a blocked attempt still writes back the expired reservations", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const quota = { count: reports.dailyLimit, lastAt: now - 1, pending: { dead: { at: now - PENDING_RESERVATION_LEASE_MILLIS } } };

  const decision = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: now, quota, operationKey: "op" });

  assert.equal(decision.status, "blocked");
  assert.equal(decision.quota.pending, undefined);
  assert.equal(decision.quota.expiredCount, 1);
  assert.equal(decision.quota.count, reports.dailyLimit);
});

test("operation keys are safe Realtime Database keys", () => {
  assert.equal(quotaOperationKey("vote_1b2c"), "vote_1b2c");
  assert.equal(quotaOperationKey("a.b#c$d[e]f/g\u0001h"), "a_b_c_d_e_f_g_h");
  assert.equal(quotaOperationKey("x".repeat(200)).length, 120);
  assert.throws(() => quotaOperationKey(""));
});

test("accepted quota attempt increments count and sets timestamps", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const decision = evaluateCommunityQuotaAttempt({
    surface: reports,
    nowMillis: now,
    quota: { count: 2, firstAt: now - 60_000, lastAt: now - reports.minIntervalMillis },
  });

  assert.equal(decision.status, "accepted");
  assert.equal(decision.quota.count, 3);
  assert.equal(decision.quota.firstAt, now - 60_000);
  assert.equal(decision.quota.lastAt, now);
});

test("active dedupe marker returns duplicate before quota mutation", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const marker = buildDedupeMarker({
    nowMillis: now - 1_000,
    targetPath: "/community_reports/report1",
    ttlMillis: 5_000,
  });
  const decision = evaluateCommunityQuotaAttempt({
    surface: reports,
    nowMillis: now,
    quota: { count: reports.dailyLimit },
    dedupe: marker,
  });

  assert.equal(decision.status, "duplicate");
  assert.equal(decision.code, "already-exists");
  assert.equal(decision.targetPath, "/community_reports/report1");
  assert.equal(decision.retryAfterMillis, 4_000);
});

test("cooldown blocks recent accepted attempts", () => {
  const now = Date.UTC(2026, 5, 7, 12, 0, 0);
  const decision = evaluateCommunityQuotaAttempt({
    surface: reports,
    nowMillis: now,
    quota: { count: 1, lastAt: now - 30_000, blockedCount: 2 },
  });

  assert.equal(decision.status, "blocked");
  assert.equal(decision.reason, "cooldown");
  assert.equal(decision.retryAfterMillis, reports.minIntervalMillis - 30_000);
  assert.equal(decision.quota.blockedCount, 3);
  assert.equal(decision.quota.lastBlockedAt, now);
});

test("daily limit blocks until the next UTC day boundary", () => {
  const now = Date.UTC(2026, 5, 7, 23, 59, 58);
  const decision = evaluateCommunityQuotaAttempt({
    surface: reports,
    nowMillis: now,
    quota: { count: reports.dailyLimit, lastAt: now - reports.minIntervalMillis },
  });

  assert.equal(decision.status, "blocked");
  assert.equal(decision.reason, "daily-limit");
  assert.equal(decision.retryAfterMillis, 2_000);
});

test("UTC quota day key is stable across local timezone settings", () => {
  assert.equal(utcQuotaDayKey(Date.UTC(2026, 0, 1, 0, 0, 0)), "20260101");
  assert.equal(utcQuotaDayKey(Date.UTC(2026, 11, 31, 23, 59, 59)), "20261231");
  assert.equal(millisUntilNextUtcDay(Date.UTC(2026, 5, 7, 23, 59, 59, 500)), 500);
});
