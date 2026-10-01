import { randomBytes } from "node:crypto";

import { getDatabase, type Reference } from "firebase-admin/database";
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
  quotaOperationKey,
  utcQuotaDayKey,
  type DedupeMarker,
  type QuotaDecision,
  type QuotaReservation,
  type QuotaSettlement,
} from "./quotaEngine";
import {
  reserveQuotaLedger,
  runWithQuotaReservation,
  settleQuotaLedger,
  type QuotaSettlingBackend,
} from "./quotaReservation";

const SHARE_SURFACE = surfaceByFunctionName("publishSharedCollection");
const DAY_MILLIS = 24 * 60 * 60 * 1_000;

/** A share link works for 30 days; the database rules refuse reads after that. */
export const SHARED_COLLECTION_TTL_MILLIS = 30 * DAY_MILLIS;
/** UTF-8 bytes of the collection document; the rules cap the stored string at the same size. */
export const MAX_SHARED_COLLECTION_DOCUMENT_BYTES = 512 * 1024;
export const MAX_SHARED_COLLECTION_ITEMS = 250;
export const SHARED_COLLECTION_VERSION = 1;

const MAX_COLLECTION_NAME = 80;
const MAX_OPERATION_ID = 120;
const DEFAULT_COLLECTION_NAME = "Shared collection";
const PRUNE_BATCH_SIZE = 500;
const PRUNE_MAX_BATCHES = 20;
const WHITESPACE_REGEX = /\s+/g;
const CONTROL_REGEX = /[\u0000-\u001F\u007F]/g;
const SERVER_DERIVED_FIELDS = ["createdByUid", "createdAt", "expiresAt", "token", "itemCount"];

interface CallableRequestLike {
  readonly data?: unknown;
  readonly auth?: {
    readonly uid?: string;
  };
  readonly app?: unknown;
}

interface SharedCollectionEnvelope {
  readonly operationId: string;
  readonly clientSentAt: number;
  readonly payload: Record<string, unknown>;
}

export interface SharedCollectionInput {
  readonly document: string;
  readonly collectionName: string;
  readonly itemCount: number;
}

export interface SharedCollectionRecord {
  readonly version: number;
  readonly payload: string;
  readonly collectionName: string;
  readonly itemCount: number;
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly createdByUid: string;
}

interface CommitShareInput {
  readonly uid: string;
  readonly surfaceKey: string;
  readonly dedupeKey: string;
  readonly token: string;
  readonly record: SharedCollectionRecord;
  readonly dedupeMarker: DedupeMarker;
}

export interface SharedCollectionBackend extends QuotaSettlingBackend {
  nowMillis(): number;
  createShareToken(): string;
  readDedupeMarker(uid: string, surfaceKey: string, dedupeKey: string): Promise<DedupeMarker | null>;
  reserveQuota(
    uid: string,
    dayKey: string,
    surface: CommunityCallableSurface,
    nowMillis: number,
    dedupe: DedupeMarker | null,
    operationKey?: string,
  ): Promise<QuotaDecision>;
  commitShare(input: CommitShareInput): Promise<void>;
}

export function createPublishSharedCollectionCallable(backend = new FirebaseSharedCollectionBackend()) {
  return onCall(callableRuntimeOptionsFor(SHARE_SURFACE), async (request) => {
    return publishSharedCollectionHandler(request, backend);
  });
}

export async function publishSharedCollectionHandler(
  request: CallableRequestLike,
  backend: SharedCollectionBackend = new FirebaseSharedCollectionBackend(),
) {
  const uid = requireCallableIdentity(request, SHARE_SURFACE);
  const nowMillis = backend.nowMillis();
  const envelope = normalizeEnvelope(request.data);
  const input = normalizeSharedCollectionPayload(envelope.payload);

  const dayKey = utcQuotaDayKey(nowMillis);
  const dedupeKey = quotaOperationKey(envelope.operationId);
  const dedupe = await backend.readDedupeMarker(uid, SHARE_SURFACE.surfaceKey, dedupeKey);
  const decision = await backend.reserveQuota(uid, dayKey, SHARE_SURFACE, nowMillis, dedupe, dedupeKey);

  if (decision.status === "duplicate") {
    const targetPath = decision.targetPath ?? "";
    return {
      operationId: envelope.operationId,
      status: "duplicate",
      token: targetPath.slice(targetPath.lastIndexOf("/") + 1),
      targetPath,
      serverTimeMillis: decision.serverTimeMillis,
    };
  }

  if (decision.status === "blocked") {
    throw new HttpsError(
      decision.code,
      `Collection share quota blocked by ${decision.reason}.`,
      {
        operationId: envelope.operationId,
        reason: decision.reason,
        retryAfterMillis: decision.retryAfterMillis,
        serverTimeMillis: decision.serverTimeMillis,
        surfaceKey: SHARE_SURFACE.surfaceKey,
      },
    );
  }

  const { token, targetPath, record } = await runWithQuotaReservation(
    backend,
    { uid, dayKey, surface: SHARE_SURFACE, reservation: decision.reservation },
    async () => {
      const token = backend.createShareToken();
      const targetPath = `/shared_collections/${token}`;
      const record: SharedCollectionRecord = {
        version: SHARED_COLLECTION_VERSION,
        payload: input.document,
        collectionName: input.collectionName,
        itemCount: input.itemCount,
        createdAt: nowMillis,
        expiresAt: nowMillis + SHARED_COLLECTION_TTL_MILLIS,
        createdByUid: uid,
      };
      await backend.commitShare({
        uid,
        surfaceKey: SHARE_SURFACE.surfaceKey,
        dedupeKey,
        token,
        record,
        dedupeMarker: buildDedupeMarker({ nowMillis, targetPath }),
      });
      return { token, targetPath, record };
    },
  );

  return {
    operationId: envelope.operationId,
    status: "accepted",
    token,
    targetPath,
    expiresAt: record.expiresAt,
    serverTimeMillis: decision.serverTimeMillis,
  };
}

/**
 * Checks the collection document the app exports. The server counts the items itself and
 * never takes owner, time, or token fields from the caller.
 */
export function normalizeSharedCollectionPayload(payload: Record<string, unknown>): SharedCollectionInput {
  for (const field of SERVER_DERIVED_FIELDS) {
    if (Object.prototype.hasOwnProperty.call(payload, field)) {
      throwInvalid(field, `${field} is set by the server.`);
    }
  }
  if (payload.version !== SHARED_COLLECTION_VERSION) {
    throwInvalid("version", "Unsupported collection share version.");
  }

  const document = requiredString(payload, "document");
  if (document.length === 0) throwInvalid("document", "Collection document is required.");
  if (Buffer.byteLength(document, "utf8") > MAX_SHARED_COLLECTION_DOCUMENT_BYTES) {
    throwInvalid("document", "Collection is too large to share as a link.");
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(document);
  } catch {
    throwInvalid("document", "Collection document must be JSON.");
  }
  const file = objectOrInvalid(parsed, "document");
  if (file.version !== SHARED_COLLECTION_VERSION) {
    throwInvalid("document", "Unsupported collection document version.");
  }
  const items = file.items;
  if (!Array.isArray(items) || items.length === 0) {
    throwInvalid("document", "Collection document has no items.");
  }
  if (items.length > MAX_SHARED_COLLECTION_ITEMS) {
    throwInvalid("document", `Collection links hold at most ${MAX_SHARED_COLLECTION_ITEMS} wallpapers.`);
  }
  if (!items.every((item) => item !== null && typeof item === "object" && !Array.isArray(item))) {
    throwInvalid("document", "Collection items must be objects.");
  }

  const requestedName = typeof payload.collectionName === "string" ? payload.collectionName : "";
  const documentName = typeof file.collectionName === "string" ? file.collectionName : "";
  const collectionName = normalizeText(requestedName, MAX_COLLECTION_NAME)
    || normalizeText(documentName, MAX_COLLECTION_NAME)
    || DEFAULT_COLLECTION_NAME;

  return { document, collectionName, itemCount: items.length };
}

/**
 * Deletes shares at least [SHARED_COLLECTION_TTL_MILLIS] old, oldest first, in batches.
 * Shares written before expiresAt existed age out by createdAt the same way.
 */
export async function deleteExpiredSharedCollections(
  root: Reference,
  nowMillis: number,
  batchSize = PRUNE_BATCH_SIZE,
  maxBatches = PRUNE_MAX_BATCHES,
): Promise<number> {
  const cutoff = nowMillis - SHARED_COLLECTION_TTL_MILLIS;
  let deleted = 0;
  for (let batch = 0; batch < maxBatches; batch++) {
    const snapshot = await root
      .child("shared_collections")
      .orderByChild("createdAt")
      .endAt(cutoff)
      .limitToFirst(batchSize)
      .get();
    const updates: Record<string, null> = {};
    snapshot.forEach((child) => {
      updates[`shared_collections/${child.key}`] = null;
    });
    const found = Object.keys(updates).length;
    if (found === 0) break;
    await root.update(updates);
    deleted += found;
    if (found < batchSize) break;
  }
  return deleted;
}

export function createPruneExpiredSharedCollectionsJob() {
  return onSchedule({ schedule: "every 24 hours", timeZone: "UTC" }, async () => {
    const deleted = await deleteExpiredSharedCollections(getDatabase().ref(), Date.now());
    logger.info("Pruned expired shared collections", { deleted });
  });
}

function normalizeEnvelope(data: unknown): SharedCollectionEnvelope {
  const value = objectOrInvalid(data, "request");
  const operationId = normalizeText(requiredString(value, "operationId"), MAX_OPERATION_ID);
  if (!operationId) throwInvalid("operationId", "Operation ID is required.");
  const clientSentAt = requiredNumber(value, "clientSentAt");
  if (clientSentAt <= 0) {
    throwInvalid("clientSentAt", "Client timestamp must be positive.");
  }
  return {
    operationId,
    clientSentAt,
    payload: objectOrInvalid(value.payload, "payload"),
  };
}

function normalizeText(value: string, maxLength: number): string {
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

function throwInvalid(field: string, message: string): never {
  throw new HttpsError("invalid-argument", message, { field });
}

class FirebaseSharedCollectionBackend implements SharedCollectionBackend {
  private readonly root = getDatabase().ref();

  nowMillis(): number {
    return Date.now();
  }

  createShareToken(): string {
    return randomBytes(16).toString("hex");
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
    return reserveQuotaLedger(this.quotaRef(uid, dayKey, surface.surfaceKey), surface, nowMillis, dedupe, operationKey);
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

  async commitShare(input: CommitShareInput): Promise<void> {
    await this.root.update({
      [`shared_collections/${input.token}`]: input.record,
      [`community_write_dedupe/${input.uid}/${input.surfaceKey}/${input.dedupeKey}`]: input.dedupeMarker,
    });
  }

  private quotaRef(uid: string, dayKey: string, surfaceKey: string) {
    return this.root
      .child("community_write_quotas")
      .child(uid)
      .child(dayKey)
      .child(surfaceKey);
  }
}
