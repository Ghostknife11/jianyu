# ADR 0008: Opaque SyncProvider and folder transport

- Status: Accepted for experimental reference implementation
- Date: 2026-09-14

## Context

ADR 0005 defines verified sync frames and merge semantics. ADR 0006 ensures that a complete frame is client-encrypted before a storage provider can observe it. The project still needs one replaceable transport contract that folder sync, NAS clients, WebDAV, S3-compatible storage, and hosted relays can implement without gaining access to household semantics or cryptographic keys.

This decision does not complete automatic synchronization. Household-key enrollment, device signatures, acknowledged checkpoints, rotation, revocation, background scheduling, and conflict recovery remain separate work.

## Decision

The Android reference surface introduces a public `SyncProvider` contract with four operations:

- list bounded opaque objects using an opaque cursor;
- put one encrypted object under a separately generated opaque object ID;
- get one encrypted object by that ID;
- delete one encrypted object by that ID.

Object IDs are fixed-length random Base64URL strings generated independently from household, child, member, device, frame, event, tombstone, content-hash, and key identifiers. Provider implementations reject path separators, traversal, extensions, UUID-shaped semantic identifiers, and any identifier outside the canonical form.

Objects are immutable. Retrying the same ID with byte-identical content is idempotent; attempting to overwrite that ID with different content fails closed. Pages and objects have strict size limits. A provider never receives a Family State, sync frame, household key, decrypted record, semantic filename, or merge authority.

The first reference implementation stores objects in a caller-selected filesystem folder. It uses a private `objects` child directory, random staging names, a same-directory non-replacing hard-link commit where supported, a `CREATE_NEW` fallback that still cannot overwrite an existing object, bounded reads, no-follow checks, deterministic listing, and exact-object deletion. It is intended for conformance tests and for future integration with Android's Storage Access Framework or an external folder-sync tool. ADR 0009 later permits a prominently labeled Android manual developer preview without treating it as production multi-device synchronization.

## Alternatives considered

- A transport-specific API for each backend was rejected because it would duplicate encryption, pagination, retry, and conflict rules.
- Mutable `latest.json` or database-file replacement was rejected because concurrent devices could overwrite evidence and resurrect deleted records.
- Content hashes or frame IDs as filenames were rejected because they reveal protocol relationships and create stable correlation identifiers.
- Passing a household key into the provider was rejected because storage transport is outside the cryptographic trust boundary.
- Advertising the folder adapter immediately in the UI was rejected because one-device key material cannot yet establish safe multi-device synchronization.

## Security and privacy consequences

- A provider sees random object IDs, ciphertext sizes, object counts, access timing, its own credentials, and the containing account or folder path. It does not receive family plaintext or semantic identifiers from the contract.
- Immutable writes prevent silent object replacement but do not authenticate the creating device. ADR 0006 authentication proves key possession, not enrolled-device identity.
- A malicious backend can omit, replay, reorder, duplicate, or delete objects. Frame verification, per-device sequence checks, tombstone dominance, future signed checkpoints, and user-visible recovery remain client responsibilities.
- A caller can still violate the boundary by handing plaintext bytes to an implementation. App orchestration must only construct provider payloads from the validated encrypted-envelope codec, and conformance tests must keep that dependency visible.

## Compatibility and migration consequences

Folder, WebDAV, S3-compatible, and hosted adapters implement the same object semantics. Backend-specific ETags, HTTP headers, bucket versions, paths, credentials, and retry policies remain adapter details and cannot alter Core merge meaning. A future provider contract with mutable manifests, leasing, or signed checkpoints requires a new version rather than reinterpretation of this one.

## Rollback and exit plan

The contract transports opaque bytes and does not change Family State or sync-frame schemas. The folder implementation can be replaced without data migration by copying its encrypted object files into any conforming backend. Until device enrollment and automatic orchestration are implemented and reviewed, the reference App continues to label cross-device sync as unfinished. ADR 0009 documents the narrower manual Android preview and its exit conditions.
