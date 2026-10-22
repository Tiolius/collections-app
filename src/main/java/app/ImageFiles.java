package app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
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
 * Все картинки — файлы в папке DATA_DIR/images (рядом с базой), по подпапкам «<id пользователя>/<id коллекции>/»;
 * в базе только отметка «картинка есть» и её тип:
 * - объекты: первая — «<id объекта>.jpg|png|gif|webp» (отметка в item_images, столбец image пустой),
 *   следующие (до MAX_IMAGES) — «<id>-2.jpg», «<id>-3.jpg» и т. д. (отметки в item_more_images, pos — номер с нуля);
 * - коллекции: «collection.jpg» в папке коллекции (collections.image_mime; столбец image пустой).
 * Папки названы по номерам, а не по названиям: названия меняются и могут содержать недопустимые символы.
 * При запуске: картинки, ещё лежащие в базе или в общей папке (старые версии), один раз переносятся на свои места,
 * а файлы удалённых объектов и коллекций и пустые папки стираются.
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

    private static String ext(String mime) {
        return "." + EXT.getOrDefault(mime, "bin");
    }

    /** Папка коллекции: images/<id пользователя>/<id коллекции>. */
    private Path folder(long userId, long collectionId) {
        return dir.resolve(String.valueOf(userId)).resolve(String.valueOf(collectionId));
    }

    /** Папка коллекции по её id или null, если коллекции нет. */
    private Path collectionFolder(long collectionId) {
        List<Long> u = jdbc.queryForList("SELECT user_id FROM collections WHERE id = ?", Long.class, collectionId);
        return u.isEmpty() ? null : folder(u.get(0), collectionId);
    }

    /** Папка коллекции объекта или null, если объекта нет. */
    private Path itemFolder(long itemId) {
        List<Path> p = jdbc.query("SELECT c.user_id, c.id FROM items i JOIN collections c ON c.id = i.collection_id WHERE i.id = ?",
                (rs, n) -> folder(rs.getLong(1), rs.getLong(2)), itemId);
        return p.isEmpty() ? null : p.get(0);
    }

    // ---------- объекты ----------

    /** Записывает картинку объекта номер pos (старый файл с другим расширением удаляется). */
    public void write(long itemId, int pos, byte[] bytes, String mime) {
        write(required(itemFolder(itemId)), itemBase(itemId, pos), bytes, mime);
    }

    /** Содержимое картинки объекта или null, если файла нет. */
    public byte[] read(long itemId, int pos, String mime) {
        Path f = itemFolder(itemId);
        return f == null ? null : read(f.resolve(itemBase(itemId, pos) + ext(mime)));
    }

    /** Все картинки объекта. Вызывать до удаления объекта из базы (по нему находится папка). */
    public void deleteAll(long itemId) {
        Path f = itemFolder(itemId);
        if (f != null) for (int pos = 0; pos < MAX_IMAGES; pos++) delete(f, itemBase(itemId, pos));
    }

    // ---------- коллекции ----------

    public void writeCollection(long collectionId, byte[] bytes, String mime) {
        write(required(collectionFolder(collectionId)), "collection", bytes, mime);
    }

    public byte[] readCollection(long collectionId, String mime) {
        Path f = collectionFolder(collectionId);
        return f == null ? null : read(f.resolve("collection" + ext(mime)));
    }

    public void deleteCollection(long collectionId) {
        Path f = collectionFolder(collectionId);
        if (f != null) delete(f, "collection");
    }

    // ---------- файлы ----------

    private static Path required(Path folder) {
        if (folder == null) throw new IllegalStateException("Нет записи для картинки");
        return folder;
    }

    private static void write(Path folder, String base, byte[] bytes, String mime) {
        try {
            Files.createDirectories(folder);
            delete(folder, base);
            // сначала во временный файл, потом переименование: недописанный файл не останется под нужным именем
            Path tmp = Files.createTempFile(folder, base + "-", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, folder.resolve(base + ext(mime)), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] read(Path f) {
        try {
            return Files.exists(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path folder, String base) {
        try {
            for (String ext : EXT.values()) Files.deleteIfExists(folder.resolve(base + "." + ext));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Где должна лежать каждая картинка из базы: путь от папки images → прежнее имя в общей папке (до подпапок). */
    private Map<Path, String> expected() {
        Map<Path, String> res = new HashMap<>();
        jdbc.query("SELECT c.user_id, c.id, p.item_id, p.pos, p.image_mime FROM "
                        + "(SELECT item_id, 0 AS pos, image_mime FROM item_images "
                        + "UNION ALL SELECT item_id, pos, image_mime FROM item_more_images) p "
                        + "JOIN items i ON i.id = p.item_id JOIN collections c ON c.id = i.collection_id",
                rs -> {
                    String name = itemBase(rs.getLong(3), rs.getInt(4)) + ext(rs.getString(5));
                    res.put(dir.relativize(folder(rs.getLong(1), rs.getLong(2)).resolve(name)), name);
                });
        jdbc.query("SELECT user_id, id, image_mime FROM collections WHERE image_mime IS NOT NULL",
                rs -> {
                    res.put(dir.relativize(folder(rs.getLong(1), rs.getLong(2)).resolve("collection" + ext(rs.getString(3)))),
                            "collection-" + rs.getLong(2) + ext(rs.getString(3)));
                });
        return res;
    }

    /**
     * Удаляет файлы, для которых в базе больше нет картинки (после удаления коллекции и т. п.), и пустые папки.
     * Временные файлы (идущая запись) трогаются только при запуске.
     */
    public void removeOrphans() {
        removeOrphans(false, Set.of());
    }

    /** stay — файлы, которые не трогать (старые, которые не удалось перенести). */
    private void removeOrphans(boolean startup, Set<Path> stay) {
        if (!Files.isDirectory(dir)) return;
        Map<Path, String> keep = expected();
        try (Stream<Path> all = Files.walk(dir)) {
            // сначала вложенное, потом папки — чтобы опустевшие папки тоже удалились
            for (Path f : all.sorted(Comparator.reverseOrder()).toList()) {
                if (f.equals(dir)) continue;
                if (Files.isDirectory(f)) {
                    try (Stream<Path> inside = Files.list(f)) {
                        if (inside.findAny().isEmpty()) Files.delete(f);
                    }
                } else if (!keep.containsKey(dir.relativize(f)) && !stay.contains(f) && (startup || !f.toString().endsWith(".tmp"))) {
                    Files.deleteIfExists(f);
                }
            }
        } catch (IOException e) {
            log.warn("Не удалось очистить папку картинок: {}", e.toString());
        }
    }

    /** При запуске: перенос картинок из базы и из общей папки на свои места (один раз) и очистка лишних файлов. */
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
        // файлы из общей папки images (до подпапок пользователь/коллекция) — в папки коллекций
        int moved = 0;
        Set<Path> failed = new HashSet<>();
        for (Map.Entry<Path, String> e : expected().entrySet()) {
            Path target = dir.resolve(e.getKey()), old = dir.resolve(e.getValue());
            if (Files.exists(target) || !Files.isRegularFile(old)) continue;
            try {
                Files.createDirectories(target.getParent());
                Files.move(old, target, StandardCopyOption.ATOMIC_MOVE);
                moved++;
            } catch (IOException ex) {
                log.warn("Не удалось перенести картинку {}: {}", old, ex.toString());
                failed.add(old); // останется в общей папке до следующего запуска
            }
        }
        if (moved > 0) log.info("Картинки разложены по папкам пользователей и коллекций: {}", moved);
        removeOrphans(true, failed);
    }
}
