# Stage 1: Build mutagen.jar
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src src
RUN mvn package -q -DskipTests

# Stage 2: Slim runtime image
FROM eclipse-temurin:21-jdk-alpine

# Maven is needed because PitestRunner invokes mvn as a subprocess.
# Git is needed for branch operations by GitClient.
RUN apk add --no-cache maven git

COPY --from=builder /build/target/mutagen.jar /opt/mutagen/mutagen.jar

ENTRYPOINT ["java", "-jar", "/opt/mutagen/mutagen.jar"]
