# ADR 0028: Self-hosted web runtime for family NAS

- Status: Accepted for experimental developer preview
- Date: 2026-10-10

## Context

The upstream project operates no family server, while compatible third-party hosting is welcome (ARCHITECTURE.md §11, PRIVACY.md §8, FORKING.md §7). Families who already own a NAS want the simplest ownership story: one container on hardware they control, opened in a browser by caregivers and children, without an upstream account.

The repository already contains a browser interaction prototype (`apps/jianyu-web-prototype`), but ADR 0002 marks it non-authoritative: no Stable Core code may depend on it, and it has no server, authentication, persistence, or packaging. The shared `packages/*` are zero-dependency isomorphic ESM that run unchanged under Node >= 22 and in browsers, so a new app can reuse the public FOE contracts directly instead of reimplementing Gate, Diversity Selector, Context Firewall, or event validation.

ARCHITECTURE.md §15 requires an ADR before freezing runtime, UI framework, database, cryptographic suite, or sync framing. This record covers the runtime and packaging choice only; the server trust boundary is ADR 0029 and the state format and cryptographic parameters are ADR 0030.

## Decision

A new application `apps/jianyu-web-nas` provides a browser client plus a Node server packaged as one Docker image, developed on the `feature/nas-web-app` branch of this repository.

- **Runtime:** Node.js 22, standard library only (`node:http`, `node:fs`, `node:path`, WebCrypto via `globalThis.crypto`). No npm runtime dependencies, matching the repository's existing zero-dependency posture and `engines.node >= 22`.
- **Server:** a single `node:http` process that serves the static client and a small JSON/byte API. No web framework, no bundler, no transpile step. The build context is the repository root because the client imports `packages/*` and `examples/` by relative path, the same mechanism the existing prototype dev-server uses.
- **Client:** dependency-free ES modules served as static files, with no build step. Shared logic (Context Firewall orchestration, Gate, Diversity Selector, event validation, lifecycle policy, provider and pack contracts) is imported from `packages/*` unchanged.
- **Packaging:** a `Dockerfile` based on a digest-pinned `node:22-alpine` image, running as a non-root user, with a `HEALTHCHECK`, a single data volume, and environment-variable configuration. A `docker-compose.yml` example documents the expected deployment shape. CI builds the image and runs container smoke checks but does not publish it; publishing requires a separate decision. The base image is pulled from Amazon ECR Public, which mirrors Docker Hub's official images, because Docker Hub rate-limits anonymous pulls from the shared egress addresses public build farms use. The digest is the one `docker.io/library/node:22-alpine` publishes for this build and both registries were verified to serve byte-identical manifests and layers, so the pin still determines the bytes that run; either reference builds the same image.
- **Security headers:** the server reuses the strict header set already proven in the prototype dev-server (CSP `default-src 'self'`, `object-src 'none'`, `frame-ancestors 'none'`, `form-action 'self'`, `referrer-policy: no-referrer`, `x-content-type-options: nosniff`, `cache-control: no-store`) and binds `0.0.0.0` inside the container.
- **Testing:** `node --test` suites under `tests/nas-web/` cover server modules, the client vault, state migrations, merge and tombstone behavior, boundary reuse (Context Firewall minimization, exact public-query approval, unsourced AI world events, Nothing), and lifecycle authority. The CI test command changes to a recursive run so the new directory is included.

## Alternatives considered

- **A web framework (Express/Fastify) or a bundled SPA toolchain** was rejected: it breaks the repository's zero-dependency posture, enlarges the supply-chain surface SECURITY.md asks to minimize, and adds a build step the public packages do not need.
- **Forking the browser prototype into the production app** was rejected: ADR 0002 keeps it non-authoritative, its vault is browser-only with a v2 state format, and its innerHTML-string UI predates current copy discipline. It remains an interaction reference only.
- **A server-rendered application** was rejected: it would place plaintext on the server, crossing the boundary ADR 0029 defines.
- **Shipping the web app as a separate repository** was rejected for now: the shared contract tests, ADR process, secret scanning, and CI already exist here, and a branch keeps the conformance evidence in one place. A later extraction remains possible because the app depends only on public interfaces.

## Security and privacy consequences

- The container is reachable from the family network and, behind a reverse proxy, from the internet. Remote exposure therefore requires TLS termination at the NAS reverse proxy; this is documented, and the session cookie is `Secure` by default (`JIANYU_INSECURE_HTTP=1` exists only for local testing).
- Browser-delivered clients inherit the threats SECURITY.md §2 already names: browser extensions, clipboard history, and a compromised OS can capture plaintext while unlocked. The client minimizes retained plaintext and keeps keys in memory only.
- No telemetry, no analytics, no raw request logging of family content; security logs use reason codes only (SECURITY.md §8).
- The image adds a supply-chain surface with no precedent in this repository: the base image is pinned by digest, the dependency set stays empty, and the container runs as non-root. Independent image signing and SBOM publication remain future work. The mirror is an availability measure, not a trust transfer: it serves the same digest, so a mirror that served anything else would fail the pin rather than pass it.

## Compatibility and migration consequences

- The app consumes only public FOE interfaces. It does not become a dependency of Stable Core, and no package gains a concrete provider, policy, pack, or brand dependency.
- The web family-state format is new and versioned (ADR 0030). Sync-compatibility with the Android reference app is **not** claimed; household key enrollment, device signatures, and signed checkpoints remain unimplemented across ADR 0005–0009, 0021, and 0022.
- Repository-wide tooling changes are limited to the recursive test run and CI job additions; existing JavaScript and Android checks are unchanged.

## Rollback and exit plan

- The app is additive: deleting `apps/jianyu-web-nas/`, its CI job, and its tests restores the previous repository state with no migration, because nothing else imports it.
- The Docker packaging can be replaced (different base image, compose layout, or registry) without touching client or server code.
- If a future decision moves web hosting upstream or into a fork, the ADR is superseded and the app continues to work against the public contracts unchanged.
