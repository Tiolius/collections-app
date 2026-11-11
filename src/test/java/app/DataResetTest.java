package app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** База прежней версии откладывается в резервный файл вместе с картинками; новая база не трогается. */
class DataResetTest {

    @TempDir Path dir;

    private void db(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("app.db"))) {
            c.createStatement().executeUpdate(sql);
        }
    }

    @Test
    void oldDatabaseIsMovedAside() throws Exception {
        db("CREATE TABLE collections (id INTEGER PRIMARY KEY, user_id INTEGER, name TEXT, kind TEXT)");
        Files.createDirectories(dir.resolve("images/ab/cd"));
        Files.writeString(dir.resolve("images/ab/cd/x.png"), "png");

        assertThat(DataReset.resetIfOld(dir)).isTrue();
        assertThat(dir.resolve("app.db")).doesNotExist();
        assertThat(dir.resolve("app-before-1.18.db")).exists();
        assertThat(dir.resolve("images-before-1.18/ab/cd/x.png")).exists();
        assertThat(dir.resolve("images")).doesNotExist();
        // второй раз — нечего откладывать; следующая старая база — под другим именем
        assertThat(DataReset.resetIfOld(dir)).isFalse();
        db("CREATE TABLE collections (id INTEGER PRIMARY KEY, user_id INTEGER, name TEXT, kind TEXT)");
        assertThat(DataReset.resetIfOld(dir)).isTrue();
        assertThat(dir.resolve("app-before-1.18-2.db")).exists();
    }

    @Test
    void newDatabaseIsKept() throws Exception {
        db("CREATE TABLE collections (id INTEGER PRIMARY KEY, user_id INTEGER, name TEXT, kind_id INTEGER)");
        assertThat(DataReset.resetIfOld(dir)).isFalse();
        assertThat(dir.resolve("app.db")).exists();
        // пустая папка — тоже ничего
        Files.delete(dir.resolve("app.db"));
        assertThat(DataReset.resetIfOld(dir)).isFalse();
    }
}
