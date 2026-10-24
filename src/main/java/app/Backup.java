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
 * Резервная копия всех данных приложения (вкладка «Резервная копия», только администратор): zip с базой app.db
 * и папкой images. Восстановление заменяет все данные без перезапуска: картинки распаковываются в images
 * (одинаковое содержимое — одинаковое имя, существующие файлы не трогаются), база заменяется через SQLite backup API
 * («restore from» драйвера), затем заново выполняется schema.sql (разовые обновления старой копии) и ImageFiles.run —
 * перенос прежних хранилищ и удаление картинок, на которые больше никто не ссылается.
 * Всё — под блокировкой ImageFiles: пока идёт копирование или восстановление, картинки никто не меняет.
 */
@Component
public class Backup {

    private static final Logger log = LoggerFactory.getLogger(Backup.class);

    /** Имя базы в архиве и картинки: images/ab/cd/<sha256>.<ext> — другие пути в архиве не принимаются. */
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

    /** Пишет zip-архив копии в out: сначала снимок базы (VACUUM INTO), затем все картинки, на которые есть ссылки. */
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
                zip.setLevel(Deflater.NO_COMPRESSION); // картинки уже сжаты
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

    /** Копию нельзя восстановить: не архив копии, нет базы и т. п. Текст — для пользователя. */
    public static class BadBackup extends Exception {
        BadBackup(String message) {
            super(message);
        }
    }

    /**
     * Восстанавливает данные из архива копии (поток in — загруженный файл). Сначала архив сохраняется и проверяется
     * целиком, данные меняются только если он годится; при ошибке проверки — BadBackup, данные не тронуты.
     */
    public void restore(InputStream in) throws IOException, BadBackup {
        Path work = Files.createTempDirectory(dataDir, "restore-");
        try {
            Path zipFile = work.resolve("upload.zip");
            Files.copy(in, zipFile);
            // 1. проверка: только база и картинки с правильными путями, база — SQLite с пользователями
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
            // 2. замена данных
            synchronized (files) {
                try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
                    for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                        if (!IMAGE_ENTRY.matcher(e.getName()).matches()) continue;
                        Path f = files.dir().resolve(e.getName().substring("images/".length()));
                        if (Files.exists(f)) continue; // то же имя — то же содержимое
                        Files.createDirectories(f.getParent());
                        Path tmp = Files.createTempFile(f.getParent(), "restore-", ".tmp");
                        Files.copy(zip, tmp, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                }
                jdbc.execute("restore from " + quote(db));
                // старая копия: таблицы и разовые обновления текущей версии, перенос прежних хранилищ картинок
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

    /** База из архива открывается и в ней есть пользователи — иначе BadBackup. */
    private static void checkDb(Path db) throws BadBackup {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM users")) {
            if (!rs.next() || rs.getInt(1) == 0) throw new BadBackup(I18n.t("backup.noUsers"));
        } catch (java.sql.SQLException e) {
            throw new BadBackup(I18n.t("backup.badDb"));
        }
    }

    /** Путь в кавычках для команды драйвера «restore from» (кавычки, которых нет в пути). */
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

    /** Для страницы: сколько места займёт копия (база + картинки), байт — примерно. */
    public long approximateSize() {
        long size = 0;
        try {
            size += Files.size(dataDir.resolve("app.db"));
        } catch (IOException ignored) {
            // базы может не быть в другом месте — не важно для подсказки
        }
        Long images = jdbc.queryForObject("SELECT COALESCE(SUM(size), 0) FROM pictures", Long.class);
        return size + (images == null ? 0 : images);
    }
}
