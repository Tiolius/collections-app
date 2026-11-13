# Build: Maven inside Docker, no Java needed on the computer
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# Downloaded dependencies are cached between builds (/root/.m2),
# so only the first run is slow
RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests

# Run
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
ENV DATA_DIR=/data
VOLUME /data
EXPOSE 8000
CMD ["java", "-jar", "app.jar"]
