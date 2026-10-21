-- Схема базы. Выполняется при каждом старте; создаёт таблицы, если их нет.

CREATE TABLE IF NOT EXISTS collections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    image BLOB,
    image_mime TEXT,
    kind TEXT -- тип коллекции: kinds.name
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

-- Типы коллекций (настраиваются на странице «Настройки»).
-- fields — поля объектов через запятую, ключи из Repo.FIELDS
CREATE TABLE IF NOT EXISTS kinds (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    fields TEXT NOT NULL DEFAULT ''
);

-- Начальные типы — только если типов ещё нет
INSERT INTO kinds (name, fields)
SELECT * FROM (VALUES
    ('Игры (диски)', 'status,price'),
    ('Игры (цифровые)', 'platform,format,status,price'),
    ('Книги', 'price'),
    ('Музыка (диски)', 'price'))
WHERE NOT EXISTS (SELECT 1 FROM kinds);
