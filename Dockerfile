FROM maven:3.9-eclipse-temurin-25-alpine AS builder
WORKDIR /app

# Download all dependencies (including lib-version from GitHub Packages)
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Build the app
COPY src ./src
RUN mvn -B -DskipTests package

# Final stage
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]