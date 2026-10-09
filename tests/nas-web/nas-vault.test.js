import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { afterEach, beforeEach, describe, it } from "node:test";
import { NasApi } from "../../apps/jianyu-web-nas/web/src/session.js";
import {
  NasFamilyVault,
  StateConflictError,
  VaultError,
  createMemoryStore
} from "../../apps/jianyu-web-nas/web/src/nas-vault.js";
import { childLifecycle } from "../../apps/jianyu-web-nas/web/src/family-state.js";
import { createCookieFetch, startServer, stopServer } from "./helpers.mjs";

const PASSPHRASE = "family-passphrase-2026";
const FAMILY = {
  familyName: "隅之家",
  caregiverName: "妈妈",
  childName: "小隅",
  birthDate: "2013-09-15"
};

function apiFor(baseUrl) {
  return new NasApi({ baseUrl, fetchImpl: createCookieFetch() });
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

describe("nas family vault", () => {
  let instance;

  beforeEach(async () => {
    instance = await startServer();
  });

  afterEach(async () => {
    await stopServer(instance);
  });

  it("creates a household whose server-side copy is ciphertext only", async () => {
    const store = createMemoryStore();
    const api = apiFor(instance.baseUrl);
    const vault = await NasFamilyVault.create({ passphrase: PASSPHRASE, ...FAMILY }, { api, store });

    assert.equal(vault.isUnlocked, true);
    assert.equal(vault.state.household.name, "隅之家");
    assert.equal(vault.stateVersion, 1);
    assert.match(vault.serverHandle, /^[A-Za-z0-9_-]{24}$/u, "the server handle is opaque and 24 characters");
    assert.notEqual(vault.householdId, vault.serverHandle, "identity and routing handle stay separate");

    assert.equal((await storedObjects(api)).length, 1, "one sealed state object exists");

    const record = await store.read();
    assert.equal(record.householdId, vault.householdId);
    assert.equal(record.serverHandle, vault.serverHandle);
    assert.equal(record.stateVersion, 1);
    const serialized = JSON.stringify(record);
    for (const secret of [PASSPHRASE, "隅之家", "小隅", "妈妈", "nas-vault-v1"]) {
      assert.equal(serialized.includes(secret), false, `the local record must not contain ${secret}`);
    }
  });

  it("never sends the passphrase or family plaintext to the server", async () => {
    const sent = [];
    const cookieFetch = createCookieFetch();
    const recordingFetch = async (url, options = {}) => {
      const body = options.body;
      sent.push(typeof body === "string" ? body : new TextDecoder().decode(body ?? new Uint8Array()));
      return cookieFetch(url, options);
    };
    const store = createMemoryStore();
    const api = new NasApi({ baseUrl: instance.baseUrl, fetchImpl: recordingFetch });
    const vault = await NasFamilyVault.create({ passphrase: PASSPHRASE, ...FAMILY }, { api, store });
    vault.addChild({ displayName: "二宝", birthDate: "2019-05-02", authorId: vault.state.members[0].id });
    await vault.save();

    const everything = sent.join("\n");
    for (const secret of [PASSPHRASE, "隅之家", "小隅", "二宝", "2013-09-15", "nas-vault-v1", "nas-auth-v1"]) {
      assert.equal(everything.includes(secret), false, `the server must never receive ${secret}`);
    }
    assert.equal(everything.includes(vault.serverHandle), true, "the opaque handle is the only household identifier sent");
    assert.equal(everything.includes(vault.householdId), false, "the household identity never reaches the server");
  });

  it("unlocks again on the same browser and round-trips the state", async () => {
    const store = createMemoryStore();
    const api = apiFor(instance.baseUrl);
    await NasFamilyVault.create({ passphrase: PASSPHRASE, ...FAMILY }, { api, store });
    const before = structuredClone(await store.read());

    const reopened = await NasFamilyVault.unlock({ passphrase: PASSPHRASE }, { api: apiFor(instance.baseUrl), store });
    assert.equal(reopened.state.household.name, "隅之家");
    assert.equal(reopened.state.children[0].displayName, "小隅");
    assert.equal(reopened.stateVersion, 1);
    assert.deepEqual(await store.read(), before, "a read-only unlock must not rewrite the record");
  });

  it("refuses a wrong passphrase without opening a session", async () => {
    const store = createMemoryStore();
    await NasFamilyVault.create({ passphrase: PASSPHRASE, ...FAMILY }, { api: apiFor(instance.baseUrl), store });

    await assert.rejects(
      () => NasFamilyVault.unlock({ passphrase: "wrong-passphrase-entirely" }, { api: apiFor(instance.baseUrl), store }),
      (error) => error instanceof VaultError && /口令不正确|打不开|损坏/u.test(error.message)
    );

    const gated = apiFor(instance.baseUrl);
    await assert.rejects(() => gated.household(), (error) => error.status === 401);
  });

  it("refuses to unlock a browser that has no local record", async () => {
    await assert.rejects(
      () => NasFamilyVault.unlock({ passphrase: PASSPHRASE }, { api: apiFor(instance.baseUrl), store: createMemoryStore() }),
      (error) => error instanceof VaultError && error.message.includes("还没有家庭保险箱")
    );
  });

  it("advances the server version on every save", async () => {
    const store = createMemoryStore();
    const api = apiFor(instance.baseUrl);
    const vault = await NasFamilyVault.create({ passphrase: PASSPHRASE, ...FAMILY }, { api, store });
    vault.addChild({ displayName: "二宝", birthDate: "2019-05-02", authorId: vault.state.members[0].id });
    assert.equal(await vault.save(), 2);
    assert.equal((await storedObjects(api)).length, 2, "each save writes a new immutable object");

    const reopened = await NasFamilyVault.unlock({ passphrase: PASSPHRASE }, { api: apiFor(instance.baseUrl), store });
    assert.deepEqual(reopened.state.children.map((child) => child.displayName), ["小隅", "二宝"]);
    assert.equal(reopened.stateVersion, 2);
  });

  it("refuses a stale write and merges after a reload", async () => {
    const first = createMemoryStore();
    const api = apiFor(instance.baseUrl);
    const writer = await NasFamilyVault.create({ passphrase: PASSPHRASE, ...FAMILY }, { api, store: first });

    // A second browser holding a copy of the same record acts as a second writer.
    const second = createMemoryStore(new Map([["primary", await first.read()]]));
    const other = await NasFamilyVault.unlock({ passphrase: PASSPHRASE }, { api: apiFor(instance.baseUrl), store: second });

    writer.addChild({ displayName: "大宝", birthDate: "2016-03-04", authorId: writer.state.members[0].id });
    await writer.save();

    other.addCaregiver({ displayName: "爸爸", authorId: other.state.members[0].id });
    await assert.rejects(() => other.save(), (error) => error instanceof StateConflictError);

    await other.reloadFromServer();
    assert.deepEqual(other.state.children.map((child) => child.displayName), ["小隅", "大宝"]);
    assert.deepEqual(
      other.state.members.filter((member) => member.role === "caregiver").map((member) => member.displayName),
      ["妈妈", "爸爸"]
    );
    await other.save();
    assert.equal(other.stateVersion, 4);

    const reopened = await NasFamilyVault.unlock({ passphrase: PASSPHRASE }, { api: apiFor(instance.baseUrl), store: first });
    assert.deepEqual(reopened.state.children.map((child) => child.displayName), ["小隅", "大宝"]);
    assert.deepEqual(
      reopened.state.members.filter((member) => member.role === "caregiver").map((member) => member.displayName),
      ["妈妈", "爸爸"]
    );
  });

  it("keeps the newest feedback when a reload merges a duplicated choice", async () => {
    const store = createMemoryStore();
    const vault = await NasFamilyVault.create({ passphrase: PASSPHRASE, ...FAMILY }, { api: apiFor(instance.baseUrl), store });
    const second = createMemoryStore(new Map([["primary", await store.read()]]));
    const other = await NasFamilyVault.unlock({ passphrase: PASSPHRASE }, { api: apiFor(instance.baseUrl), store: second });

    const choice = {
      id: globalThis.crypto.randomUUID(),
      childId: vault.state.children[0].id,
      opportunity: { title: "用现有纸张试两种折法", ecosystem: "making" },
      sourceEventId: null,
      chosenAt: new Date().toISOString(),
      status: "chosen"
    };
    vault.state.choices.push(structuredClone(choice));
    await vault.save();

    await other.reloadFromServer();
    assert.equal(other.state.choices.length, 1, "the reloaded state carries the same choice");
    other.state.choices[0].feedback = { response: "孩子说还想再试" };
    await other.save();

    vault.addCaregiver({ displayName: "爸爸", authorId: vault.state.members[0].id });
    await assert.rejects(() => vault.save(), (error) => error instanceof StateConflictError);
    await vault.reloadFromServer();

    assert.equal(vault.state.choices.length, 1, "the same choice ID merges into one record");
    assert.deepEqual(vault.state.choices[0].feedback, { response: "孩子说还想再试" });
    assert.deepEqual(
      vault.state.members.filter((member) => member.role === "caregiver").map((member) => member.displayName),
      ["妈妈", "爸爸"]
    );
  });

  it("locks the session but keeps the local record for the next unlock", async () => {
    const store = createMemoryStore();
    const vault = await NasFamilyVault.create({ passphrase: PASSPHRASE, ...FAMILY }, { api: apiFor(instance.baseUrl), store });
    await vault.lock();
    assert.equal(vault.isUnlocked, false);
    assert.notEqual(await store.read(), null, "locking is not erasing");

    await assert.rejects(() => vault.save(), (error) => error instanceof VaultError);
    const reopened = await NasFamilyVault.unlock({ passphrase: PASSPHRASE }, { api: apiFor(instance.baseUrl), store });
    assert.equal(reopened.isUnlocked, true);
  });

  it("erases every server object and the local record", async () => {
    const store = createMemoryStore();
    const api = apiFor(instance.baseUrl);
    const vault = await NasFamilyVault.create({ passphrase: PASSPHRASE, ...FAMILY }, { api, store });
    const handle = vault.serverHandle;
    await vault.eraseEverywhere();

    assert.equal(vault.isUnlocked, false);
    assert.equal(await store.read(), null);
    assert.equal(
      existsSync(join(instance.dataDir, "objects", handle)),
      false,
      "the household's ciphertext objects must be removed"
    );
    assert.equal(
      existsSync(join(instance.dataDir, "households", `${handle}.json`)),
      false,
      "the household record must be removed"
    );

    await assert.rejects(() => api.household(), (error) => error.status === 401, "erased credentials no longer open a session");
    await assert.rejects(
      () => NasFamilyVault.unlock({ passphrase: PASSPHRASE }, { api: apiFor(instance.baseUrl), store }),
      (error) => error instanceof VaultError
    );
  });

  it("reports a lifecycle boundary for a stored child", async () => {
    const store = createMemoryStore();
    const vault = await NasFamilyVault.create({ passphrase: PASSPHRASE, ...FAMILY }, { api: apiFor(instance.baseUrl), store });
    const child = vault.state.children[0];
    assert.equal(childLifecycle(child, new Date("2026-09-15T12:00:00")).stage, "hand-over");
    assert.equal(childLifecycle(child, new Date("2026-09-14T12:00:00")).stage, "co-select");

    const toddler = vault.addChild({ displayName: "小宝", birthDate: "2024-06-01", authorId: vault.state.members[0].id });
    const lifecycle = childLifecycle(toddler, new Date("2026-10-10T12:00:00"));
    assert.equal(lifecycle.stage, null, "a child below 4 has no lifecycle stage");
    assert.equal(lifecycle.discoveryOffered, false);
  });
});
