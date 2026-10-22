-- Разовый перенос базы старого формата (без пользователей) на пользователя Evered с пустым паролем.
-- Запускать один раз на остановленном приложении, предварительно сделав копию data/app.db.
-- Все коллекции, объекты, типы коллекций и цвета статусов становятся данными пользователя Evered.

-- Остановиться при первой ошибке (например, при повторном запуске) — ничего не изменится
.bail on

PRAGMA foreign_keys = OFF;
BEGIN;

-- Если база ещё до появления настроек типов — создать их, как это делала прежняя версия
CREATE TABLE IF NOT EXISTS kinds (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    fields TEXT NOT NULL DEFAULT ''
);
INSERT INTO kinds (name, fields)
SELECT * FROM (VALUES
    ('Игры (диски)', 'status,price'),
    ('Игры (цифровые)', 'platform,format,status,price'),
    ('Книги', 'price'),
    ('Музыка (диски)', 'price'))
WHERE NOT EXISTS (SELECT 1 FROM kinds);
CREATE TABLE IF NOT EXISTS kind_status_colors (
    kind_id INTEGER NOT NULL REFERENCES kinds(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    color TEXT NOT NULL,
    PRIMARY KEY (kind_id, status)
);

-- Пользователи; Evered — пустой пароль (хеш получен через app.Passwords)
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL
);
INSERT INTO users (id, name, password_hash) VALUES (1, 'Evered', 'pbkdf2$120000$H+WG+p8epP3Cgw6L877MpA==$JmoBcu23klIkTvmIKBDMPjEHDkv2u+kBrz8z2aZM6Yc=');

-- Коллекции: добавить владельца
CREATE TABLE collections_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    image BLOB,
    image_mime TEXT,
    kind TEXT
);
INSERT INTO collections_new (id, user_id, name, image, image_mime, kind)
SELECT id, 1, name, image, image_mime, kind FROM collections;
DROP TABLE collections;
ALTER TABLE collections_new RENAME TO collections;

-- Типы коллекций: добавить владельца, имя уникально в пределах пользователя
CREATE TABLE kinds_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    fields TEXT NOT NULL DEFAULT '',
    UNIQUE (user_id, name)
);
INSERT INTO kinds_new (id, user_id, name, fields) SELECT id, 1, name, fields FROM kinds;
DROP TABLE kinds;
ALTER TABLE kinds_new RENAME TO kinds;

COMMIT;
PRAGMA foreign_keys = ON;
