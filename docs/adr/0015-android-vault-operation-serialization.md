# ADR 0015: Serialize Android Family Vault operations

- Status: Accepted for the Android reference App
- Date: 2026-09-28

## Context

The local Family Vault is authoritative, but the App launches family edits, AI discovery receipts, encrypted-folder transfer, recovery import, Graduation actions, and erasure in separate I/O coroutines. Serializing only the encrypted file write is insufficient: two coroutines can each read the same old `FamilyState`, derive different successors, and save them in sequence, losing the first change. The manual encrypted-folder preview has the same stale-snapshot risk while it fetches remote objects.

## Decision

One `MainViewModel` instance uses a `VaultOperationGate` for every operation that reads, writes, replaces, exports, or erases the local Family Vault. The gate covers the current-state read, any derived state, repository write, and UI projection, so the next local operation starts from the last confirmed state. Formal discovery keeps its pre-send approval, external request, and completion receipt in the same local operation; the manual sync preview keeps its remote merge/upload and local save together. A queued operation sees the latest confirmed state when it enters the gate, not a snapshot captured when its button was tapped.

AI and World connection-form saves also enter this gate and re-check that a family still exists before writing device-local credentials. This prevents a queued settings save from recreating a connection after the same App instance has erased its family Vault and connection files.

Formal discovery additionally admits only one in-flight submission per App instance. The gate alone would serialize two rapid taps but could still send two consecutive billable requests; the admission guard prevents the duplicate before either operation is queued and resets after completion or cancellation.

This is an App-level safety measure. It does not change the event schema, encrypted frame format, `SyncProvider` contract, or fork extension points. It does not make the preview production multi-device sync.

## Alternatives considered

- Lock only `repository.save`: rejected because stale read-modify-write snapshots would still overwrite one another.
- Merge every competing local snapshot after the fact: rejected for this slice because replacement, deletion, authorship, and disclosure receipts need operation-specific conflict semantics; a generic merge cannot safely guess intent.
- Move immediately to a transactional event database: a valid future direction, but it would freeze a storage choice and require a separate ADR and migration plan.

## Security, privacy, and usability consequences

- A local edit cannot silently replace another local edit from the same App instance. The disposable-AVD tests queue two independent corrections and read both back from the encrypted Vault, then verify two rapid discovery taps invoke a synthetic AI source only once; a separate deterministic test checks gate ordering.
- A long AI request or folder transfer temporarily delays later Vault operations. The UI must keep in-flight states visible; future work may use a transactional event append or rebase protocol to reduce this wait without reintroducing stale writes.
- This gate is not a cross-process lock, device-joining protocol, signed sync checkpoint, remote exactly-once guarantee, or substitute for the pending-file recovery work in ADR 0014. Background workers and any second process must use a shared transactional boundary before they may write the Vault.
- A recovery import remains an explicitly confirmed replacement, not an automatic merge. Erasure remains a separately confirmed destructive action.

## Compatibility and exit plan

No stored data or ciphertext format changes; existing Vaults remain readable. A later storage transaction or event-log implementation may replace the gate only after concurrent edit, import, discovery, sync, deletion, and crash tests prove that no confirmed local change is lost. Keep this ADR as the reason for the required invariant even if the mechanism changes.
