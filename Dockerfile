###############################
#         BUILD STAGE
###############################
FROM maven:3.9-eclipse-temurin-25 AS build

# Set the working directory inside the container
WORKDIR /app

# Copy only pom.xml first (enables dependency caching)
COPY pom.xml .

# Pre-download all Maven dependencies to avoid re-downloading on every build
# RUN mvn dependency:go-offline
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*
# Copy the source code AFTER downloading dependencies (better layer caching)
COPY src ./src

# Compile and package application into an executable JAR, skipping tests
RUN mvn clean package -DskipTests


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
