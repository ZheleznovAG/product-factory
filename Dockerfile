# Build stage: JDK 17 + Gradle wrapper (без загрузки Gradle по curl; целостность через wrapper jar в репо).
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

# git нужен для тестов (apply_patch, push_repo_to_github).
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

# Sources, контракты, Gradle wrapper (gradlew + gradle/wrapper/*).
COPY gradlew ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src src
COPY contracts contracts

RUN chmod +x gradlew

# Сборка и тесты в Docker. Кэш слоёв Gradle: ~/.gradle.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew build installDist --no-daemon

# Run stage: JRE + distribution + policies (+ curl для healthcheck в docker-compose)
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl git \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app

COPY --from=build /app/build/install/product-factory /app
COPY policies /app/policies
COPY contracts /app/contracts
RUN chmod +x /app/bin/*

ENV AUDIT_LOG_PATH=/app/audit.log
EXPOSE 8080
ENTRYPOINT ["/app/bin/product-factory"]
