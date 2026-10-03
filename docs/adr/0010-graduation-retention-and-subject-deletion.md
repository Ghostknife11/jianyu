# ADR 0010: Graduation retention and subject deletion

- Status: Accepted for experimental reference implementation
- Date: 2026-09-15

## Context

At 16+, Jianyu already stops new caregiver-side child modeling and offers a separately encrypted subject-only export. Export alone does not give the person control over the household copy. The product promise also requires a quiet read-only default, optional minimal household retention, and deletion that cannot be undone by a stale synchronized device.

These actions must remain separate. Reaching an age boundary is not consent to export, migrate, retain, or erase anything, and choosing export is not consent to delete.

## Decision

The Graduation UI presents three household-copy outcomes:

1. **Read-only retention** is the no-action default. New caregiver-side child records remain disabled.
2. **Relationship only** removes evidence, hypotheses, choices, and subject-scoped events while retaining the person's display name, birth year, child/member link, and household relationship.
3. **Delete the household subject copy** removes that relationship record as well as all subject-scoped content.

The second and third outcomes require a fresh, subject-matched `org.foe.graduation-retention-consent/v1` authorization, an on-screen first-person confirmation, and an explicit typed deletion phrase. Authorization expires after five minutes. Deletion is not conditioned on first creating an export because portability and erasure are independent rights.

Both outcomes append one content-free deletion tombstone rather than a new narrative event. `org.foe.deletion-tombstone/v2` adds `SUBJECT_CONTENT`:

- `SUBJECT_CONTENT` removes subject-scoped evidence, hypotheses, choices, and events during every projection and merge, but retains the child/member relationship;
- `SUBJECT` removes both content and the child/member relationship.

A `SUBJECT_CONTENT` tombstone is valid only with the v2 schema and `subjectId == targetId`. Tombstone household, identity, author, device, and timestamp fields are validated before merge. Stale records lose to either tombstone regardless of arrival order. Older clients that cannot understand the new enum must fail closed rather than ignore it.

## Alternatives considered

- Automatically deleting at 16 was rejected because lifecycle transition is not consent.
- Requiring export before deletion was rejected because it would make erasure conditional on retention.
- Deleting each currently visible object without a subject-level marker was rejected because an offline device could later introduce another old object under a different ID.
- Reusing `SUBJECT` for relationship-only mode was rejected because the existing merge meaning also removes the person/member link.
- Appending a detailed audit event was rejected because subject-level deletion would remove it anyway and a narrative receipt would retain more personal data than necessary.

## Security and privacy consequences

- The durable tombstone necessarily retains an opaque subject target ID, author ID, device ID, timestamp, and reason code so synchronized stale data can remain suppressed.
- Shared-device confirmation is not cryptographic identity. A production implementation still needs subject-held credentials and identity-bound authorization.
- This implementation removes active projections and rewrites the encrypted local vault, but does not yet destroy a subject-specific data key because records are not independently envelope-encrypted by subject. Old remote ciphertext, filesystem remnants, separate exports, and provider-held plaintext are not physically erased by the tombstone.
- The UI therefore says “delete from the active household copy,” explains residual copies, and does not claim cryptographic erasure.

## Compatibility and migration consequences

Existing v1 record tombstones keep their meaning. `SUBJECT_CONTENT` is introduced only in a v2 tombstone, while `SUBJECT` remains supported. Android and the public JavaScript Vault merger share conformance tests for relationship retention and stale-content suppression. A later subject-key hierarchy can add narrow cryptographic erasure without changing the user's three high-level choices.

## Rollback and exit plan

The UI actions can be withdrawn, but an accepted v2 tombstone cannot safely be rolled back or ignored. Version-aware implementations must continue suppressing the targeted content. Production promotion requires authenticated subject identity, subject-separated key destruction, synchronized deletion acknowledgement/remote garbage collection, recovery drills, and independent privacy/security review.
