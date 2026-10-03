# ADR 0009: Android manual encrypted-folder preview

- Status: Accepted for experimental developer preview
- Date: 2026-09-15

## Context

ADRs 0005–0008 define hash-chained full-state frames, deletion-dominant merge, client-encrypted envelopes, and a ciphertext-only `SyncProvider`. The Android App still had no way to grant a replaceable transport access to a user-selected document tree or run those pieces in their intended order. That left a gap between tested protocol components and the future NAS, WebDAV, S3-compatible, or hosted adapters.

This decision does not claim production multi-device synchronization. In particular, there is no authenticated household-device enrollment, per-device signature, key-transfer ceremony, signed checkpoint, safe compaction, automatic scheduling, remote ciphertext deletion policy, or independent cryptographic review.

## Decision

The Android reference App may expose a manually triggered feature named **“Encrypted folder transport · developer preview”**. It uses the operating system's Storage Access Framework to obtain revocable, persisted read/write access to one user-selected document tree. Selecting a folder creates only an `objects` child directory; no family state leaves the device until the user separately triggers a transfer.

The `DocumentTreeSyncProvider` implements the same public opaque-object contract as other transports. It accepts canonical random object names only, bounds directory scans and object sizes, verifies names and types returned by the document provider, treats objects as immutable, verifies bytes after writing, and never receives a household key, cleartext frame, or Family State.

The provider-neutral `FamilySyncCoordinator` performs the sensitive work on the authorized client in this order:

1. list bounded ciphertext descriptors from the provider;
2. download and authenticate every envelope locally;
3. require a complete, unbroken per-device history and reject forks, gaps, rollback, disappearance, duplicate IDs, stalled cursors, wrong households, and retired devices;
4. apply frames using deletion-dominant merge;
5. if this device has no prior frame, or the merged household snapshot differs from its latest verified frame, create the next local hash-chained frame;
6. encrypt any new frame before calling the provider;
7. persist the updated local cursor and merged Family State, including when no upload was needed.

A repeat check of unchanged household content does not append another full snapshot or consume one of the v1 history slots. The comparison excludes transport cursor metadata but includes deletion tombstones and retired-device IDs. A device retired by newly merged remote history cannot publish another frame. This is only a bounded growth correction: ordinary changes still append whole-state frames, and no safe compaction or authenticated revocation has been added.

The v1 preview accepts at most 256 history objects and 48 MiB of total ciphertext. It intentionally has no compaction; reaching the limit stops safely and requires a future checkpoint protocol rather than silently discarding history.

The folder URI, opaque local device ID, key ID, and 256-bit household sync key material are stored in a purpose-specific Android-Keystore-encrypted settings file. They do not enter Family State, provider paths, AI prompts, World Brief queries, events, or ordinary exports. Disconnecting revokes the persisted URI grant where supported and erases local settings, but does not silently delete existing remote ciphertext.

If that device-local settings file exists but cannot be read, the App shows a distinct retry state. It does not reinterpret the failure as an unconfigured folder or offer to generate a fresh key over the old connection. A retry re-reads the original settings without modifying them. The Family Vault remains locally usable. After a separate irreversible-action confirmation, the family may instead discard the unreadable connection and its local key; the original folder URI cannot then be recovered for automatic permission revocation, so the App warns that system authorization may need separate removal. An explicitly authorized whole-vault erase can also proceed when the separate settings file is unreadable. None of these actions recovers a lost sync key or removes remote ciphertext.

The shared device-settings store now requires an existing Android Keystore alias when decrypting an existing file. A missing alias fails closed without generating a replacement on read; only an intentional new settings write may generate a key. The same rule applies to the other purpose-specific encrypted device settings that use this store.

## Alternatives considered

- Passing a filesystem path into the Java NIO reference adapter was rejected because Android document trees are capability URIs and may represent cloud or removable providers rather than ordinary paths.
- Writing an unencrypted vault copy was rejected because a selected folder and its upstream storage are outside the trusted plaintext boundary.
- Calling the feature “sync” without qualification was rejected because the current shared-key proof authenticates ciphertext, not an enrolled device identity.
- Automatic background execution was rejected until enrollment, scheduling controls, battery/network policy, and visible failure recovery exist.
- Deleting remote objects on disconnect was rejected because disconnect is permission withdrawal, not authorization to destroy every other copy.

## Security and privacy consequences

- The document provider observes the chosen folder, random object names, counts, sizes, and access timing. It cannot read authenticated ciphertext without the household key.
- A holder of the current shared key can create valid envelopes; without device signatures the client cannot distinguish an enrolled device from another key holder. The UI must state this limitation.
- Requiring complete append-only history detects omission and rollback relative to local cursors, but makes storage grow until a signed checkpoint/compaction protocol exists.
- A newly installed App cannot recover the folder key from ciphertext. The portable recovery bundle remains separately required; folder transport must not be presented as the only backup.
- Android document providers vary in atomicity and naming behavior. The adapter verifies returned names, file type, size, and bytes and fails closed when a provider cannot preserve the contract.

## Compatibility and migration consequences

The coordinator depends only on `SyncProvider`, so WebDAV, S3-compatible, NAS, or hosted transports can reuse it. Provider-specific credentials and cursors remain outside Family State. Device enrollment, signed frames/checkpoints, key rotation, and compaction require versioned additions; they must not reinterpret unsigned v1 history as signed history.

## Rollback and exit plan

The UI entry can be removed without changing Family State or vault schema. Existing `.foe-sync` files remain opaque and can be copied to another conforming provider. Production promotion requires authenticated enrollment, signature verification, recovery/key-transfer drills, signed checkpoints and compaction, remote deletion behavior, real-device document-provider tests, and independent security/privacy review.
