# ADR-0004: Dependency version pinning and upgrade policy

- **Status:** Accepted
- **Date:** 2026-02-20
- **Scope:** MVP+

## Context

ProductFactory depends on infrastructure components (Temporal, OPA, Argo CD) and language/runtime dependencies. To ensure reproducibility, avoid CI drift, and make incidents diagnosable, the platform must pin dependency versions and control upgrades via explicit governance.

## Decision

1. **All critical runtime dependencies MUST be pinned** to an exact version (no `latest`):
   - Temporal server and SDK version (when adopted)
   - OPA version
   - Argo CD version (if enabled)
   - PostgreSQL major/minor
   - JDK version

2. A single file **`versions.lock`** (or equivalent in repo) defines pinned versions and is treated as a controlled interface.

3. **Dependency upgrades require:**
   - A dedicated ADR or ADR amendment
   - CI run including smoke/integration checks
   - A rollback plan (previous `versions.lock`)

4. **Toolchains:** Use Gradle wrapper in-repo; builds must not rely on host-installed Gradle.

## Rationale

- Ensures builds and runs are reproducible.
- Reduces "works on my machine" drift and surprise breakages.
- Makes incident triage and rollback reliable.

## Consequences

**Positive:** Stable CI, repeatable environments, easier support.

**Negative:** Requires periodic explicit upgrade work; security patches must be actively managed.

## Implementation Notes

- `versions.lock` is used by: Helm charts/manifests, Docker image tags, build scripts.
- See [DEPENDENCY_POLICY.md](../DEPENDENCY_POLICY.md) for upgrade procedure.

## Revisit Triggers

- Dependency update cadence becomes too slow and risks security posture.
- Enterprise environment mandates a different version governance model.
