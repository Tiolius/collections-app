-- Database schema. Runs on every start; creates tables if they do not exist.
-- A database of previous versions (before 1.18, item fields as columns and separate tables) is not carried over:
-- at start it is moved aside into a backup file (DataReset), data is loaded again from Excel.

-- Users. password_hash — see Passwords (PBKDF2); role — admin (administrator) or user
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user'
);

-- Collection types of the user ("Settings" → "Collection types"). profile — profile (Repo.PROFILES: games, music,
-- books, other): which import sources fit and which special features there are. Initial types are created by Repo.createUser
CREATE TABLE IF NOT EXISTS kinds (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    profile TEXT NOT NULL DEFAULT 'other',
    UNIQUE (user_id, name)
);

-- Item fields of the type in order (pos). role — built-in role from Repo.FIELDS (platform, status, price…): special
-- features work by it (price sums, row colours by status, import by platform…); type — field kind
-- (Repo.TYPES: text, number, money, date, bool, rating, select, tags, longtext, link); label — own label
-- (NULL — the role label in the interface language). A field removed from the type is deleted together with item values
CREATE TABLE IF NOT EXISTS kind_fields (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind_id INTEGER NOT NULL REFERENCES kinds(id) ON DELETE CASCADE,
    pos INTEGER NOT NULL,
    role TEXT,
    type TEXT NOT NULL,
    label TEXT,
    UNIQUE (kind_id, role)
);

-- List field values in order (for example, play statuses) and the colour of an item's row with this value (#rrggbb or NULL)
CREATE TABLE IF NOT EXISTS field_options (
    field_id INTEGER NOT NULL REFERENCES kind_fields(id) ON DELETE CASCADE,
    pos INTEGER NOT NULL,
    value TEXT NOT NULL,
    color TEXT,
    PRIMARY KEY (field_id, pos)
);

-- Collections: type — kinds.id of the same user (a type in use is not deleted)
CREATE TABLE IF NOT EXISTS collections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    kind_id INTEGER REFERENCES kinds(id)
);

CREATE TABLE IF NOT EXISTS items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    name TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS items_collection ON items (collection_id);

-- Item field values: field — kind_fields.id of the item's collection type. value without a declared type: numbers
-- (price, rating 1…10, available 1) are stored as numbers and sorted as numbers, the rest — as text.
-- An empty value — no row (available "No" too)
CREATE TABLE IF NOT EXISTS item_values (
    item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    field_id INTEGER NOT NULL REFERENCES kind_fields(id) ON DELETE CASCADE,
    value NOT NULL,
    PRIMARY KEY (item_id, field_id)
);
CREATE INDEX IF NOT EXISTS item_values_field ON item_values (field_id);

-- Item description — every item has it, regardless of type
CREATE TABLE IF NOT EXISTS item_descriptions (
    item_id INTEGER PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
    text TEXT NOT NULL
);

-- Pictures — files in DATA_DIR/images/ab/cd/<sha256>.<ext>, an identical picture is stored once (ImageFiles).
-- pictures — the file register (size — for future statistics and limits); the row and file are deleted
-- when no references to the picture are left
CREATE TABLE IF NOT EXISTS pictures (
    hash TEXT PRIMARY KEY, -- SHA-256 of the content, 64 hex
    mime TEXT NOT NULL,
    size INTEGER NOT NULL
);

-- Item pictures: pos = 0..4 in order without gaps (0 — the main one)
CREATE TABLE IF NOT EXISTS item_pictures (
    item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    pos INTEGER NOT NULL,
    hash TEXT NOT NULL REFERENCES pictures(hash),
    PRIMARY KEY (item_id, pos)
);
CREATE INDEX IF NOT EXISTS item_pictures_hash ON item_pictures (hash);

-- Collection cover
CREATE TABLE IF NOT EXISTS collection_pictures (
    collection_id INTEGER PRIMARY KEY REFERENCES collections(id) ON DELETE CASCADE,
    hash TEXT NOT NULL REFERENCES pictures(hash)
);
CREATE INDEX IF NOT EXISTS collection_pictures_hash ON collection_pictures (hash);

-- Remembered view of a collection's item table for the user: sorting, page, filters
-- (an address query string, as in the page links); used when the collection is opened without parameters
CREATE TABLE IF NOT EXISTS collection_views (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    query TEXT NOT NULL,
    PRIMARY KEY (user_id, collection_id)
);

-- User settings ("Display" tab); no row — defaults.
-- show_prices: 1 — prices are visible in the item table and the collection value next to the count, 0 — only in the eye icon tooltip
CREATE TABLE IF NOT EXISTS user_settings (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    show_prices INTEGER NOT NULL DEFAULT 0
);

-- How many to show per page ("Display" tab): collections on the home page and items in the collection table;
-- no row or NULL — default (Repo.PageSizes)
CREATE TABLE IF NOT EXISTS user_page_sizes (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    collections INTEGER,
    items INTEGER
);

-- Theme of the user ("Display" tab): a pair of dark and light themes (Repo.THEMES); no row — the first pair.
-- Dark or light of the pair — the switch in the header, remembered in the browser
CREATE TABLE IF NOT EXISTS user_theme (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    pair TEXT NOT NULL
);

-- "Remember me" (Remember): SHA-256 of the sign-in key from the browser cookie, expiry (ms). The key itself and the password
-- are not stored here. A password change deletes the user's keys, signing out — the key of this computer
CREATE TABLE IF NOT EXISTS remember_tokens (
    hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires INTEGER NOT NULL
);

-- Personal tokens of import services ("Import" tab), currently only service = 'discogs'
CREATE TABLE IF NOT EXISTS user_tokens (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    service TEXT NOT NULL,
    token TEXT NOT NULL,
    PRIMARY KEY (user_id, service)
);

-- One-off data updates: name of a completed update (so it is not repeated on every start).
-- Updates are at the end of this file: UPDATE … WHERE NOT EXISTS (… name = 'name') and INSERT OR IGNORE INTO data_updates
CREATE TABLE IF NOT EXISTS data_updates (
    name TEXT PRIMARY KEY
);
