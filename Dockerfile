# --------------------------
# STAGE 0: Base image with Maven
# --------------------------
FROM maven:3.9-eclipse-temurin-17-alpine AS base
WORKDIR /app

# --------------------------
# STAGE 1: Prepare dependencies
# --------------------------
FROM base AS dependencies

# Copy pre-built lib-version JAR and POM
COPY lib-version.jar /tmp/lib-version.jar
COPY lib-version.pom /tmp/lib-version.pom
COPY pom.xml .

# Install lib-version.jar into local Maven repo (.m2repo)
RUN mvn install:install-file \
      -Dfile=/tmp/lib-version.jar \
      -DpomFile=/tmp/lib-version.pom \
      -DgroupId=doda25.team5 \
      -DartifactId=lib-version \
      -Dversion=1.0.1 \
      -Dpackaging=jar \
      -Dmaven.repo.local=.m2repo

# Resolve all project dependencies and plugins into local repo
RUN mvn -B -Dmaven.repo.local=.m2repo dependency:resolve dependency:resolve-plugins

# --------------------------
# STAGE 2: Build project
# --------------------------
FROM base AS build
WORKDIR /app

# Copy Maven repo and pom.xml from dependencies stage
COPY --from=dependencies /app/.m2repo .m2repo
COPY pom.xml .

# Copy source code
COPY src ./src

# Build project offline using local Maven repo
RUN mvn -o -DskipTests -Dmaven.repo.local=.m2repo package