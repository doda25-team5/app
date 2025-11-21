# syntax=docker/dockerfile:1.4

###############################
#         BUILD STAGE
###############################
FROM maven:3.9-eclipse-temurin-25-alpine AS build

# Set the working directory inside the container
WORKDIR /app

# Copy only pom.xml and settings.xml (for dependency caching and GitHub token)
COPY pom.xml settings.xml ./

# Pre-download all Maven dependencies including private GitHub repos
RUN --mount=type=secret,id=GITHUB_TOKEN \
    export GITHUB_TOKEN=$(cat /run/secrets/GITHUB_TOKEN) && \
    mvn -B -s settings.xml dependency:go-offline

# Copy the source code AFTER downloading dependencies (better caching)
COPY src ./src

# Compile and package application into an executable JAR, skipping tests
RUN --mount=type=secret,id=GITHUB_TOKEN \
    export GITHUB_TOKEN=$(cat /run/secrets/GITHUB_TOKEN) && \
    mvn -B -s settings.xml clean package -DskipTests


###############################
#        RUNTIME STAGE
###############################
FROM eclipse-temurin:25-jdk-alpine

# Set working directory inside runtime image
WORKDIR /app

# Default environment variables (can be overridden at runtime)
ENV APP_PORT=8080
ENV MODEL_HOST=http://host.docker.internal:8081

# Copy only the packaged JAR from the build stage (not source code or build tools)
COPY --from=build /app/target/*.jar app.jar

# Expose the application port so the container runtime knows what it listens on
EXPOSE ${APP_PORT}

# Start the Spring Boot application using the configured port + model host
ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${APP_PORT} --model.host=${MODEL_HOST}"]
