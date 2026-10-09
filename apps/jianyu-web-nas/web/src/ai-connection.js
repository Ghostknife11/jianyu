// The family's own AI connection (BYOK).
//
// The endpoint and the key are sealed with the household vault key and kept in
// a browser-local store of their own. They are deliberately not part of the
// family state: that would put them in merges, in recovery bundles, and in the
// ciphertext object the NAS holds. The key crosses the server only inside one
// transient proxy request, and only after the family confirms that call.

import { assertProxyTarget } from "../../shared/proxy-target.mjs";

export const AI_CONNECTION_FORMAT = "org.jianyu.web-ai-connection/v1";

export const AI_ROUTES = {
  // The key travels through the NAS for the length of one request. This is the
  // default because most providers block browser-origin requests with CORS.
  proxy: "proxy",
  // The browser calls the provider itself. The only route that works with a
  // model server on the family's own network.
  direct: "direct"
};

export const AI_CONNECTION_TEMPLATES = Object.freeze([
  Object.freeze({
    id: "openai",
    label: "OpenAI",
    endpoint: "https://api.openai.com/v1/chat/completions",
    model: "gpt-4o-mini"
  }),
  Object.freeze({
    id: "deepseek",
    label: "DeepSeek",
    endpoint: "https://api.deepseek.com/v1/chat/completions",
    model: "deepseek-chat"
  }),
  Object.freeze({
    id: "custom",
    label: "自定义 OpenAI 兼容服务",
    endpoint: "",
    model: ""
  })
]);

export class AiConnectionError extends Error {
  constructor(message) {
    super(message);
    this.name = "AiConnectionError";
  }
}

function assertText(value, { label, minimum, maximum }) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new AiConnectionError(`请填写${label}`);
  }
  const trimmed = value.trim();
  if (trimmed.length < minimum || trimmed.length > maximum) {
    throw new AiConnectionError(`${label}长度需要在 ${minimum} 到 ${maximum} 个字符之间`);
  }
  return trimmed;
}

/**
 * Checks a connection before it is stored. A proxied call must target a public
 * HTTPS endpoint, because the server refuses anything else; a direct call may
 * target a private address so a family can run their own model server.
 */
export function assertAiConnection(settings) {
  if (!settings || typeof settings !== "object" || Array.isArray(settings)) {
    throw new AiConnectionError("AI 连接设置不完整");
  }
  const endpoint = assertText(settings.endpoint, { label: "AI 服务地址", minimum: 8, maximum: 2048 });
  const apiKey = assertText(settings.apiKey, { label: "API 密钥", minimum: 8, maximum: 512 });
  const model = assertText(settings.model, { label: "模型名称", minimum: 1, maximum: 80 });
  const route = settings.route === AI_ROUTES.direct ? AI_ROUTES.direct : AI_ROUTES.proxy;
  try {
    assertProxyTarget(endpoint, { label: "AI 服务地址", allowPrivate: route === AI_ROUTES.direct });
  } catch (error) {
    throw new AiConnectionError(error instanceof TypeError ? error.message : "AI 服务地址不可用");
  }
  return Object.freeze({ endpoint, apiKey, model, route });
}

/** Plain-language description of where the key goes, for the settings screen. */
export function describeAiRoute(settings) {
  if (settings?.route === AI_ROUTES.direct) {
    return "浏览器直连：密钥从这台浏览器直接发给 AI 服务，不经过 NAS。";
  }
  return "经 NAS 瞬时代理：密钥只在这一次请求的内存里经过 NAS，不落盘、不记日志。";
}

export function createMemoryAiConnectionStore(initial = new Map()) {
  const records = initial;
  return {
    async read(householdId) { return records.get(householdId) ?? null; },
    async write(record) { records.set(record.householdId, structuredClone(record)); },
    async clear(householdId) { records.delete(householdId); }
  };
}

export function createIndexedDbAiConnectionStore({
  databaseName = "jianyu-nas-connections",
  storeName = "connections"
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

/**
 * Stores a connection. The sealed record names the household it belongs to, so
 * a record copied to another browser cannot be opened by a different family.
 */
export async function saveAiConnection({ vault, store, settings }) {
  if (!vault?.isUnlocked) throw new AiConnectionError("请先解锁家庭保险箱");
  const checked = assertAiConnection(settings);
  const sealed = await vault.sealLocalRecord(checked, AI_CONNECTION_FORMAT);
  const record = {
    householdId: vault.householdId,
    format: AI_CONNECTION_FORMAT,
    cipher: sealed.cipher,
    nonce: sealed.nonce,
    ciphertext: sealed.ciphertext,
    updatedAt: new Date().toISOString()
  };
  await store.write(record);
  return checked;
}

/** Returns the stored connection, or null when this browser has none. */
export async function readAiConnection({ vault, store }) {
  if (!vault?.isUnlocked) return null;
  const record = await store.read(vault.householdId);
  if (!record) return null;
  const opened = await vault.openLocalRecord(record, AI_CONNECTION_FORMAT);
  return assertAiConnection(opened);
}

export async function clearAiConnection({ store, householdId }) {
  if (typeof householdId !== "string" || householdId === "") return;
  await store.clear(householdId);
}
