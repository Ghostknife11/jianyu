# ADR 0017: Exact AI disclosure and refusal-only discovery boundary

- Status: Accepted for reference clients
- Date: 2026-10-01

2026-10-02 clarification: a non-refusal clause is not automatically a positive clue. After an explicit refusal, a neutral circumstance or caregiver-led plan does not authorize new discovery; the family can rephrase an actual child-led interest, question, or attempt.

## Context

The public JavaScript reference flow could invoke an LLM from a purpose-only context. It omitted the current interest that should guide AI recommendations, while a broad blacklist and a capability did not prove the family approved the particular outbound values. Its Search path also lacked the exact public-query review already required for dynamic World Brief. On Android, a pure explicit refusal could still be submitted as if it were a positive child interest, leaving a model to invent a route from a negative statement.

## Decision

- JavaScript `createTaskContext` is an allowlisted projection. The client chooses approved categories and reviews the resulting bounded values for this call. `runOpportunityFlow` recomputes that projection and requires an exact match with `approvedLlmContext` before invoking the LLM. Its short-lived capability must cover the minimized context and each approved category. No approval, missing current-interest permission, or changed values means no model call.
- JavaScript Search requires the same exact approved public query as World Brief. Its returned pre-mapped candidates need public topics and are matched to current interest locally, including the explicit-refusal guard.
- A pure explicit refusal remains a valid child view but is not positive pull for a new recommendation. Android stops discovery before a Provider call or new observation write; Policy v0.2.5 also rejects any candidate that claims child pull over such an input. JavaScript skips its LLM call. A neutral extra clause such as weather does not convert a refusal into child pull; after a refusal, a separate clause must explicitly express interest, a question, or an attempt to permit discovery. Caregiver-led wishes do not qualify. Nothing/留白 remains a valid choice.
- These are conservative clause/cue rules, not general natural-language understanding or proof that a child genuinely wants an activity.

## Alternatives

Sending the whole request or Vault snapshot to a Provider was rejected. Treating a category capability as consent to unknown actual values was rejected. Asking an LLM to classify refusal first would create another external disclosure and cost; this v0.1 boundary uses a narrow local guard. Replacing AI with static templates was rejected: AI remains the formal BYOK recommendation source.

## Security, privacy, and compatibility

An allowlist excludes separate identifiers, raw history, and secret fields, but cannot reliably remove a name or address manually typed into an approved free-text field. The client must show what will be sent. JavaScript callers must now supply `approvedLlmCategories` and an exact `approvedLlmContext`; older callers without them will see a skipped LLM source, not an implicit grant. Search clients must supply `approvedWorldQuery`. Refusal-only inputs can be retained through an appropriate separately authorized record/feedback path; this discovery action does not write them as a new positive-interest observation.

## Rollback and exit

If the lexical guard suppresses a legitimate mixed expression, the family may rephrase the child's positive clause or a future versioned Policy may provide a reviewed override. Do not roll back exact-value consent, send private interests to public services, or let a model's `childPull` assertion bypass the local Gate. Cross-client conformance fixtures and richer consent UX should precede broadening the rule.
