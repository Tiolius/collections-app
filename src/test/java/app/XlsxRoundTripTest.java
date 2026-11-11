package app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Выгрузка в Excel и загрузка обратно: все поля объектов (кроме картинок) переносятся без потерь. */
class XlsxRoundTripTest extends DbTest {

    @Autowired XlsxExport export;
    @Autowired XlsxImport load;

    static final List<String> ALL = List.of("platform", "format", "edition", "status", "rating", "price", "purchased",
            "available", "tags", "notes", "oldgames");

    @Test
    void everyFieldSurvivesExportAndImport() throws Exception {
        long uid = newUser();
        String kind = "Все поля";
        repo.saveKind(uid, null, kind, "games", ALL, ALL, List.of(), List.of("Completed", "Unfinished"), List.of());
        long cid = newCollection(uid, "Мои игры", kind);
        newItem(uid, cid, new Repo.ItemForm("Gothic II", "PC", "DVD", "Completed", 1499, List.of("0", "1"), 7, "11",
                "2023-05-17", "rpg, классика", "Europe", "Без коробки.\nДиск 2 поцарапан.", "Ролевая игра.\n\nПродолжение Gothic."));
        newItem(uid, cid, form("Пустой"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Xlsx.write(out, export.sheets(uid, List.of(repo.collection(uid, cid))));

        long other = newUser();
        repo.saveKind(other, null, kind, "games", ALL, ALL, List.of(), List.of("Completed", "Unfinished"), List.of());
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

    @Test
    void ownLabelsAreExportedAndRecognized() throws Exception {
        long uid = newUser();
        repo.saveKind(uid, null, "Книги2", "books", List.of("status", "price"), List.of("status", "price"),
                List.of("Статус чтения", "Стоимость"), List.of("Прочитано", "Читаю"), List.of());
        long cid = newCollection(uid, "Полка", "Книги2");
        newItem(uid, cid, new Repo.ItemForm("Солярис", null, null, "Читаю", 350, null, null, null, null, null, null, null, null));
        List<Xlsx.Sheet> sheets = export.sheets(uid, List.of(repo.collection(uid, cid)));
        assertThat(sheets.get(0).headers()).containsExactly("Название", "Статус чтения", "Стоимость", "Описание");

        sheets.get(0).rows().get(0).set(1, "читаю");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Xlsx.write(out, sheets);
        jdbc.update("DELETE FROM items WHERE collection_id = ?", cid);
        // статус в другом регистре — берётся из списка типа; столбцы узнаются по своим подписям
        XlsxImport.Result r = load.load(uid, new ByteArrayInputStream(out.toByteArray()), "");
        Entry it = repo.items(r.firstCollection(), "name", false, Map.of()).get(0);
        assertThat(it.status()).isEqualTo("Читаю");
        assertThat(it.price()).isEqualTo(350);
    }
}
