# Catalog Service

Микросервис каталога товаров: REST API на Ktor, health endpoints, миграции БД (архетип Product Factory).

## Требования

- JDK 17+
- Gradle (wrapper в репозитории)

## Сборка и запуск

```bash
./gradlew build
./gradlew run
```

По умолчанию приложение слушает порт 8080.

- Health: `GET /health`, `GET /health/ready`
- API каталога: см. `src/main/kotlin/catalog/api/CatalogRoutes.kt`

## Тесты

```bash
./gradlew test
```

## Docker

```bash
docker build -t catalog-service .
docker run -p 8080:8080 catalog-service
```

## Структура

- `src/main/kotlin/catalog/` — Application, API, сервисный слой
- `src/main/resources/db/migrations/` — миграции БД
- `src/test/` — unit и интеграционные тесты
