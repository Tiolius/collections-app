package app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
 * Проверка обновлений: последний релиз на GitHub (updates.url) не чаще раза в CHECK_EVERY, в фоне — страницы не ждут.
 * Новее установленной версии (app.version) — показывается администратору в шапке и в «О приложении».
 * Отключить — updates.enabled=false (переменная UPDATES_ENABLED=false). Ошибка сети — молча, следующая попытка позже.
 */
@Component
public class Updates {

    private static final Logger log = LoggerFactory.getLogger(Updates.class);
    private static final Duration CHECK_EVERY = Duration.ofHours(12);
    /** После неудачной проверки — повтор раньше. */
    private static final Duration RETRY_AFTER = Duration.ofHours(1);

    /** Последний релиз: версия (без «v») и страница релиза. */
    public record Release(String version, String url) {}

    @Value("${updates.enabled:true}")
    private boolean enabled;
    @Value("${updates.url:https://api.github.com/repos/Tiolius/collections-app/releases/latest}")
    private String url;
    @Value("${app.version}")
    private String current;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).proxy(java.net.ProxySelector.getDefault()).build();
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile Release latest;
    private volatile boolean failed;
    private volatile long nextCheck;

    /** Первая проверка — сразу после запуска, чтобы к первой странице ответ уже был. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onStart() {
        refreshIfDue();
    }

    /** Новая версия, если она есть (иначе null); заодно запускает проверку в фоне, если пора. */
    public Release available() {
        refreshIfDue();
        Release r = latest;
        return r != null && newer(r.version(), current) ? r : null;
    }

    /** Состояние для «О приложении»: проверка выключена, ещё не было ответа или последняя попытка не удалась. */
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
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CollectionsApp/" + current).GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
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

    /** a новее b: сравнение по числам («1.10.0» новее «1.9.3»); не номер версии — не новее. */
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
