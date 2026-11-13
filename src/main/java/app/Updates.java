package app;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Update check: the latest release on GitHub (updates.url) at most once per CHECK_EVERY, in the background — pages do not wait.
 * Newer than the installed version (app.version) — shown to the administrator in the header and in "About".
 * Turn off with updates.enabled=false (variable UPDATES_ENABLED=false). A network error is silent; the next attempt comes later.
 */
@Component
public class Updates {

    private static final Logger log = LoggerFactory.getLogger(Updates.class);
    private static final Duration CHECK_EVERY = Duration.ofHours(12);
    /** After a failed check — retry sooner. */
    private static final Duration RETRY_AFTER = Duration.ofHours(1);

    /** Latest release: version (without "v") and release page. */
    public record Release(String version, String url) {}

    @Value("${updates.enabled:true}")
    private boolean enabled;
    @Value("${updates.url:https://api.github.com/repos/Tiolius/collections-app/releases/latest}")
    private String url;
    @Value("${app.version}")
    private String current;

    private final Http http;

    public Updates(Http http) {
        this.http = http;
    }
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile Release latest;
    private volatile boolean failed;
    private volatile long nextCheck;

    /** The first check runs right after start, so the answer is ready by the first page. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onStart() {
        refreshIfDue();
    }

    /** New version if there is one (otherwise null); also starts a background check if it is time. */
    public Release available() {
        refreshIfDue();
        Release r = latest;
        return r != null && newer(r.version(), current) ? r : null;
    }

    /** State for "About": check turned off, no answer yet or the last attempt failed. */
    public String state() {
        if (!enabled) return "off";
        refreshIfDue();
        if (latest == null) return failed ? "failed" : "unknown";
        return newer(latest.version(), current) ? "available" : "latest";
    }

    public Release latest() {
        return latest;
    }

    private void refreshIfDue() {
        if (!enabled || System.currentTimeMillis() < nextCheck || !checking.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("update-check").start(() -> {
            try {
                check();
            } finally {
                checking.set(false);
            }
        });
    }

    private void check() {
        try {
            HttpResponse<String> r = http.text(url, "Accept", "application/vnd.github+json");
            if (r.statusCode() != 200) throw new java.io.IOException("HTTP " + r.statusCode());
            JsonNode n = json.readTree(r.body());
            String tag = n.path("tag_name").asText("").replaceFirst("^[vV]", "");
            if (!tag.matches("\\d+(\\.\\d+)*")) throw new java.io.IOException("tag " + tag);
            latest = new Release(tag, n.path("html_url").asText(""));
            failed = false;
            nextCheck = System.currentTimeMillis() + CHECK_EVERY.toMillis();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            failed = true;
            nextCheck = System.currentTimeMillis() + RETRY_AFTER.toMillis();
            log.debug("Проверка обновлений не удалась: {}", e.toString());
        }
    }

    /** a is newer than b: compared by numbers ("1.10.0" is newer than "1.9.3"); not a version number — not newer. */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.replaceFirst("-.*", "").split("\\.");
        try {
            for (int i = 0; i < Math.max(x.length, y.length); i++) {
                long p = i < x.length ? Long.parseLong(x[i]) : 0, q = i < y.length ? Long.parseLong(y[i]) : 0;
                if (p != q) return p > q;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return false;
    }
}
