# Provider SDK

Public contracts and boundary checks for LLMProvider, SearchProvider, WorldBriefProvider, SyncProvider, and NotificationProvider. Core refuses dynamic provider execution without an issued, short-lived capability bound to one provider kind, purpose, and explicit data categories.

A capability is not per-call consent. The JavaScript reference Core separately checks the exact reviewed AI context and exact approved public query before LLM, Search, or World calls. Adapters must not use family identifiers or Vault handles; public services receive public constraints only. See ADRs 0016–0017.

`assertPublicWorldQuery()` permits only bounded public fields (`region`, `timeWindow`, `language`, `categories`, `cursor`); callers must separately approve the exact query before a dynamic service is called. A free-text region is not guaranteed to be coarse. `assertWorldBriefFeed()` validates the minimum `org.foe.world-brief-feed/v1` HTTPS transport subset consumed by the Android reference App: bounded attributable records, required public `topics`, optional provider-declared localized `matchTerms`, ISO timestamps, and HTTPS source links. Private interest matching happens in the client after the public feed returns; neither field contains family data.

`createOpaqueSyncObjectId()`, `assertOpaqueSyncObjectId()`, `assertEncryptedSyncObject()`, and `assertSyncProvider()` expose the ADR 0008 ciphertext-only transport boundary to JavaScript implementations. The SDK validates the public shape and bounds; an App must still create bytes through the encrypted-envelope codec before calling a Provider.
