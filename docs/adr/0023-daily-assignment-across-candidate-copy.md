# ADR 0023: Check explicit assignments across candidate copy

- Status: Accepted for reference policies
- Date: 2026-10-02

## Context

ADR 0020 introduced a narrow local check for explicit daily assignments in a candidate title. A Provider can keep a natural-looking title while putting a recurring worksheet or mandatory drill in the explanation or `whyNow`. Those fields are shown to the family and can turn an apparent opportunity into a disguised task even when the Provider reports child pull and low pressure.

## Decision

- Android reference Policy v0.2.8 and JavaScript reference Policy v0.1.3 evaluate the same narrow `daily-task-pressure` rule over title, explanation, and `whyNow` / `entryPoint.whyNow`.
- The rule examines short punctuation-delimited clauses beginning with an explicit daily/consecutive-day assignment or mandatory exercise cue. It also recognizes a limited connector such as `然后` before that cue. It does not treat a negated example such as `不需要每天做三页练习` as an assignment.
- The public synthetic model screener uses the JavaScript reference pattern over the same three fields. A local Gate rejection remains the authority for the reference flow; benchmark findings are not model grades.
- The reason code and family-facing wording remain unchanged. A rejected candidate cannot displace a separate natural route; Nothing remains available.

## Alternatives and limits

A broad semantic classifier, second AI call, or ban on all recurring practice would overreach: a child may voluntarily study, and another model may still misread the wording. This check is deliberately conservative. It can miss pressure expressed in different words or across clauses, and it can reject a genuinely voluntary routine written as an assignment. The family must still decide whether any remaining route fits the child's actual wish.

## Privacy, compatibility, and exit

No new data is sent or stored. Existing candidate records are not rewritten; a current reference Policy may make a different decision on the same text and records its new version. Forks can replace the Policy through the public extension point. Keep synthetic tests for disguised assignments, negation, a separate natural route, and first-class Nothing; revise the pattern with a new version if human review reveals unacceptable false positives.
