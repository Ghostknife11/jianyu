// Address rules shared by the NAS server and the browser client.
//
// The same file is imported by `server/proxy.mjs`, which enforces these rules
// before any request leaves the container, and by the web client, which checks
// them before offering a route at all. Keeping one copy means the browser can
// never believe an address is acceptable when the server would refuse it.

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
