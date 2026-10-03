# Security

## 1. Security objective

Protect children and families from unauthorized disclosure, manipulation, profiling, coercion, and loss of control while keeping recovery and ordinary use practical. Security controls must survive the compromise or replacement of an AI provider, content publisher, or sync backend.

This document is a design requirement for an unfinished project, not a certification or claim of production readiness.

## 2. Trust boundaries

### Trusted only for their narrow role

- **Local client:** trusted to enforce policy and handle plaintext while unlocked; still designed to minimize retained plaintext.
- **Operating-system credential store:** trusted to protect BYOK secrets and local key wrappers where available.
- **Enrolled family devices:** trusted after authenticated enrollment; each has revocable credentials.
- **Selected cryptographic implementation:** trusted only after review, test vectors, and dependency controls.

### Untrusted or partially trusted

- AI providers and their responses;
- NAS, WebDAV, S3-compatible storage, and third-party hosting;
- World Brief publishers and pack contents;
- networks, proxies, link previews, and imported files;
- other local applications, browser extensions, and clipboard history;
- clocks and user-entered metadata;
- recovered offline devices that have missed revocations or tombstones.

No external provider is trusted with unrestricted Family Vault access.

## 3. Protected assets

- child and family identity, relationships, interests, reflections, constraints, schedules, and locations;
- attachments and derived embeddings/thumbnails;
- consent, objections, private drafts, and family decisions;
- vault, device, recovery, and provider keys;
- integrity and provenance of recommendations and opportunity data;
- availability of exports, recovery, and deletion;
- separation between households and between age-stage visibility scopes.

## 4. Threat model

| Threat | Example | Required mitigations | Residual risk / user truth |
|---|---|---|---|
| Sync-backend breach | S3 bucket or WebDAV account is exposed | Client-side authenticated encryption; opaque object names; no plaintext keys; metadata minimization | Object sizes and timing may leak unless padded/obscured |
| AI-provider overcollection | Prompt includes identity or full history | Context Firewall allow-list; purpose-scoped capability; preview where appropriate; BYOK; receipt and redaction tests | Provider still sees approved task context and follows its own terms |
| Speech-service overcollection | Device recognizer uploads a spoken child observation | Separate pre-launch disclosure; prefer offline recognition; no App microphone permission; retain no audio; return bounded text only as an editable unsaved/unsent draft | The operating system or chosen recognizer may still transmit or retain audio under its own terms |
| Prompt injection in family/imported text | A note tells the model to ignore rules or request more data | Every approved string is escaped inside a marked untrusted JSON data block; system/user rules forbid following embedded commands; response is a bounded strict schema; deterministic Gate remains outside the model | A model may still reason poorly, so no prompt is treated as the sole safety boundary |
| Prompt injection | Imported opportunity text asks the model to reveal vault data | Treat content as data; isolate instructions; capability sandbox; output validation; never expose a vault query tool | Model output can still be misleading and needs human review |
| Malicious pack | Pack contains executable payload, tracking URL, or deceptive content | Signed manifests; publisher identity; parser limits; no native code; sanitize links/content; explicit permissions; revocation | A validly signed publisher can still publish poor content |
| Stolen unlocked device | Attacker opens active client | OS lock integration; short-lived unlocked keys; local access controls; remote device revocation; private-stage screens | Local malware or a fully compromised OS can capture plaintext |
| Stolen locked device | Disk copied offline | Memory-hard KDF or OS-backed key; encrypted vault; rate limits; no plaintext indexes | Weak family passphrases reduce protection |
| Family-member overreach | Caregiver reads a teenager's private draft | Age-stage scopes; explicit share actions; audit UI; least privilege; emergency exceptions narrowly defined | Law and household power dynamics vary; software cannot eliminate coercion |
| Child bypass / accidental action | Young child publishes or purchases | Stage-aware authorization; caregiver confirmation; no autonomous enrollment, messaging, or spending | Shared device sessions can confuse identity |
| Cross-household mix-up | Data is imported into the wrong vault | Cryptographic household binding; explicit destination; checksums; preview the decrypted household name before a separate replacement confirmation; reject a stale local-vault preview | Users can still intentionally choose the wrong bundle; names can coincide and are not identity proof |
| Rollback or resurrection | Old device reuploads deleted content | Signed monotonic checkpoints; tombstone-first sync; deletion dominance; device revocation; stale-device quarantine | Permanently offline copies cannot be remotely destroyed |
| Supply-chain compromise | Dependency or update exfiltrates vault | Locked dependencies; provenance/SBOM; signed builds/updates; review; minimal dependency surface; reproducible-build goal | Client compromise can defeat most local protections |
| Credential theft | Provider API key appears in logs/export | OS credential storage; secret scanning; redacted logs; scoped keys; rotation flow | Provider-side misuse remains subject to provider controls |
| Availability/ransomware | Vault is damaged or encrypted by malware | Authenticated snapshots; encrypted offline backup; restore drills; append log verification | Backups introduce retention and key-recovery tradeoffs |
| Interrupted or unreadable local Vault | Startup mistakes unreadable ciphertext or a pending first write for an empty household and overwrites it | Fail-closed startup; preserve main and pending files; no new-family action; explicit retry only | Pending-write recovery and validated restore are not yet implemented |
| Concurrent local Vault edits | Two asynchronous App actions derive from one old family snapshot and the later write loses the first change | Serialize each App-instance read-modify-write, import, export, discovery receipt, sync preview, and erase through ADR 0015's operation gate | Not cross-process or cross-device; long external requests delay later local writes |
| Inference harm | AI labels a child or pushes unsafe opportunity | Provenance labels; prohibited sensitive inference; explainable policies; human decision; reporting and correction | AI can still be wrong, biased, or culturally unsuitable |
| Opportunity manipulation | A provider returns repetitive, sponsored, coercive, parent-pressure-maximizing, or source-spoofed options | Bind candidate source kind to the invoked Provider; goal provenance; sponsorship labels; deterministic Gate; Diversity checks; Nothing; human/child rejection | A registered provider or publisher can still make false claims; a family can still choose a poor option |
| Stale or deceptive world information | An event is canceled, sold out, moved, or a feed self-labels an item verified without independent proof | Source and retrieval times; publisher-attributed verification states; expiry; family verification prompt | Public sources can be wrong or change after verification; publisher signatures and independent trust remain unfinished |
| Traffic analysis | Host observes update cadence | Batch sync, minimal endpoints, optional padding later | MVP may not conceal all timing and volume metadata |

## 5. Cryptographic requirements

- Use maintained, widely reviewed libraries; do not design new primitives.
- Encrypt vault records and attachments with authenticated encryption.
- Use envelope encryption: independent data keys wrapped by a household key hierarchy.
- Separate keys by purpose and, where practical, subject and attachment to enable narrow erasure.
- Derive passphrase-based keys with a memory-hard KDF and versioned parameters.
- Device enrollment uses distinct device keys and authenticated approval.
- Bind household, object, version, and content type as associated data to prevent substitution.
- Include replay, truncation, reordering, corruption, wrong-key, and partial-write cases in tests.
- Key material never appears in events, logs, analytics, crash reports, sync paths, pack data, or provider prompts.
- Recovery design must explain who can recover, what they can see, and the consequence of losing all recovery material.

Concrete algorithms and parameter values require an ADR and specialist review before production.

The experimental Android sync boundary implements ADR 0006 with AES-256-GCM, a fresh 96-bit random nonce per seal, a 256-bit externally supplied household sync key, strict version/size/UTF-8/Base64 parsing, purpose-bound associated data, plaintext-buffer clearing, and authenticated opening before any merge. Tests cover opacity, nonce diversity, wrong key, ciphertext modification, unknown fields, and encrypted application through the existing hash-chain engine. It deliberately does not yet provide household key enrollment, device identity signatures, rotation/revocation, checkpoint authorization, metadata padding, or an independent review, so it is not a production sync claim.

ADR 0021 adds a separate Android-Keystore-backed P-256 signing identity primitive for future device proof of possession. Its private key is not exported, and its public-key fingerprint is only an aid for later human comparison. The current App does not use it to authorize a sync frame, transfer a household key, or enroll another device. A newly observed public key is untrusted until a separately reviewed, explicitly approved enrollment ceremony binds it to the household.

ADR 0022 adds signed, bounded join-session evidence with expected-household/key checks, two independent challenges, expiry, digest links, and strict parsing. It is not stored as a trusted roster or used by the Android App to transfer keys or accept frames. A shared-device `attributedActorId` is not human authentication; captured transcripts can be replayed until a signed consumed-invitation ledger and user-facing two-device comparison exist. These records remain experimental and are not a production admission path.

The Android reference App stores BYOK settings, World Brief credentials, and the opaque current-recorder preference in separate purpose-bound AES-GCM files backed by distinct Android Keystore aliases. The recorder preference is device-local UI state: it is not written into Family State, exports, provider prompts, or sync objects. Recorder selection remains attribution only and must not be represented as authenticated identity on a shared device.

The shared Android timeline consumes a fail-closed projection rather than raw Family State. Private or identity-scoped events, private evidence, choices linked to restricted events, and hypotheses with hidden or unresolved evidence dependencies are omitted without exposing hidden counts. This reduces accidental disclosure through the caregiver UI but is not an authorization boundary: all records still share the household vault/key and whole-vault recovery path. See ADR 0012.

Choice deletion now tombstones both the choice and its content-bearing linked events before a later merge can project stale copies. The deletion audit contains no title or response and inherits restricted visibility when the choice's source or linked history is restricted. This protects active projections, not old encrypted frames, exports, provider copies, or flash blocks; a shared-device tap does not prove subject identity. See ADR 0026.

ADR 0008 adds a ciphertext-only `SyncProvider` surface and filesystem reference adapter. Canonical random object IDs, bounded reads/pages, no-follow checks, immutable create-only writes, byte-identical retry, fail-closed replacement and exact deletion are tested. ADR 0009 adds a manual Android document-tree preview and provider-neutral client coordinator: it authenticates envelopes locally, requires a complete unbroken per-device history, rejects gaps/forks/rollback/disappearance, applies tombstones before presentation, encrypts the next frame before transport, and caps v1 history at 256 objects / 48 MiB. Folder URI, device ID and shared key are stored in a separate Android-Keystore-encrypted settings file. This still does not authenticate device identity, transfer keys safely, sign checkpoints, compact history or conceal access metadata; it remains a developer preview rather than production multi-device synchronization.

## 6. Context Firewall requirements

Every outbound request must have a declared purpose, actor, subject, provider capability, permitted categories, expiry, and policy decision. The firewall denies by default, excludes separate identifiers and irrelevant history, coarsens age/time, and records a non-sensitive receipt describing categories permitted. It cannot reliably remove names or addresses typed into free-text fields; the currently entered region is not verified as coarse. The Android App durably saves a category-only approval before contacting formal external sources, and the local Engine rejects a source whose actual category scope exceeds the approved set. This pre-send record is not evidence that bytes reached a provider. A later completion receipt can fail to save after a request may have begun; this is surfaced to the family and still requires a stronger transactional/serialized disclosure protocol.

Provider adapters cannot receive database handles, encryption keys, generalized search, filesystem access, or hidden background authority. A new provider begins with zero capabilities. Raw request logging is off by default.

## 7. World Brief and Pack security

WorldBriefProvider is a dynamic public-world service. It normally receives only public query constraints such as a user-entered region, time window, language, and categories—never a Family Vault handle or child history. A region may itself contain a name or address, so the per-call UI must display it as entered and ask for confirmation. The Engine requires an exact approved `PublicWorldQuery` before invoking the service; a missing or changed region, time window, language, or category list fails closed without a fetch. Responses carry original sources, retrieval and event times, verification state, sponsorship, tracking warnings, and expiry. Imported text is untrusted data and cannot issue instructions to an LLM, request more family context, or invoke client tools.

Pack manifests identify publisher, version, compatibility, content digest, signature, sources, retrieved dates, declared links, license, sponsorship, and requested declarative features. Installation and updates are verified before parsing. Parsers enforce size, nesting, decompression, media, and URL limits. v0.1 Packs are declarative data, not arbitrary executable code; dynamic behavior belongs behind a capability-limited Provider.

A trust store and revocation mechanism must support compromised publisher keys. Previously installed packs remain visibly attributable and can be disabled without deleting family decisions derived from them.

## 8. Logging and telemetry

Security logs use event IDs, reason codes, and coarse operational metrics; they avoid names, free text, prompts, model responses, precise locations, attachment contents, and secrets. Telemetry is off by default in the MVP. Any future telemetry is opt-in, documented by field and recipient, independently revocable, and never required for core use.

## 9. Authorization and high-impact actions

Export, provider disclosure, device enrollment, membership changes, key rotation, bulk deletion, Graduation migration, external contact, enrollment, purchase, and public sharing require explicit authorization appropriate to the age stage. AI cannot grant authorization. Sensitive actions show their scope and consequence in plain language and create a local receipt.

Emergency access, if ever introduced, must be separately designed, highly visible, narrowly scoped, and auditable; it is not part of the MVP.

## 10. Security verification gates

Before an MVP can be called ready for real family data, it must have:

- an implemented threat-model test matrix;
- independent review of key management and sync encryption;
- deterministic schema and migration tests;
- Context Firewall denial and redaction tests;
- malicious/corrupt Pack fixtures, World Brief prompt-injection/staleness fixtures, and parser fuzzing;
- Opportunity Gate and Diversity tests for coercion, sponsorship, repetition, goal displacement, and Nothing;
- backup/restore, stale-device, revocation, and deletion drills;
- dependency scanning, secret scanning, and signed release artifacts;
- a monitored private vulnerability-reporting channel and response process.

Until these gates are met, use synthetic data only.

## 11. Vulnerability reporting

The chosen route is **GitHub private vulnerability reporting**, not a public issue or a project email inbox. It is **not active yet**: [GitHub limits this feature to public repositories](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting/configure-for-a-repository), so it cannot be enabled on the current private repository, and no monitored reporting channel has been confirmed. While the repository remains private, finish CI and history checks and prepare maintainer security notifications. Only after an owner explicitly approves public visibility, make the repository public, immediately enable private vulnerability reporting in its security settings, and verify that an outside reporter can see **Report a vulnerability** and that a maintainer receives notifications. GitHub does not provide an atomic visibility-and-reporting switch, so there can be a short interval when the source is public but this channel is unavailable. Do not announce publication or describe the route as available until verification succeeds. If that interval is unacceptable, choose and document another confidential reporting route before changing visibility; never ask for sensitive details in a public issue.

Once enabled, report suspected vulnerabilities through the repository's **Security and quality → Advisories → Report a vulnerability** flow. Include the affected commit or version, impact, and a minimal reproduction using only fictional data. Never attach real children's records, Family Vaults, API keys, recovery codes, or unredacted provider responses. Ordinary feature requests and non-sensitive bugs can use public issues.

**Supported versions:** only the current developer-preview `main` branch is under active security maintenance until a release policy names supported tags. There is no production-ready version. Maintainers will triage reports and coordinate a fix, advisory, and reporter credit privately before public disclosure where practical. The project makes **no fixed first-response or repair-time promise**; reporting status must not be represented as an SLA. Reporters should avoid public technical disclosure while a private report is being assessed, but the project cannot impose an indefinite embargo.

If a credential or key is exposed, treat it as compromised immediately: revoke or rotate it with its issuer, stop using affected artifacts, assess whether family data or encrypted sync material was exposed, and notify affected people through an appropriate channel. Removing a value from current source or rewriting Git history does not revoke it or erase copies already fetched. A Family Vault or sync-key compromise needs a separate reviewed recovery plan; this developer preview must not claim that rotating one API key restores household confidentiality.
