package app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * База прежних версий (до 1.18: тип коллекции — название в collections.kind, поля объектов — столбцами и отдельными
 * таблицами) новой версией не переносится (решение пользователя: данные загружаются заново из Excel, картинки —
 * импортом). Перед запуском такая база и её картинки откладываются в резервные файлы рядом —
 * app-before-1.18.db, images-before-1.18, — и приложение начинает с чистой базы.
 */
final class DataReset {

    static final String SUFFIX = "-before-1.18";

    private DataReset() {}

    /** Откладывает базу прежней версии в папке данных; true — отложена. Новую или пустую базу не трогает. */
    static boolean resetIfOld(Path dataDir) throws IOException {
        Path db = dataDir.resolve("app.db");
        if (!Files.exists(db) || !isOld(db)) return false;
        String to = free(dataDir, "app" + SUFFIX, ".db");
        move(db, dataDir.resolve(to));
        // журнал базы (если остался) — вместе с ней, под тем же именем
        for (String part : new String[] {"-wal", "-shm"}) {
            Path p = dataDir.resolve("app.db" + part);
            if (Files.exists(p)) move(p, dataDir.resolve(to + part));
        }
        for (String dir : new String[] {"images", "thumbs"}) {
            Path p = dataDir.resolve(dir);
            if (Files.isDirectory(p)) move(p, dataDir.resolve(free(dataDir, dir + SUFFIX, "")));
        }
        return true;
    }

    /** База прежней версии: в коллекциях тип — по названию (столбец kind), а не по id. */
    static boolean isOld(Path db) throws IOException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM pragma_table_info('collections') WHERE name = 'kind'")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw new IOException("Cannot read " + db + ": " + e.getMessage(), e);
        }
    }

    /** Имя, которого ещё нет в папке: name + ext, иначе name-2 + ext, name-3 + ext… */
    private static String free(Path dir, String name, String ext) {
        String n = name + ext;
        for (int i = 2; Files.exists(dir.resolve(n)); i++) n = name + "-" + i + ext;
        return n;
    }

    private static void move(Path from, Path to) throws IOException {
        Files.move(from, to);
        System.out.println("Data of the previous version moved aside: " + from.getFileName() + " -> " + to.getFileName());
    }
}
