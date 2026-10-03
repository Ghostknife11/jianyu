# ADR 0021: Device signing identity is not device enrollment

- Status: Accepted for experimental foundation; the enrollment protocol remains open
- Date: 2026-10-02

## Context

The encrypted-sync envelope v1 authenticates possession of a shared household sync key. It does not identify which device created a frame. A copied key, a stale device, or an unapproved holder can produce apparently valid ciphertext. Automatically treating any such frame as enrolled would contradict the household's control over membership, deletion, and revocation.

The native reference App targets Android API 26+. Android Keystore supports purpose-restricted EC signing keys, including P-256 with SHA-256 ECDSA. Hardware backing varies by device and must not be assumed merely because a key is stored under `AndroidKeyStore`. See the [Android Keystore guide](https://developer.android.com/privacy-and-security/keystore) and [KeyGenParameterSpec reference](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec).

## Decision

Introduce a purpose-specific local device signing identity as a prerequisite for future authenticated enrollment:

- Creation is an explicit operation; reading an absent identity never silently generates one.
- The private P-256 signing key is generated and used through Android Keystore and is never serialized into the Family Vault, recovery bundle, provider settings, or transport.
- The public key is exportable in standard X.509 SubjectPublicKeyInfo form. Its SHA-256 fingerprint is a display/lookup aid, **not** evidence that the public key belongs to a trusted family device.
- A domain-separated signing operation is available for bounded binary enrollment challenges. Verification rejects changed messages, changed signatures, invalid public keys, and unsupported curves.
- Erasing this identity is a distinct action. It does not erase family content, revoke an already trusted device elsewhere, or destroy the household sync key.

This foundation does **not** alter the v1 sync-frame or encrypted-envelope formats, does not sign frames yet, does not enroll a second device, and is not invoked just because a folder was chosen. The current manual folder preview remains experimental and one-device-keyed.

## Enrollment requirements not yet implemented

Before a public key may authorize frames or receive a household key, a later versioned protocol must provide all of the following:

1. A new device proves possession of its private key using a fresh challenge; both sides bind the same household and session without exposing family plaintext to transport.
2. An existing authorized actor explicitly approves the new key after an out-of-band comparison on both devices; an untrusted relay cannot self-approve or change the compared identity.
3. The household-key transfer uses a reviewed authenticated key-establishment scheme and binds the approval, devices, key epoch, purpose, and expiration. A QR code or short code alone is not treated as encryption.
4. The resulting roster is authenticated, versioned, monotonic, and tied to signed frames/checkpoints. Revocation prevents future writes but cannot erase an already offline copy or retroactively hide old data from a device that held the old key.
5. Recovery and key rotation have explicit authority rules and drills, including the case where every previous device is lost. The Family Vault key, sync epoch key, provider API keys, and teen-owned keys remain separate.

The protocol and UI require independent cryptographic/privacy review before real-family multi-device use. No newly generated public key receives trust merely because it is present in a folder, encrypted frame, recovered archive, or app installation.

## Alternatives considered

- Treating possession of the current AES sync key as device enrollment was rejected: any copied or previously shared key could impersonate a device.
- Importing a software private key from the recovery bundle was rejected for this local identity: it would make the device identity clonable and conflate recovery with device approval.
- Shipping a pairing QR or short code now was rejected: without an authenticated ceremony and key-transfer protocol it could falsely imply that a second device is safe to join.

## Security and privacy consequences

An Android installation can establish and test a stable signing identity without adding a backend or changing the public Core. The cost is another device-local secret whose loss requires a future re-enrollment path. Software-backed Keystore and a compromised unlocked device remain possible; attestation and screen-holder identity are separate questions. A signature proves possession of the corresponding key for the exact signed bytes, not that the device or the person holding it is trusted by a household.

## Compatibility and migration consequences

No existing vault, sync frame, envelope, recovery bundle, or provider contract changes. The local signing key has a purpose-specific alias and does not enter exported data. A later enrollment protocol must version its public-key representation, roster, signed-frame binding, epochs, and migrations independently; it must not reinterpret unsigned v1 frames as signed history.

## Rollback and exit plan

This unused primitive can be removed without data migration while no device has been enrolled with it. Once a future protocol trusts a key, removing or losing it requires an explicit re-enrollment/revocation path rather than silent regeneration. The next implementation step is an authenticated, user-approved enrollment ceremony and its public conformance fixtures, followed by signed frames and acknowledged checkpoints—not automatic background sync yet.
