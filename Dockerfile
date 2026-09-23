FROM maven:3.8.8-eclipse-temurin-11 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:11-jre-jammy
RUN useradd --system --uid 1001 app \
    && mkdir -p /var/lib/heat \
    && chown app /var/lib/heat
USER app
WORKDIR /app
COPY --from=build /src/target/heat-routing.jar app.jar
ENV HEAT_TEMP_DIR=/var/lib/heat \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
