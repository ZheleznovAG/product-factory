# ADR-0006: Secrets management for MVP

- **Status:** Accepted
- **Date:** 2026-02-20
- **Scope:** MVP

## Context

ProductFactory needs secrets for LLM API keys, Git tokens, object storage credentials, and DB credentials. For MVP, operational complexity must remain low, but the design must allow future migration to Vault or cloud secret managers.

## Decision

MVP uses **Kubernetes Secrets** (and/or environment variables in local dev) as the primary secrets mechanism.

**Constraints:**

- Secrets are never logged.
- A **`SecretsProvider`** abstraction will be used in code, with implementations:
  - `EnvSecretsProvider` (dev)
  - `K8sSecretsProvider` (staging)
  - `VaultSecretsProvider` (future)

**Vault** (or cloud secret managers) is **explicitly deferred** to post-MVP.

## Rationale

- Minimizes operational overhead and accelerates MVP delivery.
- Keeps a clean upgrade path via provider abstraction.

## Consequences

**Positive:** Low complexity for solo developer; no extra infrastructure dependency in MVP.

**Negative:** K8s Secrets are not a full-featured secret lifecycle solution; rotation and advanced policies are limited compared to Vault.

## Revisit Triggers

- Need for strict rotation, leasing, dynamic secrets.
- Multi-tenant model introduces secret isolation requirements.
- Compliance requirements mandate stronger secret governance.
