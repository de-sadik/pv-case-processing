FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

COPY backend/pom.xml pom.xml
RUN mvn dependency:go-offline -B

COPY backend/src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:17-jre

RUN groupadd --system appgroup && \
    useradd --system --gid appgroup appuser

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]