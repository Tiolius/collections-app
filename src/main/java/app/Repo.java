package app;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Вся работа с базой. table — "collections" или "items". */
@Repository
public class Repo {

    private static final String COLLECTION_COLS = "id, name, type, image_mime IS NOT NULL AS has_image, kind";
    // У объектов нет типа и картинки (в старых базах столбцы items.type/image остаются, но не используются)
    private static final String ITEM_COLS = "id, name, collection_id, platform, format, status, price";

    /** Допустимые значения вида коллекции. */
    public static final String PHYSICAL = "Физическая", DIGITAL = "Цифровая";
    public static final List<String> KINDS = List.of(PHYSICAL, DIGITAL);

    /**
     * Есть ли у объектов коллекции этого вида платформа и формат.
     * У физической — нет; у коллекции без вида показываем все поля.
     */
    public static boolean hasDigitalFields(String kind) {
        return !PHYSICAL.equals(kind);
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
        // Столбцы, которые больше не используются: у объектов нет издателя, типа и картинки
        boolean dropped = false;
        for (String col : List.of("publisher", "type", "image", "image_mime")) dropped |= dropColumn("items", col);
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
                isItem ? null : rs.getString("type"),
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

    /** Сохраняет коллекцию: название, тип, вид и (если загружена) картинку; removeImage удаляет картинку. */
    public void saveCollection(long id, String name, String type, String kind, MultipartFile image, boolean removeImage) {
        requireName(name);
        if (!KINDS.contains(kind))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите вид коллекции: Физическая или Цифровая");
        jdbc.update("UPDATE collections SET name = ?, type = ?, kind = ? WHERE id = ?", name.trim(), trim(type), kind, id);

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
     * Сохраняет объект. Название, статус и цена есть у всех объектов;
     * платформа и формат — только в цифровой коллекции. В физической их не трогаем,
     * чтобы данные не терялись при смене вида коллекции.
     */
    public void saveItem(long id, String kind, String name, String platform, String format, String status, Integer price) {
        requireName(name);
        if (price != null && price < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Цена не может быть отрицательной");
        jdbc.update("UPDATE items SET name = ?, status = ?, price = ? WHERE id = ?", name.trim(), trim(status), price, id);
        if (hasDigitalFields(kind))
            jdbc.update("UPDATE items SET platform = ?, format = ? WHERE id = ?", trim(platform), trim(format), id);
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
