# Architecture

> The official reference client is the native Kotlin/Compose project in `apps/jianyu-android`. The browser project in `apps/jianyu-web-prototype` is an interaction prototype only. See `docs/adr/0002-native-android-reference-app.md`.

## 1. Product before infrastructure

Family Opportunity Engine exists to help a family notice a small number of worthwhile entry points that it might otherwise miss. Privacy, storage, and extensibility protect that capability; they are not the product's center.

The canonical pipeline is:

```text
Context
  Child: current interests, expressions, choices, objections
  School: current and near-future formal learning
  Life: time, cost, location, caregiver energy, plans, family skills
  World: current public events, media, places, activities, resources
                         ↓
                      Discovery
                         ↓
               Candidate Opportunities
                         ↓
                  Opportunity Gate
                         ↓
                  Diversity Selector
                         ↓
               Options including Nothing
                         ↓
                    Family Choice
                         ↓
             Optional lightweight feedback
                         ↓
                 Longitudinal Record
```

The system begins with a child's present motivation. Curriculum connections may enrich an opportunity but cannot manufacture child pull. An opportunity must still be worthwhile if no educational interpretation is extracted.

## 2. FOE and Jianyu

**Family Opportunity Engine (FOE)** is the open engine, protocol, schemas, and SDK. **Jianyu** is the official reference application.

```text
FOE public packages and contracts
              ↑
              │ uses only public APIs
              │
        Jianyu reference app

Third-party apps, services, school tools, NAS packages, research tools,
and hardware use the same contracts.
```

The reference app must not depend on private fields, private services, secret prompts, or special extension privileges. If Jianyu needs a capability, that capability must be available through the public FOE interface.

## 3. Stable Core and Evolvable Edge

### Stable Core

- Person, Child/Subject, Household, Membership, Device, and authorship identity;
- ownership, visibility, consent, assent/objection, and revocation;
- versioned Event and Opportunity schemas with provenance;
- Family Vault encryption, recovery, export, deletion, and key lifecycle;
- lifecycle authority and Graduation semantics;
- capability boundaries and compatibility negotiation.

### Evolvable Edge

- LLM, Search, World Brief, Sync, Storage, and Notification Providers;
- Age, Opportunity, Privacy, Safety, Intervention, and Graduation Policies;
- discovery, ranking, diversity, and model recommendations;
- declarative Packs;
- UI, language, theme, BrandConfig, and feature configuration.

Core defines mechanisms. Replaceable Policies express opinions. Concrete providers never become Core dependencies.

## 4. Opportunity subsystem

### Context assembler

Builds four separate context streams and preserves their provenance. It never flattens child, caregiver, and shared goals into one “family objective.” Missing and contradictory context are valid states.

### Discovery

Produces a candidate pool from multiple ecosystems rather than one content catalog:

- local Packs and saved sources;
- SearchProvider results;
- WorldBriefProvider feeds;
- family members' skills and upcoming plans;
- existing games, media, books, sports, places, tools, and real-life tasks;
- optional LLM expansion using minimized context.

Discovery optimizes breadth and possibility. It cannot publish directly to the family.

### Opportunity Gate

The Gate is at least as important as Discovery. It evaluates:

1. safety and age suitability;
2. family time, cost, travel, materials, access, and caregiver energy;
3. whether the entry point follows real child pull;
4. whether caregiver goals are displacing the child's goal;
5. privacy and intrusion;
6. source quality, freshness, and factual uncertainty;
7. misconception and reality-fidelity risk;
8. repetition and narrowing feedback loops;
9. recent intervention load and need for unstructured time;
10. family conflict or incompatible constraints.

Gate output is `allow`, `reject`, `defer`, `needs-verification`, or `needs-family-choice`, always with versioned reason codes. `Nothing` does not need to “win” against a candidate; it is always an available family choice and may be the Gate's only output.

The reference App currently knows how many entries were selected in the previous seven days, not how many activities actually happened. Policy v0.2.10 therefore surfaces a restraint warning after several selections but does not reject a fresh child-led request solely on that count. A future intervention-load policy needs evidence of actual participation and its own versioned rule.

The reference Gate also rejects an ordinary opportunity when the occasion has no available time, or the candidate calls itself a zero-minute activity. `Nothing` remains separately available without turning the pause into a negative record. This is a deterministic declared-constraint boundary, not proof that a Provider's positive duration estimate is honest (ADR 0027).

### Diversity Selector

Chooses the smallest useful set of genuinely different routes. It does not fill quotas by inventing weak options. Two good options plus Nothing are better than five repetitive suggestions. When appropriate, one adjacent or surprising route can reduce interest lock-in.

The reference selector only considers candidates that pass the local Gate, then takes turns across available source kinds while retaining at most one route per ecosystem. A clear title re-label with the same original-source URL does not consume an ecosystem slot; the selector tries the next feasible candidate instead. Differently sourced public records may retain the same generic title. Caregiver-primary routes are deferred until a child- or shared-primary route is selected, and may never outnumber those routes in the displayed set; caregiver-only results can therefore leave Nothing as the sole choice. Android retains allowed-but-unselected evaluations separately from Gate rejections, so the result page can explain that narrow case without relabeling it as a Policy refusal. A candidate's provider-reported `score` or `confidence` does not rank the family-facing doors or entitle a source to fill the set. These are bounded diversity safeguards, not semantic proof that every surviving entry is genuinely different or useful; model quality still needs synthetic benchmarks and human review (ADRs 0019 and 0025).

The invoked Pack/Provider category, not a candidate's self-declared source-kind field, determines provenance for local Gate, diversity, and UI treatment. Android Policy v0.2.10 also rejects an AI-generated `world-event` without a checkable original-source URL. The dynamic World Brief path remains separate and keeps its own freshness and attribution rules (ADR 0018). The reference Gate rejects narrow explicit daily-assignment clauses in a candidate's title, explanation, or `whyNow` even when the model self-reports child pull and low pressure; this is not a general intent detector or a ban on voluntary study (ADRs 0020 and 0023).

### Opportunity presentation

Each option explains:

- the entry point and why it may work now;
- which goal it primarily serves: child, shared, or caregiver;
- medium/ecosystem;
- time, cost, travel, preparation, caregiver energy, and participants;
- source, freshness, verification state, and uncertainty;
- possible future knowledge connections, shown softly and never as required outcomes;
- what the family needs to verify before acting.

AI output remains a proposal. No automatic enrollment, purchase, external contact, transportation decision, diagnosis, or child label is permitted.

## 5. Evidence, hypotheses, and observer plurality

The system never claims to know a child's inner state. It preserves:

```text
authored expression / direct choice / observed event / caregiver interpretation
                             ↓
                    time-bound evidence
                             ↓
             optional hypothesis + confidence + decay
                             ↓
                  low-risk opportunity proposal
                             ↓
                   real response and correction
                             ↓
          optional previewed context for a later request
```

Father, mother, child, guardian, and importer records remain separately authored and may contradict one another. A hypothesis never overwrites evidence. The child can say “you got me wrong,” and old interests naturally decay rather than becoming identity.

A caregiver selecting an opportunity proves only that the family selected it. It does not prove child interest. Only an explicit child veto or a completed, constrained follow-up outcome may re-enter a later recommendation request, and only after the family sees and approves the exact minimized summary for that call.

## 6. Age and authority

| Stage | Default product relationship |
|---|---|
| 4–6 Co-play | Caregiver leads shared sensory, daily-life, and play experiences. Inference is weak and developmental scoring is absent. |
| 7–9 Accompany | Caregiver leads; child can express, reject, correct, and choose “do not save.” |
| 10–12 Co-select | Child and caregiver compare meaningful options and keep their goals distinct. |
| 13–15 Hand over | Authority flips toward the teenager. Child-owned/private/recommendation-only context and granular sharing become normal. |
| 16+ Graduation | Stop new caregiver-side child modeling. Export, read-only retention, deletion, or a freshly authorized transfer controlled by the person. |

The exact birthday determines the stage and its safety/authority bounds in the current Android app (ADR 0011); a February 29 birth date advances completed age and stage on March 1 in non-leap years, not February 28. Age is not a score of capability. A date change never supplies consent on the person's behalf: disclosure, saving, child confirmation, and Graduation choices still require their own explicit action. A dedicated stage-transition review is not implemented yet and must not be claimed as present. Gender is omitted unless it materially changes the specific context and is authorized.

## 7. Family Vault

The local Family Vault is authoritative and consists of an encrypted event log, encrypted attachments, and disposable derived projections. Events retain authorship and version. Projections can be rebuilt. Provider credentials remain in device-secure storage and are not Family Vault events by default.

In the Android reference App, ADR 0015 serializes one `MainViewModel` instance's Vault operations across the latest-state read, local write, and UI projection. AI discovery and the manual encrypted-folder preview currently hold that gate while their external work runs, trading responsiveness for protection against stale local snapshots. This is not a cross-process transaction or a multi-device sync guarantee; future background writers must join a shared transactional boundary.

The vault supports multiple Persons, Children/Subjects, Households, guardianship relationships, and devices. This avoids hard-coding one father, one mother, one child, and supports siblings, grandparents, separated households, and changing care arrangements.

## 8. Context Firewall

Only the Context Firewall can produce family-derived outbound context. For every provider call it evaluates purpose, actor, subject, lifecycle authority, consent, requested data categories, provider capability, and expiry. It then:

- allow-lists fields;
- removes direct identifiers;
- coarsens age, location, and timing;
- separates public World data from private Family data;
- provides a human-readable disclosure summary where appropriate;
- creates a minimal receipt without raw prompts or secrets;
- validates and labels the response.

Provider adapters receive a short-lived `TaskContext`, never a vault handle, generalized query API, or decryption key.

The Android reference App requires explicit per-call AI disclosure; the JavaScript reference Core now requires the exact approved projected AI payload as a separate value. Search and World Brief require an exact approved public query. Capabilities alone do not prove consent to these values. If the current input is an explicit refusal with no separate positive child clue, a plainly caregiver-led plan, or obvious external background alone, Android preflight avoids external calls and Policy v0.2.10 rejects any candidate that nevertheless asserts child pull; the JavaScript reference flow likewise skips external discovery. A separate child-originated clause remains eligible, and a short interest term remains usable. A caregiver's own refusal is not silently re-attributed as a child's veto. This narrow lexical boundary does not turn free text into verified child intent (ADR 0024).

The locally computed number of options selected in the past seven days is not a count of activities completed or a measure of the child's interest. Local Policy may use it to show a gentle rest reminder, but it must not veto a fresh child-led request. The Context Firewall omits this family-history count from provider context by default; it can be included only with the per-call recent-history opt-in and must appear in that call's disclosure receipt.

## 9. World Brief and Pack are separate

`WorldBriefProvider` is a dynamic service interface for public, time-sensitive world information. Third parties may run search engines, crawlers, databases, AI agents, validation pipelines, and notifications behind that interface. The normal protocol sends public query constraints such as region and time window, not child context.

`Pack` is a declarative JSON/YAML-compatible package for editable, distributable knowledge and opportunity mappings. A Pack may be downloaded from a World Brief service, but it does not replace the service. See `WORLD-BRIEF.md`.

## 10. Providers

The initial public Provider surface is deliberately small:

- `LLMProvider`
- `SearchProvider`
- `WorldBriefProvider`
- `SyncProvider`
- `NotificationProvider`

Additional abstraction follows the Rule of Two: introduce it after a second real implementation demonstrates the need.

BYOK setup includes an upstream-maintained synthetic model compatibility manifest in v0.1. The Android reference App also offers an optional, explicitly requested single public-sample capability probe (ADR 0013); it contains no family data, reuses the formal local Gate and Diversity Selector after parsing one response, and does not grade model quality. Broader reproducible public benchmarks and model recommendations remain future work. Model philosophy cannot be trusted to prompts alone; the Opportunity Gate enforces product boundaries outside the model.

## 11. Synchronization and deployment

Local writes append immediately and work offline. Synchronization is automatic, idempotent, and eventually consistent.

```text
event / attachment
      ↓ local serialization
client-side authenticated encryption
      ↓ opaque object
LAN / folder / NAS / WebDAV / S3 / third-party host
      ↓
authorized device decrypts and merges locally
```

The reference Android data module proves the middle boundary with `org.foe.encrypted-sync-envelope/v1`: a complete verified sync frame is sealed with AES-256-GCM under an externally supplied high-entropy household sync key, and only the opaque key label, nonce, cipher suite, and ciphertext remain visible. The envelope exposes no household, child, device, frame, hash, or tombstone identifiers. An experimental public `SyncProvider` contract and filesystem reference adapter prove immutable random-object transport, bounded cursor pagination, idempotent retry and fail-closed replacement. The Android App now has a manually triggered document-folder preview wired through that coordinator, with client encryption before transport. This is not automatic or production multi-device synchronization: safe household key enrollment, per-device signatures, checkpoints, revocation, background orchestration and WebDAV/S3 adapters remain outside the implemented slice.

ADR 0021 adds an Android-local P-256 signing identity primitive as groundwork for enrollment. It does not change the sync envelope or give any device household authority: the App neither distributes the public key through sync nor treats a valid proof-of-possession signature as family approval. Device admission, key transfer, signed frames, and revocation still require a reviewed protocol and user-facing authorization.

ADR 0022 defines portable, signed invitation/request/approval **evidence** for an intended join session; [the protocol draft](docs/DEVICE-JOIN-PROTOCOL.md) makes its canonical bytes available to other clients. Validation establishes internal transcript consistency only. There is still no trusted roster, consumed-invitation ledger, person-authenticated approval, household-key transfer, or sync capability granted by these records.

Deployment choices are honest trade-offs:

- **Local only:** one authoritative device; sharing requires the same device or manual encrypted bundle transfer.
- **LAN:** separate devices synchronize when they meet on the same network; not timely while apart.
- **Family-controlled NAS/WebDAV/S3:** recommended for families wanting ownership plus remote multi-device sync.
- **Community/commercial hosted:** often the simplest ordinary-family experience; compatible services should still store only client-encrypted family content.

The upstream project currently operates no family service. That is an operational boundary, not a claim that servers are inherently bad. A future official service requires a separate architecture, privacy, security, and governance decision.

Sync must not become family work. The app uses background refresh, OS scheduling, optional startup permission, app-open refresh, or external sync tools when available; failure to grant background access delays sync but does not break local use.

## 12. No daily maintenance and no completeness requirement

Jianyu is occasion-driven. Most days may involve zero input and zero opens. Sparse years and uncertain observations remain valid. The product never warns that a child has not been recorded recently, never converts missing data into negative evidence, and never requires a daily server, model, certificate, conflict, or database task from a family.

## 13. Longitudinal record and assessment

The record can include voluntary school assessments, teacher feedback, interests, experiences, opportunities, refusals, autonomous follow-up, and later connections. Scores are one signal, not a definition. The product may display chronology and association but must not claim that Jianyu caused a score change without appropriate research.

Graduation export should support machine-readable JSON and human-oriented Markdown/HTML, with CSV for suitable tables. A local “what happened over these years” view emphasizes changing interests, experiences, connections, failures, and growing autonomy rather than a composite development score.

## 14. Evolution and forkability

- Events and Opportunity records use versioned public schemas.
- Unknown optional fields round-trip; unknown security semantics fail closed.
- Migrations are deterministic, idempotent, and offline-capable.
- Public conformance fixtures cover Core, Provider, Policy, Pack, export, and sync compatibility.
- Era changes occur only when the product paradigm changes; ordinary features use normal versions.
- Family data remains usable when a provider, pack, host, model, app, brand, or upstream maintainer disappears.

## 15. Dependency direction

```text
apps/jianyu-android
  -> foe-core
  -> foe-opportunity
  -> foe-vault
  -> provider-sdk / policy-sdk / pack-sdk / brand-config

foe-core -> foe-schema
foe-opportunity -> foe-schema + policy-sdk + provider-sdk
foe-vault -> foe-schema + reviewed crypto implementation

Concrete providers and policies implement public contracts; Core never imports them.
```

Runtime, UI framework, database, concrete cryptographic suite, and sync framing require ADRs. The first runnable vertical slice may use a zero-dependency reference implementation without freezing the final application stack.
