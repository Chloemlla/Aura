import { getDatabase } from "firebase-admin/database";
import * as logger from "firebase-functions/logger";
import { HttpsError, onCall } from "firebase-functions/v2/https";

import { requireCallableIdentity } from "./callableScaffold";
import {
  callableRuntimeOptionsFor,
  surfaceByFunctionName,
  type CommunityCallableSurface,
} from "./communityContract";
import {
  buildDedupeMarker,
  evaluateCommunityQuotaAttempt,
  utcQuotaDayKey,
  type DedupeMarker,
  type QuotaDecision,
  type QuotaLedgerState,
} from "./quotaEngine";

const VOTE_SURFACE = surfaceByFunctionName("recordCommunityVote");
const MAX_CONTENT_ID = 240;
const MAX_OPERATION_ID = 120;
const FIREBASE_KEY_REGEX = /[.#$[\]/]/g;
const WHITESPACE_REGEX = /\s+/g;
const CONTROL_REGEX = /[\u0000-\u001F\u007F]/g;
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

export interface VoteBackend {
  nowMillis(): number;
  hasExistingVote(uid: string, contentId: string): Promise<boolean>;
  readDedupeMarker(uid: string, surfaceKey: string, dedupeKey: string): Promise<DedupeMarker | null>;
  reserveQuota(
    uid: string,
    dayKey: string,
    surface: CommunityCallableSurface,
    nowMillis: number,
    dedupe: DedupeMarker | null,
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

  const commit = await backend.commitVote({
    uid,
    contentId,
    surfaceKey: VOTE_SURFACE.surfaceKey,
    dedupeKey,
    dedupeMarker: buildDedupeMarker({
      nowMillis,
      targetPath,
    }),
  });

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
  ): Promise<QuotaDecision> {
    let decision: QuotaDecision | null = null;
    const result = await this.quotaRef(uid, dayKey, surface.surfaceKey).transaction(
      (current: unknown) => {
        const quota = current !== null && typeof current === "object"
          ? current as QuotaLedgerState
          : {};
        decision = evaluateCommunityQuotaAttempt({
          surface,
          nowMillis,
          quota,
          dedupe,
        });
        if (decision.status === "duplicate") {
          return current;
        }
        return decision.quota;
      },
      undefined,
      false,
    );
    if (!result.committed || decision === null) {
      throw new HttpsError("aborted", "Unable to reserve community vote quota.");
    }
    return decision;
  }

  async commitVote(input: CommitVoteInput): Promise<VoteCommitResult> {
    // The private marker is claimed first so two racing calls from one account cannot both
    // increment the public count. Only the owner can read vote_markers/{uid}.
    const markerRef = this.markerRef(input.uid, input.contentId);
    const claim = await markerRef.transaction(
      (current: unknown) => (current === true ? undefined : true),
      undefined,
      false,
    );
    if (!claim.committed) return { status: "duplicate" };

    let upvotes = 0;
    try {
      const legacySeed = await this.legacyUpvotes(input.contentId);
      const counted = await this.root.child("vote_counts").child(input.contentId).transaction(
        (current: unknown) => {
          const stored = current !== null && typeof current === "object"
            ? (current as Record<string, unknown>).upvotes
            : undefined;
          const base = typeof stored === "number" ? stored : legacySeed;
          upvotes = Math.max(0, Math.trunc(base)) + 1;
          return { upvotes };
        },
        undefined,
        false,
      );
      if (!counted.committed) throw new Error("vote count transaction aborted");
    } catch {
      await markerRef.remove();
      throw new HttpsError("aborted", "Unable to commit community vote.");
    }

    await this.root.update({
      [`community_write_dedupe/${input.uid}/${input.surfaceKey}/${input.dedupeKey}`]: input.dedupeMarker,
    });
    try {
      await this.mirrorUploadVotes(input.contentId, upvotes);
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

  /** Seeds a first vote_counts row from the count kept before the schema split. */
  private async legacyUpvotes(contentId: string): Promise<number> {
    const snapshot = await this.root.child("votes").child(contentId).child("upvotes").get();
    const value = snapshot.val();
    return typeof value === "number" && Number.isFinite(value) ? Math.max(0, Math.trunc(value)) : 0;
  }

  /**
   * Community feeds sort and filter by the upload row's own `votes` field, so keep it in
   * step with the public count. Skipped when the upload row is gone.
   */
  private async mirrorUploadVotes(contentId: string, upvotes: number): Promise<void> {
    const uploadPath = communityUploadMetadataPath(contentId);
    if (!uploadPath) return;
    const upload = this.root.child(uploadPath);
    if (!(await upload.child("storagePath").get()).exists()) return;
    await upload.child("votes").transaction(
      (current: unknown) => Math.max(typeof current === "number" ? Math.trunc(current) : 0, upvotes),
      undefined,
      false,
    );
  }

  private quotaRef(uid: string, dayKey: string, surfaceKey: string) {
    return this.root
      .child("community_write_quotas")
      .child(uid)
      .child(dayKey)
      .child(surfaceKey);
  }
}
