# FactoryRunTest: разбор и варианты

## С чем связано

Отключённый тест `POST factory run returns 200 and accepted when policy allows` проверяет полный сценарий: POST /factory/run с непустым goal → 200, accepted, затем чтение audit-файла и проверка наличия событий (state_changed, tool_call_executed, create_repo_from_archetype, artifact_registry_updated, STAGED, DONE) и файла артефакт-реестра.

**Почему нестабилен (по комментарию в коде):** «в части окружений (Gradle/IDE) шаг tool_call не выполняется или audit не содержит tool_call_executed».

Возможные причины:

1. **CWD (текущая директория)**  
   Тест передаёт в `SandboxToolExecutor` путь к схеме: `Path.of("contracts", "tools.schema.json")` — относительный. Схема загружается в конструкторе (`schemaFactory.getSchema(schemaPath.toUri())`). В Gradle тесты обычно запускаются с CWD = корень проекта → файл есть. В IDE CWD может быть другой (модуль, подкаталог) → файл не найден, при создании executor возможна ошибка или неверная валидация.

2. **Зависимость от корня проекта**  
   Архетип тест создаёт во временной директории (`testRoot.resolve("archetypes/catalog-service")`), а схема — из текущей файловой системы. Один и тот же тест по-разному ведёт себя в зависимости от того, откуда запущен (Gradle vs IDE).

3. **Синхронность и audit**  
   Workflow в `POST /factory/run` выполняется синхронно; ответ отдаётся после завершения. `FileAuditLog` пишет в файл через `appendText`. Теоретически возможна задержка сброса буфера ОС, но маловероятно как основная причина.

Итого: нестабильность скорее всего связана с **окружением (CWD)** и **относительными путями** (схема, при необходимости — другие файлы), а не с асинхронностью или логикой workflow.

---

## Варианты решения

| Вариант | Описание | Плюсы | Минусы |
|--------|----------|--------|--------|
| **A. Оставить как есть** | Тест с `@Ignore`, в implementation-assessment зафиксировано, что e2e проверяется через Docker и `run_live_run.sh` (CI job live-run). | Ничего не менять, одна точка правды (Docker e2e). | В классе остаётся неработающий сценарий. |
| **B. Удалить игнорируемый тест** | Удалить только метод с `@Ignore`, оставить в `FactoryRunTest` остальные тесты (health, empty goal, target_stack). В implementation-assessment и в коде явно указать: полный e2e только в Docker (run_live_run.sh, CI). | Нет «мёртвого» кода, меньше путаницы. | Меньше напоминания в коде о полном сценарии. |
| **C. Сделать тест независимым от CWD** | В тесте не использовать относительный путь к схеме: либо копировать `contracts/tools.schema.json` во временную директорию (путь к оригиналу — от `user.dir` или classpath), либо подключать схему как test resource и передавать путь к нему. Архетип и workspace уже во временных каталогах. | Один и тот же тест стабильно работает в Gradle и IDE. | Нужно продумать, откуда брать schema (resources или явный project root). |
| **D. Ослабить проверки** | Оставить тест включённым, но проверять только: 200, accepted, наличие в audit хотя бы `state_changed` (без жёсткой проверки tool_call_executed и полного порядка состояний/артефакта). | Быстрый «дымовой» тест без жёсткой привязки к путям. | Не проверяет полный контур (tool call, artifact registry). |

## Решение

**Дата решения: 2026-02-26.**

**Принятый вариант: C (сделать тест независимым от CWD).**

- В `FactoryRunTest` удалён `@Ignore`, полный e2e-сценарий снова выполняется в unit/integration прогоне.
- Путь к `contracts/tools.schema.json` теперь резолвится устойчиво:
  - сначала из classpath (`src/test/resources/contracts/tools.schema.json`);
  - fallback: поиск `contracts/tools.schema.json` вверх от `user.dir`.
- Каноническое e2e-покрытие через Docker (`scripts/run_live_run.sh` + CI job `live-run`) сохранено как отдельный контур.
- Результат: сценарий `POST /factory/run` стабилизирован в Gradle/IDE/CI без привязки к текущей директории запуска.

---

## Нужна ли эта ситуация

**Нет, сама по себе ситуация не обязательна.**

- Один и тот же сценарий (POST /factory/run → DONE, audit, artifact) уже проверяется:
  - скриптом `scripts/run_live_run.sh`;
  - CI job **live-run** (compose up → run_live_run.sh);
  - runbook и implementation-assessment.
- В unit-тестах остаются: health, empty goal, optional target_stack/budget, approval API, SandboxToolExecutor (в т.ч. create_repo_from_archetype, apply_patch), WorkflowRunner policy/state machine и др.

Имеет смысл явно считать **каноническим e2e** именно прогон в Docker (run_live_run + CI), а не отключённый тест в testApplication. Тогда либо оставляем тест с @Ignore и ссылкой в комментарии (A), либо удаляем его и фиксируем в документации, что полный e2e — только в Docker (B).
