package app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Описание и картинки игры с одной страницы Old-Games.ru по ссылке, которую вставил пользователь (поле «Old-Games.ru»
 * в окне объекта). Поиска нет (его robots.txt сайта закрывает для программ), API у сайта нет — разбираются страницы:
 * игра (/game/N.html, обзор в div#reviewtext), обложки (/game/covers/N.html, первая — лицевая сторона коробки)
 * и скриншоты (/game/screenshots/N.html). Правила сайта требуют ссылку на источник — она дописывается к описанию.
 */
@Component
public class OldGames {

    /** Номер игры в каталоге (так его вводят в поле) или адрес страницы игры: https://www.old-games.ru/game/<номер>.html. */
    private static final Pattern GAME_ID = Pattern.compile("^\\d{1,7}$");
    private static final Pattern GAME_URL = Pattern.compile("^(?:https?://)?(?:www\\.)?old-games\\.ru/game/(\\d{1,7})\\.html(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    /** Сколько знаков обзора брать в описание (обрезка по абзацу; весь обзор бывает в десятки тысяч знаков). */
    static final int DESCRIPTION_LIMIT = 2000;

    /** Загруженное: описание (со ссылкой на источник) и картинки по порядку. */
    public record Result(String url, String description, List<byte[]> images) {}

    @Value("${oldgames.site-url:https://www.old-games.ru}")
    private String site;
    /** Пауза между запросами к сайту, мс (на одну игру — 3 страницы и до 5 картинок). */
    @Value("${oldgames.interval-ms:400}")
    private long intervalMs;
    @Value("${app.version}")
    private String appVersion;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).proxy(java.net.ProxySelector.getDefault()).build();
    private long lastCall;

    /** Номер игры: введённый номер или номер из адреса страницы; null — ни то ни другое. */
    public static String gameId(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (GAME_ID.matcher(v).matches()) return v.replaceFirst("^0+(?=\\d)", "");
        Matcher m = GAME_URL.matcher(v);
        return m.matches() ? m.group(1) : null;
    }

    /** Адрес страницы игры (https://www.old-games.ru/game/N.html) по номеру или адресу; null — не номер и не адрес. */
    public static String normalize(String url) {
        String id = gameId(url);
        return id == null ? null : "https://www.old-games.ru/game/" + id + ".html";
    }

    /**
     * Загружает описание и до max картинок (лицевая обложка, затем скриншоты) со страницы игры.
     * Не адрес страницы игры — IllegalArgumentException; страницы нет — null.
     */
    public synchronized Result load(String url, int max) throws Exception {
        String id = gameId(url);
        if (id == null) throw new IllegalArgumentException(I18n.t("og.needId"));
        String page = get(site + "/game/" + id + ".html");
        if (page == null) return null;
        String description = review(page);
        String source = normalize(url);
        description = (description == null ? "" : description + "\n\n") + I18n.t("og.source", source);

        List<String> urls = new ArrayList<>();
        String covers = get(site + "/game/covers/" + id + ".html");
        if (covers != null) {
            // у каждой обложки две ссылки: просмотр (…/covers/largecover/файл) и «Скачать» (…/covers/файл) — берём вторую
            Matcher m = Pattern.compile("href=\"(/games/[^\"]+/covers/(?!largecover/)[^\"/]+\\.(?:jpe?g|png|gif))\"",
                    Pattern.CASE_INSENSITIVE).matcher(covers);
            if (m.find()) urls.add(site + m.group(1)); // первая — лицевая сторона коробки
        }
        String shots = get(site + "/game/screenshots/" + id + ".html");
        if (shots != null) {
            Set<String> seen = new LinkedHashSet<>();
            Matcher m = Pattern.compile("href=\"(/games/[^\"]+/screenshots/[^\"/]+\\.(?:jpe?g|png|gif))\"",
                    Pattern.CASE_INSENSITIVE).matcher(shots);
            while (m.find()) seen.add(site + m.group(1));
            urls.addAll(seen);
        }
        List<byte[]> images = new ArrayList<>();
        for (String u : urls) {
            if (images.size() >= max) break;
            byte[] b = image(u);
            if (b != null && images.stream().noneMatch(x -> Arrays.equals(x, b))) images.add(b);
        }
        return new Result(source, description, images);
    }

    /** Начало обзора из div#reviewtext: текст без разметки, по абзацам, не длиннее DESCRIPTION_LIMIT. */
    static String review(String page) {
        int start = page.indexOf("<div id=\"reviewtext\">");
        if (start < 0) return null;
        // конец блока — по вложенности div
        Matcher m = Pattern.compile("<(/?)div\\b", Pattern.CASE_INSENSITIVE).matcher(page);
        int depth = 0, end = page.length();
        m.region(start, page.length());
        while (m.find()) {
            depth += m.group(1).isEmpty() ? 1 : -1;
            if (depth == 0) {
                end = m.start();
                break;
            }
        }
        String t = page.substring(start, end).replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n\n")
                .replaceAll("<[^>]+>", "");
        t = ImageImport.cleanDescription(t.replace("<", "&lt;")); // сущности; «<» в тексте не путать с тегами
        if (t == null) return null;
        t = t.replaceAll("[ \\t\\u00a0]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        if (t.length() <= DESCRIPTION_LIMIT) return t;
        // по абзацам, пока помещается (но хотя бы первый абзац, обрезанный по слову)
        StringBuilder b = new StringBuilder();
        for (String p : t.split("\n+")) {
            if (p.isBlank()) continue;
            if (b.length() + p.length() + 2 > DESCRIPTION_LIMIT) break;
            if (b.length() > 0) b.append("\n\n");
            b.append(p.trim());
        }
        if (b.length() == 0) {
            String cut = t.substring(0, DESCRIPTION_LIMIT);
            b.append(cut, 0, Math.max(cut.lastIndexOf(' '), DESCRIPTION_LIMIT / 2));
        }
        return b.append(" …").toString();
    }

    private void pause() throws InterruptedException {
        long wait = lastCall + intervalMs - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastCall = System.currentTimeMillis();
    }

    private HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CollectionsApp/" + appVersion).GET().build();
    }

    /** Страница сайта (UTF-8) или null, если её нет. */
    private String get(String url) throws Exception {
        pause();
        HttpResponse<String> r = http.send(request(url), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) return null;
        if (r.statusCode() != 200) throw new java.io.IOException("Old-Games.ru: " + r.statusCode());
        return r.body();
    }

    /** Картинка с сайта или null (нет такой, не картинка или больше 10 МБ). */
    private byte[] image(String url) throws Exception {
        if (!url.startsWith(site + "/games/")) return null;
        pause();
        HttpResponse<byte[]> r = http.send(request(url), HttpResponse.BodyHandlers.ofByteArray());
        byte[] b = r.body();
        return r.statusCode() == 200 && b.length <= ImageImport.MAX_IMAGE_BYTES && Repo.imageType(b) != null ? b : null;
    }
}
