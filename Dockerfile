FROM maven:3.9-eclipse-temurin-25-alpine AS base
WORKDIR /app

# Copy pre-built JAR & POM
COPY lib-version.jar /tmp/lib-version.jar
COPY lib-version.pom /tmp/lib-version.pom
COPY pom.xml .

# Install into local Maven repo used for offline builds
RUN mvn install:install-file \
    -Dfile=/tmp/lib-version.jar \
    -DpomFile=/tmp/lib-version.pom \
    -DgroupId=doda25.team5 \
    -DartifactId=lib-version \
    -Dversion=1.0.1 \
    -Dpackaging=jar \
    -Dmaven.repo.local=.m2repo

# Resolve all dependencies offline
RUN mvn -B -Dmaven.repo.local=.m2repo dependency:resolve dependency:resolve-plugins

# Copy source and build offline
COPY src ./src
RUN mvn -DskipTests -Dmaven.repo.local=.m2repo clean package
