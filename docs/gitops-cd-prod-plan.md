# GitOps CD план: продвижение staging -> prod

Цель документа: зафиксировать процедуру продвижения артефактов из `staging` в `prod` в GitOps-стиле.
Документ описывает операционную реализацию в репозитории (desired state + promotion script), без внедрения новой инфраструктуры (Argo CD/Ingress/Service Mesh и т.д.).

## 1) Схема продвижения артефактов (GitOps-style)

```text
Build + test + security gates (CI)
        ->
Publish immutable artifact (image@sha256:...)
        ->
Update staging desired state (git commit)
        ->
Staging deploy + smoke + наблюдаемость
        ->
Promotion PR to prod desired state (same digest)
        ->
Approval (по risk/profile policy)
        ->
Merge + GitOps sync
        ->
Prod verification (health + SLO + бизнес smoke)
```

Ключевые принципы:
- Продвигается только неизменяемый артефакт (digest), уже проверенный в `staging`.
- `prod` не собирается заново: используется тот же `image@sha256`, что прошёл проверки.
- Единственный механизм изменения окружений: commit/PR в GitOps-репозитории desired state.

## 2) Процедура `staging -> prod`

1. Подтвердить готовность артефакта в `staging`:
- smoke/health успешны;
- нет блокирующих алертов;
- качество и security gates пройдены.
2. Создать promotion PR в GitOps-ветку `prod`:
- изменить только версию/дижест образа (и связанные release-метки при необходимости);
- указать ссылку на staging run и результаты проверок.
3. Пройти approvals по policy (особенно для `medium/high` risk tiers).
4. Выполнить merge PR.
5. Дождаться применения desired state (`prod`) вашим GitOps-рантаймом.
6. Выполнить post-deploy проверку:
- `health/ready`;
- ключевой бизнес smoke;
- контроль SLO/ошибок в коротком окне наблюдения (например, 15-30 минут).
7. Зафиксировать результат в release log/ADR (если изменение нетривиальное).

### Практическая реализация в репозитории

- `deploy/staging/docker-compose.yml` + `deploy/staging/gitops.env` — staging desired state.
- `deploy/prod/docker-compose.yml` + `deploy/prod/gitops.env` — prod desired state.
- `deploy/prod-canary/docker-compose.yml` + `deploy/prod-canary/gitops.env` — canary desired state.
- `scripts/gitops_promote_staging_to_prod.sh` — переносит один и тот же `CATALOG_SERVICE_IMAGE` (только `image@sha256`) в выбранный target (`prod|canary|all`).
- `scripts/gitops_apply_env.sh` — применяет desired state для `staging|prod|prod-canary` и может сразу выполнять smoke (`--smoke`).

Базовый promotion:

```bash
scripts/gitops_promote_staging_to_prod.sh
git add deploy/prod/gitops.env
git commit -m "promote: catalog-service <digest> to prod"
git push
```

Canary-first:

```bash
TARGET=canary scripts/gitops_promote_staging_to_prod.sh
git add deploy/prod-canary/gitops.env
git commit -m "canary: catalog-service <digest>"
git push
```

## 3) Опциональный canary (частичный трафик)

Использовать canary, если риск изменения выше низкого или есть неопределённость по нагрузке/поведению.

Рекомендуемая процедура:
1. Продвинуть новый digest в `prod-canary` desired state (или эквивалентную canary-конфигурацию).
2. Направить малую долю трафика на canary (например, 5%).
3. Наблюдать окно стабильности (ошибки, latency, saturation, бизнес-метрики).
4. Если стабильно:
- увеличить долю (например, 5% -> 25% -> 50% -> 100%);
- на каждом шаге повторять проверку.
5. Если деградация:
- остановить увеличение;
- вернуть трафик на стабильную версию;
- выполнить rollback по GitOps-процедуре.

Примечание: конкретный механизм маршрутизации (Ingress/mesh/controller) выбирается отдельно, вне scope этого документа.

## 4) Ссылки на текущие артефакты и rollback

- Staging desired state и правила обновления: [deploy/staging/README.md](../deploy/staging/README.md)
- Prod desired state: [deploy/prod/README.md](../deploy/prod/README.md)
- Prod canary desired state: [deploy/prod-canary/README.md](../deploy/prod-canary/README.md)
- Команды/процедура отката: [docs/runbook.md#Откат деплоя (GitOps rollback)](runbook.md#откат-деплоя-gitops-rollback)

## 5) Границы документа

- Это операционная схема и процедура.
- Документ не добавляет новую инфраструктуру и не изменяет runtime фабрики.
