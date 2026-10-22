package app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Все картинки — файлы в папке DATA_DIR/images (рядом с базой), в базе только отметка «картинка есть» и её тип:
 * - объекты: первая — «<id объекта>.jpg|png|gif|webp» (отметка в item_images, столбец image пустой),
 *   следующие (до MAX_IMAGES) — «<id>-2.jpg», «<id>-3.jpg» и т. д. (отметки в item_more_images, pos — номер с нуля);
 * - коллекции: «collection-<id>.jpg» (collections.image_mime; столбец image пустой).
 * При запуске: картинки, ещё лежащие в базе (старые версии), один раз переносятся в файлы,
 * а файлы удалённых объектов и коллекций стираются.
 */
@Component
public class ImageFiles implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImageFiles.class);
    private static final Map<String, String> EXT = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/gif", "gif", "image/webp", "webp");

    /** Сколько картинок может быть у объекта. */
    public static final int MAX_IMAGES = 5;

    private final Path dir;
    private final JdbcTemplate jdbc;

    public ImageFiles(@Value("${DATA_DIR:/data}") String dataDir, JdbcTemplate jdbc) {
        this.dir = Path.of(dataDir, "images");
        this.jdbc = jdbc;
    }

    // ---------- имена файлов ----------

    private static String itemBase(long itemId, int pos) {
        return pos == 0 ? String.valueOf(itemId) : itemId + "-" + (pos + 1);
    }

    private static String collectionBase(long collectionId) {
        return "collection-" + collectionId;
    }

    private Path file(String base, String mime) {
        return dir.resolve(base + "." + EXT.getOrDefault(mime, "bin"));
    }

    // ---------- объекты ----------

    /** Записывает картинку объекта номер pos (старый файл с другим расширением удаляется). */
    public void write(long itemId, int pos, byte[] bytes, String mime) {
        write(itemBase(itemId, pos), bytes, mime);
    }

    /** Содержимое картинки объекта или null, если файла нет. */
    public byte[] read(long itemId, int pos, String mime) {
        return read(itemBase(itemId, pos), mime);
    }

    public void delete(long itemId, int pos) {
        delete(itemBase(itemId, pos));
    }

    /** Все картинки объекта. */
    public void deleteAll(long itemId) {
        for (int pos = 0; pos < MAX_IMAGES; pos++) delete(itemId, pos);
    }

    // ---------- коллекции ----------

    public void writeCollection(long collectionId, byte[] bytes, String mime) {
        write(collectionBase(collectionId), bytes, mime);
    }

    public byte[] readCollection(long collectionId, String mime) {
        return read(collectionBase(collectionId), mime);
    }

    public void deleteCollection(long collectionId) {
        delete(collectionBase(collectionId));
    }

    // ---------- файлы ----------

    private void write(String base, byte[] bytes, String mime) {
        try {
            Files.createDirectories(dir);
            delete(base);
            // сначала во временный файл, потом переименование: недописанный файл не останется под нужным именем
            Path tmp = Files.createTempFile(dir, base + "-", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file(base, mime), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] read(String base, String mime) {
        try {
            Path f = file(base, mime);
            return Files.exists(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void delete(String base) {
        try {
            for (String ext : EXT.values()) Files.deleteIfExists(dir.resolve(base + "." + ext));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Удаляет файлы, для которых в базе больше нет картинки (после удаления коллекции и т. п.).
     * Временные файлы (идущая запись) трогаются только при запуске.
     */
    public void removeOrphans() {
        removeOrphans(false);
    }

    private void removeOrphans(boolean startup) {
        if (!Files.isDirectory(dir)) return;
        Set<String> keep = new HashSet<>();
        jdbc.query("SELECT item_id, 0, image_mime FROM item_images UNION ALL SELECT item_id, pos, image_mime FROM item_more_images",
                rs -> { keep.add(file(itemBase(rs.getLong(1), rs.getInt(2)), rs.getString(3)).getFileName().toString()); });
        jdbc.query("SELECT id, image_mime FROM collections WHERE image_mime IS NOT NULL",
                rs -> { keep.add(file(collectionBase(rs.getLong(1)), rs.getString(2)).getFileName().toString()); });
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator)
                if (!keep.contains(f.getFileName().toString()) && (startup || !f.toString().endsWith(".tmp")))
                    Files.deleteIfExists(f);
        } catch (IOException e) {
            log.warn("Не удалось очистить папку картинок: {}", e.toString());
        }
    }

    /** При запуске: перенос картинок из базы в файлы (один раз, по одной) и очистка лишних файлов. */
    @Override
    public void run(ApplicationArguments args) {
        List<Long> items = jdbc.queryForList("SELECT item_id FROM item_images WHERE length(image) > 0", Long.class);
        for (long id : items) {
            Map<String, Object> row = jdbc.queryForMap("SELECT image, image_mime FROM item_images WHERE item_id = ?", id);
            write(id, 0, (byte[]) row.get("image"), (String) row.get("image_mime"));
            jdbc.update("UPDATE item_images SET image = X'' WHERE item_id = ?", id);
        }
        List<Long> colls = jdbc.queryForList(
                "SELECT id FROM collections WHERE length(image) > 0 AND image_mime IS NOT NULL", Long.class);
        for (long id : colls) {
            Map<String, Object> row = jdbc.queryForMap("SELECT image, image_mime FROM collections WHERE id = ?", id);
            writeCollection(id, (byte[]) row.get("image"), (String) row.get("image_mime"));
            jdbc.update("UPDATE collections SET image = NULL WHERE id = ?", id);
        }
        if (!items.isEmpty() || !colls.isEmpty()) {
            log.info("Картинки перенесены из базы в файлы: объектов {}, коллекций {}", items.size(), colls.size());
            jdbc.execute("VACUUM"); // вернуть место, которое занимали картинки в файле базы
        }
        removeOrphans(true);
    }
}
