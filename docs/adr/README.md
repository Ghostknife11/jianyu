# Architecture Decision Records

Use ADRs for decisions that constrain Stable Core, security, privacy, compatibility, or long-term implementation choices.

Name records `NNNN-short-title.md`. Each record should contain: status, context, decision, alternatives, security/privacy consequences, compatibility/migration consequences, and rollback or exit plan.

The first expected ADRs cover runtime/client framework, local database, event serialization, cryptographic suite and key hierarchy, sync bundle/checkpoint protocol, and extension isolation.

- [0005 Sync frame, merge, and tombstone semantics v1](0005-sync-frame-merge-and-tombstones-v1.md)
- [0006 Client-encrypted sync envelope v1](0006-client-encrypted-sync-envelope-v1.md)
- [0007 Self-controlled Graduation archive v1](0007-self-controlled-graduation-archive-v1.md)
- [0008 Opaque SyncProvider and folder transport](0008-opaque-sync-provider-and-folder-transport.md)
- [0009 Android manual encrypted-folder preview](0009-android-manual-encrypted-folder-preview.md)
- [0010 Graduation retention and subject deletion](0010-graduation-retention-and-subject-deletion.md)
- [0011 Exact birthday lifecycle boundaries](0011-exact-birthday-lifecycle-boundaries.md)
- [0012 Shared timeline privacy projection](0012-shared-timeline-privacy-projection.md)
- [0013 Optional public synthetic AI capability probe](0013-public-synthetic-ai-capability-probe.md)
- [0014 Fail-closed startup for unreadable or pending Family Vault](0014-unreadable-and-pending-vault-startup.md)
- [0015 Serialize Android Family Vault operations](0015-android-vault-operation-serialization.md)
- [0016 Exact public World query approval and conservative refusal matching](0016-public-world-query-and-refusal-boundary.md)
- [0017 Exact AI disclosure and refusal-only discovery boundary](0017-exact-ai-disclosure-and-refusal-only-boundary.md)
- [0018 Bind candidate provenance and reject unsourced AI world events](0018-bind-source-provenance-and-ai-world-events.md)
- [0019 Filter clear duplicate doors before presenting a diverse set](0019-clear-duplicate-door-filter.md)
- [0020 Reject explicit daily assignments outside model self-report](0020-high-confidence-daily-task-gate.md)
- [0021 Device signing identity is not device enrollment](0021-device-signing-identity-before-enrollment.md)
- [0022 Signed device-join evidence does not grant access](0022-device-join-evidence-without-automatic-admission.md)
- [0023 Reject daily assignments anywhere in candidate copy](0023-daily-assignment-across-candidate-copy.md)
- [0024 Require a child-originated current clue before discovery](0024-current-clue-provenance-before-discovery.md)
- [0025 Balance goals in the reference diversity selector](0025-goal-balance-in-reference-selector.md)
- [0026 Delete a saved choice with linked event tombstones](0026-choice-deletion-and-linked-event-tombstones.md)
- [0027 Zero-time opportunity Gate](0027-zero-time-opportunity-gate.md)
