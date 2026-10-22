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
 * Импорт картинок объектов («Настройки» → «Импорт») из Steam (игры) или Discogs (музыка).
 * Steam: объект сопоставляется с игрой по названию, картинка — шапка страницы игры (header.jpg).
 * Discogs: название объекта — «Группа - Альбом», ищется альбом с тем же названием у той же группы, картинка — обложка.
 * Импорт идёт в фоне, по одному на всё приложение (лимиты сервисов — на адрес, а не на пользователя):
 * запросы к API не чаще заданного интервала, при отказе сервиса (429/403/5xx) — пауза и повтор.
 */
@Component
public class ImageImport {

    public static final String STEAM = "steam", DISCOGS = "discogs";
    /** Источники: ключ → название для страницы. */
    public static final Map<String, String> SOURCES = new LinkedHashMap<>();
    static {
        SOURCES.put(STEAM, "Steam");
        SOURCES.put(DISCOGS, "Discogs");
    }

    /** Паузы между запросами к API, мс: Steam — около 200 за 5 минут, Discogs — до 60 в минуту с токеном. */
    @Value("${steam.api-interval-ms:1500}")
    private long steamIntervalMs;
    @Value("${discogs.api-interval-ms:1100}")
    private long discogsIntervalMs;
    /** Пауза, если сервис ограничил запросы, мс, и сколько раз подряд её делать. */
    @Value("${import.throttle-pause-ms:60000}")
    private long throttlePauseMs;
    static final int THROTTLE_RETRIES = 5;
    /** Столько ошибок сети подряд — сервис недоступен, импорт останавливается. */
    static final int MAX_ERRORS_IN_ROW = 10;
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** Адреса сервисов (для проверки можно подменить на заглушку) и токен Discogs (переменная окружения DISCOGS_TOKEN). */
    @Value("${steam.store-url:https://store.steampowered.com}")
    private String steamUrl;
    @Value("${discogs.api-url:https://api.discogs.com}")
    private String discogsUrl;
    @Value("${discogs.token:}")
    private String discogsToken;
    @Value("${app.version}")
    private String appVersion;

    /** Состояние импорта пользователя (читается страницей через /import/status). */
    public static class Job {
        public final long collectionId;
        public final String collectionName;
        public final String source;
        public volatile String state = "queued"; // queued | running | done | cancelled | failed
        public volatile int total, processed, loaded, notFound, errors;
        public volatile String current = "";
        public volatile String message = "";      // пауза из-за ограничений сервиса, причина остановки
        volatile boolean cancel;

        Job(long collectionId, String collectionName, String source) {
            this.collectionId = collectionId;
            this.collectionName = collectionName;
            this.source = source;
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

    /** Есть ли токен Discogs (без него поиск Discogs не работает). */
    public boolean discogsReady() {
        return !discogsToken.isBlank();
    }

    /** Текущий или последний импорт пользователя (или null). */
    public Job job(long uid) {
        return jobs.get(uid);
    }

    /** Запускает импорт; false — у пользователя уже идёт импорт. Права на коллекцию и источник проверяет вызывающий код. */
    public synchronized boolean start(long uid, Entry collection, boolean onlyMissing, String source) {
        Job old = jobs.get(uid);
        if (old != null && old.active()) return false;
        Job job = new Job(collection.id(), collection.name(), source);
        List<Entry> items = repo.items(collection.id(), "name", false, Map.of()).stream()
                .filter(e -> !onlyMissing || !e.hasImage()).toList();
        job.total = items.size();
        jobs.put(uid, job);
        worker.submit(() -> run(job, items));
        return true;
    }

    public void cancel(long uid) {
        Job job = jobs.get(uid);
        if (job != null) job.cancel = true;
    }

    private void run(Job job, List<Entry> items) {
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
                    byte[] image = job.source.equals(DISCOGS) ? findDiscogs(job, item.name()) : findSteam(job, item.name());
                    if (image == null) {
                        job.notFound++;
                    } else {
                        repo.setItemImage(item.id(), image);
                        job.loaded++;
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

    /** Картинка игры с таким же названием или null, если в Steam такой игры нет. */
    private byte[] findSteam(Job job, String name) throws Exception {
        String key = norm(name);
        if (key.isEmpty()) return null;
        JsonNode res = api(job, steamUrl + "/api/storesearch/?cc=us&l=english&term=" + enc(name));
        if (res == null) return null;
        for (JsonNode it : res.path("items")) {
            if (!"app".equals(it.path("type").asText("app")) || !key.equals(norm(it.path("name").asText()))) continue;
            long appId = it.path("id").asLong();
            // Шапка игры: рядом с маленькой картинкой из поиска; если её там нет — адрес из карточки игры
            String tiny = it.path("tiny_image").asText("");
            byte[] img = tiny.contains("/") ? download(tiny.substring(0, tiny.lastIndexOf('/') + 1) + "header.jpg") : null;
            if (img == null) {
                JsonNode d = api(job, steamUrl + "/api/appdetails?filters=basic&appids=" + appId);
                String header = d == null ? "" : d.path(String.valueOf(appId)).path("data").path("header_image").asText("");
                if (!header.isEmpty()) img = download(header);
            }
            if (img == null && !tiny.isEmpty()) img = download(tiny);
            if (img != null) return img;
        }
        return null;
    }

    // ---------- Discogs ----------

    /**
     * Обложка альбома или null: название объекта «Группа - Альбом» (без разделителя — не ищем).
     * Сначала главная версия альбома (master), если её нет — отдельные издания (release).
     */
    private byte[] findDiscogs(Job job, String name) throws Exception {
        String[] aa = artistAlbum(name);
        if (aa == null) return null;
        String artist = aa[0], album = aa[1];
        for (String type : List.of("master", "release")) {
            JsonNode res = api(job, discogsUrl + "/database/search?type=" + type + "&per_page=10&artist=" + enc(artist)
                    + "&release_title=" + enc(album));
            if (res == null) continue;
            for (JsonNode r : res.path("results")) {
                // Название результата — «Группа - Альбом»: альбом должен совпасть, группа — тоже
                String[] t = artistAlbum(r.path("title").asText(""));
                if (t == null || !norm(t[1]).equals(norm(album)) || !sameArtist(t[0], artist)) continue;
                String cover = r.path("cover_image").asText("");
                if (cover.isEmpty() || cover.contains("spacer.gif")) continue; // у Discogs нет обложки — пустая заглушка
                byte[] img = download(cover);
                if (img != null) return img;
            }
        }
        return null;
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
        long interval = discogs ? discogsIntervalMs : steamIntervalMs;
        for (int attempt = 0; ; attempt++) {
            synchronized (lastCall) {
                long wait = lastCall.getOrDefault(job.source, 0L) + interval - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                lastCall.put(job.source, System.currentTimeMillis());
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "CollectionsApp/" + appVersion).GET();
            if (discogs) req.header("Authorization", "Discogs token=" + discogsToken.trim());
            HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            if (code == 200) {
                job.message = "";
                return json.readTree(r.body());
            }
            if (code == 404) return null;
            if (discogs && code == 401)
                throw new FatalImport("Discogs отклонил токен — проверьте DISCOGS_TOKEN (см. README). Импорт остановлен");
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
                || host.endsWith(".steampowered.com") || host.endsWith(".discogs.com"));
        if (!known && !url.startsWith(steamUrl + "/") && !url.startsWith(discogsUrl + "/")) return null;
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CollectionsApp/" + appVersion).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] b = r.body();
        return r.statusCode() == 200 && b.length <= MAX_IMAGE_BYTES && Repo.imageType(b) != null ? b : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Название для сравнения: без регистра, знаков ™®©, пунктуации и лишних пробелов; «&» = «and». */
    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[™®©]", "").replace("&", " and ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
