# BrandConfig

Declarative product identity, localized copy, theme, assets, feature flags, default Provider registrations, and default Policy selection for Jianyu and forks. Identity fields are deliberately separate: `displayNameZhCN` names the product, `taglineZhCN` is its compact promise, `heroZhCN` is the onboarding headline, and `missionZhCN` states the mission. Do not substitute one for another in a client.

Brand configuration cannot change security, privacy, provenance, lifecycle authority, or event semantics. Operational screens should normally speak in terms of the family, child, AI, and local device instead of repeatedly personifying the configured brand.

`src/index.js` validates the public `org.foe.brand-config/v1` document. The Android reference app consumes the same document shape from its bundled assets; conformance tests prevent the copies from drifting.
