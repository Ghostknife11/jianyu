import { lstat, mkdir, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { assertEncryptedSyncObject, assertOpaqueSyncObjectId } from "../../../packages/provider-sdk/src/index.js";

const HOUSEHOLD_SEGMENT = /^[A-Za-z0-9_-]{24}$/;

export class ObjectConflictError extends Error {
  constructor(objectId) {
    super(`object ${objectId} already exists with different content`);
    this.code = "object-conflict";
  }
}

export class ObjectLimitError extends Error {
  constructor() {
    super("this household has reached the server object limit");
    this.code = "object-limit";
  }
}

function assertHouseholdId(householdId) {
  if (typeof householdId !== "string" || !HOUSEHOLD_SEGMENT.test(householdId)) {
    throw new TypeError("householdId must be a canonical 24-character opaque identifier");
  }
  return householdId;
}

function assertObjectId(objectId) {
  try {
    return assertOpaqueSyncObjectId(objectId);
  } catch {
    const error = new TypeError("object ID must be canonical 24-character opaque Base64URL");
    error.code = "invalid-object-id";
    throw error;
  }
}

function objectPath(householdDirectory, objectId) {
  return join(householdDirectory, objectId);
}

async function listObjectIds(householdDirectory) {
  const entries = await readdir(householdDirectory).catch(() => []);
  return entries.filter((entry) => HOUSEHOLD_SEGMENT.test(entry)).sort();
}

async function statObject(path) {
  try {
    const stats = await lstat(path);
    // A symlink is not an object this store ever wrote, so it is rejected.
    if (!stats.isFile() || stats.isSymbolicLink()) return null;
    return stats;
  } catch {
    return null;
  }
}

/**
 * Ciphertext object store implementing ADR 0008 semantics over HTTP.
 *
 * The store sees opaque IDs, byte counts, and timing — never family plaintext,
 * semantic filenames, or merge authority. Writes are immutable: retrying an ID
 * with identical bytes succeeds idempotently, and different bytes fail closed.
 */
export class CiphertextObjectStore {
  #root;
  #config;

  constructor(config) {
    this.#config = config;
    this.#root = join(config.dataDir, "objects");
  }

  async prepare() {
    await mkdir(this.#root, { recursive: true });
  }

  #householdDirectory(householdId) {
    return join(this.#root, assertHouseholdId(householdId));
  }

  async put(householdId, objectId, bytes) {
    const household = assertHouseholdId(householdId);
    const id = assertObjectId(objectId);
    assertEncryptedSyncObject(bytes);
    if (bytes.byteLength > this.#config.maxObjectBytes) {
      throw new RangeError(`object exceeds ${this.#config.maxObjectBytes} bytes`);
    }
    const directory = this.#householdDirectory(household);
    await mkdir(directory, { recursive: true });
    const existing = await listObjectIds(directory);
    if (existing.length >= this.#config.maxObjectsPerHousehold && !existing.includes(id)) {
      throw new ObjectLimitError();
    }
    const path = objectPath(directory, id);
    try {
      await writeFile(path, bytes, { flag: "wx" });
      return { created: true, sizeBytes: bytes.byteLength };
    } catch (error) {
      if (error?.code !== "EEXIST") throw error;
    }
    const stored = await statObject(path);
    if (!stored || stored.size !== bytes.byteLength) throw new ObjectConflictError(id);
    const current = await readFile(path);
    if (!current.equals(Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength))) {
      throw new ObjectConflictError(id);
    }
    return { created: false, sizeBytes: stored.size };
  }

  async get(householdId, objectId) {
    const directory = this.#householdDirectory(householdId);
    const path = objectPath(directory, assertObjectId(objectId));
    const stats = await statObject(path);
    if (!stats) return null;
    if (stats.size > this.#config.maxObjectBytes) return null;
    const bytes = await readFile(path);
    return new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  }

  async list(householdId, cursor, limit) {
    const directory = this.#householdDirectory(householdId);
    const pageLimit = Math.max(1, Math.min(Number.isInteger(limit) ? limit : this.#config.objectPageDefault, this.#config.objectPageMax));
    const ids = await listObjectIds(directory);
    const start = typeof cursor === "string" && cursor !== "" ? ids.indexOf(cursor) + 1 : 0;
    if (typeof cursor === "string" && cursor !== "" && start === 0) {
      const error = new TypeError("cursor is not a valid position in this household's object list");
      error.code = "invalid-cursor";
      throw error;
    }
    const page = ids.slice(start, start + pageLimit);
    const objects = [];
    for (const id of page) {
      const stats = await statObject(objectPath(directory, id));
      if (stats) objects.push({ objectId: id, sizeBytes: stats.size });
    }
    const nextCursor = start + pageLimit < ids.length ? ids[start + page.length - 1] : null;
    return { objects, nextCursor };
  }

  async delete(householdId, objectId) {
    const directory = this.#householdDirectory(householdId);
    const path = objectPath(directory, assertObjectId(objectId));
    if (!(await statObject(path))) return false;
    await rm(path, { force: true });
    return true;
  }

  async count(householdId) {
    return (await listObjectIds(this.#householdDirectory(householdId))).length;
  }

  async erase(householdId) {
    await rm(this.#householdDirectory(householdId), { recursive: true, force: true });
  }
}
