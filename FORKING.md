# Forking Jianyu

## 1. Forkability is a product requirement

Apache-2.0 grants the legal ability to fork. Jianyu also aims to make that ability operational: documented formats, replaceable extensions, no mandatory upstream service, brand isolation, deterministic migrations, compatibility fixtures, and family-controlled export.

A fork should be able to survive if upstream disappears. A family should be able to move between compatible builds without surrendering its data or keys to either maintainer.

## 2. What a viable fork can replace

- product name, localized copy, links, visual tokens, and assets through BrandConfig;
- LLM, Search, World Brief, Sync, Storage, and Notification integrations through Provider;
- consent, ranking, safety, notification, and jurisdictional defaults through Policy;
- dynamic public-world services through WorldBriefProvider and declarative content through Packs;
- client interface and deployment packaging;
- release infrastructure and update channel.

A fork should not need to rewrite event identity, consent history, encryption semantics, export manifests, or family records merely to replace those opinions.

## 3. Fork checklist

1. Choose a distinct product identity and replace all upstream-facing BrandConfig values.
2. Preserve `LICENSE`, applicable notices, copyright, and change notices required by Apache-2.0.
3. Use truthful attribution without implying upstream endorsement; see `BRAND.md`.
4. Publish the fork's own privacy notice, security contact, supported versions, release keys, hosting claims, and dependency policy.
5. Give every new service or provider zero capabilities by default and document its data flow.
6. Keep local-only use, export, deletion, and provider replacement functional.
7. Run schema, migration, extension, encrypted-sync, deletion, recovery, and threat-model conformance suites.
8. Publish all format changes and deterministic migration tools before releasing a writer that emits them.
9. Test import from upstream and export to an independent reader.
10. State clearly which Concept Freeze principles the fork retains or changes.
11. Demonstrate that the reference flow uses only public FOE interfaces and that WorldBriefProvider remains distinct from Pack.

## 4. Compatibility levels

A fork may describe compatibility precisely:

- **Import-compatible:** can import a documented Jianyu export version.
- **Round-trip compatible:** imports and re-exports supported events without loss, including unknown preserved payloads.
- **Sync-compatible:** interoperates at the encrypted-object and checkpoint protocol level.
- **Extension-compatible:** runs a stated version range of Provider, Policy, Pack, or BrandConfig contracts.
- **Opportunity-compatible:** preserves Opportunity Schema, goal provenance, Gate decisions, Diversity semantics, and Nothing.

Do not use a vague “Jianyu-compatible” claim without naming the level and tested versions. Compatibility is a conformance result, not upstream endorsement.

## 5. Schema divergence

Prefer new namespaced event types and additive optional fields. If changing existing meaning:

- assign a new schema/event version;
- preserve original provenance and IDs;
- provide deterministic forward migration;
- document whether reverse migration is possible;
- include golden fixtures and property tests;
- retain unknown events during export/sync;
- never reinterpret consent, deletion, lifecycle authority, or key state silently.

If safe round-trip is impossible, mark the boundary clearly before import and keep an original encrypted export.

## 6. Cryptography and keys

Forks may replace implementations but must avoid inventing cryptographic primitives. Any protocol change needs version negotiation and migration that does not upload plaintext. A fork must never require families to give upstream or fork operators their recovery secret. Key rotation and rewrapping should happen locally.

Changing cipher suites, KDF parameters, signatures, or object framing requires an ADR, test vectors, corrupted/wrong-key cases, and independent review before real data use.

## 7. Hosted forks

A fork may operate a service, but must not present that service as the upstream default. Hosted forks must separately disclose operator identity, visible metadata, retention, subprocessors, legal jurisdiction, incident response, deletion limits, and availability expectations.

Client-side encryption remains mandatory before family data reaches a sync host. If a fork introduces server-side plaintext features, it has crossed a major trust boundary and must label them explicitly, obtain separate authorization, and avoid describing them as equivalent to the upstream privacy model.

## 8. Policy divergence

Open Core does not mean all Policies are endorsed. A fork changing age-stage control, safety, consent, ranking, retention, or Graduation must make the difference visible and version it. It must not reuse upstream branding to obscure the divergence.

Policies that rank children, sell or target family profiles, hide sponsorship, coerce engagement, or turn “comprehensive development” into a score conflict with the Jianyu mission even if the underlying Apache-2.0 code permits modification.

## 9. World Brief ecosystem

Forks can maintain independent WorldBriefProvider services, publisher trust stores, Pack catalogs, and revocation lists. Dynamic Providers may implement search, verification, AI, databases, and push; Packs remain declarative content. Source attribution and licensing travel with records. Installing a Pack or configuring a World Brief source never grants Family Vault access. Catalog inclusion is not endorsement, and paid placement must never be hidden inside ranking.

## 10. Staying upstream-friendly

Keep changes in small, documented layers; contribute neutral Stable Core improvements separately from brand or policy preferences; add ADRs for constitutional changes; preserve portable fixtures; and avoid dependencies on private infrastructure. Upstream contribution is welcome but never required for a fork to remain useful.
