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
    image BLOB,
    image_mime TEXT,
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

-- Картинка объекта (отдельная таблица: у старых баз создаётся сама, без изменения items)
CREATE TABLE IF NOT EXISTS item_images (
    item_id INTEGER PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
    image BLOB NOT NULL,
    image_mime TEXT NOT NULL
);

-- Дополнительные картинки объекта (вторая и следующие, pos = 1..4; первая — в item_images); сами файлы — в DATA_DIR/images
CREATE TABLE IF NOT EXISTS item_more_images (
    item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    pos INTEGER NOT NULL,
    image_mime TEXT NOT NULL,
    PRIMARY KEY (item_id, pos)
);

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

-- Личные токены сервисов импорта (вкладка «Импорт»), сейчас только service = 'discogs'
CREATE TABLE IF NOT EXISTS user_tokens (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    service TEXT NOT NULL,
    token TEXT NOT NULL,
    PRIMARY KEY (user_id, service)
);

-- Наличие объекта (поле типа «Наличие»): здесь — объекты, которых нет в наличии; нет строки — объект есть
CREATE TABLE IF NOT EXISTS item_absent (
    item_id INTEGER PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE
);

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
