# Builds the attendance web application. Used by docker-compose.yml; you normally do not run this directly.
# The official Maven image already contains Maven (same version as the wrapper), so the build does not
# depend on a separate one-shot download of Maven itself; library downloads are retried by Maven.
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /src/target/attendance-api-1.0.0.jar app.jar
USER app
# Listen on all interfaces inside the container; docker-compose.yml decides who can reach it.
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
