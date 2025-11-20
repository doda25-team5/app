FROM maven:3.9-eclipse-temurin-25-alpine AS base

######################
# STAGE 1: Get Dependencies
FROM base AS dependencies

WORKDIR /app

# copy the manually provided lib-version JAR + POM
COPY lib-artifact /tmp/lib

# install the artifact into local m2 repo
RUN mvn install:install-file \
    -Dfile=/tmp/lib/lib-version-1.0.1.jar \
    -DpomFile=/tmp/lib/pom.xml \
    -DgroupId=doda25.team5 \
    -DartifactId=lib-version \
    -Dversion=1.0.1 \
    -Dpackaging=jar

COPY pom.xml .

# download all other dependencies (will now work because lib-version exists)
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
