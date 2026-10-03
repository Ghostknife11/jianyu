# ADR 0011: Exact birthday lifecycle boundaries with legacy fallback

- Status: Accepted
- Date: 2026-09-15

## Context

The first Android flow asked for only a birth year and calculated age as `currentYear - birthYear`. That moves every child to a new authority stage on January 1, can be wrong for almost a full year, and makes a normal family setup form feel like an engineering placeholder. Lifecycle stages control consent, persistence, decision ownership, and Graduation, so the boundary must be precise.

## Decision

- New Android records collect a calendar birth date and store it as ISO `YYYY-MM-DD` inside the encrypted Family Vault.
- Age is calculated in completed years against the device's current local date.
- A stage changes on the birthday: 4–6 co-play, 7–9 accompany, 10–12 co-select, 13–15 hand over, and 16+ Graduation.
- For a February 29 birth date, a non-leap year's birthday boundary is March 1, not February 28. This applies to both displayed age and authority-stage changes.
- Imported or legacy data for someone below age 4 remains in the encrypted vault, but the current Android app does not offer or execute a new discovery for that member. No new observation is created by that attempt.
- `16+` is open-ended. The client must not silently reinterpret it as `16–18`; an older returning subject may still need export, deletion, or read-only Graduation controls.
- Provider disclosure continues to use derived age/lifecycle context. Exact birth date is excluded by Context Firewall.
- The Family Vault schema advances to `org.jianyu.family-vault/v5`. `Child.birthYear` remains required while `birthDate` is optional; v2/v3/v4 vaults migrate to v5 without inventing a month or day.
- When an old record has no `birthDate`, the client uses the previous year-only approximation and labels the missing month/day rather than inventing one.
- Graduation export includes `birthDate` when known because it is the subject's own encrypted archive; its external envelope reveals no identity. New archives use `org.foe.graduation-archive/v2`; readers retain support for v1 archives without the field.

## Consequences

Birthday is now sensitive local metadata and must follow the same deletion, export, encryption, and sync rules as the subject record. Future clients must test the day before and the day of the 13th and 16th birthdays, plus at least one adult well beyond age 18. A future schema version may retire `birthYear` only after cross-client migration fixtures and compatibility policy permit it.
