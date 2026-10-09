// The family's own World-information feed address.
//
// Like the AI connection, this is service configuration rather than family
// history: it is sealed with the household vault key and kept in a
// browser-local record of its own, so it never joins the family state and never
// reaches a merge, a recovery bundle, or the NAS. The feed itself carries no
// family data at all — see `world-brief-client.js`.

import { assertProxyTarget } from "../../shared/proxy-target.mjs";

export const WORLD_CONNECTION_FORMAT = "org.jianyu.web-world-connection/v1";

export class WorldConnectionError extends Error {
  constructor(message) {
    super(message);
    this.name = "WorldConnectionError";
  }
}

/**
 * Checks a feed address before it is stored. A feed reached through the NAS
 * proxy must be public HTTPS, because the server refuses anything else; a feed
 * the browser fetches itself may be on the family's own network.
 */
export function assertWorldConnection(settings) {
  if (!settings || typeof settings !== "object" || Array.isArray(settings)) {
    throw new WorldConnectionError("世界信息设置不完整");
  }
  const feedUrl = typeof settings.feedUrl === "string" ? settings.feedUrl.trim() : "";
  if (feedUrl === "" || feedUrl.length > 2048) throw new WorldConnectionError("请填写世界信息 feed 地址");
  const route = settings.route === "direct" ? "direct" : "proxy";
  try {
    assertProxyTarget(feedUrl, { label: "世界信息 feed 地址", allowPrivate: route === "direct" });
  } catch (error) {
    throw new WorldConnectionError(error instanceof TypeError ? error.message : "世界信息 feed 地址不可用");
  }
  const region = typeof settings.region === "string" ? settings.region.trim().slice(0, 120) : "";
  return Object.freeze({ feedUrl, region, route });
}

export function createMemoryWorldConnectionStore(initial = new Map()) {
  const records = initial;
  return {
    async read(householdId) { return records.get(householdId) ?? null; },
    async write(record) { records.set(record.householdId, structuredClone(record)); },
    async clear(householdId) { records.delete(householdId); }
  };
}

export function createIndexedDbWorldConnectionStore({
  // Its own database rather than a second store in the AI one: the two are
  // independent service settings, and a separate database keeps either one
  // readable when the other is being written.
  databaseName = "jianyu-nas-world-connections",
  storeName = "world-connections"
} = {}) {
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
        request.result.createObjectStore(storeName, { keyPath: "householdId" });
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
    read: (householdId) => withStore("readonly", (store) => requestOutcome(store.get(householdId))),
    write: (record) => withStore("readwrite", (store) => requestOutcome(store.put(record))),
    clear: (householdId) => withStore("readwrite", (store) => requestOutcome(store.delete(householdId)))
  };
}

/** Stores a feed address, sealed with the vault key under this household. */
export async function saveWorldConnection({ vault, store, settings }) {
  if (!vault?.isUnlocked) throw new WorldConnectionError("请先解锁家庭保险箱");
  const checked = assertWorldConnection(settings);
  const sealed = await vault.sealLocalRecord(checked, WORLD_CONNECTION_FORMAT);
  await store.write({
    householdId: vault.householdId,
    format: WORLD_CONNECTION_FORMAT,
    cipher: sealed.cipher,
    nonce: sealed.nonce,
    ciphertext: sealed.ciphertext,
    updatedAt: new Date().toISOString()
  });
  return checked;
}

/** The stored feed address, or null when this browser has none. */
export async function readWorldConnection({ vault, store }) {
  if (!vault?.isUnlocked) return null;
  const record = await store.read(vault.householdId);
  if (!record) return null;
  return assertWorldConnection(await vault.openLocalRecord(record, WORLD_CONNECTION_FORMAT));
}

export async function clearWorldConnection({ store, householdId }) {
  if (typeof householdId !== "string" || householdId === "") return;
  await store.clear(householdId);
}
