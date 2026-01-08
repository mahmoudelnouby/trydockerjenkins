
# syntax=docker/dockerfile:1

# ----------- Build stage -----------
FROM maven:3.8.8-eclipse-temurin-17 AS build

# Consistent app directory.
ENV APP_DIR=/usr/app
WORKDIR ${APP_DIR}

# Cache dependencies unless pom.xml is changed.
ARG SOURCE_DIR=.
COPY ${SOURCE_DIR}/pom.xml ${APP_DIR}/pom.xml
RUN mvn -B -DskipTests dependency:go-offline

# add the source code and build.
COPY ${SOURCE_DIR}/src ${APP_DIR}/src
RUN mvn -B -DskipTests clean package

# ----------- Runtime stage -----------
FROM eclipse-temurin:17-jre-alpine

ENV APP_DIR=/usr/app
ENV LOG_DIR=${APP_DIR}/logs

# App configuration overridden via docker-compose using .env file.
#ENV DB_URL=
#ENV DB_USERNAME=
#ENV DB_PASSWORD=
ENV LOG_LEVEL=
ENV JAVA_OPTS=""

WORKDIR ${APP_DIR}
RUN mkdir -p "${LOG_DIR}"

COPY --from=build /usr/app/target/*.jar ${APP_DIR}/ms-template.jar
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar ${APP_DIR}/ms-template.jar"]
