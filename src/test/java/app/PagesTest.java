package app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** Страницы целиком (без браузера): что видит вошедший пользователь. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PagesTest extends DbTest {

    @Autowired MockMvc mvc;

    /** Сессия вошедшего пользователя (как после входа: id и хеш пароля). */
    MockHttpSession login(long uid) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("uid", uid);
        s.setAttribute(WebController.SESSION_PW, repo.user(uid).passwordHash());
        return s;
    }

    String page(long uid, String url) throws Exception {
        return mvc.perform(get(url).session(login(uid))).andReturn().getResponse().getContentAsString();
    }

    /** Порядок, в котором классы c-<поле> встречаются в куске страницы. */
    static List<String> order(String html, List<String> keys) {
        return keys.stream().filter(k -> html.contains("c-" + k + "\""))
                .sorted(java.util.Comparator.comparingInt(k -> html.indexOf("c-" + k + "\""))).toList();
    }

    @Test
    void rowCellsFollowColumnOrderOfKind() throws Exception {
        long uid = newUser();
        List<String> f = List.of("price", "tags", "platform", "available");
        repo.saveKind(uid, null, "Порядок", "games", f, f, List.of("Стоимость"), List.of(), List.of());
        repo.setShowPrices(uid, true);
        long cid = newCollection(uid, "Полка", "Порядок");
        newItem(uid, cid, new Repo.ItemForm("Doom", "PC", null, null, 199, List.of("0", "1"), null, null, null, "шутер", null, null, null));
        String html = page(uid, "/collections/" + cid + "?sort=name");
        String head = html.substring(html.indexOf("<thead>"), html.indexOf("</thead>"));
        String row = html.substring(html.indexOf("<tbody>"), html.indexOf("</tbody>"));
        List<String> keys = List.of("price", "tags", "platform", "available");
        assertThat(order(head, keys)).containsExactlyElementsOf(keys);
        assertThat(order(row, keys)).containsExactlyElementsOf(keys);
        assertThat(head).contains("Стоимость");
        assertThat(row).contains("199 ₽").contains("PC").contains("шутер");
    }

    @Test
    void customFieldsShowInTableAndSaveFromDialog() throws Exception {
        long uid = newUser();
        List<String> rows = List.of("n1", "price", "n2", "n3");
        repo.saveKind(uid, null, "Своё", "other", rows, rows, List.of("Серия", "", "Том", "Есть автограф"),
                List.of("text", "", "number", "bool"), List.of("", "", "", ""), List.of(), List.of());
        Kind k = repo.kindByName(uid, "Своё");
        long series = k.customs().get(0).id(), vol = k.customs().get(1).id(), auto = k.customs().get(2).id();
        long cid = newCollection(uid, "Полка", "Своё");
        long id = newItem(uid, cid, form("Дюна"));
        // окно отправляет свои поля как cf_<id>; у «да/нет» — скрытое 0 и галочка 1
        mvc.perform(multipart("/items/" + id + "/edit").session(login(uid)).header("Sec-Fetch-Site", "same-origin")
                .param("name", "Дюна").param("price", "500").param("cf_" + series, "Хроники Дюны")
                .param("cf_" + vol, "1").param("cf_" + auto, "0", "1"));
        assertThat(repo.customValues(List.of(id)).get(id)).containsEntry(series, "Хроники Дюны")
                .containsEntry(vol, 1L).containsEntry(auto, 1L);
        assertThat(repo.item(uid, id).price()).isEqualTo(500);

        String html = page(uid, "/collections/" + cid + "?sort=name");
        String head = html.substring(html.indexOf("<thead>"), html.indexOf("</thead>"));
        String row = html.substring(html.indexOf("<tbody>"), html.indexOf("</tbody>"));
        List<String> keys = List.of("c" + series, "price", "c" + vol, "c" + auto);
        assertThat(order(head, keys)).containsExactlyElementsOf(keys);
        assertThat(order(row, keys)).containsExactlyElementsOf(keys);
        assertThat(head).contains("Серия").contains("Есть автограф");
        assertThat(row).contains("Хроники Дюны").contains("avail yes");
        // описание своих полей для окна — в data-item строки (кавычки JSON в атрибуте — &quot;)
        assertThat(row).contains("&quot;customs&quot;").contains("&quot;c" + series + "&quot;:&quot;Хроники Дюны&quot;");
        // снять галочку — значения нет
        mvc.perform(multipart("/items/" + id + "/edit").session(login(uid)).header("Sec-Fetch-Site", "same-origin")
                .param("name", "Дюна").param("cf_" + auto, "0"));
        assertThat(repo.customValues(List.of(id)).get(id)).doesNotContainKey(auto).containsEntry(series, "Хроники Дюны");
        // фильтр по своему полю
        assertThat(page(uid, "/collections/" + cid + "?f_c" + auto + "=1")).doesNotContain(">Дюна<");
    }

    @Test
    void statsShowKindStatusesAndFiltersWork() throws Exception {
        long uid = newUser();
        List<String> f = List.of("status", "price");
        repo.saveKind(uid, null, "Чтение", "books", f, f, List.of("Статус чтения", ""), List.of("Прочитано", "Читаю", "Брошено"),
                List.of("#aabbcc", "", ""));
        long cid = newCollection(uid, "Полка", "Чтение");
        newItem(uid, cid, new Repo.ItemForm("Солярис", null, null, "Прочитано", 300, null, null, null, null, null, null, null, null));
        newItem(uid, cid, new Repo.ItemForm("Пикник", null, null, "Прочитано", 150, null, null, null, null, null, null, null, null));
        newItem(uid, cid, new Repo.ItemForm("Улитка", null, null, "Читаю", 90, null, null, null, null, null, null, null, null));
        String stats = page(uid, "/stats");
        // статусы — из списка типа, с числами, цветом и ссылкой на фильтр; в итогах — самые частые
        assertThat(stats).contains("background:#aabbcc").contains("Прочитано <b>2</b>").contains("Читаю <b>1</b>")
                .contains("Брошено <b>0</b>").contains("f_status=");
        assertThat(stats).contains("<b>2</b><span>Прочитано</span>");
        // фильтры таблицы: статус и цена «от — до»
        String table = page(uid, "/collections/" + cid + "?f_status=Прочитано&f_price_min=200");
        String rows = table.substring(table.indexOf("<tbody>"), table.indexOf("</tbody>"));
        assertThat(rows).contains(">Солярис<").doesNotContain(">Пикник<").doesNotContain(">Улитка<");
        // в списке фильтра статусов — статусы типа по порядку, даже без объектов
        String head = table.substring(table.indexOf("<thead>"), table.indexOf("</thead>"));
        assertThat(head.indexOf(">Прочитано<")).isLessThan(head.indexOf(">Читаю<"));
        assertThat(head).contains(">Брошено<");
    }
}
