# syntax=docker/dockerfile:1.7
# (enables build secrets support even in older Docker versions)

# ============ BUILD STAGE ============
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy only pom.xml first (enables better Docker layer caching)
COPY pom.xml .

# Download dependencies using a securely mounted settings.xml (never baked into image!)
# → In CI: pass your PAT via secret
# → Locally: pass your real ~/.m2/settings.xml
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -B -s /root/.m2/settings.xml dependency:go-offline -DexcludeReactor=false

# Copy source code
COPY src ./src

# Build the final JAR (again using the secret-mounted settings.xml)
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -B -s /root/.m2/settings.xml clean package -DskipTests

# ============ RUNTIME STAGE ============
# Use JRE (not JDK) for smaller final image (~80 MB vs ~250 MB)
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root user (security best practice)
RUN addgroup -g 1001 appgroup && \
    adduser -D -u 1001 -G appgroup appuser
USER appuser

# Copy the built JAR
COPY --from=build --chown=appuser:appgroup /app/target/*.jar app.jar

# Environment variables (can be overridden at runtime)
ENV APP_PORT=8080 \
    MODEL_HOST=http://host.docker.internal:8081

EXPOSE ${APP_PORT}

# Clean entrypoint (no shell needed)
ENTRYPOINT ["java", "-jar", "/app/app.jar", \
    "--server.port=${APP_PORT}", \
    "--model.host=${MODEL_HOST}"]