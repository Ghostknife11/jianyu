// Tests for the family's own World-information feed address. The properties that
// matter are the same ones the AI connection has: the address is service
// configuration, not family history, so it is sealed with the vault key in a
// browser-local record of its own and never reaches the family state, a merge, a
// recovery bundle, or the NAS — and an address routed through the NAS proxy has
// to be one the proxy can actually reach.

import assert from "node:assert/strict";
import { describe, it, afterEach, beforeEach } from "node:test";
import { NasApi } from "../../apps/jianyu-web-nas/web/src/session.js";
import { NasFamilyVault } from "../../apps/jianyu-web-nas/web/src/nas-vault.js";
import {
  WORLD_CONNECTION_FORMAT,
  WorldConnectionError,
  assertWorldConnection,
  clearWorldConnection,
  createMemoryWorldConnectionStore,
  readWorldConnection,
  saveWorldConnection
} from "../../apps/jianyu-web-nas/web/src/world-connection.js";
import { startServer, stopServer, createCookieFetch } from "./helpers.mjs";

const PASSPHRASE = "family-passphrase-2026";
const FAMILY = {
  familyName: "隅之家",
  caregiverName: "妈妈",
  childName: "小隅",
  birthDate: "2013-09-15"
};

const SETTINGS = Object.freeze({
  feedUrl: "https://feeds.example.invalid/weekend.json",
  region: "华东",
  route: "proxy"
});

function apiFor(baseUrl) {
  return new NasApi({ baseUrl, fetchImpl: createCookieFetch() });
}

async function vaultOn(instance, overrides = {}) {
  const store = new Map();
  const api = apiFor(instance.baseUrl);
  const vault = await NasFamilyVault.create({ passphrase: PASSPHRASE, ...FAMILY, ...overrides }, {
    api,
    store: {
      async read() { return store.get("primary") ?? null; },
      async write(record) { store.set("primary", structuredClone(record)); },
      async clear() { store.clear(); }
    }
  });
  return { vault, api, vaultStore: store };
}

describe("World connection", () => {
  let instance;

  beforeEach(async () => {
    instance = await startServer();
  });

  afterEach(async () => {
    await stopServer(instance);
  });

  it("round-trips a feed address through a sealed browser-local record", async () => {
    const { vault } = await vaultOn(instance);
    const feeds = createMemoryWorldConnectionStore();
    const saved = await saveWorldConnection({ vault, store: feeds, settings: SETTINGS });

    assert.equal(saved.feedUrl, SETTINGS.feedUrl);
    assert.equal(saved.region, SETTINGS.region);
    assert.equal(saved.route, "proxy");

    const record = await feeds.read(vault.householdId);
    assert.equal(record.format, WORLD_CONNECTION_FORMAT);
    assert.equal(record.cipher, "AES-256-GCM");
    const serialized = JSON.stringify(record);
    assert.equal(serialized.includes(SETTINGS.feedUrl), false, "the stored record holds ciphertext only");

    assert.deepEqual(await readWorldConnection({ vault, store: feeds }), saved);
  });

  it("keeps the feed address out of the family state and off the server", async () => {
    const { vault, api } = await vaultOn(instance);
    const feeds = createMemoryWorldConnectionStore();
    await saveWorldConnection({ vault, store: feeds, settings: SETTINGS });

    const stateText = JSON.stringify(vault.state);
    assert.equal(stateText.includes(SETTINGS.feedUrl), false, "the address is not part of the family state");

    const objects = [];
    let cursor = "";
    for (let guard = 0; guard < 8; guard += 1) {
      const page = await api.listObjects({ cursor, limit: 64 });
      objects.push(...page.objects);
      if (!page.nextCursor) break;
      cursor = page.nextCursor;
    }
    assert.ok(objects.length > 0, "the household has stored state");
    assert.equal(JSON.stringify(objects).includes(SETTINGS.feedUrl), false,
      "no stored object contains the feed address");
  });

  it("refuses a record that belongs to another household", async () => {
    const first = await vaultOn(instance);
    const feeds = createMemoryWorldConnectionStore();
    await saveWorldConnection({ vault: first.vault, store: feeds, settings: SETTINGS });

    const second = await vaultOn(instance, { passphrase: "another-passphrase-2026" });
    assert.notEqual(second.vault.householdId, first.vault.householdId);
    assert.equal(await readWorldConnection({ vault: second.vault, store: feeds }), null,
      "the store is keyed by household, so another family finds nothing");

    const copied = await feeds.read(first.vault.householdId);
    await feeds.write({ ...copied, householdId: second.vault.householdId });
    await assert.rejects(
      () => readWorldConnection({ vault: second.vault, store: feeds }),
      /无法打开/u,
      "a copied record must not open under another household's key"
    );
  });

  it("erases the address from this browser and leaves the family state alone", async () => {
    const { vault } = await vaultOn(instance);
    const feeds = createMemoryWorldConnectionStore();
    await saveWorldConnection({ vault, store: feeds, settings: SETTINGS });
    assert.ok(await readWorldConnection({ vault, store: feeds }));

    await clearWorldConnection({ store: feeds, householdId: vault.householdId });
    assert.equal(await readWorldConnection({ vault, store: feeds }), null);
    assert.equal(vault.state.household.name, "隅之家");
    assert.equal(vault.isUnlocked, true);
  });

  it("refuses to store or read while the vault is locked", async () => {
    const { vault } = await vaultOn(instance);
    const feeds = createMemoryWorldConnectionStore();
    await saveWorldConnection({ vault, store: feeds, settings: SETTINGS });
    await vault.lock();

    await assert.rejects(
      () => saveWorldConnection({ vault, store: feeds, settings: SETTINGS }),
      /请先解锁/u
    );
    assert.equal(await readWorldConnection({ vault, store: feeds }), null);
  });

  it("accepts only an address the chosen route can reach", () => {
    assert.throws(() => assertWorldConnection({ ...SETTINGS, feedUrl: "http://feeds.example.invalid/f.json" }), WorldConnectionError);
    assert.throws(() => assertWorldConnection({ ...SETTINGS, feedUrl: "http://127.0.0.1:8080/f.json" }), WorldConnectionError);
    assert.throws(() => assertWorldConnection({ ...SETTINGS, feedUrl: "https://user:secret@feeds.example.invalid/f.json" }), WorldConnectionError);
    assert.throws(() => assertWorldConnection({ ...SETTINGS, feedUrl: "   " }), WorldConnectionError);
    assert.throws(() => assertWorldConnection({ ...SETTINGS, feedUrl: "https://feeds.example.invalid/" + "a".repeat(2100) }), WorldConnectionError);

    // A feed the browser fetches itself may live on the family's own network.
    const direct = assertWorldConnection({ ...SETTINGS, feedUrl: "http://127.0.0.1:8080/f.json", route: "direct" });
    assert.equal(direct.route, "direct");
    assert.equal(assertWorldConnection({ ...SETTINGS, route: "anything-else" }).route, "proxy");
  });

  it("trims and freezes what it stores", async () => {
    const { vault } = await vaultOn(instance);
    const feeds = createMemoryWorldConnectionStore();
    const saved = await saveWorldConnection({
      vault,
      store: feeds,
      settings: { ...SETTINGS, region: "  华东  " }
    });
    assert.equal(saved.region, "华东");
    assert.equal(Object.isFrozen(saved), true);
  });
});
