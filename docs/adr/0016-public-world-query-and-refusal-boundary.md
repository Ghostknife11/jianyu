# ADR 0016: Exact public World query approval and conservative refusal matching

- Status: Accepted for the reference clients
- Date: 2026-10-01

## Context

WorldBriefProvider is a dynamic third-party public-world service, not a Pack or an AI prompt. The Android Engine already requires an exact approved public query and matches returned topics locally. The JavaScript reference flow previously allowed arbitrary query keys and accepted a service's pre-mapped opportunity without checking the child's current interest. Both paths could also treat a topic mentioned in an explicit refusal as positive child pull.

## Decision

- Public World queries are limited to `region`, `timeWindow`, `language`, `categories`, and `cursor`, with bounded values. An unknown or private field fails before a Provider call. The exact query must match a separate `approvedWorldQuery` on each reference-flow request; absent or changed approval skips the World call with an `outside-approved-scope` provenance reason. A capability alone does not prove consent to the particular query.
- Third-party World data keeps public topic terms and is matched to the current, explicitly entered interest on the client. The JavaScript v0.1 compatibility path still accepts pre-mapped public candidates from `getBrief`, but now requires their `topics` for local matching; an untagged candidate is not promoted simply because its provider sets `childPull`.
- Optional Pack and World candidates are excluded when any of their own trigger/topic terms occurs in a clause with a common explicit refusal. A different refused clause does not suppress a positive term elsewhere. This is a conservative lexical guard, not a claim to understand natural language, identity, or the child's actual wishes. AI remains the primary BYOK discovery source and Nothing remains available.
- The bundled web prototype's World provider is a fictional in-memory demo. Its reference call passes the same fictional public query as approval only for that local demo; a networked client must obtain a real per-call review first.

## Alternatives

Sending private interests to the World service for server-side matching was rejected. Trusting a service's `childPull` flag or every keyword occurrence was rejected. Replacing the dynamic service with static Packs was rejected. A comprehensive sentiment classifier was not adopted because it would overstate certainty and create a new privacy/model dependency.

## Security, privacy, and compatibility

The allowlist closes the known JavaScript query-shape gap but cannot detect a name or exact address typed into an allowed free-text `region`; the client must show that value before approval. Existing third-party JavaScript providers that return pre-mapped candidates without public `topics` will no longer be selected by the reference flow; they should add topic metadata or adapt to the versioned World Brief feed. The Android wire feed remains `org.foe.world-brief-feed/v1`; no private context is added to its request. Both clients keep public services and Packs separate.

## Rollback and exit

If matching suppresses a useful public item, a future replaceable Policy/Source may offer an explicit family review path, but must not silently infer child pull from a refused or untagged record. Do not remove exact query approval or send private interest to a public World service as a rollback. Future WorldBriefProvider versions should converge on the versioned public feed and conformance fixtures rather than treating this pre-mapped JavaScript path as the final protocol.
