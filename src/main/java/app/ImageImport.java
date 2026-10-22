package app;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Импорт картинок и описаний объектов («Настройки» → «Импорт») из Steam (игры) или Discogs (музыка).
 * Steam: объект сопоставляется с игрой по названию, картинка — шапка страницы игры (header.jpg),
 * описание — краткое описание игры (по-русски, если есть).
 * Discogs: картинка — обложка, описание — поле Notes альбома (главной версии, иначе её основного издания).
 * FantLab (книги): название объекта — «Автор - Название» или просто «Название»; картинка — обложка, описание — аннотация.
 * Discogs: название объекта — «Группа - Альбом», ищется альбом с тем же названием у той же группы, картинка — обложка.
 * Импорт идёт в фоне, по одному на всё приложение (лимиты сервисов — на адрес, а не на пользователя):
 * запросы к API не чаще заданного интервала, при отказе сервиса (429/403/5xx) — пауза и повтор.
 */
@Component
public class ImageImport {

    public static final String STEAM = "steam", DISCOGS = "discogs", FANTLAB = "fantlab";
    /** Источники: ключ → название для страницы. */
    public static final Map<String, String> SOURCES = new LinkedHashMap<>();
    static {
        SOURCES.put(STEAM, "Steam");
        SOURCES.put(DISCOGS, "Discogs");
        SOURCES.put(FANTLAB, "FantLab");
    }

    /** Паузы между запросами к API, мс: Steam — около 200 за 5 минут, Discogs — до 60 в минуту с токеном. */
    @Value("${steam.api-interval-ms:1500}")
    private long steamIntervalMs;
    @Value("${discogs.api-interval-ms:1100}")
    private long discogsIntervalMs;
    /** FantLab просит не чаще раза в 1,2 с (Crawl-delay в robots.txt). */
    @Value("${fantlab.api-interval-ms:1200}")
    private long fantlabIntervalMs;
    /** Пауза, если сервис ограничил запросы, мс, и сколько раз подряд её делать. */
    @Value("${import.throttle-pause-ms:60000}")
    private long throttlePauseMs;
    static final int THROTTLE_RETRIES = 5;
    /** Столько ошибок сети подряд — сервис недоступен, импорт останавливается. */
    static final int MAX_ERRORS_IN_ROW = 10;
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** Адреса сервисов (для проверки можно подменить на заглушку). Токен Discogs — у каждого пользователя свой (вкладка «Импорт»). */
    @Value("${steam.store-url:https://store.steampowered.com}")
    private String steamUrl;
    @Value("${discogs.api-url:https://api.discogs.com}")
    private String discogsUrl;
    @Value("${fantlab.api-url:https://api.fantlab.ru}")
    private String fantlabUrl;
    /** Сайт FantLab: пути к обложкам в ответах API — относительно него. */
    @Value("${fantlab.site-url:https://fantlab.ru}")
    private String fantlabSite;
    @Value("${app.version}")
    private String appVersion;

    /** Состояние импорта пользователя (читается страницей через /import/status). */
    public static class Job {
        public final long collectionId;
        public final String collectionName;
        public final String source;
        public final boolean images, descriptions;
        /** Steam: картинка — первый скриншот из галереи игры (1920×1080), а не шапка. */
        public final boolean screenshot;
        public volatile String state = "queued"; // queued | running | done | cancelled | failed
        public volatile int total, processed, found, loaded, described, notFound, errors; // found — найдено в источнике; loaded — картинок, described — описаний
        public volatile String current = "";
        public volatile String message = "";      // пауза из-за ограничений сервиса, причина остановки
        volatile boolean cancel;
        /** Личный токен Discogs пользователя (пусто — без токена); на страницу не попадает. */
        private final String discogsToken;

        Job(long collectionId, String collectionName, String source, boolean images, boolean descriptions,
            boolean screenshot, String discogsToken) {
            this.screenshot = screenshot;
            this.discogsToken = discogsToken == null ? "" : discogsToken.trim();
            this.collectionId = collectionId;
            this.collectionName = collectionName;
            this.source = source;
            this.images = images;
            this.descriptions = descriptions;
        }

        public boolean active() {
            return state.equals("queued") || state.equals("running");
        }

        public String sourceName() {
            return SOURCES.get(source);
        }
    }

    /** Ошибка, после которой продолжать бессмысленно (например, Discogs отклонил токен). */
    private static class FatalImport extends Exception {
        FatalImport(String message) {
            super(message);
        }
    }

    private final Repo repo;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).proxy(java.net.ProxySelector.getDefault()).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "image-import");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    /** Время последнего запроса к API каждого источника. */
    private final Map<String, Long> lastCall = new ConcurrentHashMap<>();

    public ImageImport(Repo repo) {
        this.repo = repo;
    }

    /** Текущий или последний импорт пользователя (или null). */
    public Job job(long uid) {
        return jobs.get(uid);
    }

    /**
     * Запускает импорт; false — у пользователя уже идёт импорт. Права на коллекцию и источник проверяет вызывающий код.
     * onlyMissing — не трогать уже заполненное: картинку загружать только тем, у кого её нет, описание — так же.
     * discogsToken — личный токен Discogs пользователя (null — нет): без него Discogs отдаёт результаты и Notes,
     * но без обложек (и только 25 запросов в минуту).
     */
    public synchronized boolean start(long uid, Entry collection, boolean onlyMissing, String source,
                                      boolean images, boolean descriptions, boolean screenshot, String discogsToken) {
        Job old = jobs.get(uid);
        if (old != null && old.active()) return false;
        boolean hasToken = discogsToken != null && !discogsToken.isBlank();
        // Без токена обложек у Discogs нет — только описания
        boolean img = images && (!source.equals(DISCOGS) || hasToken);
        Job job = new Job(collection.id(), collection.name(), source, img, descriptions,
                screenshot && source.equals(STEAM), discogsToken);
        List<Entry> items = repo.items(collection.id(), "name", false, Map.of()).stream()
                .filter(e -> needImage(job, e, onlyMissing) || needDescription(job, e, onlyMissing)).toList();
        job.total = items.size();
        jobs.put(uid, job);
        worker.submit(() -> run(job, items, onlyMissing));
        return true;
    }

    private static boolean needImage(Job job, Entry e, boolean onlyMissing) {
        return job.images && (!onlyMissing || !e.hasImage());
    }

    private static boolean needDescription(Job job, Entry e, boolean onlyMissing) {
        return job.descriptions && (!onlyMissing || e.description() == null);
    }

    public void cancel(long uid) {
        Job job = jobs.get(uid);
        if (job != null) job.cancel = true;
    }

    private void run(Job job, List<Entry> items, boolean onlyMissing) {
        job.state = "running";
        int errorsInRow = 0;
        try {
            for (Entry item : items) {
                if (job.cancel) {
                    job.state = "cancelled";
                    return;
                }
                job.current = item.name();
                try {
                    boolean img = needImage(job, item, onlyMissing), descr = needDescription(job, item, onlyMissing);
                    Found f = switch (job.source) {
                        case DISCOGS -> findDiscogs(job, item.name(), img, descr);
                        case FANTLAB -> findFantlab(job, item.name(), img, descr);
                        default -> findSteam(job, item.name(), img, descr);
                    };
                    if (f == null) {
                        job.notFound++;
                    } else {
                        job.found++;
                        if (img && f.image() != null) {
                            repo.setItemImage(item.id(), f.image());
                            job.loaded++;
                        }
                        if (descr && f.description() != null) {
                            repo.setItemDescription(item.id(), f.description());
                            job.described++;
                        }
                    }
                    errorsInRow = 0;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    job.state = "cancelled";
                    return;
                } catch (FatalImport e) {
                    job.message = e.getMessage();
                    job.state = "failed";
                    return;
                } catch (Exception e) {
                    job.errors++;
                    if (++errorsInRow >= MAX_ERRORS_IN_ROW) {
                        job.processed++;
                        job.message = job.sourceName() + " недоступен — импорт остановлен";
                        job.state = "failed";
                        return;
                    }
                }
                job.processed++;
            }
            job.state = "done";
        } finally {
            job.current = "";
            if (!job.state.equals("failed")) job.message = "";
        }
    }

    // ---------- Steam ----------

    /** Найденное: картинка и/или описание (null — нет). */
    private record Found(byte[] image, String description) {}

    /**
     * Игра с таким же названием: картинка (если нужна) и краткое описание (если нужно); null — в Steam такой игры нет.
     * Карточка игры (appdetails, по-русски) запрашивается, только если нужно описание или шапки нет рядом с картинкой из поиска.
     */
    private Found findSteam(Job job, String name, boolean wantImage, boolean wantDescription) throws Exception {
        String key = norm(name);
        if (key.isEmpty()) return null;
        // Поиск Steam спотыкается о знаки («Batman: … - Game of the Year Edition», «F.E.A.R.»):
        // если по названию как есть совпадения нет — ещё раз по названию без знаков препинания
        String plain = name.replace(".", "").replaceAll("[^\\p{L}\\p{N}']+", " ").trim();
        for (String term : plain.equals(name) ? List.of(name) : List.of(name, plain)) {
            Found f = findSteamTerm(job, term, key, wantImage, wantDescription);
            if (f != null) return f;
        }
        return null;
    }

    private Found findSteamTerm(Job job, String term, String key, boolean wantImage, boolean wantDescription) throws Exception {
        JsonNode res = api(job, steamUrl + "/api/storesearch/?cc=us&l=english&term=" + enc(term));
        if (res == null) return null;
        for (JsonNode it : res.path("items")) {
            if (!"app".equals(it.path("type").asText("app")) || !key.equals(norm(it.path("name").asText()))) continue;
            long appId = it.path("id").asLong();
            String tiny = it.path("tiny_image").asText("");
            boolean wantShot = wantImage && job.screenshot;
            JsonNode details = null;
            String description = null;
            // описание и скриншоты — в одной карточке игры, один запрос
            if (wantDescription || wantShot) details = appDetails(job, appId, wantShot);
            if (wantDescription) description = cleanDescription(details.path("short_description").asText(""));
            byte[] img = null;
            if (wantShot) {
                // первый скриншот галереи в полном размере
                String shot = details.path("screenshots").path(0).path("path_full").asText("");
                if (!shot.isEmpty()) img = download(shot);
            }
            if (wantImage && img == null) {
                // Шапка игры: рядом с маленькой картинкой из поиска; если её там нет — адрес из карточки игры
                img = tiny.contains("/") ? download(tiny.substring(0, tiny.lastIndexOf('/') + 1) + "header.jpg") : null;
                if (img == null) {
                    if (details == null) details = appDetails(job, appId, false);
                    String header = details.path("header_image").asText("");
                    if (!header.isEmpty()) img = download(header);
                }
                if (img == null && !tiny.isEmpty()) img = download(tiny);
            }
            return new Found(img, description);
        }
        return null;
    }

    /** Карточка игры Steam (data из appdetails) на русском, со скриншотами — если нужны; пустой узел, если её нет. */
    private JsonNode appDetails(Job job, long appId, boolean screenshots) throws Exception {
        JsonNode d = api(job, steamUrl + "/api/appdetails?filters=basic" + (screenshots ? ",screenshots" : "")
                + "&l=russian&appids=" + appId);
        return d == null ? json.createObjectNode() : d.path(String.valueOf(appId)).path("data");
    }

    /** Описание из Steam: без HTML-тегов и сущностей, не длиннее допустимого; пустое — null. */
    static String cleanDescription(String html) {
        String t = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim();
        if (t.length() > Repo.MAX_DESCRIPTION) t = t.substring(0, Repo.MAX_DESCRIPTION).trim();
        return t.isEmpty() ? null : t;
    }

    // ---------- Discogs ----------

    /**
     * Альбом «Группа - Альбом» (без разделителя — не ищем): обложка (если нужна) и Notes (если нужно описание); null — не найден.
     * Сначала главная версия альбома (master), если её нет — отдельные издания (release).
     */
    private Found findDiscogs(Job job, String name, boolean wantImage, boolean wantDescription) throws Exception {
        String[] aa = artistAlbum(name);
        if (aa == null) return null;
        String artist = aa[0], album = aa[1];
        for (String type : List.of("master", "release")) {
            JsonNode res = api(job, discogsUrl + "/database/search?type=" + type + "&per_page=10&artist=" + enc(artist)
                    + "&release_title=" + enc(album));
            if (res == null) continue;
            // Подходящие результаты (название «Группа - Альбом» совпадает) — от самого популярного:
            // у одноимённых фильма, сборника или бутлега владельцев на Discogs гораздо меньше, чем у самого альбома
            List<JsonNode> matches = new java.util.ArrayList<>();
            for (JsonNode r : res.path("results")) {
                String[] t = artistAlbum(r.path("title").asText(""));
                if (t != null && norm(t[1]).equals(norm(album)) && sameArtist(t[0], artist)) matches.add(r);
            }
            matches.sort(java.util.Comparator.comparingLong((JsonNode r) -> r.path("community").path("have").asLong()).reversed());
            for (JsonNode r : matches) {
                byte[] img = null;
                if (wantImage) {
                    String cover = r.path("cover_image").asText("");
                    // нет обложки — у Discogs пустая заглушка spacer.gif
                    if (!cover.isEmpty() && !cover.contains("spacer.gif")) img = download(cover);
                }
                String notes = wantDescription ? discogsNotes(job, type, r.path("id").asLong()) : null;
                if (img == null && notes == null) continue; // ни обложки, ни примечаний — следующий результат
                return new Found(img, notes);
            }
        }
        return null;
    }

    /** Notes альбома: у главной версии (master), если там пусто — у её основного издания; у издания (release) — его Notes. */
    private String discogsNotes(Job job, String type, long id) throws Exception {
        if (id <= 0) return null;
        if (type.equals("master")) {
            JsonNode m = api(job, discogsUrl + "/masters/" + id);
            if (m == null) return null;
            String notes = cleanNotes(m.path("notes").asText(""));
            if (notes != null) return notes;
            long main = m.path("main_release").asLong();
            if (main <= 0) return null;
            id = main;
        }
        JsonNode rel = api(job, discogsUrl + "/releases/" + id);
        return rel == null ? null : cleanNotes(rel.path("notes").asText(""));
    }

    /**
     * Notes Discogs без их разметки: [url=…]текст[/url] → текст, [a=Имя]/[l=Имя] → Имя,
     * ссылки на записи по номеру ([a123], [m=123]) и теги [b] [i] [u] убираются, пробелы перед знаками препинания — тоже; пустое — null.
     */
    static String cleanNotes(String s) {
        String t = s.replaceAll("(?is)\\[url=[^\\]]*\\](.*?)\\[/url\\]", "$1")
                .replaceAll("\\[[alrm]=?\\d+\\]", "")          // ссылки по номеру: [a45467], [m=11329]
                .replaceAll("\\[[alrm]=([^\\]]+)\\]", "$1")    // по имени: [a=Имя], [l=Студия]
                .replaceAll("(?i)\\[/?[biu]\\]", "")
                .replaceAll("[ \\t]+([,.;:!?)])", "$1").replaceAll("[ \\t]{2,}", " ")
                .replace("\r\n", "\n").replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        if (t.length() > Repo.MAX_DESCRIPTION) t = t.substring(0, Repo.MAX_DESCRIPTION).trim();
        return t.isEmpty() ? null : t;
    }

    // ---------- FantLab ----------

    /** Книга: «Автор - Книга» (как у дисков; автор проверяется), без « - » — просто «Название». null — не найдена. */
    private Found findFantlab(Job job, String name, boolean wantImage, boolean wantDescription) throws Exception {
        String[] at = artistAlbum(name);
        return at != null ? findFantlabWork(job, at[0], at[1], wantImage, wantDescription)
                : findFantlabWork(job, null, name.trim(), wantImage, wantDescription);
    }

    private Found findFantlabWork(Job job, String author, String title, boolean wantImage, boolean wantDescription) throws Exception {
        if (norm(title).isEmpty()) return null;
        JsonNode res = api(job, fantlabUrl + "/search-works?page=1&q=" + enc(title));
        if (res == null) return null;
        // Произведения с тем же названием (русским или оригинальным) и, если указан, тем же автором — от самого популярного
        // (по числу оценок): иначе вместо романа можно получить одноимённый сценарий или цикл
        List<JsonNode> matches = new java.util.ArrayList<>();
        for (JsonNode w : res.path("matches")) {
            // Название: русское, оригинальное или одно из альтернативных переводов («Основание» = «Академия» Азимова)
            boolean sameTitle = norm(w.path("rusname").asText("")).equals(norm(title)) || norm(w.path("name").asText("")).equals(norm(title));
            for (String alt : w.path("altname").asText("").split(";"))
                sameTitle |= !alt.isBlank() && norm(alt).equals(norm(title));
            if (sameTitle && (author == null || sameAuthor(author, w.path("all_autor_rusname").asText("") + " " + w.path("all_autor_name").asText(""))))
                matches.add(w);
        }
        matches.sort(java.util.Comparator.comparingLong((JsonNode w) -> w.path("markcount").asLong()).reversed());
        for (JsonNode w : matches) {
            JsonNode work = api(job, fantlabUrl + "/work/" + w.path("work_id").asLong());
            if (work == null) continue;
            long workId = w.path("work_id").asLong();
            String image = work.path("image").asText("");
            byte[] img = null;
            if (wantImage) {
                if (image.isEmpty()) image = editionCover(job, workId); // своей обложки нет — обложка одного из изданий
                if (image != null && !image.isEmpty()) img = download(image.startsWith("/") ? fantlabSite + image : image);
            }
            String descr = wantDescription ? cleanFantlab(work.path("work_description").asText("")) : null;
            if (img == null && descr == null) continue;
            return new Found(img, descr);
        }
        return null;
    }

    /** Обложка из списка изданий (расширенная карточка): первое издание с картинкой, русское — в первую очередь; или null. */
    private String editionCover(Job job, long workId) throws Exception {
        JsonNode ext = api(job, fantlabUrl + "/work/" + workId + "/extended");
        if (ext == null) return null;
        long any = 0;
        for (JsonNode block : ext.path("editions_blocks"))
            for (JsonNode e : block.path("list")) {
                if (e.path("pic_num").asInt() <= 0 || e.path("edition_id").asLong() <= 0) continue;
                if ("ru".equals(e.path("lang_code").asText())) return "/images/editions/big/" + e.path("edition_id").asLong();
                if (any == 0) any = e.path("edition_id").asLong();
            }
        return any == 0 ? null : "/images/editions/big/" + any;
    }

    /**
     * Тот же автор: каждое слово из написанного пользователем совпадает началом (до 5 букв) со словом в именах авторов FantLab —
     * «Стругацкие», «Стругацкий» и «Аркадий и Борис Стругацкие» подходят друг к другу.
     */
    static boolean sameAuthor(String given, String fantlabNames) {
        List<String> names = List.of(norm(fantlabNames).split(" "));
        for (String w : norm(given).split(" ")) {
            if (w.length() < 2) continue;
            String stem = w.substring(0, Math.min(5, w.length()));
            if (names.stream().noneMatch(n -> n.startsWith(stem))) return false;
        }
        return true;
    }

    /** Аннотация FantLab без их разметки ([b], [i], [url=…], [spoiler] и т. п. — остаётся текст); пустая — null. */
    static String cleanFantlab(String s) {
        String t = s.replaceAll("\\[/?[a-zA-Z]+(=[^\\]]*)?\\]", "")
                .replace("\r\n", "\n").replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        if (t.length() > Repo.MAX_DESCRIPTION) t = t.substring(0, Repo.MAX_DESCRIPTION).trim();
        return t.isEmpty() ? null : t;
    }

    /** «Группа - Альбом» → [группа, альбом] по первому « - » (или « – », « — »); иначе null. */
    static String[] artistAlbum(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s+[-–—]\\s+").matcher(s);
        if (!m.find()) return null;
        String a = s.substring(0, m.start()).trim(), b = s.substring(m.end()).trim();
        return a.isEmpty() || b.isEmpty() ? null : new String[] {a, b};
    }

    /** Та же группа: без учёта регистра, пунктуации, «The» в начале и номера Discogs вида «Name (2)» или «Name*». */
    static boolean sameArtist(String a, String b) {
        return artistKey(a).equals(artistKey(b));
    }

    private static String artistKey(String s) {
        return norm(s.replaceAll("\\s*\\(\\d+\\)$", "").replaceAll("\\*$", "")).replaceAll("^the ", "");
    }

    // ---------- общее ----------

    /** Запрос к API с паузой между запросами; при ограничении (429/403/5xx) — пауза и повтор. null — 404. */
    private JsonNode api(Job job, String url) throws Exception {
        boolean discogs = job.source.equals(DISCOGS);
        // Discogs без токена — 25 запросов в минуту
        boolean token = discogs && !job.discogsToken.isEmpty();
        long interval = discogs ? (token ? discogsIntervalMs : Math.max(discogsIntervalMs, 2500))
                : job.source.equals(FANTLAB) ? fantlabIntervalMs : steamIntervalMs;
        for (int attempt = 0; ; attempt++) {
            synchronized (lastCall) {
                long wait = lastCall.getOrDefault(job.source, 0L) + interval - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                lastCall.put(job.source, System.currentTimeMillis());
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "CollectionsApp/" + appVersion).GET();
            if (token) req.header("Authorization", "Discogs token=" + job.discogsToken);
            HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            if (code == 200) {
                job.message = "";
                return json.readTree(r.body());
            }
            if (code == 404) return null;
            if (discogs && code == 401)
                throw new FatalImport("Discogs отклонил токен — проверьте токен Discogs выше. Импорт остановлен");
            if ((code == 429 || code == 403 || code >= 500) && attempt < THROTTLE_RETRIES) {
                job.message = job.sourceName() + " ограничил запросы — пауза " + throttlePauseMs / 1000 + " с";
                Thread.sleep(throttlePauseMs);
                if (job.cancel) throw new InterruptedException();
                continue;
            }
            throw new java.io.IOException(job.sourceName() + ": " + code);
        }
    }

    /** Картинка по адресу сервиса или null (нет такой, не картинка или слишком большая). */
    private byte[] download(String url) throws Exception {
        // Только адреса Steam и Discogs (и заглушек, если адреса сервисов подменены)
        URI u = URI.create(url);
        String host = u.getHost() == null ? "" : u.getHost();
        boolean known = "https".equals(u.getScheme()) && (host.endsWith(".steamstatic.com")
                || host.endsWith(".steampowered.com") || host.endsWith(".discogs.com")
                || host.equals("fantlab.ru") || host.endsWith(".fantlab.ru"));
        if (!known && !url.startsWith(steamUrl + "/") && !url.startsWith(discogsUrl + "/") && !url.startsWith(fantlabSite + "/")) return null;
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CollectionsApp/" + appVersion).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] b = r.body();
        return r.statusCode() == 200 && b.length <= MAX_IMAGE_BYTES && Repo.imageType(b) != null ? b : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Название для сравнения: без регистра, знаков ™®©, пунктуации и лишних пробелов; «&» = «and»;
     * римские числа — арабскими («Gothic II» = «Gothic 2», «Final Fantasy IX» = «Final Fantasy 9»).
     */
    static String norm(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[™®©]", "").replace("&", " and ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        StringBuilder b = new StringBuilder();
        for (String w : t.split(" ")) {
            if (b.length() > 0) b.append(' ');
            b.append(w.matches("x{0,3}(ix|iv|v?i{0,3})") && !w.isEmpty() ? String.valueOf(roman(w)) : w);
        }
        return b.toString();
    }

    /** Римское число до 39 (i…xxxix). */
    private static int roman(String w) {
        int n = 0;
        for (int i = 0; i < w.length(); i++) {
            int v = w.charAt(i) == 'x' ? 10 : w.charAt(i) == 'v' ? 5 : 1;
            int next = i + 1 < w.length() ? (w.charAt(i + 1) == 'x' ? 10 : w.charAt(i + 1) == 'v' ? 5 : 1) : 0;
            n += v < next ? -v : v;
        }
        return n;
    }
}
