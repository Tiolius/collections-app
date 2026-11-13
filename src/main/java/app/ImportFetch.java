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
 * Import requests to services: a pause between requests to each source (limits are per address, not per user),
 * when limited (429/403/5xx) — a pause and a retry; pictures are downloaded only from the source's addresses.
 */
@Component
public class ImportFetch {

    /** Pause when the service limited requests, ms, and how many times in a row to make it. */
    @Value("${import.throttle-pause-ms:60000}")
    private long throttlePauseMs;
    static final int THROTTLE_RETRIES = 5;

    /** An error after which continuing makes no sense (for example, Discogs rejected the token). */
    static class Fatal extends Exception {
        Fatal(String message) {
            super(message);
        }
    }

    private final Http http;
    private final ObjectMapper json = new ObjectMapper();
    /** Time of the last request to each source. */
    private final Map<String, Long> lastCall = new ConcurrentHashMap<>();

    public ImportFetch(Http http) {
        this.http = http;
    }

    ObjectMapper json() {
        return json;
    }

    /** Service response as JSON with pauses and retries (see page); null — 404. */
    JsonNode api(ImportSource src, ImageImport.Job job, String url) throws Exception {
        String body = page(src, job, url);
        return body == null ? null : json.readTree(body);
    }

    /** A page or service response as text: not more often than the source interval; when limited — pause and retry. null — 404. */
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

    /** Picture at a source address or null (foreign address, missing, not a picture or too large). */
    byte[] download(ImportSource src, String url) throws Exception {
        if (url == null || url.isEmpty()) return null;
        URI u = URI.create(url);
        if (!src.imageUrl(u, url)) return null;
        return http.image(url);
    }

    /** Downloads pictures from the addresses in order until max are collected; failures and duplicates (by content) are skipped. */
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
