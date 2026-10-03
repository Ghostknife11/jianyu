import { assertEvent } from "../../foe-schema/src/index.js";

function clone(value) {
  return structuredClone(value);
}

export class InMemoryEventStore {
  #events = [];
  #ids = new Set();

  append(event) {
    assertEvent(event);
    if (this.#ids.has(event.eventId)) return false;
    this.#events.push(clone(event));
    this.#ids.add(event.eventId);
    return true;
  }

  list() {
    return clone(this.#events);
  }

  export() {
    return Object.freeze({
      schema: "org.foe.export/v1",
      createdAt: new Date().toISOString(),
      events: this.list()
    });
  }
}

const COLLECTIONS = [
  ["members", "id"],
  ["children", "id"],
  ["evidence", "id"],
  ["hypotheses", "id"],
  ["events", "eventId"]
];

/**
 * Provider-neutral reference merge for already authenticated, decrypted state.
 * Persistence, transport encryption, enrollment, and signature verification are
 * intentionally outside this small compatibility function.
 */
export function mergeFamilyState(local, incoming) {
  if (!local || !incoming || local.household?.id !== incoming.household?.id) {
    throw new TypeError("family states must belong to the same household");
  }
  if (!sameValue(local.household, incoming.household)) {
    throw new TypeError("conflicting household object");
  }

  const tombstones = mergeStrict(local.tombstones ?? [], incoming.tombstones ?? [], "tombstoneId", "tombstone");
  validateTombstones(tombstones, local.household.id);
  const deletedSubjects = targetIds(tombstones, "SUBJECT");
  const clearedSubjectContent = targetIds(tombstones, "SUBJECT_CONTENT");
  const deletedByType = new Map([
    ["evidence", targetIds(tombstones, "EVIDENCE")],
    ["hypotheses", targetIds(tombstones, "HYPOTHESIS")],
    ["choices", targetIds(tombstones, "CHOICE")],
    ["events", targetIds(tombstones, "EVENT")]
  ]);

  const result = { ...clone(local), tombstones: clone(tombstones) };
  for (const [collection, idField] of COLLECTIONS) {
    const merged = mergeStrict(local[collection] ?? [], incoming[collection] ?? [], idField, collection);
    const directDeletes = deletedByType.get(collection) ?? new Set();
    result[collection] = merged.filter((item) => {
      const id = item[idField];
      const subjectId = collection === "children" ? item.id : item.childId ?? item.subjectId;
      const isSubjectContent = collection === "evidence" || collection === "hypotheses" || collection === "events";
      return !directDeletes.has(id) && !deletedSubjects.has(subjectId) &&
        !(isSubjectContent && clearedSubjectContent.has(subjectId));
    });
  }

  const choices = mergeChoices(local.choices ?? [], incoming.choices ?? []);
  result.choices = choices.filter((item) =>
    !deletedByType.get("choices").has(item.id) && !deletedSubjects.has(item.childId) &&
    !clearedSubjectContent.has(item.childId)
  );
  return result;
}

function validateTombstones(tombstones, householdId) {
  for (const item of tombstones) {
    if (!["org.foe.deletion-tombstone/v1", "org.foe.deletion-tombstone/v2"].includes(item.schema)) {
      throw new TypeError("unsupported deletion tombstone schema");
    }
    if (item.householdId !== householdId || typeof item.targetId !== "string" || item.targetId.length === 0 ||
        typeof item.authorId !== "string" || item.authorId.length === 0 ||
        typeof item.deviceId !== "string" || item.deviceId.length === 0 ||
        !Number.isFinite(Date.parse(item.deletedAt))) {
      throw new TypeError("invalid deletion tombstone");
    }
    if (String(item.targetType).toUpperCase() === "SUBJECT_CONTENT" &&
        (item.schema !== "org.foe.deletion-tombstone/v2" || item.subjectId !== item.targetId)) {
      throw new TypeError("subject-content deletion requires a v2 subject-bound tombstone");
    }
  }
}

function mergeStrict(left, right, idField, label) {
  const merged = new Map();
  for (const item of [...left, ...right]) {
    const id = item?.[idField];
    if (typeof id !== "string" || id.length === 0) throw new TypeError(`${label} ID must not be blank`);
    if (merged.has(id) && !sameValue(merged.get(id), item)) {
      throw new TypeError(`conflicting ${label} object: ${id}`);
    }
    if (!merged.has(id)) merged.set(id, clone(item));
  }
  return [...merged.values()];
}

function mergeChoices(left, right) {
  const merged = new Map();
  for (const item of [...left, ...right]) {
    const id = item?.id;
    if (typeof id !== "string" || id.length === 0) throw new TypeError("choice ID must not be blank");
    const current = merged.get(id);
    if (!current || sameValue(current, item)) {
      merged.set(id, clone(item));
    } else if (sameChoiceIdentity(current, item) && current.feedback == null && item.feedback != null) {
      merged.set(id, clone(item));
    } else if (!(sameChoiceIdentity(current, item) && current.feedback != null && item.feedback == null)) {
      throw new TypeError(`conflicting choice object: ${id}`);
    }
  }
  return [...merged.values()];
}

function sameChoiceIdentity(left, right) {
  const allowed = new Set(["chosen", "reflected"]);
  return left.id === right.id && left.childId === right.childId &&
    sameValue(left.opportunity, right.opportunity) && left.sourceEventId === right.sourceEventId &&
    left.chosenAt === right.chosenAt && allowed.has(left.status) && allowed.has(right.status);
}

function targetIds(tombstones, targetType) {
  return new Set(tombstones
    .filter((item) => String(item.targetType).toUpperCase() === targetType)
    .map((item) => item.targetId));
}

function sameValue(left, right) {
  return JSON.stringify(canonical(left)) === JSON.stringify(canonical(right));
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

const SYNC_ENVELOPE_FORMAT = "org.foe.encrypted-sync-envelope/v1";
const SYNC_CIPHER = "AES-256-GCM";
const SYNC_KEY_ID = /^[A-Za-z0-9_-]{16,128}$/;
const SYNC_ENVELOPE_FIELDS = new Set(["format", "cipher", "keyId", "nonce", "ciphertext"]);
const MAX_SYNC_FRAME_BYTES = 16 * 1024 * 1024;
const MAX_SYNC_ENVELOPE_BYTES = 24 * 1024 * 1024;

/** Creates a purpose-specific key handle without serializing it into transport data. */
export function createHouseholdSyncKey(keyId, keyBytes) {
  if (typeof keyId !== "string" || !SYNC_KEY_ID.test(keyId)) {
    throw new TypeError("sync key ID must be an opaque base64url-like identifier");
  }
  const material = toBytes(keyBytes, "household sync key");
  if (material.byteLength !== 32) throw new TypeError("household sync key must contain 256 bits");
  return Object.freeze({ keyId, keyBytes: material.slice() });
}

/** Seals complete sync-frame bytes before a folder, NAS, WebDAV, S3, or host can observe them. */
export async function sealSyncFrame(frameBytes, keyHandle) {
  const frame = toBytes(frameBytes, "sync frame");
  if (frame.byteLength === 0 || frame.byteLength > MAX_SYNC_FRAME_BYTES) {
    throw new RangeError("sync frame size is invalid");
  }
  const key = assertSyncKey(keyHandle);
  const nonce = webCrypto().getRandomValues(new Uint8Array(12));
  const cryptoKey = await webCrypto().subtle.importKey("raw", key.keyBytes.slice(), "AES-GCM", false, ["encrypt"]);
  const ciphertext = new Uint8Array(await webCrypto().subtle.encrypt(
    { name: "AES-GCM", iv: nonce, additionalData: syncAad(key.keyId), tagLength: 128 },
    cryptoKey,
    frame
  ));
  const envelope = {
    format: SYNC_ENVELOPE_FORMAT,
    cipher: SYNC_CIPHER,
    keyId: key.keyId,
    nonce: base64UrlEncode(nonce),
    ciphertext: base64UrlEncode(ciphertext)
  };
  return new TextEncoder().encode(JSON.stringify(envelope));
}

/**
 * Authenticates and opens an encrypted envelope. The returned bytes are still a
 * versioned sync frame and must pass its schema/hash/sequence verifier before merge.
 */
export async function openSyncEnvelope(envelopeBytes, keyHandle) {
  const encoded = toBytes(envelopeBytes, "encrypted sync envelope");
  if (encoded.byteLength === 0 || encoded.byteLength > MAX_SYNC_ENVELOPE_BYTES) {
    throw new RangeError("encrypted sync envelope size is invalid");
  }
  let envelope;
  try {
    envelope = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(encoded));
  } catch {
    throw new TypeError("encrypted sync envelope is invalid");
  }
  assertSyncEnvelope(envelope);
  const key = assertSyncKey(keyHandle);
  if (envelope.keyId !== key.keyId) throw new TypeError("encrypted sync envelope uses another key");
  const nonce = base64UrlDecode(envelope.nonce, "nonce");
  const ciphertext = base64UrlDecode(envelope.ciphertext, "ciphertext");
  if (nonce.byteLength !== 12) throw new TypeError("encrypted sync envelope nonce is invalid");
  if (ciphertext.byteLength < 16 || ciphertext.byteLength > MAX_SYNC_FRAME_BYTES + 16) {
    throw new RangeError("encrypted sync ciphertext size is invalid");
  }
  const cryptoKey = await webCrypto().subtle.importKey("raw", key.keyBytes.slice(), "AES-GCM", false, ["decrypt"]);
  try {
    return new Uint8Array(await webCrypto().subtle.decrypt(
      { name: "AES-GCM", iv: nonce, additionalData: syncAad(envelope.keyId), tagLength: 128 },
      cryptoKey,
      ciphertext
    ));
  } catch {
    throw new TypeError("encrypted sync key is wrong or the envelope was modified");
  }
}

export function assertSyncEnvelope(envelope) {
  if (!envelope || typeof envelope !== "object" || Array.isArray(envelope)) {
    throw new TypeError("encrypted sync envelope must be an object");
  }
  const fields = Object.keys(envelope);
  if (fields.length !== SYNC_ENVELOPE_FIELDS.size || fields.some((field) => !SYNC_ENVELOPE_FIELDS.has(field))) {
    throw new TypeError("encrypted sync envelope contains missing or unknown fields");
  }
  if (envelope.format !== SYNC_ENVELOPE_FORMAT) throw new TypeError("unsupported encrypted sync envelope format");
  if (envelope.cipher !== SYNC_CIPHER) throw new TypeError("unsupported encrypted sync cipher");
  if (typeof envelope.keyId !== "string" || !SYNC_KEY_ID.test(envelope.keyId)) throw new TypeError("encrypted sync key ID is invalid");
  if (typeof envelope.nonce !== "string" || typeof envelope.ciphertext !== "string") {
    throw new TypeError("encrypted sync envelope encoding is invalid");
  }
  return envelope;
}

function assertSyncKey(keyHandle) {
  if (!keyHandle || typeof keyHandle !== "object") throw new TypeError("household sync key is required");
  return createHouseholdSyncKey(keyHandle.keyId, keyHandle.keyBytes);
}

function toBytes(value, label) {
  if (value instanceof Uint8Array) return value.slice();
  if (value instanceof ArrayBuffer) return new Uint8Array(value.slice(0));
  throw new TypeError(`${label} must be bytes`);
}

function webCrypto() {
  if (!globalThis.crypto?.subtle || typeof globalThis.crypto.getRandomValues !== "function") {
    throw new Error("Web Crypto API is required");
  }
  return globalThis.crypto;
}

function syncAad(keyId) {
  return new TextEncoder().encode(`${SYNC_ENVELOPE_FORMAT}|${SYNC_CIPHER}|${keyId}|sync-frame`);
}

function base64UrlEncode(bytes) {
  let binary = "";
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return globalThis.btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

function base64UrlDecode(value, label) {
  if (!/^[A-Za-z0-9_-]+$/u.test(value) || value.length % 4 === 1) {
    throw new TypeError(`encrypted sync ${label} is invalid`);
  }
  const padded = value.replaceAll("-", "+").replaceAll("_", "/") + "=".repeat((4 - value.length % 4) % 4);
  let binary;
  try {
    binary = globalThis.atob(padded);
  } catch {
    throw new TypeError(`encrypted sync ${label} is invalid`);
  }
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

// The in-memory event store remains a behavioral prototype. The sync-envelope
// helpers are real Web Crypto framing, but persistence, enrollment, rotation,
// device signatures, checkpoints, and transports remain separate responsibilities.
