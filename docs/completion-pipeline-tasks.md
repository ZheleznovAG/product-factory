# Пайплайн завершения — чеклист задач

Актуальный список из **16 шагов** — только то, что ещё не закрыто (проверка по коду 2026-02-27).

**План:** [completion-pipeline.md](completion-pipeline.md). **Скрипт:** [run_completion_pipeline.py](../scripts/run_completion_pipeline.py).

**Запуск:** `python3 scripts/run_completion_pipeline.py --allow-docker`

---

## Наблюдаемость и SLO
- [ ] **1** Документация уточнения и аудио-слой
- [ ] **2** Prometheus rules и Grafana SLO/cost
- [ ] **3** Онлайн-сигналы SLO/cost для promotion

## Окружения
- [ ] **4** ADR local-docker и remote-ssh
- [ ] **5** Local-docker и/или remote-ssh по ADR

## RAG и качество
- [x] **6** RAG ingestion + pgvector
- [ ] **7** Offline eval RAGAs и датасеты

## Комплаенс и supply chain
- [ ] **8** Контур AI-risk
- [x] **9** Release с SBOM/подписью и high-risk approval
- [x] **10** SLSA аттестации по политике

## Доставка и UX
- [ ] **11** GitOps CD до prod
- [ ] **12** Canary и откат
- [ ] **13** Role registry и шаблоны по ролям
- [ ] **14** Веб-форма решений (опционально)
- [ ] **15** Portal/CLI self-serve в runbook

## Документация и аудит
- [ ] **16** ADR 0011/0012, implementation-assessment, runbook, risk-register

---

Отмечать по коммитам: `completion-pipeline: шаг N — ...`.
