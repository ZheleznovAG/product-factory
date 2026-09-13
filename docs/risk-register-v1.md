# Risk register v1 (MVP thresholds)

- **Status:** Active
- **Date:** 2026-02-20
- **Scope:** ProductFactory MVP
- **Purpose:** Identify and manage architectural, operational, financial, and AI-related risks with measurable triggers and mitigations.

Дополняет [risk-register.md](risk-register.md); фокус на порогах и триггерах для MVP.

---

## Risk scoring

- **Probability (P):** 1 = Rare … 5 = Almost certain  
- **Impact (I):** 1 = Negligible … 5 = Critical  
- **Score = P × I.** Priority: 1–5 Low, 6–10 Medium, 11–15 High, 16–25 Critical  

---

## R-001: LLM cost explosion

**Category:** Financial | **P:** 3 | **I:** 4 | **Score:** 12 (High)

**Description:** Unbounded token usage or excessive retries cause per-run or monthly cost spikes.

**Triggers:** Average S-run cost > $1; monthly LLM bill > forecast × 1.5; token usage per run exceeds 150k repeatedly.

**Mitigation:** Hard token and call limits per run; budget envelope enforcement; cost telemetry per run; abort on limit exceed.

**Fallback:** Switch to smaller models; disable non-critical LLM steps; manual approval for large runs.

---

## R-002: LLM latency degradation

**Category:** Reliability | **P:** 3 | **I:** 3 | **Score:** 9 (Medium)

**Description:** High p99 latency stalls workflows.

**Triggers:** Planner p99 > 20 s; Codegen p99 > 60 s; queue wait > 15 s.

**Mitigation:** Circuit breaker; reduced context mode; timeout enforcement; provider fallback.

**Fallback:** Abort run; degrade to template-only generation.

---

## R-003: Non-deterministic output breaks pipeline

**Category:** Architectural | **P:** 4 | **I:** 4 | **Score:** 16 (Critical)

**Description:** LLM produces inconsistent plans or invalid output schema.

**Triggers:** >10% schema validation failures; frequent plan drift for identical inputs.

**Mitigation:** Strict JSON schema validation; tool output validation; deterministic execution core; structured prompts.

**Fallback:** Retry with reduced temperature; abort and require manual review.

---

## R-004: Audit log integrity compromise

**Category:** Security / Governance | **P:** 2 | **I:** 5 | **Score:** 10 (Medium)

**Description:** Audit logs tampered, incomplete, or corrupted.

**Triggers:** Missing run_id sequences; audit file write failures; integrity mismatch.

**Mitigation:** Append-only storage; correlation IDs; optional hash-chain; periodic verification job.

**Fallback:** Freeze system on audit failure; alert + manual inspection.

---

## R-005: Policy engine misconfiguration

**Category:** Governance | **P:** 3 | **I:** 4 | **Score:** 12 (High)

**Description:** OPA rules allow forbidden tools or block legitimate runs.

**Triggers:** Run executes disallowed tool; >10% false denials; policy mismatch incidents.

**Mitigation:** Policy tests in CI; versioned policies; dry-run before policy rollout.

**Fallback:** Emergency policy override (with audit); rollback policy version.

---

## R-006: Secrets exposure

**Category:** Security | **P:** 2 | **I:** 5 | **Score:** 10 (Medium)

**Description:** Secrets logged, leaked, or misconfigured.

**Triggers:** Secret appears in logs; unauthorized access; Git leak.

**Mitigation:** Redaction middleware; no logging of secret values; SecretsProvider abstraction (ADR-0006).

**Fallback:** Rotate all secrets; invalidate tokens; post-incident review.

---

## R-007: RAG performance collapse

**Category:** Performance | **P:** 2 | **I:** 3 | **Score:** 6 (Medium)

**Description:** Vector search latency increases due to index growth.

**Triggers:** p99 retrieval > 700 ms; vector storage > 15 GB.

**Mitigation:** Monitor index size; periodic maintenance; migration plan to dedicated vector DB.

**Fallback:** Disable RAG; switch to keyword search.

---

## R-008: Workflow engine instability (Temporal)

**Category:** Infrastructure | **P:** 2 | **I:** 4 | **Score:** 8 (Medium)

**Description:** Temporal crashes or deadlocks cause stuck runs.

**Triggers:** >5% workflows stuck; worker crash loops.

**Mitigation:** Health checks; run timeouts; worker autoscaling.

**Fallback:** Manual cancellation; restart workers.

---

## R-009: Run state corruption

**Category:** Data integrity | **P:** 2 | **I:** 5 | **Score:** 10 (Medium)

**Description:** State machine transitions invalid or partially persisted.

**Triggers:** Inconsistent state transitions; missing artifacts for completed run.

**Mitigation:** Transactional DB writes; idempotent step design; state transition validation.

**Fallback:** Mark run as failed; manual replay.

---

## R-010: Scope explosion (MVP drift)

**Category:** Strategic | **P:** 4 | **I:** 3 | **Score:** 12 (High)

**Description:** Adding multi-tenancy, RAG, self-improving AI too early destabilizes system.

**Triggers:** >3 parallel major feature tracks; delivery delays >50%; increasing complexity without metrics.

**Mitigation:** MVP scope lock; strict ADR governance; feature freeze policy.

**Fallback:** Roll back to core-only execution; disable experimental features.

---

## R-011: Vendor lock-in (LLM provider)

**Category:** Strategic | **P:** 3 | **I:** 4 | **Score:** 12 (High)

**Description:** System tightly coupled to one LLM API or pricing model.

**Triggers:** >90% traffic on single provider; breaking API change; price increase >30%.

**Mitigation:** LLM gateway abstraction (ADR-0007); provider-agnostic contract; multi-provider routing capability.

**Fallback:** Switch provider; deploy smaller local model if viable.

---

## R-012: Observability blind spot

**Category:** Operational | **P:** 3 | **I:** 3 | **Score:** 9 (Medium)

**Description:** Incidents cannot be diagnosed due to insufficient telemetry.

**Triggers:** >2 incidents without root cause; missing correlation IDs; no latency visibility.

**Mitigation:** Structured logging; correlation ID enforcement; gradual move to tracing.

**Fallback:** Temporarily enable verbose logs; increase retention.

---

## Review policy

- Weekly review of High and Critical risks.
- Monthly full risk register review.
- Any incident automatically updates related risk entry.
- Risk score re-evaluated quarterly.
