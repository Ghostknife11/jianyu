# ADR 0020: Reject explicit daily assignments outside model self-report

- Status: Accepted for reference policies
- Date: 2026-10-02

## Context

The formal AI Provider reports `childPull`, `naturalEntry`, goal ownership, and intervention pressure for each candidate. Those fields are useful inputs but are not independent evidence of the child's wish. A model can call an explicit daily worksheet child-led and low-pressure, allowing a disguised assignment through a Gate that only checks those flags. Jianyu's purpose is to expand naturally available doors, not prescribe a recurring study task.

## Decision

- Android reference Policy v0.2.7 and public JavaScript reference Policy v0.1.2 add the `daily-task-pressure` rejection reason for a small set of high-confidence candidate-title patterns: a title starting with an explicit daily or consecutive-day schedule followed by exercises, worksheets, homework, drills or check-ins, or an explicitly mandatory assignment. The check runs locally after the Provider response and does not trust the Provider's `childPull` or low-pressure claim to override it.
- Only the candidate title is checked. An ordinary self-directed question or study interest is not categorically rejected. Nothing remains available when all candidates fail, and a natural route from the same result may still be selected.
- The family-facing detail says `像每日任务，可能增加压力`; it does not diagnose the child's motivation or claim the App perfectly understands the text.
- The public synthetic model benchmark reuses the JavaScript reference pattern to flag the same explicit title form during model screening. This is a screening finding, not the full Android Gate or a published model rating.

## Alternatives

Banning educational topics or all practice was rejected because a child may voluntarily want to investigate or practice something. Trusting a prompt or model-reported pressure alone was rejected because those are part of the same untrusted response. A broad text classifier or second external AI call was deferred because it can misread negation or intent, disclose more context, add cost, and still be wrong.

## Security, privacy, and compatibility

The check sends no additional data. A candidate previously shown under the reference Policy may now be rejected with a versioned reason. Replaceable Policies may take a different, explicitly documented approach, but should not present model self-report as independent proof of child pull. This narrow pattern is easy for differently worded coercive content to evade and may occasionally reject a genuinely self-chosen daily practice if its title is phrased as an assignment; human choice and quality evaluation remain necessary.

## Rollback and exit

Keep the test cases for a lying daily worksheet and a self-directed study question. If real-family review reveals false positives, narrow or version the pattern rather than quietly restoring a daily-assignment recommendation. A future reviewed semantic policy may replace this lexical reference rule without changing the public Provider contract.
