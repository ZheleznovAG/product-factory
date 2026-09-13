# ADR-0003: Deployment substrate and cloud strategy (MVP)

- **Status:** Accepted
- **Date:** 2026-02-20
- **Scope:** MVP

## Context

ProductFactory must provide reproducible runs, predictable costs, and clear separation between deterministic execution and non-deterministic LLM behavior. Early lock-in to a single cloud provider increases integration surface and operational complexity for a solo developer MVP. The platform needs an execution substrate that works in dev/staging with minimal friction and remains portable to AWS/GCP/Azure later.

## Decision

For MVP, ProductFactory targets **Kubernetes** as the deployment substrate with a **portable, provider-agnostic stack**:

- **Dev:** `kind` or `k3d` (local)
- **Staging:** `k3s` or managed Kubernetes (provider-agnostic)
- **Object storage:** S3-compatible API (MinIO in MVP)
- **Database:** PostgreSQL
- **Workflow engine:** Temporal (self-hosted in MVP when integrated)
- **Policy engine:** OPA (self-hosted)
- **GitOps:** Argo CD (optional for MVP; enabled for staging when available)

Cloud-provider proprietary managed services (AWS/GCP/Azure specific) are **not required** for MVP.

## Rationale

- Minimizes vendor lock-in and keeps a single deployment topology across environments.
- Enables reproducibility and deterministic operation independent of cloud-specific behavior.
- Keeps operational complexity manageable while aligning with future scale.

## Consequences

**Positive:** Portable deployments, predictable infra, easier local iteration, simpler security posture for MVP.

**Negative:** Self-hosted components increase operational responsibility (acceptable for MVP).

## Alternatives Considered

- **AWS/GCP/Azure first:** rejected due to lock-in and complexity for MVP.
- **Pure on-prem without K8s:** rejected due to mismatch with long-term target.

## Revisit Triggers

- Need for multi-region HA or large scale (e.g. >100 runs/day).
- Strong requirement for managed compliance controls (SOC2/ISO) at infra layer.
- Operational burden exceeds time budget.
