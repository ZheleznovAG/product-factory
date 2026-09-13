# Ответ на технический аудит (European Software Architecture Award‑стиль)

Краткая сводка по пунктам внешнего аудита репозитория Product Factory и что сделано.

## P0 — Критичные (сделано)

| Пункт | Замечание | Решение |
|-------|-----------|---------|
| P0.1 | В архив попали build/.gradle | В репо они в .gitignore; в README добавлено предупреждение не включать их в архивы. |
| P0.2 | Нет gradlew, README обещает ./gradlew | Добавлен Gradle wrapper (gradlew, gradlew.bat, gradle/wrapper); в README — сборка через ./gradlew build. |
| P0.3 | Ссылки на несуществующие deep-research-report-*.md | Ссылки убраны из README; заменены на общую отсылку к docs/ и отчётам при наличии. |
| P0.4 | Audit log: ручная склейка JSON, риск log forging | Все payload в audit переведены на структурную сериализацию (buildJsonObject/buildJsonArray + .toString()); escapeJson удалён. |

## P1 — Важные (частично или уже было)

| Пункт | Замечание | Состояние |
|-------|-----------|-----------|
| P1.1 | OPA не подключена, policy «allow all» | OPA подключена: при OPA_URL PolicyCheck дергает OPA HTTP; при недоступности — fallback allow (задокументировано в runbook). |
| P1.2 | Нет тестов | Тесты есть: FactoryRunTest, FactoryApprovalApiTest, SandboxToolExecutorTest, WorkflowRunnerPolicyTest и др.; CI запускает gradle build (включая тесты). |
| P1.3 | Dockerfile скачивает Gradle без проверки целостности | Сборка переведена на Gradle wrapper: COPY gradlew + gradle/wrapper, RUN ./gradlew build installDist; загрузка по URL из wrapper с validateDistributionUrl. |
| P1.4 | CI проглатывает ошибки (|| true) | В текущем ci.yml таких конструкций нет; eval gate вызывается без подавления вывода. |

## P2 — Средний приоритет

| Пункт | Замечание | Состояние |
|-------|-----------|-----------|
| P2.1 | Нет observability (OTel, health) | OTel уже в коде (WorkflowRunner, spans); health/ready — у архетипов; для фабрики при необходимости добавить /healthz. |
| P2.2 | API контракт бедный (target_stack, budget и т.д.) | Оставлено на следующий шаг; расширение контракта — в implementation-assessment как рекомендация. |

## Что закрепить

- Документация и контракты (solution_design, threat_model, risk_register, approval-policy) — без изменений по аудиту.
- Contracts-first, OPA, обязательный audit log — сохранены; audit log усилен за счёт структурной сериализации.

Пересмотр: при следующем внешнем аудите или крупном изменении границ фабрики.

---

## Аналитические отчёты (deep research)

| Отчёт | Содержание |
|-------|------------|
| [deep-research-report-9.md](deep-research-report-9.md) | Соответствие документации и кода, пробелы, расхождения, дорожная карта (2026-02-27). Ключевые рекомендации: AuthN/AuthZ для API, per-run budgets enforcement, OPA fail-closed по умолчанию в prod, уточнение staging/GitOps и out-of-scope. Таблица требований vs реализации, варианты решений, приоритезированная roadmap и реестр рисков. |
