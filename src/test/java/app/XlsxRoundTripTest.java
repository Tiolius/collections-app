package app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Выгрузка в Excel и загрузка обратно: все поля объектов (кроме картинок) переносятся без потерь. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class XlsxRoundTripTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("collections-xlsx").toString();
        r.add("DATA_DIR", () -> dir);
        r.add("updates.enabled", () -> "false");
    }

    @Autowired Repo repo;
    @Autowired XlsxExport export;
    @Autowired XlsxImport load;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void russian() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
    }

    @Test
    void everyFieldSurvivesExportAndImport() throws Exception {
        long uid = repo.createUser("owner", "Kwq7-zmlpt-Rvx9", "admin");
        String kind = "Игры (диски)";
        jdbc.update("UPDATE kinds SET fields = 'platform,format,edition,status,rating,price,purchased,available,tags,notes,oldgames' "
                + "WHERE user_id = ? AND name = ?", uid, kind);
        long cid = repo.createCollection(uid);
        jdbc.update("UPDATE collections SET name = 'Мои игры', kind = ? WHERE id = ?", kind, cid);
        long id = repo.createItem(cid);
        jdbc.update("UPDATE items SET name = 'Gothic II', platform = 'PC', format = 'DVD', status = 'Completed', price = 1499 WHERE id = ?", id);
        jdbc.update("INSERT INTO item_ratings (item_id, rating) VALUES (?, 7)", id);
        jdbc.update("INSERT INTO item_present (item_id) VALUES (?)", id);
        jdbc.update("INSERT INTO item_details (item_id, purchased, tags, edition, notes) VALUES (?, '2023-05-17', 'rpg, классика', 'Europe', ?)",
                id, "Без коробки.\nДиск 2 поцарапан.");
        jdbc.update("INSERT INTO item_oldgames (item_id, url) VALUES (?, 'https://www.old-games.ru/game/11.html')", id);
        repo.setItemDescription(id, "Ролевая игра.\n\nПродолжение Gothic.");
        long empty = repo.createItem(cid);
        jdbc.update("UPDATE items SET name = 'Пустой' WHERE id = ?", empty);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Xlsx.write(out, export.sheets(uid, List.of(repo.collection(uid, cid))));

        long other = repo.createUser("other", "Kwq7-zmlpt-Rvx9", "user");
        jdbc.update("UPDATE kinds SET fields = 'platform,format,edition,status,rating,price,purchased,available,tags,notes,oldgames' "
                + "WHERE user_id = ? AND name = ?", other, kind);
        XlsxImport.Result r = load.load(other, new ByteArrayInputStream(out.toByteArray()), kind);
        assertThat(r.collections()).isEqualTo(1);

        List<Entry> items = repo.items(r.firstCollection(), "name", false, Map.of());
        assertThat(items).extracting(Entry::name).containsExactly("Gothic II", "Пустой");
        Entry g = repo.item(other, items.get(0).id());
        assertThat(g.platform()).isEqualTo("PC");
        assertThat(g.format()).isEqualTo("DVD");
        assertThat(g.edition()).isEqualTo("Europe");
        assertThat(g.status()).isEqualTo("Completed");
        assertThat(g.rating()).isEqualTo(7);
        assertThat(g.price()).isEqualTo(1499);
        assertThat(g.purchased()).isEqualTo("2023-05-17");
        assertThat(g.available()).isTrue();
        assertThat(g.tags()).isEqualTo("rpg, классика");
        assertThat(g.notes()).isEqualTo("Без коробки.\nДиск 2 поцарапан.");
        assertThat(g.oldGamesId()).isEqualTo("11");
        assertThat(g.description()).isEqualTo("Ролевая игра.\n\nПродолжение Gothic.");
        Entry e = repo.item(other, items.get(1).id());
        assertThat(e.available()).isFalse();
        assertThat(e.description()).isNull();
    }
}
