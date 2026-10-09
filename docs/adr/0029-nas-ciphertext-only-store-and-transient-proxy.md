# ADR 0029: NAS server is a ciphertext-only store with a labeled transient proxy

- Status: Accepted for experimental developer preview
- Date: 2026-10-10

## Context

FORKING.md §7 permits a fork to operate a service but forbids presenting it as the upstream default, and states that client-side encryption remains mandatory before family data reaches a host: a fork that introduces server-side plaintext features "has crossed a major trust boundary and must label them explicitly, obtain separate authorization, and avoid describing them as equivalent to the upstream privacy model." PRIVACY.md §8 is stricter still: hosts "receive client-encrypted objects only," and visible transport metadata must be documented honestly. README.zh-CN.md names the problem precisely: centralized control of children's plaintext.

ADR 0008 already defines the server-side surface a storage host may expose: four ciphertext-only operations over opaque, immutable, randomly named objects. ADR 0006 defines the envelope such a host may see. What is missing is a decision for a *stateful* web server: one that authenticates browsers, holds the household's ciphertext between visits, and must still not become a plaintext host.

Two practical forces shape the design. First, a zero-knowledge server cannot verify a passphrase, so authentication must be built from a derived verifier rather than a password check. Second, the primary discovery path is BYOK AI (ADR 0003), and major OpenAI-compatible services do not send CORS headers, so a browser cannot call them directly; without a server-side forward, the headline feature would break for ordinary families.

## Decision

The NAS server is a **ciphertext-only store with a labeled transient proxy**. It never receives, derives, stores, or logs plaintext family data or passphrases.

**Storage.** The server exposes ADR 0008's object semantics over HTTP, scoped to an authenticated household: list with an opaque bounded cursor, put (immutable; byte-identical retry is idempotent; different bytes under one ID fail closed), get, and delete. Object IDs are canonical 24-character random Base64URL values generated independently of household, child, member, device, frame, event, tombstone, and content-hash identifiers. The server stores no index derived from content, no semantic filenames, and no plaintext. Each household's current encrypted state is one such object; the client may retain a small bounded history.

**Authentication (zero-knowledge).** At registration the client generates a random salt and derives two independent keys with PBKDF2-SHA-256: a verifier for server-side authentication and a key-encryption key that never leaves the browser. The server stores only the household's random ID, the salt, the iteration count, and `SHA-256(verifier)`. Unlocking submits the verifier over the session and receives an opaque session token stored server-side only as a SHA-256 hash, delivered as an `HttpOnly`, `SameSite=Strict` cookie (30-day sliding expiry). Failures are throttled per IP and per household with delay only — never a lockout that could strand data. The server cannot distinguish a wrong passphrase from a wrong household except by the verifier hash.

**Concurrency.** Writes carry the client's base version. If the stored object has advanced, the write is refused and the client must reload and merge locally using the public `mergeFamilyState`. This is an honest single-active-writer model with conflict reload. The product and documentation must not describe it as automatic multi-device synchronization; enrollment, device signatures, signed checkpoints, rotation, and revocation remain the unfinished work named in ADR 0005–0009, 0021, and 0022.

**Transient proxy (opt-in, labeled).** Two endpoints exist for practical reachability, both requiring a valid session, both bounded, both disclosed in the UI before use:

- *AI completions forward.* The browser sends the family's provider endpoint, API key, and the already-minimized task context; the server forwards one request and returns the response. The key and context exist in server memory for the duration of the call only — never written to disk, never logged. The Context Firewall still runs in the browser first, so the server sees only what the family approved for that call.
- *Public feed fetch.* The server fetches a configured HTTPS World Brief feed URL and returns the bytes. Requests are https-only, reject embedded credentials, cap response size, and apply a timeout. Private address ranges are refused unless an operator explicitly sets `JIANYU_ALLOW_PRIVATE_FEED=1`, which exists for families running their own feed service on their own network.

A "browser-direct" connection option remains available for self-hosted OpenAI-compatible services that send permissive CORS headers, so families can avoid the proxy entirely where their provider allows it.

**What the server can see, stated honestly in product copy and documentation:** ciphertext and its size, object counts, request timing, IP addresses, the cookie, and — during a proxied AI call — the provider key and minimized task context in memory. It cannot see names, interests, evidence, history, or the vault key. Object sizes and timing remain the residual metadata risk SECURITY.md names for sync-backend breach.

## Alternatives considered

- **Server-side plaintext vault** (the conventional self-hosted pattern) was rejected: it contradicts PRIVACY.md §8 and FORKING.md §7, would require the explicit major-trust-boundary labeling and separate authorization those documents demand, and would put BYOK keys and children's records at the mercy of anyone with NAS or container access.
- **No proxy at all** was rejected: browser CORS restrictions would make the primary BYOK discovery path (ADR 0003) unusable with common providers, defeating the product for ordinary families.
- **Server-stored API keys** were rejected: persisting the key server-side widens the blast radius of a NAS compromise for no usability gain, since the browser can send it per request.
- **Password-based server authentication** was rejected: it would require the server to see the passphrase, breaking the zero-knowledge property the verifier design preserves.
- **Reusing the ADR 0022 device-join evidence as the admission path** was rejected: that record explicitly grants no access, and no trusted roster, consumed-invitation ledger, or key transfer exists.

## Security and privacy consequences

- A stolen or copied data volume yields ciphertext only; the GCM tag and the PBKDF2 work factor stand between an attacker and the plaintext. Weak family passphrases remain the dominant residual risk, exactly as SECURITY.md's stolen-locked-device row states.
- A malicious or compromised server can omit, replay, reorder, or delete objects — the residual risks ADR 0008 already assigns to the client. The client fails closed on tampering, verifies authentication before merge, and surfaces conflicts instead of silently resolving them.
- The AI proxy sees the provider key in memory during a call. This is disclosed in plain language in the connection UI, together with the alternative direct mode, satisfying FORKING.md §7's labeling duty.
- Sessions are revocable by deleting the server-side record; locking the vault in the browser drops the in-memory keys regardless of server state.
- Rate limiting protects the verifier endpoint; the store refuses writes beyond per-household object count and size caps.

## Compatibility and migration consequences

- The HTTP object API is an adaptation of the public `SyncProvider` contract (ADR 0008) for a stateful server; it does not reinterpret that contract, and a future signed-checkpoint or manifest-bearing contract requires a new version rather than silent change.
- The verifier/salt/iteration record format is versioned; changing the KDF or its parameters requires a new ADR per FORKING.md §6 and a client-side migration path.
- Household IDs, object IDs, and session tokens are opaque random values and carry no compatibility meaning.

## Rollback and exit plan

- The server can be replaced by any conforming ADR 0008 backend: copying the encrypted object files preserves family data, because the server holds no plaintext and no semantic index.
- The proxy endpoints can be disabled by configuration (and removed entirely) without affecting the vault, recovery bundle, or local-only use; families whose providers allow browser-direct calls lose nothing.
- If enrollment and signed checkpoints are later implemented, this record is superseded by the enrollment ADR and the object semantics remain as the transport layer.
