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

-- Цвет строки объекта в зависимости от статуса (настраивается в типе коллекции)
CREATE TABLE IF NOT EXISTS kind_status_colors (
    kind_id INTEGER NOT NULL REFERENCES kinds(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    color TEXT NOT NULL, -- #rrggbb
    PRIMARY KEY (kind_id, status)
);
