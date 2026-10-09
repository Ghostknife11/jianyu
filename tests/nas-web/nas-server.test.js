import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { createServer } from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, it } from "node:test";
import { loadConfig } from "../../apps/jianyu-web-nas/server/config.mjs";
import { createJianyuNasServer } from "../../apps/jianyu-web-nas/server/server.mjs";

function baseConfig(overrides = {}) {
  return {
    ...loadConfig({}),
    host: "127.0.0.1",
    port: 0,
    insecureHttp: true,
    ...overrides
  };
}

async function startServer(overrides) {
  const dataDir = await mkdtemp(join(tmpdir(), "jianyu-nas-test-"));
  const app = createJianyuNasServer(baseConfig({ dataDir, ...overrides }));
  const address = await app.listen();
  return { app, baseUrl: `http://127.0.0.1:${address.port}`, dataDir };
}

async function stopServer(instance) {
  await instance.app.close();
  await rm(instance.dataDir, { recursive: true, force: true });
}

class Session {
  constructor(baseUrl) {
    this.baseUrl = baseUrl;
    this.cookie = null;
  }

  async request(path, options = {}) {
    const headers = { ...options.headers };
    if (this.cookie) headers.cookie = this.cookie;
    const response = await fetch(`${this.baseUrl}${path}`, { ...options, headers });
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

function deterministicHex(byteLength) {
  const bytes = new Uint8Array(byteLength);
  for (let index = 0; index < byteLength; index += 1) bytes[index] = (index * 7 + 11) % 256;
  return Buffer.from(bytes).toString("hex");
}

function registrationBody() {
  return {
    householdId: "AaBbCcDdEeFfGgHh12345678",
    verifier: deterministicHex(32),
    salt: Buffer.from("jianyu-test-salt").toString("base64"),
    iterations: 210_000
  };
}

function jsonPost(body) {
  return {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body)
  };
}

function statePut(objectId, baseVersion) {
  return {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ objectId, baseVersion })
  };
}

function bytesBody(bytes) {
  return {
    method: "PUT",
    headers: { "content-type": "application/octet-stream" },
    body: Buffer.from(bytes)
  };
}

function objectId(filler) {
  return filler.repeat(24);
}

async function unlock(session) {
  const body = registrationBody();
  const registered = await session.json("/api/households", jsonPost(body));
  assert.equal(registered.status, 201, "a fresh server accepts the test household");
  const unlocked = await session.json("/api/sessions", jsonPost(body));
  assert.equal(unlocked.status, 200, "the test household must unlock");
  return unlocked;
}

describe("NAS server surface", () => {
  /** @type {{app: any, baseUrl: string, dataDir: string}} */
  let instance;

  beforeEach(async () => {
    instance = await startServer();
  });

  afterEach(async () => {
    await stopServer(instance);
  });

  it("answers the health check without a session", async () => {
    const session = new Session(instance.baseUrl);
    const { status, body } = await session.json("/healthz");
    assert.equal(status, 200);
    assert.equal(body.status, "ok");
    assert.equal(body.service, "jianyu-nas");
  });

  it("describes its capabilities without family data", async () => {
    const session = new Session(instance.baseUrl);
    const { status, body } = await session.json("/api/capabilities");
    assert.equal(status, 200);
    assert.equal(body.aiProxy, true);
    assert.equal(body.feedProxy, true);
    assert.equal(body.privateFeedAllowed, false);
    assert.equal(body.secureCookies, false);
    assert.equal(body.maxObjectBytes, 24 * 1024 * 1024);
    assert.equal(body.sessionTtlDays, 30);
  });

  it("hides unknown API routes behind the session gate", async () => {
    const anonymous = new Session(instance.baseUrl);
    assert.equal((await anonymous.json("/api/unknown")).status, 401);
    assert.equal((await anonymous.json("/api/households", { method: "GET" })).status, 401);

    const session = new Session(instance.baseUrl);
    await unlock(session);
    const unknown = await session.json("/api/unknown");
    assert.equal(unknown.status, 404);
    assert.equal(unknown.body.error.code, "not-found");
    assert.equal((await session.json("/api/households", { method: "GET" })).status, 404);
    assert.equal((await session.json("/api/state", { method: "POST" })).status, 405);
  });

  it("registers a household once and rejects a duplicate identifier", async () => {
    const session = new Session(instance.baseUrl);
    const body = registrationBody();
    const created = await session.json("/api/households", jsonPost(body));
    assert.equal(created.status, 201);
    assert.equal(created.body.householdId, body.householdId);

    const duplicate = await session.json("/api/households", jsonPost(body));
    assert.equal(duplicate.status, 409);
    assert.equal(duplicate.body.error.code, "household-exists");
  });

  it("rejects malformed registrations", async () => {
    const session = new Session(instance.baseUrl);
    const cases = [
      { ...registrationBody(), householdId: "not-opaque" },
      { ...registrationBody(), verifier: "abc" },
      { ...registrationBody(), salt: "!!!" },
      { ...registrationBody(), iterations: 5 }
    ];
    for (const body of cases) {
      const { status, body: payload } = await session.json("/api/households", jsonPost(body));
      assert.equal(status, 400, JSON.stringify(body));
      assert.equal(payload.error.code, "invalid-request");
    }
  });

  it("rejects a non-JSON body", async () => {
    const session = new Session(instance.baseUrl);
    const { status } = await session.json("/api/households", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "not json"
    });
    assert.equal(status, 400);
  });

  it("requires a session for household data", async () => {
    const session = new Session(instance.baseUrl);
    for (const path of ["/api/households/me", "/api/state", "/api/objects"]) {
      const { status, body } = await session.json(path);
      assert.equal(status, 401, path);
      assert.equal(body.error.code, "no-session");
    }
  });

  it("refuses an unlock with a wrong verifier", async () => {
    const session = new Session(instance.baseUrl);
    const body = registrationBody();
    await session.json("/api/households", jsonPost(body));
    const { status, body: payload } = await session.json("/api/sessions", jsonPost({
      householdId: body.householdId,
      verifier: deterministicHex(32).replace(/^../, "ff")
    }));
    assert.equal(status, 401);
    assert.equal(payload.error.code, "unlock-failed");
    assert.equal(session.cookie, null);
  });

  it("refuses an unlock for an unknown household", async () => {
    const session = new Session(instance.baseUrl);
    const { status } = await session.json("/api/sessions", jsonPost({
      householdId: "ZzYyXxWwVvUu1234567890",
      verifier: deterministicHex(32)
    }));
    assert.equal(status, 401);
  });

  it("issues a session cookie on a correct unlock and reuses it", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    assert.ok(session.cookie?.startsWith("jianyu_session="));

    const summary = await session.request("/api/households/me");
    assert.equal(summary.status, 200);
    assert.equal((summary.headers.getSetCookie?.() ?? []).length, 0, "an existing session must not be re-issued");
  });

  it("marks the session cookie HttpOnly, SameSite=Strict and Secure", async () => {
    const hardened = await startServer({ insecureHttp: false });
    try {
      const session = new Session(hardened.baseUrl);
      const body = registrationBody();
      await session.json("/api/households", jsonPost(body));
      const response = await session.request("/api/sessions", jsonPost(body));
      const cookie = (response.headers.getSetCookie?.() ?? []).join("; ");
      assert.match(cookie, /HttpOnly/);
      assert.match(cookie, /SameSite=Strict/);
      assert.match(cookie, /Secure/);
      assert.match(cookie, /Max-Age=2592000/);
    } finally {
      await stopServer(hardened);
    }
  });

  it("reads an empty state pointer before the first save", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    const { status, body } = await session.json("/api/state");
    assert.equal(status, 200);
    assert.equal(body.version, 0);
    assert.equal(body.objectId, null);
  });

  it("stores objects immutably", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    const id = objectId("0");
    const bytes = new Uint8Array([1, 2, 3, 4]);

    const created = await session.json(`/api/objects/${id}`, bytesBody(bytes));
    assert.equal(created.status, 201);
    assert.equal(created.body.created, true);
    assert.equal(created.body.sizeBytes, 4);

    const retried = await session.json(`/api/objects/${id}`, bytesBody(bytes));
    assert.equal(retried.status, 200);
    assert.equal(retried.body.created, false);

    const conflicting = await session.json(`/api/objects/${id}`, bytesBody(new Uint8Array([9, 9, 9, 9])));
    assert.equal(conflicting.status, 409);
    assert.equal(conflicting.body.error.code, "object-conflict");

    const fetched = await session.request(`/api/objects/${id}`);
    assert.equal(fetched.status, 200);
    assert.equal(fetched.headers.get("content-type"), "application/octet-stream");
    assert.deepEqual(new Uint8Array(await fetched.arrayBuffer()), bytes);

    const missing = await session.json(`/api/objects/${objectId("z")}`);
    assert.equal(missing.status, 404);
  });

  it("rejects non-opaque object identifiers and wrong content types", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    const badId = await session.json("/api/objects/not-opaque", bytesBody(new Uint8Array([1])));
    assert.equal(badId.status, 400);

    const wrongType = await session.json(`/api/objects/${objectId("1")}`, {
      method: "PUT",
      headers: { "content-type": "application/json" },
      body: "{}"
    });
    assert.equal(wrongType.status, 415);
  });

  it("paginates objects with an opaque cursor", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    const ids = [objectId("a"), objectId("b"), objectId("c")];
    for (const id of ids) {
      const response = await session.json(`/api/objects/${id}`, bytesBody(new Uint8Array([1])));
      assert.equal(response.status, 201, id);
    }

    const first = await session.json("/api/objects?limit=2");
    assert.equal(first.status, 200);
    assert.deepEqual(first.body.objects.map((entry) => entry.objectId), ids.slice(0, 2));
    assert.equal(first.body.nextCursor, ids[1]);

    const second = await session.json(`/api/objects?limit=2&cursor=${first.body.nextCursor}`);
    assert.deepEqual(second.body.objects.map((entry) => entry.objectId), [ids[2]]);
    assert.equal(second.body.nextCursor, null);

    assert.equal((await session.json("/api/objects?limit=0")).status, 400);
    assert.equal((await session.json("/api/objects?limit=999")).status, 400);
    assert.equal((await session.json("/api/objects?cursor=nope")).status, 400);
  });

  it("commits state only when the base version still matches", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    const id = objectId("d");
    assert.equal((await session.json(`/api/objects/${id}`, bytesBody(new Uint8Array([7])))).status, 201);

    const missingObject = await session.json("/api/state", statePut(objectId("e"), 0));
    assert.equal(missingObject.status, 400);
    assert.equal(missingObject.body.error.code, "object-missing");

    const invalidVersion = await session.json("/api/state", statePut(id, -1));
    assert.equal(invalidVersion.status, 400);

    const committed = await session.json("/api/state", statePut(id, 0));
    assert.equal(committed.status, 200);
    assert.equal(committed.body.version, 1);

    const stale = await session.json("/api/state", statePut(id, 0));
    assert.equal(stale.status, 409);
    assert.equal(stale.body.error.code, "state-conflict");
    assert.equal(stale.body.error.currentVersion, 1);
    assert.equal(stale.body.error.currentObjectId, id);

    const advanced = await session.json("/api/state", statePut(id, 1));
    assert.equal(advanced.status, 200);
    assert.equal(advanced.body.version, 2);

    const pointer = await session.json("/api/state");
    assert.equal(pointer.body.version, 2);
    assert.equal(pointer.body.objectId, id);
  });

  it("deletes objects and reports the household summary", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    const id = objectId("f");
    assert.equal((await session.json(`/api/objects/${id}`, bytesBody(new Uint8Array([5])))).status, 201);
    const removed = await session.request(`/api/objects/${id}`, { method: "DELETE" });
    assert.equal(removed.status, 204);
    const again = await session.request(`/api/objects/${id}`, { method: "DELETE" });
    assert.equal(again.status, 404);

    const summary = await session.json("/api/households/me");
    assert.equal(summary.status, 200);
    assert.equal(summary.body.householdId, registrationBody().householdId);
    assert.equal(summary.body.objectCount, 0);
    assert.equal(summary.body.objectLimit, 512);
    assert.equal(summary.body.state.version, 0);
  });

  it("revokes the session on lock", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    const locked = await session.request("/api/sessions", { method: "DELETE" });
    assert.equal(locked.status, 204);
    const after = await session.json("/api/households/me");
    assert.equal(after.status, 401);
  });

  it("erases the household and its objects", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    const id = objectId("g");
    await session.json(`/api/objects/${id}`, bytesBody(new Uint8Array([3])));
    await session.json("/api/state", statePut(id, 0));

    const erased = await session.request("/api/households/me", { method: "DELETE" });
    assert.equal(erased.status, 200);
    assert.equal((await erased.json()).erased, true);
    assert.equal((await session.json("/api/households/me")).status, 401);

    const relogin = new Session(instance.baseUrl);
    const unlockAgain = await relogin.json("/api/sessions", jsonPost(registrationBody()));
    assert.equal(unlockAgain.status, 401, "erased credentials must not unlock again");

    const householdDirectory = join(instance.dataDir, "objects", registrationBody().householdId);
    assert.equal(existsSync(householdDirectory), false, "the household's objects must be removed");
    assert.equal(existsSync(join(instance.dataDir, "households", `${registrationBody().householdId}.json`)), false);
  });

  it("keeps another household's data when one household is erased", async () => {
    const first = new Session(instance.baseUrl);
    await unlock(first);
    const keepId = objectId("h");
    await first.json(`/api/objects/${keepId}`, bytesBody(new Uint8Array([8])));
    await first.json("/api/state", statePut(keepId, 0));

    const second = new Session(instance.baseUrl);
    const other = { ...registrationBody(), householdId: objectId("Q") };
    assert.equal((await second.json("/api/households", jsonPost(other))).status, 201);
    assert.equal((await second.json("/api/sessions", jsonPost(other))).status, 200);
    const otherId = objectId("i");
    await second.json(`/api/objects/${otherId}`, bytesBody(new Uint8Array([9])));

    assert.equal((await first.request("/api/households/me", { method: "DELETE" })).status, 200);

    const surviving = await second.json("/api/state");
    assert.equal(surviving.body.version, 0);
    const kept = await second.json(`/api/objects/${otherId}`);
    assert.equal(kept.status, 200);
  });
});

describe("static file serving", () => {
  /** @type {{app: any, baseUrl: string, dataDir: string}} */
  let instance;

  beforeEach(async () => {
    instance = await startServer();
  });

  afterEach(async () => {
    await stopServer(instance);
  });

  it("serves shared engine packages and examples", async () => {
    const response = await fetch(`${instance.baseUrl}/packages/provider-sdk/src/index.js`);
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type") ?? "", /javascript/);
    const feed = await fetch(`${instance.baseUrl}/examples/world-brief/weekend.demo.js`);
    assert.equal(feed.status, 200);
  });

  it("applies the strict security header set", async () => {
    const response = await fetch(`${instance.baseUrl}/packages/provider-sdk/src/index.js`);
    assert.match(
      response.headers.get("content-security-policy") ?? "",
      /^default-src 'self'; connect-src 'self'; .*frame-ancestors 'none'; form-action 'self'$/
    );
    assert.equal(response.headers.get("referrer-policy"), "no-referrer");
    assert.equal(response.headers.get("x-content-type-options"), "nosniff");
    assert.equal(response.headers.get("cache-control"), "no-store");
  });

  it("refuses paths outside the allowlist", async () => {
    const cases = [
      "/../SECURITY.md",
      "/packages/../SECURITY.md",
      "/.git/config",
      "/apps/jianyu-android/build.gradle.kts",
      "/server/server.mjs",
      "/apps/jianyu-web-nas/server/server.mjs",
      "/apps/jianyu-web-nas/server/../server/server.mjs",
      "/packages/provider-sdk/src/../../../../SECURITY.md"
    ];
    for (const path of cases) {
      const response = await fetch(`${instance.baseUrl}${path}`);
      assert.notEqual(response.status, 200, path);
    }
  });
});

describe("transient proxy", () => {
  /** @type {{app: any, baseUrl: string, dataDir: string}} */
  let instance;
  let feedServer;
  let feedBaseUrl;

  beforeEach(async () => {
    instance = await startServer();
    feedServer = createServer((request, response) => {
      if (request.url === "/feed.json") {
        response.writeHead(200, { "content-type": "application/json" });
        response.end(JSON.stringify({ schema: "org.foe.world-brief-feed/v1", briefs: [] }));
        return;
      }
      response.writeHead(404);
      response.end("no");
    });
    await new Promise((done) => feedServer.listen(0, "127.0.0.1", done));
    feedBaseUrl = `http://127.0.0.1:${feedServer.address().port}`;
  });

  afterEach(async () => {
    await new Promise((done) => feedServer.close(done));
    await stopServer(instance);
  });

  it("requires a session", async () => {
    const session = new Session(instance.baseUrl);
    const { status } = await session.json("/api/proxy/feed", jsonPost({ url: `${feedBaseUrl}/feed.json` }));
    assert.equal(status, 401);
  });

  it("refuses a private AI endpoint even with a key", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    const { status, body } = await session.json("/api/proxy/ai-completions", jsonPost({
      endpoint: `${feedBaseUrl}/v1/chat/completions`,
      payload: { model: "demo", messages: [] }
    }));
    assert.equal(status, 400);
    assert.equal(body.error.code, "proxy-target-invalid");
  });

  it("refuses a malformed AI forward", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    const placeholderKey = ["synthetic", "provider", "key"].join("-");
    const cases = [
      { endpoint: "https://api.example.invalid/v1/chat/completions", payload: {} },
      { endpoint: "https://api.example.invalid/v1/chat/completions", apiKey: placeholderKey, payload: "text" },
      { endpoint: "ftp://api.example.invalid/v1", apiKey: placeholderKey, payload: {} },
      { endpoint: `https://user:${placeholderKey}@api.example.invalid/v1`, apiKey: placeholderKey, payload: {} }
    ];
    for (const body of cases) {
      const { status } = await session.json("/api/proxy/ai-completions", jsonPost(body));
      assert.equal(status, 400, JSON.stringify(body));
    }
  });

  it("refuses a cleartext public feed and credentials in the URL", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    for (const url of ["http://example.invalid/feed.json", "https://user:pass@example.invalid/feed.json"]) {
      const { status } = await session.json("/api/proxy/feed", jsonPost({ url }));
      assert.equal(status, 400, url);
    }
  });

  it("refuses a private feed unless the operator opted in", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    const { status } = await session.json("/api/proxy/feed", jsonPost({ url: `${feedBaseUrl}/feed.json` }));
    assert.equal(status, 400);
  });

  it("fetches a private feed when the operator opted in", async () => {
    const allowed = await startServer({ allowPrivateFeed: true });
    try {
      const session = new Session(allowed.baseUrl);
      await unlock(session);
      const { status, body } = await session.json("/api/proxy/feed", jsonPost({ url: `${feedBaseUrl}/feed.json` }));
      assert.equal(status, 200);
      assert.equal(body.proxied, true);
      assert.equal(JSON.parse(body.body).schema, "org.foe.world-brief-feed/v1");
    } finally {
      await stopServer(allowed);
    }
  });

  it("refuses a reserved hostname before connecting", async () => {
    const session = new Session(instance.baseUrl);
    await unlock(session);
    const { status, body } = await session.json("/api/proxy/feed", jsonPost({ url: "https://127.0.0.1.invalid/feed.json" }));
    assert.equal(status, 400);
    assert.equal(body.error.code, "proxy-target-invalid");
  });
});
