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

    public WebController(Repo repo, LoginGuard guard, ImageImport images, CacheClear cacheClear, OldGames oldGames,
                         Backup backup, Updates updates) {
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

    // Вкладка «Отображение»: показывать цены открыто или за иконкой глаза
    @GetMapping("/display")
    public String display(@SessionAttribute("uid") long uid, Model m) {
        m.addAttribute("showPrices", repo.showPrices(uid));
        return "display";
    }

    @PostMapping("/display")
    public String saveDisplay(@SessionAttribute("uid") long uid, @RequestParam(defaultValue = "false") boolean showPrices) {
        repo.setShowPrices(uid, showPrices);
        return "redirect:/display?saved";
    }

    // Вкладка «Импорт»: картинки объектов коллекции из Steam или Discogs (в фоне, прогресс — /import/status)
    @GetMapping("/import")
    public String importPage(@SessionAttribute("uid") long uid, Model m) {
        m.addAttribute("collections", repo.collections(uid));
        m.addAttribute("job", images.job(uid));
        String token = repo.token(uid, ImageImport.DISCOGS);
        m.addAttribute("discogsReady", token != null);
        // сам токен на страницу не выводится — только последние 4 символа
        m.addAttribute("discogsTokenEnd", token == null ? null : token.substring(Math.max(0, token.length() - 4)));
        return "import";
    }

    /** Личный токен Discogs пользователя: сохранить или удалить (кнопка «Удалить токен»). */
    @PostMapping("/import/discogs-token")
    public String discogsToken(@SessionAttribute("uid") long uid, @RequestParam(defaultValue = "") String token,
                               @RequestParam(defaultValue = "false") boolean delete) {
        token = delete ? "" : token.trim();
        if (!delete && token.isEmpty()) return "redirect:/import"; // пустое поле — токен не меняется
        // токен Discogs — латинские буквы и цифры; ограничение длины — с запасом
        if (!token.isEmpty() && !token.matches("[A-Za-z0-9]{10,200}"))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    I18n.t("import.token.bad"));
        repo.setToken(uid, ImageImport.DISCOGS, token);
        return "redirect:/import?token=" + (token.isEmpty() ? "deleted" : "saved");
    }

    @PostMapping("/import/start")
    public String importStart(@SessionAttribute("uid") long uid, @RequestParam long collection,
                              @RequestParam(defaultValue = ImageImport.STEAM) String source,
                              @RequestParam(defaultValue = "false") boolean onlyMissing,
                              @RequestParam(defaultValue = "false") boolean loadImages,
                              @RequestParam(defaultValue = "false") boolean loadDescriptions,
                              @RequestParam(defaultValue = "screenshot") String steamImage,
                              @RequestParam(defaultValue = "5") int imageCount) {
        Entry c = repo.collection(uid, collection); // чужая коллекция — 404
        // пока у коллекции удаляются картинки («Кэш»), импорт в неё не запускается
        if (cacheClear.active(collection))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    I18n.t("import.cacheBusy"));
        if (!ImageImport.SOURCES.containsKey(source))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, I18n.t("import.badSource"));
        // Обложки Discogs — только с токеном; без него — только описания
        String discogsToken = repo.token(uid, ImageImport.DISCOGS);
        if (source.equals(ImageImport.DISCOGS) && discogsToken == null) loadImages = false;
        boolean descriptions = loadDescriptions;
        if (!loadImages && !descriptions)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    I18n.t("import.nothing"));
        images.start(uid, c, onlyMissing, source, loadImages, descriptions, "screenshot".equals(steamImage), imageCount,
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
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    I18n.t("cache.importBusy"));
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
        List<Xlsx.Sheet> sheets = new ArrayList<>();
        for (Entry c : chosen) {
            // Описание, заметки и номер Old-Games.ru в Excel не выгружаются (по просьбе пользователя)
            Set<String> fields = new java.util.LinkedHashSet<>(repo.itemFields(uid, c.kind()));
            fields.remove("oldgames");
            fields.remove("notes");
            List<String> headers = new ArrayList<>(List.of(I18n.t("field.name")));
            List<Integer> widths = new ArrayList<>(List.of(60));
            for (String f : fields) {
                headers.add(I18n.t(f.equals("price") ? "field.priceRub" : Repo.FIELDS.get(f)));
                widths.add(f.equals("price") || f.equals("available") || f.equals("rating") || f.equals("purchased") ? 12
                        : f.equals("tags") ? 30 : 22);
            }
            List<List<Object>> rows = new ArrayList<>();
            for (Entry it : repo.items(c.id(), "name", false, Map.of())) {
                List<Object> row = new ArrayList<>();
                row.add(it.name());
                for (String f : fields)
                    row.add(switch (f) {
                        case "platform" -> it.platform();
                        case "format" -> it.format();
                        case "status" -> it.status();
                        case "price" -> it.price();
                        case "available" -> I18n.t(it.available() ? "available.yes" : "available.no");
                        case "rating" -> it.rating() == null ? null : it.rating() / 2.0; // звёзды: 3.5
                        case "purchased" -> it.purchased(); // ГГГГ-ММ-ДД
                        case "tags" -> it.tags();
                        case "edition" -> it.edition();
                        default -> null;
                    });
                rows.add(row);
            }
            sheets.add(new Xlsx.Sheet(c.name(), headers, rows, widths));
        }
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
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, I18n.t("user.adminOnly"));
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

    /** Коллекций на одной странице главной. */
    private static final int COLLECTIONS_PER_PAGE = 10;

    @GetMapping("/")
    public String index(@SessionAttribute("uid") long uid, Model m, @RequestParam(defaultValue = "1") int page) {
        List<Entry> all = repo.collections(uid);
        int total = all.size();
        int pages = Math.max(1, (total + COLLECTIONS_PER_PAGE - 1) / COLLECTIONS_PER_PAGE);
        page = Math.min(Math.max(page, 1), pages);
        int from = (page - 1) * COLLECTIONS_PER_PAGE;
        List<Entry> shown = all.subList(from, Math.min(from + COLLECTIONS_PER_PAGE, total));
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
            Map<Long, Set<String>> fields = new java.util.HashMap<>();
            for (Entry it : r.items())
                fields.computeIfAbsent(it.collectionId(), cid -> repo.itemFields(uid, byId.get(cid).kind()));
            m.addAttribute("kindFields", fields);
        }
        return "search";
    }

    /** Статистика: итоги по всем коллекциям и по каждой, рейтинги, покупки по годам, частые теги. */
    @GetMapping("/stats")
    public String stats(@SessionAttribute("uid") long uid, Model m) {
        List<Entry> all = repo.collections(uid);
        Map<Long, Repo.Stats> stats = repo.stats(uid);
        Map<Long, Set<String>> fields = new java.util.HashMap<>();
        Map<Long, String> prices = new java.util.HashMap<>();
        long items = 0, available = 0, completed = 0, unfinished = 0, price = 0, images = 0;
        for (Entry c : all) {
            Repo.Stats s = stats.get(c.id());
            Set<String> f = repo.itemFields(uid, c.kind());
            fields.put(c.id(), f);
            prices.put(c.id(), formatPrice(s.price()));
            items += s.items();
            images += s.withImages();
            if (f.contains("available")) available += s.available();
            if (f.contains("status")) { completed += s.completed(); unfinished += s.unfinished(); }
            if (f.contains("price")) price += s.price();
        }
        m.addAttribute("collections", all);
        m.addAttribute("stats", stats);
        m.addAttribute("kindFields", fields);
        m.addAttribute("prices", prices);
        m.addAttribute("total", Map.of("items", items, "available", available, "completed", completed,
                "unfinished", unfinished, "images", images));
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

    /** Столбец таблицы объектов: ключ сортировки, заголовок (ключ текста), css-класс. */
    public record Col(String key, String label, String css) {}

    /** Размеры страницы для выпадающего списка: 25, 50, … 200. */
    private static final List<Integer> PAGE_SIZES = IntStream.rangeClosed(1, 8).map(i -> i * 25).boxed().toList();

    @GetMapping("/collections/{id}")
    public String collection(@SessionAttribute("uid") long uid, @PathVariable long id, Model m,
                             @RequestParam(defaultValue = "name") String sort,
                             @RequestParam(defaultValue = "asc") String dir,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "200") int size,
                             @RequestParam(name = "f_name", required = false) String fName,
                             @RequestParam(name = "f_platform", required = false) String fPlatform,
                             @RequestParam(name = "f_format", required = false) String fFormat,
                             @RequestParam(name = "f_status", required = false) String fStatus,
                             @RequestParam(name = "f_price_min", required = false) String fPriceMin,
                             @RequestParam(name = "f_price_max", required = false) String fPriceMax,
                             @RequestParam(name = "f_available", required = false) String fAvailable,
                             @RequestParam(name = "f_images", required = false) String fImages,
                             @RequestParam(name = "f_rating", required = false) String fRating,
                             @RequestParam(name = "f_edition", required = false) String fEdition,
                             @RequestParam(name = "f_tags", required = false) String fTags,
                             @RequestParam(name = "f_purchased", required = false) String fPurchased,
                             @RequestParam(defaultValue = "false") boolean dups,
                             HttpServletRequest req) {
        Entry c = repo.collection(uid, id);
        // Открыта без параметров (с главной и т. п.) — запомненный вид списка этой коллекции: сортировка, страница, фильтры
        if (req.getQueryString() == null || req.getQueryString().isEmpty()) {
            String saved = repo.collectionView(uid, id);
            if (isView(saved)) return "redirect:" + collectionUrl(id, saved);
        }
        Set<String> fields = repo.itemFields(uid, c.kind());
        m.addAttribute("statusColors", repo.statusColors(uid, c.kind()));

        List<Col> cols = new ArrayList<>();
        cols.add(new Col("name", "field.name", "title"));
        if (fields.contains("platform")) cols.add(new Col("platform", "field.platform", ""));
        if (fields.contains("format")) cols.add(new Col("format", "field.format", ""));
        if (fields.contains("edition")) cols.add(new Col("edition", "field.edition", ""));
        if (fields.contains("status")) cols.add(new Col("status", "field.statusShort", ""));
        if (fields.contains("rating")) cols.add(new Col("rating", "field.rating", "rating"));
        // Цена: открыто (настройка «Отображение») или в подсказке у иконки глаза
        boolean showPrices = repo.showPrices(uid);
        m.addAttribute("showPrices", showPrices);
        if (fields.contains("price")) cols.add(showPrices ? new Col("price", "field.price", "num") : new Col("price", "col.priceEye", "eye"));
        if (fields.contains("purchased")) cols.add(new Col("purchased", "field.purchased", "date"));
        if (fields.contains("available")) cols.add(new Col("available", "field.available", "flag"));
        if (fields.contains("tags")) cols.add(new Col("tags", "field.tags", "tags"));

        String requested = sort;
        if (cols.stream().noneMatch(col -> col.key().equals(requested))) sort = "name";
        boolean desc = "desc".equals(dir);
        if (!PAGE_SIZES.contains(size)) size = 200;

        // Фильтры по столбцам (только по тем, что есть у типа коллекции); пустые не учитываются
        Map<String, String> filters = new java.util.LinkedHashMap<>();
        putFilter(filters, "name", fName);
        if (fields.contains("platform")) putFilter(filters, "platform", fPlatform);
        if (fields.contains("format")) putFilter(filters, "format", fFormat);
        if (fields.contains("status")) putFilter(filters, "status", fStatus);
        if (fields.contains("edition")) putFilter(filters, "edition", fEdition);
        if (fields.contains("tags")) putFilter(filters, "tags", fTags);
        if (fields.contains("purchased") && fPurchased != null && fPurchased.matches("\\d{4}")) filters.put("purchased", fPurchased);
        // Цена «от — до» (включительно): только целые числа, остальное не учитывается
        if (fields.contains("price")) {
            putPriceFilter(filters, "price_min", fPriceMin);
            putPriceFilter(filters, "price_max", fPriceMax);
        }
        // Наличие: «1» — есть, «0» — нет
        if (fields.contains("available") && ("1".equals(fAvailable) || "0".equals(fAvailable)))
            filters.put("available", fAvailable);
        // Рейтинг: «0» — без рейтинга, «1»…«5» — не меньше стольких звёзд
        if (fields.contains("rating") && fRating != null && fRating.matches("[0-5]")) filters.put("rating", fRating);
        // Картинки (есть у всех объектов): «any» — есть, «0» — нет, «1»…«5» — ровно столько
        if (fImages != null && fImages.matches("any|[0-" + ImageFiles.MAX_IMAGES + "]")) filters.put("images", fImages);
        Map<String, List<String>> filterOptions = new java.util.HashMap<>();
        for (String f : Repo.SELECT_FILTERS)
            if (fields.contains(f)) filterOptions.put(f, repo.distinctValues(id, f));
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
        m.addAttribute("sortable", Repo.ITEM_SORT.keySet());
        // Массовые действия: куда можно перенести объекты — другие коллекции пользователя
        m.addAttribute("moveTargets", repo.collections(uid).stream().filter(x -> x.id() != id).toList());
        List<Entry> pageItems = found.subList(fromIdx, Math.min(fromIdx + size, total));
        m.addAttribute("items", pageItems);
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
        m.addAttribute("size", size);
        m.addAttribute("sizes", PAGE_SIZES);
        m.addAttribute("total", total);
        m.addAttribute("from", total == 0 ? 0 : fromIdx + 1);
        m.addAttribute("to", Math.min(page * size, total));
        m.addAttribute("pageLinks", pageLinks(page, pages));
        // Текущий вид списка — чтобы после изменения/удаления объекта вернуться туда же
        String view = "sort=" + sort + "&dir=" + (desc ? "desc" : "asc") + "&page=" + page + "&size=" + size + filterQs;
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
            if (!v.matches("\\d{1,18}")) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
            target = repo.collection(uid, Long.parseLong(v)).id(); // чужая коллекция — 404
        } else if (!action.equals("delete") && !(Set.of("available", "status", "rating").contains(action) && fields.contains(action))) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        List<Long> chosen = ids == null ? List.of() : ids;
        // сначала проверка всех (чужой или из другой коллекции — 404, ничего не меняется), потом изменения
        // (без общей транзакции: удаление сразу освобождает файлы картинок)
        for (long itemId : chosen)
            if (repo.item(uid, itemId).collectionId() != id)
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        if (action.equals("status")) Repo.checkStatus(v); // неверный статус — 400 до изменений
        if (action.equals("rating") && !(v.matches("\\d{1,2}") && Integer.parseInt(v) <= 10))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        for (long itemId : chosen) {
            switch (action) {
                case "available" -> repo.setAvailable(itemId, "1".equals(v));
                case "status" -> repo.setStatus(itemId, v);
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
    public String createItem(@SessionAttribute("uid") long uid, @PathVariable long cid, @RequestParam String name,
                             @RequestParam(required = false) String platform,
                             @RequestParam(required = false) String format,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) Integer price,
                             @RequestParam(required = false) List<String> available,
                             @RequestParam(required = false) Integer rating,
                             @RequestParam(required = false) String oldgames,
                             @RequestParam(required = false) String purchased,
                             @RequestParam(required = false) String tags,
                             @RequestParam(required = false) String edition,
                             @RequestParam(required = false) String notes,
                             @RequestParam Map<String, MultipartFile> files,
                             @RequestParam(required = false) String description,
                             @RequestParam(required = false) String view) {
        Entry c = repo.collection(uid, cid);
        long id = repo.createItem(cid);
        repo.saveItem(uid, id, c.kind(), name, platform, format, status, price, available(available), rating,
                oldgames, new Repo.Details(purchased, tags, edition, notes), newImages(files), Set.of(), description);
        return "redirect:" + collectionUrl(cid, view);
    }

    @GetMapping("/items/{id}/edit")
    public String editItemForm(@SessionAttribute("uid") long uid, @PathVariable long id, @RequestParam(required = false) String view) {
        Entry it = repo.item(uid, id);
        String v = isView(view) ? view : repo.collectionView(uid, it.collectionId());
        return "redirect:" + withParam(collectionUrl(it.collectionId(), v), "open=" + id);
    }

    /** Добавляет параметр к адресу (с «?» или «&»). */
    private static String withParam(String url, String param) {
        return url + (url.contains("?") ? "&" : "?") + param;
    }

    /**
     * Данные объекта для окна объекта (поиск и открытие по адресу open=<id> — когда объекта нет в таблице на странице):
     * те же ключи, что у data-атрибутов строки таблицы, и fields — поля типа его коллекции.
     */
    @GetMapping("/items/{id}/data")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> itemData(@SessionAttribute("uid") long uid, @PathVariable long id) {
        Entry it = repo.item(uid, id);
        Map<String, Object> d = new java.util.HashMap<>();
        d.put("id", String.valueOf(it.id()));
        d.put("cid", String.valueOf(it.collectionId()));
        d.put("fields", String.join(",", repo.itemFields(uid, repo.collection(uid, it.collectionId()).kind())));
        d.put("name", it.name());
        d.put("platform", it.platform());
        d.put("format", it.format());
        d.put("status", it.status());
        d.put("price", it.price() == null ? null : String.valueOf(it.price()));
        d.put("description", it.description());
        d.put("available", Boolean.TRUE.equals(it.available()) ? "1" : "0");
        d.put("pics", it.pics());
        d.put("rating", it.rating() == null ? null : String.valueOf(it.rating()));
        d.put("oldgames", it.oldGamesId());
        d.put("purchased", it.purchased());
        d.put("tags", it.tags());
        d.put("edition", it.edition());
        d.put("notes", it.notes());
        return d;
    }

    @PostMapping("/items/{id}/edit")
    public String editItem(@SessionAttribute("uid") long uid, @PathVariable long id, @RequestParam String name,
                           @RequestParam(required = false) String platform,
                           @RequestParam(required = false) String format,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Integer price,
                           @RequestParam(required = false) List<String> available,
                           @RequestParam(required = false) Integer rating,
                           @RequestParam(required = false) String oldgames,
                           @RequestParam(required = false) String purchased,
                           @RequestParam(required = false) String tags,
                           @RequestParam(required = false) String edition,
                           @RequestParam(required = false) String notes,
                           @RequestParam Map<String, MultipartFile> files,
                           @RequestParam(name = "remove_image", required = false) String removeImage,
                           @RequestParam(name = "remove", required = false) List<Integer> remove,
                           @RequestParam(required = false) String description,
                           @RequestParam(required = false) String view,
                           @RequestParam(required = false) String q) {
        Entry it = repo.item(uid, id);
        repo.saveItem(uid, id, repo.collection(uid, it.collectionId()).kind(), name, platform, format, status, price,
                available(available), rating, oldgames, new Repo.Details(purchased, tags, edition, notes), newImages(files),
                removed(remove, removeImage != null), description);
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

    /**
     * Новые картинки из формы: «image_N» — на место N (окно объекта), «image» — на место первой (страница объекта).
     */
    private static Map<Integer, MultipartFile> newImages(Map<String, MultipartFile> files) {
        Map<Integer, MultipartFile> res = new java.util.HashMap<>();
        MultipartFile first = files.get("image");
        if (first != null && !first.isEmpty()) res.put(0, first);
        for (int pos = 0; pos < ImageFiles.MAX_IMAGES; pos++) {
            MultipartFile f = files.get("image_" + pos);
            if (f != null && !f.isEmpty()) res.put(pos, f);
        }
        return res;
    }

    /** Какие картинки удалить: номера из «remove» (окно объекта), «remove_image» — первую (страница объекта). */
    private static Set<Integer> removed(List<Integer> remove, boolean removeFirst) {
        Set<Integer> res = new java.util.HashSet<>(remove == null ? List.of() : remove);
        if (removeFirst) res.add(0);
        return res;
    }

    /**
     * Галочка «Наличие»: в форме перед ней скрытое поле available=0, отмеченная галочка добавляет available=1.
     * Нет ни одного значения — поля нет в форме, наличие не меняется.
     */
    private static Boolean available(List<String> values) {
        return values == null ? null : values.contains("1");
    }

    /**
     * Картинка по хешу содержимого: /img/<хеш> — сама картинка, /img/<хеш>/thumb — уменьшенная копия (главная, шапка
     * коллекции, поиск; если уменьшать нечего — сама картинка). Содержимое по адресу никогда не меняется (другая
     * картинка — другой хеш), поэтому браузер хранит её год и не спрашивает снова. Только свои картинки, иначе 404.
     */
    @GetMapping({"/img/{hash}", "/img/{hash}/thumb"})
    public ResponseEntity<byte[]> picture(@SessionAttribute("uid") long uid, @PathVariable String hash, HttpServletRequest req) {
        ImageFiles.Pic pic = hash.matches("[0-9a-f]{64}") ? repo.ownedPicture(uid, hash) : null;
        if (pic == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        ImageFiles.Thumb thumb = req.getRequestURI().endsWith("/thumb") ? repo.thumb(pic) : null;
        byte[] body = thumb != null ? thumb.bytes() : repo.pictureBytes(pic);
        if (body == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePrivate().immutable())
                .contentType(MediaType.parseMediaType(thumb != null ? thumb.mime() : pic.mime()))
                .body(body);
    }

    /** Картинка объекта; без кеша в браузере, чтобы после замены сразу была видна новая. */
    @GetMapping("/items/{id}/image")
    public ResponseEntity<byte[]> itemImage(@SessionAttribute("uid") long uid, @PathVariable long id,
                                            @RequestParam(defaultValue = "0") int n) {
        Map<String, Object> row = repo.itemImage(uid, id, n);
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noCache().cachePrivate())
                .contentType(MediaType.parseMediaType((String) row.get("image_mime")))
                .body((byte[]) row.get("image"));
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
    public String createKind(@SessionAttribute("uid") long uid, @RequestParam String name, @RequestParam(required = false) List<String> fields,
                             @RequestParam(name = "st", required = false) List<String> statuses,
                             @RequestParam(name = "stc", required = false) List<String> colors) {
        repo.saveKind(uid, null, name, fields, statuses, colors);
        return "redirect:/kinds";
    }

    @GetMapping("/kinds/{id}/edit")
    public String editKindForm(@SessionAttribute("uid") long uid, @PathVariable long id, Model m) {
        return kindForm(uid, m, repo.kind(uid, id), "kinds.kind");
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

    private String kindForm(long uid, Model m, Kind kind, String title) {
        Map<String, String> colors = kind == null ? Map.of() : kind.statusColors();
        m.addAttribute("kind", kind);
        m.addAttribute("statusColors", colors);
        // пустые строки «статус — цвет»: по одной на каждый статус из списка, у которого ещё нет цвета
        List<String> lower = Repo.STATUSES.stream().map(s -> s.toLowerCase(java.util.Locale.ROOT)).toList();
        long colored = colors.keySet().stream().filter(s -> lower.contains(s.toLowerCase(java.util.Locale.ROOT))).count();
        m.addAttribute("statusesLower", lower);
        m.addAttribute("emptyRows", IntStream.range(0, (int) Math.max(0, Repo.STATUSES.size() - colored)).boxed().toList());
        m.addAttribute("fieldNames", Repo.FIELDS);
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
