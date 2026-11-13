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
 * Content-addressed picture storage: an identical picture is stored on disk once, however many items,
 * collections and users refer to it.
 * - File: DATA_DIR/images/ab/cd/<sha256>.<ext> (ab, cd — the first characters of the hash: at most 256 folders per level).
 * - pictures (hash, mime, size) — the file register; item_pictures (item_id, pos) and collection_pictures (collection_id) —
 *   references to it. The file and the pictures row are deleted when no references to the hash are left (release).
 * Changes go under one lock: a file that was just referenced is not deleted by a concurrent cleanup.
 * At start, extra files (without a pictures row) and pictures rows without references are deleted.
 */
@Component
public class ImageFiles implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImageFiles.class);
    private static final Map<String, String> EXT = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/gif", "gif");

    /** How many pictures an item can have. */
    public static final int MAX_IMAGES = 5;

    /** Picture: content hash and type. */
    public record Pic(String hash, String mime) {}

    private final Path dir;
    /** Smaller copies (thumb): DATA_DIR/thumbs/ab/cd/<sha256>.<jpg|png> — created on first request, not included in backups. */
    private final Path thumbs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ImageFiles(@Value("${DATA_DIR:/data}") String dataDir, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.dir = Path.of(dataDir, "images");
        this.thumbs = Path.of(dataDir, "thumbs");
        this.jdbc = jdbc;
        this.tx = tx;
    }

    // ---------- reading ----------

    /** Item pictures in order. */
    public List<Pic> item(long itemId) {
        return jdbc.query("SELECT p.hash, p.mime FROM item_pictures ip JOIN pictures p ON p.hash = ip.hash "
                + "WHERE ip.item_id = ? ORDER BY ip.pos", (rs, n) -> new Pic(rs.getString(1), rs.getString(2)), itemId);
    }

    /** Pictures folder (DATA_DIR/images). */
    public Path dir() {
        return dir;
    }

    /** Files of all pictures from the pictures register (for the backup; call under the lock). */
    public List<Path> allFiles() {
        return jdbc.query("SELECT hash, mime FROM pictures", (rs, n) -> file(rs.getString(1), rs.getString(2)));
    }

    /** Largest side of the smaller copy: with a margin for double-density screens (the cover on the home page is 76px). */
    public static final int THUMB = 200;

    /**
     * Smaller copy of a picture (at most THUMB on the larger side): from the thumbs folder, and if it is not there — created.
     * null — nothing to shrink (the picture is small already) or ImageIO could not read it — then the picture itself is served.
     * A copy with transparency — PNG, without — JPEG.
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
        } catch (Exception e) { // not a picture for ImageIO (for example, CMYK JPEG) — the picture itself is served
            log.debug("Не удалось уменьшить картинку {}: {}", pic.hash(), e.toString());
            return null;
        }
    }

    /** Smaller copy: content and type. */
    public record Thumb(byte[] bytes, String mime) {}

    /** Shrinking in steps by half (smoother than several times at once), the last step — to max on the larger side. */
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

    /** Picture content or null if there is no file. */
    public byte[] read(Pic pic) {
        try {
            Path f = file(pic.hash(), pic.mime());
            return Files.exists(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- changes ----------

    /**
     * Replaces all pictures of the item (up to MAX_IMAGES, in order; an empty list deletes all).
     * Non-pictures (not PNG, JPEG, GIF) are skipped.
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

    /** Sets the collection picture (null removes it). Not a picture — nothing changes. */
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

    /** How many items to process at a time when clearing a collection (one transaction per batch). */
    private static final int CLEAR_BATCH = 50;

    /**
     * Removes pictures of all items of the collection (the cover stays) in batches of CLEAR_BATCH items:
     * each batch is one transaction under the lock, between batches other users can save.
     * progress receives how many pictures are already removed. Returns how many were removed.
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

    /** Removes all pictures of the items in the list; returns how many pictures were removed. */
    private synchronized int clearItems(List<Long> ids) {
        String in = "(" + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")";
        Object[] args = ids.toArray();
        List<String> old = jdbc.queryForList("SELECT hash FROM item_pictures WHERE item_id IN " + in, String.class, args);
        tx.executeWithoutResult(s -> jdbc.update("DELETE FROM item_pictures WHERE item_id IN " + in, args));
        release(old);
        return old.size();
    }

    /** Hashes of the item pictures — remember them before deleting it, pass them to release after deleting. */
    public List<String> itemHashes(long itemId) {
        return hashes("SELECT hash FROM item_pictures WHERE item_id = ?", itemId);
    }

    /** Hashes of the collection cover and the pictures of all its items. */
    public List<String> collectionHashes(long collectionId) {
        List<String> res = new ArrayList<>(hashes("SELECT hash FROM collection_pictures WHERE collection_id = ?", collectionId));
        res.addAll(hashes("SELECT hash FROM item_pictures WHERE item_id IN (SELECT id FROM items WHERE collection_id = ?)",
                collectionId));
        return res;
    }

    /**
     * Deletes files (and pictures rows) of hashes nobody refers to any more.
     * Rows — in one transaction (one by one SQLite would write each to disk — thousands of pictures would take minutes),
     * files — after it.
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
                for (String ext : List.of("jpg", "png")) Files.deleteIfExists(thumbFile(h, ext)); // and the smaller copies
            } catch (IOException e) {
                log.warn("Не удалось удалить картинку {}: {}", f.getFileName(), e.toString());
            }
        }
    }

    // ---------- files ----------

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
     * Puts a picture into the storage (if it is not there yet) and records it in pictures; returns the hash
     * or null if it is not a picture. Call under the lock.
     */
    private String store(byte[] bytes) {
        String mime = bytes == null ? null : Repo.imageType(bytes);
        if (mime == null) return null;
        String h = sha256(bytes);
        Path f = file(h, mime);
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                // first to a temporary file, then rename: a half-written file never stays under the real name
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

    // ---------- start ----------

    @Override
    public synchronized void run(ApplicationArguments args) {
        // pictures rows without references and files without a pictures row (and half-written .tmp) are deleted
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
            // nested items first, then folders — so emptied folders are deleted too
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

    /** Smaller copies of pictures no longer in pictures (and half-written .tmp) are deleted. */
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
