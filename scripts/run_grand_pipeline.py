#!/usr/bin/env python3
"""
Grand Pipeline: полный пайплайн «по-взрослому» — всё реализуемое, кроме тяжёлого/фантастического.
Бэклог: docs/grand-pipeline-plan.md. Чеклист: docs/grand-pipeline-tasks.md.

Шаги загружаются из scripts/.grand_pipeline_steps.json (результат мозгового штурма)
или используются встроенные (STEPS_BUILTIN).

Запуск:
  python scripts/run_grand_pipeline.py [--dry-run] [--step N] [--from-step N] [--to-step N] [--allow-docker] [--no-commit] [--no-push] [--model MODEL] [--resume]
"""
from __future__ import annotations

import sys
from pathlib import Path

from pipeline_lib import (
    SCRIPTS_DIR,
    PipelineStep,
    SprintRunner,
    load_steps_from_file,
)

STEPS_FILE = SCRIPTS_DIR / ".grand_pipeline_steps.json"
STATE_FILE = SCRIPTS_DIR / ".grand_pipeline_state.json"

STEPS_BUILTIN = [
    PipelineStep("1", "LlmIntentClarification + промпт и fallback",
        "В Product Factory реализуй LlmIntentClarification: nextQuestion(goal, constraints, previousAnswers) через NeuralServiceClient. Промпт и формат ответа в docs. Fallback на stub при отсутствии NEURAL_SERVICE_URL. Подставь в контур при NEURAL_SERVICE_URL. См. IntentClarification.kt, LlmAgentPlanner."),
    PipelineStep("2", "Адаптивные вопросы и раннее завершение",
        "В Product Factory сделай выбор и порядок вопросов зависимыми от goal/constraints. Добавь раннее завершение: после каждого ответа обновлять черновик intent, nextQuestion возвращает null при достаточной полноте. Документируй в intent-clarification-protocol."),
    PipelineStep("3", "Короткий свободный ответ в протоколе",
        "В Product Factory разреши в протоколе уточнений не только A/B, но и короткую фразу. Маппинг в поля intent (правила или LLM). При неясности — один уточняющий вопрос или fallback на A/B. Обнови IntentClarification и docs."),
    PipelineStep("4", "LlmIntentGenerator и LlmIntentCandidatesGenerator",
        "В Product Factory реализуй LlmIntentGenerator.generate(goal, constraints) и LlmIntentCandidatesGenerator.generate(intent) через NeuralServiceClient. Fallback на stub при ошибке. Подставь в Application при NEURAL_SERVICE_URL."),
    PipelineStep("5", "Документация уточнения и аудио-слой",
        "Обнови intent-clarification-protocol и intent-clarification-text-audio-optimality. Добавь раздел про аудио-слой: ASR → текст, TTS для вопросов."),
    PipelineStep("6", "Маршруты POST /intent/estimate и /experience/generate",
        "В Product Factory добавь в Ktor POST /intent/estimate и POST /experience/generate по api-intent-experience.md. Обработчики вызывают IntentClarification, IntentGenerator, IntentCandidatesGenerator. Запись в audit."),
    PipelineStep("7", "Runbook intent → factory run",
        "В runbook.md добавь пример запроса к /intent/estimate и /experience/generate и сценарий «после выбора кандидата POST /factory/run». Примеры curl."),
    PipelineStep("8", "Источник SBOM/signature в run",
        "Реализуй передачу sbomVersion/signatureVersion в ToolStepResult: tool или пост-шаг Syft/Cosign по workspace или приём из CI. Документируй; при решении «только CI» — ADR 0011."),
    PipelineStep("9", "SLO CI gate в ci.yml",
        "Добавь в ci.yml job SLO gate: вызов slo_gate, проверка порогов после live-run, exit 1 при нарушении. Конфиг порогов. Обнови slo-ci-gate.md и runbook."),
    PipelineStep("10", "Prometheus rules и Grafana SLO/cost",
        "Добавь deploy/prometheus-rules.yml, подключи в prometheus.yml. Дашборд SLO+cost актуален. Runbook: SLO и алерты."),
    PipelineStep("11", "Онлайн-сигналы для promotion",
        "Реализуй или доработай использование /metrics и артефакт-реестра для promotion. Обнови slo-promotion-signals.md."),
    PipelineStep("12", "Хранение счётчиков за период",
        "Реализуй хранение счётчиков за день/месяц (токены, tool_calls, runs). Обновление при каждом run. Документ дизайна."),
    PipelineStep("13", "Проверка лимита перед run и конфиг cost",
        "Перед принятием run проверяй лимит за период; при превышении 429/403. Конфиг cost-budgets. Runbook и slo-cost-draft."),
    PipelineStep("14", "ADR local-docker и remote-ssh",
        "Создай ADR 0012: решение local-docker (конфиг или запуск в compose) и remote-ssh (реализовать или out of scope). Обнови environments.md."),
    PipelineStep("15", "Local-docker выполнение в контейнере",
        "При решении «полноценный запуск»: ExecutionContext, LocalDockerEnvironmentProvider выполняет команды в контейнере. Подключи к TestRunner/tool executor. Иначе — только runbook."),
    PipelineStep("16", "Remote-ssh runner или out of scope",
        "При решении «реализовать»: минимальный SSH-runner (env: host, user, key). Иначе пометка в коде и environments.md по ADR 0012."),
    PipelineStep("17", "RAG ingestion + pgvector",
        "Реализуй загрузку архетипов/docs в pgvector, версионирование индекса. Точка в планировщике опционально. Документируй."),
    PipelineStep("18", "Offline eval RAGAs и датасеты",
        "Добавь сценарий RAGAs (faithfulness, relevance). Обнови eval-ragas.md и датасеты. Расширь eval gate при необходимости."),
    PipelineStep("19", "SLSA provenance и аттестации",
        "По slsa-attestation-plan реализуй шаги SLSA Level 2: provenance, build attestation в CI. Документируй."),
    PipelineStep("20", "Контур AI-risk",
        "По ai-risk-contour-plan внедри процессные элементы: governance, risk assessment, мониторинг. Чеклист. Связь с threat_model и risk-register."),
    PipelineStep("21", "Release с SBOM/подписью и high-risk approval",
        "Обеспечь каждый release с SBOM и подписью. Явный ручной approval для high-risk. Документируй."),
    PipelineStep("22", "GitOps CD до prod",
        "Реализуй или детально опиши GitOps CD до prod по gitops-cd-prod-plan. Обнови deploy и runbook."),
    PipelineStep("23", "Canary и откат",
        "Добавь схему canary и процедуру отката за минуты в runbook."),
    PipelineStep("24", "Изоляция по tenant",
        "Спроектируй и реализуй tenantId в API. Изоляция реестра и audit по tenant. Документируй."),
    PipelineStep("25", "Portal/CLI self-serve",
        "Добавь минимальный UI или CLI: запуск run, статус, выбор intent. Runbook: потребитель получает сервис без копипаста."),
    PipelineStep("26", "Хранилище профиля предпочтений",
        "Реализуй хранилище профиля (embedding + правила). Конфиг и consent. Документируй."),
    PipelineStep("27", "Обновление профиля по выборам и API сброс",
        "При выборе кандидата обновляй профиль. Использование при ранжировании. API сброса и инкогнито. Runbook."),
    PipelineStep("28", "Role registry и шаблоны по ролям",
        "Добавь реестр ролей и шаблоны промптов на роль. Использование в LlmAgentPlanner/Codegen. Документируй."),
    PipelineStep("29", "Провижининг и headless browser",
        "Документируй провижининг раннеров. Интеграция headless browser для smoke web-app. Runbook."),
    PipelineStep("30", "Веб-форма решений и спринт-поинты",
        "Минимальный веб-UI: план, риски, варианты; сбор решений; вызов answer/approvals. Спринт-поинты. Runbook."),
    PipelineStep("31", "Контракт artifact_manifest.json",
        "Введи контракт artifact_manifest.json (manifest + seed + inputs). Схема в contracts/schemas или docs."),
    PipelineStep("32", "Заполнение manifest в run и воспроизведение",
        "При stage/finish записывай manifest в реестр и audit. Документ или скрипт воспроизведения по manifest."),
    PipelineStep("33", "session в потоке и референсы 6 карточек",
        "Используй session в API и audit. Сценарий: 6 вариантов, пользователь выбирает 2; reference_ids в intent и audit. Runbook."),
    PipelineStep("34", "FactoryRunTest стабильный",
        "По factory-run-test-analysis почини FactoryRunTest или зафиксируй решение; стабильный e2e в CI."),
    PipelineStep("35", "Тесты ask_user и intent API",
        "Интеграционный тест ask_user flow. Тесты /intent/estimate, /experience/generate и intent → run."),
    PipelineStep("36", "E2E скрипт intent → run",
        "Скрипт или CI шаг: уточнение, выбор кандидата, POST /factory/run, проверка. Runbook."),
    PipelineStep("37", "ADR 0011/0012 и implementation-assessment",
        "Создай/дополни ADR 0011, 0012. Обнови implementation-assessment: убрать placeholder по реализованному."),
    PipelineStep("38", "Runbook полный и risk-register",
        "Актуализируй runbook: intent, SLO, cost, environments, self-serve, откат. risk-register: SLO/cost, AI-risk, multi-tenancy."),
]


def main() -> int:
    steps = load_steps_from_file(STEPS_FILE, STEPS_BUILTIN)
    runner = SprintRunner(
        name="grand-pipeline",
        steps=steps,
        state_file=STATE_FILE,
        default_push=True,
        description="Grand Pipeline (Codex CLI)",
    )
    return runner.run()


if __name__ == "__main__":
    sys.exit(main())
