# План следующих шагов (без крупных блоков)

Задачи из [whats-next.md](whats-next.md), сгруппированные по приоритету. Крупные блоки (Temporal, RAG, supply chain) здесь не включены.

---

## Быстрые (1–3 дня)

| # | Задача | Критерий готовности |
|---|--------|----------------------|
| 1 | **Метрики Prometheus** | **Сделано:** GET /metrics, счётчики runs, duration, tool_calls, llm_calls, llm_input/output_tokens_total. |
| 2 | **Prometheus + Grafana в compose** | **Сделано:** profile observability в deploy/docker-compose.yml. |
| 3 | **Живой прогон в runbook** | **Сделано:** scripts/run_live_run.sh, описание в runbook. |
| 4 | **FactoryRunTest** | **Сделано (вариант A):** оставлен @Ignore, e2e через Docker/run_live_run/CI; см. [factory-run-test-analysis.md](factory-run-test-analysis.md). |

---

## Средний приоритет (неделя и больше)

| # | Задача | Критерий готовности |
|---|--------|----------------------|
| 5 | **GET /factory/runs/{runId}** | **Сделано:** JSON runId, workflowState, updatedAt, repositoryVersion, artifact_location; 404 если не найден. |
| 6 | **Git: create repo + push** | **Сделано:** `create_github_repo`, `push_repo_to_github` (repo_name, owner?). Workflow при `GITHUB_TOKEN`: create_repo → create_github_repo → push_repo_to_github. |
| 7 | **Apply patch** | **Сделано:** tool `apply_patch` (repo_name, patch_content), вызов после кодогена при наличии .patch; unit-тест в SandboxToolExecutorTest. |
| 8 | **SLO/cost метрики** | **Сделано:** парсинг usage из ответа LLM → FactoryMetrics.recordLlmTokens; счётчики factory_llm_input/output_tokens_total в /metrics. |

---

## Автоматизация и GitHub

| # | Задача | Критерий готовности |
|---|--------|----------------------|
| 9 | **Скрипт живого прогона** | **Сделано:** scripts/run_live_run.sh, вручную и из CI. |
| 10 | **CI: job живого прогона** | **Сделано:** job live-run в .github/workflows/ci.yml (compose up, run_live_run.sh, down). |
| 11 | **GitHub в контуре** | **Сделано:** фабрика с create_github_repo и push_repo_to_github (GITHUB_TOKEN, GITHUB_OWNER); при токене workflow создаёт репо и пушит. |

---

## Порядок выполнения (рекомендуемый)

1. Метрики (/metrics) + Prometheus/Grafana в compose.  
2. GET /factory/runs/{runId} (нужен ArtifactRegistry.get).  
3. Скрипт живого прогона + запись в runbook.  
4. CI job: живой прогон (compose up, run script, down).  
5. Git tool (create repo + push) в фабрике; при необходимости сервис с gh в compose для тестов.  
6. Apply patch, SLO/cost — по мере приоритета.  
7. FactoryRunTest — починить или зафиксировать замену в implementation-assessment.

---

## Что не входит (крупные блоки)

- Temporal (durable workflow).  
- RAG + pgvector.  
- Углубление supply chain / AI-risk (SLSA, NIST AI RMF и т.д.).

---

## Что дальше (опционально)

Пункты из плана выше закрыты. Возможные следующие шаги:

| Задача | Описание |
|--------|----------|
| **Валидация контрактов в CI** | **Сделано (2026-02-24):** job **validate-contracts** в [ci.yml](../.github/workflows/ci.yml) запускается на каждый push и PR (без path filter); валидируется `contracts-example/` или `contracts/`. |
| **Бюджеты OPA в рантайме** | В policies/opa/data.json заданы max_tokens_per_run, max_tool_calls_per_run. При желании — проверка в WorkflowRunner/PolicyCheck и остановка run при превышении. |
| **Runbook: GitHub** | **Сделано:** в [runbook.md](runbook.md) добавлен раздел «GitHub: создание репозитория и push» (GITHUB_TOKEN, GITHUB_OWNER, живой прогон с репо на GitHub). |
| **Крупные блоки** | По готовности: Temporal, RAG/pgvector, углубление supply chain — см. [whats-next.md](whats-next.md), roadmap. |

См. [whats-next.md](whats-next.md), [implementation-assessment.md](implementation-assessment.md).
