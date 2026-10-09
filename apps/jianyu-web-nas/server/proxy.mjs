const PRIVATE_HOSTNAMES = new Set(["localhost", "127.0.0.1", "0.0.0.0", "::", "::1", "[::1]"]);
const PRIVATE_HOST_SUFFIXES = [".localhost", ".local", ".internal", ".home.arpa"];
const IPV4_PATTERN = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;

function parseIpv4(hostname) {
  const match = IPV4_PATTERN.exec(hostname);
  if (!match) return null;
  const octets = match.slice(1).map((part) => Number.parseInt(part, 10));
  if (octets.some((octet) => octet > 255)) return null;
  return octets;
}

/**
 * True when a hostname belongs to the family's own network or the loopback
 * range. Used to refuse server-side requests that would otherwise let a
 * caller probe whatever else runs on the NAS or the home LAN.
 */
export function isPrivateAddress(hostname) {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (PRIVATE_HOSTNAMES.has(host)) return true;
  if (PRIVATE_HOST_SUFFIXES.some((suffix) => host.endsWith(suffix))) return true;
  const ipv4 = parseIpv4(host);
  if (ipv4) {
    const [a, b] = ipv4;
    return a === 0 || a === 10 || a === 127 || (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) || (a === 192 && b === 0) || (a === 198 && (b === 18 || b === 19)) ||
      (a === 203 && b === 0) || (a === 224) || a >= 240;
  }
  if (host.includes(":")) {
    const bare = host.split("%")[0];
    if (bare === "::" || bare === "::1") return true;
    if (bare.startsWith("::ffff:")) return isPrivateAddress(bare.slice("::ffff:".length));
    const firstGroup = bare.split(":")[0];
    return /^f[cd]/i.test(firstGroup) || /^fe[89ab]/i.test(firstGroup);
  }
  return false;
}

/**
 * Validates a URL the server is willing to fetch on a family's behalf.
 *
 * Two rules follow from ADR 0029: the AI forward always targets a public HTTPS
 * endpoint, and the feed fetch may target a private address only when the
 * operator opted in with `JIANYU_ALLOW_PRIVATE_FEED=1`.
 */
export function assertProxyTarget(rawUrl, { label, allowPrivate = false }) {
  if (typeof rawUrl !== "string" || rawUrl.trim() !== rawUrl || rawUrl.length === 0 || rawUrl.length > 2048) {
    throw new TypeError(`${label} 地址格式不正确`);
  }
  let url;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new TypeError(`${label} 地址格式不正确`);
  }
  if (url.username || url.password) throw new TypeError(`${label} 地址不能包含账号或密钥`);
  if (url.hash) throw new TypeError(`${label} 地址不能包含片段`);
  const privateHost = isPrivateAddress(url.hostname);
  if (url.protocol !== "https:" && !(allowPrivate && privateHost && url.protocol === "http:")) {
    throw new TypeError(`${label} 地址必须使用 HTTPS`);
  }
  if (privateHost && !allowPrivate) throw new TypeError(`${label} 地址不在允许的公共网络范围内`);
  return url;
}

async function readBounded(response, limit) {
  const declared = Number.parseInt(response.headers.get("content-length") ?? "", 10);
  if (Number.isInteger(declared) && declared > limit) {
    throw new RangeError("上游响应超过大小上限");
  }
  const reader = response.body?.getReader();
  if (!reader) return "";
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) throw new RangeError("上游响应超过大小上限");
    chunks.push(value);
  }
  const merged = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(merged);
}

async function sendWithTimeout(url, init, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    // redirect: "error" keeps a redirect from being followed to an address the
    // caller never approved, which is the classic SSRF bypass.
    return await fetch(url, { ...init, redirect: "error", signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Forwards one AI completion request.
 *
 * The provider key and the already-minimized task context live in memory for
 * the duration of this call: never written to disk, never logged, never kept
 * between requests. The browser runs the Context Firewall first, so this
 * function only ever sees what the family approved for that single call.
 */
export async function forwardAiCompletion({ endpoint, apiKey, payload }, config) {
  const url = assertProxyTarget(endpoint, { label: "AI 服务地址" });
  if (typeof apiKey !== "string" || apiKey.trim() === "" || apiKey.length > 512) {
    throw new TypeError("AI 服务密钥格式不正确");
  }
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    throw new TypeError("AI 请求体格式不正确");
  }
  let body;
  try {
    body = JSON.stringify(payload);
  } catch {
    throw new TypeError("AI 请求体格式不正确");
  }
  if (body.length > 256 * 1024) throw new RangeError("AI 请求体超过大小上限");
  const response = await sendWithTimeout(
    url,
    {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${apiKey}` },
      body
    },
    config.aiProxyTimeoutMs
  );
  const text = await readBounded(response, config.aiProxyMaxResponseBytes);
  return { status: response.status, ok: response.ok, body: text };
}

/** Fetches a public World Brief feed and returns its bounded bytes. */
export async function fetchPublicFeed({ url: feedUrl }, config) {
  const url = assertProxyTarget(feedUrl, { label: "公开资讯地址", allowPrivate: config.allowPrivateFeed });
  const response = await sendWithTimeout(url, { method: "GET" }, config.feedTimeoutMs);
  if (!response.ok) {
    throw new RangeError(`公开资讯服务返回 ${response.status}`);
  }
  const text = await readBounded(response, config.feedMaxResponseBytes);
  return { status: response.status, body: text };
}
