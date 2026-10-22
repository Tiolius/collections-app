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
 * Картинки объектов — файлы в папке DATA_DIR/images (рядом с базой): «<id объекта>.jpg|png|gif|webp».
 * В базе (item_images) остаются только отметка «картинка есть» и её тип; столбец image — пустой.
 * При запуске: картинки, ещё лежащие в базе (старые версии), один раз переносятся в файлы,
 * а файлы удалённых объектов стираются.
 */
@Component
public class ImageFiles implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImageFiles.class);
    private static final Map<String, String> EXT = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/gif", "gif", "image/webp", "webp");

    private final Path dir;
    private final JdbcTemplate jdbc;

    public ImageFiles(@Value("${DATA_DIR:/data}") String dataDir, JdbcTemplate jdbc) {
        this.dir = Path.of(dataDir, "images");
        this.jdbc = jdbc;
    }

    private Path file(long itemId, String mime) {
        return dir.resolve(itemId + "." + EXT.getOrDefault(mime, "bin"));
    }

    /** Записывает картинку объекта (старый файл с другим расширением удаляется). */
    public void write(long itemId, byte[] bytes, String mime) {
        try {
            Files.createDirectories(dir);
            delete(itemId);
            // сначала во временный файл, потом переименование: недописанный файл не останется под нужным именем
            Path tmp = Files.createTempFile(dir, itemId + "-", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file(itemId, mime), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Содержимое картинки или null, если файла нет. */
    public byte[] read(long itemId, String mime) {
        try {
            Path f = file(itemId, mime);
            return Files.exists(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(long itemId) {
        try {
            for (String ext : EXT.values()) Files.deleteIfExists(dir.resolve(itemId + "." + ext));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Удаляет файлы, у которых в базе больше нет объекта с картинкой (после удаления коллекции, пользователя).
     * Временные файлы (идущая запись) трогаются только при запуске.
     */
    public void removeOrphans() {
        removeOrphans(false);
    }

    private void removeOrphans(boolean startup) {
        if (!Files.isDirectory(dir)) return;
        Set<String> keep = new HashSet<>();
        jdbc.query("SELECT item_id, image_mime FROM item_images",
                rs -> { keep.add(file(rs.getLong(1), rs.getString(2)).getFileName().toString()); });
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
        List<Long> ids = jdbc.queryForList("SELECT item_id FROM item_images WHERE length(image) > 0", Long.class);
        for (long id : ids) {
            Map<String, Object> row = jdbc.queryForMap("SELECT image, image_mime FROM item_images WHERE item_id = ?", id);
            write(id, (byte[]) row.get("image"), (String) row.get("image_mime"));
            jdbc.update("UPDATE item_images SET image = X'' WHERE item_id = ?", id);
        }
        if (!ids.isEmpty()) {
            log.info("Картинки объектов перенесены из базы в файлы: {}", ids.size());
            jdbc.execute("VACUUM"); // вернуть место, которое занимали картинки в файле базы
        }
        removeOrphans(true);
    }
}
