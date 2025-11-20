FROM maven:3.9-eclipse-temurin-25-alpine AS build

WORKDIR /app

COPY lib-version.jar /tmp/lib-version.jar
COPY lib-version.pom /tmp/lib-version.pom

# Install lib-version locally
RUN mvn install:install-file \
    -Dfile=/tmp/lib-version.jar \
    -DpomFile=/tmp/lib-version.pom \
    -DgroupId=doda25.team5 \
    -DartifactId=lib-version \
    -Dversion=1.0.1 \
    -Dpackaging=jar

COPY pom.xml .
COPY src ./src

# Build offline
RUN mvn -B -o -DskipTests -Dmaven.repo.local=.m2repo package
