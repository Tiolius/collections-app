package app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A database of previous versions (before 1.18: collection type — a name in collections.kind, item fields — columns and separate
 * tables) is not carried over by the new version (the user's decision: data is loaded again from Excel, pictures — by
 * import). Before start such a database and its pictures are moved aside into backup files next to it —
 * app-before-1.18.db, images-before-1.18, — and the application starts with a clean database.
 */
final class DataReset {

    static final String SUFFIX = "-before-1.18";

    private DataReset() {}

    /** Moves a previous-version database in the data folder aside; true — moved. A new or empty database is left alone. */
    static boolean resetIfOld(Path dataDir) throws IOException {
        Path db = dataDir.resolve("app.db");
        if (!Files.exists(db) || !isOld(db)) return false;
        String to = free(dataDir, "app" + SUFFIX, ".db");
        move(db, dataDir.resolve(to));
        // the database journal (if left over) goes with it, under the same name
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

    /** Previous-version database: collections refer to the type by name (column kind), not by id. */
    static boolean isOld(Path db) throws IOException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM pragma_table_info('collections') WHERE name = 'kind'")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw new IOException("Cannot read " + db + ": " + e.getMessage(), e);
        }
    }

    /** A name not yet in the folder: name + ext, otherwise name-2 + ext, name-3 + ext… */
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
