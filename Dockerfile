# syntax=docker/dockerfile:1.7
# ============ BUILD STAGE ============
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy only pom.xml first (for better caching)
COPY pom.xml .

# Copy Maven settings.xml (must be included in repo or generated from secret)
COPY settings.xml /root/.m2/settings.xml

# Download dependencies offline
RUN mvn -B -s /root/.m2/settings.xml dependency:go-offline -DexcludeReactor=false

# Copy source code
COPY src ./src

# Build the JAR
RUN mvn -B -s /root/.m2/settings.xml clean package -DskipTests

# ============ RUNTIME STAGE ============
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root user
RUN addgroup -g 1001 appgroup && \
    adduser -D -u 1001 -G appgroup appuser
USER appuser

# Copy built JAR
COPY --from=build --chown=appuser:appgroup /app/target/*.jar app.jar

# Environment variables
ENV APP_PORT=8080 \
    MODEL_HOST=http://host.docker.internal:8081

EXPOSE ${APP_PORT}

ENTRYPOINT ["java", "-jar", "/app/app.jar", \
    "--server.port=${APP_PORT}", \
    "--model.host=${MODEL_HOST}"]
