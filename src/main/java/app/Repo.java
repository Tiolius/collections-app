package app;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Вся работа с базой. table — "collections" или "items". */
@Repository
public class Repo {

    private static final String COLLECTION_COLS = "id, name, image_mime IS NOT NULL AS has_image, kind";
    private static final String ITEM_COLS = "id, name, collection_id, platform, format, status, price";

    /** Типы коллекций (в порядке выпадающего списка). */
    public static final String GAMES_DISC = "Игры (диски)", GAMES_DIGITAL = "Игры (цифровые)",
            MUSIC_DISC = "Музыка (диски)", BOOKS = "Книги";
    public static final List<String> KINDS = List.of(GAMES_DISC, GAMES_DIGITAL, MUSIC_DISC, BOOKS);

    /** Поля объекта (кроме названия) в зависимости от типа коллекции. */
    private static final Map<String, Set<String>> KIND_FIELDS = Map.of(
            GAMES_DIGITAL, Set.of("platform", "format", "status", "price"), // как вкладка PC Collection
            GAMES_DISC, Set.of("status", "price"),                          // как вкладки издателей
            MUSIC_DISC, Set.of("price"),
            BOOKS, Set.of("price"));
    private static final Set<String> ALL_FIELDS = Set.of("platform", "format", "status", "price");

    /** Какие поля есть у объектов коллекции этого типа (у коллекции без типа — все). */
    public static Set<String> itemFields(String kind) {
        return KIND_FIELDS.getOrDefault(kind, ALL_FIELDS);
    }

    private final JdbcTemplate jdbc;

    public Repo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        migrate();
    }

    /** schema.sql создаёт только новые таблицы, поэтому старые базы приводим к текущей схеме здесь. */
    private void migrate() {
        addColumn("collections", "kind", "TEXT");
        addColumn("items", "platform", "TEXT");
        addColumn("items", "format", "TEXT");
        addColumn("items", "status", "TEXT");
        addColumn("items", "price", "INTEGER");
        // Старые названия типов коллекций
        jdbc.update("UPDATE collections SET kind = ? WHERE kind = 'Физическая'", GAMES_DISC);
        jdbc.update("UPDATE collections SET kind = ? WHERE kind = 'Цифровая'", GAMES_DIGITAL);
        // Столбцы, которые больше не используются: у объектов нет издателя, типа и картинки
        boolean dropped = false;
        for (String col : List.of("publisher", "type", "image", "image_mime")) dropped |= dropColumn("items", col);
        dropped |= dropColumn("collections", "type"); // свободный текст «Тип» заменён на тип коллекции (kind)
        if (dropped) jdbc.execute("VACUUM"); // вернуть место, которое занимали удалённые картинки
    }

    private boolean dropColumn(String table, String col) {
        if (!columns(table).contains(col)) return false;
        jdbc.execute("ALTER TABLE " + table + " DROP COLUMN " + col);
        return true;
    }

    private void addColumn(String table, String col, String type) {
        if (!columns(table).contains(col)) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + type);
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("SELECT name FROM pragma_table_info(?)", String.class, table);
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

    // ---------- чтение ----------

    public List<Entry> allCollections() {
        return jdbc.query("SELECT " + COLLECTION_COLS + ", (SELECT COUNT(*) FROM items i WHERE i.collection_id = c.id) AS cnt "
                + "FROM collections c ORDER BY name COLLATE NOCASE", mapper(false, true));
    }

    public Entry collection(long id) {
        return one(jdbc.query("SELECT " + COLLECTION_COLS + " FROM collections WHERE id = ?", mapper(false, false), id));
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

    public Entry item(long id) {
        return one(jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE id = ?", mapper(true, false), id));
    }

    /** Картинка коллекции: {image, image_mime} или 404. */
    public Map<String, Object> collectionImage(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT image, image_mime FROM collections WHERE id = ? AND image IS NOT NULL", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    // ---------- изменение ----------

    public long createCollection() {
        return jdbc.queryForObject("INSERT INTO collections (name) VALUES ('') RETURNING id", Long.class);
    }

    public long createItem(long collectionId) {
        return jdbc.queryForObject("INSERT INTO items (collection_id, name) VALUES (?, '') RETURNING id",
                Long.class, collectionId);
    }

    /** Сохраняет коллекцию: название, тип и (если загружена) картинку; removeImage удаляет картинку. */
    public void saveCollection(long id, String name, String kind, MultipartFile image, boolean removeImage) {
        requireName(name);
        if (!KINDS.contains(kind))
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
     * Сохраняет объект: название и те поля, которые есть у типа коллекции (itemFields).
     * Остальные поля не трогаем, чтобы данные не терялись при смене типа коллекции.
     */
    public void saveItem(long id, String kind, String name, String platform, String format, String status, Integer price) {
        requireName(name);
        if (price != null && price < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Цена не может быть отрицательной");
        Set<String> f = itemFields(kind);
        jdbc.update("UPDATE items SET name = ? WHERE id = ?", name.trim(), id);
        if (f.contains("platform")) jdbc.update("UPDATE items SET platform = ? WHERE id = ?", trim(platform), id);
        if (f.contains("format")) jdbc.update("UPDATE items SET format = ? WHERE id = ?", trim(format), id);
        if (f.contains("status")) jdbc.update("UPDATE items SET status = ? WHERE id = ?", trim(status), id);
        if (f.contains("price")) jdbc.update("UPDATE items SET price = ? WHERE id = ?", price, id);
    }

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
