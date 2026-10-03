# ADR 0022: Signed device-join evidence does not grant access

- Status: Accepted for experimental protocol fixtures; not an enrollment release
- Date: 2026-10-02

## Context

ADR 0021 gives an Android installation a purpose-specific signing key, but no household trusts its public key. The next protocol step must bind a prospective device to a particular household and a fresh invitation, then record which already-known device proposed approval. That evidence must remain separate from actual authorization, key transfer, sync-frame acceptance, and human identity verification.

## Decision

Define three versioned, bounded, signed records: `org.foe.device-join-invitation/v1`, `org.foe.device-join-request/v1`, and `org.foe.device-join-approval-evidence/v1`.

The exact field order, byte encoding, digest construction, and validation limits are in [DEVICE-JOIN-PROTOCOL.md](../DEVICE-JOIN-PROTOCOL.md).

1. An existing device signs an invitation containing the household ID, its public key, a 256-bit random challenge, creation time, and short expiry.
2. A prospective device signs a request containing the exact invitation digest, its public key, a distinct 256-bit random challenge, and creation time.
3. The existing device may sign approval *evidence* containing the exact request digest, its public key, a locally attributed actor ID, and approval time.

The signed bytes use an explicitly ordered, length-prefixed UTF-8/binary representation with a record-type label. The experimental JSON carrier is accepted only in its canonical encoded form, preventing duplicate-key or alternate-parser ambiguity. Unrecognized schema or fields, oversized content, invalid key encodings, mismatched digests, signature failures, future/expired times beyond a bounded five-minute clock tolerance, and unexpected household or inviter keys fail closed. Public-key fingerprints and human-readable comparison text are not substituted for full-key equality.

A validator can establish only that these records are internally consistent and that the two signing keys were used. It returns **no enrolled-device capability**, does not mutate Family State, and does not release or wrap the household sync key. A `actorId` describes a shared-device action; it is not proof of which human held the phone. A relay may show a different transcript to different people until an out-of-band comparison and user-facing confirmation are implemented.

## Alternatives considered

- Accepting any signed request as enrollment was rejected because any phone can generate a signing key.
- Letting a signed approval record alone unlock the household key was rejected because shared-device signing does not authenticate the human approver and the key-transfer protocol is unfinished.
- Using wall-clock time as the sole replay defense was rejected; a later roster/checkpoint must record consumed invitation IDs and monotonic admission state.

## Security and privacy consequences

The records may expose an opaque household ID, public keys, timing, and actor ID to whoever carries the pairing artifact. They must not be uploaded to a generic sync provider as plaintext family context. A 256-bit random challenge makes accidental collision or blind guessing implausible but does not itself authenticate a person or prevent replay of a captured valid transcript. Expiry is checked against a supplied local clock; clock manipulation and offline replay still need a consumed-invitation ledger and signed roster. The current 13–15 and Graduation boundaries require separate subject-aware approval design before a new device receives any private scope.

## Compatibility and migration consequences

No existing vault, sync frame, envelope, or recovery bundle changes. Record schemas and signing bytes are fixed independently so another client can implement conformance fixtures. A later protocol may replace these experimental records with a reviewed scheme; v1 evidence must never be reinterpreted as a production enrollment grant.

## Rollback and exit plan

The evidence codec is not connected to the reference App's folder preview or user UI and can be removed without migration. Before any device can join, add a user-visible two-device comparison and confirmation ceremony, authenticated actor/subject authority, a reviewed key-transfer mechanism, consumed-invitation state, signed roster and frames, revocation/rotation, recovery drills, and independent security review. No automatic sync promotion follows from this ADR.
