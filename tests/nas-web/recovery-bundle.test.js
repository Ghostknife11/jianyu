import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";
import { NasApi } from "../../apps/jianyu-web-nas/web/src/session.js";
import {
  NasFamilyVault,
  VaultError,
  createMemoryStore
} from "../../apps/jianyu-web-nas/web/src/nas-vault.js";
import {
  assertRecoveryBundleShape,
  exportRecoveryBundle,
  parseRecoveryBundle,
  previewRecoveryBundle,
  RecoveryBundleError,
  RECOVERY_BUNDLE_FORMAT,
  serializeRecoveryBundle
} from "../../apps/jianyu-web-nas/web/src/recovery-bundle.js";
import { createCookieFetch, startServer, stopServer } from "./helpers.mjs";

const PASSPHRASE = "family-passphrase-2026";
const SECOND_PASSPHRASE = "second-device-passphrase";
const FAMILY = {
  familyName: "隅之家",
  caregiverName: "妈妈",
  childName: "小隅",
  birthDate: "2013-09-15"
};

function apiFor(baseUrl, log = null) {
  const inner = createCookieFetch();
  return new NasApi({
    baseUrl,
    fetchImpl: async (url, options = {}) => {
      log?.push(`${options.method ?? "GET"} ${url}`);
      return inner(url, options);
    }
  });
}

async function createVault(baseUrl, log = null) {
  const store = createMemoryStore();
  const api = apiFor(baseUrl, log);
  const vault = await NasFamilyVault.create({ passphrase: PASSPHRASE, ...FAMILY }, { api, store });
  return { vault, store, api };
}

/** Reads back every object the household has stored, through the same API. */
async function storedObjects(api) {
  const objects = [];
  let cursor = "";
  for (let guard = 0; guard < 8; guard += 1) {
    const page = await api.listObjects({ cursor, limit: 64 });
    objects.push(...page.objects);
    if (!page.nextCursor) break;
    cursor = page.nextCursor;
  }
  return objects;
}

describe("recovery bundle", () => {
  let instance;

  beforeEach(async () => {
    instance = await startServer();
  });

  afterEach(async () => {
    await stopServer(instance);
  });

  it("round-trips through export and preview", async () => {
    const { vault } = await createVault(instance.baseUrl);
    vault.addChild({ displayName: "二宝", birthDate: "2024-05-01", authorId: vault.state.members[0].id });
    await vault.save();

    const { bundle, recoveryCode } = await exportRecoveryBundle({ vault });
    assert.equal(bundle.format, RECOVERY_BUNDLE_FORMAT);
    assert.match(recoveryCode, /^[2-9A-HJ-NP-Z]{5}(-[2-9A-HJ-NP-Z]{5}){3}$/u);

    const preview = await previewRecoveryBundle(bundle, recoveryCode);
    assert.equal(preview.householdName, "隅之家");
    assert.equal(preview.childCount, 2);
    assert.deepEqual(preview.childNames, ["小隅", "二宝"]);
    assert.equal(preview.stateVersion, vault.stateVersion);
    assert.equal(preview.householdId, vault.householdId);
  });

  it("keeps the bundle unreadable without the code", async () => {
    const { vault } = await createVault(instance.baseUrl);
    const { bundle } = await exportRecoveryBundle({ vault });
    const serialized = serializeRecoveryBundle(bundle);
    for (const secret of ["隅之家", "小隅", "妈妈", "2013-09-15", PASSPHRASE, vault.serverHandle]) {
      assert.equal(serialized.includes(secret), false, `the bundle must not contain ${secret}`);
    }
    await assert.rejects(() => previewRecoveryBundle(bundle, "23456-23456-23456-23456"), RecoveryBundleError);
  });

  it("refuses a wrong code, a tampered bundle, and a swapped household", async () => {
    const { vault } = await createVault(instance.baseUrl);
    const { bundle, recoveryCode } = await exportRecoveryBundle({ vault });

    const groups = recoveryCode.split("-");
    groups[3] = groups[3] === "AAAAA" ? "BBBBB" : "AAAAA";
    await assert.rejects(() => previewRecoveryBundle(bundle, groups.join("-")), /恢复码与这个恢复包不匹配/);

    const tampered = { ...bundle, ciphertext: `${bundle.ciphertext.slice(0, -4)}AAAA` };
    await assert.rejects(() => previewRecoveryBundle(tampered, recoveryCode), /恢复码不正确，或恢复包已被篡改/);

    // The associated data binds the ciphertext to the declared household, so
    // pointing the bundle at another identity fails to open at all.
    const swapped = { ...bundle, householdId: "0f0f0f0f-0f0f-0f0f-0f0f-0f0f0f0f0f0f" };
    await assert.rejects(() => previewRecoveryBundle(swapped, recoveryCode), /恢复码不正确，或恢复包已被篡改/);
  });

  it("refuses a file that is not a bundle before deriving anything", () => {
    assert.throws(() => assertRecoveryBundleShape({ format: "org.foe.portable-family-bundle/v1" }), /恢复包格式不受支持/);
    assert.throws(() => assertRecoveryBundleShape({ ...minimalBundle(), kdf: "scrypt" }), /密钥派生参数不受支持/);
    assert.throws(() => assertRecoveryBundleShape({ ...minimalBundle(), salt: "" }), /缺少盐值/);
    assert.throws(() => assertRecoveryBundleShape({ ...minimalBundle(), iterations: 0 }), /迭代次数无效/);
    assert.throws(() => assertRecoveryBundleShape({ ...minimalBundle(), stateVersion: 0 }), /状态版本无效/);
    assert.throws(() => parseRecoveryBundle("not json"), /恢复包文件不是有效的 JSON/);
  });

  it("imports into a second browser without touching any server", async () => {
    const log = [];
    const { vault } = await createVault(instance.baseUrl, log);
    vault.addChild({ displayName: "二宝", birthDate: "2024-05-01", authorId: vault.state.members[0].id });
    await vault.save();
    const { bundle, recoveryCode } = await exportRecoveryBundle({ vault });

    log.length = 0;
    const preview = await previewRecoveryBundle(bundle, recoveryCode);
    const secondStore = createMemoryStore();
    const second = await NasFamilyVault.importRecoveryBundle(
      { preview, passphrase: SECOND_PASSPHRASE },
      { api: apiFor(instance.baseUrl, log), store: secondStore }
    );

    assert.deepEqual(log, [], "importing a bundle must not call the server");
    assert.equal(second.householdId, vault.householdId, "the imported vault keeps the same household identity");
    assert.equal(second.isRegistered, false, "an imported bundle grants no server-side effect by itself");
    assert.equal(second.state.children.length, 2);
    assert.equal((await secondStore.read()).householdId, vault.householdId);
  });

  it("refuses to save an unregistered vault, and registration stays a separate action", async () => {
    const { vault } = await createVault(instance.baseUrl);
    const { bundle, recoveryCode } = await exportRecoveryBundle({ vault });
    const preview = await previewRecoveryBundle(bundle, recoveryCode);
    const secondApi = apiFor(instance.baseUrl);
    const second = await NasFamilyVault.importRecoveryBundle(
      { preview, passphrase: SECOND_PASSPHRASE },
      { api: secondApi, store: createMemoryStore() }
    );

    await assert.rejects(() => second.save(), /还没有登记在这台服务器上/, VaultError);

    const handle = await second.registerOnServer();
    assert.match(handle, /^[A-Za-z0-9_-]{24}$/u);
    assert.notEqual(handle, vault.serverHandle, "registration on this NAS allocates its own handle");
    assert.equal(second.isRegistered, true);

    second.addChild({ displayName: "三宝", birthDate: "2019-01-31", authorId: second.state.members[0].id });
    await second.save();
    assert.equal(second.state.children.length, 2);
    assert.equal(second.stateVersion, 2, "registration uploads once and the save uploads again");
    assert.equal((await storedObjects(secondApi)).length, 2);
  });

  it("reopens an imported vault after locking, even with no reachable server", async () => {
    const { vault } = await createVault(instance.baseUrl);
    const { bundle, recoveryCode } = await exportRecoveryBundle({ vault });
    const preview = await previewRecoveryBundle(bundle, recoveryCode);
    const store = createMemoryStore();
    const second = await NasFamilyVault.importRecoveryBundle(
      { preview, passphrase: SECOND_PASSPHRASE },
      { api: apiFor(instance.baseUrl), store }
    );
    await second.lock();

    const reopened = await NasFamilyVault.unlock(
      { passphrase: SECOND_PASSPHRASE },
      { api: apiFor(instance.baseUrl), store }
    );
    assert.equal(reopened.state.household.name, "隅之家");
    assert.equal(reopened.isRegistered, false);
    await assert.rejects(
      () => NasFamilyVault.unlock({ passphrase: PASSPHRASE }, { api: apiFor(instance.baseUrl), store }),
      /口令不正确，或密文已被篡改/
    );
  });

  it("erasing an imported vault removes the local record", async () => {
    const { vault } = await createVault(instance.baseUrl);
    const { bundle, recoveryCode } = await exportRecoveryBundle({ vault });
    const preview = await previewRecoveryBundle(bundle, recoveryCode);
    const store = createMemoryStore();
    const second = await NasFamilyVault.importRecoveryBundle(
      { preview, passphrase: SECOND_PASSPHRASE },
      { api: apiFor(instance.baseUrl), store }
    );
    assert.ok(await store.read());
    await second.eraseEverywhere();
    assert.equal(await store.read(), null);
    assert.equal(second.isUnlocked, false);
  });

  it("keeps the household identity inside the ciphertext, not the server handle", async () => {
    const { vault } = await createVault(instance.baseUrl);
    assert.notEqual(vault.householdId, vault.serverHandle);
    assert.match(vault.serverHandle, /^[A-Za-z0-9_-]{24}$/u);
    assert.equal(vault.state.household.id, vault.householdId);

    const { bundle } = await exportRecoveryBundle({ vault });
    assert.equal(serializeRecoveryBundle(bundle).includes(vault.serverHandle), false);
  });

  it("lets a bundle replace a local vault, never merge with it", async () => {
    const { vault } = await createVault(instance.baseUrl);
    vault.addChild({ displayName: "二宝", birthDate: "2024-05-01", authorId: vault.state.members[0].id });
    await vault.save();
    const { bundle, recoveryCode } = await exportRecoveryBundle({ vault });

    const preview = await previewRecoveryBundle(bundle, recoveryCode);
    const store = createMemoryStore();
    const local = await NasFamilyVault.create(
      { passphrase: SECOND_PASSPHRASE, familyName: "另一个家", caregiverName: "爸爸", childName: "阿另一个", birthDate: "2020-02-29" },
      { api: apiFor(instance.baseUrl), store }
    );
    assert.equal(local.state.children.length, 1);

    const replaced = await NasFamilyVault.importRecoveryBundle(
      { preview, passphrase: SECOND_PASSPHRASE },
      { api: apiFor(instance.baseUrl), store }
    );
    assert.equal(replaced.state.household.name, "隅之家", "the bundle replaces the local household outright");
    assert.equal(replaced.state.children.length, 2);
    assert.equal(replaced.state.children.some((child) => child.displayName === "阿另一个"), false);
  });
});

function minimalBundle() {
  return {
    format: RECOVERY_BUNDLE_FORMAT,
    householdId: "1c1c1c1c-1c1c-1c1c-1c1c-1c1c1c1c1c1c",
    kdf: "PBKDF2-SHA-256+HKDF-SHA-256",
    iterations: 210_000,
    salt: "c2FsdA==",
    recoveryCheck: "ab".repeat(32),
    stateVersion: 1,
    nonce: "bm9uY2U=",
    ciphertext: "Y2lwaGVy"
  };
}
