# Коллекции (Java)

Простое веб-приложение для хранения коллекций и объектов (название, тип, картинка).
Java 21 + Spring Boot + Thymeleaf + SQLite. Всё, включая картинки, хранится в одном файле `data/app.db`.

## Требования
Только Docker с Docker Compose. Java и Maven на компьютере не нужны: сборка идёт внутри Docker.
Первый запуск занимает несколько минут (скачиваются зависимости), следующие — быстрее.

## Запуск
- Linux / macOS: `./start.sh`
- Windows: `start.bat`

Откройте http://localhost:47321

## Остановка
- Linux / macOS: `./stop.sh`
- Windows: `stop.bat`

Данные сохраняются в папке `data` между перезапусками.
Резервная копия — просто скопируйте `data/app.db`.

## Структура
```
src/main/java/app/
  App.java            точка входа
  WebController.java  все страницы и действия
  Repo.java           работа с базой (SQL)
  Entry.java          коллекция или объект
src/main/resources/
  application.properties  настройки (порт, база, лимит картинки)
  schema.sql              таблицы
  templates/              HTML-страницы (Thymeleaf)
```

## Разработка без Docker
```
DATA_DIR=./data mvn spring-boot:run
```
Приложение будет на http://localhost:8000

Внешний порт меняется в `docker-compose.yml` (`"47321:8000"`).
