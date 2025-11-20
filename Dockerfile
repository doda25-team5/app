FROM maven:3.9-eclipse-temurin-25-alpine AS builder
WORKDIR /app

# Copy pom and settings
COPY pom.xml .
COPY settings.xml .

# Download dependencies using the token
RUN --mount=type=secret,id=GITHUB_TOKEN \
    export GITHUB_TOKEN=$(cat /run/secrets/GITHUB_TOKEN) && \
    mvn -B -s settings.xml dependency:go-offline

# Build
COPY src ./src
RUN --mount=type=secret,id=GITHUB_TOKEN \
    export GITHUB_TOKEN=$(cat /run/secrets/GITHUB_TOKEN) && \
    mvn -B -s settings.xml -DskipTests package