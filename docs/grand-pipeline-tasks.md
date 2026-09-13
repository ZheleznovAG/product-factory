# Задачи Grand Pipeline — чеклист

Пайплайн «по-взрослому»: всё реализуемое, кроме тяжёлого/фантастического (глаза/мимика, демонстрация примеров, психометрика, био/нейро).

**План:** [grand-pipeline-plan.md](grand-pipeline-plan.md). **Скрипт:** [run_grand_pipeline.py](../scripts/run_grand_pipeline.py).

**Запуск:** `python3 scripts/run_grand_pipeline.py --allow-docker`

**Проверка по коду (2026-02-27):** отмечено [x] то, что уже реализовано в репозитории; остаётся ~15 пунктов (RAG, окружения, AI-risk, GitOps CD, role registry, веб-форма, доки).

---

## Блок 1 — Intent: текст и аудио
- [x] **1** LlmIntentClarification + промпт и fallback
- [x] **2** Адаптивные вопросы и раннее завершение
- [x] **3** Короткий свободный ответ в протоколе
- [x] **4** LlmIntentGenerator и LlmIntentCandidatesGenerator
- [ ] **5** Документация уточнения и аудио-слой

## Блок 2 — API intent/experience
- [x] **6** Маршруты POST /intent/estimate и /experience/generate
- [x] **7** Runbook intent → factory run

## Блок 3–6 — SBOM, SLO, cost, окружения
- [x] **8** Источник SBOM/signature в run
- [x] **9** SLO CI gate в ci.yml
- [ ] **10** Prometheus rules и Grafana SLO/cost
- [ ] **11** Онлайн-сигналы для promotion
- [x] **12** Хранение счётчиков за период
- [x] **13** Проверка лимита перед run и конфиг cost
- [ ] **14** ADR local-docker и remote-ssh
- [ ] **15** Local-docker выполнение в контейнере
- [ ] **16** Remote-ssh runner или out of scope

## Блок 7–10 — RAG, комплаенс, GitOps, multi-tenancy
- [ ] **17** RAG ingestion + pgvector
- [ ] **18** Offline eval RAGAs и датасеты
- [x] **19** SLSA provenance и аттестации
- [ ] **20** Контур AI-risk
- [ ] **21** Release с SBOM/подписью и high-risk approval
- [ ] **22** GitOps CD до prod
- [ ] **23** Canary и откат
- [x] **24** Изоляция по tenant
- [ ] **25** Portal/CLI self-serve

## Блок 11–16 — Preference, WS1/2/3 v1, manifest, session
- [x] **26** Хранилище профиля предпочтений
- [x] **27** Обновление профиля по выборам и API сброс
- [ ] **28** Role registry и шаблоны по ролям
- [x] **29** Провижининг и headless browser
- [ ] **30** Веб-форма решений и спринт-поинты
- [x] **31** Контракт artifact_manifest.json
- [x] **32** Заполнение manifest в run и воспроизведение
- [x] **33** session в потоке и референсы 6 карточек

## Блок 17–18 — Тесты и документация
- [x] **34** FactoryRunTest стабильный
- [x] **35** Тесты ask_user и intent API
- [x] **36** E2E скрипт intent → run
- [ ] **37** ADR 0011/0012 и implementation-assessment
- [ ] **38** Runbook полный и risk-register

---

Отмечать по коммитам `grand-pipeline: шаг N — ...`.
