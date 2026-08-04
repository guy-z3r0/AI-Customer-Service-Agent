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
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
