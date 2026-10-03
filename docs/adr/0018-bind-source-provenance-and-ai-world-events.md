# ADR 0018: Bind candidate provenance and reject unsourced AI world events

- Status: Accepted for reference clients
- Date: 2026-10-01

## Context

An AI Provider may propose a specific current exhibition, competition, or other `world-event` without a real original source. The built-in Android AI adapter marks these as generated ideas, but the local Gate previously allowed such a candidate if its self-reported child pull and practical fields passed. The Engine also trusted the candidate's own source-kind string for Gate, diversity, and UI provenance, so a replaceable Provider could label its output as a different source type. Dynamic World Brief must remain a separate third-party public-information service, not be replaced by AI guesses or static Packs.

## Decision

- Android Policy v0.2.6 rejects a `byok-llm` candidate in the `world-event` ecosystem when it has no original-source URL, with a stable `ai-world-event-without-source` reason. The built-in AI prompt asks for generic, source-independent routes rather than invented named events. Other ordinary AI ecosystems remain eligible, and Nothing is always available.
- When no candidate survives and this rejection occurred without a failed source, the Android result lead names the missing original source and keeps both retry and 留白 available; a failed-source explanation takes precedence when the search was incomplete.
- Before Gate and diversity, the Android Engine binds each returned candidate's `sourceKind` to the source it invoked. Its explicit offline-demo kind retains the existing display alias. The JavaScript reference Core similarly binds Pack, World Brief, Search, and LLM candidates to their actual invoked category.
- World Brief records continue to use their own freshness, verification, and source rules. A fictional `IDEA` fixture with no real external link may remain an explicit demo; this is not a global no-link rule. A claimed URL or `VERIFIED` flag is not independent evidence of truth.

## Alternatives

Trusting model confidence, `childPull`, or a candidate's self-labelled provenance was rejected. Treating every World Brief idea without a URL as false was rejected because it would collapse a dynamic service's idea/likely/verified distinctions and break an explicit fictional fixture. Automatically checking every external link within the family client was deferred: reachability alone cannot prove event identity, availability, licensing, or safety.

## Security, privacy, and compatibility

Binding source kind prevents a Provider payload from impersonating a different registered category, but the source registration and publisher identity themselves still need trust and revocation work. Existing Android AI-generated `world-event` candidates without a URL will now be rejected; ordinary AI routes and genuine World Brief candidates are unaffected. Existing saved choices retain their historical source labels. The JavaScript reference flow now ignores the source kind inside returned candidate data and records the invoked category instead; clients that relied on an arbitrary candidate-selected kind should adapt their Provider registration. No new family data is sent to any service.

## Rollback and exit

Do not re-enable source-kind spoofing as a compatibility fallback. A future versioned Provider contract may carry source citations and client verification metadata; only then consider a reviewed route for AI-assisted world-event discovery. Keep public-world services separate from private family context and preserve the family's final verification and choice.
