# ADR 0003: BYOK AI is the primary discovery path

- Status: Accepted
- Date: 2026-09-13

## Context

Jianyu is an AI-assisted Family Opportunity Engine, not a fixed activity-template catalog. Offline templates are useful for tests and demonstrations, but presenting them as the product's recommendation intelligence would contradict the Concept Freeze. At the same time, sending a complete Family Vault to an external model would contradict the privacy model.

## Decision

The native Android reference app treats a family-configured OpenAI-compatible BYOK provider as the primary opportunity-discovery source. The offline source remains available only through an explicitly labeled demo mode and its outputs identify themselves as templates, never as AI recommendations.

Before each network discovery:

1. the family sees and approves the disclosure for that request;
2. `DefaultContextFirewall` constructs a purpose-scoped `TaskContext`;
3. the Engine issues a 60-second bearer capability bound to one Provider ID, kind, purpose, and explicit data categories;
4. names, household/member/child IDs, exact location, full history, and all secrets are excluded;
5. the provider rejects a mismatched, under-scoped, or expired capability and returns structured candidate data, not decisions;
6. the client treats provider content as untrusted and applies schema parsing, deterministic Opportunity Policy, Gate reasons, diversity selection, and first-class Nothing locally.

The API key and endpoint configuration are encrypted separately with Android Keystore. They are device-local configuration and are never written into Family Vault events, exports, prompts, or future sync objects. The initial adapter accepts HTTPS endpoints only.

The Android reference app sends no historical evidence by default. For a single request, the family may separately opt into a visible projection of at most five recent records. The deterministic local projector accepts only recent child statements, child choices, direct observations that are not child-private, explicit child vetoes, and completed follow-up outcomes (`喜欢`, `一般`, or `不合适`). A family merely selecting an opportunity is not treated as proof of child preference. The projector excludes AI inferences, assessments, teacher feedback, imported claims, unknown free-form outcome values, records older than 180 days, other children, and malformed timestamps. It removes common URLs, email addresses, and long phone-like numbers before showing the exact outbound preview. The provider capability includes `recent-evidence-summaries` only when that list is non-empty. This is context for the occasion, never a fixed child profile. Provider prompts must treat these summaries as untrusted quoted data, never as instructions.

The OpenAI-compatible adapter also does not embed third-party World Brief text in its prompt. Public-world records are converted into candidates by a separate local source, which prevents untrusted feed titles or summaries from becoming model instructions.

## Consequences

- A configured network provider can receive the specific interest and context fields a family approves for the current discovery purpose.
- The family can let AI notice short-term continuity without granting access to the vault or complete history; free text can still contain identifiers the deterministic redactor cannot recognize, so the visible preview and per-call choice remain material controls.
- BYOK is a separation and choice mechanism, not a promise that the external provider is private; the provider's own terms still apply.
- Provider output remains an `idea` until a separate Search/World Brief source provides verifiable real-world evidence.
- `WorldBriefProvider` continues to receive public queries only and remains separate from LLM Provider and declarative Pack.
- Future local-model adapters can implement the same public interface without weakening the network adapter's HTTPS rule.
