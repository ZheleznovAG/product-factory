#!/usr/bin/env python3
"""
Оркестр MVP-спринта (первый archetype) через Codex CLI.
Фаза MVP по docs/roadmap-checklist.md: archetype catalog-service, CI, SBOM/Trivy/Cosign, staging.

Запуск: python scripts/run_mvp_sprint.py [--dry-run] [--step 1] [--allow-docker] [--no-commit] [--model MODEL] [--resume]
  --allow-docker: рекомендуется для MVP.
"""
from __future__ import annotations

import sys

from pipeline_lib import PipelineStep, SprintRunner

STEPS = [
    PipelineStep(
        id="1",
        name="Архетип catalog-service",
        prompt="""В этом репо Product Factory создай первый архетип — шаблон сервиса catalog-service. Размести в директории archetypes/catalog-service/. Стек: Kotlin, Ktor, JDK 17. Включи: минимальный HTTP API (например GET /health, GET /api/catalog), unit-тесты (JUnit/Kotlin Test), интеграционный тест на запуск приложения, Dockerfile (multi-stage, образ на JRE), заготовку под миграции БД (Flyway или пустая директория migrations с README). Структура: build.gradle.kts, src/main/kotlin, src/test/kotlin, Dockerfile. Не дублируй корневой gradle репо — архетип может быть отдельным Gradle-проектом в подпапке со своим build.gradle.kts. См. docs/roadmap-checklist.md (MVP), docs/system-audit-corrective-plan.md (фаза MVP).""",
    ),
    PipelineStep(
        id="2",
        name="Tool create_repo_from_archetype и архетип",
        prompt="""В Product Factory sandbox executor уже имеет tool create_repo_from_archetype (stub). Подключи его к реальному архетипу: при вызове с аргументом archetype_id=catalog-service (и опционально repo_name) копировать содержимое archetypes/catalog-service/ в целевую директорию (например в workspace или путь из конфига). Сохрани идемпотентность по idempotencyKey: при повторном вызове с тем же ключом — не перезаписывать, вернуть успех из кэша. Запись вызова в audit log оставь. Контракт tools — contracts/tools.schema.json. См. src/main/kotlin/productfactory/workflow/tools/SandboxToolExecutor.kt, docs/tools-list-mvp.md.""",
    ),
    PipelineStep(
        id="3",
        name="CI для архетипа (build, test, образ)",
        prompt="""Добавь CI для архетипа catalog-service. Вариант 1: отдельный workflow в .github/workflows/ (например ci-archetype-catalog.yml), который заходит в archetypes/catalog-service и запускает gradle build (с тестами) и docker build. Вариант 2: job в существующем ci.yml, который выполняет сборку и тесты архетипа. Используй те же конвенции, что и для фабрики: сборка и тесты в Docker или на runner с Java 17. Архетип должен собираться и проходить тесты в CI. См. .github/workflows/ci.yml, docs/roadmap-checklist.md (MVP: CI к репо).""",
    ),
    PipelineStep(
        id="4",
        name="SBOM (Syft), Trivy, Cosign в gate",
        prompt="""Добавь в CI фабрики или архетипа шаги supply-chain gate: 1) Генерация SBOM для образа (Syft, формат CycloneDX или SPDX). 2) Сканирование уязвимостей (Trivy image), fail при HIGH/CRITICAL по политике (см. docs/approval-policy.md, quality_profile). 3) Подпись образа (Cosign) и проверка (cosign verify) в gate. Можно начать с одного job в .github/workflows/, который после docker build запускает syft, trivy, cosign sign/verify. Документируй в README или docs/runbook.md требования (Syft/Trivy/Cosign в PATH или в контейнере). См. docs/system-audit-corrective-plan.md (SBOM, Trivy, Cosign), docs/definition-of-done-mvp.md.""",
    ),
    PipelineStep(
        id="5",
        name="Staging (GitOps) и smoke test",
        prompt="""Добавь заготовку под staging deploy в стиле GitOps: манифесты для деплоя архетипа (Kubernetes Deployment/Service или docker-compose для простого варианта) в директорию infra/staging/ или deploy/staging/. Добавь шаг smoke test: после деплоя (или симуляции) выполнить HTTP-запрос к health/ready и проверить 200. Можно реализовать как отдельный job в CI или скрипт scripts/smoke-staging.sh. Документируй в docs/runbook.md порядок деплоя и отката. См. docs/roadmap-checklist.md (критерий успеха MVP: rollback через GitOps), docs/risk-register.md (R-009 rollback).""",
    ),
]


def main() -> int:
    runner = SprintRunner(name="mvp-sprint", steps=STEPS)
    return runner.run()


if __name__ == "__main__":
    sys.exit(main())
