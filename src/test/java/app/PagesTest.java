package app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** Whole pages (without a browser): what a signed-in user sees. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PagesTest extends DbTest {

    @Autowired MockMvc mvc;

    /** Session of a signed-in user (as after signing in: id and password hash). */
    MockHttpSession login(long uid) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("uid", uid);
        s.setAttribute(WebController.SESSION_PW, repo.user(uid).passwordHash());
        return s;
    }

    String page(long uid, String url) throws Exception {
        return mvc.perform(get(url).session(login(uid))).andReturn().getResponse().getContentAsString();
    }

    /** Response page of a POST with parameters (name, value, name, value…). */
    String postPage(long uid, String url, String... params) throws Exception {
        var req = post(url).session(login(uid));
        for (int i = 0; i < params.length; i += 2) req.param(params[i], params[i + 1]);
        return mvc.perform(req).andReturn().getResponse().getContentAsString();
    }

    /** Order in which the c-<field> classes appear in a piece of the page. */
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
        // the window sends custom fields as cf_<id>; yes/no has a hidden 0 and a tick 1
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
        // definitions of custom fields for the window — in the row data-item (JSON quotes in an attribute — &quot;)
        assertThat(row).contains("&quot;customs&quot;").contains("&quot;c" + series + "&quot;:&quot;Хроники Дюны&quot;");
        // unticking — no value
        mvc.perform(multipart("/items/" + id + "/edit").session(login(uid)).header("Sec-Fetch-Site", "same-origin")
                .param("name", "Дюна").param("cf_" + auto, "0"));
        assertThat(repo.customValues(List.of(id)).get(id)).doesNotContainKey(auto).containsEntry(series, "Хроники Дюны");
        // filter by a custom field
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
        // statuses — from the type list, with numbers, colour and a filter link; in the totals — the most frequent
        assertThat(stats).contains("background:#aabbcc").contains("Прочитано <b>2</b>").contains("Читаю <b>1</b>")
                .contains("Брошено <b>0</b>").contains("f_status=");
        assertThat(stats).contains("<b>2</b><span>Прочитано</span>");
        // collection value: with hidden prices — in the eye tooltip, with visible prices — in the column
        assertThat(stats).contains("Стоимость коллекции: 540 ₽");
        repo.setShowPrices(uid, true);
        stats = page(uid, "/stats");
        assertThat(stats).contains(">540 ₽</span>");
        // table filters: status and price "from — to"
        String table = page(uid, "/collections/" + cid + "?f_status=Прочитано&f_price_min=200");
        String rows = table.substring(table.indexOf("<tbody>"), table.indexOf("</tbody>"));
        assertThat(rows).contains(">Солярис<").doesNotContain(">Пикник<").doesNotContain(">Улитка<");
        // the status filter list has the type statuses in order, even without items
        String head = table.substring(table.indexOf("<thead>"), table.indexOf("</thead>"));
        assertThat(head.indexOf(">Прочитано<")).isLessThan(head.indexOf(">Читаю<"));
        assertThat(head).contains(">Брошено<");
    }

    @Test
    void themePairIsSavedAndPutIntoPages() throws Exception {
        long uid = newUser();
        assertThat(repo.themePair(uid)).isEqualTo("night");                       // default — the first pair
        assertThat(page(uid, "/")).contains("var pair = \"night\"");
        mvc.perform(post("/display").session(login(uid)).header("Sec-Fetch-Site", "same-origin")
                .param("collectionsPerPage", "10").param("itemsPerPage", "200").param("theme", "dusk"));
        assertThat(repo.themePair(uid)).isEqualTo("dusk");
        assertThat(page(uid, "/stats")).contains("var pair = \"dusk\"");
        String display = page(uid, "/display");
        assertThat(display).containsPattern("value=\"dusk\"[^>]*checked").doesNotContainPattern("value=\"night\"[^>]*checked")
                .contains("data-pair=\"forest\"");
        // unknown pair — 400, the choice does not change
        int status = mvc.perform(post("/display").session(login(uid)).header("Sec-Fetch-Site", "same-origin")
                .param("collectionsPerPage", "10").param("itemsPerPage", "200").param("theme", "neon")).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
        assertThat(repo.themePair(uid)).isEqualTo("dusk");
    }

    @Test
    void adminSetsPasswordOfAnotherUserIncludingAdmin() throws Exception {
        long admin = newUser();
        jdbc.update("UPDATE users SET role = 'admin' WHERE id = ?", admin);
        String name = "Второй админ " + System.nanoTime();
        long other = repo.createUser(name, "старый-пароль-длинный", Repo.ADMIN);
        String newPw = "новая фраза для входа";
        // weak password and mismatch — an error, the password stays
        assertThat(postPage(admin, "/users/" + other + "/password", "password", "123", "password2", "123")).contains("class=\"error\"");
        assertThat(postPage(admin, "/users/" + other + "/password", "password", newPw, "password2", newPw + "!"))
                .contains("Пароли не совпадают");
        assertThat(Passwords.matches("старый-пароль-длинный", repo.user(other).passwordHash())).isTrue();
        // correct — the password is changed (the current one is not needed)
        mvc.perform(post("/users/" + other + "/password").session(login(admin)).param("password", newPw).param("password2", newPw))
                .andExpect(status().is3xxRedirection());
        assertThat(Passwords.matches(newPw, repo.user(other).passwordHash())).isTrue();
        // own password is not changed this way — only on the "Password" tab
        String before = repo.user(admin).passwordHash();
        mvc.perform(post("/users/" + admin + "/password").session(login(admin)).param("password", newPw).param("password2", newPw))
                .andExpect(redirectedUrl("/account"));
        assertThat(repo.user(admin).passwordHash()).isEqualTo(before);
        // not an administrator — forbidden
        long plain = newUser();
        mvc.perform(post("/users/" + other + "/password").session(login(plain)).param("password", newPw).param("password2", newPw))
                .andExpect(status().is4xxClientError());
        // page: a key for everyone, for yourself — a link to "Password", the form list does not include yourself, selection by link
        String users = page(admin, "/users?pw=" + other);
        assertThat(users).contains("href=\"/account\"").contains("/users?pw=" + other + "#pw-form")
                .containsPattern("<option value=\"" + other + "\"[^>]*selected")
                .doesNotContain("<option value=\"" + admin + "\"");
    }

    @Test
    void rememberMeSignsInWithoutPasswordUntilLogoutOrPasswordChange() throws Exception {
        long uid = newUser();
        String name = repo.user(uid).name();
        // without the tick — no cookie
        var plain = mvc.perform(post("/login").param("name", name).param("password", "Kwq7-zmlpt-Rvx9")).andReturn().getResponse();
        assertThat(plain.getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith(Remember.COOKIE + "="));
        // with the tick — a cookie for 90 days, not available to scripts; the database has only the hash
        var res = mvc.perform(post("/login").param("name", name).param("password", "Kwq7-zmlpt-Rvx9").param("remember", "true"))
                .andReturn().getResponse();
        String set = res.getHeaders("Set-Cookie").stream().filter(c -> c.startsWith(Remember.COOKIE + "=")).findFirst().orElseThrow();
        assertThat(set).contains("Max-Age=7776000").contains("HttpOnly").contains("SameSite=Lax");
        String token = set.substring(Remember.COOKIE.length() + 1, set.indexOf(';'));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remember_tokens WHERE hash = ?", Integer.class, Remember.hash(token))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remember_tokens WHERE hash = ?", Integer.class, token)).isZero();
        var cookie = new jakarta.servlet.http.Cookie(Remember.COOKIE, token);
        // a new browser session without a session — straight into the application; the login page leads there too
        mvc.perform(get("/").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/login").cookie(cookie)).andExpect(redirectedUrl("/"));
        // someone else's or a broken key — to the login page
        mvc.perform(get("/").cookie(new jakarta.servlet.http.Cookie(Remember.COOKIE, "x" + token))).andExpect(redirectedUrl("/login"));
        // a password change (by an administrator or the user) — the key no longer works
        repo.setPassword(uid, "совсем другая фраза для входа");
        mvc.perform(get("/").cookie(cookie)).andExpect(redirectedUrl("/login"));
        // signing out — this computer is forgotten
        res = mvc.perform(post("/login").param("name", name).param("password", "совсем другая фраза для входа").param("remember", "true"))
                .andReturn().getResponse();
        set = res.getHeaders("Set-Cookie").stream().filter(c -> c.startsWith(Remember.COOKIE + "=")).findFirst().orElseThrow();
        var cookie2 = new jakarta.servlet.http.Cookie(Remember.COOKIE, set.substring(Remember.COOKIE.length() + 1, set.indexOf(';')));
        var out = mvc.perform(post("/logout").session(login(uid)).cookie(cookie2)).andReturn().getResponse();
        assertThat(out.getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith(Remember.COOKIE + "=;") && c.contains("Max-Age=0"));
        mvc.perform(get("/").cookie(cookie2)).andExpect(redirectedUrl("/login"));
    }
}
