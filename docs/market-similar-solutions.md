# Похожие решения на рынке

Краткий обзор продуктов и платформ, которые пересекаются с идеей Product Factory: генерация кода/продукта из описания, мульти-агенты, автоматизация от спецификации до артефакта. Сравниваем не «кто круче», а **какую часть системы они закрывают**. Позиционирование PF — [strategy-differentiation-and-kernel.md](strategy-differentiation-and-kernel.md). Данные — по состоянию на 2024–2026 гг.; актуальность уточняйте на сайтах.

---

## Рынок по классам (сильнее / слабее PF)

| Класс | Примеры | Сильнее PF сейчас | Слабее PF (потенциал) |
|-------|---------|-------------------|------------------------|
| **A) IDP / Golden Paths** | Backstage (Software Templates), Harness IDP | Портал, UX, экосистема плагинов, enterprise adoption | Нет встроенного *agentic control plane*: детерминированный workflow + OPA + tools + audit как единое ядро (часто «портал», а не «фабрика-исполнитель») |
| **B) Cloud Dev + blueprints + AI** | AWS CodeCatalyst (blueprints, Dev Environments, Amazon Q) | Облачные окружения «из коробки», интеграция с экосистемой | Сложнее сделать vendor-agnostic и OPA-first governance под произвольную инфраструктуру |
| **C) VCS/DevSecOps с агентами** | GitLab Duo Agent Platform (GA 2026), GitHub Copilot coding agent | Бесшовная интеграция в GitHub/GitLab (issues, PR/MR, actions/pipelines), готовая операционка | PF может быть радикально гибче: любые tools, любые policy, любая инфраструктура; контроль и воспроизводимость строже, чем у коробочных агентов |
| **D) OSS SWE-агенты** | SWE-agent, OpenDevin, Open SWE (LangChain) | Agent loop (plan→act→observe→fix), интеграции с GitHub issues/PR | Часто слабее governance: policy/approvals/аттестации/enterprise security; слабее «platform продукт» (окружения, бюджеты, портал) — **это шанс PF** |

**Дифференциатор PF:** policy-driven multi-agent factory с доказуемыми артефактами (все side-effects через tools, OPA allow/deny/approval, provenance/audit, одинаково local/CI/k8s). См. [strategy-differentiation-and-kernel.md](strategy-differentiation-and-kernel.md).

---

## 1. Платформы «спецификация → код/продукт» и мульти-агенты

| Продукт | Что делает | Отличие от Product Factory |
|--------|------------|----------------------------|
| **Factory.ai** (factory.ai) | Enterprise-платформа с AI-агентами («Droid»). **Specification Mode**: планирование и изучение кодовой базы до написания кода; пользователь утверждает план, затем выполнение. Поддержка air-gap, политики, аудит, SOC2/ISO. | Ближе всего по идее «фабрика с контролем»: план → утверждение → выполнение. Коммерческий, enterprise; нет открытого ядра. |
| **AgentCodeFactory** (agentcodefactory.com) | Мульти-агентный пайплайн: спецификация → дизайн → реализация → тесты → код-ревью. Несколько LLM (OpenAI, Anthropic, Mistral, Ollama), стеки (Python, TypeScript, Go, Rust, Java), OWASP-скан. Есть бесплатная **ACF Local Edition** (офлайн). | Роли по этапам (spec, design, impl, test, review) — аналог «разные роли». Не фокус на архетипах и политиках как в PF. |
| **AutoGen Studio** (Microsoft) | No-code интерфейс для мульти-агентных сценариев на базе AutoGen: drag-and-drop workflow, отладка, галерея агентов. Исследовательский проект (Microsoft Research, 2024). | Универсальные мульти-агенты, не заточены под «продукт из архетипа»; нет встроенного Tool Executor и OPA-политик как в PF. |
| **Bolt.new** (StackBlitz) | Генерация полноценного веб-приложения из промпта в браузере (WebContainers). Full-stack, деплой, БД, аутентификация. Два агента (v1 быстрый, Claude — «production». | Удобно для прототипов и веба; один поток «промпт → приложение», без явных ролей, политик и артефакт-реестра как в PF. |

---

## 2. Автономные агенты-разработчики (AI coding agents)

| Продукт | Что делает | Отличие от Product Factory |
|--------|------------|----------------------------|
| **Devin** (devin.ai) | Автономный агент: получает задачу, планирует, пишет код, дебажит, может пушить PR. Человек подключается на чекпоинтах. Закрытая бета. | Один агент «под ключ», без разделения Control Plane / Intelligence Plane и без ваших архетипов и OPA. |
| **OpenDevin** (open source) | Открытая платформа «агент как разработчик»: код, CLI, браузер, песочница, мульти-агентная координация, бенчмарки. MIT. | Близко по духу к «агент делает код»; нет концепции фабрики артефактов, политик и реестра как в PF. Класс D. |
| **SWE-agent** (OSS) | Агент, автономно решающий задачи в реальных GitHub-репозиториях через инструменты. | Силён в agent loop; слабее governance (policy/approvals/аттестации). Класс D. |
| **GitHub Copilot coding agent** | Автономное выполнение задач в окружении на базе GitHub Actions, открытие PR. | Интеграция в GitHub (класс C); контроль и воспроизводимость — менее строгие, чем у PF. |
| **GitLab Duo Agent Platform** (GA 2026) | Agentic-flows: Issue→MR, миграция CI/CD, фиксы пайплайна, code review. | Класс C; полная интеграция в GitLab. |
| **Cursor** (cursor.com) | IDE с AI: со-редактирование, агенты, недавно — интеграция со Slack для постановки задач. Разработчик остаётся в цикле. | Инструмент разработчика, а не фабрика «запрос → артефакт»; нет workflow, tools, артефакт-реестра. |
| **Codex / OpenAI** | Модели и API для кода; используются во многих инструментах. | Движок, а не готовая фабрика с пайплайном и политиками. |

---

## 3. Enterprise: деплой, шаблоны, голубые отпечатки

| Продукт | Что делает | Отличие от Product Factory |
|--------|------------|----------------------------|
| **Google Cloud** (Application Blueprint, Config Sync, CI/CD) | Git-driven пайплайны, шаблоны приложений, автоматизированный деплой. | Фокус на инфраструктуре и деплое, не на генерации продукта из goal и не на агентах. |
| **AWS** (Blueprint Factory, Service Catalog) | Шаблоны CloudFormation/CDK, каталог сервисов, конфигурация пайплайнов. | То же: инфраструктура и шаблоны, без AI-агентов и «запрос → репо». |
| **NVIDIA AI Factory** | Enterprise AI: инфраструктура и пайплайны для обучения и инференса моделей. | Про ML-инфраструктуру, не про генерацию софт-продуктов. |

---

## 4. Сводка: где кто

- **«Спека → план → утверждение → код»:** Factory.ai, отчасти AgentCodeFactory (этапы как роли).
- **«Промпт → приложение в браузере + деплой»:** Bolt.new, Replit (AI-режимы).
- **«Один автономный агент-разработчик»:** Devin, OpenDevin.
- **«Мульти-агенты без жёсткого продукт-пайплайна»:** AutoGen Studio, различные фреймворки (CrewAI, LangGraph и т.п.).
- **«Контроль, политики, архетипы, audit»:** в явном виде и в одном продукте — редко; ближе всего Factory.ai (enterprise) и Product Factory (открытое ядро, архетипы, OPA, Tool Executor, реестр артефактов).

Product Factory выделяется сочетанием: **детерминированное ядро** (state machine, OPA, Tool Executor, audit) + **опциональный LLM** (планировщик, кодоген) + **архетипы** (копия + опционально GitHub/S3) + развёртывание **on-prem / в своём контуре**. Расширенное позиционирование и модель «Kernel + плагины» — [strategy-differentiation-and-kernel.md](strategy-differentiation-and-kernel.md).

---

## 5. Полезные ссылки (проверяйте актуальность)

- Factory.ai: docs.factory.ai  
- AgentCodeFactory: agentcodefactory.com  
- AutoGen Studio: Microsoft Research, публикации и репозитории по AutoGen  
- Bolt.new: bolt.new  
- OpenDevin: opendevin.github.io (и репозиторий на GitHub)  
- SWE-agent: github.com/SWE-agent/SWE-agent  
- Devin: devin.ai  
- Cursor: cursor.com  
- Backstage Software Templates: backstage.io/docs/features/software-templates  
- Harness IDP: developer.harness.io (Internal Developer Portal)  
- GitLab Duo / Agent Platform: about.gitlab.com  
- GitHub Copilot coding agent: docs.github.com (Copilot / agents)  
- AWS CodeCatalyst: docs.aws.amazon.com/codecatalyst  

Для roadmap мульти-ролей, окружений и human loop — см. [roadmap-workstreams-and-variants.md](roadmap-workstreams-and-variants.md). Стратегия и дифференциация — [strategy-differentiation-and-kernel.md](strategy-differentiation-and-kernel.md).
