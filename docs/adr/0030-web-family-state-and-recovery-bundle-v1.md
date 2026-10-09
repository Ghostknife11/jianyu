# ADR 0030: Web family-state format v1, recovery bundle, and compatibility stance

- Status: Accepted for experimental developer preview
- Date: 2026-10-10

## Context

The web app (ADR 0028) needs an on-client family state format, an encrypted recovery bundle, and a stated position on compatibility. Three existing formats are candidates but none fits as-is:

- The Android Family State (`org.jianyu.family-vault/v5`) is a Kotlin implementation; adopting it would claim a synchronization compatibility the repository explicitly does not have.
- The browser prototype's state (`org.jianyu.family-vault/v2`) is marked non-authoritative by ADR 0002, is browser-IndexedDB-specific, and predates the exact-birthday rule (ADR 0011).
- `packages/foe-vault` provides the merge semantics (`mergeFamilyState`), tombstone validation, and the AES-256-GCM envelope helpers, but no KDF, no persistence, and no recovery bundle.

The event and opportunity *envelopes*, however, are public and shared: `org.foe.event/v1` validated by `foe-schema`, and the opportunity schema with ecosystem canonicalization. Those should be reused so the web app's records remain interpretable FOE records rather than a private dialect.

FORKING.md §4 permits a compatibility claim only when the level is named and tested: Import-compatible, Round-trip compatible, Sync-compatible, Extension-compatible, or Opportunity-compatible. The honest available level today is Opportunity-compatible at the engine level; Sync-compatible requires the unfinished enrollment/checkpoint protocol.

## Decision

**State format.** The web app defines `org.jianyu.web-family-state/v1` as its own versioned format, projected by the client and encrypted as one opaque object on the NAS (ADR 0029). The state carries `household`, `members`, `children`, `evidence`, `hypotheses`, `choices`, `events`, `tombstones`, and `preferences`, matching the collection names and ID fields `mergeFamilyState` already merges, so the public merge function applies without modification.

- Events use the public `org.foe.event/v1` envelope and must pass `assertEvent` before being appended; event families follow the initial set in DATA-SCHEMA.md §11.
- Opportunities use the public opportunity schema; ecosystem aliases are canonicalized before Gate and diversity evaluation.
- Children carry a local ISO-8601 `birthDate`; lifecycle stage and authority derive from completed age on the birthday, with a February 29 birth date advancing on March 1 in non-leap years (ADR 0011). Legacy year-only records migrate deterministically and are labeled approximate in the UI.
- Feedback events use `opportunity.feedback-recorded` version 2 with `payload.responseSource` (`child-signed` or `caregiver-relayed-child-view`); version 1 or unmatched feedback is excluded from later AI context, matching the Android reference behavior.
- Choice deletion appends `org.foe.deletion-tombstone/v1` records for the choice and its linked events plus a content-free audit event (ADR 0026); shared-timeline rendering consumes a fail-closed projection that omits restricted records and never exposes hidden counts (ADR 0012).
- Graduation at 16+ projects a subject-only `org.foe.graduation-archive/v2` export with an independent recovery key, and the three retention outcomes of ADR 0010 are available as the minimal v1 hand-over.

**Migrations.** Migrations are deterministic, idempotent, offline, and fixture-tested. A state whose version is newer than the client understands fails closed. Unknown optional fields round-trip; unknown security semantics fail closed (ARCHITECTURE.md §14).

**Encryption parameters (v1).** The client derives two PBKDF2-SHA-256 keys from the passphrase and a 16-byte random salt at 210,000 iterations, domain-separated as `nas-auth-v1` and `nas-vault-v1`. The state object is sealed with AES-256-GCM under the vault key, with a fresh 96-bit nonce and associated data binding the format, household ID, and state version. These parameters match the browser prototype and are recorded here so a future change is visible. PBKDF2 is **not** memory-hard; SECURITY.md and ADR 0004 both require a memory-hard KDF before production use, so v1 is labeled a developer preview and an Argon2id (or equivalent) migration with its own ADR, test vectors, and review is required before real family data.

**Recovery bundle.** The app defines `org.jianyu.web-recovery-bundle/v1`: a JSON document containing the format, KDF parameters, salt, the AES-256-GCM sealed state, and a random recovery code shown once at export. Import is a two-step replacement, never a merge: the file and code are verified locally first, the current and bundled household names are shown for a separate confirmation, and a wrong code or a local change after preview keeps the existing vault untouched. The bundle grants no server-side effect by itself; registering it on a NAS is a separate explicit action.

**Compatibility stance.** The web app claims **Opportunity-compatible** behavior: the same public Gate, Diversity Selector, Context Firewall, event and opportunity schemas, lifecycle policy, and provider/pack/brand contracts, verified by the shared conformance tests. It does **not** claim Import-, Round-trip-, or Sync-compatibility with the Android reference app: the state formats differ, household key enrollment and signed checkpoints do not exist, and no cross-client fixture proves otherwise. Documentation must use the named level, never a vague "Jianyu-compatible" (FORKING.md §4).

## Alternatives considered

- **Adopting the Android v5 state format directly** was rejected: it would imply an interoperability the sync protocol cannot yet deliver and would freeze the web app to a Kotlin-oriented schema.
- **Reusing the prototype's v2 browser format** was rejected: ADR 0002 marks the prototype non-authoritative, and its format predates ADR 0011 and the feedback-provenance rules.
- **Defining a brand-new event envelope for the web app** was rejected: the public envelope exists, is validated, and preserves authorship and provenance; a private dialect would break the evidence guarantees the product promises.
- **Claiming Sync-compatible now** was rejected: enrollment, device signatures, signed checkpoints, rotation, and compaction are unimplemented (ADRs 0005–0009, 0021, 0022; IMPLEMENTATION-STATUS.md), and a vague claim is explicitly forbidden by FORKING.md §4.
- **Argon2id in v1** was rejected for now: no zero-dependency, browser-plus-Node implementation exists in this repository, and adopting a WASM dependency would contradict the dependency posture ADR 0028 just froze. The limitation is documented instead.

## Security and privacy consequences

- Domain-separated derivation prevents the authentication verifier from being reused as the vault key; the vault key never leaves the browser.
- Associated data binds ciphertext to its household and state version, preventing substitution across households or versions.
- The recovery code is the only recovery material; losing it with the passphrase loses the vault, and the export flow says so plainly. A holder of the bundle or the household key can read its contents — the web app does not claim child-held independent keys, matching the Android app's stated boundary.
- Tombstones dominate during merge, so a stale restored copy cannot resurrect deleted records; v2 subject-content tombstones cover Graduation retention.
- PBKDF2's non-memory-hard nature is the stated v1 weakness; the preview labeling and the Argon2id migration plan are the mitigations, not a claim of production strength.

## Compatibility and migration consequences

- The format is versioned; a v2 state requires a new record, a deterministic migration, and published fixtures before any writer emits it (FORKING.md §3 item 8).
- Event envelopes and opportunity records remain public FOE records, so future cross-client import at the record level stays possible even though full sync compatibility does not exist today.
- The Android recovery bundle (`org.foe.portable-family-bundle/v1`) and this bundle are separate formats; neither claims to open the other.

## Rollback and exit plan

- If the Android v5 format later gains the enrollment protocol, the web state can be re-projected from its own event log into the shared format behind a new ADR and tested fixtures; family-authored events survive because they are public envelopes from the start.
- If the web format is abandoned, the recovery bundle and export views remain readable documented JSON plus the sealed state; no family record is trapped in a private encoding.
- Changing the KDF requires a new ADR, migration of existing vaults, and independent review before real family data (FORKING.md §6).
