package app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class WebController {

    private final Repo repo;

    public WebController(Repo repo) {
        this.repo = repo;
    }

    // ---------- Коллекции ----------

    /** Коллекций на одной странице главной. */
    private static final int COLLECTIONS_PER_PAGE = 10;

    @GetMapping("/")
    public String index(Model m, @RequestParam(defaultValue = "1") int page) {
        List<Entry> all = repo.collections();
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
    public String newCollectionForm(Model m) {
        return form(m, null, false, Set.of(), null, "Новая коллекция", "/");
    }

    @Transactional
    @PostMapping("/collections/new")
    public String createCollection(@RequestParam String name,
                                   @RequestParam(required = false) String kind,
                                   @RequestParam(required = false) MultipartFile image) {
        long id = repo.createCollection();
        repo.saveCollection(id, name, kind, image, false);
        return "redirect:/collections/" + id;
    }

    /** Столбец таблицы объектов: ключ сортировки, заголовок, css-класс. */
    public record Col(String key, String label, String css) {}

    /** Размеры страницы для выпадающего списка: 25, 50, … 200. */
    private static final List<Integer> PAGE_SIZES = IntStream.rangeClosed(1, 8).map(i -> i * 25).boxed().toList();

    @GetMapping("/collections/{id}")
    public String collection(@PathVariable long id, Model m,
                             @RequestParam(defaultValue = "name") String sort,
                             @RequestParam(defaultValue = "asc") String dir,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "50") int size) {
        Entry c = repo.collection(id);
        Set<String> fields = repo.itemFields(c.kind());
        m.addAttribute("statusColors", repo.statusColors(c.kind()));

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
    public ResponseEntity<byte[]> collectionImage(@PathVariable long id) {
        Map<String, Object> row = repo.collectionImage(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType((String) row.get("image_mime")))
                .body((byte[]) row.get("image"));
    }

    @GetMapping("/collections/{id}/edit")
    public String editCollectionForm(@PathVariable long id, Model m) {
        Entry c = repo.collection(id);
        return form(m, c, false, Set.of(), c.hasImage() ? "/collections/" + id + "/image" : null,
                "Редактировать коллекцию", "/collections/" + id);
    }

    @PostMapping("/collections/{id}/edit")
    public String editCollection(@PathVariable long id, @RequestParam String name,
                                 @RequestParam(required = false) String kind,
                                 @RequestParam(required = false) MultipartFile image,
                                 @RequestParam(name = "remove_image", required = false) String removeImage) {
        repo.collection(id); // 404, если нет
        repo.saveCollection(id, name, kind, image, removeImage != null);
        return "redirect:/collections/" + id;
    }

    @PostMapping("/collections/{id}/delete")
    public String deleteCollection(@PathVariable long id) {
        repo.delete("collections", id);
        return "redirect:/";
    }

    // ---------- Объекты ----------

    @GetMapping("/collections/{cid}/items/new")
    public String newItemForm(@PathVariable long cid, Model m) {
        Set<String> fields = repo.itemFields(repo.collection(cid).kind());
        return form(m, null, true, fields, null, "Новый объект", "/collections/" + cid);
    }

    @Transactional
    @PostMapping("/collections/{cid}/items/new")
    public String createItem(@PathVariable long cid, @RequestParam String name,
                             @RequestParam(required = false) String platform,
                             @RequestParam(required = false) String format,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) Integer price) {
        Entry c = repo.collection(cid);
        long id = repo.createItem(cid);
        repo.saveItem(id, c.kind(), name, platform, format, status, price);
        return "redirect:/collections/" + cid;
    }

    @GetMapping("/items/{id}/edit")
    public String editItemForm(@PathVariable long id, Model m, @RequestParam(required = false) String view) {
        Entry it = repo.item(id);
        Set<String> fields = repo.itemFields(repo.collection(it.collectionId()).kind());
        m.addAttribute("view", view);
        return form(m, it, true, fields, null, "Редактировать объект", collectionUrl(it.collectionId(), view));
    }

    @PostMapping("/items/{id}/edit")
    public String editItem(@PathVariable long id, @RequestParam String name,
                           @RequestParam(required = false) String platform,
                           @RequestParam(required = false) String format,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Integer price,
                           @RequestParam(required = false) String view) {
        Entry it = repo.item(id);
        repo.saveItem(id, repo.collection(it.collectionId()).kind(), name, platform, format, status, price);
        return "redirect:" + collectionUrl(it.collectionId(), view);
    }

    @PostMapping("/items/{id}/delete")
    public String deleteItem(@PathVariable long id, @RequestParam(required = false) String view) {
        Entry it = repo.item(id);
        repo.delete("items", id);
        return "redirect:" + collectionUrl(it.collectionId(), view);
    }

    // ---------- Настройки: типы коллекций ----------

    @GetMapping("/kinds")
    public String kinds(Model m) {
        m.addAttribute("kinds", repo.kinds());
        m.addAttribute("fieldNames", Repo.FIELDS);
        return "kinds";
    }

    @GetMapping("/kinds/new")
    public String newKindForm(Model m) {
        return kindForm(m, null, "Новый тип коллекции");
    }

    @Transactional
    @PostMapping("/kinds/new")
    public String createKind(@RequestParam String name, @RequestParam(required = false) List<String> fields,
                             @RequestParam(name = "st", required = false) List<String> statuses,
                             @RequestParam(name = "stc", required = false) List<String> colors) {
        repo.saveKind(null, name, fields, statuses, colors);
        return "redirect:/kinds";
    }

    @GetMapping("/kinds/{id}/edit")
    public String editKindForm(@PathVariable long id, Model m) {
        return kindForm(m, repo.kind(id), "Тип коллекции");
    }

    @Transactional
    @PostMapping("/kinds/{id}/edit")
    public String editKind(@PathVariable long id, @RequestParam String name,
                           @RequestParam(required = false) List<String> fields,
                           @RequestParam(name = "st", required = false) List<String> statuses,
                           @RequestParam(name = "stc", required = false) List<String> colors) {
        repo.saveKind(id, name, fields, statuses, colors);
        return "redirect:/kinds";
    }

    @PostMapping("/kinds/{id}/delete")
    public String deleteKind(@PathVariable long id) {
        repo.deleteKind(id);
        return "redirect:/kinds";
    }

    /** Сколько пустых строк «статус — цвет» добавлять в форму типа. */
    private static final int EMPTY_STATUS_ROWS = 3;

    private String kindForm(Model m, Kind kind, String title) {
        m.addAttribute("kind", kind);
        m.addAttribute("statusColors", kind == null ? Map.of() : kind.statusColors());
        m.addAttribute("emptyRows", IntStream.range(0, EMPTY_STATUS_ROWS).boxed().toList());
        // подсказки: стандартные статусы и те, что уже есть у объектов этого типа
        Set<String> hints = new java.util.TreeSet<>(Repo.alphabetical());
        hints.addAll(List.of("Complete", "Incomplete"));
        if (kind != null) hints.addAll(repo.usedStatuses(kind.name()));
        m.addAttribute("statusHints", hints);
        m.addAttribute("fieldNames", Repo.FIELDS);
        m.addAttribute("title", title);
        return "kind-form";
    }

    // ---------- вспомогательное ----------

    private String form(Model m, Entry obj, boolean isItem, Set<String> fields, String imgUrl, String title, String back) {
        m.addAttribute("obj", obj);
        m.addAttribute("isItem", isItem);
        m.addAttribute("fields", fields);
        m.addAttribute("kinds", repo.kinds().stream().map(Kind::name).toList());
        m.addAttribute("imgUrl", imgUrl);
        m.addAttribute("title", title);
        m.addAttribute("back", back);
        return "form";
    }
}
