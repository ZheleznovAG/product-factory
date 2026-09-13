# Web App

Минимальное Ktor-приложение (архетип Product Factory).

## Сборка и запуск

```bash
./gradlew build
./gradlew run
```

По умолчанию приложение слушает порт 8080. Health: `GET /health`.

## Docker

```bash
docker build -t web-app .
docker run -p 8080:8080 web-app
```

## Структура

- `src/main/kotlin/webapp/` — маршруты и точка входа
- `src/test/` — интеграционные тесты
