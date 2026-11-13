package app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final Remember remember;
    private final ImageImport images;
    private final CacheClear cacheClear;
    private final OldGames oldGames;
    private final Backup backup;
    private final Updates updates;
    private final XlsxImport xlsxImport;
    private final XlsxExport xlsxExport;

    public WebController(Repo repo, LoginGuard guard, Remember remember, ImageImport images, CacheClear cacheClear, OldGames oldGames,
                         Backup backup, Updates updates, XlsxImport xlsxImport, XlsxExport xlsxExport) {
        this.remember = remember;
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

    // ---------- Sign-in, users, password ----------
    // Who is signed in is kept in the session ("uid"); without signing in pages are not available (see WebConfig).

    /** Session attribute with the password hash at sign-in: after a password change old sessions are invalid (WebConfig). */
    static final String SESSION_PW = "pw";

    /** Hash for checking the password of a non-existent user — so names cannot be learned from the response time. */
    private static final String DUMMY_HASH = Passwords.hash("dummy-password");

    @GetMapping("/login")
    public String loginPage(HttpServletRequest request, Model m) {
        // the computer is remembered — straight into the application (WebConfig signs in)
        Long uid = remember.user(request);
        if (uid != null && repo.user(uid) != null) return "redirect:/";
        return loginForm(m);
    }

    private String loginForm(Model m) {
        m.addAttribute("setup", repo.countUsers() == 0);
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String name, @RequestParam(defaultValue = "") String password,
                        @RequestParam(defaultValue = "false") boolean remember,
                        HttpServletRequest request, HttpServletResponse response, Model m) {
        // Protection against guessing: address + name (5 attempts) and address with any names (30 attempts)
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
        if (remember) this.remember.issue(u.id(), response);
        return "redirect:/";
    }

    // First start (no users yet): an administrator is created on the login page.

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

    // ---------- Settings: the "Password" and "Users" tabs (the "Collection types" tab — /kinds) ----------

    @GetMapping("/account")
    public String account(@SessionAttribute("uid") long uid) {
        return "account";
    }

    // "Display" tab: show prices openly or behind the eye icon; how many collections and items per page
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

    /** Number per page from the "Display" form: a whole number within min…max, otherwise 400. */
    private static int pageSize(String value, int min, int max) {
        String v = value == null ? "" : value.trim();
        if (v.matches("\\d{1,4}")) {
            int n = Integer.parseInt(v);
            if (n >= min && n <= max) return n;
        }
        throw Fail.badRequest("display.badPageSize", min, max);
    }

    // "Import" tab: pictures of collection items from Steam or Discogs (in the background, progress — /import/status)
    @GetMapping("/import")
    public String importPage(@SessionAttribute("uid") long uid, Model m) {
        List<Entry> collections = repo.collections(uid);
        m.addAttribute("collections", collections);
        // profile of the type of each collection — for choosing the default source
        Map<String, String> profileOf = new java.util.HashMap<>();
        for (Kind k : repo.kinds(uid)) profileOf.put(k.name(), k.profile());
        Map<Long, String> profiles = new java.util.HashMap<>();
        for (Entry c : collections) profiles.put(c.id(), profileOf.getOrDefault(c.kind(), "other"));
        m.addAttribute("profiles", profiles);
        m.addAttribute("job", images.job(uid));
        String token = repo.token(uid, ImageImport.DISCOGS);
        m.addAttribute("discogsReady", token != null);
        // the token itself is not shown on the page — only its last 4 characters
        m.addAttribute("discogsTokenEnd", token == null ? null : token.substring(Math.max(0, token.length() - 4)));
        m.addAttribute("kinds", repo.kinds(uid));
        return "import";
    }

    /** Loading collections from Excel: each sheet is a new collection; the result or an error — on the "Import" page, in the loading block. */
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

    /** The user's personal Discogs token: save or delete (the "Delete token" button). */
    @PostMapping("/import/discogs-token")
    public String discogsToken(@SessionAttribute("uid") long uid, @RequestParam(defaultValue = "") String token,
                               @RequestParam(defaultValue = "false") boolean delete) {
        token = delete ? "" : token.trim();
        if (!delete && token.isEmpty()) return "redirect:/import"; // empty field — the token does not change
        // a Discogs token — Latin letters and digits; the length limit has a margin
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
        Entry c = repo.collection(uid, collection); // someone else's collection — 404
        // while pictures of the collection are being deleted ("Cache"), no import into it is started
        if (cacheClear.active(collection))
            throw Fail.conflict("import.cacheBusy");
        if (!ImageImport.SOURCES.containsKey(source))
            throw Fail.badRequest("import.badSource");
        // Discogs covers — only with a token; without it — descriptions only
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

    // "Cache" tab: delete all pictures of the items of the selected collection (in the background, progress — /cache/status)
    @GetMapping("/cache")
    public String cachePage(@SessionAttribute("uid") long uid, Model m) {
        m.addAttribute("collections", repo.collections(uid));
        m.addAttribute("imageCounts", repo.collectionImageCounts(uid));
        m.addAttribute("job", cacheClear.job(uid));
        return "cache";
    }

    @PostMapping("/cache/clear")
    public String cacheClear(@SessionAttribute("uid") long uid, @RequestParam long collection) {
        Entry c = repo.collection(uid, collection); // someone else's collection — 404
        ImageImport.Job j = images.job(uid);
        // while an import into this collection runs, it would write the pictures back right away
        if (j != null && j.active() && j.collectionId == collection)
            throw Fail.conflict("cache.importBusy");
        cacheClear.start(uid, c, repo.collectionImageCounts(uid).getOrDefault(collection, 0)); // already running — not started
        return "redirect:/cache";
    }

    @GetMapping("/cache/status")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> cacheStatus(@SessionAttribute("uid") long uid) {
        CacheClear.Job j = cacheClear.job(uid);
        if (j == null) return Map.of("state", "none");
        return Map.of("state", j.state, "total", j.total, "done", j.done);
    }

    // "Export" tab: selected collections into an Excel file, each on its own sheet (pictures are not exported)
    @GetMapping("/export")
    public String exportPage(@SessionAttribute("uid") long uid, Model m) {
        m.addAttribute("collections", repo.collections(uid));
        return "export";
    }

    @PostMapping("/export")
    public Object export(@SessionAttribute("uid") long uid, @RequestParam(name = "c", required = false) List<Long> ids,
                         Model m) throws java.io.IOException {
        // Only own collections, in list order (alphabetically)
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
                // File name: in Latin for old browsers and in Russian (filename*, RFC 5987) for the rest
                .header("Content-Disposition", "attachment; filename=\"collections-" + java.time.LocalDate.now()
                        + ".xlsx\"; filename*=UTF-8''" + java.net.URLEncoder.encode(file, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20"))
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(out.toByteArray());
    }

    /** Application version (from pom.xml, see app.version in application.properties). */
    @org.springframework.beans.factory.annotation.Value("${app.version}")
    private String appVersion;

    /** Author, email and update date — the build date (app.author, app.email, app.build-date in application.properties). */
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

    // "Backup" tab (administrator only): all application data into a zip and restoring from it

    @GetMapping("/backup")
    public String backupPage(@SessionAttribute("uid") long uid, Model m) {
        requireAdmin(uid);
        m.addAttribute("backupSize", String.format(org.springframework.context.i18n.LocaleContextHolder.getLocale(), "%.1f",
                backup.approximateSize() / 1048576.0));
        return "backup";
    }

    /** The backup archive goes straight into the response (without an intermediate file): database and pictures. */
    @GetMapping("/backup/download")
    public void backupDownload(@SessionAttribute("uid") long uid, jakarta.servlet.http.HttpServletResponse res)
            throws java.io.IOException {
        requireAdmin(uid);
        res.setContentType("application/zip");
        res.setHeader("Content-Disposition", "attachment; filename=\"collections-backup-" + java.time.LocalDate.now() + ".zip\"");
        backup.write(res.getOutputStream());
    }

    /**
     * Restore: the backup file comes as the request body (a page script, with upload progress) — this way the
     * picture upload size limit does not apply to it. Answer: {ok} or {error}.
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
        m.addAttribute("me", uid);
        return "users";
    }

    /**
     * A new password for another user (including an administrator) — administrator only, without the current password.
     * Own password — on the "Password" tab (the current one is needed there). The user's sessions end: the password hash in them is outdated.
     */
    @PostMapping("/users/{id}/password")
    public String setUserPassword(@SessionAttribute("uid") long uid, @PathVariable long id,
                                  @RequestParam(defaultValue = "") String password,
                                  @RequestParam(defaultValue = "") String password2, Model m) {
        requireAdmin(uid);
        Repo.User u = repo.user(id);
        if (u == null) throw Fail.notFound();
        if (id == uid) return "redirect:/account";
        String error = Passwords.problem(password, u.name());
        if (error == null && !password.equals(password2)) error = I18n.t("user.passwordsDiffer");
        if (error != null) {
            m.addAttribute("pwError", error);
            m.addAttribute("pwUser", id);
            return users(uid, m);
        }
        repo.setPassword(id, password);
        return "redirect:/users?passwordSet=" + java.net.URLEncoder.encode(u.name(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Adding a user — administrator only (in "Settings"). */
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

    /** Checking the data of a new user; null — everything is fine. */
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
    public String logout(HttpSession session, HttpServletRequest request, HttpServletResponse response) {
        remember.forget(request, response); // signing out on a remembered computer — forget it
        session.invalidate();
        return "redirect:/login";
    }

    @PostMapping("/account/password")
    public String changePassword(@SessionAttribute("uid") long uid, @RequestParam(defaultValue = "") String current,
                                 @RequestParam(defaultValue = "") String password,
                                 @RequestParam(defaultValue = "") String password2, HttpSession session,
                                 HttpServletRequest request, HttpServletResponse response, Model m) {
        String error = null;
        // Guessing the current password is limited too: 5 failed attempts — a lock
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
        boolean remembered = remember.user(request) != null;
        repo.setPassword(uid, password); // deletes the "Remember me" keys on all computers
        if (remembered) remember.issue(uid, response); // this computer stays remembered
        // This session stays, the user's other sessions end (the password hash changed)
        session.setAttribute(SESSION_PW, repo.user(uid).passwordHash());
        return "redirect:/account?passwordChanged";
    }

    /** Sign-in: a new session (protection against session fixation), the user id and their password hash in it. */
    private void signIn(HttpServletRequest request, long uid) {
        HttpSession old = request.getSession(false);
        if (old != null) old.invalidate();
        HttpSession s = request.getSession(true);
        s.setAttribute("uid", uid);
        s.setAttribute(SESSION_PW, repo.user(uid).passwordHash());
    }

    // ---------- Collections ----------

    @GetMapping("/")
    public String index(@SessionAttribute("uid") long uid, Model m, @RequestParam(defaultValue = "1") int page) {
        List<Entry> all = repo.collections(uid);
        // Collections per page — the "Display" setting
        int perPage = repo.pageSizes(uid).collections();
        int total = all.size();
        int pages = Math.max(1, (total + perPage - 1) / perPage);
        page = Math.min(Math.max(page, 1), pages);
        int from = (page - 1) * perPage;
        List<Entry> shown = all.subList(from, Math.min(from + perPage, total));
        m.addAttribute("collections", shown);
        // Counters under the name: total, in stock and value — the last two if the collection type has such fields
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

    /** Search across all collections: items (name, tags, region / edition, notes) and collections by name. */
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
            // fields of the type of each collection — to show status and availability only where they exist
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

    /** Collection status on the statistics page: value, how many items, row colour (or null). */
    public record StatusCount(String status, int count, String color) {}

    /** Statistics: totals for all collections and for each, ratings, purchases by year, frequent tags. */
    @GetMapping("/stats")
    public String stats(@SessionAttribute("uid") long uid, Model m) {
        List<Entry> all = repo.collections(uid);
        Map<Long, Repo.Stats> stats = repo.stats(uid);
        Map<Long, Set<String>> fields = new java.util.HashMap<>();
        Map<Long, String> prices = new java.util.HashMap<>();
        Map<Long, Map<String, Integer>> statusCounts = repo.statusCounts(uid);
        // collection statuses — in the order of its type's list (with colour), then previous values not in the list
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
        // totals: the most frequent statuses (up to 4), with items
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

    /** Item table column: sort key, title (text), css class. */
    public record Col(String key, String label, String css) {}

    /** Column title: the own field label in the type or the standard one (text key defaultKey — shorter than in the form). */
    private static String colLabel(Kind kind, String role, String defaultKey) {
        Kind.Field f = kind == null ? null : kind.field(role);
        return f != null && f.label() != null ? f.label() : I18n.t(defaultKey);
    }

    /**
     * Item table filters from the f_<field key> parameters — by field kind: the name and values from lists (platform,
     * format, status, region, tag, custom text and list) — as is; purchase year — 4 digits; price "from — to" (f_price_min,
     * f_price_max) — whole numbers; available and custom yes/no — "1" / "0"; rating — "0" (no rating) or "1"…"5" (at least
     * this many stars); pictures (every item has them) — "any", "0"…"5". Only by fields of the collection type.
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

    /** List values for a filter: first the values from the field settings, then previous values not in the list. */
    private static List<String> mergeOptions(List<String> options, List<String> used) {
        List<String> res = new ArrayList<>(options);
        for (String u : used) if (!res.contains(u)) res.add(u);
        return res;
    }

    /**
     * Values of custom fields for display in the table: item id → ("c<id>" → text): a number — with digit grouping, a date —
     * by the interface language, yes/no — "1" (the cell draws a tick), the rest — as is.
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

    /** Labels of type fields for templates: role → label (own or standard); without a type — standard ones. */
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
        // Opened without parameters (from the home page etc.) — the remembered list view of this collection: sorting, page, filters
        if (req.getQueryString() == null || req.getQueryString().isEmpty()) {
            String saved = repo.collectionView(uid, id);
            if (isView(saved)) return "redirect:" + collectionUrl(id, saved);
        }
        Kind kind = repo.kindByName(uid, c.kind());
        Set<String> fields = kind == null ? Repo.FIELDS.keySet() : kind.fields();
        m.addAttribute("statusColors", repo.statusColors(uid, c.kind()));
        m.addAttribute("kind", kind);
        m.addAttribute("labels", labels(kind));

        // Columns — in the order of type fields; the title — the own field label in the type or the standard one
        boolean showPrices = repo.showPrices(uid);
        m.addAttribute("showPrices", showPrices);
        List<Col> cols = new ArrayList<>();
        cols.add(new Col("name", I18n.t("field.name"), "title"));
        for (String f : fields) {
            Col col = switch (f) {
                case "platform", "format", "edition" -> new Col(f, colLabel(kind, f, "field." + f), "");
                case "status" -> new Col(f, colLabel(kind, f, "field.statusShort"), "");
                case "rating" -> new Col(f, colLabel(kind, f, "field.rating"), "rating");
                // Price: openly (the "Display" setting) or in the eye icon tooltip
                case "price" -> showPrices ? new Col(f, colLabel(kind, f, "field.price"), "num") : new Col(f, I18n.t("col.priceEye"), "eye");
                case "purchased" -> new Col(f, colLabel(kind, f, "field.purchased"), "date");
                case "available" -> new Col(f, colLabel(kind, f, "field.available"), "flag");
                case "tags" -> new Col(f, colLabel(kind, f, "field.tags"), "tags");
                default -> null; // notes and Old-Games.ru are not shown in the table
            };
            if (col != null) cols.add(col);
        }
        // custom fields — also in type order (long text is not shown in the table)
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
        // columns — in the order of type fields (the name first)
        cols.sort(java.util.Comparator.comparingInt(col -> col.key().equals("name") ? -1 : order.indexOf(col.key())));
        m.addAttribute("customTypes", customTypes);

        String requested = sort;
        if (cols.stream().noneMatch(col -> col.key().equals(requested))) sort = "name";
        boolean desc = "desc".equals(dir);
        // Items per page — the "Display" setting
        int size = repo.pageSizes(uid).items();

        // Column filters (f_<field key> parameters; only by fields of the collection type); empty and invalid ones are ignored
        Map<String, String> filters = filters(kind, fields, req);
        // Values for filter dropdowns: lists (statuses, custom lists) — in the order of the type settings, then other values
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

        // "Duplicates" mode: only items that have an item with the same name in the collection
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
        // sorting is possible by built-in fields from ITEM_SORT and by custom ones (except long text — it is not in the table)
        Set<String> sortable = new java.util.HashSet<>(Repo.ITEM_SORT.keySet());
        sortable.addAll(customTypes.keySet());
        m.addAttribute("sortable", sortable);
        // Bulk actions: where items can be moved — the user's other collections
        m.addAttribute("moveTargets", repo.collections(uid).stream().filter(x -> x.id() != id).toList());
        List<Entry> pageItems = found.subList(fromIdx, Math.min(fromIdx + size, total));
        m.addAttribute("items", pageItems);
        Map<Long, Map<Long, Object>> custom = repo.customValues(pageItems.stream().map(Entry::id).toList());
        m.addAttribute("itemJson", itemJson(pageItems, it -> kind, custom));
        m.addAttribute("customText", customText(kind, custom));
        m.addAttribute("kindJson", kindJson(kind));
        // In duplicates mode sorted by name — the first row of each group (with a line above it)
        Set<Long> groupStarts = new java.util.HashSet<>();
        if (dups && sort.equals("name"))
            for (int i = fromIdx; i < fromIdx + pageItems.size(); i++)
                if (i == 0 || !dupKey(found.get(i).name()).equals(dupKey(found.get(i - 1).name())))
                    groupStarts.add(found.get(i).id());
        m.addAttribute("groupStarts", groupStarts);
        // Total value — only if the collection type has the "Price" field; format "717 841" (in English "717,841")
        if (fields.contains("price"))
            m.addAttribute("totalPrice", formatPrice(repo.totalPrice(id)));
        m.addAttribute("dups", dups);
        m.addAttribute("dupGroups", dupGroups);
        m.addAttribute("modeQs", modeQs);
        m.addAttribute("filters", filters);
        m.addAttribute("filterOptions", filterOptions);
        m.addAttribute("filterQs", filterQs.toString());
        // Total without filters: in duplicates mode — all duplicates, otherwise — all collection items
        m.addAttribute("totalAll", dups ? dupIds.size() : filters.isEmpty() ? total : repo.countItems(id));
        m.addAttribute("sort", sort);
        m.addAttribute("dir", desc ? "desc" : "asc");
        m.addAttribute("page", page);
        m.addAttribute("pages", pages);
        m.addAttribute("total", total);
        m.addAttribute("from", total == 0 ? 0 : fromIdx + 1);
        m.addAttribute("to", Math.min(page * size, total));
        m.addAttribute("pageLinks", pageLinks(page, pages));
        // The current list view — to return to it after changing/deleting an item
        String view = "sort=" + sort + "&dir=" + (desc ? "desc" : "asc") + "&page=" + page + filterQs;
        m.addAttribute("view", view + modeQs);
        // Remember the view for this user and collection ("Duplicates" mode is not remembered)
        if (!dups) repo.setCollectionView(uid, id, view);
        return "collection";
    }

    /** Amount in roubles with digit grouping by the interface language: "717 841" (in English "717,841"). */
    private static String formatPrice(long sum) {
        return String.format(org.springframework.context.i18n.LocaleContextHolder.getLocale(), "%,d", sum)
                .replace('\u00a0', ' ').replace('\u202f', ' ');
    }

    /**
     * A bulk action on the selected collection items: availability, status, rating (if the type has the field),
     * moving to another collection, deletion. Someone else's items and items not from this collection — 404. Then — the same list view.
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
            target = repo.collection(uid, Long.parseLong(v)).id(); // someone else's collection — 404
        } else if (!action.equals("delete") && !(Set.of("available", "status", "rating").contains(action) && fields.contains(action))) {
            throw Fail.badRequest();
        }
        List<Long> chosen = ids == null ? List.of() : ids;
        // first check all (someone else's or from another collection — 404, nothing changes), then the changes
        // (without a shared transaction: deletion frees picture files right away)
        for (long itemId : chosen)
            if (repo.item(uid, itemId).collectionId() != id)
                throw Fail.notFound();
        // invalid status (not from the collection type list) — 400 before any changes
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

    /** Page numbers for navigation: the first, the last and those next to the current one; 0 — a gap ("…"). */
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
     * Address of the collection page with the saved list view (sorting, page, filters).
     * view — only from our own link: only parameter and URL-encoding characters are allowed.
     */
    private static String collectionUrl(long cid, String view) {
        return "/collections/" + cid + (isView(view) ? "?" + view : "");
    }

    /** Where to return from an item page: to the search (q — the query, if the item was opened from the search) or to the collection. */
    private static String backUrl(long cid, String view, String q) {
        return q != null ? "/search?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8)
                : collectionUrl(cid, view);
    }

    private static boolean isView(String view) {
        return view != null && !view.isEmpty() && view.matches("[A-Za-z0-9=&%+._*-]*");
    }

    /** Key for finding duplicates: the name ignoring case and extra spaces. */
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
        repo.collection(uid, id); // 404 if there is none
        repo.saveCollection(uid, id, name, kind, image, removeImage != null);
        return "redirect:/collections/" + id;
    }

    @PostMapping("/collections/{id}/delete")
    public String deleteCollection(@SessionAttribute("uid") long uid, @PathVariable long id) {
        repo.collection(uid, id); // 404 if the collection is someone else's
        repo.delete("collections", id);
        return "redirect:/";
    }

    // ---------- Items ----------

    /**
     * There are no item pages — an item is created and changed in the item window. Old addresses (bookmarks, opening in a new
     * tab) lead to the collection with the remembered list view; the script opens the window by the new=1 / open=<id> parameter.
     */
    @GetMapping("/collections/{cid}/items/new")
    public String newItemForm(@SessionAttribute("uid") long uid, @PathVariable long cid) {
        repo.collection(uid, cid); // someone else's — 404
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
     * Custom fields from the item window: cf_<field id> parameters (yes/no has a hidden 0 and a tick 1: the last value is taken).
     * Fields not in the form do not change; fields not of the item's collection type are skipped by Repo.setCustom.
     */
    private void saveCustoms(long itemId, HttpServletRequest req) {
        req.getParameterMap().forEach((name, values) -> {
            if (name.matches("cf_\\d{1,18}") && values.length > 0)
                repo.setCustom(itemId, Long.parseLong(name.substring(3)), values[values.length - 1]);
        });
    }

    /** Adds a parameter to an address (with "?" or "&"). */
    private static String withParam(String url, String param) {
        return url + (url.contains("?") ? "&" : "?") + param;
    }

    /** Item data for the item window by the address open=<id> (the item may not be in the table on the page). */
    @GetMapping("/items/{id}/data")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> itemData(@SessionAttribute("uid") long uid, @PathVariable long id) {
        Entry it = repo.item(uid, id);
        return itemData(it, repo.kindByName(uid, repo.collection(uid, it.collectionId()).kind()),
                repo.customValues(List.of(id)).getOrDefault(id, Map.of()));
    }

    /**
     * Item data for the item window — the single definition: both for rows of the collection table and the search (the
     * data-item attribute, JSON) and for /items/{id}/data. fields — the fields of the item's collection type (the window shows only them).
     * Add a new item field here and to the window (base :: itemDialog).
     */
    private static Map<String, Object> itemData(Entry it, Kind kind, Map<Long, Object> custom) {
        Map<String, Object> d = new java.util.HashMap<>();
        d.put("id", it.id());
        d.put("cid", it.collectionId());
        d.putAll(kindData(kind));
        // custom fields: "c<id>" → value (as text)
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
     * What the item window takes from the collection type: fields — field roles in order (the window shows only them, in this
     * order), labels — own field labels (role → label; none — the label from the window markup), statuses — the status list.
     */
    private static Map<String, Object> kindData(Kind kind) {
        Map<String, Object> d = new java.util.HashMap<>();
        d.put("fields", String.join(",", kind == null ? Repo.FIELDS.keySet() : kind.keys()));
        // custom fields: key, label, kind, list values — the window creates rows for them
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

    /** The same for the new item window on the collection page (the data-kind attribute of the window), JSON. */
    private static String kindJson(Kind kind) {
        try {
            return JSON.writeValueAsString(kindData(kind));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Item data for the data-item attribute of table rows: id → JSON. kindOf — the item's collection type. */
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
     * Description and pictures from an Old-Games.ru game page (the "Load" button in the item window). Saves nothing:
     * the window puts the description and pictures into the form, they are saved with the "Save" button.
     * Answer: {url, description, images: [{mime, data (base64)}]} or {error}.
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

    /** New pictures from the item window: "image_N" — into place N. */
    private static Map<Integer, MultipartFile> newImages(Map<String, MultipartFile> files) {
        Map<Integer, MultipartFile> res = new java.util.HashMap<>();
        for (int pos = 0; pos < ImageFiles.MAX_IMAGES; pos++) {
            MultipartFile f = files.get("image_" + pos);
            if (f != null && !f.isEmpty()) res.put(pos, f);
        }
        return res;
    }

    /**
     * Picture by content hash: /img/<hash> — the picture itself, /img/<hash>/thumb — a smaller copy (home page, collection
     * header, search; if there is nothing to shrink — the picture itself). The content at an address never changes (another
     * picture — another hash), so the browser keeps it for a year and does not ask again. Only own pictures, otherwise 404.
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

    // ---------- Settings: collection types ----------

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
     * A field row in the type form: key (role of a built-in or "c<id>" of a custom field), standard label (empty for custom),
     * label (own or ""), whether it is on, kind of a custom field ("" for built-in), list values one per line.
     */
    public record KindFieldRow(String role, String defaultLabel, String label, boolean on, String type, String opts) {
        public boolean custom() {
            return !type.isEmpty();
        }
    }

    private String kindForm(long uid, Model m, Kind kind, String title) {
        m.addAttribute("kind", kind);
        m.addAttribute("profiles", Repo.PROFILES);
        // Fields: first the type fields in its order, then the rest of the set (off) — the order is changed with the arrows
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
        // Statuses with colours in order and two empty rows for new ones (more rows are added by the "Add status" button)
        List<Kind.Option> statuses = new ArrayList<>();
        if (kind != null && kind.field("status") != null) statuses.addAll(kind.field("status").options());
        statuses.add(new Kind.Option("", null));
        statuses.add(new Kind.Option("", null));
        m.addAttribute("statusRows", statuses);
        m.addAttribute("maxStatuses", Repo.MAX_OPTIONS);
        m.addAttribute("title", title);
        return "kind-form";
    }

    // ---------- helpers ----------

    /** Collection form (new or edit); items are changed in the item window. */
    private String form(long uid, Model m, Entry obj, String imgUrl, String title, String back) {
        m.addAttribute("obj", obj);
        m.addAttribute("kinds", repo.kinds(uid).stream().map(Kind::name).toList());
        m.addAttribute("imgUrl", imgUrl);
        m.addAttribute("title", title);
        m.addAttribute("back", back);
        return "form";
    }
}
