# Stage 1: Build the application
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /app
COPY pom.xml .
# Download dependencies to cache them
RUN mvn dependency:go-offline -B
COPY src ./src
# Build the application skipping tests (since tests require Testcontainers/Docker)
RUN mvn clean package -DskipTests

# Stage 2: Run the application
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/target/wallet-0.0.1-SNAPSHOT.jar app.jar

# Expose standard Spring Boot port
EXPOSE 8080

# Healthcheck targeting the liveness probe
HEALTHCHECK --interval=30s --timeout=3s \
  CMD wget -q -O - http://localhost:8080/healthz || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
