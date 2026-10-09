// Shared helpers for the NAS web tests: a real server on a loopback port and a
// cookie-aware fetch, because Node's fetch does not keep a cookie jar the way
// a browser does.

import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { loadConfig } from "../../apps/jianyu-web-nas/server/config.mjs";
import { createJianyuNasServer } from "../../apps/jianyu-web-nas/server/server.mjs";

export function baseConfig(overrides = {}) {
  return {
    ...loadConfig({}),
    host: "127.0.0.1",
    port: 0,
    insecureHttp: true,
    ...overrides
  };
}

export async function startServer(overrides) {
  const dataDir = await mkdtemp(join(tmpdir(), "jianyu-nas-test-"));
  const app = createJianyuNasServer(baseConfig({ dataDir, ...overrides }));
  const address = await app.listen();
  return { app, baseUrl: `http://127.0.0.1:${address.port}`, dataDir };
}

export async function stopServer(instance) {
  await instance.app.close();
  await rm(instance.dataDir, { recursive: true, force: true });
}

/** A fetch that stores and replays cookies, standing in for the browser. */
export function createCookieFetch() {
  let cookie = null;
  return async (url, options = {}) => {
    const headers = { ...options.headers };
    if (cookie) headers.cookie = cookie;
    const response = await fetch(url, { ...options, headers, redirect: "error" });
    for (const value of response.headers.getSetCookie?.() ?? []) {
      // The server clears the session cookie with Max-Age=0, as a browser would.
      cookie = /Max-Age=0/i.test(value) ? null : value.split(";")[0];
    }
    return response;
  };
}

export class Session {
  constructor(baseUrl, fetchImpl = globalThis.fetch) {
    this.baseUrl = baseUrl;
    this.cookie = null;
    this.fetchImpl = fetchImpl;
  }

  async request(path, options = {}) {
    const headers = { ...options.headers };
    if (this.cookie) headers.cookie = this.cookie;
    const response = await this.fetchImpl(`${this.baseUrl}${path}`, { ...options, headers });
    for (const value of response.headers.getSetCookie?.() ?? []) {
      this.cookie = value.split(";")[0];
    }
    return response;
  }

  async json(path, options = {}) {
    const response = await this.request(path, options);
    const body = await response.json().catch(() => null);
    return { status: response.status, body };
  }
}

export function deterministicHex(byteLength) {
  const bytes = new Uint8Array(byteLength);
  for (let index = 0; index < byteLength; index += 1) bytes[index] = (index * 7 + 11) % 256;
  return Buffer.from(bytes).toString("hex");
}

export function registrationBody() {
  return {
    householdId: "AaBbCcDdEeFfGgHh12345678",
    verifier: deterministicHex(32),
    salt: Buffer.from("jianyu-test-salt").toString("base64"),
    iterations: 210_000
  };
}

export function jsonPost(body) {
  return {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body)
  };
}

export function statePut(objectId, baseVersion) {
  return {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ objectId, baseVersion })
  };
}

export function bytesBody(bytes) {
  return {
    method: "PUT",
    headers: { "content-type": "application/octet-stream" },
    body: Buffer.from(bytes)
  };
}

export function objectId(filler) {
  return filler.repeat(24);
}
