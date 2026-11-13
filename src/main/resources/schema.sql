-- Схема базы. Выполняется при каждом старте; создаёт таблицы, если их нет.
-- База прежних версий (до 1.18, поля объектов — столбцами и отдельными таблицами) не переносится:
-- при запуске она откладывается в резервный файл (DataReset), данные загружаются заново из Excel.

-- Пользователи. password_hash — см. Passwords (PBKDF2); role — admin (администратор) или user
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user'
);

-- Типы коллекций пользователя («Настройки» → «Типы коллекций»). profile — профиль (Repo.PROFILES: games, music,
-- books, other): какие источники импорта подходят и какие особые возможности есть. Начальные типы создаёт Repo.createUser
CREATE TABLE IF NOT EXISTS kinds (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    profile TEXT NOT NULL DEFAULT 'other',
    UNIQUE (user_id, name)
);

-- Поля объектов типа по порядку (pos). role — встроенная роль из Repo.FIELDS (platform, status, price…): по ней
-- работают особые возможности (суммы цен, цвета строк по статусу, импорт по платформе…); type — вид поля
-- (Repo.TYPES: text, number, money, date, bool, rating, select, tags, longtext, link); label — своя подпись
-- (NULL — подпись роли на языке интерфейса). Снятое с типа поле удаляется вместе со значениями объектов
CREATE TABLE IF NOT EXISTS kind_fields (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind_id INTEGER NOT NULL REFERENCES kinds(id) ON DELETE CASCADE,
    pos INTEGER NOT NULL,
    role TEXT,
    type TEXT NOT NULL,
    label TEXT,
    UNIQUE (kind_id, role)
);

-- Значения поля-списка по порядку (например, статусы прохождения) и цвет строки объекта с этим значением (#rrggbb или NULL)
CREATE TABLE IF NOT EXISTS field_options (
    field_id INTEGER NOT NULL REFERENCES kind_fields(id) ON DELETE CASCADE,
    pos INTEGER NOT NULL,
    value TEXT NOT NULL,
    color TEXT,
    PRIMARY KEY (field_id, pos)
);

-- Коллекции: тип — kinds.id этого же пользователя (тип, который используется, не удаляется)
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

-- Значения полей объектов: поле — kind_fields.id типа коллекции объекта. value без объявленного типа: числа
-- (цена, рейтинг 1…10, наличие 1) хранятся числами и сортируются как числа, остальное — текстом.
-- Пустое значение — строки нет (наличие «Нет» — тоже)
CREATE TABLE IF NOT EXISTS item_values (
    item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    field_id INTEGER NOT NULL REFERENCES kind_fields(id) ON DELETE CASCADE,
    value NOT NULL,
    PRIMARY KEY (item_id, field_id)
);
CREATE INDEX IF NOT EXISTS item_values_field ON item_values (field_id);

-- Описание объекта — есть у всех объектов, независимо от типа
CREATE TABLE IF NOT EXISTS item_descriptions (
    item_id INTEGER PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
    text TEXT NOT NULL
);

-- Картинки — файлы в DATA_DIR/images/ab/cd/<sha256>.<ext>, одинаковая картинка хранится один раз (ImageFiles).
-- pictures — справочник файлов (size — для будущей статистики и ограничений); строка и файл удаляются,
-- когда на картинку не осталось ссылок
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

-- Запомненный вид таблицы объектов коллекции у пользователя: сортировка, страница, фильтры
-- (строка параметров адреса, как в ссылках страницы); подставляется, если коллекция открыта без параметров
CREATE TABLE IF NOT EXISTS collection_views (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    query TEXT NOT NULL,
    PRIMARY KEY (user_id, collection_id)
);

-- Настройки пользователя (вкладка «Отображение»); нет строки — значения по умолчанию.
-- show_prices: 1 — цены видны в таблице объектов и стоимость коллекции рядом с количеством, 0 — только в подсказке у иконки глаза
CREATE TABLE IF NOT EXISTS user_settings (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    show_prices INTEGER NOT NULL DEFAULT 0
);

-- Сколько показывать на странице (вкладка «Отображение»): коллекций на главной и объектов в таблице коллекции;
-- нет строки или NULL — по умолчанию (Repo.PageSizes)
CREATE TABLE IF NOT EXISTS user_page_sizes (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    collections INTEGER,
    items INTEGER
);

-- Тема оформления пользователя (вкладка «Отображение»): пара тёмной и светлой темы (Repo.THEMES); нет строки — первая пара.
-- Тёмная или светлая из пары — переключатель в шапке, запоминается в браузере
CREATE TABLE IF NOT EXISTS user_theme (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    pair TEXT NOT NULL
);

-- «Запомнить меня на этом компьютере» (Remember): SHA-256 ключа входа из cookie браузера, срок (мс). Сам ключ и пароль
-- здесь не хранятся. Смена пароля удаляет ключи пользователя, выход — ключ этого компьютера
CREATE TABLE IF NOT EXISTS remember_tokens (
    hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires INTEGER NOT NULL
);

-- Личные токены сервисов импорта (вкладка «Импорт»), сейчас только service = 'discogs'
CREATE TABLE IF NOT EXISTS user_tokens (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    service TEXT NOT NULL,
    token TEXT NOT NULL,
    PRIMARY KEY (user_id, service)
);

-- Разовые обновления данных: имя выполненного обновления (чтобы не повторять его при каждом старте).
-- Обновления — в конце этого файла: UPDATE … WHERE NOT EXISTS (… name = 'имя') и INSERT OR IGNORE INTO data_updates
CREATE TABLE IF NOT EXISTS data_updates (
    name TEXT PRIMARY KEY
);
