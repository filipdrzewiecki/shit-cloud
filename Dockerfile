# The API only. The GUI is a separate service with its own image — see
# frontend/Dockerfile — so nothing here knows about npm. The one file taken from frontend/ is the builder guide,
# which is also the build chat's system prompt.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
COPY frontend/src/guide/builder-guide.md ./frontend/src/guide/
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/shit-cloud.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
