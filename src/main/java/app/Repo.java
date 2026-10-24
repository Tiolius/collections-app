package app;

import java.io.IOException;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Вся работа с базой. table — "collections" или "items".
 * Почти все методы принимают uid — id текущего пользователя: чужие коллекции, объекты и типы не видны (404).
 */
@Repository
public class Repo {

    // в запросах таблица collections — с псевдонимом c
    private static final String COLLECTION_COLS =
            "id, name, EXISTS (SELECT 1 FROM collection_pictures cp WHERE cp.collection_id = c.id) AS has_image, kind, "
            + "(SELECT cp.hash FROM collection_pictures cp WHERE cp.collection_id = c.id) AS pics";
    private static final String ITEM_COLS = "id, name, collection_id, platform, format, status, price, "
            + "EXISTS (SELECT 1 FROM item_pictures ip WHERE ip.item_id = items.id) AS has_image, "
            + "(SELECT COUNT(*) FROM item_pictures ip WHERE ip.item_id = items.id) AS image_count, "
            + "(SELECT d.text FROM item_descriptions d WHERE d.item_id = items.id) AS description, "
            + "EXISTS (SELECT 1 FROM item_present a WHERE a.item_id = items.id) AS available, "
            + "(SELECT r.rating FROM item_ratings r WHERE r.item_id = items.id) AS rating, "
            + "(SELECT o.url FROM item_oldgames o WHERE o.item_id = items.id) AS oldgames, "
            + "(SELECT d.purchased FROM item_details d WHERE d.item_id = items.id) AS purchased, "
            + "(SELECT d.tags FROM item_details d WHERE d.item_id = items.id) AS tags, "
            + "(SELECT d.edition FROM item_details d WHERE d.item_id = items.id) AS edition, "
            + "(SELECT d.notes FROM item_details d WHERE d.item_id = items.id) AS notes, "
            // хеши картинок по порядку — адреса /img/<хеш> (кеш браузера по содержимому)
            + "(SELECT group_concat(ip.hash, ',' ORDER BY ip.pos) FROM item_pictures ip WHERE ip.item_id = items.id) AS pics";

    /** Сравнение по алфавиту для русского языка: без учёта регистра, «Ё» рядом с «Е», латиница перед кириллицей. */
    public static Comparator<String> alphabetical() {
        Collator c = Collator.getInstance(Locale.forLanguageTag("ru"));
        c.setStrength(Collator.SECONDARY);
        return c::compare;
    }

    /**
     * Готовый набор полей объекта (кроме названия, оно есть всегда): ключ → подпись.
     * Ключ = столбец в items. Тип коллекции выбирает поля из этого набора.
     */
    public static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static {
        // подпись — ключ текста (messages*.properties)
        FIELDS.put("platform", "field.platform");
        FIELDS.put("format", "field.format");
        FIELDS.put("edition", "field.edition"); // не столбец items: регион / издание (таблица item_details)
        FIELDS.put("status", "field.status");
        FIELDS.put("rating", "field.rating"); // не столбец items: таблица item_ratings, 1…10 половинок звезды (5 звёзд)
        FIELDS.put("price", "field.price");
        FIELDS.put("purchased", "field.purchased"); // не столбец items: дата покупки ГГГГ-ММ-ДД (item_details)
        FIELDS.put("available", "field.available"); // не столбец items: отметки «есть» — в таблице item_present, по умолчанию «нет»
        FIELDS.put("tags", "field.tags"); // не столбец items: теги через «, » (item_details)
        FIELDS.put("notes", "field.notes"); // не столбец items: заметки (item_details), в таблице объектов не показываются
        FIELDS.put("oldgames", "field.oldgames"); // не столбец items: ссылка на страницу игры (таблица item_oldgames)
    }

    /** Значения поля «Статус прохождения» (выпадающий список); пусто — статус не указан. */
    public static final List<String> STATUSES = List.of("Completed", "Unfinished");

    private final JdbcTemplate jdbc;
    /** Картинки объектов и коллекций — хранилище по содержимому (ImageFiles). */
    private final ImageFiles files;

    public Repo(JdbcTemplate jdbc, ImageFiles files) {
        this.jdbc = jdbc;
        this.files = files;
    }

    private static RowMapper<Entry> mapper(boolean isItem, boolean withCount) {
        return (rs, i) -> new Entry(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getBoolean("has_image"),
                isItem ? rs.getLong("collection_id") : null,
                withCount ? rs.getInt("cnt") : 0,
                isItem ? null : rs.getString("kind"),
                isItem ? rs.getString("platform") : null,
                isItem ? rs.getString("format") : null,
                isItem ? rs.getString("status") : null,
                isItem ? price(rs) : null,
                isItem ? rs.getString("description") : null,
                isItem ? rs.getBoolean("available") : null,
                isItem ? rs.getInt("image_count") : 0,
                isItem ? rating(rs) : null,
                isItem ? rs.getString("oldgames") : null,
                isItem ? rs.getString("purchased") : null,
                isItem ? rs.getString("tags") : null,
                isItem ? rs.getString("edition") : null,
                isItem ? rs.getString("notes") : null,
                rs.getString("pics"));
    }

    /** Рейтинг (1…10 половинок звезды) или null. */
    private static Integer rating(java.sql.ResultSet rs) throws java.sql.SQLException {
        int v = rs.getInt("rating");
        return rs.wasNull() ? null : v;
    }

    /** Цена или null (getObject(.., Integer.class) драйвер SQLite не умеет для NULL). */
    private static Integer price(java.sql.ResultSet rs) throws java.sql.SQLException {
        int v = rs.getInt("price");
        return rs.wasNull() ? null : v;
    }

    // ---------- типы коллекций ----------

    private static Set<String> parseFields(String s) {
        Set<String> f = Arrays.stream(s == null ? new String[0] : s.split(","))
                .map(String::trim).collect(Collectors.toSet());
        // в порядке FIELDS
        return FIELDS.keySet().stream().filter(f::contains).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static final RowMapper<Kind> KIND_MAPPER = (rs, i) -> new Kind(
            rs.getLong("id"), rs.getString("name"), parseFields(rs.getString("fields")), rs.getInt("used"), Map.of());

    /** Добавляет к типам цвета статусов (статусы по алфавиту). */
    private List<Kind> withColors(List<Kind> kinds) {
        Map<Long, Map<String, String>> byKind = new java.util.HashMap<>();
        jdbc.query("SELECT kind_id, status, color FROM kind_status_colors", rs -> {
            byKind.computeIfAbsent(rs.getLong("kind_id"), k -> new java.util.TreeMap<>(alphabetical()))
                    .put(rs.getString("status"), rs.getString("color"));
        });
        return kinds.stream().map(k -> new Kind(k.id(), k.name(), k.fields(), k.used(),
                byKind.getOrDefault(k.id(), Map.of()))).toList();
    }

    private static final String KIND_SELECT = "SELECT id, name, fields, (SELECT COUNT(*) FROM collections c "
            + "WHERE c.kind = k.name AND c.user_id = k.user_id) AS used FROM kinds k WHERE k.user_id = ?";

    /** Все типы пользователя по алфавиту. */
    public List<Kind> kinds(long uid) {
        return withColors(jdbc.query(KIND_SELECT, KIND_MAPPER, uid)).stream()
                .sorted(Comparator.comparing(Kind::name, alphabetical())).toList();
    }

    public Kind kind(long uid, long id) {
        List<Kind> list = withColors(jdbc.query(KIND_SELECT + " AND id = ?", KIND_MAPPER, uid, id));
        if (list.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return list.get(0);
    }

    /** Цвета строк по статусу для типа: статус в нижнем регистре → цвет. */
    public Map<String, String> statusColors(long uid, String kindName) {
        Map<String, String> res = new java.util.HashMap<>();
        jdbc.query("SELECT s.status, s.color FROM kind_status_colors s JOIN kinds k ON k.id = s.kind_id "
                + "WHERE k.user_id = ? AND k.name = ?",
                rs -> { res.put(rs.getString("status").trim().toLowerCase(Locale.ROOT), rs.getString("color")); }, uid, kindName);
        return res;
    }

    /** Поля объектов коллекции этого типа. Если тип не найден — все поля, чтобы данные не прятались. */
    public Set<String> itemFields(long uid, String kindName) {
        List<Kind> list = jdbc.query(KIND_SELECT + " AND name = ?", KIND_MAPPER, uid, kindName);
        return list.isEmpty() ? FIELDS.keySet() : list.get(0).fields();
    }

    /**
     * Создаёт (id == null) или изменяет тип. При переименовании меняет тип и у его коллекций.
     * Возвращает id типа.
     */
    public long saveKind(long uid, Long id, String name, List<String> fields, List<String> statuses, List<String> colors) {
        requireName(name);
        String n = name.trim();
        for (Kind k : kinds(uid))
            if (alphabetical().compare(k.name(), n) == 0 && (id == null || k.id() != id))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("kind.exists", k.name()));
        String f = FIELDS.keySet().stream().filter(key -> fields != null && fields.contains(key))
                .collect(Collectors.joining(","));
        Map<String, String> sc = parseStatusColors(statuses, colors);
        long kindId;
        if (id == null) {
            kindId = jdbc.queryForObject("INSERT INTO kinds (user_id, name, fields) VALUES (?, ?, ?) RETURNING id",
                    Long.class, uid, n, f);
        } else {
            Kind old = kind(uid, id);
            jdbc.update("UPDATE kinds SET name = ?, fields = ? WHERE id = ?", n, f, id);
            jdbc.update("UPDATE collections SET kind = ? WHERE kind = ? AND user_id = ?", n, old.name(), uid);
            kindId = id;
        }
        jdbc.update("DELETE FROM kind_status_colors WHERE kind_id = ?", kindId);
        sc.forEach((st, color) -> jdbc.update(
                "INSERT INTO kind_status_colors (kind_id, status, color) VALUES (?, ?, ?)", kindId, st, color));
        return kindId;
    }

    /** Пары «статус — цвет» из формы: пустые статусы пропускаются, повтор статуса (без учёта регистра) — ошибка. */
    private static Map<String, String> parseStatusColors(List<String> statuses, List<String> colors) {
        Map<String, String> res = new LinkedHashMap<>();
        if (statuses == null) return res;
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < statuses.size(); i++) {
            String st = statuses.get(i) == null ? "" : statuses.get(i).trim();
            if (st.isEmpty()) continue;
            String color = colors != null && i < colors.size() ? colors.get(i) : null;
            if (color == null || !color.matches("#[0-9a-fA-F]{6}"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("kind.badColor", st));
            if (!seen.add(st.toLowerCase(Locale.ROOT)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("kind.statusTwice", st));
            res.put(st, color.toLowerCase(Locale.ROOT));
        }
        return res;
    }

    /** Удаляет тип, если ни одна коллекция его не использует. */
    public void deleteKind(long uid, long id) {
        Kind k = kind(uid, id);
        if (k.used() > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    I18n.t("kind.inUse", k.name(), k.used()));
        jdbc.update("DELETE FROM kinds WHERE id = ?", id);
    }

    // ---------- пользователи ----------

    /** Роли пользователей: ключ (users.role) → название. Администратор может добавлять пользователей. */
    public static final String ADMIN = "admin", USER = "user";
    public static final Map<String, String> ROLES = new LinkedHashMap<>();
    static {
        ROLES.put(ADMIN, "role.admin"); // подпись — ключ текста
        ROLES.put(USER, "role.user");
    }

    /** Пользователь: id, имя, хеш пароля (см. Passwords), роль (ключ из ROLES). */
    public record User(long id, String name, String passwordHash, String role) {
        public boolean isAdmin() {
            return ADMIN.equals(role);
        }
    }

    private static final String USER_SELECT = "SELECT id, name, password_hash, role FROM users";

    private static final RowMapper<User> USER_MAPPER = (rs, i) ->
            new User(rs.getLong("id"), rs.getString("name"), rs.getString("password_hash"), rs.getString("role"));

    public int countUsers() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
    }

    /** Все пользователи по алфавиту. */
    public List<User> users() {
        return jdbc.query(USER_SELECT, USER_MAPPER).stream()
                .sorted(Comparator.comparing(User::name, alphabetical())).toList();
    }

    /** Пользователь по имени без учёта регистра (в т.ч. кириллицы) или null. */
    public User userByName(String name) {
        if (name == null) return null;
        Comparator<String> abc = alphabetical();
        return jdbc.query(USER_SELECT, USER_MAPPER).stream()
                .filter(u -> abc.compare(u.name(), name.trim()) == 0).findFirst().orElse(null);
    }

    public User user(long uid) {
        List<User> list = jdbc.query(USER_SELECT + " WHERE id = ?", USER_MAPPER, uid);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Показывать ли цены открыто (настройка «Отображение»); по умолчанию — нет, только в подсказке. */
    public boolean showPrices(long uid) {
        List<Integer> v = jdbc.queryForList("SELECT show_prices FROM user_settings WHERE user_id = ?", Integer.class, uid);
        return !v.isEmpty() && v.get(0) == 1;
    }

    public void setShowPrices(long uid, boolean show) {
        jdbc.update("INSERT INTO user_settings (user_id, show_prices) VALUES (?, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET show_prices = excluded.show_prices", uid, show ? 1 : 0);
    }

    /** Запомненный вид таблицы объектов коллекции (строка параметров адреса) или null. */
    public String collectionView(long uid, long collectionId) {
        List<String> v = jdbc.queryForList("SELECT query FROM collection_views WHERE user_id = ? AND collection_id = ?",
                String.class, uid, collectionId);
        return v.isEmpty() ? null : v.get(0);
    }

    public void setCollectionView(long uid, long collectionId, String query) {
        jdbc.update("INSERT INTO collection_views (user_id, collection_id, query) VALUES (?, ?, ?) "
                + "ON CONFLICT (user_id, collection_id) DO UPDATE SET query = excluded.query", uid, collectionId, query);
    }

    /** Личный токен сервиса импорта (например, Discogs) или null. */
    public String token(long uid, String service) {
        List<String> v = jdbc.queryForList("SELECT token FROM user_tokens WHERE user_id = ? AND service = ?", String.class, uid, service);
        return v.isEmpty() ? null : v.get(0);
    }

    /** Сохраняет токен; пустой — удаляет. */
    public void setToken(long uid, String service, String token) {
        if (token == null || token.isBlank()) {
            jdbc.update("DELETE FROM user_tokens WHERE user_id = ? AND service = ?", uid, service);
            return;
        }
        jdbc.update("INSERT INTO user_tokens (user_id, service, token) VALUES (?, ?, ?) "
                + "ON CONFLICT (user_id, service) DO UPDATE SET token = excluded.token", uid, service, token);
    }

    /** Начальные типы коллекций нового пользователя: название → поля. */
    private static final Map<String, String> DEFAULT_KINDS = new LinkedHashMap<>();
    static {
        // название — ключ текста: типы создаются на языке интерфейса того, кто создаёт пользователя
        DEFAULT_KINDS.put("kind.default.gamesDiscs", "status,rating,price,available,oldgames");
        DEFAULT_KINDS.put("kind.default.gamesDigital", "platform,format,status,rating,price,available");
        DEFAULT_KINDS.put("kind.default.books", "price,available");
        DEFAULT_KINDS.put("kind.default.music", "price,available");
    }

    /** Создаёт пользователя с начальными типами коллекций. Возвращает id. */
    public long createUser(String name, String password, String role) {
        if (name == null || name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("user.nameRequired"));
        if (userByName(name) != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("user.exists", name.trim()));
        if (!ROLES.containsKey(role))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("user.badRole"));
        long uid = jdbc.queryForObject("INSERT INTO users (name, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class, name.trim(), Passwords.hash(password), role);
        DEFAULT_KINDS.forEach((k, f) -> jdbc.update("INSERT INTO kinds (user_id, name, fields) VALUES (?, ?, ?)", uid, I18n.t(k), f));
        return uid;
    }

    public void setPassword(long uid, String password) {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", Passwords.hash(password), uid);
    }

    // ---------- чтение ----------

    /**
     * Все коллекции по алфавиту. Сортируем в Java: SQLite не умеет сравнивать кириллицу без учёта регистра.
     * Коллекций немного, поэтому страницу на главной вырезаем из этого списка.
     */
    public List<Entry> collections(long uid) {
        List<Entry> list = jdbc.query("SELECT " + COLLECTION_COLS + ", (SELECT COUNT(*) FROM items i "
                + "WHERE i.collection_id = c.id) AS cnt FROM collections c WHERE c.user_id = ?", mapper(false, true), uid);
        Comparator<String> abc = alphabetical();
        return list.stream().sorted(Comparator.comparing(Entry::name, abc).thenComparing(Entry::id)).toList();
    }

    public Entry collection(long uid, long id) {
        return one(jdbc.query("SELECT " + COLLECTION_COLS + " FROM collections c WHERE id = ? AND user_id = ?",
                mapper(false, false), id, uid));
    }

    /** Поля, по которым можно сортировать объекты: ключ из URL → столбец. */
    public static final Map<String, String> ITEM_SORT = Map.of(
            "name", "name", "platform", "platform", "format", "format", "status", "status", "price", "price",
            "available", "available", "rating", "rating", "purchased", "purchased", "edition", "edition");

    /** Общая стоимость коллекции — сумма цен объектов (объекты без цены не считаются). */
    /** Счётчики коллекции для главной: сколько объектов в наличии и общая стоимость (сумма цен). */
    public record CollectionStats(int available, long price) {}

    /** Счётчики всех коллекций пользователя (коллекции без объектов в карте нет). */
    public Map<Long, CollectionStats> collectionStats(long uid) {
        Map<Long, CollectionStats> res = new HashMap<>();
        jdbc.query("SELECT i.collection_id, SUM(EXISTS (SELECT 1 FROM item_present a WHERE a.item_id = i.id)), "
                + "COALESCE(SUM(i.price), 0) FROM items i JOIN collections c ON c.id = i.collection_id "
                + "WHERE c.user_id = ? GROUP BY i.collection_id",
                rs -> { res.put(rs.getLong(1), new CollectionStats(rs.getInt(2), rs.getLong(3))); }, uid);
        return res;
    }

    public long totalPrice(long collectionId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(price), 0) FROM items WHERE collection_id = ?", Long.class, collectionId);
    }

    public int countItems(long collectionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM items WHERE collection_id = ?", Integer.class, collectionId);
    }

    /** Столбцы, по которым фильтр — выбор значения из списка (точное совпадение). */
    public static final List<String> SELECT_FILTERS = List.of("platform", "format", "status");

    /**
     * Объекты коллекции с фильтрами и сортировкой (страницу вырезает вызывающий код).
     * filters: "name" — часть названия без учёта регистра (в т.ч. кириллицы, поэтому проверяется в Java),
     * ключи из SELECT_FILTERS — точное значение. sort — ключ из ITEM_SORT (иначе по названию);
     * пустые значения всегда в конце, при равенстве — по названию.
     */
    public List<Entry> items(long collectionId, String sort, boolean desc, Map<String, String> filters) {
        String col = ITEM_SORT.getOrDefault(sort, "name");
        String order = "(" + col + " IS NULL OR " + col + " = ''), "
                + col + (col.equals("price") || col.equals("available") || col.equals("rating") ? "" : " COLLATE NOCASE") + (desc ? " DESC" : "")
                + ", name COLLATE NOCASE, id";
        StringBuilder where = new StringBuilder("collection_id = ?");
        List<Object> args = new java.util.ArrayList<>(List.of(collectionId));
        for (String f : SELECT_FILTERS)
            if (filters.containsKey(f)) {
                where.append(" AND ").append(f).append(" = ?"); // f — только из SELECT_FILTERS
                args.add(filters.get(f));
            }
        // Цена «от — до»: значения уже проверены в контроллере (целые числа); объекты без цены не подходят
        if (filters.containsKey("price_min")) {
            where.append(" AND price >= ?");
            args.add(Long.parseLong(filters.get("price_min")));
        }
        if (filters.containsKey("price_max")) {
            where.append(" AND price <= ?");
            args.add(Long.parseLong(filters.get("price_max")));
        }
        // Наличие: "1" — есть, "0" — нет
        if (filters.containsKey("available"))
            where.append("1".equals(filters.get("available")) ? " AND" : " AND NOT")
                    .append(" EXISTS (SELECT 1 FROM item_present a WHERE a.item_id = items.id)");
        // Рейтинг: "0" — без рейтинга, "1".."5" — не меньше стольких звёзд
        String rating = filters.get("rating");
        if (rating != null) {
            String r = "(SELECT r.rating FROM item_ratings r WHERE r.item_id = items.id)";
            if (rating.equals("0")) where.append(" AND ").append(r).append(" IS NULL");
            else {
                where.append(" AND ").append(r).append(" >= ?");
                args.add(Integer.parseInt(rating) * 2);
            }
        }
        // Регион / издание — точное значение; год покупки — дата начинается с него
        if (filters.containsKey("edition")) {
            where.append(" AND (SELECT d.edition FROM item_details d WHERE d.item_id = items.id) = ?");
            args.add(filters.get("edition"));
        }
        if (filters.containsKey("purchased")) {
            where.append(" AND (SELECT d.purchased FROM item_details d WHERE d.item_id = items.id) LIKE ?");
            args.add(filters.get("purchased") + "-%");
        }
        // Картинки: "any" — есть хотя бы одна, "0" — нет ни одной, "1".."5" — ровно столько
        String images = filters.get("images");
        if (images != null) {
            String count = "(SELECT COUNT(*) FROM item_pictures ip WHERE ip.item_id = items.id)";
            if (images.equals("any")) where.append(" AND ").append(count).append(" > 0");
            else {
                where.append(" AND ").append(count).append(" = ?");
                args.add(Integer.parseInt(images));
            }
        }
        List<Entry> list = jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE " + where + " ORDER BY " + order,
                mapper(true, false), args.toArray());
        // Тег — без учёта регистра (в т.ч. кириллицы), поэтому в Java
        String tag = filters.get("tags");
        if (tag != null) {
            String t = tag.toLowerCase(RU);
            list = list.stream().filter(e -> e.tagList().stream().anyMatch(x -> x.toLowerCase(RU).equals(t))).toList();
        }
        String name = filters.get("name");
        if (name == null) return list;
        String needle = name.toLowerCase(RU);
        return list.stream().filter(e -> e.name().toLowerCase(RU).contains(needle)).toList();
    }

    private static final Locale RU = Locale.forLanguageTag("ru");

    /** Сколько объектов показывать в поиске по всем коллекциям. */
    public static final int SEARCH_LIMIT = 200;

    /**
     * Поиск по всем коллекциям пользователя: часть названия, тега, региона / издания или заметки без учёта регистра
     * (в т.ч. кириллицы — поэтому в Java). Объекты — по алфавиту, не больше SEARCH_LIMIT; total — сколько найдено всего.
     */
    public record SearchResult(List<Entry> items, int total) {}

    public SearchResult search(long uid, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(RU);
        if (q.isEmpty()) return new SearchResult(List.of(), 0);
        List<Long> ids = new java.util.ArrayList<>();
        jdbc.query("SELECT i.id, i.name, d.tags, d.edition, d.notes FROM items i LEFT JOIN item_details d ON d.item_id = i.id "
                + "WHERE i.collection_id IN (SELECT id FROM collections WHERE user_id = ?)", rs -> {
            for (int col = 2; col <= 5; col++) {
                String s = rs.getString(col);
                if (s != null && s.toLowerCase(RU).contains(q)) {
                    ids.add(rs.getLong(1));
                    return;
                }
            }
        }, uid);
        if (ids.isEmpty()) return new SearchResult(List.of(), 0);
        List<Entry> found = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i += 500) { // по частям: у SQLite ограничено число параметров
            List<Long> part = ids.subList(i, Math.min(ids.size(), i + 500));
            found.addAll(jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE id IN ("
                    + String.join(",", java.util.Collections.nCopies(part.size(), "?")) + ")", mapper(true, false), part.toArray()));
        }
        Comparator<String> abc = alphabetical();
        return new SearchResult(found.stream().sorted(Comparator.comparing(Entry::name, abc).thenComparing(Entry::id))
                .limit(SEARCH_LIMIT).toList(), found.size());
    }

    // ---------- статистика ----------

    /**
     * Счётчики коллекции для страницы «Статистика»: объектов, в наличии, пройдено / не пройдено, с рейтингом и средний
     * рейтинг (в звёздах), стоимость, с картинками, с описанием.
     */
    public record Stats(long collectionId, int items, int available, int completed, int unfinished, int rated,
                        double avgRating, long price, int withImages, int withDescription) {}

    /** Счётчики всех коллекций пользователя: id коллекции → счётчики (коллекции без объектов — нули). */
    public Map<Long, Stats> stats(long uid) {
        Map<Long, Stats> res = new HashMap<>();
        jdbc.query("SELECT c.id, COUNT(i.id), "
                + "COALESCE(SUM(EXISTS (SELECT 1 FROM item_present a WHERE a.item_id = i.id)), 0), "
                + "COALESCE(SUM(i.status = 'Completed'), 0), COALESCE(SUM(i.status = 'Unfinished'), 0), "
                + "COUNT(r.rating), COALESCE(AVG(r.rating), 0), COALESCE(SUM(i.price), 0), "
                + "COALESCE(SUM(EXISTS (SELECT 1 FROM item_pictures p WHERE p.item_id = i.id)), 0), "
                + "COALESCE(SUM(EXISTS (SELECT 1 FROM item_descriptions d WHERE d.item_id = i.id)), 0) "
                + "FROM collections c LEFT JOIN items i ON i.collection_id = c.id LEFT JOIN item_ratings r ON r.item_id = i.id "
                + "WHERE c.user_id = ? GROUP BY c.id", rs -> {
            res.put(rs.getLong(1), new Stats(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                    rs.getInt(6), rs.getDouble(7) / 2, rs.getLong(8), rs.getInt(9), rs.getInt(10)));
        }, uid);
        return res;
    }

    /** Сколько объектов с рейтингом в каждой целой звезде (округление вверх: 3,5 → 4): индекс 0 — 1 звезда … 4 — 5 звёзд. */
    public int[] ratingHistogram(long uid) {
        int[] res = new int[5];
        jdbc.query("SELECT (r.rating + 1) / 2, COUNT(*) FROM item_ratings r JOIN items i ON i.id = r.item_id "
                + "JOIN collections c ON c.id = i.collection_id WHERE c.user_id = ? GROUP BY 1",
                rs -> { int s = rs.getInt(1); if (s >= 1 && s <= 5) res[s - 1] = rs.getInt(2); }, uid);
        return res;
    }

    /** Покупки по годам (дата покупки): год → {объектов, сумма цен}; новые годы сверху. */
    public Map<String, long[]> purchasesByYear(long uid) {
        Map<String, long[]> res = new java.util.TreeMap<>(Comparator.reverseOrder());
        jdbc.query("SELECT substr(d.purchased, 1, 4), COUNT(*), COALESCE(SUM(i.price), 0) FROM item_details d "
                + "JOIN items i ON i.id = d.item_id JOIN collections c ON c.id = i.collection_id "
                + "WHERE c.user_id = ? AND d.purchased IS NOT NULL GROUP BY 1",
                rs -> { res.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)}); }, uid);
        return res;
    }

    /** Самые частые теги: тег → сколько объектов, не больше limit, по убыванию. */
    public List<Map.Entry<String, Integer>> topTags(long uid, int limit) {
        Map<String, Integer> count = new java.util.TreeMap<>(alphabetical());
        jdbc.query("SELECT d.tags FROM item_details d JOIN items i ON i.id = d.item_id JOIN collections c ON c.id = i.collection_id "
                + "WHERE c.user_id = ? AND d.tags IS NOT NULL", rs -> {
            for (String t : splitTags(rs.getString(1))) count.merge(t, 1, Integer::sum);
        }, uid);
        return count.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(limit).toList();
    }

    /** Значения столбца у объектов коллекции — для выпадающего списка фильтра (по алфавиту, без пустых). */
    /** Значения дополнительных полей для фильтров: регион / издание, теги, годы покупки (новые сверху). */
    public List<String> detailValues(long collectionId, String field) {
        String sql = "SELECT d.%s FROM item_details d JOIN items i ON i.id = d.item_id WHERE i.collection_id = ? AND d.%s IS NOT NULL";
        return switch (field) {
            case "edition" -> jdbc.queryForList(String.format(sql, "edition", "edition"), String.class, collectionId).stream()
                    .distinct().sorted(alphabetical()).toList();
            case "tags" -> {
                Map<String, String> tags = new java.util.TreeMap<>(alphabetical());
                for (String s : jdbc.queryForList(String.format(sql, "tags", "tags"), String.class, collectionId))
                    for (String t : splitTags(s)) tags.putIfAbsent(t, t);
                yield List.copyOf(tags.values());
            }
            case "purchased" -> jdbc.queryForList(String.format(sql, "purchased", "purchased"), String.class, collectionId).stream()
                    .map(d -> d.substring(0, 4)).distinct().sorted(Comparator.reverseOrder()).toList();
            default -> throw new IllegalArgumentException(field);
        };
    }

    public List<String> distinctValues(long collectionId, String column) {
        if (!SELECT_FILTERS.contains(column)) throw new IllegalArgumentException(column);
        return jdbc.queryForList("SELECT DISTINCT " + column + " FROM items WHERE collection_id = ? AND "
                + column + " <> ''", String.class, collectionId).stream().sorted(alphabetical()).toList();
    }

    public Entry item(long uid, long id) {
        return one(jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE id = ? AND collection_id IN "
                + "(SELECT id FROM collections WHERE user_id = ?)", mapper(true, false), id, uid));
    }

    /** Картинка коллекции: {image, image_mime} или 404. */
    public Map<String, Object> collectionImage(long uid, long id) {
        collection(uid, id); // чужая коллекция — 404
        return picture(files.collection(id));
    }

    /**
     * Картинка по хешу содержимого (адрес /img/<хеш>), если на неё ссылается объект или коллекция этого пользователя;
     * иначе null (чужие картинки не отдаются, даже если знать хеш).
     */
    public ImageFiles.Pic ownedPicture(long uid, String hash) {
        List<String> mime = jdbc.queryForList("SELECT p.mime FROM pictures p WHERE p.hash = ? AND ("
                + "EXISTS (SELECT 1 FROM item_pictures ip JOIN items i ON i.id = ip.item_id JOIN collections c ON c.id = i.collection_id "
                + "WHERE ip.hash = p.hash AND c.user_id = ?) OR "
                + "EXISTS (SELECT 1 FROM collection_pictures cp JOIN collections c ON c.id = cp.collection_id "
                + "WHERE cp.hash = p.hash AND c.user_id = ?))", String.class, hash, uid, uid);
        return mime.isEmpty() ? null : new ImageFiles.Pic(hash, mime.get(0));
    }

    /** Содержимое картинки (null — файла нет). */
    public byte[] pictureBytes(ImageFiles.Pic pic) {
        return files.read(pic);
    }

    /** Уменьшенная копия картинки или null — тогда отдаётся сама картинка. */
    public ImageFiles.Thumb thumb(ImageFiles.Pic pic) {
        return files.thumb(pic);
    }

    /** Картинка объекта номер n (с нуля): {image, image_mime} или 404 (в т.ч. если объект чужой). */
    public Map<String, Object> itemImage(long uid, long id, int n) {
        item(uid, id); // чужой объект — 404
        List<ImageFiles.Pic> pics = files.item(id);
        return picture(n < 0 || n >= pics.size() ? null : pics.get(n));
    }

    private Map<String, Object> picture(ImageFiles.Pic pic) {
        byte[] bytes = pic == null ? null : files.read(pic);
        if (bytes == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("image", bytes, "image_mime", pic.mime());
    }

    // ---------- изменение ----------

    public long createCollection(long uid) {
        return jdbc.queryForObject("INSERT INTO collections (user_id, name) VALUES (?, '') RETURNING id", Long.class, uid);
    }

    public long createItem(long collectionId) {
        return jdbc.queryForObject("INSERT INTO items (collection_id, name) VALUES (?, '') RETURNING id",
                Long.class, collectionId);
    }

    /** Сохраняет коллекцию: название, тип и (если загружена) картинку; removeImage удаляет картинку. */
    public void saveCollection(long uid, long id, String name, String kind, MultipartFile image, boolean removeImage) {
        requireName(name);
        if (kind == null || jdbc.queryForObject("SELECT COUNT(*) FROM kinds WHERE user_id = ? AND name = ?",
                Integer.class, uid, kind) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("coll.kindRequired"));
        jdbc.update("UPDATE collections SET name = ?, kind = ? WHERE id = ?", name.trim(), kind, id);

        if (image != null && !image.isEmpty()) {
            files.setCollection(id, readImage(image));
        } else if (removeImage) {
            files.setCollection(id, null);
        }
    }

    /** Наибольшая длина описания объекта. */
    public static final int MAX_DESCRIPTION = 5000;

    /** Записывает описание объекта (пустое — удаляет); владельца проверяет вызывающий код. */
    public void setItemDescription(long itemId, String text) {
        String t = text == null ? "" : text.replace("\r\n", "\n").trim();
        if (t.length() > MAX_DESCRIPTION)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("item.descriptionTooLong", MAX_DESCRIPTION));
        if (t.isEmpty()) jdbc.update("DELETE FROM item_descriptions WHERE item_id = ?", itemId);
        else jdbc.update("INSERT INTO item_descriptions (item_id, text) VALUES (?, ?) ON CONFLICT (item_id) "
                + "DO UPDATE SET text = excluded.text", itemId, t);
    }

    /**
     * Заменяет все картинки объекта (до ImageFiles.MAX_IMAGES, по порядку; пустой список — удаляет все).
     * Не картинки пропускаются. Владельца проверяет вызывающий код.
     */
    public void setItemImages(long itemId, List<byte[]> images) {
        files.setItem(itemId, images);
    }

    /** Сколько картинок у объектов каждой коллекции пользователя: id коллекции → число (вкладка «Кэш»). */
    public Map<Long, Integer> collectionImageCounts(long uid) {
        Map<Long, Integer> counts = new HashMap<>();
        jdbc.query("SELECT i.collection_id, COUNT(*) FROM item_pictures ip JOIN items i ON i.id = ip.item_id "
                + "JOIN collections c ON c.id = i.collection_id WHERE c.user_id = ? GROUP BY i.collection_id",
                rs -> { counts.put(rs.getLong(1), rs.getInt(2)); }, uid);
        return counts;
    }

    /** Содержимое загруженной картинки; если это не PNG, JPEG, GIF или WebP — ошибка 400. */
    private static byte[] readImage(MultipartFile image) {
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("image.readError"));
        }
        // Тип определяется по содержимому файла, а не по тому, что прислал браузер
        if (imageType(bytes) == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("image.badFormat"));
        return bytes;
    }

    /** Тип картинки по первым байтам файла: PNG, JPEG, GIF, WebP; иначе null (в т.ч. SVG — в нём бывают скрипты). */
    static String imageType(byte[] b) {
        if (starts(b, 0, 0x89, 'P', 'N', 'G')) return "image/png";
        if (starts(b, 0, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (starts(b, 0, 'G', 'I', 'F', '8')) return "image/gif";
        if (starts(b, 0, 'R', 'I', 'F', 'F') && starts(b, 8, 'W', 'E', 'B', 'P')) return "image/webp";
        return null;
    }

    private static boolean starts(byte[] b, int from, int... sig) {
        if (b.length < from + sig.length) return false;
        for (int i = 0; i < sig.length; i++)
            if ((b[from + i] & 0xFF) != sig[i]) return false;
        return true;
    }

    /**
     * Сохраняет объект: название и те поля, которые есть у типа коллекции (см. настройки типа).
     * Остальные поля не трогаем, чтобы данные не терялись при смене типа коллекции.
     */
    public void saveItem(long uid, long id, String kind, String name, String platform, String format, String status,
                         Integer price, Boolean available, Integer rating, String oldGames, Details details,
                         Map<Integer, MultipartFile> newImages, Set<Integer> removed, String description) {
        requireName(name);
        if (price != null && price < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("item.negativePrice"));
        Set<String> f = itemFields(uid, kind);
        jdbc.update("UPDATE items SET name = ? WHERE id = ?", name.trim(), id);
        if (f.contains("platform")) jdbc.update("UPDATE items SET platform = ? WHERE id = ?", trim(platform), id);
        if (f.contains("format")) jdbc.update("UPDATE items SET format = ? WHERE id = ?", trim(format), id);
        if (f.contains("status")) {
            // только значения из списка; прежнее значение не из списка можно оставить как есть
            String st = trim(status);
            String old = jdbc.queryForObject("SELECT status FROM items WHERE id = ?", String.class, id);
            if (st != null && !st.isEmpty() && !STATUSES.contains(st) && !st.equals(old))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("item.badStatus"));
            jdbc.update("UPDATE items SET status = ? WHERE id = ?", st, id);
        }
        if (f.contains("price")) jdbc.update("UPDATE items SET price = ? WHERE id = ?", price, id);
        // Рейтинг: 0 — убрать, 1…10 — половинки звезды; null — поля нет в форме, не меняется
        if (f.contains("rating") && rating != null) setRating(id, rating);
        // Ссылка на Old-Games.ru: пусто — убрать; только адрес страницы игры; null — поля нет в форме, не меняется
        if (f.contains("oldgames") && oldGames != null) {
            if (oldGames.isBlank()) jdbc.update("DELETE FROM item_oldgames WHERE item_id = ?", id);
            else {
                String url = OldGames.normalize(oldGames);
                if (url == null)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("og.needIdField"));
                jdbc.update("INSERT INTO item_oldgames (item_id, url) VALUES (?, ?) "
                        + "ON CONFLICT (item_id) DO UPDATE SET url = excluded.url", id, url);
            }
        }
        if (f.contains("available") && available != null) setAvailable(id, available);
        // Дополнительные поля (item_details): null — поля нет в форме, не меняется; пусто — очистить
        if (details != null) {
            if (f.contains("purchased") && details.purchased() != null) setDetail(id, "purchased", purchaseDate(details.purchased()));
            if (f.contains("tags") && details.tags() != null) setDetail(id, "tags", limit(normalizeTags(details.tags()), MAX_TAGS));
            if (f.contains("edition") && details.edition() != null) setDetail(id, "edition", limit(trim(details.edition()), MAX_EDITION));
            if (f.contains("notes") && details.notes() != null)
                setDetail(id, "notes", limit(details.notes().replace("\r\n", "\n").trim(), MAX_NOTES));
        }
        // Описание и картинка есть у всех объектов, независимо от типа коллекции
        if (description != null) setItemDescription(id, description);
        // Картинки: новый файл на месте pos заменяет картинку (или добавляет), removed — удалить; без пропусков по порядку
        if (!newImages.isEmpty() || !removed.isEmpty()) {
            List<ImageFiles.Pic> pics = files.item(id);
            List<byte[]> result = new java.util.ArrayList<>();
            for (int pos = 0; pos < ImageFiles.MAX_IMAGES; pos++) {
                MultipartFile upload = newImages.get(pos);
                if (upload != null && !upload.isEmpty()) result.add(readImage(upload));
                else if (pos < pics.size() && !removed.contains(pos)) result.add(files.read(pics.get(pos)));
            }
            setItemImages(id, result);
        }
    }

    /** Статус прохождения: пусто или значение из STATUSES (массовое изменение — без прежних значений не из списка). */
    public void setStatus(long itemId, String status) {
        jdbc.update("UPDATE items SET status = ? WHERE id = ?", checkStatus(status), itemId);
    }

    /** Статус из списка или пусто (без пробелов по краям); иначе — 400. */
    public static String checkStatus(String status) {
        String st = trim(status);
        if (!st.isEmpty() && !STATUSES.contains(st)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("item.badStatus"));
        return st;
    }

    /** Переносит объект в другую коллекцию (владельца обеих проверяет вызывающий код); поля и картинки не меняются. */
    public void moveItem(long itemId, long collectionId) {
        jdbc.update("UPDATE items SET collection_id = ? WHERE id = ?", collectionId, itemId);
    }

    /** Дополнительные поля объекта из формы (null — поля нет в форме). */
    public record Details(String purchased, String tags, String edition, String notes) {}

    /** Наибольшая длина тегов (всех вместе), региона / издания и заметок. */
    public static final int MAX_TAGS = 500, MAX_EDITION = 200, MAX_NOTES = 2000;

    /** Наличие объекта: есть — строка в item_present, нет — строки нет. */
    public void setAvailable(long itemId, boolean available) {
        if (available) jdbc.update("INSERT OR IGNORE INTO item_present (item_id) VALUES (?)", itemId);
        else jdbc.update("DELETE FROM item_present WHERE item_id = ?", itemId);
    }

    /** Рейтинг объекта: 0 — убрать, 1…10 — половинки звезды. */
    public void setRating(long itemId, int rating) {
        if (rating < 0 || rating > 10) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("item.badRating"));
        if (rating == 0) jdbc.update("DELETE FROM item_ratings WHERE item_id = ?", itemId);
        else jdbc.update("INSERT INTO item_ratings (item_id, rating) VALUES (?, ?) "
                + "ON CONFLICT (item_id) DO UPDATE SET rating = excluded.rating", itemId, rating);
    }

    /** Одно дополнительное поле (столбец item_details из фиксированного списка); пусто — NULL, пустая строка удаляется. */
    private void setDetail(long itemId, String column, String value) {
        if (!List.of("purchased", "tags", "edition", "notes").contains(column)) throw new IllegalArgumentException(column);
        String v = value == null || value.isEmpty() ? null : value;
        jdbc.update("INSERT INTO item_details (item_id, " + column + ") VALUES (?, ?) "
                + "ON CONFLICT (item_id) DO UPDATE SET " + column + " = excluded." + column, itemId, v);
        jdbc.update("DELETE FROM item_details WHERE item_id = ? AND purchased IS NULL AND tags IS NULL "
                + "AND edition IS NULL AND notes IS NULL", itemId);
    }

    /** Дата покупки из поля даты (ГГГГ-ММ-ДД) или пусто; другое — 400. */
    private static String purchaseDate(String value) {
        String v = trim(value);
        if (v.isEmpty()) return "";
        try {
            return java.time.LocalDate.parse(v).toString();
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("item.badDate"));
        }
    }

    /** Теги: через запятую, без пробелов по краям и повторов (без учёта регистра), в порядке ввода. */
    public static List<String> splitTags(String tags) {
        if (tags == null) return List.of();
        Map<String, String> res = new LinkedHashMap<>();
        for (String t : tags.split(",")) {
            String s = t.trim().replaceAll("\\s+", " ");
            if (!s.isEmpty()) res.putIfAbsent(s.toLowerCase(RU), s);
        }
        return List.copyOf(res.values());
    }

    private static String normalizeTags(String tags) {
        return String.join(", ", splitTags(tags));
    }

    private static String limit(String s, int max) {
        if (s.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("item.tooLong", max));
        return s;
    }

    /** Удаляет запись; владельца проверяет вызывающий код (collection/item с uid). */
    public void delete(String table, long id) {
        // ссылки на картинки удаляются каскадно вместе с записью; файлы — если на них больше никто не ссылается
        List<String> pics = table.equals("items") ? files.itemHashes(id) : files.collectionHashes(id);
        jdbc.update("DELETE FROM " + table + " WHERE id = ?", id); // объекты коллекции удаляются каскадно
        files.release(pics);
    }

    // ---------- вспомогательное ----------

    private static void requireName(String name) {
        if (name == null || name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t("item.nameRequired"));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static Entry one(List<Entry> list) {
        if (list.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return list.get(0);
    }
}
