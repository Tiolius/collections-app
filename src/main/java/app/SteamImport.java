package app;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
 * Импорт картинок объектов из Steam («Настройки» → «Импорт»).
 * Объект сопоставляется с игрой Steam по названию (без учёта регистра, знаков ™®© и пунктуации);
 * картинка — шапка страницы игры (header.jpg).
 * Импорт идёт в фоне, по одному на всё приложение (лимит Steam — на адрес, а не на пользователя):
 * запросы к API Steam — не чаще раза в 1,5 с (около 200 за 5 минут), при отказе Steam (429/403) — пауза и повтор.
 */
@Component
public class SteamImport {

    /** Пауза между запросами к API Steam, мс. */
    @Value("${steam.api-interval-ms:1500}")
    private long apiIntervalMs;
    /** Пауза, если Steam ограничил запросы, мс, и сколько раз подряд её делать. */
    @Value("${steam.throttle-pause-ms:60000}")
    private long throttlePauseMs;
    static final int THROTTLE_RETRIES = 5;
    /** Столько ошибок сети подряд — Steam недоступен, импорт останавливается. */
    static final int MAX_ERRORS_IN_ROW = 10;
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** Состояние импорта пользователя (читается страницей через /import/status). */
    public static class Job {
        public final long collectionId;
        public final String collectionName;
        public volatile String state = "queued"; // queued | running | done | cancelled | failed
        public volatile int total, processed, loaded, notFound, errors;
        public volatile String current = "";
        public volatile String message = "";      // пауза из-за ограничений Steam, причина остановки
        public final List<String> notFoundNames = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile boolean cancel;

        Job(long collectionId, String collectionName) {
            this.collectionId = collectionId;
            this.collectionName = collectionName;
        }

        public boolean active() {
            return state.equals("queued") || state.equals("running");
        }
    }

    private final Repo repo;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).proxy(java.net.ProxySelector.getDefault()).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "steam-import");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private long lastApiCall;

    /** Адрес магазина Steam (для проверки можно подменить на заглушку). */
    @Value("${steam.store-url:https://store.steampowered.com}")
    private String storeUrl;

    public SteamImport(Repo repo) {
        this.repo = repo;
    }

    /** Текущий или последний импорт пользователя (или null). */
    public Job job(long uid) {
        return jobs.get(uid);
    }

    /** Запускает импорт; false — у пользователя уже идёт импорт. Права на коллекцию проверяет вызывающий код. */
    public synchronized boolean start(long uid, Entry collection, boolean onlyMissing) {
        Job old = jobs.get(uid);
        if (old != null && old.active()) return false;
        Job job = new Job(collection.id(), collection.name());
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
                    byte[] image = findImage(job, item.name());
                    if (image == null) {
                        job.notFound++;
                        job.notFoundNames.add(item.name());
                    } else {
                        repo.setItemImage(item.id(), image);
                        job.loaded++;
                    }
                    errorsInRow = 0;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    job.state = "cancelled";
                    return;
                } catch (Exception e) {
                    job.errors++;
                    if (++errorsInRow >= MAX_ERRORS_IN_ROW) {
                        job.processed++;
                        job.message = "Steam недоступен — импорт остановлен";
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

    /** Картинка игры с таким же названием или null, если в Steam такой игры нет. */
    private byte[] findImage(Job job, String name) throws Exception {
        String key = norm(name);
        if (key.isEmpty()) return null;
        JsonNode res = api(job, "/api/storesearch/?cc=us&l=english&term=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        if (res == null) return null;
        for (JsonNode it : res.path("items")) {
            if (!"app".equals(it.path("type").asText("app")) || !key.equals(norm(it.path("name").asText()))) continue;
            long appId = it.path("id").asLong();
            // Шапка игры: рядом с маленькой картинкой из поиска; если её там нет — адрес из карточки игры
            String tiny = it.path("tiny_image").asText("");
            byte[] img = tiny.contains("/") ? download(tiny.substring(0, tiny.lastIndexOf('/') + 1) + "header.jpg") : null;
            if (img == null) {
                JsonNode d = api(job, "/api/appdetails?filters=basic&appids=" + appId);
                String header = d == null ? "" : d.path(String.valueOf(appId)).path("data").path("header_image").asText("");
                if (!header.isEmpty()) img = download(header);
            }
            if (img == null && !tiny.isEmpty()) img = download(tiny);
            if (img != null) return img;
        }
        return null;
    }

    /** Запрос к API Steam с паузой между запросами; при ограничении (429/403) — пауза и повтор. null — 404. */
    private JsonNode api(Job job, String path) throws Exception {
        for (int attempt = 0; ; attempt++) {
            synchronized (this) {
                long wait = lastApiCall + apiIntervalMs - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                lastApiCall = System.currentTimeMillis();
            }
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(storeUrl + path))
                    .timeout(Duration.ofSeconds(30)).header("User-Agent", "CollectionsApp").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            if (code == 200) {
                job.message = "";
                return json.readTree(r.body());
            }
            if (code == 404) return null;
            if ((code == 429 || code == 403 || code >= 500) && attempt < THROTTLE_RETRIES) {
                job.message = "Steam ограничил запросы — пауза " + throttlePauseMs / 1000 + " с";
                Thread.sleep(throttlePauseMs);
                if (job.cancel) throw new InterruptedException();
                continue;
            }
            throw new java.io.IOException("Steam: " + code);
        }
    }

    /** Картинка по адресу Steam или null (нет такой, не картинка или слишком большая). */
    private byte[] download(String url) throws Exception {
        // Только адреса Steam (и заглушки, если адрес магазина подменён)
        URI u = URI.create(url);
        String host = u.getHost() == null ? "" : u.getHost();
        boolean steamHost = "https".equals(u.getScheme()) && (host.endsWith(".steamstatic.com") || host.endsWith(".steampowered.com"));
        if (!steamHost && !url.startsWith(storeUrl + "/")) return null;
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CollectionsApp").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] b = r.body();
        return r.statusCode() == 200 && b.length <= MAX_IMAGE_BYTES && Repo.imageType(b) != null ? b : null;
    }

    /** Название для сравнения: без регистра, знаков ™®©, пунктуации и лишних пробелов; «&» = «and». */
    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[™®©]", "").replace("&", " and ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
