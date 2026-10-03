# Jianyu App modules

This directory is intentionally split by responsibility:

| Module | Owns | Must not own |
|---|---|---|
| `ui.js` | routing, browser events, orchestration | persistence format, Gate rules |
| `view-templates.js` | accessible HTML rendering | state mutation, provider calls |
| `app-state.js` | family-domain mutations and event emission | browser rendering |
| `authorization.js` | role- and subject-aware action decisions | authentication or UI rendering |
| `event-factory.js` | versioned event envelope creation | projections or persistence |
| `browser-vault.js` | encrypted-at-rest browser persistence | product recommendations |
| `vault-migrations.js` | state-version validation and ordered migrations | UI or provider behavior |
| `opportunity-service.js` | App-to-public-FOE adaptation | UI and vault access |
| `local-discovery.js` | offline candidate generation | selection policy or storage |

New features should cross these boundaries through explicit inputs and outputs. Do not let UI components read IndexedDB directly, let providers receive a vault handle, or reproduce Opportunity Gate decisions inside presentation code.

When a second implementation proves a reusable need, move the contract into the appropriate public SDK. Do not add an abstraction only because it might be useful someday.
