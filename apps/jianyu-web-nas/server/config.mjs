import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(fileURLToPath(new URL("../../..", import.meta.url)));

function boundedInteger(value, fallback, { min, max, label }) {
  if (value === undefined || value === "") return fallback;
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) {
    throw new RangeError(`${label} must be an integer between ${min} and ${max}`);
  }
  return parsed;
}

function flag(value) {
  return value === "1" || value === "true";
}

/**
 * Runtime configuration for the self-hosted NAS server.
 *
 * Every value has a safe default so a family can run the container with no
 * environment variables at all. Nothing here can weaken the ciphertext-only
 * boundary: the data volume holds opaque objects, never family plaintext.
 */
export function loadConfig(env = process.env) {
  const host = env.JIANYU_HOST ?? "0.0.0.0";
  const port = boundedInteger(env.JIANYU_PORT, 8080, { min: 1, max: 65535, label: "JIANYU_PORT" });
  const dataDir = resolve(env.JIANYU_DATA_DIR ?? resolve(repositoryRoot, "apps/jianyu-web-nas/.data"));
  const staticRoot = resolve(env.JIANYU_STATIC_ROOT ?? repositoryRoot);
  const sessionTtlMs = boundedInteger(env.JIANYU_SESSION_TTL_MS, 30 * 24 * 60 * 60 * 1000, {
    min: 60_000,
    max: 90 * 24 * 60 * 60 * 1000,
    label: "JIANYU_SESSION_TTL_MS"
  });

  return {
    host,
    port,
    dataDir,
    staticRoot,
    sessionTtlMs,
    sessionCookieName: "jianyu_session",
    // Secure cookies need HTTPS at the reverse proxy. Local plain-HTTP testing
    // is the only reason to relax this, and the flag is documented as such.
    insecureHttp: flag(env.JIANYU_INSECURE_HTTP),
    // Lets a family point the World Brief feed at a service on their own
    // network (ADR 0029). The AI proxy stays public-HTTPS-only: a self-hosted
    // model server is served by the browser-direct connection option instead.
    allowPrivateFeed: flag(env.JIANYU_ALLOW_PRIVATE_FEED),
    maxObjectBytes: 24 * 1024 * 1024,
    maxObjectsPerHousehold: boundedInteger(env.JIANYU_MAX_OBJECTS, 512, {
      min: 8,
      max: 4096,
      label: "JIANYU_MAX_OBJECTS"
    }),
    objectPageDefault: 64,
    objectPageMax: 256,
    maxJsonBodyBytes: 1024 * 1024,
    aiProxyTimeoutMs: boundedInteger(env.JIANYU_AI_TIMEOUT_MS, 120_000, {
      min: 5_000,
      max: 300_000,
      label: "JIANYU_AI_TIMEOUT_MS"
    }),
    aiProxyMaxResponseBytes: 8 * 1024 * 1024,
    feedTimeoutMs: 30_000,
    feedMaxResponseBytes: 4 * 1024 * 1024,
    unlockThrottle: {
      windowMs: 15 * 60 * 1000,
      threshold: 2,
      baseDelayMs: 400,
      maxDelayMs: 5_000
    },
    // Registration is once per household, so the ceiling is high enough that a
    // family never notices it, while a flood still pays for every attempt.
    registerThrottle: {
      windowMs: 15 * 60 * 1000,
      threshold: 20,
      baseDelayMs: 250,
      maxDelayMs: 2_000
    }
  };
}
