# ADR-0007: LLM Gateway API shape (single endpoint with role routing)

- **Status:** Accepted
- **Date:** 2026-02-20
- **Scope:** MVP

## Context

The system uses one LLM service URL. There was an open question whether to split into two endpoints (PLANNER/CODEGEN) or keep a single endpoint. We need predictable routing, observability, and ability to switch providers/models without invasive code changes.

## Decision

MVP will use a **single LLM Gateway endpoint** with request-level routing by **`role`**.

**API contract (conceptual):**

- `role`: `PLANNER | CODEGEN | EVAL | EMBED`
- `model`: optional override string
- `timeout_ms`: optional
- `input`: structured payload (messages/context)
- `metadata`: `run_id`, `trace_id`, cost budget hints

The gateway selects the actual model/provider based on role, cost budget, latency SLO, and policy constraints.

If in the future different networks/keys/compliance zones require separation, multiple endpoints may be introduced as an infrastructure concern **without changing the client contract** (the gateway remains the stable interface).

## Rationale

- Minimizes configuration and moving parts in MVP.
- Keeps routing logic centralized and auditable.
- Preserves flexibility for future multi-provider routing.

## Consequences

**Positive:** Simple client integration; easier observability and governance at one choke point.

**Negative:** Gateway becomes a critical dependency; must be reliable.

## Revisit Triggers

- Separate compliance zones required (e.g. code must stay in-cluster).
- Different SLA envelopes require independent scaling and isolation.
- Multi-tenant routing needs stronger isolation.
