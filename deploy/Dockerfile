# Образ для поставки: берёт уже собранный jar (target/heat-routing.jar).
# Так образ под другую архитектуру собирается без эмуляции Maven - jar от архитектуры не зависит.
# Сборка: mvn package, затем
#   docker buildx build --platform linux/amd64 -f deploy/Dockerfile -t heat-routing:1.1.0 --load .
FROM eclipse-temurin:11-jre-jammy

RUN useradd --system --uid 1001 app \
    && mkdir -p /var/lib/heat \
    && chown app /var/lib/heat
USER app
WORKDIR /app
COPY target/heat-routing.jar app.jar
ENV HEAT_TEMP_DIR=/var/lib/heat \
    JAVA_OPTS="-Xmx2g"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
