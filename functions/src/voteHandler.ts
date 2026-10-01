import { getDatabase, ServerValue, type Reference } from "firebase-admin/database";
import * as logger from "firebase-functions/logger";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";

import { requireCallableIdentity } from "./callableScaffold";
import {
  callableRuntimeOptionsFor,
  surfaceByFunctionName,
  type CommunityCallableSurface,
} from "./communityContract";
import {
  buildDedupeMarker,
  PENDING_RESERVATION_LEASE_MILLIS,
  quotaOperationKey,
  utcQuotaDayKey,
  type DedupeMarker,
  type QuotaDecision,
  type QuotaReservation,
  type QuotaSettlement,
} from "./quotaEngine";
import {
  type QuotaSettlingBackend,
  reserveQuotaLedger,
  runWithQuotaReservation,
  settleQuotaLedger,
} from "./quotaReservation";

const VOTE_SURFACE = surfaceByFunctionName("recordCommunityVote");
const MAX_CONTENT_ID = 240;
const MAX_OPERATION_ID = 120;
const FIREBASE_KEY_REGEX = /[.#$[\]/]/g;
const WHITESPACE_REGEX = /\s+/g;
const CONTROL_REGEX = /[\u0000-\u001F\u007F]/g;
const SEED_BATCH_SIZE = 200;
const SEED_MAX_BATCHES = 50;
const COMMUNITY_UPLOAD_VOTE_KEY = /^(SOUND|WALLPAPER)::COMMUNITY::(cu|cw)_([A-Za-z0-9_-]{1,200})$/;

interface CallableRequestLike {
  readonly data?: unknown;
  readonly auth?: {
    readonly uid?: string;
  };
  readonly app?: unknown;
}

interface CommunityVoteEnvelope {
  readonly operationId: string;
  readonly clientSentAt: number;
  readonly payload: Record<string, unknown>;
}

interface CommitVoteInput {
  readonly uid: string;
  readonly contentId: string;
  readonly surfaceKey: string;
  readonly dedupeKey: string;
  readonly dedupeMarker: DedupeMarker;
}

export interface VoteCommitResult {
  readonly status: "accepted" | "duplicate";
  readonly upvotes?: number;
}

export interface VoteBackend extends QuotaSettlingBackend {
  nowMillis(): number;
  hasExistingVote(uid: string, contentId: string): Promise<boolean>;
  readDedupeMarker(uid: string, surfaceKey: string, dedupeKey: string): Promise<DedupeMarker | null>;
  reserveQuota(
    uid: string,
    dayKey: string,
    surface: CommunityCallableSurface,
    nowMillis: number,
    dedupe: DedupeMarker | null,
    operationKey?: string,
  ): Promise<QuotaDecision>;
  commitVote(input: CommitVoteInput): Promise<VoteCommitResult>;
}

export function createRecordCommunityVoteCallable(backend = new FirebaseVoteBackend()) {
  return onCall(callableRuntimeOptionsFor(VOTE_SURFACE), async (request) => {
    return recordCommunityVoteHandler(request, backend);
  });
}

export async function recordCommunityVoteHandler(
  request: CallableRequestLike,
  backend: VoteBackend = new FirebaseVoteBackend(),
) {
  const uid = requireCallableIdentity(request, VOTE_SURFACE);
  const nowMillis = backend.nowMillis();
  const envelope = normalizeEnvelope(request.data);
  const contentId = normalizeVoteContentId(requiredString(envelope.payload, "contentId"));
  const targetPath = `/vote_counts/${contentId}`;
  if (await backend.hasExistingVote(uid, contentId)) {
    return {
      operationId: envelope.operationId,
      status: "duplicate",
      targetPath,
      serverTimeMillis: nowMillis,
    };
  }

  const dayKey = utcQuotaDayKey(nowMillis);
  const dedupeKey = contentId;
  const dedupe = await backend.readDedupeMarker(uid, VOTE_SURFACE.surfaceKey, dedupeKey);
  const decision = await backend.reserveQuota(
    uid,
    dayKey,
    VOTE_SURFACE,
    nowMillis,
    dedupe,
    quotaOperationKey(envelope.operationId),
  );

  if (decision.status === "duplicate") {
    return {
      operationId: envelope.operationId,
      status: "duplicate",
      targetPath: decision.targetPath ?? targetPath,
      serverTimeMillis: decision.serverTimeMillis,
    };
  }

  if (decision.status === "blocked") {
    throw new HttpsError(
      decision.code,
      `Community vote quota blocked by ${decision.reason}.`,
      {
        operationId: envelope.operationId,
        reason: decision.reason,
        retryAfterMillis: decision.retryAfterMillis,
        serverTimeMillis: decision.serverTimeMillis,
        surfaceKey: VOTE_SURFACE.surfaceKey,
      },
    );
  }

  // A failed commit, or one that lost the marker race and counted nothing, refunds the unit.
  const commit = await runWithQuotaReservation(
    backend,
    { uid, dayKey, surface: VOTE_SURFACE, reservation: decision.reservation },
    () => backend.commitVote({
      uid,
      contentId,
      surfaceKey: VOTE_SURFACE.surfaceKey,
      dedupeKey,
      dedupeMarker: buildDedupeMarker({
        nowMillis,
        targetPath,
      }),
    }),
    (result) => result.status === "accepted",
  );

  return {
    operationId: envelope.operationId,
    status: commit.status,
    targetPath,
    serverTimeMillis: decision.serverTimeMillis,
    upvotes: commit.upvotes,
  };
}

/**
 * Maps a community upload's vote key (`SOUND::COMMUNITY::cu_<id>` or
 * `WALLPAPER::COMMUNITY::cw_<id>`) to its metadata row, or null for any other content.
 */
export function communityUploadMetadataPath(contentId: string): string | null {
  const match = COMMUNITY_UPLOAD_VOTE_KEY.exec(contentId);
  if (!match) return null;
  const [, type, prefix, uploadId] = match;
  if (type === "SOUND" && prefix === "cu") return `community_sounds/${uploadId}`;
  if (type === "WALLPAPER" && prefix === "cw") return `community_wallpapers/${uploadId}`;
  return null;
}

/**
 * Creates `/vote_counts/{contentId}` from the count kept before the schema split, or repairs a
 * stored count that is not a whole number. A whole-number row is left alone, so the callable and
 * the seeding job can run in either order without one overwriting votes the other counted.
 * Returns true when it wrote a row.
 */
export async function seedVoteCount(root: Reference, contentId: string): Promise<boolean> {
  const countRef = root.child("vote_counts").child(contentId);
  if (isWholeCount((await countRef.child("upvotes").get()).val())) return false;
  const [legacyVote, legacyVoters] = await Promise.all([
    root.child("votes").child(contentId).get(),
    root.child("voters").child(contentId).get(),
  ]);
  const legacySeed = legacyVoteCount(legacyVote.val(), legacyVoters.val());
  let wrote = false;
  const seeded = await countRef.transaction(
    (current: unknown) => {
      const row = current !== null && typeof current === "object" ? current as Record<string, unknown> : {};
      wrote = !isWholeCount(row.upvotes);
      if (!wrote) return current;
      return { ...row, upvotes: typeof row.upvotes === "number" ? wholeCount(row.upvotes) : legacySeed };
    },
    undefined,
    false,
  );
  if (!seeded.committed) throw new Error("vote count seed aborted");
  return wrote;
}

/** The legacy count, never below the number of distinct legacy voters. */
export function legacyVoteCount(vote: unknown, voters: unknown): number {
  const row = vote !== null && typeof vote === "object" ? vote as Record<string, unknown> : {};
  const ids = new Set<string>();
  for (const map of [row.voters, voters]) {
    if (map === null || typeof map !== "object") continue;
    for (const [uid, marker] of Object.entries(map as Record<string, unknown>)) {
      if (marker === true) ids.add(uid);
    }
  }
  return Math.max(wholeCount(row.upvotes), ids.size);
}

/**
 * Community feeds sort and filter by the upload row's own `votes` field, so keep it in step with
 * the public count. One transaction on the whole row, so an upload deleted between a check and
 * the write cannot come back as a stub holding only `votes`.
 */
export async function mirrorUploadVotes(root: Reference, contentId: string, upvotes: number): Promise<void> {
  const uploadPath = communityUploadMetadataPath(contentId);
  if (!uploadPath) return;
  await root.child(uploadPath).transaction(
    (current: unknown) => {
      if (current === null || typeof current !== "object") return current;
      const row = current as Record<string, unknown>;
      if (row.storagePath === undefined || row.storagePath === null) return current;
      const stored = typeof row.votes === "number" ? Math.trunc(row.votes) : 0;
      return stored >= upvotes ? current : { ...row, votes: upvotes };
    },
    undefined,
    false,
  );
}

/**
 * Gives every content ID under the legacy `/votes` and `/voters` roots a `/vote_counts` row and
 * mirrors it onto the upload row, through the same transaction the callable uses. Content already
 * counted under the new schema keeps its row. Returns how many rows it wrote.
 */
export async function seedLegacyVoteCounts(
  root: Reference,
  batchSize = SEED_BATCH_SIZE,
  maxBatches = SEED_MAX_BATCHES,
): Promise<number> {
  let seeded = 0;
  const visited = new Set<string>();
  for (const legacyRoot of ["votes", "voters"]) {
    let lastKey: string | undefined;
    for (let batch = 0; batch < maxBatches; batch++) {
      let query = root.child(legacyRoot).orderByKey();
      if (lastKey !== undefined) query = query.startAfter(lastKey);
      const snapshot = await query.limitToFirst(batchSize).get();
      const keys: string[] = [];
      snapshot.forEach((child) => {
        if (child.key !== null) keys.push(child.key);
      });
      for (const contentId of keys) {
        if (visited.has(contentId)) continue;
        visited.add(contentId);
        if (await seedVoteCount(root, contentId)) seeded++;
        const counted = wholeCount((await root.child("vote_counts").child(contentId).child("upvotes").get()).val());
        if (counted > 0) await mirrorUploadVotes(root, contentId, counted);
      }
      if (keys.length < batchSize) break;
      lastKey = keys[keys.length - 1];
    }
  }
  return seeded;
}

export function createSeedLegacyVoteCountsJob() {
  return onSchedule({ schedule: "every 24 hours", timeZone: "UTC" }, async () => {
    const seeded = await seedLegacyVoteCounts(getDatabase().ref());
    logger.info("Seeded legacy vote counts", { seeded });
  });
}

function wholeCount(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? Math.max(0, Math.trunc(value)) : 0;
}

function isWholeCount(value: unknown): boolean {
  return typeof value === "number" && Number.isInteger(value) && value >= 0;
}

export function normalizeVoteContentId(value: string): string {
  const normalized = value
    .replace(CONTROL_REGEX, " ")
    .replace(WHITESPACE_REGEX, " ")
    .trim()
    .slice(0, MAX_CONTENT_ID)
    .replace(FIREBASE_KEY_REGEX, "_");
  if (!normalized) {
    throw new HttpsError("invalid-argument", "Vote content ID is required.", { field: "contentId" });
  }
  return normalized;
}

function normalizeEnvelope(data: unknown): CommunityVoteEnvelope {
  const value = objectOrInvalid(data, "request");
  const operationId = normalizeShortText(requiredString(value, "operationId"), MAX_OPERATION_ID);
  if (!operationId) {
    throw new HttpsError("invalid-argument", "Operation ID is required.", { field: "operationId" });
  }
  const clientSentAt = requiredNumber(value, "clientSentAt");
  if (clientSentAt <= 0) {
    throw new HttpsError("invalid-argument", "Client timestamp must be positive.", { field: "clientSentAt" });
  }
  return {
    operationId,
    clientSentAt,
    payload: objectOrInvalid(value.payload, "payload"),
  };
}

function normalizeShortText(value: string, maxLength: number): string {
  return value
    .replace(CONTROL_REGEX, " ")
    .replace(WHITESPACE_REGEX, " ")
    .trim()
    .slice(0, maxLength);
}

function objectOrInvalid(value: unknown, field: string): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpsError("invalid-argument", `${field} must be an object.`, { field });
  }
  return value as Record<string, unknown>;
}

function requiredString(value: Record<string, unknown>, field: string): string {
  const raw = value[field];
  if (typeof raw !== "string") {
    throw new HttpsError("invalid-argument", `${field} must be a string.`, { field });
  }
  return raw;
}

function requiredNumber(value: Record<string, unknown>, field: string): number {
  const raw = value[field];
  if (typeof raw !== "number" || !Number.isFinite(raw)) {
    throw new HttpsError("invalid-argument", `${field} must be a finite number.`, { field });
  }
  return raw;
}

class FirebaseVoteBackend implements VoteBackend {
  private readonly root = getDatabase().ref();

  nowMillis(): number {
    return Date.now();
  }

  async hasExistingVote(uid: string, contentId: string): Promise<boolean> {
    // Votes cast before the private marker tree existed still count as duplicates.
    const [marker, nested, legacy] = await Promise.all([
      this.markerRef(uid, contentId).get(),
      this.root.child("votes").child(contentId).child("voters").child(uid).get(),
      this.root.child("voters").child(contentId).child(uid).get(),
    ]);
    return marker.exists() || nested.exists() || legacy.exists();
  }

  async readDedupeMarker(
    uid: string,
    surfaceKey: string,
    dedupeKey: string,
  ): Promise<DedupeMarker | null> {
    const snapshot = await this.root
      .child("community_write_dedupe")
      .child(uid)
      .child(surfaceKey)
      .child(dedupeKey)
      .get();
    const value = snapshot.val();
    if (value === null || typeof value !== "object") return null;
    return value as DedupeMarker;
  }

  async reserveQuota(
    uid: string,
    dayKey: string,
    surface: CommunityCallableSurface,
    nowMillis: number,
    dedupe: DedupeMarker | null,
    operationKey?: string,
  ): Promise<QuotaDecision> {
    return reserveQuotaLedger(
      this.root.child("community_write_quotas").child(uid),
      dayKey,
      surface,
      nowMillis,
      dedupe,
      operationKey,
    );
  }

  async settleQuota(
    uid: string,
    dayKey: string,
    surface: CommunityCallableSurface,
    reservation: QuotaReservation,
    settlement: QuotaSettlement,
  ): Promise<void> {
    await settleQuotaLedger(this.quotaRef(uid, dayKey, surface.surfaceKey), reservation, settlement, this.nowMillis());
  }

  async commitVote(input: CommitVoteInput): Promise<VoteCommitResult> {
    // A private lock holds the vote while it is counted, so two racing calls from one account
    // cannot both increment. The marker and the increment land in one atomic update, so a run
    // that dies part way leaves no marker to turn the retry away; its lock lapses after the lease.
    const lockRef = this.lockRef(input.uid, input.contentId);
    const lockedAt = this.nowMillis();
    let busy = false;
    const lock = await lockRef.transaction(
      (current: unknown) => {
        const at = current !== null && typeof current === "object"
          ? (current as Record<string, unknown>).at
          : undefined;
        busy = typeof at === "number" && lockedAt - at < PENDING_RESERVATION_LEASE_MILLIS;
        return busy ? current : { at: lockedAt };
      },
      undefined,
      false,
    );
    if (!lock.committed) throw new HttpsError("aborted", "Unable to commit community vote.");
    if (busy) {
      throw new HttpsError("aborted", "This vote is still being counted.", {
        retryAfterMillis: PENDING_RESERVATION_LEASE_MILLIS,
      });
    }

    const countRef = this.root.child("vote_counts").child(input.contentId);
    try {
      if ((await this.markerRef(input.uid, input.contentId).get()).exists()) {
        await lockRef.remove();
        return { status: "duplicate" };
      }
      await seedVoteCount(this.root, input.contentId);
      await this.root.update({
        [`vote_markers/${input.uid}/${input.contentId}`]: true,
        [`vote_counts/${input.contentId}/upvotes`]: ServerValue.increment(1),
        [`vote_locks/${input.uid}/${input.contentId}`]: null,
      });
    } catch (error) {
      if (error instanceof HttpsError) throw error;
      await lockRef.remove().catch(() => undefined);
      throw new HttpsError("aborted", "Unable to commit community vote.");
    }
    const counted = (await countRef.child("upvotes").get()).val();
    const upvotes = typeof counted === "number" ? counted : 1;

    try {
      // The vote marker already turns away a second vote, so a lost dedupe row must not
      // fail a vote that counted (that would also refund its quota unit).
      await this.root.update({
        [`community_write_dedupe/${input.uid}/${input.surfaceKey}/${input.dedupeKey}`]: input.dedupeMarker,
      });
    } catch (error) {
      logger.warn("Community vote dedupe write failed", { contentId: input.contentId, error: String(error) });
    }
    try {
      await mirrorUploadVotes(this.root, input.contentId, upvotes);
    } catch (error) {
      // The vote itself is committed; the next vote on this upload re-mirrors the count.
      logger.warn("Community upload vote mirror failed", { contentId: input.contentId, error: String(error) });
    }
    return {
      status: "accepted",
      upvotes,
    };
  }

  private markerRef(uid: string, contentId: string) {
    return this.root.child("vote_markers").child(uid).child(contentId);
  }

  private lockRef(uid: string, contentId: string) {
    return this.root.child("vote_locks").child(uid).child(contentId);
  }

  private quotaRef(uid: string, dayKey: string, surfaceKey: string) {
    return this.root
      .child("community_write_quotas")
      .child(uid)
      .child(dayKey)
      .child(surfaceKey);
  }
}
