# ADR 0013: Optional public synthetic AI capability probe

- Status: Accepted
- Date: 2026-09-21

## Context

The native Android app accepts a family's own OpenAI-compatible endpoint, model ID, and key. A saved connection proves neither that the endpoint responds nor that the model can return FOE opportunities. The v0.1 compatibility manifest contains demonstration-only synthetic entries, not measured ratings of real models. Presenting a prefilled model ID as a verified recommendation would mislead families.

## Decision

- A caregiver may explicitly run one optional probe from the AI connection card after saving a connection. It is never automatic at setup, app launch, or discovery time.
- That action issues a short-lived Provider capability restricted to the `check-public-ai-capability` purpose and the `fixed-public-synthetic-case` category. The probe validates the grant before opening a connection; it cannot authorize family-context discovery.
- The probe builds a fixed, fictional paper-airplane occasion through the same `DefaultContextFirewall`, prompt builder, chat-completions transport, and structured parser used by the reference discovery path. After one response, it replays the parsed candidates locally through the formal Opportunity Engine's Gate and Diversity Selector, without another network call. It reads no Family Vault data, names, interests, history, or local identifiers.
- The UI says before the call that the selected service receives this public synthetic prompt and may charge for one request. The check result remains transient UI state and is cleared when the connection changes; it is not a Family Event or model grade.
- Results distinguish a sample with at least one displayable entry, a valid Nothing response, an incompatible response, candidates rejected by the local Gate, Gate-eligible candidates not displayed by Diversity, and a connection failure. Nothing and a selection-empty sample are inconclusive rather than evidence of a broken connection or a suitable model. HTTP authentication and rate-limit codes receive bounded, non-sensitive guidance. Raw provider response bodies, prompts, and keys are not shown or logged.
- A passing sample proves only that one public test worked now. It is not a safety certification, a ranking, a recommendation-quality evaluation, or a guarantee of future results. Real model ratings require reproducible public synthetic benchmarks with multiple cases and provenance.
- The production chat transport does not follow redirects and accepts only HTTPS base URLs without user-info, query parameters, or fragments.

## Alternatives considered

- Treating a saved key or a provider preset as a verified model recommendation was rejected because neither exercises the FOE task.
- Running the probe automatically was rejected because it would contact a paid third party without a clear family action.
- Using real family context was rejected because capability testing needs no child data.
- Publishing A/B/C model grades from one sample was rejected because it cannot measure diversity, restraint, safety, speed, cost, or robustness.

## Security and privacy consequences

The probe transmits only fixed fictional context to the selected endpoint. The provider still sees a request and may retain or bill for it under its own terms. Keys remain device-local; redirects are not followed, and raw responses are not shown in UI errors. A malicious configured endpoint remains outside the app's trust boundary.

## Consequences

The reference app can distinguish "saved connection" from "one synthetic request passed" without sending child data. The first probe may incur provider cost and can fail for network, key, quota, model, schema, or policy reasons. It does not replace local Gate, family choice, child refusal, or a future upstream compatibility manifest based on actual measurements.

## Compatibility, migration, and rollback

No Family Vault, Event, or Provider protocol schema changes. Forks may replace the test case or model catalog while preserving no-family-data and explicit-invocation boundaries. The probe can be removed without migrating stored data.
