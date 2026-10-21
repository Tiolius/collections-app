CREATE TABLE IF NOT EXISTS collections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    type TEXT,
    image BLOB,
    image_mime TEXT,
    kind TEXT
);

-- Новые столбцы добавлять и сюда, и в Repo.migrate() (для уже существующих баз)
CREATE TABLE IF NOT EXISTS items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    type TEXT,
    image BLOB,
    image_mime TEXT,
    platform TEXT,
    format TEXT,
    status TEXT,
    price INTEGER
);
