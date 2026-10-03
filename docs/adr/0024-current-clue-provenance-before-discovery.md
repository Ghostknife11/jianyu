# ADR 0024: Keep the current child's clue distinct from adult plans and public background

- Status: Accepted for reference clients
- Date: 2026-10-03

## Context

The reference lexical guard already stopped an isolated child refusal from becoming a new recommendation. It still treated any non-refused sentence containing a Pack or World Brief topic as child pull. Thus “家长想带孩子看赛车” could produce a child-led candidate, while “家长不想去赛车现场，但孩子想在家看赛车” could incorrectly turn the adult's refusal into the child's veto. An obvious public-background sentence could also start a paid AI request despite naming no child-originated interest.

## Decision

- The Android App preflight, Android Engine, and JavaScript reference flow skip external discovery when the only current sentence is a plainly caregiver-led plan, an isolated refusal, or obvious external background. They retain first-class Nothing and do not save a positive interest observation for these cases.
- Local Pack, Search, and World Brief matching uses the same bounded clause distinction. A separate positive child clause may still match; a short topic alone remains an accepted shorthand.
- A clearly caregiver-attributed refusal is not treated as a child veto. It does not by itself authorize a recommendation; a separate child clue is still required. The caregiver's real time, travel, and willingness should remain visible in practical context and family choice.
- Android reference Policy advances from v0.2.8 to v0.2.9 because its `no-current-child-pull` outcome changes for the same input. Existing saved decisions are not rewritten. The JavaScript reference Core changes its source preflight without changing the separate Policy SDK's Gate rules.
- Product copy names the absence of a usable active clue rather than falsely stating that the child said “不要” when the input only describes an adult plan or public background.

## Alternatives and limits

Requiring an explicit “想” in every sentence would lose ordinary observations, shorthand, and voluntary behavior. The reference instead uses narrow phrase checks. They can miss paraphrases, misread an unpunctuated sentence with multiple speakers, or mistake a short topic for child pull. It does not establish true intent or solve feasibility: an adult who does not want to travel may still need a different route or 留白. Families retain the final choice, and future policies can replace these lexical opinions through the public extension boundary.

## Privacy and verification

The change reduces unnecessary outbound calls; it adds no transmitted field and keeps private matching on-device. Synthetic tests cover adult-led plans, background-only statements, child and adult refusals with a separate child wish, no-call behavior, local Pack/World results, and the Android no-write path. Real family language and model behavior require human review before a production claim.
