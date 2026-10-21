package app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class WebController {

    private final Repo repo;

    public WebController(Repo repo) {
        this.repo = repo;
    }

    // ---------- Вход, регистрация, пароль ----------
    // Кто вошёл — хранится в сессии ("uid"); без входа страницы недоступны (см. WebConfig).

    /** Минимальная длина нового пароля. */
    private static final int MIN_PASSWORD = 4;

    /** Имя вошедшего пользователя для шапки страниц. */
    @ModelAttribute("userName")
    public String userName(HttpSession session) {
        Object uid = session.getAttribute("uid");
        Repo.User u = uid == null ? null : repo.user((Long) uid);
        return u == null ? null : u.name();
    }

    @GetMapping("/login")
    public String loginForm() {
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String name, @RequestParam(defaultValue = "") String password,
                        HttpServletRequest request, Model m) {
        Repo.User u = repo.userByName(name);
        if (u == null || !Passwords.matches(password, u.passwordHash())) {
            m.addAttribute("error", "Неверное имя пользователя или пароль");
            m.addAttribute("name", name);
            return "login";
        }
        signIn(request, u.id());
        return "redirect:/";
    }

    @GetMapping("/register")
    public String registerForm() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String name, @RequestParam(defaultValue = "") String password,
                           @RequestParam(defaultValue = "") String password2, HttpServletRequest request, Model m) {
        String error = null;
        if (name.isBlank()) error = "Укажите имя пользователя";
        else if (repo.userByName(name) != null) error = "Пользователь «" + name.trim() + "» уже есть";
        else if (password.length() < MIN_PASSWORD) error = "Пароль должен быть не короче " + MIN_PASSWORD + " символов";
        else if (!password.equals(password2)) error = "Пароли не совпадают";
        if (error != null) {
            m.addAttribute("error", error);
            m.addAttribute("name", name);
            return "register";
        }
        signIn(request, repo.createUser(name, password));
        return "redirect:/";
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    @PostMapping("/account/password")
    public String changePassword(@SessionAttribute("uid") long uid, @RequestParam(defaultValue = "") String current,
                                 @RequestParam(defaultValue = "") String password,
                                 @RequestParam(defaultValue = "") String password2, Model m) {
        String error = null;
        if (!Passwords.matches(current, repo.user(uid).passwordHash())) error = "Текущий пароль указан неверно";
        else if (password.length() < MIN_PASSWORD) error = "Новый пароль должен быть не короче " + MIN_PASSWORD + " символов";
        else if (!password.equals(password2)) error = "Новые пароли не совпадают";
        if (error != null) {
            m.addAttribute("passwordError", error);
            return kinds(uid, m);
        }
        repo.setPassword(uid, password);
        return "redirect:/kinds?passwordChanged";
    }

    /** Вход: новая сессия (защита от подмены сессии) и id пользователя в ней. */
    private static void signIn(HttpServletRequest request, long uid) {
        HttpSession old = request.getSession(false);
        if (old != null) old.invalidate();
        request.getSession(true).setAttribute("uid", uid);
    }

    // ---------- Коллекции ----------

    /** Коллекций на одной странице главной. */
    private static final int COLLECTIONS_PER_PAGE = 10;

    @GetMapping("/")
    public String index(@SessionAttribute("uid") long uid, Model m, @RequestParam(defaultValue = "1") int page) {
        List<Entry> all = repo.collections(uid);
        int total = all.size();
        int pages = Math.max(1, (total + COLLECTIONS_PER_PAGE - 1) / COLLECTIONS_PER_PAGE);
        page = Math.min(Math.max(page, 1), pages);
        int from = (page - 1) * COLLECTIONS_PER_PAGE;
        m.addAttribute("collections", all.subList(from, Math.min(from + COLLECTIONS_PER_PAGE, total)));
        m.addAttribute("page", page);
        m.addAttribute("pages", pages);
        m.addAttribute("pageLinks", pageLinks(page, pages));
        return "index";
    }

    @GetMapping("/collections/new")
    public String newCollectionForm(@SessionAttribute("uid") long uid, Model m) {
        return form(uid, m, null, false, Set.of(), null, "Новая коллекция", "/");
    }

    @Transactional
    @PostMapping("/collections/new")
    public String createCollection(@SessionAttribute("uid") long uid, @RequestParam String name,
                                   @RequestParam(required = false) String kind,
                                   @RequestParam(required = false) MultipartFile image) {
        long id = repo.createCollection(uid);
        repo.saveCollection(uid, id, name, kind, image, false);
        return "redirect:/collections/" + id;
    }

    /** Столбец таблицы объектов: ключ сортировки, заголовок, css-класс. */
    public record Col(String key, String label, String css) {}

    /** Размеры страницы для выпадающего списка: 25, 50, … 200. */
    private static final List<Integer> PAGE_SIZES = IntStream.rangeClosed(1, 8).map(i -> i * 25).boxed().toList();

    @GetMapping("/collections/{id}")
    public String collection(@SessionAttribute("uid") long uid, @PathVariable long id, Model m,
                             @RequestParam(defaultValue = "name") String sort,
                             @RequestParam(defaultValue = "asc") String dir,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "50") int size) {
        Entry c = repo.collection(uid, id);
        Set<String> fields = repo.itemFields(uid, c.kind());
        m.addAttribute("statusColors", repo.statusColors(uid, c.kind()));

        List<Col> cols = new ArrayList<>();
        cols.add(new Col("name", "Название", "title"));
        if (fields.contains("platform")) cols.add(new Col("platform", "Платформа", ""));
        if (fields.contains("format")) cols.add(new Col("format", "Формат", ""));
        if (fields.contains("status")) cols.add(new Col("status", "Статус", ""));
        if (fields.contains("price")) cols.add(new Col("price", "₽", "eye")); // цена видна в подсказке у иконки глаза

        String requested = sort;
        if (cols.stream().noneMatch(col -> col.key().equals(requested))) sort = "name";
        boolean desc = "desc".equals(dir);
        if (!PAGE_SIZES.contains(size)) size = 50;
        int total = repo.countItems(id);
        int pages = Math.max(1, (total + size - 1) / size);
        page = Math.min(Math.max(page, 1), pages);

        m.addAttribute("c", c);
        m.addAttribute("fields", fields);
        m.addAttribute("cols", cols);
        m.addAttribute("items", repo.items(id, sort, desc, size, (page - 1) * size));
        m.addAttribute("sort", sort);
        m.addAttribute("dir", desc ? "desc" : "asc");
        m.addAttribute("page", page);
        m.addAttribute("pages", pages);
        m.addAttribute("size", size);
        m.addAttribute("sizes", PAGE_SIZES);
        m.addAttribute("total", total);
        m.addAttribute("from", (page - 1) * size + 1);
        m.addAttribute("to", Math.min(page * size, total));
        m.addAttribute("pageLinks", pageLinks(page, pages));
        // Текущий вид списка — чтобы после изменения/удаления объекта вернуться туда же
        m.addAttribute("view", "sort=" + sort + "&dir=" + (desc ? "desc" : "asc") + "&page=" + page + "&size=" + size);
        return "collection";
    }

    /** Номера страниц для навигации: первая, последняя и соседние с текущей; 0 — пропуск («…»). */
    private static List<Integer> pageLinks(int page, int pages) {
        List<Integer> res = new ArrayList<>();
        for (int p = 1; p <= pages; p++) {
            if (p == 1 || p == pages || Math.abs(p - page) <= 2) res.add(p);
            else if (res.get(res.size() - 1) != 0) res.add(0);
        }
        return res;
    }

    /** Адрес страницы коллекции с сохранённым видом списка (view — только из нашей же ссылки). */
    private static String collectionUrl(long cid, String view) {
        return "/collections/" + cid + (view != null && view.matches("[A-Za-z0-9=&]*") && !view.isEmpty() ? "?" + view : "");
    }

    @GetMapping("/collections/{id}/image")
    public ResponseEntity<byte[]> collectionImage(@SessionAttribute("uid") long uid, @PathVariable long id) {
        Map<String, Object> row = repo.collectionImage(uid, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType((String) row.get("image_mime")))
                .body((byte[]) row.get("image"));
    }

    @GetMapping("/collections/{id}/edit")
    public String editCollectionForm(@SessionAttribute("uid") long uid, @PathVariable long id, Model m) {
        Entry c = repo.collection(uid, id);
        return form(uid, m, c, false, Set.of(), c.hasImage() ? "/collections/" + id + "/image" : null,
                "Редактировать коллекцию", "/collections/" + id);
    }

    @PostMapping("/collections/{id}/edit")
    public String editCollection(@SessionAttribute("uid") long uid, @PathVariable long id, @RequestParam String name,
                                 @RequestParam(required = false) String kind,
                                 @RequestParam(required = false) MultipartFile image,
                                 @RequestParam(name = "remove_image", required = false) String removeImage) {
        repo.collection(uid, id); // 404, если нет
        repo.saveCollection(uid, id, name, kind, image, removeImage != null);
        return "redirect:/collections/" + id;
    }

    @PostMapping("/collections/{id}/delete")
    public String deleteCollection(@SessionAttribute("uid") long uid, @PathVariable long id) {
        repo.collection(uid, id); // 404, если коллекция чужая
        repo.delete("collections", id);
        return "redirect:/";
    }

    // ---------- Объекты ----------

    @GetMapping("/collections/{cid}/items/new")
    public String newItemForm(@SessionAttribute("uid") long uid, @PathVariable long cid, Model m) {
        Set<String> fields = repo.itemFields(uid, repo.collection(uid, cid).kind());
        return form(uid, m, null, true, fields, null, "Новый объект", "/collections/" + cid);
    }

    @Transactional
    @PostMapping("/collections/{cid}/items/new")
    public String createItem(@SessionAttribute("uid") long uid, @PathVariable long cid, @RequestParam String name,
                             @RequestParam(required = false) String platform,
                             @RequestParam(required = false) String format,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) Integer price) {
        Entry c = repo.collection(uid, cid);
        long id = repo.createItem(cid);
        repo.saveItem(uid, id, c.kind(), name, platform, format, status, price);
        return "redirect:/collections/" + cid;
    }

    @GetMapping("/items/{id}/edit")
    public String editItemForm(@SessionAttribute("uid") long uid, @PathVariable long id, Model m, @RequestParam(required = false) String view) {
        Entry it = repo.item(uid, id);
        Set<String> fields = repo.itemFields(uid, repo.collection(uid, it.collectionId()).kind());
        m.addAttribute("view", view);
        return form(uid, m, it, true, fields, null, "Редактировать объект", collectionUrl(it.collectionId(), view));
    }

    @PostMapping("/items/{id}/edit")
    public String editItem(@SessionAttribute("uid") long uid, @PathVariable long id, @RequestParam String name,
                           @RequestParam(required = false) String platform,
                           @RequestParam(required = false) String format,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Integer price,
                           @RequestParam(required = false) String view) {
        Entry it = repo.item(uid, id);
        repo.saveItem(uid, id, repo.collection(uid, it.collectionId()).kind(), name, platform, format, status, price);
        return "redirect:" + collectionUrl(it.collectionId(), view);
    }

    @PostMapping("/items/{id}/delete")
    public String deleteItem(@SessionAttribute("uid") long uid, @PathVariable long id, @RequestParam(required = false) String view) {
        Entry it = repo.item(uid, id);
        repo.delete("items", id);
        return "redirect:" + collectionUrl(it.collectionId(), view);
    }

    // ---------- Настройки: типы коллекций ----------

    @GetMapping("/kinds")
    public String kinds(@SessionAttribute("uid") long uid, Model m) {
        m.addAttribute("kinds", repo.kinds(uid));
        m.addAttribute("fieldNames", Repo.FIELDS);
        return "kinds";
    }

    @GetMapping("/kinds/new")
    public String newKindForm(@SessionAttribute("uid") long uid, Model m) {
        return kindForm(uid, m, null, "Новый тип коллекции");
    }

    @Transactional
    @PostMapping("/kinds/new")
    public String createKind(@SessionAttribute("uid") long uid, @RequestParam String name, @RequestParam(required = false) List<String> fields,
                             @RequestParam(name = "st", required = false) List<String> statuses,
                             @RequestParam(name = "stc", required = false) List<String> colors) {
        repo.saveKind(uid, null, name, fields, statuses, colors);
        return "redirect:/kinds";
    }

    @GetMapping("/kinds/{id}/edit")
    public String editKindForm(@SessionAttribute("uid") long uid, @PathVariable long id, Model m) {
        return kindForm(uid, m, repo.kind(uid, id), "Тип коллекции");
    }

    @Transactional
    @PostMapping("/kinds/{id}/edit")
    public String editKind(@SessionAttribute("uid") long uid, @PathVariable long id, @RequestParam String name,
                           @RequestParam(required = false) List<String> fields,
                           @RequestParam(name = "st", required = false) List<String> statuses,
                           @RequestParam(name = "stc", required = false) List<String> colors) {
        repo.saveKind(uid, id, name, fields, statuses, colors);
        return "redirect:/kinds";
    }

    @PostMapping("/kinds/{id}/delete")
    public String deleteKind(@SessionAttribute("uid") long uid, @PathVariable long id) {
        repo.deleteKind(uid, id);
        return "redirect:/kinds";
    }

    /** Сколько пустых строк «статус — цвет» добавлять в форму типа. */
    private static final int EMPTY_STATUS_ROWS = 3;

    private String kindForm(long uid, Model m, Kind kind, String title) {
        m.addAttribute("kind", kind);
        m.addAttribute("statusColors", kind == null ? Map.of() : kind.statusColors());
        m.addAttribute("emptyRows", IntStream.range(0, EMPTY_STATUS_ROWS).boxed().toList());
        // подсказки: стандартные статусы и те, что уже есть у объектов этого типа
        Set<String> hints = new java.util.TreeSet<>(Repo.alphabetical());
        hints.addAll(List.of("Complete", "Incomplete"));
        if (kind != null) hints.addAll(repo.usedStatuses(uid, kind.name()));
        m.addAttribute("statusHints", hints);
        m.addAttribute("fieldNames", Repo.FIELDS);
        m.addAttribute("title", title);
        return "kind-form";
    }

    // ---------- вспомогательное ----------

    private String form(long uid, Model m, Entry obj, boolean isItem, Set<String> fields, String imgUrl, String title, String back) {
        m.addAttribute("obj", obj);
        m.addAttribute("isItem", isItem);
        m.addAttribute("fields", fields);
        m.addAttribute("kinds", repo.kinds(uid).stream().map(Kind::name).toList());
        m.addAttribute("imgUrl", imgUrl);
        m.addAttribute("title", title);
        m.addAttribute("back", back);
        return "form";
    }
}
