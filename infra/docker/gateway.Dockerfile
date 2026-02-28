FROM eclipse-temurin:24-jdk

WORKDIR /app

# Warm Gradle wrapper + dependency cache in separate layers.
COPY gateway/gradlew /app/gradlew
COPY gateway/gradle /app/gradle
COPY gateway/settings.gradle /app/settings.gradle
COPY gateway/build.gradle /app/build.gradle

RUN chmod +x /app/gradlew \
    && /app/gradlew --no-daemon dependencies

COPY gateway/src /app/src
COPY infra/docker/gateway-entrypoint.sh /usr/local/bin/gateway-entrypoint.sh

RUN chmod +x /app/gradlew /usr/local/bin/gateway-entrypoint.sh \
    && /app/gradlew --no-daemon classes

EXPOSE 4317 4318
ENTRYPOINT ["/usr/local/bin/gateway-entrypoint.sh"]
