# ADR 0014: Fail-closed startup for unreadable or pending Family Vault

- Status: Accepted for the Android reference client
- Date: 2026-09-28

## Context

The encrypted Family Vault is the local authority. Previously, a failed decrypt/migration left the App in the same `family == null` state as a genuinely empty device, exposing new-family onboarding. A crash during the first atomic write could also leave only `family.vault.pending`; `load()` ignored that file and returned empty. A new save could then replace recoverable family ciphertext.

## Decision

The Android repository treats any pending vault file as an unresolved write, not an empty household. It does not delete, promote, or overwrite that file during startup, save, import, or export; explicit erasure remains a separate destructive action. A load failure—including an unreadable main vault or pending file—sets an explicit fail-closed UI state with no new-family action. The only current action is to retry the read after the underlying condition changes. First-time creation has a single in-flight guard and shows local-save progress. An ordinary failed save keeps the form; if it leaves an unresolved pending file, the App instead moves to the fail-closed page.

## Consequences and open work

- This prevents an accidental onboarding overwrite of existing or potentially recoverable ciphertext. It does not prove that the ciphertext can be recovered.
- An interrupted write may block ordinary startup until a safe recovery flow exists. A future design must validate main and pending envelopes, choose a recoverable version without losing deletion/consent events, preserve both originals before repair, and test crash points and key-loss scenarios. It must not silently discard `family.vault.pending` merely to make startup succeed.
- The failed-open screen does not yet import a recovery bundle. Families should not be told that tapping retry performs recovery.
- Tests create only synthetic malformed files on the disposable UI-test AVD and verify bytes are unchanged by attempted onboarding.
