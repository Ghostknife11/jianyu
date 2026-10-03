const CACHE_NAME = "jianyu-app-shell-v5";
const APP_SHELL = [
  "/",
  "/apps/jianyu-web-prototype/styles.css",
  "/apps/jianyu-web-prototype/icon.svg",
  "/apps/jianyu-web-prototype/src/ui.js",
  "/apps/jianyu-web-prototype/src/app-state.js",
  "/apps/jianyu-web-prototype/src/authorization.js",
  "/apps/jianyu-web-prototype/src/browser-vault.js",
  "/apps/jianyu-web-prototype/src/event-factory.js",
  "/apps/jianyu-web-prototype/src/local-discovery.js",
  "/apps/jianyu-web-prototype/src/opportunity-service.js",
  "/apps/jianyu-web-prototype/src/view-templates.js",
  "/apps/jianyu-web-prototype/src/vault-migrations.js",
  "/packages/foe-core/src/index.js",
  "/packages/foe-opportunity/src/index.js",
  "/packages/foe-schema/src/index.js",
  "/packages/pack-sdk/src/index.js",
  "/packages/policy-sdk/src/index.js",
  "/packages/provider-sdk/src/index.js",
  "/examples/packs/motorsport.demo.json",
  "/examples/world-brief/weekend.demo.js"
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((names) => Promise.all(names.filter((name) => name !== CACHE_NAME).map((name) => caches.delete(name))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET" || new URL(event.request.url).origin !== self.location.origin) return;
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy));
        }
        return response;
      })
      .catch(() => caches.match(event.request).then((cached) => cached ?? caches.match("/")))
  );
});
