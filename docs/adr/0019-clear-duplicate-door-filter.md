# ADR 0019: Filter clear duplicate doors before presenting a diverse set

- Status: Accepted for reference clients
- Date: 2026-10-02

## Context

The reference selectors took turns across sources and kept at most one candidate per ecosystem. Two candidates could still describe the same family action while claiming different ecosystem labels, so the result could show one door twice. Provider confidence is not a reliable way to decide which action is better. Public records with the same generic title may, however, refer to different original events or places.

## Decision

- Keep the existing local Gate and source-turn order. Before accepting a candidate into the visible set, check whether a selected candidate already has the same normalized, family-facing title and the same original-source URL. Normalize title width, case, spacing, and punctuation only; do not infer synonyms or semantic identity. Very short generic titles are not used as duplicate keys.
- A clear duplicate does not consume an ecosystem slot. Continue searching that source turn for a different feasible door. Keep all Gate evaluations available even when a candidate is not selected; this is selection, not a new Gate rejection reason.
- Distinct original-source URLs remain distinct public items even if their displayed titles match. Nothing remains present, and the selector may return fewer than five actionable doors rather than fill the set with re-labels.
- Apply the same bounded behavior in Android and the public JavaScript reference selector. Tests cover punctuation-only re-labels, next-candidate selection, distinct public URLs, source turns, and Nothing.

## Alternatives

Trusting different ecosystem labels as proof of different experiences was rejected. Title-only de-duplication without source URLs could hide separate real-world records. Fuzzy text similarity or an additional AI judgment was deferred: either could collapse genuinely different routes, add disclosure/cost, or make the selector harder to explain.

## Security, privacy, and compatibility

The check runs locally on candidate data already available to the Engine; it sends no new family information. Some previously visible duplicate cards will now be omitted from the selected set. Source provenance and Gate evaluations are not rewritten. This rule does not prove that differently worded candidates are genuinely diverse or that a source URL identifies a real event; human review and model-quality evaluation remain necessary.

## Rollback and exit

If real families find that meaningful routes share the same title and URL, narrow or version this selection key after examining synthetic and consented evidence. Do not restore duplicate cards merely to reach a target option count, and do not replace the dynamic World Brief service with a static list.
