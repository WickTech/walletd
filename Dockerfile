# Build stage: compile and package with the Maven wrapper.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package

# Runtime stage: JRE only, non-root.
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home walletd
WORKDIR /app
COPY --from=build /build/target/walletd-*.jar app.jar
USER walletd
EXPOSE 8080
# Honour the container's memory limit; die on OOM so the orchestrator restarts a clean process.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
