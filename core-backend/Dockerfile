FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
ENV MAVEN_OPTS="-Xmx384m -XX:MaxMetaspaceSize=192m"
COPY pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 --create-home contextflow
WORKDIR /app
COPY --from=build --chown=contextflow:contextflow /app/target/core-backend-0.1.0-SNAPSHOT.jar app.jar
USER contextflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
