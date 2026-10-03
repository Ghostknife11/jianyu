# ADR 0005: Sync frame, merge, and tombstone semantics v1

- Status: Accepted for reference implementation
- Date: 2026-09-14

## Context

Portable whole-vault recovery proves that family state can be encrypted independently of Android Keystore, but it is not synchronization. Multiple devices can append concurrently, a deleted record can survive on an offline device, and copying the newest-looking snapshot can silently lose authorship or resurrect erased child data.

Network adapters must not invent their own conflict rules. Folder, LAN, WebDAV, S3-compatible, and hosted transports need the same provider-neutral payload and deterministic merge behavior before any of them can be presented as supported synchronization.

## Decision

The reference protocol introduces `org.foe.sync-frame/v1` and upgrades the projected Family State to `org.jianyu.family-vault/v4`.

A frame is produced by one enrolled device and contains:

- household and device IDs;
- a strictly increasing per-device sequence;
- the previous accepted frame hash and a hash of the current canonical frame body;
- a created-at timestamp;
- versioned members, children, evidence, hypotheses, choices, events, and deletion tombstones known to that device.

The reference v1 frame may repeat known objects. Identity makes application idempotent; later versions can add delta discovery without changing merge meaning. A transport sees only an independently encrypted opaque object. Semantic IDs, frame JSON, hashes, and tombstones do not appear in filenames or plaintext transport metadata.

Merge rules are deliberately conservative:

1. Household IDs must match.
2. A frame from a retired device is rejected until explicit re-enrollment.
3. Per-device sequence must be the next value and `previousFrameHash` must match the locally accepted cursor. The exact last frame is idempotent; gaps, forks, and rollback attempts fail closed.
4. Objects with different IDs coexist. The same ID with different content is a conflict and fails closed; wall-clock last-write-wins is forbidden.
5. A tombstone dominates its target regardless of object timestamp or arrival order. Tombstones themselves are append-only and cannot be deleted by ordinary merge.
6. A subject tombstone removes the child projection and all evidence, hypotheses, choices, subject-authored membership, and subject-scoped events available on that device. Only the minimum tombstone and non-content audit data remain.
7. Imported or restored state applies tombstones before any deleted content becomes visible.
8. Compaction is not safe until authorized devices acknowledge a checkpoint. Offline exports and devices outside the protocol cannot be remotely erased.

`DeletionTombstone` contains only the target type/ID, household, author/device, deletion time, and reason code. It must not copy the deleted free text. Physical ciphertext removal and narrow-key destruction remain separate storage actions.

## Consequences

- Deletion can propagate without relying on clocks or mutable rows.
- Concurrent observations with distinct IDs survive; contradictory evidence is preserved.
- A stale or cloned device cannot silently fork an accepted sequence or reintroduce deleted content.
- Transport adapters remain replaceable and cannot define family-data semantics.
- The reference implementation can test merging before it has credentials for any real NAS or hosted service.
- A lost frame currently blocks later frames from that device until the missing frame is recovered or an explicit, separately designed checkpoint/re-enrollment flow occurs.
- Hash chaining plus encrypted transport provides continuity and corruption detection, not a production device-signature hierarchy. Authenticated enrollment, per-device signing keys, checkpoint signatures, revocation distribution, attachment framing, padding, and independent cryptographic review remain required before production sync claims.
