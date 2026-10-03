# Data Schema

## 1. Principles

- Preserve evidence instead of a single authoritative Child Profile.
- Preserve who said, observed, imported, inferred, chose, or rejected something.
- Child, caregiver, and shared goals never collapse into one field.
- Events are append-only, versioned, portable, and deletable through tombstones plus cryptographic erasure.
- Hypotheses carry confidence, supporting and contradicting evidence, model/policy provenance, timestamp, and decay.
- Sparse data and `unknown` are valid. Missing data is not negative evidence.
- AI output is a proposal and never overwrites a human-authored expression.

## 2. Identity model

```text
Person
├── may participate in one or more Households
├── may act as caregiver, child/self, guardian, or other member
└── owns one or more Device identities

ChildSubject
├── references a Person where appropriate
├── can relate to more than one Household
└── has lifecycle-specific ownership and visibility rules
```

Do not hard-code father/mother or assume one unified household opinion. Records use opaque local IDs and explicit authorship.

### Child lifecycle date

New Android records keep an ISO-8601 local birth date so a lifecycle boundary changes on the person's birthday rather than on January 1:

```json
{
  "id": "subject-local-id",
  "memberId": "member-local-id",
  "displayName": "本人称呼",
  "birthYear": 2013,
  "birthDate": "2013-09-15",
  "createdAt": "2026-09-15T08:00:00Z"
}
```

`birthDate` is local-only sensitive metadata introduced by `org.jianyu.family-vault/v5`. Provider requests derive an age band and lifecycle stage; they do not disclose the exact date. `birthYear` remains during the v5 compatibility window so older vaults and replaceable clients can still decode a child record. v2/v3/v4 states migrate deterministically to v5 without inventing a month or day. When `birthDate` is absent, clients use the legacy year-only approximation and label that limitation in the UI. A later schema migration may remove `birthYear` only after fixtures prove all supported readers understand `birthDate`.
For a February 29 birth date, the completed-year age and authority stage advance on March 1 in a non-leap year, as chosen for this project. This applies to display and permission boundaries, not only birthday wording.

## 3. Event envelope v1

```json
{
  "schema": "org.foe.event/v1",
  "eventId": "evt_01JEXAMPLE",
  "eventType": "evidence.observed",
  "eventVersion": 1,
  "householdId": "household_demo",
  "subjectId": "person_child_demo",
  "authorId": "person_caregiver_demo",
  "actorRole": "caregiver",
  "deviceId": "device_demo_a",
  "occurredAt": "2026-09-12T10:30:00+08:00",
  "recordedAt": "2026-09-12T10:31:00+08:00",
  "causationId": null,
  "correlationId": "flow_demo",
  "ownership": "author",
  "visibility": "family",
  "purposes": ["opportunity-recommendation", "longitudinal-record"],
  "consentRefs": ["consent_demo"],
  "policyRef": {"id": "jianyu-default", "version": "0.1.0"},
  "payload": {},
  "integrity": {"previousEventHash": null, "eventHash": "base64url"}
}
```

Visibility values include `private`, `selected-members`, `guardians`, `family`, and `recommendation-only`. The last permits a bounded local calculation without exposing raw content to other members. Actual enforcement requires key and authorization design, not a UI flag alone.

The Android reference App writes a `provider.disclosure-approved` v1 event into the encrypted Family Vault **before** a formal external discovery request. Its payload records `requestId`, recipient, purpose, included/excluded category IDs, retention choice, `rawValuesStored=false`, and `deliveryStatus=not-confirmed`; it contains no prompt or raw field values. A failed approval write aborts the external request. The later `provider.context-disclosed` v1 event records the computed per-source scope after discovery returns, including when a source failed or was locally skipped; neither event proves network delivery or third-party retention. A failed later write can leave an approval without a completion receipt; the UI warns about possible prior delivery and duplicate sending on retry. Both events keep the actor and visibility appropriate to the lifecycle stage. The `coarse-region` category ID is retained for compatibility, but a user-entered region is not automatically verified as coarse or stripped of names and addresses.

Provider-disclosure events must carry the actual per-call approver as author. In the 13–15 hand-over stage, the Android reference App signs the disclosure receipt as the young person and marks the receipt shared-with-child; it must not silently substitute the caregiver's active recorder. This is authorship/projection metadata, not proof that the shared-device holder was cryptographically authenticated.

## 4. Evidence

```json
{
  "kind": "direct-observation",
  "expression": "今天他主动研究了半小时赛车调校",
  "topicHints": ["motorsport", "tuning"],
  "motivationHints": ["optimization", "competition"],
  "interpretation": "可能对调校比观看比赛更有兴趣",
  "certainty": "uncertain",
  "sourceRef": null
}
```

`kind` is one of `child-stated`, `child-choice`, `direct-observation`, `caregiver-interpretation`, `teacher-feedback`, `assessment`, `imported-claim`, or `ai-inference`. Interpretation is never stored as direct fact. Multiple observers may contradict one another without destructive merge.

## 5. Hypothesis

```json
{
  "hypothesisId": "hyp_demo",
  "statement": "近期可能更喜欢动手调校，而不只是观看赛车",
  "status": "tentative",
  "confidence": 0.58,
  "supports": ["evt_supporting_1", "evt_supporting_2"],
  "contradicts": ["evt_contradicting_1"],
  "derivedBy": {"kind": "model", "provider": "demo", "model": "synthetic"},
  "policyRef": {"id": "hypothesis-default", "version": "0.1.0"},
  "validFrom": "2026-09-12T10:31:00+08:00",
  "reviewAfter": "2026-10-12T10:31:00+08:00",
  "decay": {"kind": "evidence-age", "halfLifeDays": 45}
}
```

A child correction can reject or qualify a hypothesis without deleting the historical fact that it once existed. No hypothesis field is named intelligence, personality type, potential, obedience, development score, or predicted outcome.

### Shared timeline projection

The Android caregiver/shared-device timeline is a derived view, never the raw `FamilyState`. It omits `CHILD_PRIVATE` evidence and any hypothesis whose supporting or contradicting evidence is hidden, missing, or unresolved. Events use a fail-closed visibility allow-list; linked choices are omitted when their source, choice, or later response event is restricted. Broader labels on later events cannot disclose a restricted choice. Hidden totals are not exposed. This projection does not change storage, ownership, export, recovery, or encryption and must not be described as a child-only cryptographic space. See ADR 0012.

## 6. Goals and context request

```json
{
  "occasionId": "occasion_demo",
  "goals": [
    {"owner": "child", "value": "想把赛车调得更快", "source": "child-stated"},
    {"owner": "caregiver", "value": "希望以后能连接速度与力", "source": "caregiver-stated"},
    {"owner": "shared", "value": "周末一起做点有意思的", "source": "joint"}
  ],
  "constraints": {
    "timeMinutes": 120,
    "costBand": "low",
    "caregiverEnergy": "low",
    "travelMinutesMax": 30,
    "locationPrecision": "city-district",
    "materials": ["computer"],
    "screenPreference": "allowed",
    "accessibility": []
  },
  "schoolWindow": [
    {"topic": "speed-and-time", "startsInWeeks": 3, "required": false}
  ]
}
```

The concrete schema must validate structured topic identifiers; the example remains conceptual until ADR-0001 selects serialization. School connections are optional enrichments, not hard requirements.

## 7. Opportunity schema v1

```json
{
  "schema": "org.foe.opportunity/v1",
  "opportunityId": "opp_demo",
  "title": "一起调一辆车，看看哪里真的更快",
  "entryPoint": {
    "motivation": "optimization",
    "whyNow": "孩子最近主动比较赛车调校",
    "startupCost": "low"
  },
  "ecosystem": "digital-game",
  "goalAlignment": {"primary": "child", "secondary": ["shared"]},
  "participants": ["child", "caregiver"],
  "requirements": {
    "timeMinutes": 60,
    "costBand": "free-existing",
    "caregiverEnergy": "low",
    "travelMinutes": 0,
    "materials": ["existing-racing-game"]
  },
  "source": {
    "kind": "pack",
    "publisher": "org.foe.demo",
    "retrievedAt": "2026-09-12T10:00:00+08:00",
    "url": null
  },
  "verification": "idea",
  "freshUntil": null,
  "realityFidelity": {
    "overall": "simplified-model",
    "notes": ["游戏中的轮胎与空气动力学模型可能被简化"]
  },
  "risks": [],
  "possibleConnections": [
    {"topic": "speed-and-time", "mode": "cognitive-anchor", "mustTeach": false}
  ],
  "explanation": "不用讲公式，先让比较和试错本身成立",
  "sponsorship": null
}
```

Verification is `verified`, `likely`, or `idea`. A time-sensitive real-world opportunity must include source, retrieval time, event time, location, and what the family still needs to confirm.

`ecosystem` is a stable machine identifier rather than display copy. The v1 recommended set is:

```text
existing-interest  digital-game  media          reading
sport              making       family-life    nature
travel             place        people         real-world
world-event        digital-making              real-project
other
```

Clients localize these IDs for families. The reference engine accepts documented legacy English and Chinese aliases, canonicalizes them before Gate/diversity evaluation, and maps an unknown extension value to `other`. This prevents aliases such as `making` and `动手制作` from being counted as two different ecosystems. `nothing` is reserved for the explicit first-class Nothing option and is not a provider-generated candidate ecosystem.

`Nothing` is represented as an explicit option type with a reason such as `no-natural-entry-point`, `family-energy-low`, `recent-intervention-load`, `child-veto`, or `insufficient-reliable-information`.

## 8. Gate decision

```json
{
  "decisionId": "gate_demo",
  "opportunityId": "opp_demo",
  "result": "allow",
  "reasons": ["fits-time", "fits-energy", "child-led", "low-startup-cost"],
  "warnings": ["simplified-reality-model"],
  "policyRefs": [
    {"id": "opportunity.jianyu-default", "version": "0.1.0"}
  ]
}
```

Gate results are preserved so a future Policy can reinterpret the same candidates without pretending its conclusions existed earlier.

The Android reference result model also distinguishes `eligibleNotSelected`: candidates that passed the Gate but were left out by the replaceable Diversity Selector. They are neither visible options nor Gate rejections. This optional, non-durable result field defaults to empty when decoding older results; it does not change Event or Opportunity Schema v1. The public JavaScript reference keeps all evaluations in its result alongside the selected set.

## 9. Decision and feedback

Family decisions are `selected`, `edited`, `rejected`, `deferred`, or `nothing`. Child response is separate from caregiver adoption. Lightweight feedback includes `liked`, `neutral`, `disliked`, `want-again`, `do-not-want-again`, or free text. Lack of feedback has no negative meaning.

The Android reference app's `opportunity.feedback-recorded` eventVersion `2` adds `payload.responseSource`: `child-signed` or `caregiver-relayed-child-view`, alongside `choiceId` and `value`. The event's `actorRole` must agree with that source. These values preserve how a view was recorded; a shared-device signature is not identity authentication, and neither a choice nor a view proves participation. The legacy `FamilyChoice.feedback` field remains readable, but a v1 or unmatched feedback event has unknown provenance and is excluded from later AI context. The single-field v0.1 choice projection is not a substitute for a future multi-author child-response/caregiver-adoption model.

For 10–12 共选, the reference App now marks the caregiver-authored `opportunity.chosen` / `opportunity.nothing-chosen` / confirmed-veto event and a caregiver-relayed child-view event `shared-with-child`, matching that stage's shared observation boundary. Authorship remains the caregiver's and feedback retains `caregiver-relayed-child-view`; visibility does not prove that the child saw the screen, agreed, participated, or owns an independent decryption key. Earlier events keep their original provenance and visibility.

Deleting one saved choice removes its active `FamilyChoice` projection and every same-subject event with that `choiceId`, including the original choice and later response. It adds a v1 `CHOICE` tombstone and v1 `EVENT` tombstones for the linked events, plus a title-free `opportunity.choice-deleted` v1 audit event containing opaque target and tombstone IDs only. The earlier interest evidence is independent and remains. A restricted source keeps the deletion audit restricted. The shared Timeline currently offers this action only for visible choices; this does not create independent access to hidden subject records. See ADR 0026.

## 10. Assessment Event

```json
{
  "subject": "mathematics",
  "assessmentKind": "midterm",
  "score": 92,
  "maximum": 100,
  "comparison": {"classAverage": 81, "percentile": null},
  "topics": ["functions", "geometry"],
  "source": "caregiver-entered",
  "notes": null
}
```

All comparison fields are optional. Assessment projection may show time association with other events but never computes a global child score or attributes causality to Jianyu.

The Android reference App records this as an authored `SCHOOL` / `ASSESSMENT` Evidence object plus an `assessment.recorded` Event whose payload retains the structured fields above. The Evidence summary is only a human-readable projection; it is not the canonical comparison model. A 13–15-year-old must explicitly confirm retention, and Graduation rejects new caregiver-side assessment records.

## 11. Initial event families

- identity and household: creation, membership, guardianship, device enrollment;
- lifecycle and authority: stage review/transition, ownership transfer, Graduation;
- evidence and hypotheses: observation, expression, child correction, derivation, expiry;
- goals and constraints: child/caregiver/shared goals, occasion context;
- opportunities: candidate import, Gate decision, option set, family choice, child response;
- assessment and reflection: scores, teacher feedback, later connection, autonomous follow-up;
- providers and Packs: configuration, capability grant/revoke, receipt, install/update/disable;
- vault and sync: key rotation, bundle export/import, checkpoints;
- deletion: record deletion, subject erasure, tombstone acknowledgment, key destruction.

## 12. Versioning, merge, deletion, and export

The Android reference implementation currently projects `org.jianyu.family-vault/v5` and defines a provider-neutral `org.foe.sync-frame/v1` before adding any network transport. A cleartext frame exists only inside an authorized device, before independent client-side encryption:

```json
{
  "schema": "org.foe.sync-frame/v1",
  "body": {
    "schema": "org.foe.sync-frame-body/v1",
    "frameId": "opaque-local-id",
    "household": {"id": "household_demo"},
    "deviceId": "device_demo_a",
    "sequence": 2,
    "previousFrameHash": "sha256-hex",
    "createdAt": "2026-09-14T10:00:00Z",
    "members": [],
    "children": [],
    "evidence": [],
    "hypotheses": [],
    "choices": [],
    "events": [],
    "tombstones": [],
    "retiredDeviceIds": []
  },
  "contentHash": "sha256-hex"
}
```

The current hash chain detects corruption, rollback, gaps, and forks relative to an accepted per-device cursor. It is not a device signature or enrollment proof. Before a transport can observe the frame, the Android reference codec wraps its complete canonical bytes as:

```json
{
  "format": "org.foe.encrypted-sync-envelope/v1",
  "cipher": "AES-256-GCM",
  "keyId": "opaque-random-key-id",
  "nonce": "base64url-96-bit-nonce",
  "ciphertext": "base64url-authenticated-sync-frame"
}
```

`keyId` is a random lookup label, never a household, child, member, device, or account identifier. Associated data binds the format, cipher, key ID, and `sync-frame` purpose. The household sync key is not serialized. Wrong keys, modified ciphertext, modified associated data, unknown fields, invalid UTF-8/Base64, size violations, and invalid decrypted frames fail closed. Transport adapters use random object names and must never expose cleartext frame JSON, semantic IDs, hashes, or tombstones as storage metadata. See ADR 0006. Household key distribution, device signatures, rotation/revocation, and acknowledged checkpoints remain separate unfinished protocols.

An experimental device-join invitation/request/approval-evidence v1 transcript is specified in [docs/DEVICE-JOIN-PROTOCOL.md](docs/DEVICE-JOIN-PROTOCOL.md). It binds two public signing keys and fresh challenges to an expected household, but it is not an enrolled-device roster, a human-identity proof, a key-transfer mechanism, or authority to accept sync frames.

- Envelope and payload versions evolve independently.
- Migrations are deterministic, idempotent, offline-capable, and fixture-tested.
- Concurrent evidence coexists; wall-clock last-write-wins cannot resolve consent, visibility, membership, Graduation, deletion, or key state.
- Same-ID/different-content conflicts fail closed. The exact last frame is idempotent; sequence gaps, forks, rollbacks, and frames from locally retired devices fail closed.
- Revocation and deletion dominate older grants during projection.
- Attachments and sensitive record groups use independently wrapped keys where practical.
- Deletion removes projections and derivatives, propagates a minimal tombstone, requests ciphertext deletion, and destroys the narrow key when physical erasure cannot be proven.
- Restored backups replay newer tombstones before use.
- Export supports canonical JSON plus appropriate CSV, Markdown, and HTML views, includes schemas and provenance, and works without an upstream service.

## 13. Graduation archive v2

At 16+, an explicitly subject-authorized export projects only records about that person into `org.foe.graduation-archive/v2`:

```json
{
  "schema": "org.foe.graduation-archive/v2",
  "archiveId": "opaque-random-id",
  "createdAt": "2026-09-14T08:00:00Z",
  "subject": {
    "subjectId": "subject-local-id",
    "memberId": "member-local-id",
    "displayName": "本人称呼",
    "birthYear": 2010,
    "birthDate": "2010-09-15"
  },
  "authors": [
    {"memberId": "member-local-id", "role": "CHILD"},
    {"memberId": "caregiver-local-id", "role": "CAREGIVER"}
  ],
  "evidence": [],
  "hypotheses": [],
  "choices": [],
  "events": [],
  "tombstones": [],
  "humanReadableMarkdown": "# 本人的见隅资料"
}
```

The author table preserves roles without copying other members' display names. Sibling records and unrelated household events are excluded. v2 adds the optional exact `birthDate`; readers continue to accept v1 archives whose subject carries only `birthYear`. The archive is sealed inside `org.foe.encrypted-graduation-bundle/v1` with a new independent recovery key; its envelope reveals no subject identity or record count. Export has no implicit delete, transfer, account creation, or future consent effect. See ADR 0007 and ADR 0011.

## 14. Graduation retention tombstones

Graduation retention remains separate from export. `org.foe.deletion-tombstone/v2` adds a subject-content scope:

```json
{
  "schema": "org.foe.deletion-tombstone/v2",
  "tombstoneId": "opaque-id",
  "householdId": "household-id",
  "targetType": "SUBJECT_CONTENT",
  "targetId": "subject-id",
  "subjectId": "subject-id",
  "authorId": "subject-member-id",
  "deviceId": "authorized-device-id",
  "deletedAt": "2026-09-15T08:00:00Z",
  "reasonCode": "graduation-relationship-only"
}
```

`SUBJECT_CONTENT` suppresses all evidence, hypotheses, choices, and subject-scoped events while retaining the `Child` and child-role `FamilyMember` relationship. `SUBJECT` additionally suppresses those relationship objects. Both dominate stale synchronized data. The v2 subject-content form requires `subjectId == targetId`; implementations that do not understand the scope must fail closed. Active-vault removal is implemented, while subject-separated key destruction and physical removal of old ciphertext remain future work. See ADR 0010.
