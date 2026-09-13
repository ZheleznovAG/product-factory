# Scripts

## run_alpha_sprint.py

Оркестр Alpha-спринта: по очереди вызывает `codex exec` с промптами из [docs/alpha-sprint.md](../docs/alpha-sprint.md).

### Почему не видно созданных файлов

Codex CLI по умолчанию может запускаться в **read-only sandbox**. Тогда команды на запись в репо блокируются с `Operation not permitted`, и файлы (схемы в `contracts/schemas/`, ADR в `docs/adr/` и т.д.) не появляются.

**Что сделано в скрипте:** вызов идёт с `--sandbox workspace-write` и `--cd <корень репо>`, чтобы Codex мог писать в репозиторий. Если по политике нужен только просмотр без записи — запускайте с `--no-write`.

### Запуск

```bash
# Все шаги, с записью в репо (по умолчанию)
python3 scripts/run_alpha_sprint.py

# Только шаг 1 (схемы)
python3 scripts/run_alpha_sprint.py --step 1

# Проверка без вызова Codex
python3 scripts/run_alpha_sprint.py --dry-run

# Без записи (sandbox read-only)
python3 scripts/run_alpha_sprint.py --no-write

# Не коммитить после шага
python3 scripts/run_alpha_sprint.py --no-commit

# Разрешить Codex запускать Docker (build/run и т.д.) — sandbox danger-full-access
python3 scripts/run_alpha_sprint.py --allow-docker

# Модель Codex 5.3 (если доступна в аккаунте)
python3 scripts/run_alpha_sprint.py --model gpt-5.3-codex
```

После каждого успешного шага скрипт делает `git add -A` и `git commit -m "alpha-sprint: шаг N — ..."` (если есть изменения). Отключить: `--no-commit`.

**Sandbox:** по умолчанию `workspace-write` (запись в репо). `--allow-docker` переключает на `danger-full-access` — тогда Codex может запускать Docker-контейнеры (команды в терминале: `docker build`, `docker run` и т.д.). Используйте на доверенной машине.

Требуется: `codex` в PATH (установка на хосте: `brew install --cask codex` или `npm i -g @openai/codex`). Точное имя модели для 5.3 смотри в `codex exec --help` или в настройках Codex.

**Скиллы Codex:** Codex подхватывает скиллы из `$REPO_ROOT/.agents/skills` (и из `~/.agents/skills`). В репо есть скилл `product-factory` в [.agents/skills/product-factory/SKILL.md](../.agents/skills/product-factory/SKILL.md) — при запуске оркестра из корня репо Codex использует его для контекста по фабрике. Описание в frontmatter должно быть в кавычках (YAML), без лишних двоеточий.

---

## run_planning_pipeline.py

Пайплайн **продумывания и детализации** плана технологий и задач: на основе архитектуры и roadmap (architecture-and-path, roadmap-workstreams-and-variants и др.) генерирует полный документ [docs/technology-and-implementation-plan.md](../docs/technology-and-implementation-plan.md). Стек — зрелый open-source с 1–2 альтернативами на компонент (без жёсткой привязки); раздел «Пока не определено» — под технологии без зрелых open-source решений. Цель: чтобы осталось только делать.

```bash
python3 scripts/run_planning_pipeline.py --dry-run   # просмотр шагов
python3 scripts/run_planning_pipeline.py             # полный прогон (5 шагов Codex)
python3 scripts/run_planning_pipeline.py --step 1    # только шаг 1 (стек)
python3 scripts/run_planning_pipeline.py --resume    # продолжить после лимита Codex
```

Описание: [docs/planning-pipeline.md](../docs/planning-pipeline.md).

---

## run_pipeline_with_resume.py

Запуск Completion Pipeline с **автовозобновлением** при временном исчерпании лимита Codex (rate limit / квота). Вызывает [run_completion_pipeline.py](run_completion_pipeline.py); при ошибке из-за лимита читает из state время сброса (`codex_retry_after_ts`), ждёт, затем перезапускает пайплайн с `--resume`.

```bash
# Полный пайплайн с автовозобновлением при лимите
python3 scripts/run_pipeline_with_resume.py --allow-docker

# Ограничить ожидание 30 минутами при лимите
python3 scripts/run_pipeline_with_resume.py --allow-docker --wait-min 30

# Не ждать при лимите — выйти с подсказкой запустить --resume вручную
python3 scripts/run_pipeline_with_resume.py --allow-docker --no-wait

# Макс. 12 возобновлений
python3 scripts/run_pipeline_with_resume.py --allow-docker --max-resume 12
```

Все остальные опции передаются в `run_completion_pipeline.py` (например `--step N`, `--from-step`, `--to-step`, `--model`, `--no-push`).

---

## run_intent_to_run.sh

E2E-скрипт для цепочки:
1) `POST /intent/estimate` (уточнение intent),
2) `POST /experience/generate` (получение кандидатов),
3) выбор кандидата по `CANDIDATE_INDEX`,
4) `POST /factory/run`,
5) polling `GET /factory/runs/{runId}` до `STAGED`/`DONE`.

Запуск:

```bash
bash ./scripts/run_intent_to_run.sh
```

Полезные переменные:
- `FACTORY_URL` (по умолчанию `http://localhost:9080`)
- `TENANT_ID` (по умолчанию `default`)
- `USER_QUERY` (по умолчанию встроенный onboarding-запрос)
- `VARIANTS` (`3..7`, по умолчанию `3`)
- `CANDIDATE_INDEX` (1-based, по умолчанию `1`)
- `TARGET_STACK` (по умолчанию `web-app`)
- `FACTORY_RUN_TIMEOUT` (сек, по умолчанию `180`)

Требует `curl` и `jq`. Используется в `.github/workflows/ci.yml` (job `live-run`).

---

## run_beta_sprint.py

Оркестр Beta-спринта (Execution Core): state machine, OTel, OPA из приложения, sandbox executor, артефакт-реестр. Те же флаги, что у Alpha: `--dry-run`, `--step 1|2|3|4|5`, `--no-write`, `--no-commit`, `--model`, **`--allow-docker`** (если шагу нужны сборка/тесты в Docker).

---

## run_mvp_sprint.py

Оркестр **MVP-спринта** (первый archetype): по очереди вызывает Codex для пяти шагов:

| Шаг | Название |
|-----|----------|
| 1 | Архетип catalog-service (Kotlin/Ktor, тесты, Docker, миграции) в `archetypes/catalog-service/` |
| 2 | Tool create_repo_from_archetype — подключение к архетипу, копирование по archetype_id |
| 3 | CI для архетипа (build, test, docker build) |
| 4 | SBOM (Syft), Trivy, Cosign в gate |
| 5 | Staging (GitOps) + smoke test |

Флаги: `--dry-run`, `--step 1|2|3|4|5`, `--no-write`, `--no-commit`, `--model`, **`--allow-docker`** (рекомендуется — шаги 1–5 связаны со сборкой и Docker).

```bash
# Все шаги MVP, с Docker и коммитами
python3 scripts/run_mvp_sprint.py --allow-docker

# Только архетип (шаг 1)
python3 scripts/run_mvp_sprint.py --step 1 --allow-docker

# Проверка без вызова Codex
python3 scripts/run_mvp_sprint.py --dry-run
```

См. [docs/roadmap-checklist.md](../docs/roadmap-checklist.md) (фаза MVP).

---

## run_scale_sprint.py

Оркестр **Scale-спринта** (AI как Proposal Engine): агент-планировщик, кодоген (patch/diff), approvals, trace grading и eval gate.

| Шаг | Название |
|-----|----------|
| 1 | Агент-планировщик: pipeline plan, ADR draft, test plan в JSON (contracts/agent_outputs.schema.json, модуль в src/.../agent/) |
| 2 | Агент-кодоген: patch sets (git diff), без merge — только proposals, запись в audit |
| 3 | Approvals для write/deploy: сохранение запросов, endpoint/CLI approve, продолжение workflow после одобрения |
| 4 | Trace grading и eval runs: формат trace, eval/datasets + runner, CI gate при изменении prompts/policies/tools |

Флаги: `--dry-run`, `--step 1|2|3|4`, `--no-write`, `--no-commit`, `--model`, **`--allow-docker`** (рекомендуется для сборки и тестов).

```bash
# Все шаги Scale
python3 scripts/run_scale_sprint.py --allow-docker

# Только планировщик (шаг 1)
python3 scripts/run_scale_sprint.py --step 1

python3 scripts/run_scale_sprint.py --dry-run
```

См. [docs/system-audit-corrective-plan.md](../docs/system-audit-corrective-plan.md) (фаза Scale), [docs/roadmap-checklist.md](../docs/roadmap-checklist.md).

---

## neural_gateway (нейросервис на хосте)

Сервис в `scripts/neural_gateway/` — единый OpenAI-совместимый API. Бэкенд переключается переменной `NEURAL_BACKEND`: `codex` (вызов Codex CLI на этом хосте), `openai` (прокси в api.openai.com), `proxy` (прокси на `PROXY_TARGET_URL`). Фабрике и тестам задаётся один URL (`NEURAL_SERVICE_URL=http://localhost:8090`) — по ответам не видно, идёт ли запрос в OpenAI, на self-hosted или на Codex.

```bash
cd scripts/neural_gateway && pip install -r requirements.txt
NEURAL_BACKEND=codex uvicorn main:app --host 0.0.0.0 --port 8090
```

См. [scripts/neural_gateway/README.md](neural_gateway/README.md), [docs/neural-service-api.md](../docs/neural-service-api.md).
