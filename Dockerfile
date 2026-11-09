FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY java/pom.xml ./pom.xml
COPY java/src/main ./src/main
RUN mvn --batch-mode package dependency:copy-dependencies -DincludeScope=runtime -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre
ARG SOURCE_REVISION=unknown
ARG APP_VERSION=0.4.0
LABEL org.opencontainers.image.title="flamoris-pudding" \
      org.opencontainers.image.version=$APP_VERSION \
      org.opencontainers.image.revision=$SOURCE_REVISION \
      org.opencontainers.image.licenses="Apache-2.0"
WORKDIR /app
COPY --from=build /build/target/classes ./classes
COPY --from=build /build/target/dependency ./lib
COPY LICENSE /app/LICENSE
RUN printf '%s\n' "$APP_VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$' \
    && printf '%s\n' "$APP_VERSION" > /app/classes/pudding-version.txt
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD ["java", "-cp", "/app/classes:/app/lib/*", "net.flamoris.pudding.ServiceMain", "healthcheck"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/classes:/app/lib/*", "net.flamoris.pudding.ServiceMain"]

