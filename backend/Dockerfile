FROM eclipse-temurin:21-jdk-alpine

WORKDIR /App

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src

RUN ./mvnw dependency:go-offline
CMD ["./mvnw", "spring-boot:run"]
