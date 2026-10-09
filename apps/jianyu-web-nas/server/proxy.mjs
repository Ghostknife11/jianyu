// The transient proxy. Address validation lives in `shared/proxy-target.mjs`
// so the browser can check a URL with the same rules the server enforces;
// the re-export below keeps the existing module boundary intact.
import { assertProxyTarget, isPrivateAddress } from "../shared/proxy-target.mjs";

export { assertProxyTarget, isPrivateAddress };

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
    throw new TypeError("API 密钥格式不正确");
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
