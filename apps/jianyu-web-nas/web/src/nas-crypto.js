// Client-side cryptography for the NAS web app. Everything here runs on Web
// Crypto only, so the same module is exercised by the browser and by
// `node --test` without a shim. The server never sees any of these values
// except the opaque household ID and a hash of the authentication verifier.

const encoder = new TextEncoder();

export const STATE_FORMAT = "org.jianyu.web-family-state/v1";
export const STATE_CIPHER = "AES-256-GCM";
export const KDF_NAME = "PBKDF2-SHA-256+HKDF-SHA-256";
export const KDF_ITERATIONS = 210_000;
export const RECOVERY_KDF_NAME = "PBKDF2-SHA-256+HKDF-SHA-256";
export const AUTH_INFO = "nas-auth-v1";
export const VAULT_INFO = "nas-vault-v1";
export const RECOVERY_INFO = "nas-recovery-v1";
export const MIN_PASSPHRASE_LENGTH = 8;

const SALT_BYTES = 16;
const KEY_BYTES = 32;
const NONCE_BYTES = 12;
const CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
const CODE_GROUPS = 4;
const CODE_GROUP_LENGTH = 5;

export function randomBytes(length) {
  if (!Number.isInteger(length) || length < 1) throw new RangeError("随机字节长度无效");
  return globalThis.crypto.getRandomValues(new Uint8Array(length));
}

export function randomObjectId() {
  // 24 base64url characters, matching the server's opaque object ID rule.
  const bytes = randomBytes(18);
  return base64UrlFromBytes(bytes);
}

export function bytesToBase64(bytes) {
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return globalThis.btoa(binary);
}

export function base64ToBytes(value) {
  if (typeof value !== "string") throw new TypeError("Base64 必须是字符串");
  let binary;
  try {
    binary = globalThis.atob(value);
  } catch {
    throw new TypeError("Base64 无效");
  }
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

export function base64UrlFromBytes(bytes) {
  return bytesToBase64(bytes).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

export function base64UrlToBytes(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/u.test(value) || value.length % 4 === 1) {
    throw new TypeError("Base64URL 无效");
  }
  const padded = value.replaceAll("-", "+").replaceAll("_", "/") + "=".repeat((4 - value.length % 4) % 4);
  return base64ToBytes(padded);
}

export function bytesToHex(bytes) {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function hexToBytes(value) {
  if (typeof value !== "string" || value.length === 0 || value.length % 2 !== 0 || !/^[0-9a-fA-F]+$/u.test(value)) {
    throw new TypeError("十六进制无效");
  }
  return Uint8Array.from(value.match(/../gu), (pair) => Number.parseInt(pair, 16));
}

async function sha256(bytes) {
  return new Uint8Array(await globalThis.crypto.subtle.digest("SHA-256", bytes));
}

/** PBKDF2-SHA-256 master secret. Both purpose keys are expanded from it. */
async function deriveMaster(secret, salt, iterations, label) {
  if (typeof secret !== "string" || secret.length === 0) throw new TypeError(`${label}不能为空`);
  if (typeof salt !== "string" || salt.length === 0) throw new TypeError(`${label}用的盐值缺失`);
  if (!Number.isInteger(iterations) || iterations < 1) throw new TypeError(`${label}用的迭代次数无效`);
  const material = await globalThis.crypto.subtle.importKey(
    "raw",
    encoder.encode(secret.normalize("NFKC")),
    "PBKDF2",
    false,
    ["deriveBits"]
  );
  return new Uint8Array(await globalThis.crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: base64ToBytes(salt), iterations },
    material,
    KEY_BYTES * 8
  ));
}

/** Domain separation: one master secret, two purpose-bound keys, never reused. */
async function expand(master, info) {
  const key = await globalThis.crypto.subtle.importKey("raw", master, "HKDF", false, ["deriveBits"]);
  return new Uint8Array(await globalThis.crypto.subtle.deriveBits(
    { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: encoder.encode(info) },
    key,
    KEY_BYTES * 8
  ));
}

async function importVaultKey(keyBytes) {
  return globalThis.crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, ["encrypt", "decrypt"]);
}

function stateAad(householdId, stateVersion) {
  return encoder.encode(`${STATE_FORMAT}|${STATE_CIPHER}|${householdId}|${stateVersion}`);
}

/**
 * Associated data for a browser-local secret. It carries no version, because
 * the record is rewritten in place rather than migrated, and it names its own
 * format so a sealed secret can never be replayed as family state.
 */
function localSecretAad(format, householdId) {
  return encoder.encode(`${format}|${STATE_CIPHER}|${householdId}`);
}

/**
 * The two keys a household needs. `verifier` travels to the server (which
 * keeps only its SHA-256 hash); `vaultKey` never leaves this browser.
 */
export async function deriveHouseholdKeys(passphrase, salt, iterations) {
  if (typeof passphrase !== "string" || passphrase.length < MIN_PASSPHRASE_LENGTH) {
    throw new TypeError(`保险箱口令至少需要 ${MIN_PASSPHRASE_LENGTH} 个字符`);
  }
  const master = await deriveMaster(passphrase, salt, iterations, "口令");
  const verifier = await expand(master, AUTH_INFO);
  const vaultKey = await importVaultKey(await expand(master, VAULT_INFO));
  return { salt, iterations, verifier, vaultKey };
}

export function newSalt() {
  return bytesToBase64(randomBytes(SALT_BYTES));
}

/** Random recovery code, grouped for reading aloud and typing by hand. */
export function generateRecoveryCode() {
  const characters = [];
  for (let index = 0; index < CODE_GROUPS * CODE_GROUP_LENGTH; index += 1) {
    characters.push(CODE_ALPHABET[randomBytes(1)[0] % CODE_ALPHABET.length]);
  }
  return characters.join("").replace(/(.{5})/gu, "$1-").replace(/-$/u, "");
}

export function normalizeRecoveryCode(input) {
  if (typeof input !== "string") throw new TypeError("恢复码必须是字符串");
  const normalized = input.toUpperCase().replaceAll(/[^2-9A-HJ-NP-Z]/gu, "");
  if (normalized.length !== CODE_GROUPS * CODE_GROUP_LENGTH) throw new TypeError("恢复码长度不正确");
  return normalized;
}

/**
 * Cheap pre-check so a mistyped code is refused before the expensive
 * derivation. It authenticates the code against this bundle's salt only.
 */
export async function recoveryCodeCheck(code, salt) {
  const digest = await sha256(encoder.encode(`${normalizeRecoveryCode(code)}|${salt}`));
  return bytesToHex(digest);
}

export async function deriveRecoveryKey(code, salt, iterations) {
  const master = await deriveMaster(normalizeRecoveryCode(code), salt, iterations, "恢复码");
  return importVaultKey(await expand(master, RECOVERY_INFO));
}

export async function sealState(vaultKey, state, householdId, stateVersion) {
  const nonce = randomBytes(NONCE_BYTES);
  const plaintext = encoder.encode(JSON.stringify(state));
  const ciphertext = new Uint8Array(await globalThis.crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce, additionalData: stateAad(householdId, stateVersion), tagLength: 128 },
    vaultKey,
    plaintext
  ));
  return { nonce: bytesToBase64(nonce), ciphertext: bytesToBase64(ciphertext) };
}

export async function openState(vaultKey, sealed, householdId, stateVersion) {
  if (!sealed || typeof sealed.nonce !== "string" || typeof sealed.ciphertext !== "string") {
    throw new TypeError("密文记录不完整");
  }
  let plaintext;
  try {
    plaintext = await globalThis.crypto.subtle.decrypt(
      { name: "AES-GCM", iv: base64ToBytes(sealed.nonce), additionalData: stateAad(householdId, stateVersion), tagLength: 128 },
      vaultKey,
      base64ToBytes(sealed.ciphertext)
    );
  } catch {
    throw new TypeError("口令不正确，或密文已被篡改");
  }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(plaintext));
}

export async function digestState(state) {
  return bytesToHex(await sha256(encoder.encode(JSON.stringify(state))));
}

/**
 * Seals a browser-local secret, such as an AI provider key. The value stays on
 * this device: it is never part of the family state, so it is never merged,
 * bundled, or uploaded.
 */
export async function sealLocalSecret(vaultKey, value, { format, householdId }) {
  if (typeof format !== "string" || format === "") throw new TypeError("本地加密记录需要格式名称");
  if (typeof householdId !== "string" || householdId === "") throw new TypeError("本地加密记录需要家庭标识");
  const nonce = randomBytes(NONCE_BYTES);
  const plaintext = encoder.encode(JSON.stringify(value));
  const ciphertext = new Uint8Array(await globalThis.crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce, additionalData: localSecretAad(format, householdId), tagLength: 128 },
    vaultKey,
    plaintext
  ));
  return { format, cipher: STATE_CIPHER, nonce: bytesToBase64(nonce), ciphertext: bytesToBase64(ciphertext) };
}

export async function openLocalSecret(vaultKey, sealed, { format, householdId }) {
  if (!sealed || typeof sealed !== "object" || Array.isArray(sealed)) throw new TypeError("本地加密记录缺失");
  if (sealed.format !== format) throw new TypeError("本地加密记录格式不受支持");
  if (sealed.cipher !== STATE_CIPHER) throw new TypeError("本地加密记录的加密方式不受支持");
  if (typeof sealed.nonce !== "string" || typeof sealed.ciphertext !== "string") {
    throw new TypeError("本地加密记录不完整");
  }
  let plaintext;
  try {
    plaintext = await globalThis.crypto.subtle.decrypt(
      { name: "AES-GCM", iv: base64ToBytes(sealed.nonce), additionalData: localSecretAad(format, householdId), tagLength: 128 },
      vaultKey,
      base64ToBytes(sealed.ciphertext)
    );
  } catch {
    throw new TypeError("本地加密记录无法打开，可能属于另一个家庭或已被篡改");
  }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(plaintext));
}

export function assertPassphrase(passphrase) {
  if (typeof passphrase !== "string" || passphrase.length < MIN_PASSPHRASE_LENGTH) {
    throw new TypeError(`保险箱口令至少需要 ${MIN_PASSPHRASE_LENGTH} 个字符`);
  }
  if (passphrase.normalize("NFKC").length < MIN_PASSPHRASE_LENGTH) {
    throw new TypeError(`保险箱口令至少需要 ${MIN_PASSPHRASE_LENGTH} 个字符`);
  }
}
