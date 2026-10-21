package app;

import java.io.IOException;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
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

    private static final String COLLECTION_COLS = "id, name, image_mime IS NOT NULL AS has_image, kind";
    private static final String ITEM_COLS = "id, name, collection_id, platform, format, status, price";

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
    }

    private final JdbcTemplate jdbc;

    public Repo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static RowMapper<Entry> mapper(boolean isItem, boolean withCount) {
        return (rs, i) -> new Entry(
                rs.getLong("id"),
                rs.getString("name"),
                !isItem && rs.getBoolean("has_image"),
                isItem ? rs.getLong("collection_id") : null,
                withCount ? rs.getInt("cnt") : 0,
                isItem ? null : rs.getString("kind"),
                isItem ? rs.getString("platform") : null,
                isItem ? rs.getString("format") : null,
                isItem ? rs.getString("status") : null,
                isItem ? price(rs) : null);
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

    /** Статусы, которые уже встречаются у объектов коллекций этого типа (для подсказок в форме). */
    public List<String> usedStatuses(long uid, String kindName) {
        return jdbc.queryForList("SELECT DISTINCT i.status FROM items i JOIN collections c ON c.id = i.collection_id "
                + "WHERE c.user_id = ? AND c.kind = ? AND i.status <> ''", String.class, uid, kindName)
                .stream().sorted(alphabetical()).toList();
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

    /** Пользователь: id, имя, хеш пароля (см. Passwords). */
    public record User(long id, String name, String passwordHash) {}

    private static final RowMapper<User> USER_MAPPER =
            (rs, i) -> new User(rs.getLong("id"), rs.getString("name"), rs.getString("password_hash"));

    /** Пользователь по имени без учёта регистра (в т.ч. кириллицы) или null. */
    public User userByName(String name) {
        if (name == null) return null;
        Comparator<String> abc = alphabetical();
        return jdbc.query("SELECT id, name, password_hash FROM users", USER_MAPPER).stream()
                .filter(u -> abc.compare(u.name(), name.trim()) == 0).findFirst().orElse(null);
    }

    public User user(long uid) {
        List<User> list = jdbc.query("SELECT id, name, password_hash FROM users WHERE id = ?", USER_MAPPER, uid);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Начальные типы коллекций нового пользователя: название → поля. */
    private static final Map<String, String> DEFAULT_KINDS = new LinkedHashMap<>();
    static {
        DEFAULT_KINDS.put("Игры (диски)", "status,price");
        DEFAULT_KINDS.put("Игры (цифровые)", "platform,format,status,price");
        DEFAULT_KINDS.put("Книги", "price");
        DEFAULT_KINDS.put("Музыка (диски)", "price");
    }

    /** Создаёт пользователя с начальными типами коллекций. Возвращает id. */
    public long createUser(String name, String password) {
        if (name == null || name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите имя пользователя");
        if (userByName(name) != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Пользователь «" + name.trim() + "» уже есть");
        long uid = jdbc.queryForObject("INSERT INTO users (name, password_hash) VALUES (?, ?) RETURNING id",
                Long.class, name.trim(), Passwords.hash(password));
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
        return one(jdbc.query("SELECT " + COLLECTION_COLS + " FROM collections WHERE id = ? AND user_id = ?",
                mapper(false, false), id, uid));
    }

    /** Поля, по которым можно сортировать объекты: ключ из URL → столбец. */
    public static final Map<String, String> ITEM_SORT = Map.of(
            "name", "name", "platform", "platform", "format", "format", "status", "status", "price", "price");

    public int countItems(long collectionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM items WHERE collection_id = ?", Integer.class, collectionId);
    }

    /**
     * Страница объектов коллекции. sort — ключ из ITEM_SORT (иначе по названию).
     * Пустые значения всегда в конце, при равенстве — по названию.
     */
    public List<Entry> items(long collectionId, String sort, boolean desc, int limit, int offset) {
        String col = ITEM_SORT.getOrDefault(sort, "name");
        String order = "(" + col + " IS NULL OR " + col + " = ''), "
                + col + (col.equals("price") ? "" : " COLLATE NOCASE") + (desc ? " DESC" : "")
                + ", name COLLATE NOCASE, id";
        return jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE collection_id = ? ORDER BY " + order
                + " LIMIT ? OFFSET ?", mapper(true, false), collectionId, limit, offset);
    }

    public Entry item(long uid, long id) {
        return one(jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE id = ? AND collection_id IN "
                + "(SELECT id FROM collections WHERE user_id = ?)", mapper(true, false), id, uid));
    }

    /** Картинка коллекции: {image, image_mime} или 404. */
    public Map<String, Object> collectionImage(long uid, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT image, image_mime FROM collections WHERE id = ? AND user_id = ? AND image IS NOT NULL", id, uid);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.get(0);
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
            String mime = image.getContentType();
            if (mime == null || !mime.startsWith("image/"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл должен быть картинкой");
            try {
                jdbc.update("UPDATE collections SET image = ?, image_mime = ? WHERE id = ?", image.getBytes(), mime, id);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не удалось прочитать файл");
            }
        } else if (removeImage) {
            jdbc.update("UPDATE collections SET image = NULL, image_mime = NULL WHERE id = ?", id);
        }
    }

    /**
     * Сохраняет объект: название и те поля, которые есть у типа коллекции (см. настройки типа).
     * Остальные поля не трогаем, чтобы данные не терялись при смене типа коллекции.
     */
    public void saveItem(long uid, long id, String kind, String name, String platform, String format, String status,
                         Integer price) {
        requireName(name);
        if (price != null && price < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Цена не может быть отрицательной");
        Set<String> f = itemFields(uid, kind);
        jdbc.update("UPDATE items SET name = ? WHERE id = ?", name.trim(), id);
        if (f.contains("platform")) jdbc.update("UPDATE items SET platform = ? WHERE id = ?", trim(platform), id);
        if (f.contains("format")) jdbc.update("UPDATE items SET format = ? WHERE id = ?", trim(format), id);
        if (f.contains("status")) jdbc.update("UPDATE items SET status = ? WHERE id = ?", trim(status), id);
        if (f.contains("price")) jdbc.update("UPDATE items SET price = ? WHERE id = ?", price, id);
    }

    /** Удаляет запись; владельца проверяет вызывающий код (collection/item с uid). */
    public void delete(String table, long id) {
        jdbc.update("DELETE FROM " + table + " WHERE id = ?", id); // объекты коллекции удаляются каскадно
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
