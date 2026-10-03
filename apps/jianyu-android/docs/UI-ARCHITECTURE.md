# Android UI architecture

The Android app is a reference consumer of public FOE contracts. UI code may coordinate a journey, but it must not contain a second recommendation engine, hidden profile fields, provider-specific product rules, or brand-only access to Core.

## Current screen boundaries

```text
JianyuApp.kt        app shell, top bar, bottom navigation, onboarding
TodayScreen.kt      AI-first occasion input, optional Context preview, discovery, Gate results
FamilyScreens.kt    children, lifecycle stage, evidence, corrections, choices
SettingsScreen.kt   BYOK, privacy/security truth, manual encrypted recovery, destructive controls
UiLabels.kt         human-readable presentation of stable reason/status codes
ui/theme/           replaceable visual tokens for the Jianyu BrandConfig
```

`MainViewModel` coordinates public domain/data contracts and exposes immutable `MainUiState`. Screens never read the Family Vault or provider key directly.

## Rules for new UI

1. Start a new screen or focused component instead of growing a single all-purpose file.
2. Keep Child, School, Life, and World visibly distinguishable where provenance matters.
3. Keep Child, Caregiver, and Shared goals distinguishable.
4. Show source, freshness, verification, uncertainty, and Gate reasons in family language.
5. Preserve `Nothing` as a calm first-class action; never add streaks, coverage, or missed-opportunity pressure.
6. Ask for provider disclosure per occasion; never turn a settings toggle into blanket consent.
7. Route recommendation logic through `core:domain` public contracts. UI-only ranking or filtering is a bug.
8. Route family persistence through `VaultRepository`. Screens do not receive storage handles.
9. Child corrections append authored evidence; they do not invisibly mutate history.
10. Brand forks replace `BrandConfig` and theme/assets without forking the Stable Core.

## Planned boundaries

- `WorldBriefScreen`: public feed and source verification, without Family Vault access.
- `PackManagementScreen`: declarative pack install/disable and publisher attribution.
- `SyncAndRecoveryScreen`: promote the current experimental manual recovery controls from Settings and add local-only, LAN, NAS/WebDAV/S3, and third-party-hosted choices only after the sync protocol is implemented.
- stage-aware child surfaces for co-select, hand-over, and Graduation.

The browser prototype is not a source of Android runtime dependencies. It may be used only as an interaction reference.
