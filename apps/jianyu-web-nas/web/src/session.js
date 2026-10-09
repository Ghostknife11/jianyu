// HTTP client for the NAS runtime. Every call is same-origin and cookie-based;
// the server holds no family plaintext, so nothing here needs a token store.

export class ApiRequestError extends Error {
  constructor(status, code, message, details = {}) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
    this.code = code;
    this.details = details;
  }

  get isStateConflict() {
    return this.code === "state-conflict";
  }

  get isObjectConflict() {
    return this.code === "object-conflict";
  }
}

export class NasApi {
  #baseUrl;
  #fetchImpl;

  constructor({ baseUrl = "", fetchImpl } = {}) {
    this.#baseUrl = String(baseUrl ?? "").replace(/\/+$/u, "");
    this.#fetchImpl = fetchImpl ?? globalThis.fetch.bind(globalThis);
  }

  async #request(path, { method = "GET", body, contentType, accept = "application/json" } = {}) {
    const headers = { accept };
    if (body !== undefined) headers["content-type"] = contentType ?? "application/json";
    let response;
    try {
      response = await this.#fetchImpl(`${this.#baseUrl}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : (contentType ?? "application/json") === "application/json" ? JSON.stringify(body) : body,
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error"
      });
    } catch {
      throw new ApiRequestError(0, "network-error", "无法连接到这台 NAS，请检查地址与网络");
    }
    if (response.status === 204) return null;
    if (accept === "application/json") {
      const payload = await response.json().catch(() => null);
      if (!response.ok) {
        const error = payload?.error ?? {};
        throw new ApiRequestError(response.status, error.code ?? "request-failed", error.message ?? "请求失败", {
          currentVersion: error.currentVersion,
          currentObjectId: error.currentObjectId
        });
      }
      return payload;
    }
    if (!response.ok) throw new ApiRequestError(response.status, "request-failed", "读取密文对象失败");
    return new Uint8Array(await response.arrayBuffer());
  }

  capabilities() {
    return this.#request("/api/capabilities");
  }

  registerHousehold({ householdId, verifier, salt, iterations }) {
    return this.#request("/api/households", { method: "POST", body: { householdId, verifier, salt, iterations } });
  }

  unlock({ householdId, verifier }) {
    return this.#request("/api/sessions", { method: "POST", body: { householdId, verifier } });
  }

  lock() {
    return this.#request("/api/sessions", { method: "DELETE", accept: "*/*" });
  }

  household() {
    return this.#request("/api/households/me");
  }

  eraseHousehold() {
    return this.#request("/api/households/me", { method: "DELETE" });
  }

  readStatePointer() {
    return this.#request("/api/state");
  }

  commitState({ objectId, baseVersion }) {
    return this.#request("/api/state", { method: "PUT", body: { objectId, baseVersion } });
  }

  listObjects({ cursor = "", limit } = {}) {
    const query = new URLSearchParams();
    if (cursor) query.set("cursor", cursor);
    if (Number.isInteger(limit)) query.set("limit", String(limit));
    const suffix = query.toString() === "" ? "" : `?${query}`;
    return this.#request(`/api/objects${suffix}`);
  }

  putObject(objectId, bytes) {
    return this.#request(`/api/objects/${encodeURIComponent(objectId)}`, {
      method: "PUT",
      body: bytes,
      contentType: "application/octet-stream"
    });
  }

  getObject(objectId) {
    return this.#request(`/api/objects/${encodeURIComponent(objectId)}`, { accept: "application/octet-stream" });
  }

  deleteObject(objectId) {
    return this.#request(`/api/objects/${encodeURIComponent(objectId)}`, { method: "DELETE", accept: "*/*" });
  }

  /**
   * Transient AI proxy. The provider key crosses this boundary for the length
   * of one request and is never stored by the server; the browser must label
   * that boundary in the UI (FORKING.md §7).
   */
  proxyAiCompletion({ endpoint, apiKey, payload }) {
    return this.#request("/api/proxy/ai-completions", { method: "POST", body: { endpoint, apiKey, payload } });
  }

  proxyFeed({ url }) {
    return this.#request("/api/proxy/feed", { method: "POST", body: { url } });
  }
}
