# Jianyu Web prototype

This browser build is retained as an interaction and domain prototype for the native Android App. It is not the primary product deliverable and must not be wrapped in a WebView and presented as native Android.

Discovery currently uses local templates, a declarative synthetic Pack, and a mock WorldBriefProvider. The Opportunity Gate, ecosystem diversity, and first-class Nothing option come from public FOE interfaces.

Start the browser prototype from the repository root:

```text
node apps/jianyu-web-prototype/dev-server.js
```

Then open `http://127.0.0.1:4173`.

Run the command-line flow:

```text
node apps/jianyu-web-prototype/src/demo.js
```

Family data is encrypted at rest in browser IndexedDB and is not sent to a remote service. This is not yet the production vault: recovery keys, native secure storage, encrypted attachments, multi-device key exchange, a complete migration suite, and external security review remain future work. See `docs/adr/0001-reference-app-foundation.md` for the replaceable module boundaries.

Maintainer checks:

```text
node scripts/check.mjs
node --test
node scripts/smoke-app.mjs
```

The smoke check launches an isolated headless Chrome profile and verifies the real App journey, ciphertext-at-rest, relock persistence, encrypted bundle round-trip, offline reload, basic accessibility, and mobile overflow.
