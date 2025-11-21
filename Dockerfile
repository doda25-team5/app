###########################################
#           BUILD STAGE
###########################################
FROM maven:3.9-eclipse-temurin-25-alpine AS build

WORKDIR /app

# Copy pom + settings.xml first (for layer caching)
COPY pom.xml settings.xml ./

# Download dependencies (requires PAT secret)
RUN --mount=type=secret,id=MAVEN_TOKEN \
    export MAVEN_TOKEN=$(cat /run/secrets/MAVEN_TOKEN) && \
    mvn -s settings.xml -B dependency:go-offline

# Copy source AFTER dependencies (good caching)
COPY src ./src

# Build the JAR (requires PAT again)
RUN --mount=type=secret,id=MAVEN_TOKEN \
    export MAVEN_TOKEN=$(cat /run/secrets/MAVEN_TOKEN) && \
    mvn -s settings.xml -B clean package -DskipTests


###########################################
#           RUNTIME STAGE
###########################################
FROM eclipse-temurin:25-jdk-alpine

WORKDIR /app

ENV APP_PORT=8080
ENV MODEL_HOST=http://host.docker.internal:8081

COPY --from=build /app/target/*.jar app.jar

EXPOSE ${APP_PORT}

ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${APP_PORT} --model.host=${MODEL_HOST}"]
