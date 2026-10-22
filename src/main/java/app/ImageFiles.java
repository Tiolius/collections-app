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
import java.util.TreeMap;
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
 * При запуске: картинки из прежних хранилищ (в базе, по папкам пользователь/коллекция, в общей папке) один раз
 * переносятся сюда, затем лишние файлы удаляются.
 */
@Component
public class ImageFiles implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImageFiles.class);
    private static final Map<String, String> EXT = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/gif", "gif", "image/webp", "webp");

    /** Сколько картинок может быть у объекта. */
    public static final int MAX_IMAGES = 5;

    /** Картинка: хеш содержимого и тип. */
    public record Pic(String hash, String mime) {}

    private final Path dir;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ImageFiles(@Value("${DATA_DIR:/data}") String dataDir, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.dir = Path.of(dataDir, "images");
        this.jdbc = jdbc;
        this.tx = tx;
    }

    // ---------- чтение ----------

    /** Картинки объекта по порядку. */
    public List<Pic> item(long itemId) {
        return jdbc.query("SELECT p.hash, p.mime FROM item_pictures ip JOIN pictures p ON p.hash = ip.hash "
                + "WHERE ip.item_id = ? ORDER BY ip.pos", (rs, n) -> new Pic(rs.getString(1), rs.getString(2)), itemId);
    }

    /** Картинка коллекции или null. */
    public Pic collection(long collectionId) {
        List<Pic> p = jdbc.query("SELECT p.hash, p.mime FROM collection_pictures cp JOIN pictures p ON p.hash = cp.hash "
                + "WHERE cp.collection_id = ?", (rs, n) -> new Pic(rs.getString(1), rs.getString(2)), collectionId);
        return p.isEmpty() ? null : p.get(0);
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
     * Не картинки (не PNG, JPEG, GIF, WebP) пропускаются.
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

    /** Убирает картинки всех объектов коллекции (обложка остаётся). Возвращает, сколько картинок убрано. */
    public synchronized int clearCollectionItems(long collectionId) {
        String where = " WHERE item_id IN (SELECT id FROM items WHERE collection_id = ?)";
        List<String> old = hashes("SELECT hash FROM item_pictures" + where, collectionId);
        jdbc.update("DELETE FROM item_pictures" + where, collectionId);
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

    /** Удаляет файлы (и строки pictures) тех хешей, на которые больше никто не ссылается. */
    public synchronized void release(Collection<String> hashes) {
        for (String h : new LinkedHashSet<>(hashes)) {
            Integer refs = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM item_pictures WHERE hash = ?) "
                    + "+ (SELECT COUNT(*) FROM collection_pictures WHERE hash = ?)", Integer.class, h, h);
            if (refs != null && refs > 0) continue;
            List<String> mime = jdbc.queryForList("SELECT mime FROM pictures WHERE hash = ?", String.class, h);
            jdbc.update("DELETE FROM pictures WHERE hash = ?", h);
            if (!mime.isEmpty()) {
                try {
                    Files.deleteIfExists(file(h, mime.get(0)));
                } catch (IOException e) {
                    log.warn("Не удалось удалить картинку {}: {}", h, e.toString());
                }
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
        migrate();
        // строки pictures без ссылок и файлы без строки pictures (и оставшиеся от прежних хранилищ, и .tmp) — удаляются
        release(jdbc.queryForList("SELECT hash FROM pictures WHERE hash NOT IN (SELECT hash FROM item_pictures) "
                + "AND hash NOT IN (SELECT hash FROM collection_pictures)", String.class));
        Set<Path> keep = new HashSet<>();
        jdbc.query("SELECT hash, mime FROM pictures", rs -> { keep.add(file(rs.getString(1), rs.getString(2))); });
        if (!Files.isDirectory(dir)) return;
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
    }

    /**
     * Перенос из прежних хранилищ (один раз): таблицы item_images / item_more_images и столбцы collections.image,
     * collections.image_mime. Картинка берётся из базы (старые версии) или из файла — по папкам «пользователь/коллекция»
     * или в общей папке images. Всё в одной транзакции: при ошибке чтения ничего не меняется и запуск прерывается,
     * чтобы прежние картинки не потерялись; отсутствующий файл пропускается (картинки и так не было).
     */
    private void migrate() {
        boolean items = tableExists("item_images"), more = tableExists("item_more_images");
        boolean colls = columnExists("collections", "image_mime");
        if (!items && !more && !colls) return;
        int[] moved = {0, 0};
        tx.executeWithoutResult(s -> {
            if (items || more) {
                // item_id → (pos → тип); первая картинка — в item_images (pos 0), следующие — в item_more_images (pos 1..4)
                Map<Long, Map<Integer, String>> all = new TreeMap<>();
                if (items) jdbc.query("SELECT item_id, image_mime FROM item_images",
                        rs -> { all.computeIfAbsent(rs.getLong(1), k -> new TreeMap<>()).put(0, rs.getString(2)); });
                if (more) jdbc.query("SELECT item_id, pos, image_mime FROM item_more_images",
                        rs -> { all.computeIfAbsent(rs.getLong(1), k -> new TreeMap<>()).put(rs.getInt(2), rs.getString(3)); });
                for (Map.Entry<Long, Map<Integer, String>> e : all.entrySet()) {
                    long id = e.getKey();
                    List<Long> owner = jdbc.query("SELECT c.user_id, c.id FROM items i JOIN collections c ON c.id = i.collection_id "
                            + "WHERE i.id = ?", (rs, n) -> List.of(rs.getLong(1), rs.getLong(2)), id).stream().findFirst().orElse(null);
                    if (owner == null) continue;
                    List<String> hs = new ArrayList<>();
                    for (Map.Entry<Integer, String> p : e.getValue().entrySet()) {
                        int pos = p.getKey();
                        byte[] b = pos == 0 && items ? blob("SELECT image FROM item_images WHERE item_id = ?", id) : null;
                        String base = pos == 0 ? String.valueOf(id) : id + "-" + (pos + 1);
                        if (b == null) b = oldFile(p.getValue(), owner, base, base);
                        String h = b == null ? null : store(b);
                        if (h != null && hs.size() < MAX_IMAGES) hs.add(h);
                    }
                    jdbc.update("DELETE FROM item_pictures WHERE item_id = ?", id);
                    for (int pos = 0; pos < hs.size(); pos++)
                        jdbc.update("INSERT INTO item_pictures (item_id, pos, hash) VALUES (?, ?, ?)", id, pos, hs.get(pos));
                    moved[0] += hs.size();
                }
                jdbc.execute("DROP TABLE IF EXISTS item_more_images");
                jdbc.execute("DROP TABLE IF EXISTS item_images");
            }
            if (colls) {
                boolean blobs = columnExists("collections", "image");
                List<Object[]> rows = jdbc.query("SELECT id, user_id, image_mime FROM collections WHERE image_mime IS NOT NULL",
                        (rs, n) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getString(3)});
                for (Object[] r : rows) {
                    long id = (Long) r[0];
                    byte[] b = blobs ? blob("SELECT image FROM collections WHERE id = ?", id) : null;
                    if (b == null) b = oldFile((String) r[2], List.of((Long) r[1], id), "collection", "collection-" + id);
                    String h = b == null ? null : store(b);
                    if (h == null) continue;
                    jdbc.update("INSERT OR REPLACE INTO collection_pictures (collection_id, hash) VALUES (?, ?)", id, h);
                    moved[1]++;
                }
                if (blobs) jdbc.execute("ALTER TABLE collections DROP COLUMN image");
                jdbc.execute("ALTER TABLE collections DROP COLUMN image_mime");
            }
        });
        log.info("Картинки перенесены в хранилище по содержимому: объектов {}, коллекций {}", moved[0], moved[1]);
        jdbc.execute("VACUUM"); // вернуть место, которое занимали прежние таблицы и картинки в файле базы
    }

    /** Непустой BLOB или null. */
    private byte[] blob(String sql, long id) {
        List<byte[]> b = jdbc.query(sql, (rs, n) -> rs.getBytes(1), id);
        return b.isEmpty() || b.get(0) == null || b.get(0).length == 0 ? null : b.get(0);
    }

    /** Файл прежнего хранилища: images/<пользователь>/<коллекция>/<base>.ext, иначе images/<flat>.ext; null — нет файла. */
    private byte[] oldFile(String mime, List<Long> owner, String base, String flat) {
        String ext = "." + EXT.getOrDefault(mime, "bin");
        Path nested = dir.resolve(String.valueOf(owner.get(0))).resolve(String.valueOf(owner.get(1))).resolve(base + ext);
        for (Path f : List.of(nested, dir.resolve(flat + ext))) {
            try {
                if (Files.isRegularFile(f)) return Files.readAllBytes(f);
            } catch (IOException e) {
                throw new UncheckedIOException("Не удалось прочитать картинку " + f, e);
            }
        }
        return null;
    }

    private boolean tableExists(String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", Integer.class, name) > 0;
    }

    private boolean columnExists(String table, String column) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?", Integer.class, table, column) > 0;
    }
}
