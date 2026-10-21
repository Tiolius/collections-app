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

    private final JdbcTemplate jdbc;

    public Repo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static RowMapper<Entry> mapper(boolean withCollection, boolean withCount) {
        return (rs, i) -> new Entry(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("type"),
                rs.getBoolean("has_image"),
                withCollection ? rs.getLong("collection_id") : null,
                withCount ? rs.getInt("cnt") : 0);
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
        return jdbc.query("SELECT " + COLS + ", collection_id FROM items WHERE collection_id = ? "
                + "ORDER BY name COLLATE NOCASE", mapper(true, false), collectionId);
    }

    public Entry item(long id) {
        return one(jdbc.query("SELECT " + COLS + ", collection_id FROM items WHERE id = ?", mapper(true, false), id));
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

    public void delete(String table, long id) {
        jdbc.update("DELETE FROM " + table + " WHERE id = ?", id); // объекты коллекции удаляются каскадно
    }

    // ---------- вспомогательное ----------

    private static Entry one(List<Entry> list) {
        if (list.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return list.get(0);
    }
}
