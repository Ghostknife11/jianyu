# Extending Family Opportunity Engine

## 1. Rule

Open source here means that another team can genuinely replace, combine, and improve the system—not merely read the source. Jianyu itself uses these public interfaces and has no private capability.

v0.1 freezes only the extension points with demonstrated need:

- `LLMProvider`
- `SearchProvider`
- `WorldBriefProvider`
- `SyncProvider`
- `NotificationProvider`
- `AgePolicy`
- `OpportunityPolicy`
- `Pack`
- `BrandConfig`

Use the Rule of Two before adding more abstraction.

## 2. Provider contract

Every Provider describes its identity, version, capabilities, network destinations, data categories, authentication mode, retention assumptions, availability, and compatibility range. Installation/configuration grants no Family Vault access.

### LLMProvider

```text
describe() -> LLMDescriptor
validateConfiguration(secretRef, options) -> ValidationResult
generateCandidates(taskContext, options, cancellation) -> CandidateResult
```

It receives only a Context Firewall-produced `TaskContext`, never a vault handle. It must return structured candidates, model identity, uncertainty, and provider receipts. BYOK secrets are device-local by default.

Candidate provenance is bound by the reference engines to the source actually invoked, not to a source-kind string inside a Provider's own output. A Provider may supply attributable publisher details, but cannot impersonate a different registered source category to gain different Gate or diversity treatment. In Android reference Policy v0.2.6, a BYOK AI `world-event` without an original source URL is not a family-facing entrance; sourced, fresh public World Brief remains a separate dynamic service path. A source URL supplied by any third party is still an assertion to verify, not independent proof.

In the JavaScript reference flow, `createTaskContext(request, approvedLlmCategories)` projects only approved, bounded categories. The client must present the resulting values for this call and pass the reviewed object as `approvedLlmContext`; `runOpportunityFlow` recomputes it and skips the Provider if it changed. The capability must cover `minimized-task-context` plus each approved category. This does not scrub names or addresses typed into allowed free-text fields; the user must review those values. The Android reference App has its own per-call disclosure confirmation. A pure explicit refusal is not treated as positive child pull; add a separate active-interest clause before requesting a recommendation.

The Android OpenAI-compatible reference adapter uses non-streaming Chat Completions. When a response supplies `finish_reason`, only `stop` is treated as complete; `length`, filtering, tool calls, interruption, null, and unknown values do not become family options even if their partial text parses as JSON. Omission remains accepted for older compatible services. The optional public synthetic sample reports an incomplete response separately from a network failure or schema mismatch; one sample is not a model-quality verdict. Other adapters may implement different wire protocols through the public Provider contract, but must preserve the same complete-before-Gate boundary.

The public Android `SourceFailureException` accepts only `SourceFailureReason` values (`response-incomplete`, `authentication-rejected`, `rate-limited`, `invalid-response`). An adapter may throw one of these after discarding any raw provider body; the Engine records only its safe code in `DiscoverySourceIssue`, and the App translates known codes into family-facing guidance. Unknown errors retain the generic unavailable status. Cancellation still propagates and must not be mislabeled as a failed source. This is a status contract, not permission for a Provider to bypass the local Gate or show partial content.

### SearchProvider

```text
searchPublic(query, region, timeWindow, categories, cancellation)
  -> sourced public results
```

SearchProvider searches the public world. Family matching remains local. Results include source, retrieval time, and enough evidence to distinguish real records from generated ideas.

The JavaScript reference flow uses the exact reviewed `approvedWorldQuery` for Search just as for World Brief. Pre-mapped public candidates must carry `topics` for local matching and may add bounded `matchTerms` in the requested language; a child's explicitly refused advertised term suppresses the candidate. Clearly adult-led plans or external background alone do not trigger the fetch, while an adult refusal is not silently treated as a child veto. This is a v0.1 compatibility path, not permission to send family interests to a public Search service.

### WorldBriefProvider

```text
getBrief(region, timeWindow, categories, cursor, cancellation)
  -> WorldBriefPage

subscribe?(publicWatch, deliveryCapability)
  -> subscription receipt
```

WorldBriefProvider connects dynamic third-party services that may run continuously and use crawlers, APIs, AI agents, databases, editorial review, freshness checks, and push infrastructure. The normal request contains public constraints such as region, time, and category—not child interest history or Family Vault data.

The reference Android Engine and JavaScript flow require an exact `approvedWorldQuery` for each fetch. A missing approval, or any changed region, time window, language, or category list skips the external call and reports an out-of-scope source issue or provenance reason; the JavaScript public-query shape also allows an exact-matched pagination cursor. The JavaScript compatibility path requires public `topics` even when a provider returns pre-mapped candidates; matching remains client-side. Feed v1 services may add optional `matchTerms` (up to 20 public strings of 2–80 characters) in the requested language. For example, keep `topics: ["astronomy", "moon"]` and add `matchTerms: ["月亮", "观月"]` for `language=zh-CN`; do not receive, echo, or derive aliases from a child's private input. Older clients can ignore the optional field and older feeds still work through their topic terms. Third-party clients should obtain the same per-request approval before invoking a dynamic World Brief service. Allowed free-text `region` values are not automatically de-identified; show them as entered.

### SyncProvider

```text
listOpaqueObjects(cursor) -> ObjectPage
putEncryptedObject(objectId, ciphertext, integrity) -> PutResult
getEncryptedObject(objectId) -> Ciphertext
deleteEncryptedObject(objectId) -> DeleteResult
```

It transports authenticated ciphertext and opaque metadata only. Key management, plaintext merge, events, tombstones, and permissions remain client-side.

The reference transport object is `org.foe.encrypted-sync-envelope/v1` from ADR 0006. A client seals the complete verified sync-frame bytes before calling `putEncryptedObject`; a Provider must never accept a cleartext fallback or receive the household key. `objectId` is separately random and cannot reuse a household, child, device, frame, event, tombstone, or content-hash identifier. The Android data module and public `foe-vault` Web Crypto helpers share an interoperability fixture. Envelope authentication is followed by frame verification and local merge—it does not replace either step.

The Android reference surface now includes the experimental contract and a filesystem adapter described by ADR 0008. Opaque object IDs are canonical 24-character random Base64URL values. Objects are immutable: a byte-identical retry is idempotent, while reuse of an ID for different ciphertext fails. Listing is bounded and cursor-based. A provider may expose its own account, bucket, timing, size and object-count metadata, but must not derive paths from Family State or interpret ciphertext. The filesystem adapter is conformance infrastructure, not App-level sync; Android Storage Access Framework integration, household key enrollment, device signatures, checkpoints and orchestration remain required before it can be offered to families.

### NotificationProvider

Carries a minimal public or opaque notification trigger. It cannot include child interests or plaintext family context. Notifications are opt-in, rare, and focused on user-subscribed time-sensitive events rather than daily engagement.

## 3. Policy contract

Policies return explicit decisions and reasons rather than hiding educational values in Core.

```text
AgePolicy.evaluate(actor, subject, lifecycleStage, action)
  -> Allow | Deny | RequireJointDecision | RequireSubjectControl

OpportunityPolicy.evaluate(candidate, goals, constraints, evidenceSummary)
  -> Allow | Reject | Defer | NeedsVerification | NeedsFamilyChoice
```

Official policies include age/authority, opportunity, safety, privacy, intervention, retention, and Graduation behavior. Forks may replace them and must identify divergence from Jianyu defaults.

Policies cannot call networks, read secrets, rewrite evidence, assign child worth, or collapse Child/Caregiver/Shared goals. Decisions include policy ID/version and reason codes.

## 4. Pack contract

A Pack is declarative content designed to be editable by people who are not software developers. JSON and YAML serializations map to one public schema.

Packs may contain:

- interest and motivation entry points;
- opportunity templates across multiple ecosystems;
- requirements, reality-fidelity notes, possible misconceptions, and safety metadata;
- knowledge connections expressed as optional cognitive anchors;
- localization, source attribution, licensing, sponsorship, and freshness metadata;
- declarative matching hints and Gate inputs.

Packs do not contain arbitrary executable code in v0.1. Dynamic online behavior belongs in a Provider. This keeps Pack contribution accessible without reducing the capability of World Brief services.

Every opportunity intended for automatic matching must declare non-empty, specific `triggerTerms`. The reference client matches those terms only against the family's current explicit interest and fails closed when terms are absent, blank, or too short. Historical evidence does not silently activate a Pack. Avoid ambiguous vocabulary such as “speed” that can cross unrelated topics; a Pack supplements discovery and must not behave like generic advice or override AI judgment.

Opportunity `ecosystem` values are stable machine IDs, not localized display text. Use the recommended IDs in `DATA-SCHEMA.md` (for example `making`, `people`, or `world-event`). Providers and Packs must not invent translated variants solely for display. The reference engine canonicalizes known legacy aliases before diversity selection and treats unknown IDs as `other`, so spelling variations cannot fake a multi-ecosystem result. Brand/UI layers own the family-facing label.

The manifest declares publisher, version, schema compatibility, signature/digest, license, sources, external links, sponsorship, requested declarative features, and content category. Trust tiers are `official`, `verified`, `community`, and `local`; tier affects defaults, not whether a source is presented as infallible.

High-risk health, mental-health, sexuality, finance, politics, or dangerous-experiment content cannot enter default child-facing recommendations merely because it is community-signed.

## 5. BrandConfig

BrandConfig replaces product name, tagline, description, localized copy, theme, asset references, support/security/privacy links, default feature flags, default Policies, and default Provider registrations.

It cannot hide provenance, change event meaning, weaken mandatory encryption, or suppress safety/privacy disclosure. A fork should never need global search-and-replace to remove Jianyu branding.

The v1 identity fields have distinct jobs: `displayNameZhCN` is used on identity surfaces, `taglineZhCN` is the compact product promise, `heroZhCN` is the onboarding headline, and `missionZhCN` is the public mission. Clients must not alias these fields. Brand names belong in the app bar, onboarding, and About surface; ordinary task, safety, privacy, deletion, and recovery copy should name the actor or mechanism directly and remain valid after a fork changes the brand.

## 6. Capability lifecycle

```text
discover with zero authority
→ inspect identity, compatibility, destinations, categories, and terms
→ install/configure
→ request narrow purpose-scoped capability
→ Policy evaluates lifecycle authority and consent
→ Context Firewall minimizes context
→ execute and record non-sensitive receipt
→ expire or revoke
```

Extensions tolerate denial, offline operation, timeout, cancellation, provider replacement, and removal. Core records never become inaccessible because an extension disappears or a subscription ends.

## 7. World Brief versus Pack

| Capability | WorldBriefProvider | Pack |
|---|---|---|
| Dynamic and continuously updated | Yes | Versioned snapshots/updates |
| Server-side code, search, AI, databases | Yes, implementation-defined | No arbitrary code |
| Runs while Jianyu is closed | Third-party service may | No |
| Public event freshness verification | Primary role | Carries declared freshness |
| Editable by non-programmers | Service-dependent | Primary goal |
| Needs child/family context | No by default | Installation grants none |
| Local/offline use | Cached results | Yes |

## 8. Compatibility and tests

Extensions declare semantic contract ranges; data uses independent schema versions. Required shared tests cover:

- manifest, signature, trust tier, and incompatible versions;
- zero-capability default, denial, expiry, and revocation;
- declared network destinations and data categories;
- timeout, cancellation, retry, malformed response, and offline behavior;
- no raw secrets or family content in logs;
- stale, sponsored, deceptive, prompt-injected, oversized, and corrupt content;
- Context Firewall redaction;
- export/import with the extension absent;
- lifecycle authority, goal provenance, Nothing, and deletion behavior.
