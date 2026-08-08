# Build the jar inside the image so a clean clone needs nothing but Docker.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Copy the pom first so Maven's dependency download is cached separately from
# the source, which changes far more often.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/java-backend-2.0.0.jar app.jar

# The jar is all this stage needs, and it needs no write access to it either.
# logs/ is the one thing it does write: logback-spring.xml rolls a file there,
# so the folder has to exist and belong to the user before it drops privileges.
RUN groupadd --system --gid 10001 agent \
    && useradd --system --uid 10001 --gid 10001 --no-create-home agent \
    && chown 10001:10001 /app/app.jar \
    && mkdir -p /app/logs \
    && chown 10001:10001 /app/logs
USER 10001

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
