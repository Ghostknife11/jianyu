# ADR 0012: Fail-closed privacy projection for the shared timeline

- Status: Accepted
- Date: 2026-09-20

## Context

The Android Family Vault stores evidence, choices, hypotheses, and audit events together. The first timeline rendered those raw collections directly. That made an evidence item marked `CHILD_PRIVATE`, an event marked private, or an inference derived from private evidence eligible to appear on a caregiver/shared-device screen. Filtering only the evidence row would still leak information through a dependent hypothesis, linked choice, count, or audit event.

The current vault uses a household key and the shared-device recorder selector is attribution rather than authentication. A UI filter therefore cannot honestly provide cryptographic privacy from another household-key holder.

## Decision

- Shared/caregiver timeline screens consume `projectSharedTimeline(FamilyState)` rather than raw vault collections.
- `CHILD_PRIVATE` evidence is excluded.
- An event is shared-timeline-visible only when its normalized visibility is `guardians`, `family`, or `shared-with-child`. `private`, `child-private`, `selected-members`, `recommendation-only`, and unknown future values fail closed.
- A choice is excluded when its source event exists but is restricted, or when a restricted event names that choice in `payload.choiceId`. Missing legacy and explicit ephemeral source IDs remain readable when no restricted stored event exists.
- Hypothesis v1 has no independent visibility. It is shown only when it has at least one supporting evidence reference and every supporting or contradicting reference resolves to visible evidence. Unknown, deleted, or restricted dependencies fail closed.
- The UI may disclose only that restricted records are hidden. It does not reveal their count, subject, type, or content.
- The UI states that this is shared-screen projection, not an authenticated child-only space or subject-separated encryption.

## Alternatives considered

- Filtering only `Evidence` was rejected because hypotheses, choices, counts, and audit events can reveal the hidden record.
- Showing `selected-members` on a shared device was rejected because the current recorder selector does not authenticate the viewer.
- Deleting or moving restricted data out of the household vault was rejected as an implicit destructive migration and because subject key spaces are not implemented yet.
- Claiming child privacy based on a Compose screen was rejected because every household-key holder can still recover/export the same vault.

## Security and privacy consequences

The shared timeline no longer leaks known restricted records through its direct projections, dependent hypotheses, linked choices, counts, or audit events. Unknown visibility values fail closed. This boundary does not protect against a compromised device, another code path reading the raw vault, whole-vault recovery/export, or a household-key holder. A real child-only area still requires authenticated identity, subject-separated keys, recovery rules, and independently reviewed migration/deletion behavior.

## Compatibility and migration

No stored schema changes. Existing vaults are projected at read time. Older choices whose source event is absent remain visible unless a restricted event explicitly names the choice. Hypothesis v1 records with missing or empty evidence links no longer appear on the shared timeline; the raw record remains in the vault.

## Rollback and exit plan

The projector can be replaced by an authenticated viewer-aware authorization projection when identity and subject key scopes exist. The fail-closed tests remain compatibility requirements. Rolling back to direct raw-vault rendering is not permitted.
