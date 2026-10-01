import type { Reference } from "firebase-admin/database";
import * as logger from "firebase-functions/logger";
import { HttpsError } from "firebase-functions/v2/https";

import type { CommunityCallableSurface } from "./communityContract";
import {
  evaluateCommunityQuotaAttempt,
  settledQuotaState,
  type DedupeMarker,
  type QuotaDecision,
  type QuotaLedgerState,
  type QuotaReservation,
  type QuotaSettlement,
} from "./quotaEngine";

/** Realtime Database half of reserveQuota: one transaction over the surface's day ledger. */
export async function reserveQuotaLedger(
  ref: Reference,
  surface: CommunityCallableSurface,
  nowMillis: number,
  dedupe: DedupeMarker | null,
  operationKey: string | undefined,
): Promise<QuotaDecision> {
  let decision: QuotaDecision | null = null;
  const result = await ref.transaction(
    (current: unknown) => {
      const quota = current !== null && typeof current === "object" ? current as QuotaLedgerState : {};
      decision = evaluateCommunityQuotaAttempt({ surface, nowMillis, quota, dedupe, operationKey });
      return decision.status === "duplicate" ? current : decision.quota;
    },
    undefined,
    false,
  );
  if (!result.committed || decision === null) {
    throw new HttpsError("aborted", `Unable to reserve ${surface.surfaceKey} quota.`);
  }
  return decision;
}

/** The settle half of a callable backend; reserveQuota hands out the reservation. */
export interface QuotaSettlingBackend {
  settleQuota(
    uid: string,
    dayKey: string,
    surface: CommunityCallableSurface,
    reservation: QuotaReservation,
    settlement: QuotaSettlement,
  ): Promise<void>;
}

export interface ReservedQuota {
  readonly uid: string;
  readonly dayKey: string;
  readonly surface: CommunityCallableSurface;
  readonly reservation?: QuotaReservation;
}

/**
 * Runs the protected write for an accepted attempt. The unit it reserved stays spent only
 * when [action] stores something. A throw, or a result [stored] rejects, refunds the unit
 * and the cooldown it started, so the person can retry straight away.
 */
export async function runWithQuotaReservation<T>(
  backend: QuotaSettlingBackend,
  reserved: ReservedQuota,
  action: () => Promise<T>,
  stored: (result: T) => boolean = () => true,
): Promise<T> {
  let result: T;
  try {
    result = await action();
  } catch (error) {
    await settleQuietly(backend, reserved, "released");
    throw error;
  }
  await settleQuietly(backend, reserved, stored(result) ? "finalized" : "released");
  return result;
}

async function settleQuietly(
  backend: QuotaSettlingBackend,
  reserved: ReservedQuota,
  settlement: QuotaSettlement,
): Promise<void> {
  if (reserved.reservation === undefined) return;
  try {
    await backend.settleQuota(reserved.uid, reserved.dayKey, reserved.surface, reserved.reservation, settlement);
  } catch (error) {
    // Settling is bookkeeping and must not change what the caller sees. A lost finalize
    // expires as spent after the lease; a lost release costs this one unit.
    logger.warn("Community quota settlement failed", {
      surfaceKey: reserved.surface.surfaceKey,
      settlement,
      error: String(error),
    });
  }
}

/** Realtime Database half of settleQuota, shared by every callable backend. */
export async function settleQuotaLedger(
  ref: Reference,
  reservation: QuotaReservation,
  settlement: QuotaSettlement,
  nowMillis: number,
): Promise<void> {
  await ref.transaction(
    (current: unknown) => {
      // Never abort: the first run can see an empty or stale local copy, and writing the
      // same value back makes the server rerun this against the stored ledger.
      if (current === null || typeof current !== "object") return current;
      return settledQuotaState(current as QuotaLedgerState, reservation, settlement, nowMillis) ?? current;
    },
    undefined,
    false,
  );
}
