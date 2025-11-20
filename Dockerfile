FROM maven:3.9-eclipse-temurin-25-alpine AS base

######################
# STAGE 1: Get Dependencies
FROM base AS dependencies

WORKDIR /app

# copy provided lib-version jar and pom
COPY lib-version.jar /tmp/lib-version.jar
COPY lib-version.pom /tmp/lib-version.pom

# install into local maven repo
RUN mvn install:install-file \
    -Dfile=/tmp/lib-version.jar \
    -DpomFile=/tmp/lib-version.pom \
    -DgroupId=doda25.team5 \
    -DartifactId=lib-version \
    -Dversion=1.0.1 \
    -Dpackaging=jar

COPY pom.xml .

# Download dependencies and plugins offline
RUN mvn -B -Dmaven.repo.local=.m2repo \
    dependency:resolve \
    dependency:resolve-plugins

#######################
# STAGE 2: Build
FROM base AS build

WORKDIR /app

COPY --from=dependencies /app /app

COPY src ./src

RUN mvn -o -DskipTests -Dmaven.repo.local=.m2repo package
