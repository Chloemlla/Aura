const assert = require("node:assert/strict");
const test = require("node:test");

const { surfaceByKey } = require("../lib/communityContract.js");
const { evaluateCommunityQuotaAttempt, utcQuotaDayKey } = require("../lib/quotaEngine.js");
const { reserveQuotaLedger } = require("../lib/quotaReservation.js");

const reports = surfaceByKey("reports");

/** A Reference stand-in over a plain object, enough for reserveQuotaLedger. */
function fakeLedger(store) {
  const reads = [];
  const lookup = (path) => path.reduce((value, key) => (value == null ? undefined : value[key]), store);
  const node = (path) => ({
    child: (key) => node([...path, key]),
    async get() {
      reads.push(path.join("/"));
      return { val: () => lookup(path) ?? null };
    },
    async transaction(update) {
      const next = update(lookup(path) ?? null);
      if (next === undefined) return { committed: false };
      let parent = store;
      for (const key of path.slice(0, -1)) parent = parent[key] ??= {};
      parent[path[path.length - 1]] = next;
      return { committed: true };
    },
  });
  return { ref: node([]), reads };
}

test("a replay just after UTC midnight sees the pending reservation in yesterday's ledger", async () => {
  const started = Date.UTC(2026, 5, 7, 23, 59, 50);
  const yesterdayKey = utcQuotaDayKey(started);
  const yesterday = evaluateCommunityQuotaAttempt({ surface: reports, nowMillis: started, operationKey: "op" });
  const store = { [yesterdayKey]: { reports: yesterday.quota } };
  const { ref } = fakeLedger(store);
  const replayAt = Date.UTC(2026, 5, 8, 0, 0, 5);

  const decision = await reserveQuotaLedger(ref, utcQuotaDayKey(replayAt), reports, replayAt, null, "op");

  assert.equal(decision.status, "blocked");
  assert.equal(decision.reason, "in-progress");
  assert.equal(store[utcQuotaDayKey(replayAt)].reports.count ?? 0, 0);
  assert.deepEqual(store[yesterdayKey].reports, yesterday.quota);
});

test("away from midnight only today's ledger is touched", async () => {
  const nowMillis = Date.UTC(2026, 5, 8, 12, 0, 0);
  const todayKey = utcQuotaDayKey(nowMillis);
  const store = {};
  const { ref, reads } = fakeLedger(store);

  const decision = await reserveQuotaLedger(ref, todayKey, reports, nowMillis, null, "op");

  assert.equal(decision.status, "accepted");
  assert.deepEqual(reads, []);
  assert.equal(store[todayKey].reports.count, 1);
});
