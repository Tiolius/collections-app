package app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
}
