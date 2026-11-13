package app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * Backup of all application data ("Backup" tab, administrator only): a zip with the app.db database
 * and the images folder. Restoring replaces all data without a restart: pictures are unpacked into images
 * (same content — same name, existing files are left alone), the database is replaced via the SQLite backup API
 * (the driver's "restore from"), then schema.sql runs again (one-off updates of an old backup) and ImageFiles.run —
 * moving old storages and deleting pictures nobody refers to any more.
 * All under the ImageFiles lock: while copying or restoring, nobody changes pictures.
 */
@Component
public class Backup {

    private static final Logger log = LoggerFactory.getLogger(Backup.class);

    /** Name of the database in the archive and pictures: images/ab/cd/<sha256>.<ext> — other paths in the archive are rejected. */
    static final String DB_ENTRY = "app.db";
    private static final Pattern IMAGE_ENTRY = Pattern.compile("images/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}\\.(png|jpg|gif)");

    private final Path dataDir;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ImageFiles files;

    public Backup(@Value("${DATA_DIR:/data}") String dataDir, JdbcTemplate jdbc, DataSource dataSource, ImageFiles files) {
        this.dataDir = Path.of(dataDir).toAbsolutePath();
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.files = files;
    }

    /** Writes the backup zip to out: first a database snapshot (VACUUM INTO), then all pictures that are referenced. */
    public void write(OutputStream out) throws IOException {
        Path snapshot = dataDir.resolve("backup-" + System.nanoTime() + ".db");
        synchronized (files) {
            try {
                jdbc.execute("VACUUM INTO '" + snapshot.toString().replace("'", "''") + "'");
                ZipOutputStream zip = new ZipOutputStream(out);
                zip.setLevel(Deflater.BEST_SPEED);
                zip.putNextEntry(new ZipEntry(DB_ENTRY));
                Files.copy(snapshot, zip);
                zip.closeEntry();
                zip.setLevel(Deflater.NO_COMPRESSION); // pictures are already compressed
                for (Path f : files.allFiles()) {
                    if (!Files.exists(f)) continue;
                    zip.putNextEntry(new ZipEntry("images/" + files.dir().relativize(f).toString().replace('\\', '/')));
                    Files.copy(f, zip);
                    zip.closeEntry();
                }
                zip.finish();
                zip.flush();
            } finally {
                Files.deleteIfExists(snapshot);
            }
        }
    }

    /** The backup cannot be restored: not a backup archive, no database etc. The text is for the user. */
    public static class BadBackup extends Exception {
        BadBackup(String message) {
            super(message);
        }
    }

    /**
     * Restores data from a backup archive (stream in — the uploaded file). First the archive is saved and checked
     * as a whole, data changes only if it is fine; if the check fails — BadBackup, data untouched.
     */
    public void restore(InputStream in) throws IOException, BadBackup {
        Path work = Files.createTempDirectory(dataDir, "restore-");
        try {
            Path zipFile = work.resolve("upload.zip");
            Files.copy(in, zipFile);
            // 1. check: only the database and pictures with correct paths, the database is SQLite with users
            Path db = work.resolve(DB_ENTRY);
            int images = 0;
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    if (e.isDirectory()) continue;
                    if (e.getName().equals(DB_ENTRY)) Files.copy(zip, db, StandardCopyOption.REPLACE_EXISTING);
                    else if (IMAGE_ENTRY.matcher(e.getName()).matches()) images++;
                    else throw new BadBackup(I18n.t("backup.badFile"));
                }
            } catch (java.util.zip.ZipException e) {
                throw new BadBackup(I18n.t("backup.badFile"));
            }
            if (!Files.exists(db)) throw new BadBackup(I18n.t("backup.noDb"));
            checkDb(db);
            // 2. replacing data
            synchronized (files) {
                try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
                    for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                        if (!IMAGE_ENTRY.matcher(e.getName()).matches()) continue;
                        Path f = files.dir().resolve(e.getName().substring("images/".length()));
                        if (Files.exists(f)) continue; // same name — same content
                        Files.createDirectories(f.getParent());
                        Path tmp = Files.createTempFile(f.getParent(), "restore-", ".tmp");
                        Files.copy(zip, tmp, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                }
                jdbc.execute("restore from " + quote(db));
                // old backup: tables and one-off updates of the current version, moving old picture storages
                ResourceDatabasePopulator schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
                schema.setSqlScriptEncoding("UTF-8");
                schema.execute(dataSource);
                files.run(null);
            }
            log.info("Данные восстановлены из резервной копии: картинок в архиве {}", images);
        } finally {
            deleteTree(work);
        }
    }

    /**
     * The database from the archive opens, has users and is of this version (a backup of a version before 1.18 does not fit:
     * its database is not carried over, see DataReset) — otherwise BadBackup.
     */
    private static void checkDb(Path db) throws BadBackup {
        boolean old;
        try {
            old = DataReset.isOld(db);
        } catch (java.io.IOException e) {
            throw new BadBackup(I18n.t("backup.badDb"));
        }
        if (old) throw new BadBackup(I18n.t("backup.oldVersion"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM users")) {
            if (!rs.next() || rs.getInt(1) == 0) throw new BadBackup(I18n.t("backup.noUsers"));
        } catch (java.sql.SQLException e) {
            throw new BadBackup(I18n.t("backup.badDb"));
        }
    }

    /** Quoted path for the driver command "restore from" (quotes that are not in the path). */
    private static String quote(Path p) {
        String s = p.toString();
        return s.contains("'") ? "\"" + s + "\"" : "'" + s + "'";
    }

    private static void deleteTree(Path dir) {
        try (var all = Files.walk(dir)) {
            for (Path p : all.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Не удалось удалить временную папку {}: {}", dir, e.toString());
        }
    }

    /** For the page: how much space the backup takes (database + pictures), bytes — roughly. */
    public long approximateSize() {
        long size = 0;
        try {
            size += Files.size(dataDir.resolve("app.db"));
        } catch (IOException ignored) {
            // the database may be elsewhere — does not matter for the hint
        }
        Long images = jdbc.queryForObject("SELECT COALESCE(SUM(size), 0) FROM pictures", Long.class);
        return size + (images == null ? 0 : images);
    }
}
