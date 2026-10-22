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
            "id, name, EXISTS (SELECT 1 FROM collection_pictures cp WHERE cp.collection_id = c.id) AS has_image, kind";
    private static final String ITEM_COLS = "id, name, collection_id, platform, format, status, price, "
            + "EXISTS (SELECT 1 FROM item_pictures ip WHERE ip.item_id = items.id) AS has_image, "
            + "(SELECT COUNT(*) FROM item_pictures ip WHERE ip.item_id = items.id) AS image_count, "
            + "(SELECT d.text FROM item_descriptions d WHERE d.item_id = items.id) AS description, "
            + "EXISTS (SELECT 1 FROM item_present a WHERE a.item_id = items.id) AS available";

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
        FIELDS.put("platform", "Платформа");
        FIELDS.put("format", "Формат");
        FIELDS.put("status", "Статус прохождения");
        FIELDS.put("price", "Цена");
        FIELDS.put("available", "Наличие"); // не столбец items: отметки «есть» — в таблице item_present, по умолчанию «нет»
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
                isItem ? rs.getInt("image_count") : 0);
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
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Тип «" + k.name() + "» уже есть");
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
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неверный цвет для статуса «" + st + "»");
            if (!seen.add(st.toLowerCase(Locale.ROOT)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Статус «" + st + "» указан дважды");
            res.put(st, color.toLowerCase(Locale.ROOT));
        }
        return res;
    }

    /** Удаляет тип, если ни одна коллекция его не использует. */
    public void deleteKind(long uid, long id) {
        Kind k = kind(uid, id);
        if (k.used() > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Тип «" + k.name() + "» используется в коллекциях (" + k.used() + "), его нельзя удалить");
        jdbc.update("DELETE FROM kinds WHERE id = ?", id);
    }

    // ---------- пользователи ----------

    /** Роли пользователей: ключ (users.role) → название. Администратор может добавлять пользователей. */
    public static final String ADMIN = "admin", USER = "user";
    public static final Map<String, String> ROLES = new LinkedHashMap<>();
    static {
        ROLES.put(ADMIN, "Администратор");
        ROLES.put(USER, "Пользователь");
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
        DEFAULT_KINDS.put("Игры (диски)", "status,price,available");
        DEFAULT_KINDS.put("Игры (цифровые)", "platform,format,status,price,available");
        DEFAULT_KINDS.put("Книги", "price,available");
        DEFAULT_KINDS.put("Музыка (диски)", "price,available");
    }

    /** Создаёт пользователя с начальными типами коллекций. Возвращает id. */
    public long createUser(String name, String password, String role) {
        if (name == null || name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите имя пользователя");
        if (userByName(name) != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Пользователь «" + name.trim() + "» уже есть");
        if (!ROLES.containsKey(role))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестная роль");
        long uid = jdbc.queryForObject("INSERT INTO users (name, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class, name.trim(), Passwords.hash(password), role);
        DEFAULT_KINDS.forEach((k, f) -> jdbc.update("INSERT INTO kinds (user_id, name, fields) VALUES (?, ?, ?)", uid, k, f));
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
            "available", "available");

    /** Общая стоимость коллекции — сумма цен объектов (объекты без цены не считаются). */
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
                + col + (col.equals("price") || col.equals("available") ? "" : " COLLATE NOCASE") + (desc ? " DESC" : "")
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
        List<Entry> list = jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE " + where + " ORDER BY " + order,
                mapper(true, false), args.toArray());
        String name = filters.get("name");
        if (name == null) return list;
        String needle = name.toLowerCase(RU);
        return list.stream().filter(e -> e.name().toLowerCase(RU).contains(needle)).toList();
    }

    private static final Locale RU = Locale.forLanguageTag("ru");

    /** Значения столбца у объектов коллекции — для выпадающего списка фильтра (по алфавиту, без пустых). */
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите тип коллекции");
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Описание длиннее " + MAX_DESCRIPTION + " символов");
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

    /**
     * Удаляет все картинки объектов коллекции; обложка коллекции остаётся. Файл стирается, только если
     * на ту же картинку больше никто не ссылается. Чужая коллекция — 404. Возвращает, сколько картинок удалено.
     */
    public int clearCollectionImages(long uid, long collectionId) {
        collection(uid, collectionId);
        return files.clearCollectionItems(collectionId);
    }

    /** Содержимое загруженной картинки; если это не PNG, JPEG, GIF или WebP — ошибка 400. */
    private static byte[] readImage(MultipartFile image) {
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не удалось прочитать файл");
        }
        // Тип определяется по содержимому файла, а не по тому, что прислал браузер
        if (imageType(bytes) == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Картинка должна быть в формате PNG, JPEG, GIF или WebP");
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
                         Integer price, Boolean available, Map<Integer, MultipartFile> newImages, Set<Integer> removed,
                         String description) {
        requireName(name);
        if (price != null && price < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Цена не может быть отрицательной");
        Set<String> f = itemFields(uid, kind);
        jdbc.update("UPDATE items SET name = ? WHERE id = ?", name.trim(), id);
        if (f.contains("platform")) jdbc.update("UPDATE items SET platform = ? WHERE id = ?", trim(platform), id);
        if (f.contains("format")) jdbc.update("UPDATE items SET format = ? WHERE id = ?", trim(format), id);
        if (f.contains("status")) {
            // только значения из списка; прежнее значение не из списка можно оставить как есть
            String st = trim(status);
            String old = jdbc.queryForObject("SELECT status FROM items WHERE id = ?", String.class, id);
            if (st != null && !st.isEmpty() && !STATUSES.contains(st) && !st.equals(old))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите статус из списка");
            jdbc.update("UPDATE items SET status = ? WHERE id = ?", st, id);
        }
        if (f.contains("price")) jdbc.update("UPDATE items SET price = ? WHERE id = ?", price, id);
        if (f.contains("available") && available != null) {
            if (available) jdbc.update("INSERT OR IGNORE INTO item_present (item_id) VALUES (?)", id);
            else jdbc.update("DELETE FROM item_present WHERE item_id = ?", id);
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Название обязательно");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static Entry one(List<Entry> list) {
        if (list.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return list.get(0);
    }
}
