# Repository guidance

`D:\test\JIAOYU` is the Jianyu / Family Opportunity Engine project root.

Read `MANIFESTO.md`, `README.zh-CN.md`, `ARCHITECTURE.md`, `MVP-PRD.md`, `DATA-SCHEMA.md`, `WORLD-BRIEF.md`, `SECURITY.md`, and `PRIVACY.md` before changing behavior.

For Android UI changes, also read `docs/UI-SYSTEM.md`; use its shared page hierarchy, layout tokens, semantic tones, and family-facing vocabulary instead of adding page-local visual rules.

## Product core

- Start from a child's current desire, interest, question, or voluntary behavior; the entry point comes before curriculum.
- Preserve the canonical flow: Context → Discovery → Opportunity Gate → Diversity → options including Nothing → Family Choice → optional feedback → Longitudinal Record.
- Keep Child, School, Life, and World context streams distinct, and keep Child, Caregiver, and Shared goals distinct.
- Multi-ecosystem means games, media, books, sport, making, family life, nature, travel, places, people, and current world events—not cosmetic variants of one educational activity.
- `Nothing` is a first-class, common outcome. Do not add streaks, coverage, missed-opportunity counts, daily-use pressure, or completeness requirements.
- AI discovers, the Engine constrains, the family chooses, and the child validates. AI output is never a fact, diagnosis, child score, purchase, enrollment, or command.

## Lifecycle and data

- Preserve 4–6 co-play, 7–9 accompany, 10–12 co-select, 13–15 hand over, and 16+ Graduation.
- Preserve authorship, multiple observers, contradictory evidence, uncertainty, provenance, time decay, child correction, ownership, and visibility.
- Scores are optional evidence, not a definition; display associations without claiming causality.
- Use synthetic family data in development and tests.

## Architecture

- Family Opportunity Engine is the public Core/Protocol/SDK; Jianyu is a reference App that uses only public interfaces.
- Keep Stable Core free of concrete Provider, Policy, Pack, BrandConfig, and brand dependencies.
- `WorldBriefProvider` is a dynamic public-world service; `Pack` is a separate declarative content format. Never collapse one into the other.
- Preserve Local-first, BYOK, Family Vault, Context Firewall, automatic eventual sync, client-side encryption before sync, deletion/cryptographic erasure, export, and forkability.
- The upstream project currently operates no family server; compatible third-party hosted services are welcome.
- Add an ADR before freezing runtime, database, cryptography, sync framing, or changing a Concept Freeze principle.

## Verification

Add tests for schemas/migrations, Gate reasons, diversity, Nothing, Provider/Policy/Pack/BrandConfig contracts, World Brief without family context, Context Firewall minimization, encrypted sync, deletion/tombstones, recovery, lifecycle authority, and export round trips.
