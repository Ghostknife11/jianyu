import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  AUTH_INFO,
  KDF_ITERATIONS,
  MIN_PASSPHRASE_LENGTH,
  RECOVERY_INFO,
  STATE_CIPHER,
  STATE_FORMAT,
  VAULT_INFO,
  assertPassphrase,
  base64ToBytes,
  base64UrlFromBytes,
  base64UrlToBytes,
  bytesToBase64,
  bytesToHex,
  deriveHouseholdKeys,
  deriveRecoveryKey,
  digestState,
  generateRecoveryCode,
  hexToBytes,
  KDF_NAME,
  newSalt,
  normalizeRecoveryCode,
  openState,
  randomBytes,
  randomObjectId,
  recoveryCodeCheck,
  sealState
} from "../../apps/jianyu-web-nas/web/src/nas-crypto.js";

const PASSPHRASE = "family-passphrase-2026";
const HOUSEHOLD = "AaBbCcDdEeFfGgHh12345678";

describe("nas client cryptography", () => {
  it("derives two purpose-bound keys from one passphrase", async () => {
    const salt = newSalt();
    const keys = await deriveHouseholdKeys(PASSPHRASE, salt, KDF_ITERATIONS);
    assert.equal(bytesToHex(keys.verifier).length, 64);
    assert.equal(keys.vaultKey.type, "secret");
    assert.equal(keys.vaultKey.algorithm.name, "AES-GCM");
    assert.equal(keys.vaultKey.algorithm.length, 256);

    const again = await deriveHouseholdKeys(PASSPHRASE, salt, KDF_ITERATIONS);
    assert.equal(bytesToHex(again.verifier), bytesToHex(keys.verifier), "the same passphrase and salt derive the same verifier");
    const sealed = await sealState(keys.vaultKey, { a: 1 }, HOUSEHOLD, 1);
    assert.deepEqual(await openState(again.vaultKey, sealed, HOUSEHOLD, 1), { a: 1 });
  });

  it("keeps the recovery key and the vault key apart", async () => {
    const salt = newSalt();
    const { vaultKey } = await deriveHouseholdKeys(PASSPHRASE, salt, KDF_ITERATIONS);
    assert.equal(vaultKey.extractable, false, "the vault key must never be exportable");

    const sealed = await sealState(vaultKey, { note: "家庭记录" }, HOUSEHOLD, 1);
    const recoveryKey = await deriveRecoveryKey(generateRecoveryCode(), salt, KDF_ITERATIONS);
    await assert.rejects(() => openState(recoveryKey, sealed, HOUSEHOLD, 1), (error) => error instanceof TypeError);
    assert.notEqual(AUTH_INFO, VAULT_INFO);
    assert.notEqual(VAULT_INFO, RECOVERY_INFO);
  });

  it("binds sealed state to its household and version", async () => {
    const salt = newSalt();
    const { vaultKey } = await deriveHouseholdKeys(PASSPHRASE, salt, KDF_ITERATIONS);
    const sealed = await sealState(vaultKey, { household: "隅之家" }, HOUSEHOLD, 7);

    assert.deepEqual(await openState(vaultKey, sealed, HOUSEHOLD, 7), { household: "隅之家" });
    for (const [householdId, version] of [[HOUSEHOLD, 8], ["ZzYyXxWwVvUu1234567890", 7]]) {
      await assert.rejects(() => openState(vaultKey, sealed, householdId, version), (error) => error instanceof TypeError);
    }
  });

  it("fails closed on a tampered ciphertext or nonce", async () => {
    const salt = newSalt();
    const { vaultKey } = await deriveHouseholdKeys(PASSPHRASE, salt, KDF_ITERATIONS);
    const sealed = await sealState(vaultKey, { note: "家庭记录" }, HOUSEHOLD, 1);
    const bytes = base64ToBytes(sealed.ciphertext);
    bytes[0] ^= 0xff;
    await assert.rejects(
      () => openState(vaultKey, { ...sealed, ciphertext: bytesToBase64(bytes) }, HOUSEHOLD, 1),
      (error) => error instanceof TypeError && error.message.includes("篡改")
    );

    const flipped = base64ToBytes(sealed.nonce);
    flipped[11] ^= 0x01;
    await assert.rejects(
      () => openState(vaultKey, { ...sealed, nonce: bytesToBase64(flipped) }, HOUSEHOLD, 1),
      (error) => error instanceof TypeError
    );
  });

  it("refuses a short passphrase before deriving anything", async () => {
    assert.throws(() => assertPassphrase("short"), (error) => error instanceof TypeError);
    assert.throws(() => assertPassphrase("x".repeat(MIN_PASSPHRASE_LENGTH - 1)), TypeError);
    assert.doesNotThrow(() => assertPassphrase("x".repeat(MIN_PASSPHRASE_LENGTH)));
  });

  it("opens a recovery bundle only with its own code", async () => {
    const salt = newSalt();
    const code = generateRecoveryCode();
    const normalized = normalizeRecoveryCode(code.toLowerCase().replaceAll(" ", ""));
    assert.equal(normalized.length, 20);
    assert.equal(normalizeRecoveryCode(code), normalized, "the code survives reformatting");

    const key = await deriveRecoveryKey(code, salt, KDF_ITERATIONS);
    const sealed = await sealState(key, { recovered: true }, HOUSEHOLD, 1);
    assert.deepEqual(await openState(key, sealed, HOUSEHOLD, 1), { recovered: true });

    const check = await recoveryCodeCheck(code, salt);
    assert.equal(check.length, 64);
    assert.notEqual(await recoveryCodeCheck(generateRecoveryCode(), salt), check, "a different code gives a different check");
    assert.throws(() => normalizeRecoveryCode("too-short"), TypeError);
  });

  it("encodes bytes without ambiguity", () => {
    const bytes = randomBytes(32);
    assert.deepEqual(base64ToBytes(bytesToBase64(bytes)), bytes);
    assert.deepEqual(base64UrlToBytes(base64UrlFromBytes(bytes)), bytes);
    assert.equal(base64UrlFromBytes(bytes).includes("="), false);
    assert.equal(bytesToHex(hexToBytes("00ff10")), "00ff10");
    assert.throws(() => base64UrlToBytes("not*valid"), TypeError);
    assert.throws(() => hexToBytes("xyz"), TypeError);
    assert.throws(() => randomBytes(0), RangeError);
  });

  it("generates opaque 24-character object identifiers", () => {
    const ids = new Set();
    for (let index = 0; index < 64; index += 1) {
      const id = randomObjectId();
      assert.match(id, /^[A-Za-z0-9_-]{24}$/u);
      ids.add(id);
    }
    assert.equal(ids.size, 64, "identifiers must not repeat");
  });

  it("digests state deterministically", async () => {
    const state = { household: { id: "h" }, events: [1, 2] };
    assert.equal(await digestState(state), await digestState(structuredClone(state)));
    assert.notEqual(await digestState(state), await digestState({ household: { id: "other" }, events: [1, 2] }));
  });

  it("records the frozen v1 parameters", () => {
    assert.equal(STATE_FORMAT, "org.jianyu.web-family-state/v1");
    assert.equal(STATE_CIPHER, "AES-256-GCM");
    assert.equal(KDF_NAME, "PBKDF2-SHA-256+HKDF-SHA-256");
    assert.equal(KDF_ITERATIONS, 210_000);
  });
});
