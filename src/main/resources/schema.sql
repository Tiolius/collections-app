-- Схема базы. Выполняется при каждом старте; создаёт таблицы, если их нет.

-- Пользователи. password_hash — см. Passwords (PBKDF2); role — admin (администратор) или user
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user'
);

CREATE TABLE IF NOT EXISTS collections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    kind TEXT -- тип коллекции: kinds.name этого же пользователя
);

CREATE TABLE IF NOT EXISTS items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    platform TEXT,
    format TEXT,
    status TEXT,
    price INTEGER
);

-- Картинки — файлы в DATA_DIR/images/ab/cd/<sha256>.<ext>, одинаковая картинка хранится один раз (ImageFiles).
-- pictures — справочник файлов (size — для будущей статистики и ограничений); строка и файл удаляются,
-- когда на картинку не осталось ссылок. Прежние хранилища (item_images, item_more_images, collections.image/image_mime)
-- переносятся сюда при запуске и удаляются
CREATE TABLE IF NOT EXISTS pictures (
    hash TEXT PRIMARY KEY, -- SHA-256 содержимого, 64 hex
    mime TEXT NOT NULL,
    size INTEGER NOT NULL
);

-- Картинки объекта: pos = 0..4 по порядку без пропусков (0 — главная)
CREATE TABLE IF NOT EXISTS item_pictures (
    item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    pos INTEGER NOT NULL,
    hash TEXT NOT NULL REFERENCES pictures(hash),
    PRIMARY KEY (item_id, pos)
);
CREATE INDEX IF NOT EXISTS item_pictures_hash ON item_pictures (hash);

-- Обложка коллекции
CREATE TABLE IF NOT EXISTS collection_pictures (
    collection_id INTEGER PRIMARY KEY REFERENCES collections(id) ON DELETE CASCADE,
    hash TEXT NOT NULL REFERENCES pictures(hash)
);
CREATE INDEX IF NOT EXISTS collection_pictures_hash ON collection_pictures (hash);

-- Запомненный вид таблицы объектов коллекции у пользователя: сортировка, страница, размер страницы, фильтры
-- (строка параметров адреса, как в ссылках страницы); подставляется, если коллекция открыта без параметров
CREATE TABLE IF NOT EXISTS collection_views (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    query TEXT NOT NULL,
    PRIMARY KEY (user_id, collection_id)
);

-- Описание объекта (отдельная таблица: у старых баз создаётся сама, без изменения items)
CREATE TABLE IF NOT EXISTS item_descriptions (
    item_id INTEGER PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
    text TEXT NOT NULL
);

-- Типы коллекций пользователя (настраиваются на странице «Настройки»).
-- fields — поля объектов через запятую, ключи из Repo.FIELDS. Начальные типы создаёт Repo.createUser
CREATE TABLE IF NOT EXISTS kinds (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    fields TEXT NOT NULL DEFAULT '',
    UNIQUE (user_id, name)
);

-- Настройки пользователя (вкладка «Отображение»); нет строки — значения по умолчанию.
-- show_prices: 1 — цены видны в таблице объектов и стоимость коллекции рядом с количеством, 0 — только в подсказке у иконки глаза
CREATE TABLE IF NOT EXISTS user_settings (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    show_prices INTEGER NOT NULL DEFAULT 0
);

-- Личные токены и ключи сервисов импорта: service = 'discogs' (вкладка «Импорт»), 'igdb-id' и 'igdb-secret' (вкладка «Ключи»)
CREATE TABLE IF NOT EXISTS user_tokens (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    service TEXT NOT NULL,
    token TEXT NOT NULL,
    PRIMARY KEY (user_id, service)
);

-- Наличие объекта (поле типа «Наличие»): здесь — объекты, которые есть в наличии; нет строки — «Нет» (по умолчанию).
-- Прежняя таблица item_absent (отметки «нет», по умолчанию было «есть») удалена: по решению пользователя все объекты стали «Нет»
CREATE TABLE IF NOT EXISTS item_present (
    item_id INTEGER PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE
);
DROP TABLE IF EXISTS item_absent;

-- Разовые обновления данных: имя выполненного обновления (чтобы не повторять его при каждом старте)
CREATE TABLE IF NOT EXISTS data_updates (
    name TEXT PRIMARY KEY
);

-- Поле «Наличие» включается у всех уже созданных типов коллекций один раз (потом его можно снять в настройках типа)
UPDATE kinds SET fields = CASE WHEN fields = '' THEN 'available' ELSE fields || ',available' END
WHERE NOT EXISTS (SELECT 1 FROM data_updates WHERE name = 'available-field')
  AND (',' || fields || ',') NOT LIKE '%,available,%';
INSERT OR IGNORE INTO data_updates (name) VALUES ('available-field');

-- Статусы прохождения — выпадающий список (Repo.STATUSES): старые значения переименовываются один раз
-- (Complete → Completed, Incomplete → Unfinished), вместе с цветами строк по статусу
UPDATE items SET status = 'Completed' WHERE lower(trim(status)) = 'complete'
  AND NOT EXISTS (SELECT 1 FROM data_updates WHERE name = 'statuses-list');
UPDATE items SET status = 'Unfinished' WHERE lower(trim(status)) = 'incomplete'
  AND NOT EXISTS (SELECT 1 FROM data_updates WHERE name = 'statuses-list');

-- Цвет строки объекта в зависимости от статуса (настраивается в типе коллекции)
CREATE TABLE IF NOT EXISTS kind_status_colors (
    kind_id INTEGER NOT NULL REFERENCES kinds(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    color TEXT NOT NULL, -- #rrggbb
    PRIMARY KEY (kind_id, status)
);

-- (продолжение разового переименования статусов: цвета строк; затем обновление отмечается выполненным)
UPDATE OR IGNORE kind_status_colors SET status = 'Completed' WHERE lower(trim(status)) = 'complete'
  AND NOT EXISTS (SELECT 1 FROM data_updates WHERE name = 'statuses-list');
UPDATE OR IGNORE kind_status_colors SET status = 'Unfinished' WHERE lower(trim(status)) = 'incomplete'
  AND NOT EXISTS (SELECT 1 FROM data_updates WHERE name = 'statuses-list');
INSERT OR IGNORE INTO data_updates (name) VALUES ('statuses-list');
