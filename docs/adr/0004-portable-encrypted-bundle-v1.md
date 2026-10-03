# ADR 0004: Portable encrypted bundle v1

- Status: Accepted for prototype and synthetic data only
- Date: 2026-09-13

## Context

The first Android vault is encrypted with a non-exportable Android Keystore key. Copying those ciphertext bytes to another device is therefore not recovery and cannot be the basis of WebDAV, S3, NAS, folder, or hosted synchronization.

Network adapters must not be implemented around a device-bound object and later “fix” encryption. The project first needs a portable, provider-agnostic encrypted object with tests for wrong keys, tampering, nonce uniqueness, metadata exposure, and round trips.

## Decision

`org.foe.portable-family-bundle/v1` is an experimental whole-vault recovery envelope:

- canonical FamilyState JSON is encrypted on the client with AES-256-GCM;
- every export creates a cryptographically random 256-bit recovery secret;
- the recovery secret is encoded as unpadded Base64URL and is never written into the bundle;
- every encryption uses a fresh 96-bit nonce;
- format, opaque bundle ID, and content type are authenticated as associated data;
- the envelope contains only format/cipher identifiers, an opaque random bundle ID, creation time, nonce, and ciphertext;
- import validates size, format, key length, authenticated ciphertext, and FamilyState migration before replacing local state.

The v1 recovery secret is random key material, not a user password. No password KDF is claimed or implied. A future passphrase mode requires a memory-hard KDF, versioned parameters, a separate ADR, and specialist review.

The App displays the recovery secret once and does not place it in Family Vault, Provider settings, the encrypted file, logs, or future sync objects. Anyone holding both file and secret can decrypt the export; losing the secret makes the export unrecoverable.

## Non-decisions

This ADR does not yet define:

- a stable household key hierarchy or device enrollment;
- append-only sync frames, checkpoints, conflict resolution, tombstones, revocation, or stale-device quarantine;
- WebDAV, S3, NAS, folder, or hosted provider authentication;
- background scheduling;
- attachment chunking or metadata padding;
- production cryptographic readiness.

Those remain required before the App can claim multi-device synchronization. The portable bundle is the tested cryptographic substrate and an explicit manual recovery path, not automatic sync.

## Consequences

- Families can create a file that is portable across Android devices when the separate recovery secret is available.
- Storage services can hold the file without plaintext family content, but still observe file size and transfer metadata.
- Export is intentionally explicit and occasional; it creates no daily maintenance requirement.
- Until independent review and the release gates in `SECURITY.md` pass, the feature is labeled experimental and real family data is not recommended.
