# ADR 0006: Client-encrypted sync envelope v1

- Status: Accepted for experimental reference implementation
- Date: 2026-09-14

## Context

ADR 0005 defines deterministic sync frames and merge semantics, but those frames contain family-authored records and must never be handed directly to a folder, NAS, WebDAV, S3-compatible, or hosted transport. A replaceable transport needs one opaque byte object that it can store without learning the household ID, child IDs, frame hash, tombstone targets, or record contents.

This decision is framing, not a claim that production household key distribution or automatic synchronization is finished.

## Decision

The reference client introduces `org.foe.encrypted-sync-envelope/v1` with these plaintext envelope fields only:

- format identifier;
- cipher-suite identifier `AES-256-GCM`;
- an opaque, randomly assigned key ID used by an authorized client to select a household sync key;
- a fresh 96-bit nonce;
- authenticated ciphertext containing one complete canonical `org.foe.sync-frame/v1` object.

The envelope uses a 256-bit household sync key supplied by the authorized client. The key is not serialized into the envelope. Associated authenticated data binds the format, cipher, opaque key ID, and payload purpose. Decryption then performs the complete sync-frame schema, body-hash, sequence, household, retirement, tombstone, and merge checks from ADR 0005.

The key ID is not a household name, household ID, device ID, account ID, or stable provider identity. Transport object names must likewise be random and must not contain semantic IDs or frame hashes. Every seal operation generates a new nonce; encrypting the same frame twice therefore yields different envelopes.

Malformed JSON, unsupported versions, invalid key IDs, oversized objects, invalid Base64, wrong keys, modified associated data, modified ciphertext, and invalid decrypted frames fail closed. Unknown envelope fields are rejected so a later protocol revision cannot silently change security meaning.

## Alternatives considered

- Transport-specific encryption was rejected because each adapter could create a different security boundary and forks could accidentally upload cleartext.
- Password-derived encryption for every frame was rejected because automatic sync needs a high-entropy household key and repeated password derivation adds both usability and parameter-migration hazards.
- Encrypting only record bodies was rejected because semantic IDs, hashes, tombstones, and activity shape would remain visible to the storage provider.
- Deterministic nonces were rejected because a crash-safe, multi-device nonce allocator is not yet specified.

## Security and privacy consequences

- Storage providers receive opaque authenticated ciphertext rather than Family State or cleartext sync frames.
- Providers can still observe object size, count, access timing, IP address, credentials, and any transport-level folder/account metadata. Padding and traffic-shaping are not part of v1.
- Possession of the household sync key permits reading and creating envelopes. AES-GCM does not prove which enrolled device created a frame. Device enrollment, per-device signatures, hardware-backed key wrapping, recovery, rotation, revocation, and checkpoint signatures remain mandatory production work.
- SecureRandom nonce generation is a reference assumption that requires independent cryptographic review before production release.

## Compatibility and migration consequences

Envelope format and cipher identifiers are versioned independently from the decrypted sync-frame schema. A future cipher suite or padded format uses a new envelope version and explicit migration; it must not reinterpret v1 fields. Key rotation may temporarily require clients to retain multiple opaque key IDs until an acknowledged checkpoint allows retirement.

## Rollback and exit plan

The envelope codec is below the provider interface and does not change Family State. It can be replaced by a reviewed implementation while preserving sync-frame bytes and merge behavior. Until household key enrollment is implemented, this remains an experimental local/conformance boundary and no transport is advertised as production synchronization.
