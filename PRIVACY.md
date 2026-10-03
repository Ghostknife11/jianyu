# Privacy

## 1. Promise

Jianyu exists to expand a family's opportunity space, not to build a permanent dossier about a child. Privacy is expressed through local authority, data minimization, meaningful consent and assent, age-appropriate control, provider isolation, deletion, portability, and Graduation.

This document describes product requirements. Deployers and forks are responsible for accurate notices and applicable law in their jurisdictions; this is not legal advice.

## 2. Roles and scope

The current upstream project provides open-source software and does not operate an official family data server. A family running the client controls its vault. A selected AI, storage, or content service processes only the information intentionally sent to it under that service's terms.

Third-party hosts and forks must publish their own identity, purposes, data map, subprocessors, retention, security claims, rights workflow, and contact information. They may not rely on this file as a substitute for an accurate service notice.

## 3. Data principles

- **Purpose limitation:** collect or disclose data only for a user-visible family purpose.
- **Minimum necessary:** prefer an age band over a birth date, a travel radius over an address, and a short stated interest over a full activity history.
- The Android family vault may keep an exact birth date locally to switch lifecycle authority on the correct birthday. Context Firewall derives only age/lifecycle information for providers and never sends that date by default. Legacy vaults with only a year remain readable and are shown as approximate.
- **Provenance:** distinguish a child's words, caregiver observations, imported claims, AI inferences, and human decisions.
- **No covert profiling:** no advertising profile, sale of family data, cross-family scoring, or hidden engagement optimization.
- **No outcome standardization:** never rank children against each other or promise uniform results.
- **Agency over accumulation:** saving is optional where possible; drafts can remain ephemeral.
- **Goal separation:** a child's goal, a caregiver's goal, and a shared family goal are recorded separately rather than blended into one optimization target.
- **No completeness requirement:** sparse histories and quiet weeks are normal; missing data is not a deficit.
- **Local comprehension:** controls and notices must be understandable without reading server documentation.
- **Exit by design:** export, delete, revoke, and graduate without vendor permission.

## 4. Expected data categories

The MVP may store locally:

- household membership and local roles;
- age band and lifecycle stage, with exact birth date avoided unless genuinely required;
- interests, preferences, constraints, reflections, and opportunity decisions;
- saved public opportunity data and its provenance;
- consent, assent/objection, provider capability, and deletion records;
- encrypted attachments explicitly added by a family;
- device, sync, integrity, and recovery metadata.

The MVP does not need advertising identifiers, contact graph scraping, passive location history, biometric identification, ambient recording, school-system credentials, medical diagnoses, intelligence scores, or cross-family comparison data.

## 5. Age lifecycle and control

### Ages 4–6: co-play

The caregiver controls setup while the child participates through shared activities. The interface avoids evaluative labels and does not invite a private relationship with an AI. Save only what serves a clear family purpose.

### Ages 7–9: accompany

Children can express likes, dislikes, and corrections in plain language. The product explains what will be saved and supports “do not save this.” Caregiver involvement remains visible.

### Ages 10–12: co-select

Children and caregivers receive meaningful options, recommendation reasons, and a way to disagree. Provider disclosure and durable profiling require a purpose-specific decision rather than blanket acceptance.

The reference App marks a 10–12 family choice and a caregiver-relayed child view as shared-with-child while retaining the caregiver's actual signature. On the current shared-device Vault this is provenance and projection metadata, not authenticated proof of joint participation or a child-held private key.

### Ages 13–15: hand over control

Teenagers gain granular privacy, editing, sharing, export, and deletion controls consistent with applicable caregiver duties. Private drafts are not automatically visible to caregivers or providers. Exceptions must be explicit, narrow, and explained.

The Android shared timeline now uses a fail-closed privacy projection: child-private evidence, restricted audit events, choices linked to restricted events, and hypotheses that depend on hidden or unresolved evidence are omitted together. The screen reveals only that some restricted material is not shown, never its count or subject. This is presentation isolation on a shared device, not authenticated child-only access: the current household vault and recovery bundle still use household-controlled key material. Independent child identity and subject-separated keys remain required before the App can claim cryptographic privacy from caregivers.

### 16+: Graduation

The product stops treating the person as a child subject by default. It offers:

1. complete deletion and key destruction;
2. a portable export for the person;
3. selective transfer into a new, self-controlled context only after fresh authorization;
4. an optional minimal household record that contains no child profile.

Graduation is not an automatic transfer of caregiver-held history into an adult product.

The experimental Android reference flow now implements the portable archive plus two separately confirmed household-copy controls. The person may keep the vault read-only, clear their history while retaining only the family relationship, or remove their subject relationship and history from the active household copy. A v2 subject-level tombstone prevents stale synchronized records from reappearing. The UI does not require export before deletion and does not treat age transition as consent. Shared-device confirmation is not cryptographic identity proof; subject-separated key destruction, physical removal of old ciphertext, a new self-controlled key space, and selective transfer remain unfinished.

## 6. Consent and assent

Consent is specific to purpose, data category, recipient, duration, and actor authority. It is not bundled into general terms, inferred from silence, or made irrevocable. Children receive explanations and choices appropriate to their stage. Their objection is recorded and respected unless a narrowly documented legal or safety duty requires otherwise.

Revocation stops future use and queued disclosure. The client explains that a third-party provider may already retain previously submitted context under its own policy and gives the family the information needed to pursue deletion there.

## 7. External AI and BYOK

Families choose and configure providers using their own keys. Before a provider call, the Context Firewall generates the smallest useful task view. The UI identifies the provider, purpose, included categories, and significant exclusions; higher-risk disclosures require confirmation. Provider output is labeled as generated and is never silently converted into fact.

For Android formal discovery, a category-only approval record is encrypted and saved locally before an external request starts. If that save fails, the request stops. The record does not contain the raw text and does not prove that a service was contacted; a later receipt describes the request scope when discovery returns. If that later receipt cannot be saved, the app keeps the earlier confirmed record and warns that a service may already have received the approved information. Retrying can send it again.

The app may calculate a seven-day selected-option count locally to offer a rest reminder. Selection is not evidence of participation or liking. This count stays out of AI requests by default and is included only when the family explicitly enables recent-history context for that individual discovery; the disclosure then names the category.

For the built-in connection examples, the Android settings page links to the provider's published terms and data-handling information. A custom compatible endpoint has no reliable universal terms URL, so the page tells the family to check that operator's current terms and data practices separately. The optional public-sample compatibility probe sends fixed fictional content only after an explicit tap and may incur a provider charge; it does not inspect Family Vault data or certify a model.

BYOK improves choice and separation but does not make a provider private by itself. Families must be able to compare local-only and networked options and see links to the selected provider's current terms.

The Android offline demonstration is a fixed-template preview, not an AI call or a recommendation about the child. Its input and previewed selection, refusal, or 留白 do not create new Family Vault records. Earlier experimental builds could save demo material; the current recommendation-context projection excludes identifiable legacy demo observations and template choices from future AI requests, but does not silently erase those existing vault records.

System speech recognition is a separate external-processing boundary. The Android reference App asks before opening the device-provided recognizer, requests offline recognition when available, does not request its own microphone permission, and does not retain audio. Offline preference is not a guarantee: the operating system or selected recognition service may still process audio under its own settings and terms. Returned text is bounded and remains an editable, unsaved, unsent draft until the family separately chooses local retention and approves any AI disclosure.

## 8. Synchronization and hosting

NAS, WebDAV, S3-compatible storage, or third-party hosts receive client-encrypted objects only. The sync layer must not need plaintext names, interests, notes, attachments, or provider prompts. Transport metadata that remains visible—such as IP address, timing, object size, and opaque vault identifiers—must be documented honestly.

Sync is optional. Local-only use remains a supported mode. The official project currently operates no family server.

The Android reference App includes a manually triggered encrypted-folder developer preview. Granting a document tree creates a transport directory but sends no family data until the user separately chooses to transfer. Family State is framed and encrypted on the client before the document provider receives random object names and ciphertext. The provider can still observe the selected folder, object count, sizes and access times. Folder URI, device identifier and shared-key material remain in a separate Android-Keystore-encrypted local setting and are not family evidence, AI context or World Brief data. Because authenticated device enrollment, key transfer, signatures, checkpoints and compaction are unfinished, this preview is not a substitute for the separately protected recovery bundle or a production sync claim.

## 9. Retention and deletion

Defaults favor bounded retention for AI inferences, rejected recommendations, provider receipts, logs, and temporary processing. User-authored expressions and accepted opportunity history remain until the family deletes them or selects a retention rule. Every retained category must have a visible reason.

Deletion removes active projections and derivatives, synchronizes a minimal tombstone, requests remote ciphertext removal, and destroys narrow data keys when physical deletion cannot be verified. A restored backup must apply later tombstones before presenting data.

The Android reference App currently lets a family separately confirm deletion of a visible saved choice. It removes the choice and its linked choice/response events from the active local vault while retaining the earlier interest evidence, and records content-free tombstones so a later merge cannot restore those projections. The shared Timeline and optional recent AI history exclude choices linked to restricted events even if a later event was labeled more broadly. This is not physical erasure: previously encrypted frames or copied bundles may still contain ciphertext, and production remote cleanup and narrow-key destruction are not implemented. The shared-device subject confirmation at 13+ is not independent identity authentication. See ADR 0026.

No software can erase an export another person intentionally copied or plaintext already sent to an external provider. The product must disclose that boundary rather than imply magical deletion.

## 10. Portability and correction

Families can inspect stored information, correct provenance or content without rewriting history invisibly, and export in documented formats. The export includes schema and provenance needed to interpret records outside Jianyu. No account with an upstream service is required to access a local vault.

The Android portable recovery-bundle import checks the selected ciphertext and recovery code on-device before any replacement. It shows the current and recovered household names for a separate confirmation, never merges histories, and refuses to replace if the local vault changed after that preview. A wrong recovery code does not overwrite existing data. Household names are only human hints, not proof that two bundles belong to the same people.

## 11. Research, analytics, and model training

The MVP sends no product telemetry by default and does not use family data to train shared models. Any future research, analytics, or training program requires a separate proposal, explicit opt-in, clear withdrawal and deletion behavior, a data minimization review, and an option that does not degrade core product access. “De-identified” must not be used as a blanket justification for exporting rich child histories.

## 12. World Brief ecosystem

WorldBriefProvider services aggregate and verify public-world information and normally receive no child history or Family Vault identifier. Requests should use limited public constraints such as region, time window, language, and category. The Android reference App sends the optional region text as entered after preview and approval; the Engine sends no World Brief request unless the region, time window, language, and categories match the approved query exactly. The App does not automatically guarantee that region text is coarse or free of personal details. Local matching joins that public feed with private family context where possible.

Pack publishers provide public or licensed declarative opportunity content and receive no Family Vault access merely because a Pack is installed. Packs and World Brief services are distinct extension points. Network links, tracking behavior, source dates, sponsorship, and conflicts of interest must be disclosed in manifests and UI.

Opening an original-source link leaves the app and can disclose the device's network visit to that website, even if the publisher did not mark a tracking warning. The Android reference app shows the destination host and asks before opening a valid HTTPS link; its `verified` label is explicitly attributed to the source, not a Jianyu certification. This confirmation does not prevent the external website from redirecting or tracking after the person chooses to continue.

## 13. Attention and non-use

Jianyu is occasion-driven. It does not require daily opening, daily recording, complete childhood history, or continual feedback. It does not treat non-use as failure, create streak pressure, count missed opportunities, or send routine prompts designed to produce parental anxiety. Background refresh and notifications are optional; denial delays fresh public information or synchronization but never blocks local records.

## 14. Privacy review checklist

Every new feature must answer:

1. What family purpose does it serve?
2. What is the smallest data needed, and can processing remain local?
3. Who is the actor and subject, and what age-stage authority applies?
4. What leaves the device, to whom, for how long, under which terms?
5. How can the child or family inspect, correct, refuse, revoke, export, and delete it?
6. What new inference, coercion, re-identification, or family-conflict risk appears?
7. Does the feature still work when a provider, pack, host, or brand is replaced?
8. What happens at Graduation?

A feature without satisfactory answers does not enter the Stable Core or MVP.
