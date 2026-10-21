-- Схема базы. Выполняется при каждом старте; создаёт таблицы, если их нет.

CREATE TABLE IF NOT EXISTS collections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    image BLOB,
    image_mime TEXT,
    kind TEXT -- тип коллекции, значения в Repo.KINDS
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
