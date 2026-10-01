import type { CommunityCallableSurface } from "./communityContract";

const DAY_MILLIS = 24 * 60 * 60 * 1_000;
const DEDUPE_TTL_MILLIS = 7 * DAY_MILLIS;
const OPERATION_KEY_MAX = 120;
const FIREBASE_KEY_REGEX = /[.#$[\]/\u0000-\u001F\u007F]/g;

/**
 * A reservation older than this belongs to a run that died before settling it.
 * Callables time out after 60 seconds, so five minutes leaves a wide margin.
 */
export const PENDING_RESERVATION_LEASE_MILLIS = 5 * 60 * 1_000;

/** One unit taken for an operation whose protected write has not settled yet. */
export interface PendingReservation {
  readonly at: number;
  /** The ledger's lastAt before this reservation, restored when it is released. */
  readonly prevLastAt?: number;
  /** The lastAt this reservation wrote, when a takeover moved [at] past it. */
  readonly cooldownAt?: number;
}

export interface QuotaLedgerState {
  readonly count?: number;
  readonly firstAt?: number;
  readonly lastAt?: number;
  readonly blockedCount?: number;
  readonly lastBlockedAt?: number;
  readonly pending?: Readonly<Record<string, PendingReservation>>;
  readonly releasedCount?: number;
  readonly lastReleasedAt?: number;
  readonly expiredCount?: number;
}

/** Identifies the unit an accepted attempt took, so the handler can settle it. */
export interface QuotaReservation {
  readonly operationKey: string;
  readonly reservedAt: number;
}

/**
 * finalized: the protected write landed and the unit stays spent.
 * released: nothing was stored, so the unit and the cooldown it started are refunded.
 */
export type QuotaSettlement = "finalized" | "released";

export interface DedupeMarker {
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly targetPath?: string;
}

export type QuotaDecision =
  | {
      readonly status: "accepted";
      readonly code: "ok";
      readonly quota: QuotaLedgerState;
      readonly reservation?: QuotaReservation;
      readonly serverTimeMillis: number;
    }
  | {
      readonly status: "duplicate";
      readonly code: "already-exists";
      readonly targetPath?: string;
      readonly retryAfterMillis: number;
      readonly serverTimeMillis: number;
    }
  | {
      readonly status: "blocked";
      readonly code: "resource-exhausted";
      readonly reason: "cooldown" | "daily-limit" | "in-progress";
      readonly retryAfterMillis: number;
      readonly quota: QuotaLedgerState;
      readonly serverTimeMillis: number;
    };

export interface QuotaAttemptInput {
  readonly surface: CommunityCallableSurface;
  readonly nowMillis: number;
  readonly quota?: QuotaLedgerState;
  readonly dedupe?: DedupeMarker | null;
  /** The caller's operation ID as a ledger key; see [quotaOperationKey]. */
  readonly operationKey?: string;
}

export interface DedupeMarkerInput {
  readonly nowMillis: number;
  readonly targetPath?: string;
  readonly ttlMillis?: number;
}

export function utcQuotaDayKey(timestampMillis: number): string {
  requireValidTimestamp(timestampMillis);
  const instant = new Date(timestampMillis);
  const month = String(instant.getUTCMonth() + 1).padStart(2, "0");
  const day = String(instant.getUTCDate()).padStart(2, "0");
  return `${instant.getUTCFullYear()}${month}${day}`;
}

export function millisUntilNextUtcDay(timestampMillis: number): number {
  requireValidTimestamp(timestampMillis);
  const instant = new Date(timestampMillis);
  const nextMidnight = Date.UTC(
    instant.getUTCFullYear(),
    instant.getUTCMonth(),
    instant.getUTCDate() + 1,
  );
  return nextMidnight - timestampMillis;
}

/** Turns an envelope operation ID into a Realtime Database key. */
export function quotaOperationKey(operationId: string): string {
  const key = operationId.replace(FIREBASE_KEY_REGEX, "_").slice(0, OPERATION_KEY_MAX);
  if (!key) throw new Error("Operation key must not be empty");
  return key;
}

export function evaluateCommunityQuotaAttempt(input: QuotaAttemptInput): QuotaDecision {
  const { surface, nowMillis, operationKey } = input;
  requireValidTimestamp(nowMillis);
  const quota = expireStalePending(input.quota ?? {}, nowMillis, operationKey);
  const duplicate = activeDedupeMarker(input.dedupe, nowMillis);
  if (duplicate !== null) {
    return {
      status: "duplicate",
      code: "already-exists",
      targetPath: duplicate.targetPath,
      retryAfterMillis: duplicate.expiresAt - nowMillis,
      serverTimeMillis: nowMillis,
    };
  }

  const own = operationKey === undefined ? undefined : quota.pending?.[operationKey];
  if (operationKey !== undefined && own !== undefined) {
    const age = nowMillis - own.at;
    if (age < PENDING_RESERVATION_LEASE_MILLIS) {
      // The same operation is still running elsewhere; a replay must not take a second unit.
      return {
        status: "blocked",
        code: "resource-exhausted",
        reason: "in-progress",
        retryAfterMillis: PENDING_RESERVATION_LEASE_MILLIS - Math.max(0, age),
        quota: nextBlockedQuotaState(quota, nowMillis),
        serverTimeMillis: nowMillis,
      };
    }
    // The run holding this reservation died. Its replay takes over the unit already paid for.
    return {
      status: "accepted",
      code: "ok",
      quota: withPending(quota, operationKey, { ...own, at: nowMillis, cooldownAt: own.cooldownAt ?? own.at }),
      reservation: { operationKey, reservedAt: nowMillis },
      serverTimeMillis: nowMillis,
    };
  }

  const lastAt = positiveNumberOrUndefined(quota.lastAt);
  if (lastAt !== undefined) {
    const elapsed = nowMillis - lastAt;
    if (elapsed < surface.minIntervalMillis) {
      return {
        status: "blocked",
        code: "resource-exhausted",
        reason: "cooldown",
        retryAfterMillis: surface.minIntervalMillis - Math.max(0, elapsed),
        quota: nextBlockedQuotaState(quota, nowMillis),
        serverTimeMillis: nowMillis,
      };
    }
  }

  const currentCount = Math.max(0, Math.trunc(quota.count ?? 0));
  if (currentCount >= surface.dailyLimit) {
    return {
      status: "blocked",
      code: "resource-exhausted",
      reason: "daily-limit",
      retryAfterMillis: millisUntilNextUtcDay(nowMillis),
      quota: nextBlockedQuotaState(quota, nowMillis),
      serverTimeMillis: nowMillis,
    };
  }

  return {
    status: "accepted",
    code: "ok",
    quota: nextAcceptedQuotaState(quota, nowMillis, operationKey),
    reservation: operationKey === undefined ? undefined : { operationKey, reservedAt: nowMillis },
    serverTimeMillis: nowMillis,
  };
}

export function nextAcceptedQuotaState(
  quota: QuotaLedgerState,
  nowMillis: number,
  operationKey?: string,
): QuotaLedgerState {
  requireValidTimestamp(nowMillis);
  const currentCount = Math.max(0, Math.trunc(quota.count ?? 0));
  const accepted: QuotaLedgerState = {
    ...quota,
    count: currentCount + 1,
    firstAt: positiveNumberOrUndefined(quota.firstAt) ?? nowMillis,
    lastAt: nowMillis,
  };
  if (operationKey === undefined) return accepted;
  const prevLastAt = positiveNumberOrUndefined(quota.lastAt);
  // Realtime Database rejects undefined values, so an absent prevLastAt is left out.
  return withPending(accepted, operationKey, prevLastAt === undefined ? { at: nowMillis } : { at: nowMillis, prevLastAt });
}

/**
 * Settles the unit [reservation] took. Returns null when the ledger no longer holds that
 * reservation (a replay took it over, or it already settled), so there is nothing to do.
 */
export function settledQuotaState(
  quota: QuotaLedgerState,
  reservation: QuotaReservation,
  settlement: QuotaSettlement,
  nowMillis: number,
): QuotaLedgerState | null {
  requireValidTimestamp(nowMillis);
  const entry = quota.pending?.[reservation.operationKey];
  if (entry === undefined || entry.at !== reservation.reservedAt) return null;
  const settled = withoutPending(quota, reservation.operationKey);
  if (settlement === "finalized") return settled;

  const { lastAt: _lastAt, ...rest } = settled;
  // A newer reservation owns lastAt now; only this reservation's own cooldown is refunded.
  const lastAt = quota.lastAt === (entry.cooldownAt ?? entry.at)
    ? positiveNumberOrUndefined(entry.prevLastAt)
    : quota.lastAt;
  return {
    ...rest,
    ...(lastAt === undefined ? {} : { lastAt }),
    count: Math.max(0, Math.trunc(quota.count ?? 0) - 1),
    releasedCount: Math.max(0, Math.trunc(quota.releasedCount ?? 0)) + 1,
    lastReleasedAt: nowMillis,
  };
}

export function nextBlockedQuotaState(
  quota: QuotaLedgerState,
  nowMillis: number,
): QuotaLedgerState {
  requireValidTimestamp(nowMillis);
  const blockedCount = Math.max(0, Math.trunc(quota.blockedCount ?? 0));
  return {
    ...quota,
    blockedCount: blockedCount + 1,
    lastBlockedAt: nowMillis,
  };
}

export function buildDedupeMarker(input: DedupeMarkerInput): DedupeMarker {
  const { nowMillis } = input;
  requireValidTimestamp(nowMillis);
  const ttlMillis = input.ttlMillis ?? DEDUPE_TTL_MILLIS;
  if (!Number.isFinite(ttlMillis) || ttlMillis <= 0) {
    throw new Error("Dedupe TTL must be positive");
  }
  return {
    createdAt: nowMillis,
    expiresAt: nowMillis + ttlMillis,
    targetPath: input.targetPath,
  };
}

function activeDedupeMarker(
  marker: DedupeMarker | null | undefined,
  nowMillis: number,
): DedupeMarker | null {
  if (marker === null || marker === undefined) return null;
  if (!Number.isFinite(marker.expiresAt)) return null;
  return marker.expiresAt > nowMillis ? marker : null;
}

/**
 * Drops reservations whose run died without settling. Their outcome is unknown, so the
 * unit stays spent: refunding could hand out a free write that did land. The caller's
 * own reservation is kept so its replay can take it over.
 */
function expireStalePending(
  quota: QuotaLedgerState,
  nowMillis: number,
  keepOperationKey: string | undefined,
): QuotaLedgerState {
  const pending = quota.pending;
  if (pending === undefined || pending === null || typeof pending !== "object") return quota;
  const stale = Object.keys(pending).filter((key) =>
    key !== keepOperationKey && !(nowMillis - pending[key].at < PENDING_RESERVATION_LEASE_MILLIS)
  );
  if (stale.length === 0) return quota;
  let next = quota;
  for (const key of stale) next = withoutPending(next, key);
  return { ...next, expiredCount: Math.max(0, Math.trunc(quota.expiredCount ?? 0)) + stale.length };
}

function withPending(
  quota: QuotaLedgerState,
  operationKey: string,
  entry: PendingReservation,
): QuotaLedgerState {
  return { ...quota, pending: { ...(quota.pending ?? {}), [operationKey]: entry } };
}

function withoutPending(quota: QuotaLedgerState, operationKey: string): QuotaLedgerState {
  const { pending, ...rest } = quota;
  if (pending === undefined) return quota;
  const { [operationKey]: _removed, ...remaining } = pending;
  return Object.keys(remaining).length === 0 ? rest : { ...rest, pending: remaining };
}

function requireValidTimestamp(timestampMillis: number): void {
  if (!Number.isFinite(timestampMillis) || timestampMillis < 0) {
    throw new Error("Timestamp must be a non-negative finite millisecond value");
  }
}

function positiveNumberOrUndefined(value: number | undefined): number | undefined {
  if (value === undefined || !Number.isFinite(value) || value <= 0) return undefined;
  return value;
}
