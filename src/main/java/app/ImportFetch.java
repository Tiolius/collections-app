package app;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Запросы импорта к сервисам: пауза между запросами к каждому источнику (лимиты — на адрес, а не на пользователя),
 * при ограничении (429/403/5xx) — пауза и повтор; скачивание картинок только с адресов источника.
 */
@Component
public class ImportFetch {

    /** Пауза, если сервис ограничил запросы, мс, и сколько раз подряд её делать. */
    @Value("${import.throttle-pause-ms:60000}")
    private long throttlePauseMs;
    static final int THROTTLE_RETRIES = 5;

    /** Ошибка, после которой продолжать бессмысленно (например, Discogs отклонил токен). */
    static class Fatal extends Exception {
        Fatal(String message) {
            super(message);
        }
    }

    private final Http http;
    private final ObjectMapper json = new ObjectMapper();
    /** Время последнего запроса к каждому источнику. */
    private final Map<String, Long> lastCall = new ConcurrentHashMap<>();

    public ImportFetch(Http http) {
        this.http = http;
    }

    ObjectMapper json() {
        return json;
    }

    /** Ответ сервиса в JSON с паузами и повторами (см. page); null — 404. */
    JsonNode api(ImportSource src, ImageImport.Job job, String url) throws Exception {
        String body = page(src, job, url);
        return body == null ? null : json.readTree(body);
    }

    /** Страница или ответ сервиса текстом: не чаще интервала источника; при ограничении — пауза и повтор. null — 404. */
    String page(ImportSource src, ImageImport.Job job, String url) throws Exception {
        long interval = src.intervalMs(job);
        String[] headers = src.headers(job);
        for (int attempt = 0; ; attempt++) {
            synchronized (lastCall) {
                long wait = lastCall.getOrDefault(src.key(), 0L) + interval - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                lastCall.put(src.key(), System.currentTimeMillis());
            }
            HttpResponse<String> r = http.text(url, headers);
            int code = r.statusCode();
            if (code == 200) {
                job.message = "";
                return r.body();
            }
            if (code == 404) return null;
            src.checkStatus(job, code);
            if ((code == 429 || code == 403 || code >= 500) && attempt < THROTTLE_RETRIES) {
                job.message = I18n.t(job.locale, "import.throttled", job.sourceName(), String.valueOf(throttlePauseMs / 1000));
                Thread.sleep(throttlePauseMs);
                if (job.cancel) throw new InterruptedException();
                continue;
            }
            throw new java.io.IOException(job.sourceName() + ": " + code);
        }
    }

    /** Картинка по адресу источника или null (чужой адрес, нет такой, не картинка или слишком большая). */
    byte[] download(ImportSource src, String url) throws Exception {
        if (url == null || url.isEmpty()) return null;
        URI u = URI.create(url);
        if (!src.imageUrl(u, url)) return null;
        return http.image(url);
    }

    /** Скачивает картинки по адресам по порядку, пока не наберётся max; неудачные и повторы (по содержимому) пропускаются. */
    List<byte[]> downloadAll(ImportSource src, List<String> urls, int max) throws Exception {
        List<byte[]> res = new ArrayList<>();
        for (String u : urls) {
            if (res.size() >= max) break;
            byte[] b = download(src, u);
            if (b != null && res.stream().noneMatch(x -> Arrays.equals(x, b))) res.add(b);
        }
        return res;
    }
}
