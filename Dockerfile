# Сборка: Maven внутри Docker, на компьютере Java не нужна
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# Скачанные зависимости кешируются между сборками (папка /root/.m2),
# поэтому долгим будет только первый запуск
RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests

# Запуск
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
ENV DATA_DIR=/data
VOLUME /data
EXPOSE 8000
CMD ["java", "-jar", "app.jar"]
