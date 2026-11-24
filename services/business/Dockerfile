FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/business-1.0.0-SNAPSHOT.jar app.jar
USER 1000:1000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
