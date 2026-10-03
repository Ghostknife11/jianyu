# Experimental device-join evidence v1

This is a public interoperability draft for **evidence**, not a way to join a family vault. The Android reference implementation is `DeviceJoinEvidenceCodec`; another client may implement the same records without depending on the Jianyu app. See [ADR 0022](adr/0022-device-join-evidence-without-automatic-admission.md) for the trust boundary.

## Three records

All records are flat JSON objects with string fields. The experimental JSON carrier is emitted with fields in the order below, with defaults included and no extra whitespace or duplicate keys. A reader must reject unsupported schemas, unknown fields, noncanonical encoding, and objects larger than 8 KiB. This fixed-order carrier is **not** a claim of [RFC 8785 JSON Canonicalization Scheme](https://www.rfc-editor.org/rfc/rfc8785.html) compliance; the signing bytes are defined separately, and a future reviewed wire format may replace the carrier under a new schema.

| Record | Signing fields in order, excluding `signature` |
|---|---|
| `org.foe.device-join-invitation/v1` | `schema`, `householdId`, `inviterPublicKeySpki`, `challenge`, `createdAt`, `expiresAt` |
| `org.foe.device-join-request/v1` | `schema`, `invitationDigest`, `candidatePublicKeySpki`, `candidateChallenge`, `createdAt` |
| `org.foe.device-join-approval-evidence/v1` | `schema`, `requestDigest`, `inviterPublicKeySpki`, `attributedActorId`, `approvedAt` |

`householdId` and `attributedActorId` are 1–128 ASCII letters, digits, `_`, or `-`. Public keys are canonical unpadded Base64URL of X.509 SubjectPublicKeyInfo for P-256 EC keys. `challenge` and `candidateChallenge` are independently generated 32-byte random values encoded as canonical unpadded Base64URL; the validator rejects equal values but cannot prove their entropy. Timestamps are canonical UTC `Instant` strings. An invitation lives for at most 10 minutes. A five-minute clock tolerance allows bounded skew, but wall-clock checks are **not** a replay defense.

## Exact bytes

For signing, encode each field above as UTF-8. Prefix each byte string, including the schema/type field, with a four-byte unsigned length in network byte order. Concatenate the prefixed fields in the listed order. Each field is limited to 1 KiB.

The P-256 SHA-256 ECDSA signature is over:

```text
UTF8("org.foe.device-enrollment-proof/v1") || 0x00 || length-prefixed-fields
```

The signature is DER encoded and carried as canonical unpadded Base64URL. The invitation is signed by `inviterPublicKeySpki`; the request by `candidatePublicKeySpki`; the approval evidence by the inviter key. A signature only proves use of the matching private key for those exact bytes.

`invitationDigest` is lowercase hex SHA-256 of the length-prefixed sequence `"org.foe.device-join-digest/v1"`, Base64URL of the invitation's signable bytes, and its signature string. `requestDigest` uses the same construction for the request. The digest includes the signature so two otherwise identical signed artifacts cannot be silently interchanged.

## Validation and non-authority

A validator checks the expected household ID and an inviter public key obtained from a **separate trusted local source**, not from the invitation itself; it then checks schemas, bounds, canonical encodings, both digest links, signatures, distinct device keys/challenges, and the invitation/request/approval times. The current codec returns no roster mutation, sync capability, household key, or assertion that the named human approved anything.

Before any real second device can read or write family data, a future ceremony must let people compare the same transcript on both devices, authenticate appropriate human/subject authority, record invitation consumption in a signed monotonic roster, transfer the correct key epoch through a reviewed authenticated channel, bind signed frames and checkpoints to that roster, and support revocation/recovery. [RFC 9180 HPKE](https://www.rfc-editor.org/rfc/rfc9180.html) is a candidate standard to evaluate for key delivery, not an implemented or selected suite here. Existing v1 encrypted folder objects do not become trusted merely because these three records exist. No official family server is required for the eventual protocol, but a third-party relay must not gain family plaintext.
