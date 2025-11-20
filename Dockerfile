FROM maven:3.9-eclipse-temurin-25-alpine AS base

######################
# STAGE 1: Get Dependencies
FROM base AS dependencies

WORKDIR /app

COPY pom.xml .

# Download parent pom and transitive dependencies + other needed Maven plugins for mvn package to work offline
COPY libs/*.jar /root/.m2/repository/doda25/team5/lib-version/1.0.1/lib-version-1.0.1.jar

######################
# STAGE 2: Build
FROM base AS build

WORKDIR /app

COPY --from=dependencies /app /app

COPY src ./src

# skip tests for faster build and give location of local Maven repository
RUN mvn -o -DskipTests -Dmaven.repo.local=.m2repo package

###############################
#        RUNTIME STAGE
###############################
FROM eclipse-temurin:25-jdk

# Set working directory inside runtime image
WORKDIR /app

# Default environment variables (may be overridden at runtime)
# F6: Flexible configuration
ENV APP_PORT=8080
ENV MODEL_HOST=http://host.docker.internal:8081

# Copy only the packaged JAR from the build stage (not source code or build tools)
COPY --from=build /app/target/*.jar app.jar

# Expose the application port so the container runtime knows what it listens on
EXPOSE ${APP_PORT}

# Start the Spring Boot application using the configured port + model host
# `sh -c` allows environment variables to be expanded inside the command
ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${APP_PORT} --model.host=${MODEL_HOST}"]
