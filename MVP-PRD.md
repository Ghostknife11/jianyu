# MVP Product Requirements

## 1. Question the MVP must answer

Can Jianyu give an ordinary family a small set of opportunities that are more surprising, useful, feasible, and responsive to real child pull than asking a generic chatbot for activity ideas?

The MVP is successful only if it validates this product difference. Encryption, schemas, and extensibility are necessary release foundations, but they are not substitutes for recommendation quality.

## 2. Primary journey

1. A caregiver creates a local encrypted Family Vault without an upstream account.
2. In at most about 30 seconds, the family supplies an age band and selects recent self-initiated behaviors—not a personality questionnaire.
3. The caregiver describes a real occasion: a new interest, a free weekend, a trip, something the child asked, or a near-future school topic.
4. The family adds practical context: time, cost, travel range, caregiver energy, materials, screen/outdoor preference, and optional school window.
5. Discovery searches multiple ecosystems through local Packs, SearchProvider, WorldBriefProvider, and optional BYOK LLM expansion.
6. Opportunity Gate rejects unsafe, stale, infeasible, intrusive, overly parent-driven, repetitive, or forced-education candidates.
7. Diversity Selector returns two to five genuinely different options plus `Nothing`, with reasons and uncertainty.
8. The family chooses, ignores, edits, or rejects. The child may veto or correct according to lifecycle stage.
9. Feedback is optional and tiny: the reference UI uses `喜欢 / 一般 / 不合适`. Merely selecting an option is not evidence that the child liked it; an explicit veto is valid negative evidence.
10. On a later request, the family may preview and approve a small local projection of recent firsthand evidence, vetoes, and completed outcomes. It is never sent by default and never becomes a fixed child profile.
11. Events remain local, exportable, deletable, and optionally client-encrypted for sync.

## 3. First-class product concepts

### Four context streams

- **Child:** stated interest, natural behavior, direct choice, objection, and recent change.
- **School:** current and near-future formal topics, assessments, and teacher feedback; optional.
- **Life:** family plans, time, cost, energy, access, members' skills, location radius, and constraints.
- **World:** fresh public events, media, places, resources, weather-sensitive activities, exhibitions, sport, astronomy, and technology.

### Three goals

Child Goal, Caregiver Goal, and Shared Goal are separate. Every option declares which one it mainly serves. A candidate set dominated by caregiver goals must be rebalanced or rejected.

### Multiple ecosystems

At least four distinct ecosystems must be available in the demo data and provider results. The selector must not return several cosmetic variations of the same activity.

### Nothing

`Nothing` is always present and may be the only recommendation. Choosing it creates no negative score, warning, streak break, or missed-opportunity count.

## 4. In scope for v0.1

- synthetic single-household journey with multiple authored members;
- 4–6, 7–9, 10–12, 13–15 lifecycle policy and a demonstrable Graduation transition;
- behavior-based cold start and occasion-driven request;
- parent time, cost, energy, travel, and access constraints;
- Opportunity Schema with provenance, verification state, requirements, goal ownership, reality fidelity, risk, and explanation;
- deterministic Opportunity Gate and Diversity Selector;
- local declarative Pack reader with one synthetic multi-ecosystem Pack;
- `WorldBriefProvider` mock/reference adapter with fresh public-event fixtures;
- `SearchProvider` and `LLMProvider` interfaces;
- BYOK configuration boundary plus an upstream-maintained synthetic model compatibility manifest;
- Context Firewall producing a minimized `TaskContext`;
- lightweight family decision and child feedback;
- versioned append-only Events and rebuildable projections;
- local export/import and encrypted family bundle proof;
- Provider, Policy, Pack, and BrandConfig conformance tests;
- reference Jianyu flow using only public FOE APIs.

## 5. Explicitly not in v0.1

- official hosted family service;
- production WebDAV/S3/NAS matrix before the local encrypted-bundle protocol is proven;
- automatic booking, purchasing, messaging, or enrollment;
- daily feed, streak, coverage meter, missed-opportunity counter, or engagement optimization;
- passive browser, game, audio, location, camera, or screen surveillance;
- personality, intelligence, obedience, potential, development, or cross-child scores;
- automatic clinical, psychological, educational, or special-needs diagnosis;
- school administration, social feed, advertising, lead sales, sponsored ranking, or marketplace payments;
- causal claims about grades or development;
- arbitrary executable Pack code or a v0.1 plugin virtual machine.

## 6. Provider behavior

### Model recommendation

The intended v0.1 model recommendation is a manually maintained compatibility manifest based on a public, synthetic benchmark. That benchmark must evaluate FOE task suitability: structured output, constraint following, option diversity, acceptance of Nothing, refusal to label children, source discipline, cost, speed, and context capability.

The current `models/compatibility.demo.json` is demonstration data only; it does not rate any real model. The Android App can run one optional public fictional sample against a family-selected BYOK connection to check response structure and whether the formal local Gate and Diversity Selector leave a displayable entry, without reading family data. A parseable caregiver-only sample must not be reported as a passed opportunity sample merely because its candidates passed the Gate. Passing one sample is not yet the broader benchmark or a model recommendation (ADR 0013). The versioned public multi-case corpus and a non-grading machine screener now live in `models/benchmark/`; no real-provider multi-run or human-review evidence has been published yet.

The UI may label models `recommended`, `compatible`, `limited`, or `unverified`; this is not an intelligence score. Later automatic capability tests use no family data.

### World Brief

The mock/reference WorldBriefProvider demonstrates dynamic public information with source, retrieval time, event date, location scope, and `verified / likely / idea` status. It receives no child context.

## 7. Functional acceptance criteria

| ID | Criterion |
|---|---|
| F-01 | Core journey works offline with synthetic local data and no upstream account. |
| F-02 | Every durable change validates as a versioned event with author and provenance. |
| F-03 | Discovery accepts candidates from at least Pack, WorldBriefProvider fixture, and LLM/Search mocks. |
| F-04 | Gate emits machine-readable reasons for every rejection, deferment, or warning. |
| F-05 | A candidate that exceeds time, cost, travel, energy, age, or safety constraints cannot silently pass. |
| F-06 | Child, caregiver, and shared goals remain distinguishable through generation and presentation. |
| F-07 | Diversity tests reject near-duplicate ecosystems, allow fewer than five options, and prevent one source's self-reported scores from crowding out other feasible sources. |
| F-08 | Nothing is always available and never produces a penalty. |
| F-09 | Provider calls are impossible without a purpose-scoped capability and minimized TaskContext. |
| F-10 | WorldBriefProvider can operate without Family Vault access or child context. |
| F-11 | The child can reject/correct evidence; hypotheses retain uncertainty, time, and decay. |
| F-12 | Assessment data is optional, separately sourced, and never converted to a child score. |
| F-13 | Export/import preserves authorship, visibility, unknown fields, and deletion tombstones. |
| F-14 | Encrypted bundle contents expose no family plaintext or semantic filenames. |
| F-15 | Jianyu uses the same public interfaces as the conformance examples. |
| F-16 | A prior family selection cannot silently become child preference; only explicit child veto or completed follow-up can influence a later AI request, and outbound summaries are previewed per call. |

## 8. Evaluation

The first family study uses synthetic demos first, then a separately reviewed real-family protocol. It measures:

- **Surprise Rate:** at least one option the caregiver did not already think of;
- **Adoption Rate:** whether the family voluntarily chose an option;
- **Child Pull:** whether the child wanted to begin or continue;
- **Feasibility:** whether cost, time, energy, access, and preparation matched reality;
- **Diversity:** whether options represented meaningfully different routes;
- **Restraint:** whether Nothing and rejection appeared when appropriate;
- **Comprehension:** whether the family correctly understood sources, AI uncertainty, and outbound data.

Do not optimize DAU, time in app, number of stored observations, number of activities, curriculum coverage, or a composite development index.

## 9. Release gates

1. A five-minute demo makes the difference from generic chat visibly clear.
2. Schema, migration, Gate, diversity, Provider, Policy, Pack, Context Firewall, export, deletion, and encrypted-bundle tests pass.
3. Pack and provider fixtures cover stale, malformed, sponsored, unsafe, tracking, and prompt-injection content.
4. Independent review covers key management and privacy boundaries before real family data.
5. The app can operate for quiet weeks without warnings or completeness pressure.
6. Graduation preserves export, correction, deletion, and fresh authorization.
7. Public APIs are sufficient to reproduce the reference flow in a third-party example.
