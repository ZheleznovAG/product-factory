# Первичные источники (Product Factory)

Ссылки, на которые опираются [системный аудит и corrective-план](system-audit-corrective-plan.md). Обновлять при добавлении новых решений и регуляторики.

## Нормативы и стандарты

| Источник | URL | Использование |
|----------|-----|---------------|
| NIST AI RMF 1.0 (PDF) | https://nvlpubs.nist.gov/nistpubs/ai/nist.ai.100-1.pdf | Управление рисками ИИ |
| NIST AI RMF Playbook | https://airc.nist.gov/airmf-resources/playbook/ | Практики RMF |
| ISO/IEC 42001 | https://www.iso.org/standard/42001 | Системы менеджмента ИИ |
| EU AI Act (Regulation (EU) 2024/1689) | https://eur-lex.europa.eu/eli/reg/2024/1689/oj/eng | Регуляторика ЕС |
| EU AI Act implementation timeline | https://ai-act-service-desk.ec.europa.eu/en/ai-act/eu-ai-act-implementation-timeline | Сроки применения |
| GDPR (EU 2016/679) | https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng | Данные/персонализация |

## Безопасность и supply chain

| Источник | URL | Использование |
|----------|-----|---------------|
| OWASP Top 10 for LLM Apps | https://owasp.org/www-project-top-10-for-large-language-model-applications/ | Риски LLM, prompt injection, excessive agency |
| CISA SBOM Minimum Elements (2025) | https://www.cisa.gov/resources-tools/resources/2025-minimum-elements-software-bill-materials-sbom | Требования к SBOM |
| SLSA requirements v1.0 | https://slsa.dev/spec/v1.0/requirements | Provenance, attestations |

## Policy и наблюдаемость

| Источник | URL | Использование |
|----------|-----|---------------|
| Open Policy Agent (OPA) | https://www.openpolicyagent.org/docs/latest/ | PDP/PEP, decision logs |
| OpenTelemetry OTLP spec | https://opentelemetry.io/docs/specs/otlp/ | Трейсы, метрики, логи |

## Инструменты supply chain

| Источник | URL | Использование |
|----------|-----|---------------|
| Syft (SBOM) | https://github.com/anchore/syft | Генерация SBOM (CycloneDX/SPDX) |
| Trivy (vulnerability) | https://trivy.dev/docs/v0.52/guide/scanner/vulnerability/ | Сканирование, severity |
| Cosign (verify) | https://docs.sigstore.dev/cosign/verifying/verify/ | Подпись/проверка образов |

## LLM/агенты

| Источник | URL | Использование |
|----------|-----|---------------|
| OpenAI Agents SDK | https://platform.openai.com/docs/guides/agents-sdk/ | Агентный контур |
| OpenAI Trace grading | https://platform.openai.com/docs/guides/trace-grading | Оценка трасс |
| OpenAI Function calling (strict mode) | https://platform.openai.com/docs/guides/function-calling/how-do-i-ensure-the-model-calls-the-correct-function | Tool calling, схемы |
| OpenAI Pricing | https://platform.openai.com/docs/pricing/ | Бюджеты cost per run |

## CI/CD и инфра

| Источник | URL | Использование |
|----------|-----|---------------|
| GitHub Actions security hardening | https://docs.github.com/actions/learn-github-actions/security-hardening-for-github-actions | OIDC, секреты, third-party actions |
| Kubernetes learning environment | https://kubernetes.io/docs/setup/learning-environment/ | Оценка сложности K8s |
