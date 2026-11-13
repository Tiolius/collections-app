package app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** Custom fields of a type: creation, order, values by field kind, sorting, filters, Excel, search. */
class CustomFieldsTest extends DbTest {

    @Autowired XlsxExport export;
    @Autowired XlsxImport load;

    /** Type "Книги+": price, then custom fields (text, number, list, yes/no, date, long text), then status. */
    Kind books(long uid) {
        List<String> rows = List.of("price", "n1", "n2", "n3", "n4", "n5", "n6", "status");
        repo.saveKind(uid, null, "Книги+", "books", rows, rows,
                List.of("", "Издательство", "Страниц", "Переплёт", "Подписана", "Прочитана", "Цитаты", ""),
                List.of("", "text", "number", "select", "bool", "date", "longtext", ""),
                List.of("", "", "", "Твёрдый\nМягкий\n\nтвёрдый ", "", "", "", ""),
                List.of("Прочитано"), List.of());
        return repo.kindByName(uid, "Книги+");
    }

    long field(Kind k, String label) {
        return k.customs().stream().filter(f -> f.label().equals(label)).findFirst().orElseThrow().id();
    }

    @Test
    void customFieldsAreCreatedInOrderWithKindsAndOptions() {
        long uid = newUser();
        assertThatThrownBy(() -> books(uid)).isInstanceOf(ResponseStatusException.class); // "твёрдый" — a repeat
        List<String> rows = List.of("price", "n1", "n3", "status");
        repo.saveKind(uid, null, "Книги+", "books", rows, rows, List.of("", "Издательство", "Переплёт", ""),
                List.of("", "text", "select", ""), List.of("", "", "Твёрдый\nМягкий", ""), List.of(), List.of());
        Kind k = repo.kindByName(uid, "Книги+");
        assertThat(k.fields()).containsExactly("price", "status");
        assertThat(k.customs()).extracting(Kind.Field::label).containsExactly("Издательство", "Переплёт");
        assertThat(k.keys()).containsExactly("price", "c" + field(k, "Издательство"), "c" + field(k, "Переплёт"), "status");
        assertThat(k.field("c" + field(k, "Переплёт")).values()).containsExactly("Твёрдый", "Мягкий");
        // no name, unknown kind, a list without values — an error
        assertThatThrownBy(() -> repo.saveKind(uid, k.id(), k.name(), "books", List.of("n1"), List.of("n1"), List.of(""),
                List.of("text"), List.of(""), List.of(), List.of())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> repo.saveKind(uid, k.id(), k.name(), "books", List.of("n1"), List.of("n1"), List.of("X"),
                List.of("photo"), List.of(""), List.of(), List.of())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> repo.saveKind(uid, k.id(), k.name(), "books", List.of("n1"), List.of("n1"), List.of("X"),
                List.of("select"), List.of(" \n "), List.of(), List.of())).isInstanceOf(ResponseStatusException.class);
    }

    Kind fullBooks(long uid) {
        List<String> rows = List.of("price", "n1", "n2", "n3", "n4", "n5", "n6", "status");
        repo.saveKind(uid, null, "Книги+", "books", rows, rows,
                List.of("", "Издательство", "Страниц", "Переплёт", "Подписана", "Прочитана", "Цитаты", ""),
                List.of("", "text", "number", "select", "bool", "date", "longtext", ""),
                List.of("", "", "", "Твёрдый\nМягкий", "", "", "", ""), List.of("Прочитано"), List.of());
        return repo.kindByName(uid, "Книги+");
    }

    @Test
    void valuesAreValidatedSortedAndFiltered() {
        long uid = newUser();
        Kind k = fullBooks(uid);
        long cid = newCollection(uid, "Полка", "Книги+");
        long pub = field(k, "Издательство"), pages = field(k, "Страниц"), cover = field(k, "Переплёт"),
                signed = field(k, "Подписана"), read = field(k, "Прочитана");
        long a = newItem(uid, cid, form("Солярис")), b = newItem(uid, cid, form("Пикник")), c = newItem(uid, cid, form("Улитка"));
        repo.setCustom(a, pub, " Мир ");
        repo.setCustom(a, pages, "352");
        repo.setCustom(b, pages, "1 024,5");
        repo.setCustom(c, pages, "96");
        repo.setCustom(a, cover, "Твёрдый");
        repo.setCustom(b, signed, "1");
        repo.setCustom(a, read, "2024-02-29");
        Map<Long, Object> va = repo.customValues(List.of(a)).get(a);
        assertThat(va).containsEntry(pub, "Мир").containsEntry(pages, 352L).containsEntry(cover, "Твёрдый").containsEntry(read, "2024-02-29");
        assertThat(repo.customValues(List.of(b)).get(b)).containsEntry(pages, 1024.5).containsEntry(signed, 1L);
        assertThatThrownBy(() -> repo.setCustom(a, pages, "много")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> repo.setCustom(a, cover, "Кожаный")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> repo.setCustom(a, read, "29.02.2024")).isInstanceOf(ResponseStatusException.class);
        repo.setCustom(a, pub, "");                                               // empty — no value
        assertThat(repo.customValues(List.of(a)).get(a)).doesNotContainKey(pub);
        // numbers sort as numbers, no value — at the end
        assertThat(repo.items(cid, "c" + pages, false, Map.of())).extracting(Entry::name).containsExactly("Улитка", "Солярис", "Пикник");
        assertThat(repo.items(cid, "c" + pages, true, Map.of())).extracting(Entry::name).containsExactly("Пикник", "Солярис", "Улитка");
        assertThat(repo.items(cid, "name", false, Map.of("c" + cover, "Твёрдый"))).extracting(Entry::name).containsExactly("Солярис");
        assertThat(repo.items(cid, "name", false, Map.of("c" + signed, "1"))).extracting(Entry::name).containsExactly("Пикник");
        assertThat(repo.items(cid, "name", false, Map.of("c" + signed, "0"))).hasSize(2);
        assertThat(repo.customDistinct(cid, pages)).containsExactly("1024.5", "352", "96");
        // a field not of the item's type — nothing; someone else's field in a filter — ignored
        repo.setCustom(a, 999_999L, "x");
        assertThat(repo.items(cid, "name", false, Map.of("c999999", "x"))).hasSize(3);
        // search — by the text of custom fields
        repo.setCustom(c, pub, "Детская литература");
        assertThat(repo.search(uid, "детская").items()).extracting(Entry::name).containsExactly("Улитка");
    }

    @Test
    void editingKindKeepsValuesAndUncheckingDeletesThem() {
        long uid = newUser();
        Kind k = fullBooks(uid);
        long cid = newCollection(uid, "Полка", "Книги+");
        long pub = field(k, "Издательство"), pages = field(k, "Страниц");
        long a = newItem(uid, cid, form("Солярис"));
        repo.setCustom(a, pub, "Мир");
        repo.setCustom(a, pages, "352");
        // rename "Издательство", reorder; the kind of a custom field does not change even if another one is sent
        List<String> rows = List.of("c" + pages, "c" + pub, "price");
        repo.saveKind(uid, k.id(), k.name(), "books", rows, List.of("c" + pub, "price"), List.of("", "Издатель", ""),
                List.of("", "number", ""), List.of("", "", ""), List.of(), List.of());
        Kind k2 = repo.kind(uid, k.id());
        assertThat(k2.keys()).containsExactly("c" + pub, "price");
        assertThat(k2.label("c" + pub)).isEqualTo("Издатель");
        assertThat(k2.field("c" + pub).type()).isEqualTo("text");
        assertThat(repo.customValues(List.of(a)).get(a)).containsEntry(pub, "Мир").doesNotContainKey(pages);
        // someone else's custom field in the form — 404
        long other = newUser();
        Kind ok = fullBooks(other);
        assertThatThrownBy(() -> repo.saveKind(uid, k.id(), k.name(), "books", List.of("c" + field(ok, "Страниц")),
                List.of("c" + field(ok, "Страниц")), List.of("x"), List.of(""), List.of(""), List.of(), List.of()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void customFieldsGoThroughExcel() throws Exception {
        long uid = newUser();
        Kind k = fullBooks(uid);
        long cid = newCollection(uid, "Полка", "Книги+");
        long a = newItem(uid, cid, form("Солярис"));
        repo.setCustom(a, field(k, "Издательство"), "Мир");
        repo.setCustom(a, field(k, "Страниц"), "352,5");
        repo.setCustom(a, field(k, "Переплёт"), "Мягкий");
        repo.setCustom(a, field(k, "Подписана"), "1");
        repo.setCustom(a, field(k, "Прочитана"), "2020-01-31");
        repo.setCustom(a, field(k, "Цитаты"), "Строка 1\nСтрока 2");
        List<Xlsx.Sheet> sheets = export.sheets(uid, List.of(repo.collection(uid, cid)));
        assertThat(sheets.get(0).headers()).containsExactly("Название", "Цена (₽)", "Издательство", "Страниц", "Переплёт",
                "Подписана", "Прочитана", "Цитаты", "Статус прохождения", "Описание");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Xlsx.write(out, sheets);

        long other = newUser();
        Kind ok = fullBooks(other);
        XlsxImport.Result r = load.load(other, new ByteArrayInputStream(out.toByteArray()), "Книги+");
        assertThat(r.skipped()).isEmpty();
        long id = repo.items(r.firstCollection(), "name", false, Map.of()).get(0).id();
        Map<Long, Object> v = repo.customValues(List.of(id)).get(id);
        assertThat(v).containsEntry(field(ok, "Издательство"), "Мир").containsEntry(field(ok, "Страниц"), 352.5)
                .containsEntry(field(ok, "Переплёт"), "Мягкий").containsEntry(field(ok, "Подписана"), 1L)
                .containsEntry(field(ok, "Прочитана"), "2020-01-31").containsEntry(field(ok, "Цитаты"), "Строка 1\nСтрока 2");
    }
}
