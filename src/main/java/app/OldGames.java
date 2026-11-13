package app;

import java.net.http.HttpResponse;
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
 * Description and pictures of a game from one Old-Games.ru page by the link the user pasted (the "Old-Games.ru" field
 * in the item window). There is no search (the site's robots.txt closes it to programs) and no API — pages are parsed:
 * game (/game/N.html, review in div#reviewtext), covers (/game/covers/N.html, the first is the front of the box)
 * and screenshots (/game/screenshots/N.html). The site's rules require a link to the source — it is appended to the description.
 */
@Component
public class OldGames {

    /** Game number in the catalogue (as typed into the field) or game page address: https://www.old-games.ru/game/<number>.html. */
    private static final Pattern GAME_ID = Pattern.compile("^\\d{1,7}$");
    private static final Pattern GAME_URL = Pattern.compile("^(?:https?://)?(?:www\\.)?old-games\\.ru/game/(\\d{1,7})\\.html(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    /** How many characters of the review go into the description (cut at a paragraph; a full review can be tens of thousands). */
    static final int DESCRIPTION_LIMIT = 2000;

    /** Loaded: description (with a link to the source) and pictures in order. */
    public record Result(String url, String description, List<byte[]> images) {}

    @Value("${oldgames.site-url:https://www.old-games.ru}")
    private String site;
    /** Pause between requests to the site, ms (3 pages and up to 5 pictures per game). */
    @Value("${oldgames.interval-ms:400}")
    private long intervalMs;
    private final Http http;

    public OldGames(Http http) {
        this.http = http;
    }
    private long lastCall;

    /** Game number: the typed number or the number from the page address; null — neither. */
    public static String gameId(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (GAME_ID.matcher(v).matches()) return v.replaceFirst("^0+(?=\\d)", "");
        Matcher m = GAME_URL.matcher(v);
        return m.matches() ? m.group(1) : null;
    }

    /** Game page address (https://www.old-games.ru/game/N.html) by number or address; null — neither a number nor an address. */
    public static String normalize(String url) {
        String id = gameId(url);
        return id == null ? null : "https://www.old-games.ru/game/" + id + ".html";
    }

    /**
     * Loads the description and up to max pictures (front cover, then screenshots) from the game page.
     * Not a game page address — IllegalArgumentException; no such page — null.
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
            // each cover has two links: view (…/covers/largecover/file) and "Download" (…/covers/file) — take the second
            Matcher m = Pattern.compile("href=\"(/games/[^\"]+/covers/(?!largecover/)[^\"/]+\\.(?:jpe?g|png|gif))\"",
                    Pattern.CASE_INSENSITIVE).matcher(covers);
            if (m.find()) urls.add(site + m.group(1)); // the first is the front of the box
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

    /** Start of the review from div#reviewtext: text without markup, by paragraphs, not longer than DESCRIPTION_LIMIT. */
    static String review(String page) {
        int start = page.indexOf("<div id=\"reviewtext\">");
        if (start < 0) return null;
        // end of the block — by div nesting
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
        t = Names.cleanDescription(t.replace("<", "&lt;")); // entities; "<" in the text must not be taken for a tag
        if (t == null) return null;
        t = t.replaceAll("[ \\t\\u00a0]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        if (t.length() <= DESCRIPTION_LIMIT) return t;
        // by paragraphs while it fits (but at least the first paragraph, cut at a word)
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

    /** A site page (UTF-8) or null if there is none. */
    private String get(String url) throws Exception {
        pause();
        HttpResponse<String> r = http.text(url);
        if (r.statusCode() == 404) return null;
        if (r.statusCode() != 200) throw new java.io.IOException("Old-Games.ru: " + r.statusCode());
        return r.body();
    }

    /** A picture from the site or null (missing, not a picture or larger than 10 MB). */
    private byte[] image(String url) throws Exception {
        if (!url.startsWith(site + "/games/")) return null;
        pause();
        return http.image(url);
    }
}
