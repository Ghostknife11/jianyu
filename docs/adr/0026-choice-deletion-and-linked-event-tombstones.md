# ADR 0026: Delete a saved choice with its linked content-bearing events

- Status: Accepted for the Android reference path
- Date: 2026-10-03

## Context

The Family Vault can delete one evidence projection, but a saved choice, explicit veto, or later view has no individual deletion action. Removing only `FamilyChoice` would leave its title and response in `opportunity.chosen` / `opportunity.feedback-recorded` events, and an old synchronized copy could restore the choice. The existing v1 tombstone format already supports `CHOICE` and `EVENT` targets.

## Decision

- A confirmed deletion removes the selected choice and every same-subject event whose payload refers to its `choiceId`. It does not erase the earlier interest evidence, unrelated events, or an external service's copy of an approved request.
- The client appends one content-free `CHOICE` tombstone and one `EVENT` tombstone for each removed linked event. A new `opportunity.choice-deleted` v1 audit event records only opaque target and tombstone IDs, never the title, later view, or original provider text. Merge applies those tombstones before projecting stale copies.
- For a current 4–12-year-old, a caregiver or guardian authors the action. At 13+ the subject must explicitly confirm this deletion on the shared device, and the action is attributed to the linked subject member. A tap is not proof of human identity; independent subject authentication remains unfinished.
- The Timeline exposes a secondary, separately confirmed delete action for both real and retained legacy-demo choices. While deletion is saving, its other response actions are disabled. A failed local save leaves the original visible and reports a local-save failure.
- This is logical deletion in the active Family Vault and deletion-dominant merge, not a promise to remove content from previously written encrypted frames, copied recovery bundles, external providers, or physical flash blocks. Production remote ciphertext cleanup and narrow-key erasure remain separate work.

## Alternatives and privacy consequence

Deleting only the `FamilyChoice` projection was rejected because linked events can still contain its title or later response. Rewriting old events in place was rejected because it would obscure provenance and still let an old copy restore the content. Deleting the earlier interest evidence by default was rejected because that evidence is an independent family record; it has its own correction/deletion action.

The shared Timeline can operate only on choices visible in its current projection. Private choices need a future subject-authenticated view and cannot be exposed merely to provide this action. The existing whole-subject retention controls remain available at Graduation, but are not a substitute for a narrow private-choice control.

## Compatibility and exit

No existing schema is reinterpreted: v1 `CHOICE` / `EVENT` tombstones and v1 `FamilyEvent` are used as defined. Older clients that understand those tombstones can suppress stale projections. A future subject-key architecture may supersede the shared-device confirmation and make physical cryptographic erasure narrower.

If this UI flow is withdrawn, existing tombstones must continue to be honored by import and merge; rollback may hide the action, never resurrect deleted content. An interoperable client must apply the choice and linked-event tombstones together before presentation.
