package app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Хранилище картинок по содержимому: одинаковая картинка лежит на диске один раз, сколько бы объектов,
 * коллекций и пользователей на неё ни ссылалось.
 * - Файл: DATA_DIR/images/ab/cd/<sha256>.<ext> (ab, cd — первые символы хеша: не больше 256 папок на уровне).
 * - pictures (hash, mime, size) — справочник файлов; item_pictures (item_id, pos) и collection_pictures (collection_id) —
 *   ссылки на него. Файл и строка pictures удаляются, когда на хеш не осталось ссылок (release).
 * Изменения идут под одной блокировкой: файл, на который только что сослались, не удалится параллельной очисткой.
 * При запуске лишние файлы (без строки pictures) и строки pictures без ссылок удаляются.
 */
@Component
public class ImageFiles implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImageFiles.class);
    private static final Map<String, String> EXT = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/gif", "gif");

    /** Сколько картинок может быть у объекта. */
    public static final int MAX_IMAGES = 5;

    /** Картинка: хеш содержимого и тип. */
    public record Pic(String hash, String mime) {}

    private final Path dir;
    /** Уменьшенные копии (thumb): DATA_DIR/thumbs/ab/cd/<sha256>.<jpg|png> — создаются при первом запросе, в копию не входят. */
    private final Path thumbs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ImageFiles(@Value("${DATA_DIR:/data}") String dataDir, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.dir = Path.of(dataDir, "images");
        this.thumbs = Path.of(dataDir, "thumbs");
        this.jdbc = jdbc;
        this.tx = tx;
    }

    // ---------- чтение ----------

    /** Картинки объекта по порядку. */
    public List<Pic> item(long itemId) {
        return jdbc.query("SELECT p.hash, p.mime FROM item_pictures ip JOIN pictures p ON p.hash = ip.hash "
                + "WHERE ip.item_id = ? ORDER BY ip.pos", (rs, n) -> new Pic(rs.getString(1), rs.getString(2)), itemId);
    }

    /** Папка картинок (DATA_DIR/images). */
    public Path dir() {
        return dir;
    }

    /** Файлы всех картинок из справочника pictures (для резервной копии; вызывать под блокировкой). */
    public List<Path> allFiles() {
        return jdbc.query("SELECT hash, mime FROM pictures", (rs, n) -> file(rs.getString(1), rs.getString(2)));
    }

    /** Наибольшая сторона уменьшенной копии: с запасом для экранов с двойной плотностью (обложка на главной — 76px). */
    public static final int THUMB = 200;

    /**
     * Уменьшенная копия картинки (не больше THUMB по большей стороне): из папки thumbs, а если её нет — создаётся.
     * null — уменьшать нечего (картинка и так маленькая) или ImageIO не смог её прочитать — тогда отдаётся сама картинка.
     * Копия с прозрачностью — PNG, без — JPEG.
     */
    public Thumb thumb(Pic pic) {
        try {
            for (String ext : List.of("jpg", "png")) {
                Path t = thumbFile(pic.hash(), ext);
                if (Files.exists(t)) return new Thumb(Files.readAllBytes(t), ext.equals("png") ? "image/png" : "image/jpeg");
            }
            byte[] src = read(pic);
            if (src == null) return null;
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(src));
            if (img == null || Math.max(img.getWidth(), img.getHeight()) <= THUMB) return null;
            boolean alpha = img.getColorModel().hasAlpha();
            java.awt.image.BufferedImage small = scale(img, THUMB, alpha);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            String ext = alpha ? "png" : "jpg";
            if (!javax.imageio.ImageIO.write(small, alpha ? "png" : "jpeg", out)) return null;
            Path t = thumbFile(pic.hash(), ext);
            Files.createDirectories(t.getParent());
            Path tmp = Files.createTempFile(t.getParent(), pic.hash() + "-", ".tmp");
            Files.write(tmp, out.toByteArray());
            Files.move(tmp, t, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new Thumb(out.toByteArray(), alpha ? "image/png" : "image/jpeg");
        } catch (Exception e) { // не картинка для ImageIO (например, CMYK JPEG) — отдаётся сама картинка
            log.debug("Не удалось уменьшить картинку {}: {}", pic.hash(), e.toString());
            return null;
        }
    }

    /** Уменьшенная копия: содержимое и тип. */
    public record Thumb(byte[] bytes, String mime) {}

    /** Уменьшение по шагам вдвое (плавнее, чем сразу в несколько раз), последний шаг — до max по большей стороне. */
    private static java.awt.image.BufferedImage scale(java.awt.image.BufferedImage img, int max, boolean alpha) {
        int type = alpha ? java.awt.image.BufferedImage.TYPE_INT_ARGB : java.awt.image.BufferedImage.TYPE_INT_RGB;
        double k = (double) max / Math.max(img.getWidth(), img.getHeight());
        int tw = Math.max(1, (int) Math.round(img.getWidth() * k)), th = Math.max(1, (int) Math.round(img.getHeight() * k));
        java.awt.image.BufferedImage cur = img;
        int w = img.getWidth(), h = img.getHeight();
        do {
            w = Math.max(tw, w / 2);
            h = Math.max(th, h / 2);
            java.awt.image.BufferedImage next = new java.awt.image.BufferedImage(w, h, type);
            java.awt.Graphics2D g = next.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            if (!alpha) { g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, w, h); }
            g.drawImage(cur, 0, 0, w, h, null);
            g.dispose();
            cur = next;
        } while (w != tw || h != th);
        return cur;
    }

    private Path thumbFile(String hash, String ext) {
        return thumbs.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve(hash + "." + ext);
    }

    /** Содержимое картинки или null, если файла нет. */
    public byte[] read(Pic pic) {
        try {
            Path f = file(pic.hash(), pic.mime());
            return Files.exists(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- изменение ----------

    /**
     * Заменяет все картинки объекта (до MAX_IMAGES, по порядку; пустой список — удаляет все).
     * Не картинки (не PNG, JPEG, GIF) пропускаются.
     */
    public synchronized void setItem(long itemId, List<byte[]> images) {
        Set<String> old = new HashSet<>(hashes("SELECT hash FROM item_pictures WHERE item_id = ?", itemId));
        List<String> added = new ArrayList<>();
        for (byte[] b : images) {
            if (added.size() == MAX_IMAGES) break;
            String h = store(b);
            if (h != null) added.add(h);
        }
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM item_pictures WHERE item_id = ?", itemId);
            for (int pos = 0; pos < added.size(); pos++)
                jdbc.update("INSERT INTO item_pictures (item_id, pos, hash) VALUES (?, ?, ?)", itemId, pos, added.get(pos));
        });
        old.removeAll(added);
        release(old);
    }

    /** Ставит картинку коллекции (null — убирает). Не картинка — ничего не меняется. */
    public synchronized void setCollection(long collectionId, byte[] image) {
        List<String> old = hashes("SELECT hash FROM collection_pictures WHERE collection_id = ?", collectionId);
        if (image == null) {
            jdbc.update("DELETE FROM collection_pictures WHERE collection_id = ?", collectionId);
        } else {
            String h = store(image);
            if (h == null) return;
            jdbc.update("INSERT INTO collection_pictures (collection_id, hash) VALUES (?, ?) "
                    + "ON CONFLICT (collection_id) DO UPDATE SET hash = excluded.hash", collectionId, h);
            old = old.stream().filter(o -> !o.equals(h)).toList();
        }
        release(old);
    }

    /** Сколько объектов обрабатывать за раз при очистке коллекции (одна транзакция на порцию). */
    private static final int CLEAR_BATCH = 50;

    /**
     * Убирает картинки всех объектов коллекции (обложка остаётся) порциями по CLEAR_BATCH объектов:
     * каждая порция — одна транзакция и под блокировкой, между порциями другие пользователи могут сохранять.
     * progress получает, сколько картинок уже убрано. Возвращает, сколько убрано.
     */
    public int clearCollectionItems(long collectionId, java.util.function.IntConsumer progress) {
        List<Long> items = jdbc.queryForList("SELECT DISTINCT item_id FROM item_pictures "
                + "WHERE item_id IN (SELECT id FROM items WHERE collection_id = ?) ORDER BY item_id", Long.class, collectionId);
        int done = 0;
        for (int i = 0; i < items.size(); i += CLEAR_BATCH) {
            done += clearItems(items.subList(i, Math.min(items.size(), i + CLEAR_BATCH)));
            progress.accept(done);
        }
        return done;
    }

    /** Убирает все картинки объектов из списка; возвращает, сколько картинок убрано. */
    private synchronized int clearItems(List<Long> ids) {
        String in = "(" + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")";
        Object[] args = ids.toArray();
        List<String> old = jdbc.queryForList("SELECT hash FROM item_pictures WHERE item_id IN " + in, String.class, args);
        tx.executeWithoutResult(s -> jdbc.update("DELETE FROM item_pictures WHERE item_id IN " + in, args));
        release(old);
        return old.size();
    }

    /** Хеши картинок объекта — запомнить до его удаления, после удаления передать в release. */
    public List<String> itemHashes(long itemId) {
        return hashes("SELECT hash FROM item_pictures WHERE item_id = ?", itemId);
    }

    /** Хеши обложки коллекции и картинок всех её объектов. */
    public List<String> collectionHashes(long collectionId) {
        List<String> res = new ArrayList<>(hashes("SELECT hash FROM collection_pictures WHERE collection_id = ?", collectionId));
        res.addAll(hashes("SELECT hash FROM item_pictures WHERE item_id IN (SELECT id FROM items WHERE collection_id = ?)",
                collectionId));
        return res;
    }

    /**
     * Удаляет файлы (и строки pictures) тех хешей, на которые больше никто не ссылается.
     * Строки — одной транзакцией (по одной SQLite записывала бы на диск каждую — тысячи картинок шли бы минуты),
     * файлы — после неё.
     */
    public synchronized void release(Collection<String> hashes) {
        if (hashes.isEmpty()) return;
        List<Path> gone = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            for (String h : new LinkedHashSet<>(hashes)) {
                Integer refs = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM item_pictures WHERE hash = ?) "
                        + "+ (SELECT COUNT(*) FROM collection_pictures WHERE hash = ?)", Integer.class, h, h);
                if (refs != null && refs > 0) continue;
                List<String> mime = jdbc.queryForList("SELECT mime FROM pictures WHERE hash = ?", String.class, h);
                jdbc.update("DELETE FROM pictures WHERE hash = ?", h);
                if (!mime.isEmpty()) gone.add(file(h, mime.get(0)));
            }
        });
        for (Path f : gone) {
            try {
                Files.deleteIfExists(f);
                String h = f.getFileName().toString().replaceFirst("\\..*", "");
                for (String ext : List.of("jpg", "png")) Files.deleteIfExists(thumbFile(h, ext)); // и уменьшенные копии
            } catch (IOException e) {
                log.warn("Не удалось удалить картинку {}: {}", f.getFileName(), e.toString());
            }
        }
    }

    // ---------- файлы ----------

    private List<String> hashes(String sql, long id) {
        return jdbc.queryForList(sql, String.class, id);
    }

    private Path file(String hash, String mime) {
        return dir.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve(hash + "." + EXT.getOrDefault(mime, "bin"));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Кладёт картинку в хранилище (если такой ещё нет) и записывает её в pictures; возвращает хеш
     * или null, если это не картинка. Вызывать под блокировкой.
     */
    private String store(byte[] bytes) {
        String mime = bytes == null ? null : Repo.imageType(bytes);
        if (mime == null) return null;
        String h = sha256(bytes);
        Path f = file(h, mime);
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                // сначала во временный файл, потом переименование: недописанный файл не останется под нужным именем
                Path tmp = Files.createTempFile(f.getParent(), h + "-", ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        jdbc.update("INSERT OR IGNORE INTO pictures (hash, mime, size) VALUES (?, ?, ?)", h, mime, bytes.length);
        return h;
    }

    // ---------- запуск ----------

    @Override
    public synchronized void run(ApplicationArguments args) {
        // строки pictures без ссылок и файлы без строки pictures (и недописанные .tmp) — удаляются
        release(jdbc.queryForList("SELECT hash FROM pictures WHERE hash NOT IN (SELECT hash FROM item_pictures) "
                + "AND hash NOT IN (SELECT hash FROM collection_pictures)", String.class));
        Set<Path> keep = new HashSet<>();
        jdbc.query("SELECT hash, mime FROM pictures", rs -> { keep.add(file(rs.getString(1), rs.getString(2))); });
        if (!Files.isDirectory(dir)) {
            cleanThumbs();
            return;
        }
        int removed = 0;
        try (Stream<Path> all = Files.walk(dir)) {
            // сначала вложенное, потом папки — чтобы опустевшие папки тоже удалились
            for (Path f : all.sorted(Comparator.reverseOrder()).toList()) {
                if (f.equals(dir)) continue;
                if (Files.isDirectory(f)) {
                    try (Stream<Path> inside = Files.list(f)) {
                        if (inside.findAny().isEmpty()) Files.delete(f);
                    }
                } else if (!keep.contains(f)) {
                    Files.delete(f);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("Не удалось очистить папку картинок: {}", e.toString());
        }
        if (removed > 0) log.info("Удалены лишние файлы картинок: {}", removed);
        cleanThumbs();
    }

    /** Уменьшенные копии картинок, которых больше нет в pictures (и недописанные .tmp), удаляются. */
    private void cleanThumbs() {
        if (!Files.isDirectory(thumbs)) return;
        Set<String> keep = new HashSet<>(jdbc.queryForList("SELECT hash FROM pictures", String.class));
        try (Stream<Path> all = Files.walk(thumbs)) {
            for (Path f : all.sorted(Comparator.reverseOrder()).toList()) {
                if (f.equals(thumbs)) continue;
                if (Files.isDirectory(f)) {
                    try (Stream<Path> inside = Files.list(f)) {
                        if (inside.findAny().isEmpty()) Files.delete(f);
                    }
                } else if (!keep.contains(f.getFileName().toString().replaceFirst("\\..*", ""))) {
                    Files.delete(f);
                }
            }
        } catch (IOException e) {
            log.warn("Не удалось очистить папку уменьшенных копий: {}", e.toString());
        }
    }
}
