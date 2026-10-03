# ADR-0002: Jianyu reference client is a native Android app

- Status: Accepted
- Date: 2026-09-13

## Context

Jianyu is intended to be a family-facing Android application. A browser prototype can test language and flow, but it cannot silently become the primary product: doing so would blur platform security boundaries, lifecycle behavior, offline storage, accessibility expectations, and future device integrations.

## Decision

The official reference client lives in `apps/jianyu-android` and uses Kotlin plus Jetpack Compose. It is a native app, not a WebView wrapper.

The client is split into replaceable modules:

- `core:model` owns portable, versioned contracts;
- `core:domain` owns stable product rules and extension interfaces;
- `core:data` implements Android-specific local persistence;
- `app` composes those public contracts into the Jianyu experience.

The earlier browser implementation remains only in `apps/jianyu-web-prototype` as a non-authoritative interaction reference. No Stable Core code may depend on it.

## Consequences

- Android platform APIs can provide a clear local encryption boundary through Android Keystore.
- Future maintainers can replace the UI, storage implementation, providers, policies, packs, or branding without rewriting the stable model and domain contracts.
- Browser and Android clients may share protocol fixtures, but neither client defines the protocol by implementation accident.
- Remote sync, recovery encryption, databases, and production AI providers remain separate decisions requiring their own ADRs and threat-model updates.
