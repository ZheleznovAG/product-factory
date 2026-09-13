#!/usr/bin/env python3
"""
Completion Pipeline (Пайплайн завершения): актуальный пайплайн только из оставшихся задач.
Бэклог: docs/completion-pipeline.md.

В отличие от Grand Pipeline (38 шагов, многие уже сделаны), здесь только 16 незакрытых пунктов:
наблюдаемость/SLO, окружения, RAG, комплаенс, GitOps CD, role registry, доки.

Запуск:
  python3 scripts/run_completion_pipeline.py [--dry-run] [--step N] [--from-step N] [--to-step N] [--allow-docker] [--no-commit] [--no-push] [--model MODEL] [--resume]
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

STEPS_FILE = SCRIPTS_DIR / ".completion_pipeline_steps.json"
STATE_FILE = SCRIPTS_DIR / ".completion_pipeline_state.json"

STEPS_BUILTIN = [
    PipelineStep("1", "Документация уточнения и аудио-слой",
        "В Product Factory добавь в docs раздел про аудио-слой в протоколе уточнения intent: ASR → текст, TTS для вопросов. Обнови intent-clarification-protocol или intent-clarification-text-audio-optimality если такие файлы есть."),
    PipelineStep("2", "Prometheus rules и Grafana SLO/cost",
        "В Product Factory добавь deploy/prometheus-rules.yml с правилами алертов по SLO и cost. Подключи в prometheus.yml (или в deploy/docker-compose). Дашборд Grafana для SLO+cost — создай или обнови provisioning. В runbook добавь раздел про SLO и алерты. См. slo-ci-gate.md."),
    PipelineStep("3", "Онлайн-сигналы SLO/cost для promotion",
        "В Product Factory реализуй или доработай использование /metrics и артефакт-реестра для решения о promotion (авто или полуавто). Документ или раздел slo-promotion-signals.md с описанием сигналов и порогов."),
    PipelineStep("4", "ADR local-docker и remote-ssh",
        "В Product Factory создай ADR 0012: решение по local-docker (только конфиг или полноценный запуск шагов в compose) и по remote-ssh (реализовать минимальный SSH-runner или out of scope). Обнови docs/environments.md или аналог."),
    PipelineStep("5", "Local-docker и/или remote-ssh по ADR",
        "По ADR 0012: если решено реализовать — добавь LocalDockerEnvironmentProvider или минимальный SSH-runner (env: host, user, key), подключи к ExecutionContext/TestRunner где уместно. Иначе — явная пометка в коде и environments.md что out of scope."),
    PipelineStep("6", "RAG ingestion + pgvector",
        "В Product Factory реализуй загрузку архетипов и/или доков в pgvector с версионированием индекса. Точка интеграции в планировщике — опционально. Документируй в docs. RAG off by default по decision-points."),
    PipelineStep("7", "Offline eval RAGAs и датасеты",
        "В Product Factory добавь сценарий оценки RAGAs (faithfulness, relevance). Файл eval-ragas.md с описанием. Расширь датасеты и при необходимости eval gate в CI."),
    PipelineStep("8", "Контур AI-risk",
        "В Product Factory внедри процессные элементы по ai-risk-contour-plan: governance, risk assessment, мониторинг. Краткий чеклист в docs. Связь с threat_model.md и risk-register.md."),
    PipelineStep("9", "Release с SBOM/подписью и high-risk approval",
        "В Product Factory закрепи политику: каждый release с SBOM и подписью; high-risk изменения — ручной approval. Опиши в docs и при необходимости добавь проверку в CI/gates."),
    PipelineStep("10", "SLSA аттестации по политике",
        "В Product Factory добавь документ по SLSA (slsa-attestation-plan или в docs/supply-chain). Настройка аттестаций в CI где ещё не покрыто (supply-chain-factory уже делает provenance)."),
    PipelineStep("11", "GitOps CD до prod",
        "В Product Factory реализуй или детально опиши в docs GitOps CD до prod по gitops-cd-prod-plan. Обнови deploy/ и runbook.md."),
    PipelineStep("12", "Canary и откат",
        "В Product Factory добавь в runbook схему canary (если применимо) и процедуру отката за минуты (revert desired state, verify, smoke)."),
    PipelineStep("13", "Role registry и шаблоны по ролям",
        "В Product Factory добавь реестр ролей (Planner, Implementer, Tester, Reviewer и др.) и шаблоны промптов/правил на роль. Использование в LlmAgentPlanner и LlmAgentCodegen. Документируй в docs."),
    PipelineStep("14", "Веб-форма решений (опционально)",
        "В Product Factory добавь минимальный веб-UI для отображения плана, рисков, вариантов и сбора решений (вызов answer/approvals API). Или зафиксируй в runbook что out of scope и достаточно API/CLI."),
    PipelineStep("15", "Portal/CLI self-serve в runbook",
        "В Product Factory актуализируй runbook: как потребитель получает сервис без копипаста (CLI product-factory run/intent уже есть; портал — по желанию). Явно опиши путь self-serve."),
    PipelineStep("16", "ADR 0011/0012, implementation-assessment, runbook, risk-register",
        "В Product Factory создай или дополни ADR 0011 (источник SBOM/signature в run), ADR 0012 (окружения local-docker/remote-ssh). Обнови implementation-assessment.md: убери устаревшие placeholder. Актуализируй runbook и risk-register.md по разделам SLO/cost, AI-risk, multi-tenancy."),
]


def main() -> int:
    steps = load_steps_from_file(STEPS_FILE, STEPS_BUILTIN)
    runner = SprintRunner(
        name="completion-pipeline",
        steps=steps,
        state_file=STATE_FILE,
        default_push=True,
        description="Completion Pipeline (Пайплайн завершения)",
    )
    return runner.run()


if __name__ == "__main__":
    sys.exit(main())
