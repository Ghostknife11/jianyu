// The NAS family vault. One browser is the active writer at a time: a save
// that lands on a stale version is refused by the server and answered here by
// reloading the current ciphertext and merging it, never by overwriting.
//
// Two identifiers are deliberately separate:
//   - the server's household ID is an opaque 24-character handle the server
//     uses as a directory name;
//   - the household identity inside the encrypted state is a UUID that never
//     leaves the browser.
// Associated data binds ciphertext to the server handle and the state version.

import { mergeFamilyState } from "../../../../packages/foe-vault/src/index.js";
import {
  assertPassphrase,
  base64ToBytes,
  bytesToBase64,
  bytesToHex,
  deriveHouseholdKeys,
  KDF_ITERATIONS,
  KDF_NAME,
  newSalt,
  openState,
  randomObjectId,
  sealState,
  STATE_CIPHER,
  STATE_FORMAT
} from "./nas-crypto.js";
import {
  addCaregiver,
  addChild,
  assertFamilyState,
  createInitialFamilyState,
  migrateFamilyState
} from "./family-state.js";
import { ApiRequestError } from "./session.js";

const RECORD_ID = "primary";
const NONCE_BYTES = 12;

export class StateConflictError extends Error {
  constructor(details) {
    super("另一台设备已经更改过家庭记录，请重载后合并");
    this.name = "StateConflictError";
    this.currentVersion = details?.currentVersion ?? null;
    this.currentObjectId = details?.currentObjectId ?? null;
  }
}

export class VaultError extends Error {
  constructor(message) {
    super(message);
    this.name = "VaultError";
  }
}

/** In-memory record store; the browser supplies an IndexedDB-backed one. */
export function createMemoryStore(initial = new Map()) {
  const records = initial;
  return {
    async read() { return records.get(RECORD_ID) ?? null; },
    async write(record) { records.set(RECORD_ID, structuredClone(record)); },
    async clear() { records.delete(RECORD_ID); }
  };
}

export function createIndexedDbStore({ databaseName = "jianyu-nas-vault", storeName = "vaults" } = {}) {
  function requestOutcome(request) {
    return new Promise((resolve, reject) => {
      request.addEventListener("success", () => resolve(request.result), { once: true });
      request.addEventListener("error", () => reject(request.error), { once: true });
    });
  }
  async function open() {
    const request = indexedDB.open(databaseName, 1);
    request.addEventListener("upgradeneeded", () => {
      if (!request.result.objectStoreNames.contains(storeName)) {
        request.result.createObjectStore(storeName, { keyPath: "id" });
      }
    });
    return requestOutcome(request);
  }
  async function withStore(mode, operation) {
    const database = await open();
    try {
      const transaction = database.transaction(storeName, mode);
      const result = await operation(transaction.objectStore(storeName));
      await new Promise((resolve, reject) => {
        transaction.addEventListener("complete", resolve, { once: true });
        transaction.addEventListener("abort", () => reject(transaction.error), { once: true });
        transaction.addEventListener("error", () => reject(transaction.error), { once: true });
      });
      return result;
    } finally {
      database.close();
    }
  }
  return {
    read: () => withStore("readonly", (store) => requestOutcome(store.get(RECORD_ID))),
    write: (record) => withStore("readwrite", (store) => requestOutcome(store.put({ ...record, id: RECORD_ID }))),
    clear: () => withStore("readwrite", (store) => requestOutcome(store.delete(RECORD_ID)))
  };
}

function sealToBytes(nonce, ciphertext) {
  const nonceBytes = base64ToBytes(nonce);
  const cipherBytes = base64ToBytes(ciphertext);
  const combined = new Uint8Array(nonceBytes.length + cipherBytes.length);
  combined.set(nonceBytes, 0);
  combined.set(cipherBytes, nonceBytes.length);
  return combined;
}

function bytesToSealed(bytes) {
  if (bytes.byteLength <= NONCE_BYTES) throw new VaultError("密文对象内容不完整");
  return {
    nonce: bytesToBase64(bytes.subarray(0, NONCE_BYTES)),
    ciphertext: bytesToBase64(bytes.subarray(NONCE_BYTES))
  };
}

function assertRecord(record) {
  if (!record || typeof record !== "object") throw new VaultError("本设备还没有家庭保险箱");
  if (record.format !== STATE_FORMAT || record.cipher !== STATE_CIPHER || record.kdf !== KDF_NAME) {
    throw new VaultError("本设备的保险箱格式不受支持");
  }
  if (typeof record.householdId !== "string" || record.householdId === "") throw new VaultError("本设备的保险箱记录不完整");
  if (typeof record.salt !== "string" || !Number.isInteger(record.iterations)) throw new VaultError("本设备的保险箱参数缺失");
  return record;
}

export class NasFamilyVault {
  #api;
  #store;
  #keys;
  #record;
  #state;
  #serverVersion;
  #deviceId;

  constructor({ api, store, keys, record, state, serverVersion }) {
    this.#api = api;
    this.#store = store;
    this.#keys = keys;
    this.#record = record;
    this.#state = state;
    this.#serverVersion = serverVersion;
    this.#deviceId = record.deviceId;
  }

  get state() {
    return this.#state;
  }

  get householdId() {
    return this.#record.householdId;
  }

  get deviceId() {
    return this.#deviceId;
  }

  get stateVersion() {
    return this.#serverVersion;
  }

  get isUnlocked() {
    return Boolean(this.#keys && this.#state);
  }

  /**
   * Creates a household on this NAS. The passphrase never leaves the browser:
   * only the opaque handle, the salt, the iteration count, and a hash of the
   * authentication verifier are registered.
   */
  static async create({ passphrase, familyName, caregiverName, childName, birthDate }, { api, store }) {
    assertPassphrase(passphrase);
    if (!api) throw new VaultError("缺少 NAS 连接");
    const salt = newSalt();
    const keys = await deriveHouseholdKeys(passphrase, salt, KDF_ITERATIONS);
    const state = createInitialFamilyState({ familyName, caregiverName, childName, birthDate });
    const householdId = randomObjectId();
    const deviceId = globalThis.crypto.randomUUID();
    await api.registerHousehold({
      householdId,
      verifier: bytesToHex(keys.verifier),
      salt,
      iterations: KDF_ITERATIONS
    });
    // Registration alone grants no session; the writes that follow need one.
    await api.unlock({ householdId, verifier: bytesToHex(keys.verifier) });
    const vault = new NasFamilyVault({
      api,
      store,
      keys,
      record: {
        format: STATE_FORMAT,
        cipher: STATE_CIPHER,
        kdf: KDF_NAME,
        householdId,
        deviceId,
        salt,
        iterations: KDF_ITERATIONS,
        stateVersion: 0,
        objectId: null
      },
      state,
      serverVersion: 0
    });
    await vault.#commitNewState();
    return vault;
  }

  /** Unlocks with the local record and the passphrase. */
  static async unlock({ passphrase }, { api, store }) {
    assertPassphrase(passphrase);
    const record = assertRecord(await store.read());
    const keys = await deriveHouseholdKeys(passphrase, record.salt, record.iterations);
    let state;
    try {
      state = await openState(keys.vaultKey, record, record.householdId, record.stateVersion);
    } catch (error) {
      throw new VaultError(error.message);
    }
    const migrated = migrateFamilyState(state);
    const vault = new NasFamilyVault({
      api,
      store,
      keys,
      record,
      state: migrated.state,
      serverVersion: record.stateVersion
    });
    await vault.#openSession(record);
    return vault;
  }

  async #openSession(record) {
    try {
      await this.#api.unlock({ householdId: record.householdId, verifier: bytesToHex(this.#keys.verifier) });
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 401) {
        throw new VaultError("口令不正确，或这个家庭不在这台服务器上");
      }
      throw error;
    }
    await this.#reconcile();
  }

  /** Re-seals the in-memory state as a new immutable object and commits it. */
  async #commitNewState() {
    assertFamilyState(this.#state);
    const baseVersion = this.#serverVersion;
    const objectId = randomObjectId();
    const sealed = await sealState(this.#keys.vaultKey, this.#state, this.#record.householdId, baseVersion + 1);
    await this.#putObject(objectId, sealed);
    try {
      await this.#api.commitState({ objectId, baseVersion });
    } catch (error) {
      if (!(error instanceof ApiRequestError) || !error.isStateConflict) throw error;
      throw new StateConflictError(error.details);
    }
    this.#serverVersion = baseVersion + 1;
    this.#record.stateVersion = this.#serverVersion;
    this.#record.objectId = objectId;
    this.#record.nonce = sealed.nonce;
    this.#record.ciphertext = sealed.ciphertext;
    this.#record.updatedAt = new Date().toISOString();
    await this.#store.write(this.#record);
  }

  async #putObject(objectId, sealed) {
    const bytes = sealToBytes(sealed.nonce, sealed.ciphertext);
    try {
      await this.#api.putObject(objectId, bytes);
    } catch (error) {
      if (error instanceof ApiRequestError && error.isObjectConflict) {
        // Byte-identical retry after a lost response is idempotent; anything
        // else means this ID is taken and a fresh one is required.
        const existing = await this.#api.getObject(objectId);
        if (bytesToBase64(existing) !== bytesToBase64(bytes)) {
          throw new VaultError("服务器上已存在同一标识的不同内容，请重试");
        }
        return;
      }
      throw error;
    }
  }

  /** Pulls the server's current state and merges it with the local one. */
  async #reconcile() {
    const pointer = await this.#api.readStatePointer();
    if (pointer.version === this.#serverVersion && pointer.objectId === this.#record.objectId) return;
    if (pointer.version === 0) {
      if (this.#serverVersion === 0) return;
      throw new VaultError("服务器上已经没有这个家庭的记录，可能已被删除");
    }
    const bytes = await this.#api.getObject(pointer.objectId);
    const remote = await openState(this.#keys.vaultKey, bytesToSealed(bytes), this.#record.householdId, pointer.version);
    const migratedRemote = migrateFamilyState(remote);
    const merged = mergeFamilyState(this.#state, migratedRemote.state);
    this.#state = assertFamilyState(merged);
    this.#serverVersion = pointer.version;
    await this.#commitNewState();
  }

  /** Persists the in-memory state. Throws StateConflictError when stale. */
  async save() {
    if (!this.isUnlocked) throw new VaultError("家庭保险箱尚未解锁");
    await this.#commitNewState();
    return this.#serverVersion;
  }

  /** Reloads after a conflict reported by another writer and merges. */
  async reloadFromServer() {
    if (!this.isUnlocked) throw new VaultError("家庭保险箱尚未解锁");
    this.#serverVersion = 0;
    this.#record.objectId = null;
    await this.#reconcile();
    return this.#serverVersion;
  }

  addChild(entry) {
    const child = addChild(this.#state, entry);
    return child;
  }

  addCaregiver(entry) {
    return addCaregiver(this.#state, entry);
  }

  /** Drops the session and every in-memory key. The local record stays. */
  async lock() {
    if (this.#api) await this.#api.lock().catch(() => {});
    this.#keys = null;
    this.#state = null;
  }

  /** Deletes every server-side object and the local record. */
  async eraseEverywhere() {
    await this.#api.eraseHousehold().catch(() => {});
    await this.#store.clear();
    this.#keys = null;
    this.#state = null;
  }
}
