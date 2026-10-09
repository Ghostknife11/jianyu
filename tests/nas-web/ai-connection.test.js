// Tests for the family's own AI connection. The property that matters is that
// the key lives in exactly one place: sealed with the household vault key in a
// browser-local record of its own. It never joins the family state, so it never
// reaches a merge, a recovery bundle, or the NAS — and a record copied to
// another household cannot be opened.

import assert from "node:assert/strict";
import { describe, it, afterEach, beforeEach } from "node:test";
import { NasApi } from "../../apps/jianyu-web-nas/web/src/session.js";
import { NasFamilyVault } from "../../apps/jianyu-web-nas/web/src/nas-vault.js";
import {
  AI_CONNECTION_FORMAT,
  AI_ROUTES,
  AiConnectionError,
  assertAiConnection,
  clearAiConnection,
  createMemoryAiConnectionStore,
  describeAiRoute,
  readAiConnection,
  saveAiConnection
} from "../../apps/jianyu-web-nas/web/src/ai-connection.js";
import { startServer, stopServer, createCookieFetch } from "./helpers.mjs";

// Assembled from parts so no credential-shaped literal lands in the repository.
const SYNTHETIC_KEY = ["synthetic", "not", "a", "real", "key"].join("-");

const PASSPHRASE = "family-passphrase-2026";
const FAMILY = {
  familyName: "隅之家",
  caregiverName: "妈妈",
  childName: "小隅",
  birthDate: "2013-09-15"
};

const SETTINGS = Object.freeze({
  endpoint: "https://api.example.invalid/v1/chat/completions",
  apiKey: SYNTHETIC_KEY,
  model: "synthetic-model",
  route: AI_ROUTES.proxy
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

describe("AI connection", () => {
  let instance;

  beforeEach(async () => {
    instance = await startServer();
  });

  afterEach(async () => {
    await stopServer(instance);
  });

  it("round-trips a connection through a sealed browser-local record", async () => {
    const { vault } = await vaultOn(instance);
    const connections = createMemoryAiConnectionStore();
    const saved = await saveAiConnection({ vault, store: connections, settings: SETTINGS });

    assert.equal(saved.endpoint, SETTINGS.endpoint);
    assert.equal(saved.apiKey, SETTINGS.apiKey);
    assert.equal(saved.route, AI_ROUTES.proxy);

    const record = await connections.read(vault.householdId);
    assert.equal(record.format, AI_CONNECTION_FORMAT);
    assert.equal(record.cipher, "AES-256-GCM");
    const serialized = JSON.stringify(record);
    for (const secret of [SETTINGS.apiKey, SETTINGS.endpoint, SETTINGS.model]) {
      assert.equal(serialized.includes(secret), false, "the stored record holds ciphertext only");
    }

    const read = await readAiConnection({ vault, store: connections });
    assert.deepEqual(read, saved);
  });

  it("keeps the key out of the family state and off the server", async () => {
    const { vault, api } = await vaultOn(instance);
    const connections = createMemoryAiConnectionStore();
    await saveAiConnection({ vault, store: connections, settings: SETTINGS });

    const stateText = JSON.stringify(vault.state);
    assert.equal(stateText.includes(SETTINGS.apiKey), false, "the key is not part of the family state");

    // The same session the vault holds, read back through the household's own
    // objects: every one of them must be opaque ciphertext.
    const objects = [];
    let cursor = "";
    for (let guard = 0; guard < 8; guard += 1) {
      const page = await api.listObjects({ cursor, limit: 64 });
      objects.push(...page.objects);
      if (!page.nextCursor) break;
      cursor = page.nextCursor;
    }
    assert.ok(objects.length > 0, "the household has stored state");
    const objectText = JSON.stringify(objects);
    for (const secret of [SETTINGS.apiKey, SETTINGS.endpoint, SETTINGS.model]) {
      assert.equal(objectText.includes(secret), false, "no stored object contains connection settings");
    }
  });

  it("refuses a record that belongs to another household", async () => {
    const first = await vaultOn(instance);
    const connections = createMemoryAiConnectionStore();
    await saveAiConnection({ vault: first.vault, store: connections, settings: SETTINGS });

    const second = await vaultOn(instance, { passphrase: "another-passphrase-2026" });
    assert.notEqual(second.vault.householdId, first.vault.householdId);
    assert.equal(await readAiConnection({ vault: second.vault, store: connections }), null,
      "the store is keyed by household, so another family finds nothing");

    // The case that must actually fail: the record is copied under this
    // household's own key, and the ciphertext still will not open.
    const copied = await connections.read(first.vault.householdId);
    await connections.write({ ...copied, householdId: second.vault.householdId });
    await assert.rejects(
      () => readAiConnection({ vault: second.vault, store: connections }),
      /无法打开/u,
      "a copied record must not open under another household's key"
    );
  });

  it("erases the connection from this browser on request", async () => {
    const { vault } = await vaultOn(instance);
    const connections = createMemoryAiConnectionStore();
    await saveAiConnection({ vault, store: connections, settings: SETTINGS });
    assert.ok(await readAiConnection({ vault, store: connections }));

    await clearAiConnection({ store: connections, householdId: vault.householdId });
    assert.equal(await readAiConnection({ vault, store: connections }), null);

    // Erasing the connection leaves the family state untouched.
    assert.equal(vault.state.household.name, "隅之家");
    assert.equal(vault.isUnlocked, true);
  });

  it("refuses to store or read while the vault is locked", async () => {
    const { vault } = await vaultOn(instance);
    const connections = createMemoryAiConnectionStore();
    await saveAiConnection({ vault, store: connections, settings: SETTINGS });
    await vault.lock();

    await assert.rejects(
      () => saveAiConnection({ vault, store: connections, settings: SETTINGS }),
      /请先解锁/u
    );
    assert.equal(await readAiConnection({ vault, store: connections }), null);
  });

  it("accepts only what the proxy can actually reach", () => {
    assert.throws(() => assertAiConnection({ ...SETTINGS, endpoint: "http://api.example.invalid/v1" }), AiConnectionError);
    assert.throws(() => assertAiConnection({ ...SETTINGS, endpoint: "http://127.0.0.1:8080/v1" }), AiConnectionError);
    assert.throws(() => assertAiConnection({ ...SETTINGS, apiKey: "short" }), AiConnectionError);
    assert.throws(() => assertAiConnection({ ...SETTINGS, model: "" }), AiConnectionError);
    assert.throws(() => assertAiConnection({ ...SETTINGS, endpoint: "https://user:secret@api.example.invalid/v1" }), AiConnectionError);

    // A direct route may target the family's own network; the proxy route may not.
    const direct = assertAiConnection({ ...SETTINGS, endpoint: "http://127.0.0.1:8080/v1", route: AI_ROUTES.direct });
    assert.equal(direct.route, AI_ROUTES.direct);
    assert.equal(assertAiConnection({ ...SETTINGS, route: "anything-else" }).route, AI_ROUTES.proxy);
  });

  it("trims and freezes what it stores", async () => {
    const { vault } = await vaultOn(instance);
    const connections = createMemoryAiConnectionStore();
    const saved = await saveAiConnection({
      vault,
      store: connections,
      settings: { ...SETTINGS, model: "  synthetic-model  " }
    });
    assert.equal(saved.model, "synthetic-model");
    assert.equal(Object.isFrozen(saved), true);
  });

  it("describes each route in plain language", () => {
    assert.match(describeAiRoute({ route: AI_ROUTES.proxy }), /不落盘/u);
    assert.match(describeAiRoute({ route: AI_ROUTES.direct }), /不经过 NAS/u);
  });
});
