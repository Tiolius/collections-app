# Collections App

[English](#english) · [Русский](#русский)

## English

A web application for personal collections (games, books, music, etc.): collections, items, search, filters and value.
Multi-user, with password login; the interface is in Russian and English (switch in the header). Java 25 + Spring Boot + SQLite.
Runs as a Windows program (no Docker, no Java installation needed) or in Docker.

**Application address: http://localhost:47321** (port 47321). If this port is used by another program, the Windows program starts on a free one — see below.

### Windows program (without Docker)
1. Download `CollectionsApp-X.Y.Z-windows.zip` from the **Releases** section of the GitHub repository
   (the archive is built automatically for every new version).
2. Unpack it to any folder — you get the `CollectionsApp` folder.
3. Run `CollectionsApp.exe`: the application opens in a new window of the default browser (http://localhost:47321),
   and its icon appears next to the clock on the taskbar. There is no console window.
   If port 47321 is used by another program, the application starts on any free port — the address will differ
   (browser settings, such as the dark theme, are remembered anew for it); the icon and running the exe again open the right address.
   Icon: double-click — open the application; right-click — "Open", "Data folder", "Exit".
   Running the exe again just opens the browser. To stop the application, choose "Exit" in the icon menu.
   If the application fails to start, a window shows the reason; details are in the log `logs/app.log` next to `CollectionsApp.exe`.

Data (database and pictures) is stored in the `data` folder next to `CollectionsApp.exe`.
The application is available only from this computer.

- **Update** — unpack the new archive over the old folder (first choose "Exit" in the icon menu): the `data` folder stays.
- **Moving data from Docker** — stop the Docker version (`stop.bat`) and copy the `data` folder from the project folder to the `CollectionsApp` folder (next to `CollectionsApp.exe`).
- **Backup** — "Settings" → "Backup" (administrator only): "Download backup" saves a zip with all data (database and pictures), "Restore" loads such a zip back — for example, into a new installation.
- **Updates** — every 12 hours the application checks the latest release on GitHub; if there is a new version, the administrator sees its number in the header (a link to the release page). To turn the check off, set the environment variable `UPDATES_ENABLED=false`.
- On the first run Windows may show "Windows protected your PC" (the program is not signed) — "More info" → "Run anyway".

### Running in Docker

#### Requirements
Only Docker with Docker Compose. Java and Maven are not needed on the computer: the build runs inside Docker.
The first start takes a few minutes (dependencies are downloaded), later ones are faster.

#### Start
- Linux / macOS: `./start.sh`
- Windows: `start.bat`

The application runs on port **47321** — open http://localhost:47321

If the application is already running (for example, after an update), the start script first builds the new version, then stops the old one and starts the new one. If the build fails, the running application is not stopped.

#### Stop
- Linux / macOS: `./stop.sh`
- Windows: `stop.bat`

Data is kept in the `data` folder between restarts.
`data/app.db` is the database, `data/images` holds item and collection pictures (an identical picture is stored once, the file name is its hash; which item refers to it is recorded in the database, so copy the `images` folder only together with `app.db`). For a backup, copy the whole `data` folder (preferably with the application stopped).

### Structure
```
src/main/java/app/
  App.java            entry point
  WebController.java  all pages and actions
  Repo.java           database access (SQL)
  Entry.java          a collection or an item
src/main/resources/
  application.properties  settings (port, database, picture size limit)
  schema.sql              tables
  templates/              HTML pages (Thymeleaf)
```

### Development without Docker
```
DATA_DIR=./data mvn spring-boot:run
```
In this mode the application is at http://localhost:8000 (the application's internal port).

### Port
From outside the application is available on port **47321**; inside the container it listens on port 8000.
To change the external port, change the first number in `docker-compose.yml` (`"47321:8000"`)
and the address in `start.sh` / `start.bat`.

### Publishing on the internet
Now the application is available only from this computer (`127.0.0.1:47321` in `docker-compose.yml`).
For access from the internet:
- put an HTTPS proxy in front of the application (for example, Caddy or nginx with a Let's Encrypt certificate) — without HTTPS passwords are sent in the clear;
- in `docker-compose.yml` add `environment:` to the service with `COOKIE_SECURE=true` (login cookie over HTTPS only)
  and `FORWARD_HEADERS=native` (the real client address from the proxy — for protection against password guessing);
- open the application only through the proxy, do not expose port 47321.

---

## Русский

Веб-приложение для учёта личных коллекций (игры, книги, музыка и т. п.): коллекции, объекты в них, поиск, фильтры и стоимость.
Многопользовательское, со входом по паролю; интерфейс на русском и английском (переключатель в шапке). Java 25 + Spring Boot + SQLite.
Запускается как программа для Windows (без Docker и без установки Java) или в Docker.

**Адрес приложения: http://localhost:47321** (порт 47321). Программа для Windows, если этот порт занят другой программой, запускается на свободном — см. ниже.

### Программа для Windows (без Docker)
1. Скачайте архив `CollectionsApp-X.Y.Z-windows.zip` из раздела **Releases** репозитория на GitHub
   (архив собирается автоматически при выходе каждой новой версии).
2. Распакуйте его в удобную папку — получится папка `CollectionsApp`.
3. Запустите `CollectionsApp.exe`: приложение откроется в новом окне браузера по умолчанию (http://localhost:47321),
   а у часов на панели задач появится его значок. Окна консоли нет.
   Если порт 47321 занят другой программой, приложение запустится на любом свободном порту — адрес будет другим
   (настройки браузера, например тёмная тема, для него запоминаются заново); значок и повторный запуск exe открывают верный адрес.
   Значок: двойной щелчок — открыть приложение; правая кнопка — «Открыть», «Папка данных», «Выход».
   Повторный запуск exe просто открывает браузер. Чтобы остановить приложение — «Выход» в меню значка.
   Если приложение не запустилось, появится окно с причиной; подробности — в журнале `logs/app.log` рядом с `CollectionsApp.exe`.

Данные (база и картинки) хранятся в папке `data` рядом с `CollectionsApp.exe`.
Приложение доступно только с этого компьютера.

- **Обновление** — распакуйте новый архив поверх старой папки (перед этим — «Выход» в меню значка у часов): папка `data` останется.
- **Перенос данных из Docker** — остановите Docker-версию (`stop.bat`) и скопируйте папку `data` из папки проекта в папку `CollectionsApp` (рядом с `CollectionsApp.exe`).
- **Резервная копия** — «Настройки» → «Резервная копия» (только администратор): «Скачать копию» сохраняет zip со всеми данными (база и картинки), «Восстановить» загружает такой zip обратно — например, в новой установке.
- **Обновления** — приложение раз в 12 часов проверяет последний релиз на GitHub; если есть новая версия, администратор видит её номер в шапке (ссылка на страницу релиза). Отключить проверку — переменная окружения `UPDATES_ENABLED=false`.
- Windows при первом запуске может показать «Windows защитила ваш компьютер» (программа не подписана) — «Подробнее» → «Выполнить в любом случае».

### Запуск в Docker

#### Требования
Только Docker с Docker Compose. Java и Maven на компьютере не нужны: сборка идёт внутри Docker.
Первый запуск занимает несколько минут (скачиваются зависимости), следующие — быстрее.

#### Запуск
- Linux / macOS: `./start.sh`
- Windows: `start.bat`

Приложение запускается на порту **47321** — откройте http://localhost:47321

Если приложение уже запущено (например, после обновления), скрипт запуска сначала соберёт новую версию, затем остановит старую и запустит новую. Если сборка не удалась, работающее приложение не останавливается.

#### Остановка
- Linux / macOS: `./stop.sh`
- Windows: `stop.bat`

Данные сохраняются в папке `data` между перезапусками.
В `data/app.db` — база, в `data/images` — картинки объектов и коллекций (одинаковая картинка хранится один раз, имя файла — её хеш; какой объект на неё ссылается, записано в базе, поэтому папку `images` копируйте только вместе с `app.db`). Резервная копия — скопируйте всю папку `data` (лучше при остановленном приложении).

### Структура
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

### Разработка без Docker
```
DATA_DIR=./data mvn spring-boot:run
```
В этом режиме приложение будет на http://localhost:8000 (внутренний порт приложения).

### Порт
Снаружи приложение доступно на порту **47321**; внутри контейнера оно слушает порт 8000.
Чтобы сменить внешний порт, поменяйте первое число в `docker-compose.yml` (`"47321:8000"`)
и адрес в `start.sh` / `start.bat`.

### Публикация в интернете
Сейчас приложение доступно только с этого компьютера (`127.0.0.1:47321` в `docker-compose.yml`).
Для доступа из интернета:
- поставьте перед приложением прокси с HTTPS (например, Caddy или nginx с сертификатом Let's Encrypt) — без HTTPS пароли передаются открыто;
- в `docker-compose.yml` добавьте сервису `environment:` с `COOKIE_SECURE=true` (cookie входа только по HTTPS)
  и `FORWARD_HEADERS=native` (настоящий адрес клиента от прокси — для защиты от подбора пароля);
- приложение открывайте только через прокси, порт 47321 наружу не открывайте.
