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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class WebController {

    private final Repo repo;
    private final LoginGuard guard;
    private final ImageImport images;
    private final CacheClear cacheClear;
    private final OldGames oldGames;
    private final Backup backup;
    private final Updates updates;
    private final XlsxImport xlsxImport;
    private final XlsxExport xlsxExport;

    public WebController(Repo repo, LoginGuard guard, ImageImport images, CacheClear cacheClear, OldGames oldGames,
                         Backup backup, Updates updates, XlsxImport xlsxImport, XlsxExport xlsxExport) {
        this.xlsxImport = xlsxImport;
        this.xlsxExport = xlsxExport;
        this.backup = backup;
        this.updates = updates;
        this.repo = repo;
        this.guard = guard;
        this.images = images;
        this.cacheClear = cacheClear;
        this.oldGames = oldGames;
    }

    // ---------- Вход, пользователи, пароль ----------
    // Кто вошёл — хранится в сессии ("uid"); без входа страницы недоступны (см. WebConfig).

    /** Атрибут сессии с хешем пароля на момент входа: после смены пароля старые сессии недействительны (WebConfig). */
    static final String SESSION_PW = "pw";

    /** Хеш для проверки пароля несуществующего пользователя — чтобы по времени ответа нельзя было узнать имена. */
    private static final String DUMMY_HASH = Passwords.hash("dummy-password");

    @GetMapping("/login")
    public String loginForm(Model m) {
        m.addAttribute("setup", repo.countUsers() == 0);
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String name, @RequestParam(defaultValue = "") String password,
                        HttpServletRequest request, Model m) {
        // Защита от подбора: адрес + имя (5 попыток) и адрес по любым именам (30 попыток)
        String ip = "ip:" + request.getRemoteAddr();
        String ipName = ip + "|" + name.trim().toLowerCase(java.util.Locale.ROOT);
        long locked = guard.lockedMinutes(ipName, ip);
        if (locked > 0) {
            m.addAttribute("error", I18n.t("login.locked", locked));
            m.addAttribute("name", name);
            return loginForm(m);
        }
        Repo.User u = repo.userByName(name);
        boolean ok = Passwords.matches(password, u == null ? DUMMY_HASH : u.passwordHash()) && u != null;
        if (!ok) {
            guard.fail(ipName, LoginGuard.MAX_FAILS);
            guard.fail(ip, LoginGuard.MAX_FAILS_PER_IP);
            m.addAttribute("error", I18n.t("login.wrong"));
            m.addAttribute("name", name);
            return loginForm(m);
        }
        guard.success(ipName);
        signIn(request, u.id());
        return "redirect:/";
    }

    // Первый запуск (пользователей ещё нет): на странице входа создаётся администратор.

    @PostMapping("/setup")
    public String setup(@RequestParam String name, @RequestParam(defaultValue = "") String password,
                        @RequestParam(defaultValue = "") String password2, HttpServletRequest request, Model m) {
        if (repo.countUsers() > 0) return "redirect:/login";
        String error = newUserError(name, password, password2);
        if (error != null) {
            m.addAttribute("error", error);
            m.addAttribute("name", name);
            return loginForm(m);
        }
        signIn(request, repo.createUser(name, password, Repo.ADMIN));
        return "redirect:/";
    }

    // ---------- Настройки: вкладки «Пароль» и «Пользователи» (вкладка «Типы коллекций» — /kinds) ----------

    @GetMapping("/account")
    public String account(@SessionAttribute("uid") long uid) {
        return "account";
    }

    // Вкладка «Отображение»: показывать цены открыто или за иконкой глаза; сколько коллекций и объектов на странице
    @GetMapping("/display")
    public String display(@SessionAttribute("uid") long uid, Model m) {
        m.addAttribute("showPrices", repo.showPrices(uid));
        m.addAttribute("pageSizes", repo.pageSizes(uid));
        m.addAttribute("themes", Repo.THEMES);
        m.addAttribute("minCollections", Repo.PageSizes.MIN_COLLECTIONS);
        m.addAttribute("maxCollections", Repo.PageSizes.MAX_COLLECTIONS);
        m.addAttribute("minItems", Repo.PageSizes.MIN_ITEMS);
        m.addAttribute("maxItems", Repo.PageSizes.MAX_ITEMS);
        return "display";
    }

    @PostMapping("/display")
    public String saveDisplay(@SessionAttribute("uid") long uid, @RequestParam(defaultValue = "false") boolean showPrices,
                              @RequestParam String collectionsPerPage, @RequestParam String itemsPerPage,
                              @RequestParam(required = false) String theme) {
        if (theme != null) repo.setThemePair(uid, theme);
        int collections = pageSize(collectionsPerPage, Repo.PageSizes.MIN_COLLECTIONS, Repo.PageSizes.MAX_COLLECTIONS);
        int items = pageSize(itemsPerPage, Repo.PageSizes.MIN_ITEMS, Repo.PageSizes.MAX_ITEMS);
        repo.setShowPrices(uid, showPrices);
        repo.setPageSizes(uid, new Repo.PageSizes(collections, items));
        return "redirect:/display?saved";
    }

    /** Число на странице из формы «Отображение»: целое в пределах min…max, иначе 400. */
    private static int pageSize(String value, int min, int max) {
        String v = value == null ? "" : value.trim();
        if (v.matches("\\d{1,4}")) {
            int n = Integer.parseInt(v);
            if (n >= min && n <= max) return n;
        }
        throw Fail.badRequest("display.badPageSize", min, max);
    }

    // Вкладка «Импорт»: картинки объектов коллекции из Steam или Discogs (в фоне, прогресс — /import/status)
    @GetMapping("/import")
    public String importPage(@SessionAttribute("uid") long uid, Model m) {
        List<Entry> collections = repo.collections(uid);
        m.addAttribute("collections", collections);
        // профиль типа каждой коллекции — для выбора источника по умолчанию
        Map<String, String> profileOf = new java.util.HashMap<>();
        for (Kind k : repo.kinds(uid)) profileOf.put(k.name(), k.profile());
        Map<Long, String> profiles = new java.util.HashMap<>();
        for (Entry c : collections) profiles.put(c.id(), profileOf.getOrDefault(c.kind(), "other"));
        m.addAttribute("profiles", profiles);
        m.addAttribute("job", images.job(uid));
        String token = repo.token(uid, ImageImport.DISCOGS);
        m.addAttribute("discogsReady", token != null);
        // сам токен на страницу не выводится — только последние 4 символа
        m.addAttribute("discogsTokenEnd", token == null ? null : token.substring(Math.max(0, token.length() - 4)));
        m.addAttribute("kinds", repo.kinds(uid));
        return "import";
    }

    /** Загрузка коллекций из Excel: каждый лист — новая коллекция; итог или ошибка — на странице «Импорт», в блоке загрузки. */
    @PostMapping("/import/excel")
    public String importExcel(@SessionAttribute("uid") long uid, @RequestParam(required = false) MultipartFile file,
                              @RequestParam(defaultValue = "") String kind,
                              org.springframework.web.servlet.mvc.support.RedirectAttributes ra) throws java.io.IOException {
        if (file == null || file.isEmpty()) {
            ra.addFlashAttribute("excelError", I18n.t("excel.noFile"));
            return "redirect:/import#excel";
        }
        try (java.io.InputStream in = file.getInputStream()) {
            ra.addFlashAttribute("excelResult", xlsxImport.load(uid, in, kind));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            ra.addFlashAttribute("excelError", e.getReason());
        }
        return "redirect:/import#excel";
    }

    /** Личный токен Discogs пользователя: сохранить или удалить (кнопка «Удалить токен»). */
    @PostMapping("/import/discogs-token")
    public String discogsToken(@SessionAttribute("uid") long uid, @RequestParam(defaultValue = "") String token,
                               @RequestParam(defaultValue = "false") boolean delete) {
        token = delete ? "" : token.trim();
        if (!delete && token.isEmpty()) return "redirect:/import"; // пустое поле — токен не меняется
        // токен Discogs — латинские буквы и цифры; ограничение длины — с запасом
        if (!token.isEmpty() && !token.matches("[A-Za-z0-9]{10,200}"))
            throw Fail.badRequest("import.token.bad");
        repo.setToken(uid, ImageImport.DISCOGS, token);
        return "redirect:/import?token=" + (token.isEmpty() ? "deleted" : "saved");
    }

    @PostMapping("/import/start")
    public String importStart(@SessionAttribute("uid") long uid, @RequestParam long collection,
                              @RequestParam(defaultValue = ImageImport.STEAM) String source,
                              @RequestParam(defaultValue = "false") boolean onlyMissing,
                              @RequestParam(defaultValue = "false") boolean loadImages,
                              @RequestParam(defaultValue = "false") boolean loadDescriptions,
                              @RequestParam(defaultValue = "screenshot") String firstImage,
                              @RequestParam(defaultValue = "5") int imageCount) {
        Entry c = repo.collection(uid, collection); // чужая коллекция — 404
        // пока у коллекции удаляются картинки («Кэш»), импорт в неё не запускается
        if (cacheClear.active(collection))
            throw Fail.conflict("import.cacheBusy");
        if (!ImageImport.SOURCES.containsKey(source))
            throw Fail.badRequest("import.badSource");
        // Обложки Discogs — только с токеном; без него — только описания
        String discogsToken = repo.token(uid, ImageImport.DISCOGS);
        if (source.equals(ImageImport.DISCOGS) && discogsToken == null) loadImages = false;
        boolean descriptions = loadDescriptions;
        if (!loadImages && !descriptions)
            throw Fail.badRequest("import.nothing");
        images.start(uid, c, onlyMissing, source, loadImages, descriptions, "screenshot".equals(firstImage), imageCount,
                discogsToken);
        return "redirect:/import";
    }

    @PostMapping("/import/cancel")
    public String importCancel(@SessionAttribute("uid") long uid) {
        images.cancel(uid);
        return "redirect:/import";
    }

    @GetMapping("/import/status")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> importStatus(@SessionAttribute("uid") long uid) {
        ImageImport.Job j = images.job(uid);
        if (j == null) return Map.of("state", "none");
        return Map.of("state", j.state, "total", j.total, "processed", j.processed, "found", j.found, "loaded", j.loaded,
                "described", j.described, "notFound", j.notFound, "errors", j.errors, "current", j.current, "message", j.message);
    }

    // Вкладка «Кэш»: удалить все картинки объектов выбранной коллекции (в фоне, прогресс — /cache/status)
    @GetMapping("/cache")
    public String cachePage(@SessionAttribute("uid") long uid, Model m) {
        m.addAttribute("collections", repo.collections(uid));
        m.addAttribute("imageCounts", repo.collectionImageCounts(uid));
        m.addAttribute("job", cacheClear.job(uid));
        return "cache";
    }

    @PostMapping("/cache/clear")
    public String cacheClear(@SessionAttribute("uid") long uid, @RequestParam long collection) {
        Entry c = repo.collection(uid, collection); // чужая коллекция — 404
        ImageImport.Job j = images.job(uid);
        // пока идёт импорт в эту коллекцию, он сразу дописал бы картинки обратно
        if (j != null && j.active() && j.collectionId == collection)
            throw Fail.conflict("cache.importBusy");
        cacheClear.start(uid, c, repo.collectionImageCounts(uid).getOrDefault(collection, 0)); // уже идёт — не запускается
        return "redirect:/cache";
    }

    @GetMapping("/cache/status")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> cacheStatus(@SessionAttribute("uid") long uid) {
        CacheClear.Job j = cacheClear.job(uid);
        if (j == null) return Map.of("state", "none");
        return Map.of("state", j.state, "total", j.total, "done", j.done);
    }

    // Вкладка «Экспорт»: выбранные коллекции в файл Excel, каждая на своём листе (картинки не выгружаются)
    @GetMapping("/export")
    public String exportPage(@SessionAttribute("uid") long uid, Model m) {
        m.addAttribute("collections", repo.collections(uid));
        return "export";
    }

    @PostMapping("/export")
    public Object export(@SessionAttribute("uid") long uid, @RequestParam(name = "c", required = false) List<Long> ids,
                         Model m) throws java.io.IOException {
        // Только свои коллекции, в порядке списка (по алфавиту)
        List<Entry> chosen = repo.collections(uid).stream().filter(c -> ids != null && ids.contains(c.id())).toList();
        if (chosen.isEmpty()) {
            m.addAttribute("collections", repo.collections(uid));
            m.addAttribute("error", I18n.t("export.none"));
            return "export";
        }
        List<Xlsx.Sheet> sheets = xlsxExport.sheets(uid, chosen);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Xlsx.write(out, sheets);
        String file = (chosen.size() == 1 ? chosen.get(0).name() : I18n.t("export.file")) + " " + java.time.LocalDate.now() + ".xlsx";
        return ResponseEntity.ok()
                // Имя файла: латиницей для старых браузеров и по-русски (filename*, RFC 5987) для остальных
                .header("Content-Disposition", "attachment; filename=\"collections-" + java.time.LocalDate.now()
                        + ".xlsx\"; filename*=UTF-8''" + java.net.URLEncoder.encode(file, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20"))
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(out.toByteArray());
    }

    /** Версия приложения (из pom.xml, см. app.version в application.properties). */
    @org.springframework.beans.factory.annotation.Value("${app.version}")
    private String appVersion;

    /** Автор, email и дата обновления — дата сборки (app.author, app.email, app.build-date в application.properties). */
    @org.springframework.beans.factory.annotation.Value("${app.author}")
    private String appAuthor;
    @org.springframework.beans.factory.annotation.Value("${app.email}")
    private String appEmail;
    @org.springframework.beans.factory.annotation.Value("${app.build-date}")
    private String appBuildDate;

    @GetMapping("/about")
    public String about(@SessionAttribute("uid") long uid, Model m) {
        m.addAttribute("appVersion", appVersion);
        m.addAttribute("appAuthor", appAuthor);
        m.addAttribute("appEmail", appEmail);
        m.addAttribute("appBuildDate", appBuildDate);
        m.addAttribute("updateState", updates.state());
        m.addAttribute("latestRelease", updates.latest());
        return "about";
    }

    // Вкладка «Резервная копия» (только администратор): все данные приложения в zip и восстановление из него

    @GetMapping("/backup")
    public String backupPage(@SessionAttribute("uid") long uid, Model m) {
        requireAdmin(uid);
        m.addAttribute("backupSize", String.format(org.springframework.context.i18n.LocaleContextHolder.getLocale(), "%.1f",
                backup.approximateSize() / 1048576.0));
        return "backup";
    }

    /** Архив копии — прямо в ответ (без промежуточного файла): база и картинки. */
    @GetMapping("/backup/download")
    public void backupDownload(@SessionAttribute("uid") long uid, jakarta.servlet.http.HttpServletResponse res)
            throws java.io.IOException {
        requireAdmin(uid);
        res.setContentType("application/zip");
        res.setHeader("Content-Disposition", "attachment; filename=\"collections-backup-" + java.time.LocalDate.now() + ".zip\"");
        backup.write(res.getOutputStream());
    }

    /**
     * Восстановление: файл копии приходит телом запроса (скрипт страницы, с прогрессом загрузки) — так на него
     * не действует ограничение размера загружаемых картинок. Ответ: {ok} или {error}.
     */
    @PostMapping("/backup/restore")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> backupRestore(@SessionAttribute("uid") long uid, HttpServletRequest req) {
        requireAdmin(uid);
        if (images.anyActive() || cacheClear.anyActive()) return Map.of("error", I18n.t("backup.busy"));
        try {
            backup.restore(req.getInputStream());
            return Map.of("ok", true);
        } catch (Backup.BadBackup e) {
            return Map.of("error", e.getMessage());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(WebController.class).warn("Восстановление из копии не удалось", e);
            return Map.of("error", I18n.t("backup.failed"));
        }
    }

    @GetMapping("/users")
    public String users(@SessionAttribute("uid") long uid, Model m) {
        requireAdmin(uid);
        m.addAttribute("users", repo.users());
        m.addAttribute("roles", Repo.ROLES);
        return "users";
    }

    /** Добавление пользователя — только администратор (в «Настройках»). */
    @PostMapping("/users/new")
    public String addUser(@SessionAttribute("uid") long uid, @RequestParam String name,
                          @RequestParam(defaultValue = "") String password,
                          @RequestParam(defaultValue = "") String password2,
                          @RequestParam(defaultValue = Repo.USER) String role, Model m) {
        requireAdmin(uid);
        String error = newUserError(name, password, password2);
        if (error == null && !Repo.ROLES.containsKey(role)) error = I18n.t("user.badRole");
        if (error != null) {
            m.addAttribute("userError", error);
            m.addAttribute("newUserName", name);
            m.addAttribute("newUserRole", role);
            return users(uid, m);
        }
        repo.createUser(name, password, role);
        return "redirect:/users?userAdded=" + java.net.URLEncoder.encode(name.trim(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Проверка данных нового пользователя; null — всё в порядке. */
    private String newUserError(String name, String password, String password2) {
        if (name.isBlank()) return I18n.t("user.nameRequired");
        if (repo.userByName(name) != null) return I18n.t("user.exists", name.trim());
        String weak = Passwords.problem(password, name);
        if (weak != null) return weak;
        if (!password.equals(password2)) return I18n.t("user.passwordsDiffer");
        return null;
    }

    private void requireAdmin(long uid) {
        Repo.User u = repo.user(uid);
        if (u == null || !u.isAdmin())
            throw Fail.forbidden("user.adminOnly");
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    @PostMapping("/account/password")
    public String changePassword(@SessionAttribute("uid") long uid, @RequestParam(defaultValue = "") String current,
                                 @RequestParam(defaultValue = "") String password,
                                 @RequestParam(defaultValue = "") String password2, HttpSession session, Model m) {
        String error = null;
        // Подбор текущего пароля тоже ограничен: 5 неудачных попыток — блокировка
        String key = "pw:" + uid;
        long locked = guard.lockedMinutes(key);
        if (locked > 0) error = I18n.t("account.locked", locked);
        else if (!Passwords.matches(current, repo.user(uid).passwordHash())) {
            guard.fail(key, LoginGuard.MAX_FAILS);
            error = I18n.t("account.wrongCurrent");
        } else {
            error = Passwords.problem(password, repo.user(uid).name());
            if (error == null && !password.equals(password2)) error = I18n.t("account.newDiffer");
        }
        if (error != null) {
            m.addAttribute("passwordError", error);
            return "account";
        }
        guard.success(key);
        repo.setPassword(uid, password);
        // Эта сессия остаётся, остальные сессии пользователя завершатся (хеш пароля изменился)
        session.setAttribute(SESSION_PW, repo.user(uid).passwordHash());
        return "redirect:/account?passwordChanged";
    }

    /** Вход: новая сессия (защита от подмены сессии), id пользователя и хеш его пароля в ней. */
    private void signIn(HttpServletRequest request, long uid) {
        HttpSession old = request.getSession(false);
        if (old != null) old.invalidate();
        HttpSession s = request.getSession(true);
        s.setAttribute("uid", uid);
        s.setAttribute(SESSION_PW, repo.user(uid).passwordHash());
    }

    // ---------- Коллекции ----------

    @GetMapping("/")
    public String index(@SessionAttribute("uid") long uid, Model m, @RequestParam(defaultValue = "1") int page) {
        List<Entry> all = repo.collections(uid);
        // Коллекций на странице — настройка «Отображение»
        int perPage = repo.pageSizes(uid).collections();
        int total = all.size();
        int pages = Math.max(1, (total + perPage - 1) / perPage);
        page = Math.min(Math.max(page, 1), pages);
        int from = (page - 1) * perPage;
        List<Entry> shown = all.subList(from, Math.min(from + perPage, total));
        m.addAttribute("collections", shown);
        // Счётчики под названием: всего, в наличии и стоимость — последние два, если у типа коллекции есть такие поля
        Map<Long, Repo.CollectionStats> stats = repo.collectionStats(uid);
        Map<Long, Set<String>> fields = new java.util.HashMap<>();
        Map<Long, Integer> available = new java.util.HashMap<>();
        Map<Long, String> prices = new java.util.HashMap<>();
        for (Entry c : shown) {
            Repo.CollectionStats s = stats.getOrDefault(c.id(), new Repo.CollectionStats(0, 0));
            fields.put(c.id(), repo.itemFields(uid, c.kind()));
            available.put(c.id(), s.available());
            prices.put(c.id(), formatPrice(s.price()));
        }
        m.addAttribute("kindFields", fields);
        m.addAttribute("availableCounts", available);
        m.addAttribute("prices", prices);
        m.addAttribute("showPrices", repo.showPrices(uid));
        m.addAttribute("page", page);
        m.addAttribute("pages", pages);
        m.addAttribute("pageLinks", pageLinks(page, pages));
        return "index";
    }

    /** Поиск по всем коллекциям: объекты (название, теги, регион / издание, заметки) и коллекции по названию. */
    @GetMapping("/search")
    public String search(@SessionAttribute("uid") long uid, @RequestParam(defaultValue = "") String q, Model m) {
        String query = q.trim();
        m.addAttribute("q", query);
        List<Entry> all = repo.collections(uid);
        Map<Long, Entry> byId = new java.util.HashMap<>();
        for (Entry c : all) byId.put(c.id(), c);
        m.addAttribute("collectionsById", byId);
        if (!query.isEmpty()) {
            String needle = query.toLowerCase(java.util.Locale.forLanguageTag("ru"));
            m.addAttribute("foundCollections", all.stream()
                    .filter(c -> c.name().toLowerCase(java.util.Locale.forLanguageTag("ru")).contains(needle)).toList());
            Repo.SearchResult r = repo.search(uid, query);
            m.addAttribute("found", r.items());
            m.addAttribute("foundTotal", r.total());
            // поля типа каждой коллекции — чтобы показывать статус и наличие только там, где они есть
            Map<Long, Kind> kinds = new java.util.HashMap<>();
            Map<Long, Set<String>> fields = new java.util.HashMap<>();
            for (Entry it : r.items()) {
                Kind k = kinds.computeIfAbsent(it.collectionId(), cid -> repo.kindByName(uid, byId.get(cid).kind()));
                fields.put(it.collectionId(), k == null ? Repo.FIELDS.keySet() : k.fields());
            }
            m.addAttribute("kindFields", fields);
            m.addAttribute("itemJson", itemJson(r.items(), it -> kinds.get(it.collectionId()),
                    repo.customValues(r.items().stream().map(Entry::id).toList())));
        }
        return "search";
    }

    /** Статус коллекции на странице статистики: значение, сколько объектов, цвет строки (или null). */
    public record StatusCount(String status, int count, String color) {}

    /** Статистика: итоги по всем коллекциям и по каждой, рейтинги, покупки по годам, частые теги. */
    @GetMapping("/stats")
    public String stats(@SessionAttribute("uid") long uid, Model m) {
        List<Entry> all = repo.collections(uid);
        Map<Long, Repo.Stats> stats = repo.stats(uid);
        Map<Long, Set<String>> fields = new java.util.HashMap<>();
        Map<Long, String> prices = new java.util.HashMap<>();
        Map<Long, Map<String, Integer>> statusCounts = repo.statusCounts(uid);
        // статусы коллекции — в порядке списка её типа (с цветом), за ними прежние значения не из списка
        Map<Long, List<StatusCount>> statuses = new java.util.HashMap<>();
        Map<String, Integer> statusTotals = new java.util.HashMap<>();
        Map<String, String> kindLabel = new java.util.HashMap<>();
        long items = 0, available = 0, price = 0, images = 0;
        for (Entry c : all) {
            Repo.Stats s = stats.get(c.id());
            Kind kind = repo.kindByName(uid, c.kind());
            Set<String> f = kind == null ? Repo.FIELDS.keySet() : kind.fields();
            fields.put(c.id(), f);
            prices.put(c.id(), formatPrice(s.price()));
            items += s.items();
            images += s.withImages();
            if (f.contains("available")) available += s.available();
            if (f.contains("price")) price += s.price();
            if (f.contains("status")) {
                Map<String, Integer> counts = new java.util.LinkedHashMap<>(statusCounts.getOrDefault(c.id(), Map.of()));
                List<StatusCount> list = new ArrayList<>();
                Map<String, String> colors = kind == null ? Map.of() : kind.statusColors();
                for (String st : kind == null ? List.<String>of() : kind.statuses())
                    list.add(new StatusCount(st, counts.getOrDefault(st, 0), colors.get(st)));
                counts.forEach((st, n) -> { if (list.stream().noneMatch(x -> x.status().equals(st))) list.add(new StatusCount(st, n, null)); });
                statuses.put(c.id(), list);
                for (StatusCount sc : list) statusTotals.merge(sc.status(), sc.count(), Integer::sum);
            }
        }
        // итоги: самые частые статусы (до 4), с объектами
        m.addAttribute("statuses", statuses);
        m.addAttribute("statusTotals", statusTotals.entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(4).toList());
        m.addAttribute("collections", all);
        m.addAttribute("stats", stats);
        m.addAttribute("kindFields", fields);
        m.addAttribute("prices", prices);
        m.addAttribute("total", Map.of("items", items, "available", available, "images", images));
        m.addAttribute("totalPrice", formatPrice(price));
        m.addAttribute("showPrices", repo.showPrices(uid));
        int[] hist = repo.ratingHistogram(uid);
        m.addAttribute("ratingHist", hist);
        m.addAttribute("ratingMax", java.util.Arrays.stream(hist).max().orElse(0));
        Map<String, long[]> years = repo.purchasesByYear(uid);
        m.addAttribute("years", years);
        m.addAttribute("yearsMax", years.values().stream().mapToLong(v -> v[0]).max().orElse(0));
        Map<String, String> yearPrices = new java.util.HashMap<>();
        years.forEach((y, v) -> yearPrices.put(y, formatPrice(v[1])));
        m.addAttribute("yearPrices", yearPrices);
        m.addAttribute("tags", repo.topTags(uid, 20));
        return "stats";
    }

    @GetMapping("/collections/new")
    public String newCollectionForm(@SessionAttribute("uid") long uid, Model m) {
        return form(uid, m, null, null, "coll.new", "/");
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

    /** Столбец таблицы объектов: ключ сортировки, заголовок (текст), css-класс. */
    public record Col(String key, String label, String css) {}

    /** Подпись столбца: своя подпись поля в типе или стандартная (ключ текста defaultKey — короче, чем в форме). */
    private static String colLabel(Kind kind, String role, String defaultKey) {
        Kind.Field f = kind == null ? null : kind.field(role);
        return f != null && f.label() != null ? f.label() : I18n.t(defaultKey);
    }

    /**
     * Фильтры таблицы объектов из параметров f_<ключ поля> — по виду поля: название и значения из списков (платформа,
     * формат, статус, регион, тег, свои текст и список) — как есть; год покупки — 4 цифры; цена «от — до» (f_price_min,
     * f_price_max) — целые числа; наличие и свои «да / нет» — «1» / «0»; рейтинг — «0» (без рейтинга) или «1»…«5» (не меньше
     * звёзд); картинки (есть у всех) — «any», «0»…«5». Только по полям типа коллекции.
     */
    private static Map<String, String> filters(Kind kind, Set<String> fields, HttpServletRequest req) {
        Map<String, String> res = new java.util.LinkedHashMap<>();
        putFilter(res, "name", req.getParameter("f_name"));
        for (String f : List.of("platform", "format", "status", "edition", "tags"))
            if (fields.contains(f)) putFilter(res, f, req.getParameter("f_" + f));
        String purchased = req.getParameter("f_purchased");
        if (fields.contains("purchased") && purchased != null && purchased.matches("\\d{4}")) res.put("purchased", purchased);
        if (fields.contains("price")) {
            putPriceFilter(res, "price_min", req.getParameter("f_price_min"));
            putPriceFilter(res, "price_max", req.getParameter("f_price_max"));
        }
        String available = req.getParameter("f_available");
        if (fields.contains("available") && ("1".equals(available) || "0".equals(available))) res.put("available", available);
        String rating = req.getParameter("f_rating");
        if (fields.contains("rating") && rating != null && rating.matches("[0-5]")) res.put("rating", rating);
        String images = req.getParameter("f_images");
        if (images != null && images.matches("any|[0-" + ImageFiles.MAX_IMAGES + "]")) res.put("images", images);
        if (kind != null)
            for (Kind.Field cf : kind.customs()) {
                String v = req.getParameter("f_" + cf.key());
                switch (cf.type()) {
                    case "bool" -> { if ("1".equals(v) || "0".equals(v)) res.put(cf.key(), v); }
                    case "select", "text" -> putFilter(res, cf.key(), v);
                    default -> { }
                }
            }
        return res;
    }

    /** Значения списка для фильтра: сначала значения из настроек поля, за ними прежние значения не из списка. */
    private static List<String> mergeOptions(List<String> options, List<String> used) {
        List<String> res = new ArrayList<>(options);
        for (String u : used) if (!res.contains(u)) res.add(u);
        return res;
    }

    /**
     * Значения своих полей для показа в таблице: id объекта → («c<id>» → текст): число — с разрядами, дата —
     * по языку интерфейса, да/нет — «1» (ячейка рисует галочку), остальное — как есть.
     */
    private static Map<Long, Map<String, String>> customText(Kind kind, Map<Long, Map<Long, Object>> values) {
        Map<Long, Map<String, String>> res = new java.util.HashMap<>();
        if (kind == null) return res;
        values.forEach((itemId, byField) -> {
            Map<String, String> row = new java.util.HashMap<>();
            for (Kind.Field cf : kind.customs()) {
                Object v = byField.get(cf.id());
                if (v != null) row.put(cf.key(), switch (cf.type()) {
                    case "number" -> v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                            ? formatPrice(n.longValue()) : String.valueOf(v);
                    case "date" -> Entry.dateText(String.valueOf(v));
                    case "bool" -> "1";
                    default -> String.valueOf(v);
                });
            }
            res.put(itemId, row);
        });
        return res;
    }

    /** Подписи полей типа для шаблонов: роль → подпись (своя или стандартная); без типа — стандартные. */
    private static Map<String, String> labels(Kind kind) {
        if (kind != null) return kind.labels();
        Map<String, String> res = new java.util.LinkedHashMap<>();
        Repo.FIELDS.forEach((r, key) -> res.put(r, I18n.t(key)));
        return res;
    }

    @GetMapping("/collections/{id}")
    public String collection(@SessionAttribute("uid") long uid, @PathVariable long id, Model m,
                             @RequestParam(defaultValue = "name") String sort,
                             @RequestParam(defaultValue = "asc") String dir,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "false") boolean dups,
                             HttpServletRequest req) {
        Entry c = repo.collection(uid, id);
        // Открыта без параметров (с главной и т. п.) — запомненный вид списка этой коллекции: сортировка, страница, фильтры
        if (req.getQueryString() == null || req.getQueryString().isEmpty()) {
            String saved = repo.collectionView(uid, id);
            if (isView(saved)) return "redirect:" + collectionUrl(id, saved);
        }
        Kind kind = repo.kindByName(uid, c.kind());
        Set<String> fields = kind == null ? Repo.FIELDS.keySet() : kind.fields();
        m.addAttribute("statusColors", repo.statusColors(uid, c.kind()));
        m.addAttribute("kind", kind);
        m.addAttribute("labels", labels(kind));

        // Столбцы — в порядке полей типа; подпись — своя подпись поля в типе или стандартная
        boolean showPrices = repo.showPrices(uid);
        m.addAttribute("showPrices", showPrices);
        List<Col> cols = new ArrayList<>();
        cols.add(new Col("name", I18n.t("field.name"), "title"));
        for (String f : fields) {
            Col col = switch (f) {
                case "platform", "format", "edition" -> new Col(f, colLabel(kind, f, "field." + f), "");
                case "status" -> new Col(f, colLabel(kind, f, "field.statusShort"), "");
                case "rating" -> new Col(f, colLabel(kind, f, "field.rating"), "rating");
                // Цена: открыто (настройка «Отображение») или в подсказке у иконки глаза
                case "price" -> showPrices ? new Col(f, colLabel(kind, f, "field.price"), "num") : new Col(f, I18n.t("col.priceEye"), "eye");
                case "purchased" -> new Col(f, colLabel(kind, f, "field.purchased"), "date");
                case "available" -> new Col(f, colLabel(kind, f, "field.available"), "flag");
                case "tags" -> new Col(f, colLabel(kind, f, "field.tags"), "tags");
                default -> null; // заметки и Old-Games.ru в таблице не показываются
            };
            if (col != null) cols.add(col);
        }
        // свои поля — тоже в порядке типа (длинный текст в таблице не показывается)
        List<String> order = kind == null ? List.of() : kind.keys();
        Map<String, String> customTypes = new java.util.HashMap<>();
        if (kind != null)
            for (Kind.Field cf : kind.customs()) {
                customTypes.put(cf.key(), cf.type());
                if (cf.type().equals("longtext")) continue;
                cols.add(new Col(cf.key(), cf.label(), switch (cf.type()) {
                    case "number" -> "num";
                    case "date" -> "date";
                    case "bool" -> "flag";
                    default -> "";
                }));
            }
        // столбцы — в порядке полей типа (название — первым)
        cols.sort(java.util.Comparator.comparingInt(col -> col.key().equals("name") ? -1 : order.indexOf(col.key())));
        m.addAttribute("customTypes", customTypes);

        String requested = sort;
        if (cols.stream().noneMatch(col -> col.key().equals(requested))) sort = "name";
        boolean desc = "desc".equals(dir);
        // Объектов на странице — настройка «Отображение»
        int size = repo.pageSizes(uid).items();

        // Фильтры по столбцам (параметры f_<ключ поля>; только по полям типа коллекции); пустые и неверные не учитываются
        Map<String, String> filters = filters(kind, fields, req);
        // Значения для выпадающих списков фильтров: списки (статусы, свои списки) — в порядке настроек типа, затем прочие значения
        Map<String, List<String>> customOptions = new java.util.HashMap<>();
        if (kind != null)
            for (Kind.Field cf : kind.customs())
                if (cf.type().equals("select") || cf.type().equals("text"))
                    customOptions.put(cf.key(), cf.type().equals("select")
                            ? mergeOptions(cf.values(), repo.customDistinct(id, cf.id())) : repo.customDistinct(id, cf.id()));
        Map<String, List<String>> filterOptions = new java.util.HashMap<>(customOptions);
        for (String f : Repo.SELECT_FILTERS)
            if (fields.contains(f)) filterOptions.put(f, f.equals("status") && kind != null
                    ? mergeOptions(kind.statuses(), repo.distinctValues(id, f)) : repo.distinctValues(id, f));
        for (String f : List.of("edition", "tags", "purchased"))
            if (fields.contains(f)) filterOptions.put(f, repo.detailValues(id, f));
        StringBuilder filterQs = new StringBuilder();
        filters.forEach((k, v) -> filterQs.append("&f_").append(k).append("=")
                .append(java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)));

        // Режим «Дубли»: только объекты, у которых в коллекции есть объект с тем же названием
        Set<Long> dupIds = new java.util.HashSet<>();
        int dupGroups = 0;
        if (dups) {
            Map<String, List<Long>> byName = new java.util.HashMap<>();
            for (Entry e : repo.items(id, "name", false, Map.of()))
                byName.computeIfAbsent(dupKey(e.name()), k -> new ArrayList<>()).add(e.id());
            for (List<Long> g : byName.values())
                if (g.size() > 1) { dupIds.addAll(g); dupGroups++; }
        }
        String modeQs = dups ? "&dups=1" : "";

        List<Entry> found = repo.items(id, sort, desc, filters);
        if (dups) found = found.stream().filter(e -> dupIds.contains(e.id())).toList();
        int total = found.size();
        int pages = Math.max(1, (total + size - 1) / size);
        page = Math.min(Math.max(page, 1), pages);
        int fromIdx = (page - 1) * size;

        m.addAttribute("c", c);
        m.addAttribute("fields", fields);
        m.addAttribute("cols", cols);
        // сортировать можно по встроенным полям из ITEM_SORT и по своим (кроме длинного текста — его нет в таблице)
        Set<String> sortable = new java.util.HashSet<>(Repo.ITEM_SORT.keySet());
        sortable.addAll(customTypes.keySet());
        m.addAttribute("sortable", sortable);
        // Массовые действия: куда можно перенести объекты — другие коллекции пользователя
        m.addAttribute("moveTargets", repo.collections(uid).stream().filter(x -> x.id() != id).toList());
        List<Entry> pageItems = found.subList(fromIdx, Math.min(fromIdx + size, total));
        m.addAttribute("items", pageItems);
        Map<Long, Map<Long, Object>> custom = repo.customValues(pageItems.stream().map(Entry::id).toList());
        m.addAttribute("itemJson", itemJson(pageItems, it -> kind, custom));
        m.addAttribute("customText", customText(kind, custom));
        m.addAttribute("kindJson", kindJson(kind));
        // В режиме дублей при сортировке по названию — первая строка каждой группы (над ней линия)
        Set<Long> groupStarts = new java.util.HashSet<>();
        if (dups && sort.equals("name"))
            for (int i = fromIdx; i < fromIdx + pageItems.size(); i++)
                if (i == 0 || !dupKey(found.get(i).name()).equals(dupKey(found.get(i - 1).name())))
                    groupStarts.add(found.get(i).id());
        m.addAttribute("groupStarts", groupStarts);
        // Общая стоимость — только если у типа коллекции есть поле «Цена»; формат «717 841» (по-английски «717,841»)
        if (fields.contains("price"))
            m.addAttribute("totalPrice", formatPrice(repo.totalPrice(id)));
        m.addAttribute("dups", dups);
        m.addAttribute("dupGroups", dupGroups);
        m.addAttribute("modeQs", modeQs);
        m.addAttribute("filters", filters);
        m.addAttribute("filterOptions", filterOptions);
        m.addAttribute("filterQs", filterQs.toString());
        // Всего без фильтров: в режиме дублей — все дубли, иначе — все объекты коллекции
        m.addAttribute("totalAll", dups ? dupIds.size() : filters.isEmpty() ? total : repo.countItems(id));
        m.addAttribute("sort", sort);
        m.addAttribute("dir", desc ? "desc" : "asc");
        m.addAttribute("page", page);
        m.addAttribute("pages", pages);
        m.addAttribute("total", total);
        m.addAttribute("from", total == 0 ? 0 : fromIdx + 1);
        m.addAttribute("to", Math.min(page * size, total));
        m.addAttribute("pageLinks", pageLinks(page, pages));
        // Текущий вид списка — чтобы после изменения/удаления объекта вернуться туда же
        String view = "sort=" + sort + "&dir=" + (desc ? "desc" : "asc") + "&page=" + page + filterQs;
        m.addAttribute("view", view + modeQs);
        // Запомнить вид для этого пользователя и коллекции (режим «Дубли» не запоминается)
        if (!dups) repo.setCollectionView(uid, id, view);
        return "collection";
    }

    /** Сумма в рублях с разделением разрядов по языку интерфейса: «717 841» (по-английски «717,841»). */
    private static String formatPrice(long sum) {
        return String.format(org.springframework.context.i18n.LocaleContextHolder.getLocale(), "%,d", sum)
                .replace('\u00a0', ' ').replace('\u202f', ' ');
    }

    /**
     * Массовое действие над выбранными объектами коллекции: наличие, статус, рейтинг (если поле есть у типа),
     * перенос в другую коллекцию, удаление. Чужие и не из этой коллекции объекты — 404. Затем — тот же вид списка.
     */
    @PostMapping("/collections/{id}/bulk")
    public String bulk(@SessionAttribute("uid") long uid, @PathVariable long id,
                       @RequestParam(name = "ids", required = false) List<Long> ids, @RequestParam String action,
                       @RequestParam(required = false) String value, @RequestParam(required = false) String view) {
        Entry c = repo.collection(uid, id);
        Set<String> fields = repo.itemFields(uid, c.kind());
        String v = value == null ? "" : value.trim();
        Long target = null;
        if (action.equals("move")) {
            if (!v.matches("\\d{1,18}")) throw Fail.badRequest();
            target = repo.collection(uid, Long.parseLong(v)).id(); // чужая коллекция — 404
        } else if (!action.equals("delete") && !(Set.of("available", "status", "rating").contains(action) && fields.contains(action))) {
            throw Fail.badRequest();
        }
        List<Long> chosen = ids == null ? List.of() : ids;
        // сначала проверка всех (чужой или из другой коллекции — 404, ничего не меняется), потом изменения
        // (без общей транзакции: удаление сразу освобождает файлы картинок)
        for (long itemId : chosen)
            if (repo.item(uid, itemId).collectionId() != id)
                throw Fail.notFound();
        // неверный статус (не из списка типа коллекции) — 400 до изменений
        if (action.equals("status") && !v.isEmpty()) {
            Kind kind = repo.kindByName(uid, c.kind());
            if (kind == null || !kind.statuses().contains(v)) throw Fail.badRequest("item.badStatus");
        }
        if (action.equals("rating") && !(v.matches("\\d{1,2}") && Integer.parseInt(v) <= 10))
            throw Fail.badRequest();
        for (long itemId : chosen) {
            switch (action) {
                case "available" -> repo.setAvailable(itemId, "1".equals(v));
                case "status" -> repo.setValue(itemId, "status", v);
                case "rating" -> repo.setRating(itemId, Integer.parseInt(v));
                case "move" -> repo.moveItem(itemId, target);
                default -> repo.delete("items", itemId);
            }
        }
        return "redirect:" + collectionUrl(id, view);
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

    private static void putFilter(Map<String, String> filters, String key, String value) {
        if (value != null && !value.isBlank()) filters.put(key, value.trim());
    }

    private static void putPriceFilter(Map<String, String> filters, String key, String value) {
        if (value != null && value.trim().matches("\\d{1,12}")) filters.put(key, String.valueOf(Long.parseLong(value.trim())));
    }

    /**
     * Адрес страницы коллекции с сохранённым видом списка (сортировка, страница, фильтры).
     * view — только из нашей же ссылки: допускаются лишь символы параметров и URL-кодирования.
     */
    private static String collectionUrl(long cid, String view) {
        return "/collections/" + cid + (isView(view) ? "?" + view : "");
    }

    /** Куда вернуться со страницы объекта: в поиск (q — запрос, если объект открыт из поиска) или в коллекцию. */
    private static String backUrl(long cid, String view, String q) {
        return q != null ? "/search?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8)
                : collectionUrl(cid, view);
    }

    private static boolean isView(String view) {
        return view != null && !view.isEmpty() && view.matches("[A-Za-z0-9=&%+._*-]*");
    }

    /** Ключ для поиска дублей: название без учёта регистра и лишних пробелов. */
    private static String dupKey(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.forLanguageTag("ru"));
    }

    @GetMapping("/collections/{id}/edit")
    public String editCollectionForm(@SessionAttribute("uid") long uid, @PathVariable long id, Model m) {
        Entry c = repo.collection(uid, id);
        return form(uid, m, c, c.pic() != null ? "/img/" + c.pic() : null,
                "coll.editTitle", "/collections/" + id);
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

    /**
     * Страниц объекта нет — объект создаётся и меняется в окне объекта. Прежние адреса (закладки, открытие в новой
     * вкладке) ведут в коллекцию с запомненным видом списка, окно открывает скрипт по параметру new=1 / open=<id>.
     */
    @GetMapping("/collections/{cid}/items/new")
    public String newItemForm(@SessionAttribute("uid") long uid, @PathVariable long cid) {
        repo.collection(uid, cid); // чужая — 404
        return "redirect:" + withParam(collectionUrl(cid, repo.collectionView(uid, cid)), "new=1");
    }

    @Transactional
    @PostMapping("/collections/{cid}/items/new")
    public String createItem(@SessionAttribute("uid") long uid, @PathVariable long cid,
                             @org.springframework.web.bind.annotation.ModelAttribute Repo.ItemForm form,
                             @RequestParam Map<String, MultipartFile> files, @RequestParam(required = false) String view,
                             HttpServletRequest req) {
        Entry c = repo.collection(uid, cid);
        long id = repo.createItem(cid);
        repo.saveItem(uid, id, c.kind(), form, newImages(files), Set.of());
        saveCustoms(id, req);
        return "redirect:" + collectionUrl(cid, view);
    }

    @GetMapping("/items/{id}/edit")
    public String editItemForm(@SessionAttribute("uid") long uid, @PathVariable long id, @RequestParam(required = false) String view) {
        Entry it = repo.item(uid, id);
        String v = isView(view) ? view : repo.collectionView(uid, it.collectionId());
        return "redirect:" + withParam(collectionUrl(it.collectionId(), v), "open=" + id);
    }

    /**
     * Свои поля из окна объекта: параметры cf_<id поля> (у «да/нет» — скрытое 0 и галочка 1: берётся последнее значение).
     * Поля, которых нет в форме, не меняются; поля не из типа коллекции объекта пропускает Repo.setCustom.
     */
    private void saveCustoms(long itemId, HttpServletRequest req) {
        req.getParameterMap().forEach((name, values) -> {
            if (name.matches("cf_\\d{1,18}") && values.length > 0)
                repo.setCustom(itemId, Long.parseLong(name.substring(3)), values[values.length - 1]);
        });
    }

    /** Добавляет параметр к адресу (с «?» или «&»). */
    private static String withParam(String url, String param) {
        return url + (url.contains("?") ? "&" : "?") + param;
    }

    /** Данные объекта для окна объекта по адресу open=<id> (объекта может не быть в таблице на странице). */
    @GetMapping("/items/{id}/data")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> itemData(@SessionAttribute("uid") long uid, @PathVariable long id) {
        Entry it = repo.item(uid, id);
        return itemData(it, repo.kindByName(uid, repo.collection(uid, it.collectionId()).kind()),
                repo.customValues(List.of(id)).getOrDefault(id, Map.of()));
    }

    /**
     * Данные объекта для окна объекта — единственное описание: и для строк таблицы коллекции и поиска (атрибут
     * data-item, JSON), и для /items/{id}/data. fields — поля типа коллекции объекта (окно показывает только их).
     * Новое поле объекта добавлять сюда и в окно (base :: itemDialog).
     */
    private static Map<String, Object> itemData(Entry it, Kind kind, Map<Long, Object> custom) {
        Map<String, Object> d = new java.util.HashMap<>();
        d.put("id", it.id());
        d.put("cid", it.collectionId());
        d.putAll(kindData(kind));
        // свои поля: «c<id>» → значение (текстом)
        Map<String, String> cv = new java.util.HashMap<>();
        custom.forEach((fid, v) -> cv.put("c" + fid, String.valueOf(v)));
        d.put("custom", cv);
        d.put("name", it.name());
        d.put("platform", it.platform());
        d.put("format", it.format());
        d.put("status", it.status());
        d.put("price", it.price());
        d.put("description", it.description());
        d.put("available", Boolean.TRUE.equals(it.available()) ? "1" : "0");
        d.put("pics", it.pics());
        d.put("rating", it.rating());
        d.put("oldgames", it.oldGamesId());
        d.put("purchased", it.purchased());
        d.put("tags", it.tags());
        d.put("edition", it.edition());
        d.put("notes", it.notes());
        return d;
    }

    /**
     * Что окно объекта берёт из типа коллекции: fields — роли полей по порядку (окно показывает только их, в этом
     * порядке), labels — свои подписи полей (роль → подпись; нет — подпись из разметки окна), statuses — список статусов.
     */
    private static Map<String, Object> kindData(Kind kind) {
        Map<String, Object> d = new java.util.HashMap<>();
        d.put("fields", String.join(",", kind == null ? Repo.FIELDS.keySet() : kind.keys()));
        // свои поля: ключ, подпись, вид, значения списка — окно создаёт для них строки
        List<Map<String, Object>> customs = new ArrayList<>();
        if (kind != null)
            for (Kind.Field cf : kind.customs())
                customs.add(Map.of("key", cf.key(), "label", cf.label(), "type", cf.type(), "options", cf.values()));
        d.put("customs", customs);
        Map<String, String> own = new java.util.HashMap<>();
        if (kind != null)
            for (Kind.Field f : kind.defs())
                if (f.role() != null && f.label() != null) own.put(f.role(), f.label());
        d.put("labels", own);
        d.put("statuses", kind == null ? List.of() : kind.statuses());
        return d;
    }

    /** То же для окна нового объекта на странице коллекции (атрибут data-kind окна), JSON. */
    private static String kindJson(Kind kind) {
        try {
            return JSON.writeValueAsString(kindData(kind));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Данные объектов для атрибута data-item строк таблицы: id → JSON. kindOf — тип коллекции объекта. */
    private static Map<Long, String> itemJson(List<Entry> items, java.util.function.Function<Entry, Kind> kindOf,
                                              Map<Long, Map<Long, Object>> custom) {
        Map<Long, String> res = new java.util.HashMap<>();
        for (Entry it : items) {
            try {
                res.put(it.id(), JSON.writeValueAsString(itemData(it, kindOf.apply(it), custom.getOrDefault(it.id(), Map.of()))));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        return res;
    }

    @PostMapping("/items/{id}/edit")
    public String editItem(@SessionAttribute("uid") long uid, @PathVariable long id,
                           @org.springframework.web.bind.annotation.ModelAttribute Repo.ItemForm form,
                           @RequestParam Map<String, MultipartFile> files,
                           @RequestParam(name = "remove", required = false) List<Integer> remove,
                           @RequestParam(required = false) String view, @RequestParam(required = false) String q,
                           HttpServletRequest req) {
        Entry it = repo.item(uid, id);
        repo.saveItem(uid, id, repo.collection(uid, it.collectionId()).kind(), form, newImages(files),
                remove == null ? Set.of() : Set.copyOf(remove));
        saveCustoms(id, req);
        return "redirect:" + backUrl(it.collectionId(), view, q);
    }

    /**
     * Описание и картинки со страницы игры Old-Games.ru (кнопка «Загрузить» в окне объекта). Ничего не сохраняет:
     * окно подставляет описание и картинки в форму, сохраняются они кнопкой «Сохранить».
     * Ответ: {url, description, images: [{mime, data (base64)}]} или {error}.
     */
    @PostMapping("/oldgames")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> loadOldGames(@RequestParam String url) {
        try {
            OldGames.Result r = oldGames.load(url, ImageFiles.MAX_IMAGES);
            if (r == null) return Map.of("error", I18n.t("og.notFound"));
            List<Map<String, String>> images = new ArrayList<>();
            for (byte[] b : r.images())
                images.add(Map.of("mime", Repo.imageType(b), "data", java.util.Base64.getEncoder().encodeToString(b)));
            return Map.of("url", r.url(), "description", r.description(), "images", images);
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("error", I18n.t("og.interrupted"));
        } catch (Exception e) {
            return Map.of("error", I18n.t("og.unavailable"));
        }
    }

    /** Новые картинки из окна объекта: «image_N» — на место N. */
    private static Map<Integer, MultipartFile> newImages(Map<String, MultipartFile> files) {
        Map<Integer, MultipartFile> res = new java.util.HashMap<>();
        for (int pos = 0; pos < ImageFiles.MAX_IMAGES; pos++) {
            MultipartFile f = files.get("image_" + pos);
            if (f != null && !f.isEmpty()) res.put(pos, f);
        }
        return res;
    }

    /**
     * Картинка по хешу содержимого: /img/<хеш> — сама картинка, /img/<хеш>/thumb — уменьшенная копия (главная, шапка
     * коллекции, поиск; если уменьшать нечего — сама картинка). Содержимое по адресу никогда не меняется (другая
     * картинка — другой хеш), поэтому браузер хранит её год и не спрашивает снова. Только свои картинки, иначе 404.
     */
    @GetMapping({"/img/{hash}", "/img/{hash}/thumb"})
    public ResponseEntity<byte[]> picture(@SessionAttribute("uid") long uid, @PathVariable String hash, HttpServletRequest req) {
        ImageFiles.Pic pic = hash.matches("[0-9a-f]{64}") ? repo.ownedPicture(uid, hash) : null;
        if (pic == null) throw Fail.notFound();
        ImageFiles.Thumb thumb = req.getRequestURI().endsWith("/thumb") ? repo.thumb(pic) : null;
        byte[] body = thumb != null ? thumb.bytes() : repo.pictureBytes(pic);
        if (body == null) throw Fail.notFound();
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePrivate().immutable())
                .contentType(MediaType.parseMediaType(thumb != null ? thumb.mime() : pic.mime()))
                .body(body);
    }

    @PostMapping("/items/{id}/delete")
    public String deleteItem(@SessionAttribute("uid") long uid, @PathVariable long id, @RequestParam(required = false) String view,
                             @RequestParam(required = false) String q) {
        Entry it = repo.item(uid, id);
        repo.delete("items", id);
        return "redirect:" + backUrl(it.collectionId(), view, q);
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
        return kindForm(uid, m, null, "kinds.newTitle");
    }

    @Transactional
    @PostMapping("/kinds/new")
    public String createKind(@SessionAttribute("uid") long uid, @RequestParam String name,
                             @RequestParam(required = false) String profile,
                             @RequestParam(name = "role", required = false) List<String> roles,
                             @RequestParam(name = "on", required = false) List<String> enabled,
                             @RequestParam(name = "label", required = false) List<String> labels,
                             @RequestParam(name = "type", required = false) List<String> types,
                             @RequestParam(name = "opts", required = false) List<String> opts,
                             @RequestParam(name = "st", required = false) List<String> statuses,
                             @RequestParam(name = "stc", required = false) List<String> colors) {
        repo.saveKind(uid, null, name, profile, roles, enabled, labels, types, opts, statuses, colors);
        return "redirect:/kinds";
    }

    @GetMapping("/kinds/{id}/edit")
    public String editKindForm(@SessionAttribute("uid") long uid, @PathVariable long id, Model m) {
        return kindForm(uid, m, repo.kind(uid, id), "kinds.kind");
    }

    @Transactional
    @PostMapping("/kinds/{id}/edit")
    public String editKind(@SessionAttribute("uid") long uid, @PathVariable long id, @RequestParam String name,
                           @RequestParam(required = false) String profile,
                           @RequestParam(name = "role", required = false) List<String> roles,
                           @RequestParam(name = "on", required = false) List<String> enabled,
                           @RequestParam(name = "label", required = false) List<String> labels,
                           @RequestParam(name = "type", required = false) List<String> types,
                           @RequestParam(name = "opts", required = false) List<String> opts,
                           @RequestParam(name = "st", required = false) List<String> statuses,
                           @RequestParam(name = "stc", required = false) List<String> colors) {
        repo.saveKind(uid, id, name, profile, roles, enabled, labels, types, opts, statuses, colors);
        return "redirect:/kinds";
    }

    @PostMapping("/kinds/{id}/delete")
    public String deleteKind(@SessionAttribute("uid") long uid, @PathVariable long id) {
        repo.deleteKind(uid, id);
        return "redirect:/kinds";
    }

    /**
     * Строка поля в форме типа: ключ (роль встроенного или «c<id>» своего), стандартная подпись (у своего — пусто),
     * подпись (своя или ""), включено ли, вид своего поля (у встроенного — ""), значения списка по строкам.
     */
    public record KindFieldRow(String role, String defaultLabel, String label, boolean on, String type, String opts) {
        public boolean custom() {
            return !type.isEmpty();
        }
    }

    private String kindForm(long uid, Model m, Kind kind, String title) {
        m.addAttribute("kind", kind);
        m.addAttribute("profiles", Repo.PROFILES);
        // Поля: сначала поля типа в его порядке, за ними остальные из набора (выключенные) — порядок меняется стрелками
        List<KindFieldRow> rows = new ArrayList<>();
        if (kind != null)
            for (String key : kind.keys()) {
                Kind.Field f = kind.field(key);
                rows.add(f.custom()
                        ? new KindFieldRow(key, "", f.label(), true, f.type(), String.join("\n", f.values()))
                        : new KindFieldRow(key, I18n.t(Repo.FIELDS.get(key)), f.label() == null ? "" : f.label(), true, "", ""));
            }
        for (String r : Repo.FIELDS.keySet())
            if (kind == null || !kind.fields().contains(r))
                rows.add(new KindFieldRow(r, I18n.t(Repo.FIELDS.get(r)), "", false, "", ""));
        m.addAttribute("rows", rows);
        m.addAttribute("customTypes", Repo.CUSTOM_TYPES);
        m.addAttribute("maxCustom", Repo.MAX_CUSTOM);
        // Статусы с цветами по порядку и две пустые строки для новых (ещё строки добавляет кнопка «Добавить статус»)
        List<Kind.Option> statuses = new ArrayList<>();
        if (kind != null && kind.field("status") != null) statuses.addAll(kind.field("status").options());
        statuses.add(new Kind.Option("", null));
        statuses.add(new Kind.Option("", null));
        m.addAttribute("statusRows", statuses);
        m.addAttribute("maxStatuses", Repo.MAX_OPTIONS);
        m.addAttribute("title", title);
        return "kind-form";
    }

    // ---------- вспомогательное ----------

    /** Форма коллекции (новая или изменение); объекты меняются в окне объекта. */
    private String form(long uid, Model m, Entry obj, String imgUrl, String title, String back) {
        m.addAttribute("obj", obj);
        m.addAttribute("kinds", repo.kinds(uid).stream().map(Kind::name).toList());
        m.addAttribute("imgUrl", imgUrl);
        m.addAttribute("title", title);
        m.addAttribute("back", back);
        return "form";
    }
}
