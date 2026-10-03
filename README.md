# Jianyu

> **See more possibilities from one small opening.**

**More doors. Fewer prescriptions.**

Jianyu is the reference application built on the open **Family Opportunity Engine (FOE)**. It is a local-first, BYOK family AI companion that works behind caregivers—not as another AI tutor in front of children—to help ordinary families notice meaningful opportunities across a child's existing interests, school, family life, and the changing world.

> **Use AI to narrow the gap in families' access to educational opportunities, not the differences between children.**

Jianyu lowers information, practical-knowledge, resource-discovery, and opportunity-design barriers. It does not promise equal outcomes or turn “well-rounded development” into a KPI.

[简体中文](README.zh-CN.md)

> **Developer preview — fictional data only.** The native App has not passed independent security review or live recommendation-quality evaluation and is not ready for real children's records. See [development checks](DEVELOPMENT.md) and [implementation status](docs/IMPLEMENTATION-STATUS.md).

## The idea

The product begins with what a child already wants to play, make, watch, explore, or understand—not with a curriculum objective.

```text
Child + School + Life + World context
                    ↓
               Discovery
                    ↓
          Candidate Opportunities
                    ↓
             Opportunity Gate
       safety / age / cost / time / energy
       child pull / parent pressure / privacy / freshness
                    ↓
             Diversity Selector
                    ↓
        2–5 genuinely different options + Nothing
                    ↓
               Family Choice
                    ↓
          optional lightweight feedback
                    ↓
             Longitudinal Record
```

Games, films, books, sports, making, cooking, repair, travel, museums, nature, digital creation, family knowledge, and live world events can all be valid entry points. `Nothing` is a first-class outcome. The system does not optimize daily use, completion streaks, coverage, or the number of interventions.

## Lifecycle

- **4–6 Co-play:** caregiver-led shared experience, weak inference, no developmental scoring.
- **7–9 Accompany:** caregivers lead while children can easily like, reject, correct, or decline saving.
- **10–12 Co-select:** caregivers and children choose together and keep their goals distinct.
- **13–15 Hand over:** authority flips toward the teenager, including private context and granular sharing.
- **16+ Graduation:** stop new caregiver-side child modeling; export, retain read-only, erase, or migrate only with fresh authorization from the person.

## World Brief is not a Pack

A `WorldBriefProvider` connects to a dynamic third-party service that searches, aggregates, verifies, and refreshes public information about the world without needing a family's private context. Matching that public information to a family happens locally where possible.

A `Pack` is a separate, low-barrier declarative JSON/YAML content format that educators, caregivers, and researchers can edit and distribute, including offline. See [WORLD-BRIEF.md](WORLD-BRIEF.md).

## Local-first without server dogma

The Family Vault is authoritative. All synchronized family data is encrypted on an authorized client before it reaches NAS, WebDAV, S3-compatible storage, or a third-party host. The upstream project currently operates no family server, while compatible hosted services are welcome and may offer the best experience for ordinary families.

The synchronization target is automatic and eventually consistent. Local-only, delayed LAN sync, family-controlled storage, and hosted services have different usability boundaries and must be explained honestly. The current Android code has deterministic frame/hash-chain/merge/tombstone foundations, a tested client-encrypted opaque envelope, a public ciphertext-only `SyncProvider` contract, and an immutable filesystem reference adapter. The adapter is not wired into the App; Android folder authorization, orchestration, LAN/WebDAV/S3/hosted adapters, and the household device-enrollment/signature hierarchy remain unfinished. BYOK provider credentials remain device secrets by default.

## Engine and reference app

```text
Family Opportunity Engine
├── public Core / Protocol / Schema / SDK
├── Opportunity Discovery / Gate / Diversity
├── Provider / Policy / Pack / BrandConfig extension points
└── Jianyu reference app, built only on those public interfaces
```

The reference app must never become the only practical implementation. Forks and third parties can build their own apps, services, school tools, NAS packages, research systems, or hardware without depending on a Jianyu account or private API.

## Repository

```text
apps/jianyu-android/          Native Jianyu Android app
apps/jianyu-web-prototype/    Interaction/domain prototype, not the primary app
packages/foe-core/            Stable domain core
packages/foe-opportunity/     Discovery, Gate, and Diversity
packages/foe-schema/          Event and Opportunity protocols
packages/foe-vault/           Local vault and cryptographic boundary
packages/provider-sdk/        Public Provider interfaces
packages/policy-sdk/          Public Policy interfaces
packages/pack-sdk/            Declarative Pack format
packages/brand-config/        Brand, theme, and feature configuration
docs/adr/                     Architecture decisions
examples/                     Synthetic examples
tests/                        Conformance, privacy, security, and migration tests
```

## Native Android App

The primary reference product is a native Kotlin/Compose Android app—not a WebView wrapper. The current Android slice supports an on-device encrypted Family Vault, calendar birthday capture with exact completed-year lifecycle boundaries, deterministic v2/v3/v4-to-v5 migration without inventing missing month/day data, multiple children, multiple caregiver authors with explicit current-recorder attribution, lifecycle authority from co-play through Graduation, separate child/caregiver/shared goals, practical Opportunity Gate constraints, diverse AI/local opportunity candidates, first-class Nothing, child veto, optional outcome feedback that can re-enter a later approved AI request, a chronological record, per-evidence tombstone deletion, and whole-vault cryptographic erase. The opaque current-recorder preference is remembered in an Android-Keystore-encrypted device setting and is not synced as family data. Shared-device recorder switching is attribution, not identity authentication. Android `text/plain` shares enter as a bounded, editable draft: they are neither saved nor sent to AI until the family makes the ordinary choices in the recommendation flow.

The native app also records an optional dated exam or quiz as separately sourced School evidence and a versioned assessment event. Score/maximum are required; class average, percentile, topics, and notes remain optional. It does not derive a child score, compare children, or attribute a later change to Jianyu. Hand-over-stage entry requires the teenager's confirmation, and Graduation rejects new caregiver-side assessment records.

```text
cd apps/jianyu-android
gradlew.bat test assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The Android app uses a family-configured OpenAI-compatible BYOK provider as its primary discovery path. Each call requires approval and receives a Context Firewall-minimized task view; exact birth dates stay local. The 13–15 flow defaults to ephemeral input and lets the young person choose, per occasion, whether to retain the description and whether it appears in the shared timeline. Restricted evidence, events, linked choices, and dependent hypotheses are jointly hidden there without exposing hidden counts. This is UI isolation, not child-only cryptographic privacy: household-key and recovery-bundle holders may still read saved content.

The app does not separately attach names, household/member identifiers, complete family history, or keys to AI requests. However, free-text interests, goals, school/life context, entered region, and optionally approved recent summaries may themselves contain names or precise addresses; the current client does not reliably detect and remove them. Families should review their input before approval. The World Brief client receives the entered region plus a fixed 14-day window, language, and public categories, not the child's interest or family description; the region field must not contain a precise address. The offline source is explicitly a demo, never represented as AI output.

A configurable World Brief client, portable recovery bundle, schema migration, sync-frame merge, encrypted sync envelope, opaque `SyncProvider`, Android document-tree transport preview, per-record tombstones, and a separately encrypted subject-only Graduation archive have reference implementations. New Graduation archives use v2 while the reader retains v1 compatibility. At Graduation the active household copy defaults to read-only; the person can separately clear their history while retaining a minimal family relationship or delete their complete active subject copy, with v2 tombstones preventing stale synchronized records from returning. The settings UI can manually connect a system document folder and run one client-encrypted transfer; it labels this as a developer preview, not production multi-device sync. Attachment encryption, WebDAV/S3 adapters, background scheduling, household device enrollment/signatures, signed checkpoints/compaction, child-held cryptographic key scopes, selective Graduation transfer, subject-separated key destruction, remote ciphertext garbage collection, physical-device acceptance, and external security review remain required before production use. See [ADR 0012](docs/adr/0012-shared-timeline-privacy-projection.md) and the earlier ADRs in [docs/adr](docs/adr).

The `16+` Graduation band is intentionally open-ended rather than capped at age 18. An older returning subject may still need export, read-only retention, or deletion controls, while caregiver-side child modeling remains stopped.

Start with [MANIFESTO.md](MANIFESTO.md), [MVP-PRD.md](MVP-PRD.md), [ARCHITECTURE.md](ARCHITECTURE.md), [WORLD-BRIEF.md](WORLD-BRIEF.md), the [development guide](DEVELOPMENT.md), and the [Android UI system](docs/UI-SYSTEM.md). Code and documentation are licensed under [Apache-2.0](LICENSE).
