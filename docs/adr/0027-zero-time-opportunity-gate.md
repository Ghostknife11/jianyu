# ADR 0027: A zero-time occasion remains a pause, not a provider loophole

- Status: Accepted for the replaceable reference Policies
- Date: 2026-10-03

## Context

The public fictional benchmark includes an occasion with no available time or caregiver energy. The Android Gate previously compared only candidate duration against the limit. A Provider could call an activity `0` minutes with no caregiver effort, and that non-Nothing candidate would pass even when the family explicitly supplied a zero-minute limit. The JavaScript reference Gate had the same gap.

## Decision

- Android reference Opportunity Policy advances to `0.2.10`; the public JavaScript reference Policy advances to `0.1.4`.
- A zero-or-negative available-time limit rejects every ordinary opportunity with `no-available-time`. A candidate declaring zero-or-negative duration also receives `invalid-duration`; an activity cannot evade practical constraints by calling itself instantaneous.
- The Engine still constructs `Nothing` separately as a first-class family choice. A Provider-supplied candidate that claims to be Nothing or uses the reserved `nothing` ecosystem is rejected with `reserved-nothing-option`, even if it claims a positive duration. The Gate does not convert a rejected candidate into a model-generated Nothing, score the family's pause, or save a negative observation.
- Lack of caregiver energy alone is not a blanket veto: a genuinely independent older child's opportunity may need none. The time limit concerns this occasion, not a judgment about the child.

## Alternatives and limits

Trusting the Provider's zero-minute estimate was rejected because a model can understate the work needed. Treating `0` as an unknown/unlimited time value was rejected because it reverses an explicit practical constraint. Rejecting every zero-energy candidate was rejected because energy and time are distinct.

This deterministic rule cannot detect a plausible but false positive duration such as `1` minute, and it does not infer hidden family availability from free text. The current Android composer slider begins at 15 minutes; the zero-minute case currently exercises the public Core, imported/request fixtures, and future clients rather than a visible zero-time setting in that composer. A future direct zero-time UI should avoid a paid Provider call, but that UI is not implemented by this ADR.

## Compatibility and rollback

The result is a versioned reference Policy decision. No Event or Opportunity schema changes, existing selections are not rewritten, and forks may supply their own Policy through the public extension point. Readers of old decisions retain their original policy reference and reasons. Rolling the reference Policy back would reopen this specific bypass; the intended exit is a tested successor rule, not silent removal.
