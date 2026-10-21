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

    private static final String COLS = "id, name, type, image_mime IS NOT NULL AS has_image";
    private static final String ITEM_COLS = COLS + ", collection_id, platform, format, publisher, status, price";

    /** Дополнительные столбцы items: добавляются в старые базы при старте. */
    private static final Map<String, String> ITEM_EXTRA = Map.of(
            "platform", "TEXT", "format", "TEXT", "publisher", "TEXT", "status", "TEXT", "price", "INTEGER");

    private final JdbcTemplate jdbc;

    public Repo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        migrate();
    }

    /** schema.sql создаёт только новые таблицы, поэтому недостающие столбцы добавляем здесь. */
    private void migrate() {
        List<String> existing = jdbc.queryForList("SELECT name FROM pragma_table_info('items')", String.class);
        ITEM_EXTRA.forEach((col, type) -> {
            if (!existing.contains(col)) jdbc.execute("ALTER TABLE items ADD COLUMN " + col + " " + type);
        });
    }

    private static RowMapper<Entry> mapper(boolean isItem, boolean withCount) {
        return (rs, i) -> new Entry(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("type"),
                rs.getBoolean("has_image"),
                isItem ? rs.getLong("collection_id") : null,
                withCount ? rs.getInt("cnt") : 0,
                isItem ? rs.getString("platform") : null,
                isItem ? rs.getString("format") : null,
                isItem ? rs.getString("publisher") : null,
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
        return jdbc.query("SELECT " + COLS + ", (SELECT COUNT(*) FROM items i WHERE i.collection_id = c.id) AS cnt "
                + "FROM collections c ORDER BY name COLLATE NOCASE", mapper(false, true));
    }

    public Entry collection(long id) {
        return one(jdbc.query("SELECT " + COLS + " FROM collections WHERE id = ?", mapper(false, false), id));
    }

    public List<Entry> items(long collectionId) {
        return jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE collection_id = ? "
                + "ORDER BY name COLLATE NOCASE", mapper(true, false), collectionId);
    }

    public Entry item(long id) {
        return one(jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE id = ?", mapper(true, false), id));
    }

    /** Возвращает {image, image_mime} или 404. */
    public Map<String, Object> image(String table, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT image, image_mime FROM " + table + " WHERE id = ? AND image IS NOT NULL", id);
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

    /** Сохраняет название, тип и (если загружена) картинку; removeImage удаляет картинку. */
    public void save(String table, long id, String name, String type, MultipartFile image, boolean removeImage) {
        if (name == null || name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Название обязательно");
        jdbc.update("UPDATE " + table + " SET name = ?, type = ? WHERE id = ?", name.trim(),
                type == null ? "" : type.trim(), id);

        if (image != null && !image.isEmpty()) {
            String mime = image.getContentType();
            if (mime == null || !mime.startsWith("image/"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл должен быть картинкой");
            try {
                jdbc.update("UPDATE " + table + " SET image = ?, image_mime = ? WHERE id = ?", image.getBytes(), mime, id);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не удалось прочитать файл");
            }
        } else if (removeImage) {
            jdbc.update("UPDATE " + table + " SET image = NULL, image_mime = NULL WHERE id = ?", id);
        }
    }

    /** Сохраняет поля, которые есть только у объектов. */
    public void saveItemDetails(long id, String platform, String format, String publisher,
                                String status, Integer price) {
        if (price != null && price < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Цена не может быть отрицательной");
        jdbc.update("UPDATE items SET platform = ?, format = ?, publisher = ?, status = ?, price = ? WHERE id = ?",
                trim(platform), trim(format), trim(publisher), trim(status), price, id);
    }

    public void delete(String table, long id) {
        jdbc.update("DELETE FROM " + table + " WHERE id = ?", id); // объекты коллекции удаляются каскадно
    }

    // ---------- вспомогательное ----------

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static Entry one(List<Entry> list) {
        if (list.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return list.get(0);
    }
}
