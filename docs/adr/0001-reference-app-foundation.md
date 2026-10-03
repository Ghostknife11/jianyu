# ADR 0001: Reference App foundation

- Status: Superseded as primary client by ADR 0002; retained as a browser interaction prototype
- Date: 2026-09-13

## Context

Jianyu must become a usable family application without turning its first interface into a disposable prototype or coupling FOE to one framework. The repository currently has no package manager available, and the privacy model requires a useful local-only path before any account or hosted service exists.

## Decision

The first App slice is a standards-based installable browser application served by a zero-dependency local Node host.

The dependency direction is:

```text
UI templates -> UI controller -> FamilySession -> BrowserFamilyVault
                         ↓
                OpportunityService
                         ↓
                  public FOE Core
```

- `BrowserFamilyVault` owns persistence and authenticated encryption. UI code never writes family data directly to browser storage.
- `FamilySession` owns domain mutations and versioned family events. It does not know how the interface is rendered.
- `OpportunityService` adapts App input to the public FOE request and Provider contracts.
- UI templates are pure render functions. The controller owns navigation and event binding.
- Offline installation uses a Service Worker, but the encrypted vault remains authoritative.
- The reference App may use only public FOE interfaces. If it needs a new engine capability, that capability must first become a documented public contract.

## Consequences

The App works without a cloud account, build tool, or framework and can later be wrapped as a desktop/mobile application. A future framework migration may replace the controller and templates without changing FamilySession, vault format, event schemas, or Provider contracts.

The browser vault is a real encrypted-at-rest implementation, but it is not yet the production vault: recovery keys, multi-device key exchange, native secure storage, encrypted attachments, migration tooling, and external security review remain required.
