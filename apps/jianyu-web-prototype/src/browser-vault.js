import { migrateFamilyState } from "./vault-migrations.js";

const DATABASE_NAME = "jianyu-family-vault";
const STORE_NAME = "encrypted-vaults";
const VAULT_ID = "primary";
const ITERATIONS = 210_000;

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function bytesToBase64(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToBytes(value) {
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function requestResult(request) {
  return new Promise((resolve, reject) => {
    request.addEventListener("success", () => resolve(request.result), { once: true });
    request.addEventListener("error", () => reject(request.error), { once: true });
  });
}

async function openDatabase() {
  const request = indexedDB.open(DATABASE_NAME, 1);
  request.addEventListener("upgradeneeded", () => {
    if (!request.result.objectStoreNames.contains(STORE_NAME)) {
      request.result.createObjectStore(STORE_NAME, { keyPath: "id" });
    }
  });
  return requestResult(request);
}

async function withStore(mode, operation) {
  const database = await openDatabase();
  try {
    const transaction = database.transaction(STORE_NAME, mode);
    const result = await operation(transaction.objectStore(STORE_NAME));
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

async function getRecord() {
  return withStore("readonly", (store) => requestResult(store.get(VAULT_ID)));
}

async function putRecord(record) {
  return withStore("readwrite", (store) => requestResult(store.put(record)));
}

async function deriveKey(passphrase, salt, iterations = ITERATIONS) {
  const material = await crypto.subtle.importKey(
    "raw",
    encoder.encode(passphrase),
    "PBKDF2",
    false,
    ["deriveKey"]
  );
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );
}

async function encryptState(key, state) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plaintext = encoder.encode(JSON.stringify(state));
  const ciphertext = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plaintext);
  return { iv: bytesToBase64(iv), ciphertext: bytesToBase64(new Uint8Array(ciphertext)) };
}

async function decryptState(key, record) {
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: base64ToBytes(record.iv) },
    key,
    base64ToBytes(record.ciphertext)
  );
  return JSON.parse(decoder.decode(plaintext));
}

export class BrowserFamilyVault {
  #key;
  #record;

  constructor(key, record) {
    this.#key = key;
    this.#record = record;
  }

  static async exists() {
    return Boolean(await getRecord());
  }

  static async create(passphrase, initialState) {
    if (typeof passphrase !== "string" || passphrase.length < 8) {
      throw new TypeError("保险箱口令至少需要 8 个字符");
    }
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const key = await deriveKey(passphrase, salt);
    const encrypted = await encryptState(key, initialState);
    const record = {
      id: VAULT_ID,
      format: "org.jianyu.browser-vault/v1",
      cipher: "AES-GCM-256",
      kdf: "PBKDF2-SHA-256",
      iterations: ITERATIONS,
      salt: bytesToBase64(salt),
      ...encrypted,
      updatedAt: new Date().toISOString()
    };
    await putRecord(record);
    return new BrowserFamilyVault(key, record);
  }

  static async unlock(passphrase) {
    const record = await getRecord();
    if (!record) throw new Error("本设备还没有家庭保险箱");
    try {
      const key = await deriveKey(passphrase, base64ToBytes(record.salt), record.iterations);
      const decrypted = await decryptState(key, record);
      const migrated = migrateFamilyState(decrypted);
      const vault = new BrowserFamilyVault(key, record);
      if (migrated.migrated) await vault.save(migrated.state);
      return { vault, state: migrated.state };
    } catch {
      throw new Error("口令不正确，或保险箱数据已经损坏");
    }
  }

  static async importEncrypted(record) {
    if (
      record?.format !== "org.jianyu.browser-vault/v1" ||
      record?.cipher !== "AES-GCM-256" ||
      record?.kdf !== "PBKDF2-SHA-256" ||
      record?.id !== VAULT_ID ||
      !record.salt || !record.iv || !record.ciphertext
    ) {
      throw new TypeError("这不是受支持的见隅加密备份");
    }
    await putRecord(record);
  }

  async save(state) {
    const encrypted = await encryptState(this.#key, state);
    this.#record = { ...this.#record, ...encrypted, updatedAt: new Date().toISOString() };
    await putRecord(this.#record);
  }

  exportEncrypted() {
    return structuredClone(this.#record);
  }

  static async erase() {
    return withStore("readwrite", (store) => requestResult(store.delete(VAULT_ID)));
  }
}

export function createInitialFamilyState({ familyName, caregiverName, childName, birthYear }) {
  const now = new Date().toISOString();
  const childId = childName ? crypto.randomUUID() : null;
  const childMemberId = childName ? crypto.randomUUID() : null;
  const child = childName
    ? [{ id: childId, memberId: childMemberId, displayName: childName, birthYear: Number(birthYear), createdAt: now }]
    : [];
  return {
    schema: "org.jianyu.family-vault/v2",
    household: { id: crypto.randomUUID(), name: familyName, createdAt: now },
    members: [
      { id: crypto.randomUUID(), role: "caregiver", displayName: caregiverName, createdAt: now },
      ...(childName ? [{ id: childMemberId, role: "child", subjectId: childId, displayName: childName, createdAt: now }] : [])
    ],
    children: child,
    events: [],
    choices: [],
    preferences: { theme: "field-guide", quietNotifications: true }
  };
}
