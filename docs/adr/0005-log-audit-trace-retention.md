# ADR-0005: Log, audit, trace retention and storage policy

- **Status:** Accepted
- **Date:** 2026-02-20
- **Scope:** MVP

## Context

ProductFactory executes workflows and interacts with non-deterministic systems (LLMs, external tools). We must retain enough telemetry for auditability, debuggability, and cost control while limiting storage growth and sensitive data exposure.

## Decision

Three telemetry classes with separate retention:

### 1. Audit events (append-only, immutable intent)

- **Online retention:** 90 days
- **Cold/archive retention:** 1 year
- **Storage:** S3-compatible object storage (MinIO for MVP)
- **Format:** JSONL append-only with run correlation IDs
- **Integrity:** each event includes `run_id`, `event_id`, `timestamp`; optional hash-chain field

### 2. Application logs (operational, not authoritative)

- **Retention:** 30 days
- **Storage:** log aggregation or filesystem in MVP; optional ship to object storage

### 3. Traces and metrics

- **Retention:** 30 days
- Prefer aggregated metrics for longer retention when needed

### Additional

- **LLM request/response content:** not stored by default unless explicitly enabled; when enabled, store redacted/scrubbed content with 30-day retention.

## Rationale

- Audit needs longer retention and immutability for accountability and incident reconstruction.
- Operational logs and traces are high-volume; shorter retention controls cost.
- Default minimization reduces risk of storing sensitive prompt/tool data.

## Consequences

**Positive:** Clear policy for compliance and ops; predictable storage growth.

**Negative:** Full prompt history may not be available for deep forensics unless explicitly enabled.

## Revisit Triggers

- Regulatory/compliance requirement changes.
- Storage growth > 50 GB/month for telemetry.
- Incident postmortems indicate insufficient retention.
