import { createReadStream, existsSync, statSync } from "node:fs";
import { createServer } from "node:http";
import { extname, isAbsolute, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const port = Number.parseInt(process.env.JIANYU_PORT ?? "4173", 10);

const contentTypes = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".svg": "image/svg+xml"
};

function resolveRequestPath(pathname) {
  const aliases = {
    "/": "/apps/jianyu-web-prototype/index.html",
    "/manifest.webmanifest": "/apps/jianyu-web-prototype/manifest.webmanifest",
    "/service-worker.js": "/apps/jianyu-web-prototype/service-worker.js"
  };
  let requestedPath;
  try {
    requestedPath = decodeURIComponent(aliases[pathname] ?? pathname);
  } catch {
    return null;
  }
  const allowedPrefixes = [
    "/apps/jianyu-web-prototype/",
    "/packages/foe-core/src/",
    "/packages/foe-opportunity/src/",
    "/packages/foe-schema/src/",
    "/packages/pack-sdk/src/",
    "/packages/policy-sdk/src/",
    "/packages/provider-sdk/src/",
    "/examples/packs/",
    "/examples/world-brief/"
  ];
  if (!allowedPrefixes.some((prefix) => requestedPath.startsWith(prefix))) return null;
  if (requestedPath.split("/").some((segment) => segment.startsWith("."))) return null;
  const candidate = resolve(repositoryRoot, `.${requestedPath}`);
  const repositoryRelative = relative(repositoryRoot, candidate);
  if (repositoryRelative.startsWith("..") || isAbsolute(repositoryRelative)) return null;
  return candidate;
}

const server = createServer((request, response) => {
  const host = request.headers.host ?? `127.0.0.1:${port}`;
  const pathname = new URL(request.url ?? "/", `http://${host}`).pathname;
  const filePath = resolveRequestPath(pathname);

  if (!filePath || !existsSync(filePath) || !statSync(filePath).isFile()) {
    response.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    response.end("Not found");
    return;
  }

  response.writeHead(200, {
    "cache-control": "no-store",
    "content-type": contentTypes[extname(filePath)] ?? "application/octet-stream",
    "content-security-policy": "default-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
    "referrer-policy": "no-referrer",
    "x-content-type-options": "nosniff",
    ...(pathname === "/service-worker.js" ? { "service-worker-allowed": "/" } : {})
  });
  createReadStream(filePath).pipe(response);
});

server.listen(port, "127.0.0.1", () => {
  console.log(`Jianyu is available at http://127.0.0.1:${port}`);
  console.log("Family data is encrypted in the browser; discovery sources are synthetic in this initial build.");
});
