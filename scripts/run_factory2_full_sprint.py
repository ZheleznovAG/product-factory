#!/usr/bin/env python3
"""
Оркестр полного спринта Factory 2.0: полировка, LLM в Planner, документация.
Бэклог: docs/sprint-full-backlog.md. Как запускать: docs/sprint-factory2-howto.md.

Запуск:
  python3 scripts/run_factory2_full_sprint.py [--dry-run] [--step 1] [--allow-docker] [--no-commit] [--model MODEL] [--resume]
"""
from __future__ import annotations

import sys

from pipeline_lib import PipelineStep, SprintRunner

STEPS = [
    PipelineStep(
        id="1",
        name="Health endpoint для образа фабрики",
        prompt="""В Product Factory добавь health endpoint для основного приложения (образ фабрики), по аналогии с архетипами (catalog-service, web-app). Требования: (1) GET /health или GET /health/ready, возвращающий 200 и минимальный JSON (например {"status":"ok"} или {"status":"up"}). (2) Зарегистрировать маршрут в том же месте, где остальные API (например в productfactory.api или Application). (3) Добавить в docs/runbook.md краткую секцию про проверку здоровья фабрики (curl и использование в оркестраторах/CI). Не меняй контракты и workflow. См. archetypes/web-app для примера /health/ready.""",
    ),
    PipelineStep(
        id="2",
        name="Валидация контрактов в CI",
        prompt="""Добавь в CI фабрики job для валидации контрактов. Требования: (1) В .github/workflows/ci.yml (или отдельный workflow) добавь job, который запускает валидатор контрактов. Вариант: использовать образ фабрики (docker run product-factory ...) или уже существующий шаг validate-contracts. (2) Валидировать директорию с контрактами (например contracts/schemas или тестовую contracts-example если есть). (3) Job должен запускаться при изменении contracts/ или при push в main. Документируй в README или docs/runbook.md при необходимости. См. AGENTS.md: product-factory validate <dir>.""",
    ),
    PipelineStep(
        id="3",
        name="FactoryRunTest: стабилизировать или задокументировать",
        prompt="""В Product Factory интеграционный тест FactoryRunTest помечен @Ignore из-за нестабильности в testApplication (CWD/окружение). Нужно одно из двух. Вариант A: стабилизировать тест — исправить testApplication или окружение так, чтобы тест проходил без @Ignore (сохрани сценарий: POST /factory/run → DONE, проверка audit, artifact registry). Вариант B: оставить @Ignore и явно задокументировать: (1) В комментарии над тестом или в тесте указать, что замена проверки — FactoryApprovalApiTest, прогон через Docker, CI kotlin-build и validate-contracts. (2) В docs/implementation-assessment.md в разделе про тесты добавить одну строку, что FactoryRunTest сознательно отключён и заменён указанными способами. Выбери вариант, который быстрее и не ломает CI.""",
    ),
    PipelineStep(
        id="4",
        name="LlmAgentPlanner: реализация + промпт и tool surface",
        prompt="""Реализуй LlmAgentPlanner в Product Factory — планировщик, вызывающий LLM через NeuralServiceClient. Требования: (1) Новый класс LlmAgentPlanner в src/main/kotlin/productfactory/agent/ (или neural/), реализующий интерфейс AgentPlanner. (2) Конструктор принимает NeuralServiceClient (и при необходимости AuditLog для логирования). (3) generate(input: AgentPlannerInput) вызывает neuralClient.chatCompletion(...) с низкой temperature (0.2–0.3), запрашивает JSON в формате трёх артефактов: pipeline_plan, adr_draft, test_plan — по контракту contracts/agent_outputs.schema.json (поля type, steps, context, decision, consequences, scope, cases, coverage_targets). (4) Парсинг ответа в AgentPlannerArtifacts (PipelinePlanArtifact, AdrDraftArtifact, TestPlanArtifact); при невалидном JSON или ошибке/таймауте — fallback: вернуть результат StubAgentPlanner().generate(input) или выбросить с записью в audit. (5) Таймаут вызова LLM 30 секунд (см. docs/decision-points-v1.md). (6) В системный промпт включить: роль Planner, список разрешённых tools (из contracts/tools.schema.json или захардкодить create_repo_from_archetype, contract_validator, quality_gate_runner), требование выдавать только JSON. (7) После парсинга проверить, что все tool в steps принадлежат разрешённому списку; иначе заменить на fallback. См. docs/factory-2-planner-integration.md, AgentPlanner.kt, HttpNeuralServiceClient.kt.""",
    ),
    PipelineStep(
        id="5",
        name="Тесты LlmAgentPlanner",
        prompt="""Добавь unit-тесты для LlmAgentPlanner. Требования: (1) Тест с моком NeuralServiceClient: мок возвращает валидный JSON строкой (содержит pipeline_plan, adr_draft, test_plan в формате agent_outputs.schema.json). (2) Вызвать LlmAgentPlanner.generate(AgentPlannerInput(goal = \"test\")) и проверить, что возвращаются корректные AgentPlannerArtifacts (не null, steps не пустые, типы совпадают). (3) Тест fallback: мок возвращает null или невалидный JSON — проверить, что результат либо от StubAgentPlanner, либо исключение/логирование. Размести тесты в src/test/kotlin/productfactory/agent/ (например LlmAgentPlannerTest.kt). Не ломай существующие StubAgentPlannerTest.""",
    ),
    PipelineStep(
        id="6",
        name="Внедрение Planner в контур + наблюдаемость в audit",
        prompt="""Внедри LlmAgentPlanner в контур фабрики и добавь наблюдаемость. Требования: (1) В productfactory.Application.kt в функции module() при создании WorkflowRunner: если переменная окружения NEURAL_SERVICE_URL задана и не пуста — создать HttpNeuralServiceClient(baseUrl = NEURAL_SERVICE_URL, apiKey = NEURAL_SERVICE_API_KEY из env), затем LlmAgentPlanner(neuralClient) и передать его в WorkflowRunner как agentPlanner; иначе передать StubAgentPlanner(). (2) В LlmAgentPlanner при вызове LLM логировать в audit log событие (например planner_invoked или agent_planner_call) с полями: latency_ms, success (boolean), fallback_used (boolean); не логировать полные промпты и ответы (см. docs/adr/0005-log-audit-trace-retention.md). (3) При fallback из-за ошибки/таймаута тоже записать событие с fallback_used=true. AuditLog передавать в LlmAgentPlanner через конструктор. См. Application.kt, WorkflowRunner конструктор, docs/factory-2-planner-integration.md.""",
    ),
    PipelineStep(
        id="7",
        name="Runbook: живые прогоны «запрос → артефакт»",
        prompt="""Добавь в docs/runbook.md секцию про живые прогоны сценария «запрос → артефакт». Требования: (1) Подзаголовок типа «Живые прогоны (запрос → артефакт)» или «Пример прогона factory run». (2) Пример curl для POST /factory/run с телом JSON (goal, опционально другие поля по FactoryRunRequest). (3) Ожидаемый ответ (status accepted/rejected, runId). (4) Кратко: как проверить результат (audit log, артефакт-реестр, или созданная директория при использовании create_repo_from_archetype). (5) Опционально: скрипт scripts/run_live_example.sh с curl и выводом, если его ещё нет. Не меняй контракты API.""",
    ),
    PipelineStep(
        id="8",
        name="Черновик SLO и метрик cost (документ)",
        prompt="""Создай черновик документа по SLO и метрикам cost для фабрики. Требования: (1) Новый файл docs/slo-cost-draft.md (или добавь раздел в docs/decision-points-v1.md). (2) SLO: латентность run (целевые p50/p99 в мс или сек), доля успешных run (target %). (3) Метрики cost: токены (input/output), число tool calls, wall-clock время run; как будут использоваться для решения о promotion (ручной порог, авто-gate, бюджет на месяц). (4) Ссылка на docs/risk-register-v1.md (R-001 cost, R-002 latency) при необходимости. Можно кратко; детальная реализация — отдельные задачи.""",
    ),
    PipelineStep(
        id="9",
        name="Обязательные digest в audit",
        prompt="""Сделай так, чтобы в audit log фабрики события при необходимости содержали inputDigest и outputDigest. Требования: (1) Посмотри src/main/kotlin/productfactory/workflow/AuditLog.kt и места, где пишутся события (например tool_call_executed, state_changed). (2) По docs/roadmap-checklist.md в Beta указано: «Event log: runId, stepId, inputDigest, outputDigest» — частично есть. Добавь заполнение inputDigest/outputDigest там, где они логически уместны (например для tool_call_executed — digest входа/выхода tool), если сейчас пусто; используй простой hash (SHA-256 или аналог) от строки payload. (3) Если в каких-то событиях digest не применим — не добавляй лишнее; достаточно 1–2 типов событий с digest. (4) Кратко опиши в комментарии в коде или в docs/runbook.md формат audit log (какие поля обязательны). См. FileAuditLog, WorkflowRunner вызовы auditLog.log.""",
    ),
    PipelineStep(
        id="10",
        name="Threat model: доработка (NIST AML, MITRE ATLAS)",
        prompt="""Углуби docs/threat_model.md в части таксономий угроз. Требования: (1) Добавь раздел «Таксономии и ссылки» (или дополни существующий): NIST AI 100-2 (Adversarial Machine Learning) и MITRE ATLAS — кратко что это и ссылки на источники. (2) В таблице угроз при необходимости добавь колонку «ATLAS/NIST» с идентификаторами угроз из таксономий, если знаешь (иначе оставь заголовок для последующего заполнения). (3) Не удаляй существующие угрозы и митигации; только дополнение. См. docs/threat_model.md, docs/implementation-assessment.md (фаза 6).""",
    ),
    PipelineStep(
        id="11",
        name="API: опциональные поля target_stack, budget в FactoryRunRequest",
        prompt="""Расширь API фабрики опциональными полями в запросе factory run. Требования: (1) Найди определение FactoryRunRequest (Kotlin data class или сериализуемый класс) в коде фабрики. (2) Добавь опциональные поля: target_stack (String?), budget (объект или поля: token_budget, tool_calls_budget, wall_clock_seconds — по выбору, опционально). (3) Поля не обязательны; при отсутствии поведение как сейчас. (4) В документации API или в docs/runbook.md / README опиши новые поля одной строкой каждое. (5) Если есть OpenAPI/Swagger — обнови. См. docs/whats-next.md (расширить API контракт).""",
    ),
    PipelineStep(
        id="12",
        name="Синхронизация phases-task-list и implementation-assessment",
        prompt="""Приведи в соответствие с текущим состоянием два документа: docs/phases-task-list.md и docs/implementation-assessment.md. Требования: (1) В phases-task-list.md отметь галочками [x] все пункты, которые уже выполнены по docs/roadmap-checklist.md и implementation-assessment (например eval gate в фазе 5, threat model заполнен в фазе 6, web-app во фазе 7). (2) В implementation-assessment.md обнови сводку по фазам и «Чего нет» если нужно: threat_model уже заполнен; LLM в контуре — планировщик можно отметить как «готов к подключению» после внедрения LlmAgentPlanner. (3) Не меняй нумерацию фаз и структуру документов; только актуализация статусов.""",
    ),
    PipelineStep(
        id="13",
        name="WS3 v1: минимальный web-UI решений + sprint-points + runbook",
        prompt="""В Product Factory реализуй минимальный web-UI для этапа принятия решений. Требования: (1) Добавить endpoint UI (например GET /factory/ui), который показывает план, риски, варианты и формы решений. Можно inline HTML/JS в Ktor без отдельного фронтенд-рантайма. (2) Добавить read-endpoint контекста по runId (например GET /factory/runs/{runId}/decision-context): вернуть pipeline plan из audit, риски/approval context, pending ask-user question/options. (3) UI должен уметь вызывать существующие API POST /factory/runs/{runId}/answer и /factory/approvals/{runId}/approve|reject. (4) Добавить sprint-point endpoint (например POST /factory/runs/{runId}/sprint-point) для ручной фиксации proceed/hold/reject между фазами; запись в audit обязательна. (5) Добавить/обновить тесты API под новые endpoint'ы. (6) Обновить docs/runbook.md: как открыть UI, как пройти decision flow, как записывать sprint points (curl + ожидаемые ответы). Соблюдать границы слоёв (агент не делает write/deploy напрямую).""",
    ),
]


def main() -> int:
    runner = SprintRunner(name="factory2-sprint", steps=STEPS)
    return runner.run()


if __name__ == "__main__":
    sys.exit(main())
