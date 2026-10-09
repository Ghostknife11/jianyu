import { createReadStream, existsSync, statSync } from "node:fs";
import { createServer } from "node:http";
import { extname, isAbsolute, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { HouseholdAuthStore } from "./auth-store.mjs";
import { CiphertextObjectStore } from "./blob-store.mjs";
import { loadConfig } from "./config.mjs";
import { fetchPublicFeed, forwardAiCompletion } from "./proxy.mjs";

const repositoryRoot = resolve(fileURLToPath(new URL("../../..", import.meta.url)));

// Same header set as the browser prototype: the web client runs entirely from
// this origin and never needs inline scripts, styles, or third-party origins.
const SECURITY_HEADERS = {
  "content-security-policy": "default-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
  "referrer-policy": "no-referrer",
  "x-content-type-options": "nosniff"
};

const CONTENT_TYPES = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".webmanifest": "application/manifest+json; charset=utf-8"
};

const STATIC_ALIASES = {
  "/": "/apps/jianyu-web-nas/web/index.html"
};

const STATIC_PREFIXES = [
  "/apps/jianyu-web-nas/web/",
  "/packages/foe-core/src/",
  "/packages/foe-opportunity/src/",
  "/packages/foe-schema/src/",
  "/packages/pack-sdk/src/",
  "/packages/policy-sdk/src/",
  "/packages/provider-sdk/src/",
  "/packages/brand-config/",
  "/examples/packs/",
  "/examples/world-brief/"
];

const OBJECT_ID_PATTERN = /^[A-Za-z0-9_-]{24}$/;

class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function badRequest(message, code = "invalid-request") {
  return new ApiError(400, code, message);
}

function unauthorized() {
  return new ApiError(401, "no-session", "请先解锁家庭保险箱");
}

function sleep(milliseconds) {
  return new Promise((done) => setTimeout(done, milliseconds));
}

function sendJson(response, status, payload) {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    ...SECURITY_HEADERS
  });
  response.end(body);
}

function sendNoContent(response) {
  response.writeHead(204, { "cache-control": "no-store", ...SECURITY_HEADERS });
  response.end();
}

function sendError(response, error) {
  const status = error instanceof ApiError ? error.status : 500;
  const code = error instanceof ApiError ? error.code : "server-error";
  if (status >= 500) console.error(`[jianyu-nas] ${error?.stack ?? error}`);
  sendJson(response, status, {
    error: { code, message: status >= 500 ? "服务器内部错误" : error.message }
  });
}

async function readBody(request, limitBytes) {
  const chunks = [];
  let total = 0;
  for await (const chunk of request) {
    total += chunk.length;
    if (total > limitBytes) {
      const error = badRequest("请求体超过大小上限", "body-too-large");
      error.status = 413;
      throw error;
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function readJsonBody(request, config) {
  const text = (await readBody(request, config.maxJsonBodyBytes)).toString("utf8");
  if (text.trim() === "") return {};
  try {
    const parsed = JSON.parse(text);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("not an object");
    return parsed;
  } catch {
    throw badRequest("请求体必须是 JSON 对象");
  }
}

function parseCookie(header, name) {
  if (typeof header !== "string") return null;
  for (const part of header.split(";")) {
    const separator = part.trim().indexOf("=");
    if (separator === -1) continue;
    if (part.trim().slice(0, separator) !== name) continue;
    const value = part.trim().slice(separator + 1);
    return value === "" ? null : value;
  }
  return null;
}

function sessionCookie(config, sessionId, maxAgeSeconds) {
  const parts = [
    `${config.sessionCookieName}=${sessionId}`,
    "Path=/",
    "HttpOnly",
    "SameSite=Strict",
    `Max-Age=${maxAgeSeconds}`
  ];
  if (!config.insecureHttp) parts.push("Secure");
  return parts.join("; ");
}

function clearCookie(config) {
  const parts = [`${config.sessionCookieName}=`, "Path=/", "HttpOnly", "SameSite=Strict", "Max-Age=0"];
  if (!config.insecureHttp) parts.push("Secure");
  return parts.join("; ");
}

function resolveStaticPath(pathname, staticRoot) {
  const requested = STATIC_ALIASES[pathname] ?? pathname;
  let decoded;
  try {
    decoded = decodeURIComponent(requested);
  } catch {
    return null;
  }
  if (!STATIC_PREFIXES.some((prefix) => decoded.startsWith(prefix))) return null;
  if (decoded.split("/").some((segment) => segment.startsWith("."))) return null;
  const candidate = resolve(staticRoot, `.${decoded}`);
  const relativePath = relative(staticRoot, candidate);
  if (relativePath.startsWith("..") || isAbsolute(relativePath)) return null;
  return candidate;
}

function sendStaticFile(response, pathname, staticRoot) {
  const filePath = resolveStaticPath(pathname, staticRoot);
  if (!filePath || !existsSync(filePath) || !statSync(filePath).isFile()) {
    sendJson(response, 404, { error: { code: "not-found", message: "页面不存在" } });
    return;
  }
  response.writeHead(200, {
    "cache-control": "no-store",
    "content-type": CONTENT_TYPES[extname(filePath)] ?? "application/octet-stream",
    ...SECURITY_HEADERS
  });
  const stream = createReadStream(filePath);
  stream.on("error", () => response.destroy());
  stream.pipe(response);
}

/**
 * The self-hosted Jianyu NAS server.
 *
 * It serves the browser client, authenticates households with a derived
 * verifier it cannot reverse, stores opaque ciphertext objects, and offers a
 * labeled transient proxy. It never receives, derives, stores, or logs family
 * plaintext or passphrases (ADR 0029).
 */
export function createJianyuNasServer(config = loadConfig()) {
  const auth = new HouseholdAuthStore(config);
  const blobs = new CiphertextObjectStore(config);
  const http = createServer(async (request, response) => {
    try {
      await route(request, response);
    } catch (error) {
      sendError(response, error);
    }
  });

  async function householdOf(request) {
    const sessionId = parseCookie(request.headers.cookie, config.sessionCookieName);
    if (!sessionId) return null;
    const session = await auth.resolveSession(sessionId);
    return session?.householdId ?? null;
  }

  async function route(request, response) {
    const host = request.headers.host ?? `127.0.0.1:${config.port}`;
    const url = new URL(request.url ?? "/", `http://${host}`);
    const { pathname } = url;
    const started = Date.now();
    const finish = (status) => {
      if (pathname !== "/healthz") {
        console.log(`${request.method} ${pathname} -> ${status} (${Date.now() - started}ms)`);
      }
    };
    response.on("finish", () => finish(response.statusCode));

    if (pathname === "/healthz") {
      sendJson(response, 200, { status: "ok", service: "jianyu-nas" });
      return;
    }

    if (pathname.startsWith("/api/")) {
      await routeApi(request, response, url);
      return;
    }
    sendStaticFile(response, pathname, config.staticRoot);
  }

  async function routeApi(request, response, url) {
    const { pathname } = url;
    const method = request.method ?? "GET";

    if (method === "GET" && pathname === "/healthz") {
      sendJson(response, 200, { status: "ok", service: "jianyu-nas" });
      return;
    }

    if (method === "GET" && pathname === "/api/capabilities") {
      sendJson(response, 200, {
        aiProxy: true,
        feedProxy: true,
        privateFeedAllowed: config.allowPrivateFeed,
        secureCookies: !config.insecureHttp,
        maxObjectBytes: config.maxObjectBytes,
        objectPageMax: config.objectPageMax,
        sessionTtlDays: Math.round(config.sessionTtlMs / 86_400_000)
      });
      return;
    }

    if (method === "POST" && pathname === "/api/households") {
      await registerHousehold(request, response);
      return;
    }

    if (pathname === "/api/sessions") {
      if (method === "POST") await unlockHousehold(request, response);
      else if (method === "DELETE") await lockHousehold(request, response);
      else throw new ApiError(405, "method-not-allowed", "方法不允许");
      return;
    }

    const householdId = await householdOf(request);
    if (!householdId) throw unauthorized();

    if (method === "GET" && pathname === "/api/households/me") {
      sendJson(response, 200, {
        householdId,
        objectCount: await blobs.count(householdId),
        objectLimit: config.maxObjectsPerHousehold,
        state: await auth.readStatePointer(householdId)
      });
      return;
    }

    if (method === "DELETE" && pathname === "/api/households/me") {
      await blobs.erase(householdId);
      await auth.erase(householdId);
      response.setHeader("set-cookie", clearCookie(config));
      sendJson(response, 200, { erased: true });
      return;
    }

    if (pathname === "/api/state") {
      if (method === "GET") await readState(response, householdId);
      else if (method === "PUT") await commitState(request, response, householdId);
      else throw new ApiError(405, "method-not-allowed", "方法不允许");
      return;
    }

    if (pathname === "/api/objects") {
      if (method === "GET") await listObjects(response, url, householdId);
      else throw new ApiError(405, "method-not-allowed", "方法不允许");
      return;
    }

    if (pathname.startsWith("/api/objects/")) {
      const objectId = pathname.slice("/api/objects/".length);
      if (method === "PUT") await putObject(request, response, householdId, objectId);
      else if (method === "GET") await getObject(response, householdId, objectId);
      else if (method === "DELETE") await deleteObject(response, householdId, objectId);
      else throw new ApiError(405, "method-not-allowed", "方法不允许");
      return;
    }

    if (method === "POST" && pathname === "/api/proxy/ai-completions") {
      await proxyAiCompletion(request, response);
      return;
    }

    if (method === "POST" && pathname === "/api/proxy/feed") {
      await proxyFeed(request, response);
      return;
    }

    throw new ApiError(404, "not-found", "接口不存在");
  }

  async function registerHousehold(request, response) {
    const body = await readJsonBody(request, config);
    const source = request.socket.remoteAddress ?? "unknown";
    auth.noteRegisterAttempt(source);
    const delay = auth.registerDelayFor(source);
    if (delay > 0) await sleep(delay);
    try {
      const created = await auth.register(body.householdId, body.verifier, body.salt, body.iterations);
      sendJson(response, 201, created);
    } catch (error) {
      if (error?.code === "household-exists") {
        throw new ApiError(409, "household-exists", "这个家庭标识已经在这台服务器上注册过了");
      }
      if (error instanceof TypeError || error instanceof RangeError) throw badRequest(error.message);
      throw error;
    }
  }

  async function unlockHousehold(request, response) {
    const body = await readJsonBody(request, config);
    const source = request.socket.remoteAddress ?? "unknown";
    const householdId = typeof body.householdId === "string" ? body.householdId : "";
    const keys = [`unlock|${source}`, `unlock|${source}|${householdId}`];
    const session = await auth.unlock(householdId, body.verifier);
    if (!session) {
      auth.noteUnlockFailure(keys);
      await sleep(auth.unlockDelayFor(keys));
      throw new ApiError(401, "unlock-failed", "口令不正确，或该家庭不在这台服务器上");
    }
    auth.clearUnlockFailures(keys);
    response.setHeader("set-cookie", sessionCookie(config, session.sessionId, Math.round(config.sessionTtlMs / 1000)));
    sendJson(response, 200, { householdId: session.householdId, expiresAt: session.expiresAt });
  }

  async function lockHousehold(request, response) {
    const sessionId = parseCookie(request.headers.cookie, config.sessionCookieName);
    await auth.revokeSession(sessionId);
    response.setHeader("set-cookie", clearCookie(config));
    sendNoContent(response);
  }

  async function readState(response, householdId) {
    const pointer = await auth.readStatePointer(householdId);
    sendJson(response, 200, pointer ?? { version: 0, objectId: null, updatedAt: null });
  }

  async function commitState(request, response, householdId) {
    const body = await readJsonBody(request, config);
    if (!OBJECT_ID_PATTERN.test(String(body.objectId))) {
      throw badRequest("objectId 必须是不透明对象标识", "invalid-object-id");
    }
    if (!Number.isInteger(body.baseVersion) || body.baseVersion < 0) {
      throw badRequest("baseVersion 必须是非负整数", "invalid-base-version");
    }
    if (!(await blobs.get(householdId, body.objectId))) {
      throw badRequest("提交的对象尚未上传", "object-missing");
    }
    try {
      const committed = await auth.commitState(householdId, body.objectId, body.baseVersion);
      sendJson(response, 200, committed);
    } catch (error) {
      if (error?.code !== "state-conflict") throw error;
      sendJson(response, 409, {
        error: {
          code: "state-conflict",
          message: "另一台设备已经更改了家庭状态，请重新载入后再合并",
          currentVersion: error.currentVersion,
          currentObjectId: error.currentObjectId
        }
      });
    }
  }

  async function listObjects(response, url, householdId) {
    const cursor = url.searchParams.get("cursor");
    if (cursor !== null && cursor !== "" && !OBJECT_ID_PATTERN.test(cursor)) {
      throw badRequest("cursor 必须是不透明对象标识", "invalid-cursor");
    }
    const rawLimit = url.searchParams.get("limit");
    let limit = config.objectPageDefault;
    if (rawLimit !== null) {
      limit = Number.parseInt(rawLimit, 10);
      if (!Number.isInteger(limit) || limit < 1 || limit > config.objectPageMax) {
        throw badRequest(`limit 必须是 1 到 ${config.objectPageMax} 的整数`, "invalid-limit");
      }
    }
    const page = await blobs.list(householdId, cursor ?? "", limit);
    sendJson(response, 200, page);
  }

  async function putObject(request, response, householdId, objectId) {
    if (!OBJECT_ID_PATTERN.test(objectId)) {
      throw badRequest("对象标识必须是不透明 Base64URL", "invalid-object-id");
    }
    const contentType = String(request.headers["content-type"] ?? "");
    if (!contentType.startsWith("application/octet-stream")) {
      throw new ApiError(415, "unsupported-content-type", "密文对象必须使用 application/octet-stream");
    }
    const bytes = await readBody(request, config.maxObjectBytes + 1);
    try {
      const result = await blobs.put(householdId, objectId, new Uint8Array(bytes));
      sendJson(response, result.created ? 201 : 200, { objectId, ...result });
    } catch (error) {
      if (error?.code === "object-conflict") {
        throw new ApiError(409, "object-conflict", "同一对象标识已存在不同内容，写入被拒绝");
      }
      if (error?.code === "object-limit") throw new ApiError(507, "object-limit", error.message);
      if (error instanceof RangeError) throw badRequest(error.message, "object-too-large");
      throw error;
    }
  }

  async function getObject(response, householdId, objectId) {
    if (!OBJECT_ID_PATTERN.test(objectId)) {
      throw badRequest("对象标识必须是不透明 Base64URL", "invalid-object-id");
    }
    const bytes = await blobs.get(householdId, objectId);
    if (!bytes) throw new ApiError(404, "object-missing", "对象不存在");
    response.writeHead(200, {
      "content-type": "application/octet-stream",
      "content-length": bytes.byteLength,
      "cache-control": "no-store",
      ...SECURITY_HEADERS
    });
    response.end(Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength));
  }

  async function deleteObject(response, householdId, objectId) {
    if (!OBJECT_ID_PATTERN.test(objectId)) {
      throw badRequest("对象标识必须是不透明 Base64URL", "invalid-object-id");
    }
    if (!(await blobs.delete(householdId, objectId))) {
      throw new ApiError(404, "object-missing", "对象不存在");
    }
    sendNoContent(response);
  }

  async function proxyAiCompletion(request, response) {
    const body = await readJsonBody(request, config);
    try {
      const result = await forwardAiCompletion(body, config);
      sendJson(response, result.status, { proxied: true, status: result.status, body: result.body });
    } catch (error) {
      if (error instanceof TypeError || error instanceof RangeError) {
        throw badRequest(error.message, "proxy-target-invalid");
      }
      const cause = error?.cause ?? error;
      if (cause?.name === "AbortError" || cause?.code === "UND_ERR_CONNECT_TIMEOUT") {
        throw new ApiError(504, "proxy-timeout", "AI 服务响应超时");
      }
      throw new ApiError(502, "proxy-failed", "无法连接 AI 服务，请检查地址与密钥");
    }
  }

  async function proxyFeed(request, response) {
    const body = await readJsonBody(request, config);
    try {
      const result = await fetchPublicFeed(body, config);
      sendJson(response, 200, { proxied: true, status: result.status, body: result.body });
    } catch (error) {
      if (error instanceof TypeError || error instanceof RangeError) {
        throw badRequest(error.message, "proxy-target-invalid");
      }
      throw new ApiError(502, "proxy-failed", "无法获取公开资讯，请检查地址");
    }
  }

  return {
    config,
    auth,
    blobs,
    server: http,
    async listen() {
      await auth.prepare();
      await blobs.prepare();
      await new Promise((done) => http.listen(config.port, config.host, done));
      return http.address();
    },
    async close() {
      http.closeAllConnections?.();
      await new Promise((done) => http.close(done));
    }
  };
}

export function createServerFromEnvironment(env = process.env) {
  return createJianyuNasServer(loadConfig(env));
}

const invokedDirectly = process.argv[1] !== undefined &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (invokedDirectly) {
  const instance = createServerFromEnvironment();
  instance.listen().then(() => {
    console.log(`见隅 NAS 网页版已启动：http://${instance.config.host}:${instance.config.port}`);
    console.log("家庭数据在浏览器内加密；服务器只保存密文。远程访问请经反向代理启用 HTTPS。");
  }).catch((error) => {
    console.error(`[jianyu-nas] 启动失败：${error?.message ?? error}`);
    process.exitCode = 1;
  });
}
