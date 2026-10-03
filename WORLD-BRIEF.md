# World Brief Ecosystem

## 1. Purpose

World Brief gives a local family client a trustworthy view of what is happening in the public world: events, exhibitions, sport, astronomy, science and technology, cultural releases, public resources, places, and time-sensitive opportunities.

It answers:

> What is happening in the world, where, when, for whom, and how certain is that information?

It does not normally answer:

> What should this particular child do?

That second decision requires private family context and belongs in the local Opportunity Engine.

## 2. Privacy-preserving split

```text
Public web / APIs / editorial sources
                 ↓
Third-party World Brief Service
search + entity resolution + freshness + verification + summarization
                 ↓
public World Brief protocol
                 ↓
Jianyu or another FOE client
                 +
local Family Vault context
                 ↓
local Discovery / Gate / Diversity
                 ↓
family options
```

A typical request may include a city or coarse region, time window, language, accessibility needs that reveal no child identity, and public categories. It does not include names, exact addresses, child notes, assessment history, private interests, or Family Vault identifiers.

The reference Engine does not fetch a World Brief without a matching per-request `PublicWorldQuery` approval. That check covers the region, time window, language, and categories before network access; it does not sanitize free-text region input, so the client must still show the exact value to the person approving it.

If a hosted product offers cloud personalization, that is a distinct higher-trust mode requiring explicit disclosure, authorization, retention rules, and a claim that cannot be presented as equivalent to the default local-matching design.

## 3. Service capabilities

Third parties are invited to build services that:

- continuously collect and normalize public opportunities;
- detect duplicates and resolve places/events across sources;
- record original source, retrieval time, event time, location, price, age/access requirements, and booking state;
- distinguish `verified`, `likely`, and `idea`;
- label these verification states as the provider's assertions in a client until independent publisher trust and checking exist; even `verified` needs a family recheck before attendance or purchase;
- detect expired events and changed details;
- disclose sponsorship and paid placement;
- offer regional, language, cultural, accessibility, or domain specialization;
- support public subscriptions and minimal push triggers;
- publish open or commercial implementations behind the same protocol.

The upstream project supplies a protocol, fixtures, reference client, and conformance tests. It does not currently operate the service.

## 4. World Brief record

Each record includes a stable source-scoped ID, title, description, categories, location precision, start/end time, age/access constraints, cost, participation requirements, source links, retrieved/verified times, verification state, license/usage rights, sponsorship, tracking warning, and content safety metadata.

The reference flow does not promote every public record into a family recommendation. A provider fetch uses public constraints only; after records return to the authorized device, a local source matches declared `topics` and optional `matchTerms` against the current, explicitly supplied interest. `topics` remain the public subject labels; `matchTerms` are provider-declared aliases in the requested language, so an English `moon` record can match a Chinese “想看月亮” without sending that private sentence to the service. Past evidence and outcomes are not topic triggers: a previous child veto or disliked attempt may mention a topic without expressing current child pull. A topic or alias in a common explicit-refusal clause also cannot become positive child pull; if any advertised term is explicitly refused, the automatic match is suppressed. A plainly caregiver-led plan or obvious external background alone likewise does not trigger an external discovery call or become `childPull`. A separate child-originated clause remains eligible. Records with no current positive match remain public feed data and do not acquire `childPull` merely because the service returned them. These terms are untrusted publisher assertions and this lexical guard is intentionally incomplete and conservative, not proof that a child truly wants the event; the family must judge the actual suggestion.

The Android reference adapter currently accepts an HTTPS JSON feed with this minimum interoperable envelope:

```json
{
  "schema": "org.foe.world-brief-feed/v1",
  "briefs": [{
    "id": "publisher-scoped-id",
    "title": "Public event title",
    "summary": "Attributed summary that does not replace the source",
    "topics": ["astronomy", "moon"],
    "matchTerms": ["月亮", "观月"],
    "region": "coarse region",
    "startsAt": "2026-09-20T12:00:00Z",
    "expiresAt": "2026-09-20T16:00:00Z",
    "sourceTitle": "Original publisher",
    "sourceUrl": "https://example.org/original",
    "retrievedAt": "2026-09-14T00:00:00Z",
    "verification": "VERIFIED",
    "timeMinutes": 90,
    "costBand": "FREE",
    "caregiverEnergy": "LOW",
    "travelMinutes": 20,
    "minAge": 8,
    "maxAge": 14,
    "bookingRequired": true,
    "verificationNotes": ["Confirm remaining places", "Confirm adult supervision"],
    "sponsorship": "Sponsored by the named organizer",
    "trackingWarning": true
  }]
}
```

Requests contain only `region` when supplied, the fixed coarse window `next-14-days`, language, and public categories. They never contain the current interest, household life description, evidence, names, or vault identifiers. The Android client caps response size and record count, requires topics and provenance, validates timestamps, strips unsafe source URLs, and does the private match locally. `matchTerms` is optional within feed v1; older feeds still match on `topics` alone. Both lists are bounded to 20 short public strings, and malformed declared terms drop the record. The JavaScript reference compatibility flow also requires public topic metadata for pre-mapped candidates and an exact approved public query; it skips the World call when the approval is absent or changed (ADR 0016). The free-text region can still contain private information entered by a person and must be reviewed before approval.

The reference Opportunity Policy (`0.2.10`) fails closed on an invalid or elapsed `expiresAt`. For `world-brief` candidates it also rejects missing/invalid retrieval time, retrieval time more than 14 days old, or a retrieval time more than five minutes in the future. The Android Policy also rejects unsafe source-link destinations. A BYOK AI candidate that claims to be a time-sensitive `world-event` but supplies no checkable source link is rejected locally; the built-in AI adapter never manufactures such a link. This is not a blanket ban on World Brief ideas or a claim that an AI-supplied URL proves an event exists. These are versioned reference-client rules, not claims that an event remains available for those 14 days. The family must still check booking, cancellation, weather, and transport against the original source. Forks may replace the Policy through the public extension point while preserving truthful freshness and provenance labels.

The Android reference subset also understands practical and trust fields: duration, cost band, caregiver effort, travel time, age range, booking requirement, verification notes, sponsorship disclosure, and a source-tracking warning. Omitted optional fields receive conservative compatibility defaults. If a provider supplies one of these fields with the wrong type, invalid enum, impossible range, or malformed timestamp, the client drops that record instead of presenting an invented default as sourced fact. The recommendation UI exposes booking, verification, sponsorship, and tracking caveats before a family opens the original source. Trust-store signatures, publisher revocation, licensing metadata, accessibility metadata, and content-safety metadata remain protocol work rather than current implementation claims.

Generated summaries identify the model or editorial process. A summary cannot replace the original source. Time-sensitive records state what still requires family verification, such as availability, tickets, weather, supervision, or transport.

## 5. Pull, background refresh, and hosted watch

- The upstream reference app is pull-first and refreshes on open.
- When the operating system allows, it may request background refresh, scheduling, or optional startup permission.
- Denied background permission only delays refresh; it does not break the local product.
- A third-party hosted service can provide always-on discovery and push.
- Previously known events can use local notifications without exposing family context.
- Notifications are explicitly subscribed, rare, and time-sensitive—not a daily engagement feed.

## 6. Pack relationship

A Pack is a signed/versioned declarative snapshot or knowledge bundle. A World Brief service may publish Packs for offline regions or periodic distribution, and a client may ingest both. They remain separate extension points:

- Provider: dynamic service contract;
- Pack: editable declarative content contract.

## 7. Neutral ecosystem rules

- No publisher gets Family Vault access by joining the ecosystem.
- Paid placement is labeled and cannot silently override Opportunity Policy.
- Multiple providers can coexist; the client preserves source disagreements.
- A provider's disappearance cannot delete family-authored decisions.
- Official catalog inclusion is not an educational, legal, safety, or quality guarantee.
- Forks may maintain their own providers, catalogs, trust stores, and verification rules.
