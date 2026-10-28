package app;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Nintendo eShop (европейский каталог) и база игр LaunchBox (консоли Sony, Nintendo и другие): игра с тем же
 * названием на той же платформе (поле «Платформа» объекта, если указано), картинки — обложка коробки и скриншоты.
 * Импорт идёт в фоне, по одному на всё приложение (лимиты сервисов — на адрес, а не на пользователя):
 * запросы к API не чаще заданного интервала, при отказе сервиса (429/403/5xx) — пауза и повтор.
 */
@Component
public class ImageImport {

    public static final String STEAM = "steam", GOG = "gog", DISCOGS = "discogs", FANTLAB = "fantlab",
            NINTENDO = "nintendo", LAUNCHBOX = "launchbox";
    /** Источники: ключ → название для страницы. */
    public static final Map<String, String> SOURCES = new LinkedHashMap<>();
    static {
        SOURCES.put(STEAM, "Steam");
        SOURCES.put(GOG, "GOG");
        SOURCES.put(NINTENDO, "Nintendo eShop");
        SOURCES.put(LAUNCHBOX, "LaunchBox");
        SOURCES.put(DISCOGS, "Discogs");
        SOURCES.put(FANTLAB, "FantLab");
    }

    /** Источники игр: у них есть выбор «скриншот / обложка» первой картинкой. */
    static boolean gameSource(String source) {
        return source.equals(STEAM) || source.equals(GOG) || source.equals(NINTENDO) || source.equals(LAUNCHBOX);
    }

    /** Паузы между запросами к API, мс: Steam — около 200 за 5 минут, Discogs — до 60 в минуту с токеном. */
    @Value("${steam.api-interval-ms:1500}")
    private long steamIntervalMs;
    /** GOG ограничений не публикует; раз в секунду — чтобы не нагружать. */
    @Value("${gog.api-interval-ms:1000}")
    private long gogIntervalMs;
    @Value("${discogs.api-interval-ms:1100}")
    private long discogsIntervalMs;
    /** FantLab просит не чаще раза в 1,2 с (Crawl-delay в robots.txt). */
    @Value("${fantlab.api-interval-ms:1200}")
    private long fantlabIntervalMs;
    /** Nintendo и LaunchBox ограничений не публикуют; раз в секунду — чтобы не нагружать. */
    @Value("${nintendo.api-interval-ms:1000}")
    private long nintendoIntervalMs;
    @Value("${launchbox.api-interval-ms:1000}")
    private long launchboxIntervalMs;
    /** Пауза, если сервис ограничил запросы, мс, и сколько раз подряд её делать. */
    @Value("${import.throttle-pause-ms:60000}")
    private long throttlePauseMs;
    static final int THROTTLE_RETRIES = 5;
    /** Столько ошибок сети подряд — сервис недоступен, импорт останавливается. */
    static final int MAX_ERRORS_IN_ROW = 10;

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
    /** GOG (без ключа): каталог (поиск) и API (карточка игры с описанием). */
    @Value("${gog.catalog-url:https://catalog.gog.com}")
    private String gogCatalogUrl;
    @Value("${gog.api-url:https://api.gog.com}")
    private String gogApiUrl;
    /** Nintendo (без ключа): поиск европейского каталога и сайт (страницы игр со скриншотами). */
    @Value("${nintendo.search-url:https://searching.nintendo-europe.com}")
    private String nintendoSearchUrl;
    @Value("${nintendo.site-url:https://www.nintendo.com}")
    private String nintendoSite;
    /** База игр LaunchBox (без ключа): поиск и страницы игр. */
    @Value("${launchbox.site-url:https://gamesdb.launchbox-app.com}")
    private String launchboxSite;

    /** Состояние импорта пользователя (читается страницей через /import/status). */
    public static class Job {
        public final long collectionId;
        public final String collectionName;
        public final String source;
        public final boolean images, descriptions;
        /** Игры: первая картинка — скриншот, а не шапка или обложка. */
        public final boolean screenshot;
        /** Сколько картинок загружать на объект (1…ImageFiles.MAX_IMAGES), если у источника они есть. */
        public final int maxImages;
        public volatile String state = "queued"; // queued | running | done | cancelled | failed
        public volatile int total, processed, found, loaded, described, notFound, errors; // found — найдено в источнике; loaded — картинок, described — описаний
        public volatile String current = "";
        public volatile String message = "";      // пауза из-за ограничений сервиса, причина остановки
        volatile boolean cancel;
        /** Личный токен Discogs пользователя (пусто — без токена); на страницу не попадает. */
        private final String discogsToken;
        /** Язык сообщений (message) — язык интерфейса при запуске импорта. */
        final java.util.Locale locale = org.springframework.context.i18n.LocaleContextHolder.getLocale();

        Job(long collectionId, String collectionName, String source, boolean images, boolean descriptions,
            boolean screenshot, int maxImages, String discogsToken) {
            this.screenshot = screenshot;
            this.maxImages = Math.max(1, Math.min(ImageFiles.MAX_IMAGES, maxImages));
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
    private final Http http;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "image-import");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    /** Время последнего запроса к API каждого источника. */
    private final Map<String, Long> lastCall = new ConcurrentHashMap<>();

    public ImageImport(Repo repo, Http http) {
        this.repo = repo;
        this.http = http;
    }

    /** Текущий или последний импорт пользователя (или null). */
    public Job job(long uid) {
        return jobs.get(uid);
    }

    /** Идёт ли импорт у кого-нибудь (восстановление из копии в это время не запускается). */
    public boolean anyActive() {
        return jobs.values().stream().anyMatch(Job::active);
    }

    /**
     * Запускает импорт; false — у пользователя уже идёт импорт. Права на коллекцию и источник проверяет вызывающий код.
     * onlyMissing — не трогать уже заполненное: картинку загружать только тем, у кого её нет, описание — так же.
     * discogsToken — личный токен Discogs пользователя (null — нет): без него Discogs отдаёт результаты и Notes,
     * но без обложек (и только 25 запросов в минуту).
     */
    public synchronized boolean start(long uid, Entry collection, boolean onlyMissing, String source,
                                      boolean images, boolean descriptions, boolean screenshot, int maxImages,
                                      String discogsToken) {
        Job old = jobs.get(uid);
        if (old != null && old.active()) return false;
        boolean hasToken = discogsToken != null && !discogsToken.isBlank();
        // Без токена обложек у Discogs нет — только описания
        boolean img = images && (!source.equals(DISCOGS) || hasToken);
        Job job = new Job(collection.id(), collection.name(), source, img, descriptions,
                screenshot && gameSource(source), maxImages, discogsToken);
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
                        case GOG -> findGog(job, item.name(), img, descr);
                        case NINTENDO -> findNintendo(job, item.name(), item.platform(), img, descr);
                        case LAUNCHBOX -> findLaunchbox(job, item.name(), item.platform(), img, descr);
                        default -> findSteam(job, item.name(), img, descr);
                    };
                    if (f == null) {
                        job.notFound++;
                    } else {
                        job.found++;
                        if (img && !f.images().isEmpty()) {
                            repo.setItemImages(item.id(), f.images());
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
                        job.message = I18n.t(job.locale, "import.unavailable", job.sourceName());
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
    /** Найденное: картинки (по порядку, без повторов) и описание. */
    private record Found(List<byte[]> images, String description) {}

    /** Скачивает картинки по адресам по порядку, пока не наберётся max; неудачные и повторы (по содержимому) пропускаются. */
    private List<byte[]> downloadAll(List<String> urls, int max) throws Exception {
        List<byte[]> res = new java.util.ArrayList<>();
        for (String u : urls) {
            if (res.size() >= max) break;
            if (u == null || u.isEmpty()) continue;
            byte[] b = download(u);
            if (b != null && res.stream().noneMatch(x -> java.util.Arrays.equals(x, b))) res.add(b);
        }
        return res;
    }

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
        // Русские названия Steam находит только в поиске с регионом Россия и русским языком
        // («Бельтион: Свод Равновесия» = Beltion: Beyond Ritual): для названий на кириллице — ещё и так
        List<String> regions = name.matches("(?s).*\\p{IsCyrillic}.*")
                ? List.of(STEAM_SEARCH_EN, STEAM_SEARCH_RU) : List.of(STEAM_SEARCH_EN);
        for (String region : regions)
            for (String term : plain.equals(name) ? List.of(name) : List.of(name, plain)) {
                Found f = findSteamTerm(job, region, term, key, false, wantImage, wantDescription);
                if (f != null) return f;
            }
        // Последняя попытка — по части до двоеточия: консольные издания с подзаголовком продаются в Steam
        // как ПК-издания («Disgaea 2: Dark Hero Days» → «Disgaea 2 PC», «Disgaea 4: A Promise Revisited» →
        // «Disgaea 4 Complete+»). Засчитывается только результат с припиской издания или платформы:
        // иначе консольная «Fallout: Brotherhood of Steel» получила бы картинки первой Fallout
        int colon = name.indexOf(':');
        String prefix = colon > 0 ? name.substring(0, colon).trim() : "";
        if (!norm(prefix).isEmpty()) return findSteamTerm(job, STEAM_SEARCH_EN, prefix, norm(prefix), true, wantImage, wantDescription);
        return null;
    }

    /** Регион и язык поиска Steam: США по-английски (основной) и Россия по-русски (русские названия). */
    private static final String STEAM_SEARCH_EN = "cc=us&l=english", STEAM_SEARCH_RU = "cc=ru&l=russian";

    /** Поиск в Steam по term; editionOnly — засчитывать только издания key (с припиской издания или платформы). */
    private Found findSteamTerm(Job job, String region, String term, String key, boolean editionOnly, boolean wantImage,
                                boolean wantDescription) throws Exception {
        JsonNode res = api(job, steamUrl + "/api/storesearch/?" + region + "&term=" + enc(term));
        if (res == null) return null;
        // Точное совпадение названия; если его нет — то же название без «The» в начале и приписки издания
        // (DX, Remastered, GOTY…): «Banner Saga 3» = «The Banner Saga 3»; старое издание после выхода нового
        // Steam часто снимает с продажи, и поиск находит только новое
        JsonNode found = null, edition = null;
        for (JsonNode it : res.path("items")) {
            if (!"app".equals(it.path("type").asText("app"))) continue;
            String n = norm(it.path("name").asText());
            if (!editionOnly && key.equals(n)) { found = it; break; }
            if (edition == null && looseKey(key).equals(looseKey(n)) && (!editionOnly || !editionBase(n).equals(n))) edition = it;
        }
        if (found == null) found = edition;
        for (JsonNode it : found == null ? List.<JsonNode>of() : List.of(found)) {
            long appId = it.path("id").asLong();
            String tiny = it.path("tiny_image").asText("");
            // скриншоты нужны, если картинка — скриншот или картинок больше одной
            boolean wantShots = wantImage && (job.screenshot || job.maxImages > 1);
            JsonNode details = null;
            String description = null;
            // описание и скриншоты — в одной карточке игры, один запрос
            if (wantDescription || wantShots) details = appDetails(job, appId, wantShots);
            if (wantDescription) description = cleanDescription(details.path("short_description").asText(""));
            List<byte[]> imgs = List.of();
            if (wantImage) {
                List<String> shots = new java.util.ArrayList<>();
                if (wantShots) for (JsonNode sh : details.path("screenshots")) shots.add(sh.path("path_full").asText(""));
                // скриншоты галереи в полном размере; режим «шапка» — сначала шапка, за ней скриншоты
                imgs = job.screenshot ? downloadAll(shots, job.maxImages) : List.of();
                if (imgs.isEmpty()) {
                    byte[] header = steamHeader(job, appId, tiny, details);
                    List<byte[]> all = new java.util.ArrayList<>();
                    if (header != null) all.add(header);
                    if (!job.screenshot) all.addAll(downloadAll(shots, job.maxImages - all.size()));
                    imgs = all;
                }
            }
            return new Found(imgs, description);
        }
        return null;
    }

    /** Приписки изданий и платформы в конце названия (после norm; «Complete+» → «complete»): та же игра с ними и без них. */
    private static final java.util.regex.Pattern EDITION = java.util.regex.Pattern.compile(
            " (dx|hd|pc|complete|redux|remaster(ed)?|hd remaster(ed)?|goty|((definitive|complete|enhanced|deluxe|gold|platinum|ultimate|special"
            + "|anniversary|standard|digital deluxe|game of the year|goty) edition)|director s cut|directors cut)$");

    /** Название без приписок изданий в конце: «atelier ryza … dx» → «atelier ryza …» (можно несколько подряд). */
    static String editionBase(String normName) {
        String s = normName, prev;
        do {
            prev = s;
            s = EDITION.matcher(s).replaceFirst("");
        } while (!s.equals(prev) && !s.isEmpty());
        return s.isEmpty() ? normName : s;
    }

    /** Название для запасного сравнения: без «The» в начале и приписок изданий в конце. */
    static String looseKey(String normName) {
        String s = editionBase(normName);
        return s.startsWith("the ") && s.length() > 4 ? s.substring(4) : s;
    }

    /** Шапка игры: рядом с маленькой картинкой из поиска; если её там нет — адрес из карточки игры; иначе маленькая картинка. */
    private byte[] steamHeader(Job job, long appId, String tiny, JsonNode details) throws Exception {
        byte[] img = tiny.contains("/") ? download(tiny.substring(0, tiny.lastIndexOf('/') + 1) + "header.jpg") : null;
        if (img == null) {
            if (details == null) details = appDetails(job, appId, false);
            String header = details.path("header_image").asText("");
            if (!header.isEmpty()) img = download(header);
        }
        if (img == null && !tiny.isEmpty()) img = download(tiny);
        return img;
    }

    /**
     * Карточка игры Steam (data из appdetails) на русском, со скриншотами — если нужны; пустой узел, если её нет.
     * Регион — США, как у поиска: без него Steam берёт регион по адресу, и для игр, которые в России не продаются
     * (например, Sword Art Online: Fatal Bullet), отдаёт пустую карточку — без скриншотов и описания.
     */
    private JsonNode appDetails(Job job, long appId, boolean screenshots) throws Exception {
        JsonNode d = api(job, steamUrl + "/api/appdetails?filters=basic" + (screenshots ? ",screenshots" : "")
                + "&cc=us&l=russian&appids=" + appId);
        return d == null ? json.createObjectNode() : d.path(String.valueOf(appId)).path("data");
    }

    /** Описание из Steam: без HTML-тегов и сущностей, не длиннее допустимого; пустое — null. */
    static String cleanDescription(String html) {
        String t = htmlText(html.replaceAll("(?i)<br\\s*/?>", "\n")).trim();
        if (t.length() > Repo.MAX_DESCRIPTION) t = t.substring(0, Repo.MAX_DESCRIPTION).trim();
        return t.isEmpty() ? null : t;
    }

    /** Текст из HTML: без тегов, сущности (&amp;quot; &amp;#39; &amp;#x27; …) — знаками. */
    static String htmlText(String html) {
        String t = html.replaceAll("<[^>]+>", "")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">");
        Matcher m = Pattern.compile("&#(x[0-9a-fA-F]{1,6}|\\d{1,7});").matcher(t);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String n = m.group(1);
            int cp = n.startsWith("x") ? Integer.parseInt(n.substring(1), 16) : Integer.parseInt(n);
            m.appendReplacement(b, Matcher.quoteReplacement(Character.isValidCodePoint(cp) ? Character.toString(cp) : ""));
        }
        m.appendTail(b);
        return b.toString().replace("&amp;", "&");
    }

    // ---------- GOG ----------

    /**
     * Игра GOG с таким же названием (старые игры для ПК часто есть только там): картинки и описание; null — не найдена.
     * Поиск — в английском каталоге, затем в русском (у переведённых игр там русские названия);
     * как у Steam — ещё раз без знаков препинания.
     */
    private Found findGog(Job job, String name, boolean wantImage, boolean wantDescription) throws Exception {
        String key = norm(name);
        if (key.isEmpty()) return null;
        String plain = name.replace(".", "").replaceAll("[^\\p{L}\\p{N}']+", " ").trim();
        for (String locale : List.of("en-US", "ru-RU"))
            for (String term : plain.equals(name) ? List.of(name) : List.of(name, plain)) {
                JsonNode res = api(job, gogCatalogUrl + "/v1/catalog?limit=20&productType=in:game,pack&order=desc:score"
                        + "&countryCode=US&locale=" + locale + "&query=" + enc("like:" + term));
                JsonNode p = res == null ? null : bestMatch(res.path("products"), "title", key);
                if (p != null) return gogFound(job, p, wantImage, wantDescription);
            }
        return null;
    }

    /**
     * Подходящий результат поиска: точное совпадение названия (поле field), иначе без «The» и приписки издания;
     * из нескольких — первый (результаты уже упорядочены по релевантности).
     */
    private static JsonNode bestMatch(JsonNode results, String field, String key) {
        JsonNode loose = null;
        for (JsonNode it : results) {
            String n = norm(it.path(field).asText());
            if (key.equals(n)) return it;
            if (loose == null && looseKey(key).equals(looseKey(n))) loose = it;
        }
        return loose;
    }

    /**
     * Картинки и описание найденной игры GOG. Картинки — из результата поиска: скриншоты в полном размере
     * (в адресе убирается «_{formatter}»), обложка коробки (coverVertical). Режим «скриншот» — скриншоты
     * (если их нет — обложка), иначе — обложка, за ней скриншоты. Описание — из карточки игры по-русски,
     * если перевод есть (иначе GOG отдаёт английское); только если оно нужно.
     */
    private Found gogFound(Job job, JsonNode p, boolean wantImage, boolean wantDescription) throws Exception {
        String description = null;
        if (wantDescription) {
            JsonNode d = api(job, gogApiUrl + "/products/" + p.path("id").asLong() + "?expand=description&locale=ru-RU");
            if (d != null) {
                String full = d.path("description").path("full").asText("");
                description = cleanDescription(full.isBlank() ? d.path("description").path("lead").asText("") : full);
                if (description != null) description = description.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
            }
        }
        List<byte[]> imgs = List.of();
        if (wantImage) {
            List<String> shots = new java.util.ArrayList<>();
            for (JsonNode sh : p.path("screenshots")) shots.add(sh.asText("").replace("_{formatter}", ""));
            imgs = downloadAll(coverAndShots(job, p.path("coverVertical").asText(""), shots), job.maxImages);
        }
        return new Found(imgs, description);
    }

    /**
     * Порядок картинок игры: режим «скриншот» — скриншоты (если их нет — обложка),
     * иначе — обложка, за ней скриншоты.
     */
    private static List<String> coverAndShots(Job job, String cover, List<String> shots) {
        List<String> urls = new java.util.ArrayList<>();
        if (job.screenshot && !shots.isEmpty()) {
            urls.addAll(shots);
        } else {
            if (cover != null && !cover.isEmpty()) urls.add(cover);
            if (!job.screenshot) urls.addAll(shots);
        }
        return urls;
    }

    // ---------- Nintendo eShop ----------

    /**
     * Игра из европейского каталога Nintendo (Switch, Wii U, 3DS и переиздания старых игр): то же название
     * и та же платформа (если у объекта она указана и известна); null — не найдена. Русского каталога нет —
     * ищется по-английски. Картинки — обложка коробки (packshot) и скриншоты со страницы игры,
     * описание — краткое описание из каталога.
     */
    private Found findNintendo(Job job, String name, String platform, boolean wantImage, boolean wantDescription) throws Exception {
        String key = norm(name);
        if (key.isEmpty()) return null;
        // без знаков препинания: двоеточие и т. п. — часть языка запросов поиска
        String plain = name.replace(".", "").replaceAll("[^\\p{L}\\p{N}']+", " ").trim();
        JsonNode res = api(job, nintendoSearchUrl + "/en/select?fq=type:GAME&rows=30&wt=json&q=" + enc(plain));
        if (res == null) return null;
        Set<String> wanted = platformCodes(platform);
        JsonNode exact = null, loose = null;
        for (JsonNode d : res.path("response").path("docs")) {
            List<String> systems = new java.util.ArrayList<>();
            for (JsonNode s : d.path("system_names_txt")) systems.add(s.asText(""));
            if (!samePlatform(wanted, systems)) continue;
            String n = norm(d.path("title").asText(""));
            if (exact == null && key.equals(n)) exact = d;
            if (loose == null && looseKey(key).equals(looseKey(n))) loose = d;
        }
        JsonNode d = exact != null ? exact : loose;
        if (d == null) return null;
        List<byte[]> imgs = List.of();
        if (wantImage) {
            String cover = d.path("image_url").asText("");
            if (cover.isEmpty()) cover = d.path("image_url_sq_s").asText("");
            // страница игры (со скриншотами) — только если скриншоты нужны
            List<String> shots = job.screenshot || job.maxImages > 1 ? nintendoShots(job, d.path("url").asText("")) : List.of();
            imgs = downloadAll(coverAndShots(job, cover, shots), job.maxImages);
        }
        String descr = null;
        if (wantDescription) {
            descr = cleanDescription(d.path("product_catalog_description_s").asText(""));
            if (descr == null) descr = cleanDescription(d.path("excerpt").asText(""));
        }
        return new Found(imgs, descr);
    }

    /** Скриншот на странице игры Nintendo (полный размер и уменьшенная копия «_TM_Standard»). */
    private static final Pattern NINTENDO_SHOT = Pattern.compile(
            "(?:https://www\\.nintendo\\.com)?(/eu/media/images/06_screenshots/[^\"'\\s<>]+?\\.(?:jpe?g|png|gif|bmp))");

    /**
     * Адреса скриншотов со страницы игры (path — адрес страницы из каталога, от корня сайта) по порядку.
     * У старых игр полные скриншоты — в BMP (такие не принимаются), вместо них — копия «_TM_Standard» в PNG/JPEG.
     */
    private List<String> nintendoShots(Job job, String path) throws Exception {
        if (!path.startsWith("/")) return List.of();
        String html = page(job, nintendoSite + path);
        if (html == null) return List.of();
        Set<String> all = new java.util.LinkedHashSet<>();
        Matcher m = NINTENDO_SHOT.matcher(html);
        while (m.find()) all.add(m.group(1));
        List<String> res = new java.util.ArrayList<>();
        for (String p : all) {
            if (p.contains("_TM_")) continue;
            String shot = p;
            if (p.toLowerCase(Locale.ROOT).endsWith(".bmp")) {
                String small = p.substring(0, p.lastIndexOf('.')) + "_TM_Standard.";
                shot = all.stream().filter(s -> s.startsWith(small) && !s.toLowerCase(Locale.ROOT).endsWith(".bmp")).findFirst().orElse(null);
            }
            if (shot != null) res.add(nintendoSite + shot);
        }
        // по номеру в имени файла (…_01.jpg, …_02_enGB.png): на некоторых страницах они идут в обратном порядке
        res.sort(java.util.Comparator.comparingInt(ImageImport::shotNumber));
        return res;
    }

    /** Номер скриншота Nintendo из имени файла («NSwitch_Hades_03_enGB.png» → 3); нет номера — в конец. */
    static int shotNumber(String url) {
        Matcher m = Pattern.compile("_(\\d{1,3})(?:_[A-Za-z]{2,5})?(?:_TM_Standard)?\\.\\w+$").matcher(url);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    // ---------- LaunchBox ----------

    /** Игра в результатах поиска LaunchBox: адрес страницы, номер, название, платформа. */
    private static final Pattern LAUNCHBOX_CARD = Pattern.compile(
            "href=\"(/games/details/(\\d+)-[^\"]*)\">.*?<h3[^>]*>(.*?)</h3>\\s*<p[^>]*>(.*?)</p>", Pattern.DOTALL);
    /** Картинка на странице игры: адрес и подпись «Игра - Тип (Регион) - ШxВ». */
    private static final Pattern LAUNCHBOX_IMAGE = Pattern.compile(
            "<img src=\"(https://images\\.launchbox-app\\.com/+[^\"]+)\"[^>]*alt=\"([^\"]*)\"");
    /** Регионы обложек по предпочтению: сначала европейские (как у дисков в России), японские — в последнюю очередь. */
    private static final List<String> REGIONS = List.of("Russia", "Europe", "United Kingdom", "World", "North America", "United States");

    /**
     * Игра из базы LaunchBox (консоли всех поколений: PlayStation, Nintendo, Sega…): то же название и та же
     * платформа (если у объекта она указана и известна); null — не найдена. Из одинаковых по названию —
     * самая ранняя запись базы (обычно исходная игра, а не порт или сборка). Сайт разбирается как страницы:
     * поиск (/games/results/…) и страница игры — описание (Overview) и картинки с подписями типа.
     */
    private Found findLaunchbox(Job job, String name, String platform, boolean wantImage, boolean wantDescription) throws Exception {
        String key = norm(name);
        if (key.isEmpty()) return null;
        // Сайт перенаправляет поиск на адрес в нижнем регистре (для кириллицы — по кругу): сразу так
        String term = enc(name.toLowerCase(Locale.ROOT)).replace("+", "%20");
        String html = page(job, launchboxSite + "/games/results/" + term + "?searchText=" + term);
        if (html == null) return null;
        Set<String> wanted = platformCodes(platform);
        String exact = null, loose = null;
        long exactId = Long.MAX_VALUE, looseId = Long.MAX_VALUE;
        Matcher m = LAUNCHBOX_CARD.matcher(html);
        while (m.find()) {
            if (!samePlatform(wanted, List.of(htmlText(m.group(4))))) continue;
            long id = Long.parseLong(m.group(2));
            String n = norm(htmlText(m.group(3)));
            if (key.equals(n)) {
                if (id < exactId) { exactId = id; exact = m.group(1); }
            } else if (looseKey(key).equals(looseKey(n)) && id < looseId) {
                looseId = id;
                loose = m.group(1);
            }
        }
        String path = exact != null ? exact : loose;
        if (path == null) return null;
        String game = page(job, launchboxSite + path);
        if (game == null) return null;
        List<byte[]> imgs = wantImage ? downloadAll(launchboxImages(job, game), job.maxImages) : List.of();
        return new Found(imgs, wantDescription ? launchboxOverview(game) : null);
    }

    /** Описание игры LaunchBox — абзацы раздела Overview; пустое — null. */
    static String launchboxOverview(String html) {
        int start = html.indexOf("id=\"overview\"");
        if (start < 0) return null;
        int end = html.indexOf("<dl", start);
        String section = html.substring(start, end > start ? end : Math.min(html.length(), start + 20000));
        StringBuilder b = new StringBuilder();
        // <p> и <p …>, но не <path> (значок кнопки Edit рядом с заголовком)
        Matcher m = Pattern.compile("<p(?:\\s[^>]*)?>(.*?)</p>", Pattern.DOTALL).matcher(section);
        while (m.find()) {
            String p = htmlText(m.group(1).replaceAll("(?i)<br\\s*/?>", "\n")).trim();
            if (!p.isEmpty()) b.append(b.length() > 0 ? "\n\n" : "").append(p);
        }
        String t = b.toString();
        if (t.length() > Repo.MAX_DESCRIPTION) t = t.substring(0, Repo.MAX_DESCRIPTION).trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Картинки игры LaunchBox по порядку: лицевая сторона коробки (одна — лучший регион; нет — восстановленная)
     * и скриншоты (сначала игровые, крупные первыми, затем титульный экран) — в порядке, заданном режимом.
     */
    private static List<String> launchboxImages(Job job, String html) {
        String cover = null, rebuilt = null;
        int coverRank = Integer.MAX_VALUE, rebuiltRank = Integer.MAX_VALUE;
        List<String[]> gameplay = new java.util.ArrayList<>(); // [адрес, площадь]
        List<String> titles = new java.util.ArrayList<>();
        Matcher m = LAUNCHBOX_IMAGE.matcher(html);
        while (m.find()) {
            String url = m.group(1).replaceFirst("launchbox-app\\.com/+", "launchbox-app.com/");
            String alt = htmlText(m.group(2));
            int rank = regionRank(alt);
            if (alt.contains(" - Box - Front (") && !alt.contains("Fanart - Box - Front (")) {
                if (rank < coverRank) { coverRank = rank; cover = url; }
            } else if (alt.contains(" - Box - Front - Reconstructed (")) {
                if (rank < rebuiltRank) { rebuiltRank = rank; rebuilt = url; }
            } else if (alt.contains(" - Screenshot - Gameplay (")) {
                gameplay.add(new String[] {url, String.valueOf(area(alt))});
            } else if (alt.contains(" - Screenshot - Game Title (")) {
                titles.add(url);
            }
        }
        gameplay.sort(java.util.Comparator.comparingLong((String[] g) -> Long.parseLong(g[1])).reversed());
        List<String> shots = new java.util.ArrayList<>();
        for (String[] g : gameplay) shots.add(g[0]);
        shots.addAll(titles);
        return coverAndShots(job, cover != null ? cover : rebuilt, shots);
    }

    /** Место региона подписи («… (Europe) - 1621x1394») среди предпочтительных; прочие — после них, Япония — последней. */
    private static int regionRank(String alt) {
        Matcher m = Pattern.compile("\\(([^()]*)\\) - \\d+x\\d+$").matcher(alt);
        String region = m.find() ? m.group(1) : "";
        int i = REGIONS.indexOf(region);
        return i >= 0 ? i : region.equals("Japan") ? 100 : 50;
    }

    /** Площадь картинки по подписи «… - 1024x714»; нет размера — 0. */
    private static long area(String alt) {
        Matcher m = Pattern.compile("(\\d+)x(\\d+)$").matcher(alt);
        return m.find() ? Long.parseLong(m.group(1)) * Long.parseLong(m.group(2)) : 0;
    }

    // ---------- платформы ----------

    /** Названия платформ (после norm) → код: так «PS2», «PlayStation 2» и «Sony Playstation 2» — одна платформа. */
    private static final Map<String, String> PLATFORMS = new java.util.HashMap<>();
    static {
        platform("ps1", "playstation", "playstation 1", "ps1", "psx", "ps one", "psone", "ps");
        platform("ps2", "playstation 2", "ps2");
        platform("ps3", "playstation 3", "ps3");
        platform("ps4", "playstation 4", "ps4");
        platform("ps5", "playstation 5", "ps5");
        platform("psp", "psp", "playstation portable");
        platform("vita", "playstation vita", "ps vita", "psvita", "psv", "vita");
        platform("nes", "nes", "nintendo entertainment system", "famicom", "dendy", "денди");
        platform("snes", "snes", "super nes", "super nintendo", "super nintendo entertainment system", "super famicom");
        platform("n64", "n64", "nintendo 64");
        platform("gc", "gamecube", "game cube", "gc", "ngc");
        platform("wii", "wii");
        platform("wiiu", "wii u", "wiiu");
        platform("switch", "switch", "ns");
        platform("switch2", "switch 2");
        platform("gb", "game boy", "gameboy", "gb");
        platform("gbc", "game boy color", "gameboy color", "gbc");
        platform("gba", "game boy advance", "gameboy advance", "gba");
        platform("ds", "ds", "nds");
        platform("3ds", "3ds", "n3ds", "new 3ds", "new nintendo 3ds");
        platform("pc", "pc", "windows", "ms dos", "dos");
        platform("xbox", "xbox");
        platform("x360", "xbox 360", "x360");
        platform("xone", "xbox one");
        platform("xsx", "xbox series x s", "xbox series x", "xbox series s");
        platform("md", "genesis", "mega drive", "megadrive", "md");
        platform("saturn", "saturn");
        platform("dc", "dreamcast");
    }

    private static void platform(String code, String... names) {
        for (String n : names) PLATFORMS.put(norm(n), code);
    }

    /** Код платформы по названию; без известной приписки производителя («Sony», «Nintendo»…) тоже. null — неизвестная. */
    static String platformCode(String name) {
        String n = norm(name);
        String code = PLATFORMS.get(n);
        return code != null ? code : PLATFORMS.get(n.replaceFirst("^(sony|nintendo|microsoft|sega) ", ""));
    }

    /** Известные платформы из поля «Платформа» (можно несколько через «,», «/», «;», «+»: «PS4 / PS5»). */
    static Set<String> platformCodes(String field) {
        Set<String> res = new java.util.HashSet<>();
        if (field != null)
            for (String p : field.split("[,/;+|]")) {
                String code = platformCode(p);
                if (code != null) res.add(code);
            }
        return res;
    }

    /** Подходит ли запись источника (её платформы) объекту: у объекта нет известной платформы — подходит любая. */
    static boolean samePlatform(Set<String> wanted, List<String> sourcePlatforms) {
        if (wanted.isEmpty()) return true;
        for (String p : sourcePlatforms)
            if (wanted.contains(platformCode(p))) return true;
        return false;
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
                Map<String, JsonNode> cache = new java.util.HashMap<>(); // карточки альбома: для Notes и картинок — один запрос
                long id = r.path("id").asLong();
                List<byte[]> imgs = List.of();
                if (wantImage) {
                    List<String> urls = new java.util.ArrayList<>();
                    // несколько картинок — из карточки альбома (обложка, оборот, вкладыш…), иначе — обложка из поиска
                    if (job.maxImages > 1) urls.addAll(discogsImages(job, type, id, cache));
                    String cover = r.path("cover_image").asText("");
                    // обложка из поиска — если из карточки ничего нет (иначе была бы та же картинка в другом размере);
                    // нет обложки — у Discogs пустая заглушка spacer.gif
                    if (urls.isEmpty() && !cover.isEmpty() && !cover.contains("spacer.gif")) urls.add(cover);
                    imgs = downloadAll(urls, job.maxImages);
                }
                String notes = wantDescription ? discogsNotes(job, type, id, cache) : null;
                if (imgs.isEmpty() && notes == null) continue; // ни обложки, ни примечаний — следующий результат
                return new Found(imgs, notes);
            }
        }
        return null;
    }

    /** Запрос к Discogs с запоминанием ответа в пределах одного альбома. */
    private JsonNode discogsCached(Job job, String url, Map<String, JsonNode> cache) throws Exception {
        if (cache.containsKey(url)) return cache.get(url);
        JsonNode n = api(job, url);
        cache.put(url, n);
        return n;
    }

    /** Адреса картинок альбома из его карточки (master или release), главная — первой; без токена Discogs их не отдаёт. */
    private List<String> discogsImages(Job job, String type, long id, Map<String, JsonNode> cache) throws Exception {
        if (id <= 0) return List.of();
        JsonNode d = discogsCached(job, discogsUrl + (type.equals("master") ? "/masters/" : "/releases/") + id, cache);
        List<String> res = new java.util.ArrayList<>();
        if (d != null)
            for (JsonNode im : d.path("images")) {
                String uri = im.path("uri").asText("");
                if (uri.isEmpty()) continue;
                if ("primary".equals(im.path("type").asText())) res.add(0, uri);
                else res.add(uri);
            }
        return res;
    }

    /** Notes альбома: у главной версии (master), если там пусто — у её основного издания; у издания (release) — его Notes. */
    private String discogsNotes(Job job, String type, long id, Map<String, JsonNode> cache) throws Exception {
        if (id <= 0) return null;
        if (type.equals("master")) {
            JsonNode m = discogsCached(job, discogsUrl + "/masters/" + id, cache);
            if (m == null) return null;
            String notes = cleanNotes(m.path("notes").asText(""));
            if (notes != null) return notes;
            long main = m.path("main_release").asLong();
            if (main <= 0) return null;
            id = main;
        }
        JsonNode rel = discogsCached(job, discogsUrl + "/releases/" + id, cache);
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
            List<byte[]> imgs = List.of();
            if (wantImage) {
                // своя обложка произведения, за ней (или вместо неё) — обложки изданий
                List<String> urls = new java.util.ArrayList<>();
                if (!image.isEmpty()) urls.add(image);
                if (image.isEmpty() || job.maxImages > 1) urls.addAll(editionCovers(job, workId));
                imgs = downloadAll(urls.stream().map(u -> u.startsWith("/") ? fantlabSite + u : u).toList(), job.maxImages);
            }
            String descr = wantDescription ? cleanFantlab(work.path("work_description").asText("")) : null;
            if (imgs.isEmpty() && descr == null) continue;
            return new Found(imgs, descr);
        }
        return null;
    }

    /** Обложки изданий (расширенная карточка): издания с картинкой, русские — в первую очередь. */
    private List<String> editionCovers(Job job, long workId) throws Exception {
        JsonNode ext = api(job, fantlabUrl + "/work/" + workId + "/extended");
        if (ext == null) return List.of();
        List<String> ru = new java.util.ArrayList<>(), other = new java.util.ArrayList<>();
        for (JsonNode block : ext.path("editions_blocks"))
            for (JsonNode e : block.path("list")) {
                if (e.path("pic_num").asInt() <= 0 || e.path("edition_id").asLong() <= 0) continue;
                String url = "/images/editions/big/" + e.path("edition_id").asLong();
                ("ru".equals(e.path("lang_code").asText()) ? ru : other).add(url);
            }
        ru.addAll(other);
        return ru;
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

    /** Запрос к API (ответ — JSON) с паузой между запросами; при ограничении (429/403/5xx) — пауза и повтор. null — 404. */
    private JsonNode api(Job job, String url) throws Exception {
        String body = page(job, url);
        return body == null ? null : json.readTree(body);
    }

    /** Страница или ответ сервиса текстом — с теми же паузами и повторами, что у api(); null — 404. */
    private String page(Job job, String url) throws Exception {
        boolean discogs = job.source.equals(DISCOGS);
        // Discogs без токена — 25 запросов в минуту
        boolean token = discogs && !job.discogsToken.isEmpty();
        long interval = switch (job.source) {
            case DISCOGS -> token ? discogsIntervalMs : Math.max(discogsIntervalMs, 2500);
            case FANTLAB -> fantlabIntervalMs;
            case GOG -> gogIntervalMs;
            case NINTENDO -> nintendoIntervalMs;
            case LAUNCHBOX -> launchboxIntervalMs;
            default -> steamIntervalMs;
        };
        for (int attempt = 0; ; attempt++) {
            synchronized (lastCall) {
                long wait = lastCall.getOrDefault(job.source, 0L) + interval - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                lastCall.put(job.source, System.currentTimeMillis());
            }
            HttpResponse<String> r = token ? http.text(url, "Authorization", "Discogs token=" + job.discogsToken) : http.text(url);
            int code = r.statusCode();
            if (code == 200) {
                job.message = "";
                return r.body();
            }
            if (code == 404) return null;
            if (discogs && code == 401)
                throw new FatalImport(I18n.t(job.locale, "import.badToken"));
            if ((code == 429 || code == 403 || code >= 500) && attempt < THROTTLE_RETRIES) {
                job.message = I18n.t(job.locale, "import.throttled", job.sourceName(), String.valueOf(throttlePauseMs / 1000));
                Thread.sleep(throttlePauseMs);
                if (job.cancel) throw new InterruptedException();
                continue;
            }
            throw new java.io.IOException(job.sourceName() + ": " + code);
        }
    }

    /** Картинка по адресу сервиса или null (нет такой, не картинка или слишком большая). */
    private byte[] download(String url) throws Exception {
        // Только адреса картинок источников импорта (и заглушек, если адреса сервисов подменены)
        URI u = URI.create(url);
        String host = u.getHost() == null ? "" : u.getHost();
        boolean known = "https".equals(u.getScheme()) && (host.endsWith(".steamstatic.com")
                || host.endsWith(".steampowered.com") || host.endsWith(".discogs.com")
                || host.equals("fantlab.ru") || host.endsWith(".fantlab.ru") || host.endsWith(".gog-statics.com")
                || host.equals("www.nintendo.com") || host.equals("images.launchbox-app.com"));
        if (!known && !url.startsWith(steamUrl + "/") && !url.startsWith(discogsUrl + "/") && !url.startsWith(fantlabSite + "/")
                && !url.startsWith(gogCatalogUrl + "/") && !url.startsWith(nintendoSite + "/") && !url.startsWith(launchboxSite + "/"))
            return null;
        return http.image(url);
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
