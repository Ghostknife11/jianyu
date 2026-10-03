# ADR 0007: Self-controlled Graduation archive v1

- Status: Accepted for experimental reference implementation
- Date: 2026-09-14

## Context

At 16+, Jianyu stops creating new caregiver-side child records. A banner that merely says “Graduation” does not give the person meaningful control. Whole-family recovery export is also the wrong boundary: it can include siblings, caregivers, household administration, and records unrelated to the graduating person.

The person needs a portable copy of the records about them, in both machine-readable and human-readable form, without silently enrolling them in another service or exposing the rest of the household.

## Decision

The reference App introduces `org.foe.graduation-archive/v1`, carried inside `org.foe.encrypted-graduation-bundle/v1`.

Archive creation requires all of the following:

- the subject exists and is in the 16+ Graduation lifecycle stage;
- a child-role family member is linked to that subject;
- the UI obtains a fresh, explicit confirmation from the person for this export;
- the authorization subject matches the exported subject.

The archive contains only the selected subject identity and records scoped to that subject: evidence, hypotheses, choices, subject/authored events, and relevant tombstones. Records for siblings and unrelated family members are excluded. Authorship and source identifiers inside included records remain so the archive does not rewrite a caregiver interpretation as the person's own statement.

The archive includes canonical versioned JSON fields plus a generated Markdown view. The human view is a chronology and provenance summary, not a personality, intelligence, potential, compliance, coverage, or development score.

Every export creates a new random 256-bit recovery key, a new opaque bundle ID, and a new 96-bit nonce. AES-256-GCM authenticates the complete archive and binds the format, bundle ID, and `graduation-archive` purpose. The recovery key is shown separately and never written into the bundle or Family Vault. The plaintext envelope reveals no name, subject ID, household ID, record count, or lifecycle content, though storage still observes file size and timing.

Export does not automatically delete the household copy, transfer data into an adult product, create an account, contact a provider, or grant future authorization. Those are distinct actions requiring separate design and confirmation. ADR 0010 later defines separately confirmed household-retention and subject-deletion controls.

## Alternatives considered

- Reusing whole-family recovery export was rejected because it violates subject minimization and can expose sibling data.
- Plain JSON/Markdown export was rejected as the default because document providers and shared folders could immediately read sensitive childhood history.
- Automatic transfer at age 16 was rejected because age transition is not consent to a new controller or product.
- Exporting only AI summaries was rejected because it would discard primary evidence, authorship, contradictions, and corrections.

## Security and privacy consequences

- The graduating person receives a separately encrypted, subject-minimized archive and independent recovery material.
- Anyone holding both bundle and recovery key can read the archive; loss of the key makes it unrecoverable.
- Included family-authored records can still contain sensitive or incorrect statements. The archive preserves attribution and does not certify them as truth.
- UI confirmation on a shared device is not cryptographic proof of identity. A production self-controlled space still needs private authentication, child-held keys, secure device transfer, recovery, and independent review.
- File size and export timing remain visible; padding is not implemented.

## Compatibility and migration consequences

Archive and encrypted-envelope versions evolve independently. Readers must fail closed on unknown envelope security semantics and may migrate known archive schemas after authenticated opening. Forks can implement their own human views without changing the canonical records.

## Rollback and exit plan

Graduation export is read-only with respect to Family State. The codec can be replaced without migrating the vault. The experimental UI may be withdrawn if review finds a flaw, while existing bundles remain readable by version-aware tools. ADR 0010 adds deletion and minimal household retention as separate, freshly authorized actions; selective transfer into a new self-controlled key space remains future work.
