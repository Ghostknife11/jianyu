# ADR 0025: Keep caregiver goals from dominating the displayed doors

- Status: Accepted for reference clients
- Date: 2026-10-03

## Context

The Concept Freeze and MVP PRD distinguish Child Goal, Caregiver Goal, and Shared Goal. An option declares which goal it primarily serves; a candidate set dominated by caregiver goals must be rebalanced or rejected. The existing Gate could allow a feasible caregiver-primary route when it also claimed child pull, while source turns, ecosystem uniqueness, and clear-duplicate filtering still allowed caregiver-primary routes to occupy most displayed slots. A high provider score must not turn an adult plan into the family's default path.

## Decision

- In the Android and public JavaScript reference selectors, defer each Gate-eligible caregiver-primary candidate. Admit it only after selecting a child- or shared-primary candidate and only while caregiver-primary routes remain no more numerous than child/shared routes in the displayed set.
- Preserve source turns, the five-door upper bound, ecosystem uniqueness, and the clear title/source duplicate guard. Do not add weak filler options to meet a quota. If only caregiver-primary routes are available, the selected set can be empty and first-class Nothing remains available.
- Keep every Gate evaluation and its original result. The Android in-memory result groups it as `selected`, `rejected`, or `eligibleNotSelected`; the public JavaScript reference already returns the complete `evaluated` list. Omission from the displayed set is a selector decision, not a new Policy rejection reason or an Event/Opportunity Schema change. The family's final choice remains outside the selector.
- Treat the declared primary goal as untrusted candidate data. This bounded arithmetic safeguard does not verify that the activity really follows the child's interest; the Gate's separate child-pull checks, source attribution, family review, and later model-quality evaluation remain necessary.
- The Android BYOK discovery prompt also asks the model to seek child/shared natural entrances first and avoid a caregiver-primary majority, but that wording is only guidance. The deterministic selector remains the reference client's enforceable display boundary.

## Alternatives and limits

Rejecting every caregiver-primary route would erase legitimate family constraints and shared-interest occasions. Sorting by model confidence would outsource this value judgment to a Provider. Requiring an exact equal count would force irrelevant filler or hide useful child-led routes. The reference rule instead sets a ceiling on adult-primary dominance without prescribing an ideal mix. Replaceable future selectors may use a more nuanced, versioned policy after evidence from synthetic and consented evaluation.

## Verification and compatibility

Synthetic Android and JavaScript regressions first reproduced a caregiver-majority result, then verified that child/shared routes are represented and caregiver routes do not outnumber them. The Android result-screen regression renders the selected doors and Nothing at 360 dp / 1.3 font scale. Android's optional `eligibleNotSelected` result-model field now retains allowed-but-omitted evaluations separately from Gate rejections; older serialized results without it decode to an empty list. When completed sources yield only eligible caregiver-primary routes and no Gate rejections, the result page briefly explains their omission without claiming that Nothing is best or that the child has no interest. A source failure retains its higher-priority incomplete-search notice. No external model, World Brief service, or real family data was used. Some formerly displayed caregiver-primary candidates are no longer selected; the persistent Event and Opportunity schemas are unchanged.
