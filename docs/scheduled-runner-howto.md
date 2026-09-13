# Планировщик Grand Pipeline (scheduled runner)

Скрипт **`scripts/scheduled_pipeline_runner.py`** обеспечивает непрерывную работу над репозиторием: каждые ~5 часов выполняется либо pipeline (или его продолжение), либо **мозговой штурм** (после завершения всех шагов), либо только maintenance. При обрыве по лимиту API следующий цикл продолжит с последнего сохранённого шага.

## Режимы работы

| Условие | Действие |
|--------|----------|
| Есть lock (другой процесс запущен, lock свежий <6 ч) | Только **maintenance**: аудит, идеи, одна задача рефакторинга/улучшения. Pipeline не трогаем, но работа идёт. |
| Lock свободен, pipeline **не** завершён | Берём lock, **запускаем pipeline**: шаги `last_completed + 1` .. N (resume). После прогона — maintenance. |
| Lock свободен, pipeline **завершён** (все шаги выполнены) | Берём lock, **мозговой штурм**: агенты работают с докой и (при возможности) интернетом, составляют новые доки и **новый pipeline** в `scripts/.grand_pipeline_steps.json`; state сбрасывается. Затем maintenance. Следующий цикл запустит уже новый pipeline с шага 1. |
| `--maintenance-only` | Всегда только maintenance, pipeline и brainstorm не запускаются. |
| `--once` | Один цикл (pipeline / brainstorm / maintenance) и выход. |

## Параметры

```bash
python3 scripts/scheduled_pipeline_runner.py [OPTIONS]
```

- **`--interval-hours 5`** — интервал между циклами в часах (по умолчанию 5).
- **`--jitter-min 30`** — к интервалу добавляется случайное число минут от 0 до N (по умолчанию 30).
- **`--allow-docker`** — передать в `run_grand_pipeline.py` флаг `--allow-docker` (sandbox для инструментов).
- **`--once`** — выполнить один цикл и выйти (удобно для cron или ручного запуска).
- **`--maintenance-only`** — только maintenance: генерация/обновление аудита и идей, без запуска pipeline.

## Файлы состояния

- **`scripts/.grand_pipeline_state.json`** — последний успешно выполненный шаг, время, статус. Используется для resume. Записывается из `run_grand_pipeline.py` при вызове с `--state-file`. При ошибке Codex (403/rate limit) в state пишется **`codex_retry_after_ts`** (Unix timestamp): когда можно снова запускать. Это значение извлекается из stderr Codex («try again in X seconds/minutes/hours»); если в выводе нет подсказки — **повторная попытка через 30 минут** (пробуем каждые полчаса).
- **`scripts/.grand_pipeline_steps.json`** — текущий набор шагов pipeline (JSON-массив с полями id, name, prompt). Если файл есть — используется он; иначе встроенный список из `run_grand_pipeline.py`. После **мозгового штурма** агент перезаписывает этот файл новым pipeline.
- **`scripts/.pipeline_run.lock`** — lock текущего запуска (PID + timestamp). Если процесс умер, lock старше 6 часов считается мёртвым и сбрасывается.

## Сброс лимита Codex (когда запускать оборвавшийся pipeline)

Планировщик читает из state поле **`codex_retry_after_ts`**. Если оно задано и время ещё не наступило, перед следующим запуском pipeline выполняется ожидание до этого момента (но не более 6 ч). Так следующий запуск приходится на время после сброса лимита. Значение `codex_retry_after_ts` записывает `run_grand_pipeline.py` при ненулевом exit code Codex, парся сообщение вида «try again in X seconds/minutes/hours» из stderr. Если парсинг не удался (например, 403 с HTML-телом), в state пишется время «сейчас + 30 минут» — планировщик будет пробовать каждые полчаса, пока лимит не сбросится.

## Maintenance (работа не прекращается)

В **каждом** цикле выполняется **maintenance** — даже при занятом lock или после pipeline/brainstorm:

1. **Аудит** — генерация или обновление `docs/audit-scheduled-YYYY-MM-DD.md` (что сделано, бэклог, риски). Если Codex недоступен — шаблон для ручного заполнения.
2. **Идеи** — добавление записей в `docs/ideas-backlog.md`. При недоступности Codex — placeholder с датой.
3. **Рефакторинг/улучшение** — одна небольшая задача: из бэклога идей, техдолг (качество кода, тесты, документация) или мелкое исправление; изменение в коде/тестах/доках и коммит с сообщением `maintenance: ...`.

Таким образом работа не прекращается: в каждом цикле — либо шаги pipeline, либо мозговой штурм, плюс всегда аудит, идеи и одна задача по рефакторингу или улучшению.

## Мозговой штурм (после завершения pipeline)

Скрипт **`scripts/run_brainstorm.py`** вызывается планировщиком, когда все шаги текущего pipeline выполнены. Агент (Codex) изучает документацию репозитория и при возможности интернет, затем:
- добавляет идеи в `docs/ideas-backlog.md`;
- создаёт или обновляет `docs/next-pipeline-plan-YYYY-MM-DD.md`;
- записывает новый pipeline в **`scripts/.grand_pipeline_steps.json`** (JSON-массив шагов с id, name, prompt).

После успешного brainstorm state сбрасывается (last_step_completed=0), и следующий цикл планировщика запускает уже новый pipeline с шага 1. Ручной запуск: `python3 scripts/run_brainstorm.py --allow-docker --reset-state`.

## Запуск в фоне

```bash
cd /path/to/ProductFactory
nohup python3 scripts/scheduled_pipeline_runner.py --allow-docker >> logs/scheduled_runner.log 2>&1 &
```

Или через systemd/tmux по необходимости. Логи можно смотреть в реальном времени: `tail -f logs/scheduled_runner.log`.

## Связь с Grand Pipeline

- План и чеклист: [grand-pipeline-plan.md](grand-pipeline-plan.md), [grand-pipeline-tasks.md](grand-pipeline-tasks.md).
- Скрипт pipeline: [run_grand_pipeline.py](../scripts/run_grand_pipeline.py).
- Ручной запуск с определённого шага: `python3 scripts/run_grand_pipeline.py --from-step 10 --to-step 38 --allow-docker --state-file scripts/.grand_pipeline_state.json`.
