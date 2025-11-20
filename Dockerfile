FROM maven:3.9-eclipse-temurin-25-alpine AS build

WORKDIR /app

# Copy local prebuilt library into Maven local repo
COPY libs/*.jar /root/.m2/repository/doda25/team5/lib-version/1.0.1/lib-version-1.0.1.jar

# Copy pom and source
COPY pom.xml .
COPY src ./src

# Build offline using the local library
RUN mvn -o -DskipTests package

###############################
#        RUNTIME STAGE
###############################
FROM eclipse-temurin:25-jdk

WORKDIR /app

ENV APP_PORT=8080
ENV MODEL_HOST=http://host.docker.internal:8081

# Copy the packaged JAR
COPY --from=build /app/target/*.jar app.jar

EXPOSE ${APP_PORT}

ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${APP_PORT} --model.host=${MODEL_HOST}"]
