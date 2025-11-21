# -------- BUILD STAGE --------
FROM maven:3.9-eclipse-temurin-25-alpine AS build

WORKDIR /app

# Copy pom.xml and settings.xml
COPY pom.xml settings.xml ./

# Pre-download dependencies
RUN mvn -B -s settings.xml dependency:go-offline

# Copy the rest of the project
COPY src ./src

# Build the JAR
RUN mvn -B -s settings.xml clean package -DskipTests



# -------- RUNTIME STAGE --------
FROM eclipse-temurin:25-jdk-alpine

WORKDIR /app

ENV APP_PORT=8080
ENV MODEL_HOST=http://host.docker.internal:8081

COPY --from=build /app/target/*.jar app.jar

EXPOSE ${APP_PORT}

ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${APP_PORT} --model.host=${MODEL_HOST}"]
