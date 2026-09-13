FROM gradle:8.7-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon clean test installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/install/catalog-service /app
EXPOSE 8080
ENTRYPOINT ["/app/bin/catalog-service"]
