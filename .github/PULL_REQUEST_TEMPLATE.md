## Цель изменения

Кратко: что меняется и зачем.

## Риск-класс

- [ ] low (docs/refactor, без изменения схем, policies, tools)
- [ ] medium (новый archetype, изменения CI, quality thresholds)
- [ ] high (новый tool с side-effects, budget/approval/policy, секреты, автопромоушен)

## Затрагивает

- [ ] tool schemas
- [ ] policies (OPA)
- [ ] side-effects
- [ ] CI/CD
- [ ] secrets/доступы

## Обязательные проверки (gates)

- [ ] unit tests: PASS
- [ ] integration tests: PASS
- [ ] SBOM generated (Syft): PASS (если применимо)
- [ ] vulnerability scan (Trivy) failOn: HIGH/CRITICAL: PASS (если применимо)
- [ ] signature verify (Cosign): PASS (если применимо)
- [ ] offline eval suite (trace grading): PASS (если применимо)

## План отката

1. git revert коммита с этим PR
2. verify подписи последнего good image digest (если затронут образ)
3. redeploy staging через GitOps desired state (если затронут деплой)

## Наблюдаемость

- traceId последнего полного прогона (если есть): …
- auditRunId (если есть): …

---

*Шаблон по [system-audit-corrective-plan.md](docs/system-audit-corrective-plan.md).*
