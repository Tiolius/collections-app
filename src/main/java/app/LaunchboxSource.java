package app;

import static app.Names.enc;
import static app.Names.htmlText;
import static app.Names.looseKey;
import static app.Names.norm;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LaunchBox games database (no key; consoles of all generations: PlayStation, Nintendo, Sega…): the same name and the same
 * platform (if the item has one and it is known). The site is parsed as pages: search (/games/results/…)
 * and the game page — description (Overview) and pictures with type captions.
 */
@Component
class LaunchboxSource extends ImportSource {

    /** LaunchBox publishes no limits; once a second so as not to load it. */
    @Value("${launchbox.api-interval-ms:1000}")
    private long intervalMs;
    @Value("${launchbox.site-url:https://gamesdb.launchbox-app.com}")
    private String site;

    /** Game in the search results: page address, number, name, platform. */
    private static final Pattern CARD = Pattern.compile(
            "href=\"(/games/details/(\\d+)-[^\"]*)\">.*?<h3[^>]*>(.*?)</h3>\\s*<p[^>]*>(.*?)</p>", Pattern.DOTALL);
    /** Picture on the game page: address and caption "Game - Type (Region) - WxH". */
    private static final Pattern IMAGE = Pattern.compile(
            "<img src=\"(https://images\\.launchbox-app\\.com/+[^\"]+)\"[^>]*alt=\"([^\"]*)\"");
    /** Cover regions by preference: European first (like discs in Russia), Japanese last. */
    private static final List<String> REGIONS = List.of("Russia", "Europe", "United Kingdom", "World", "North America", "United States");

    LaunchboxSource(ImportFetch fetch) {
        super(fetch);
    }

    @Override
    String key() {
        return ImageImport.LAUNCHBOX;
    }

    @Override
    boolean covers() {
        return true;
    }

    @Override
    long intervalMs(ImageImport.Job job) {
        return intervalMs;
    }

    @Override
    boolean imageUrl(URI u, String url) {
        return https(u, "images.launchbox-app.com") || under(url, site);
    }

    /** Of entries with the same name — the earliest database record (usually the original game, not a port or compilation); null — not found. */
    @Override
    Found find(ImageImport.Job job, Entry item, boolean wantImage, boolean wantDescription) throws Exception {
        String name = item.name(), key = norm(name);
        if (key.isEmpty()) return null;
        // The site redirects the search to a lower-case address (for Cyrillic — in a loop): go there right away
        String term = enc(name.toLowerCase(Locale.ROOT)).replace("+", "%20");
        String html = page(job, site + "/games/results/" + term + "?searchText=" + term);
        if (html == null) return null;
        Set<String> wanted = Platforms.codes(item.platform());
        String exact = null, loose = null;
        long exactId = Long.MAX_VALUE, looseId = Long.MAX_VALUE;
        Matcher m = CARD.matcher(html);
        while (m.find()) {
            if (!Platforms.same(wanted, List.of(htmlText(m.group(4))))) continue;
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
        String game = page(job, site + path);
        if (game == null) return null;
        List<byte[]> imgs = wantImage ? downloadAll(images(job, game), job.maxImages) : List.of();
        return new Found(imgs, wantDescription ? overview(game) : null);
    }

    /** Game description — paragraphs of the Overview section; empty — null. */
    static String overview(String html) {
        int start = html.indexOf("id=\"overview\"");
        if (start < 0) return null;
        int end = html.indexOf("<dl", start);
        String section = html.substring(start, end > start ? end : Math.min(html.length(), start + 20000));
        StringBuilder b = new StringBuilder();
        // <p> and <p …>, but not <path> (the Edit button icon next to the heading)
        Matcher m = Pattern.compile("<p(?:\\s[^>]*)?>(.*?)</p>", Pattern.DOTALL).matcher(section);
        while (m.find()) {
            String p = htmlText(m.group(1).replaceAll("(?i)<br\\s*/?>", "\n")).trim();
            if (!p.isEmpty()) b.append(b.length() > 0 ? "\n\n" : "").append(p);
        }
        return Names.limit(b.toString());
    }

    /**
     * Game pictures in order: the front of the box (one — the best region; none — a restored one)
     * and screenshots (gameplay first, larger first, then the title screen) — in the order set by the mode.
     */
    static List<String> images(ImageImport.Job job, String html) {
        String cover = null, rebuilt = null;
        int coverRank = Integer.MAX_VALUE, rebuiltRank = Integer.MAX_VALUE;
        List<String[]> gameplay = new ArrayList<>(); // [address, area]
        List<String> titles = new ArrayList<>();
        Matcher m = IMAGE.matcher(html);
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
        gameplay.sort(Comparator.comparingLong((String[] g) -> Long.parseLong(g[1])).reversed());
        List<String> shots = new ArrayList<>();
        for (String[] g : gameplay) shots.add(g[0]);
        shots.addAll(titles);
        return coverAndShots(job, cover != null ? cover : rebuilt, shots);
    }

    /** Place of the caption region ("… (Europe) - 1621x1394") among preferred ones; others — after them, Japan — last. */
    static int regionRank(String alt) {
        Matcher m = Pattern.compile("\\(([^()]*)\\) - \\d+x\\d+$").matcher(alt);
        String region = m.find() ? m.group(1) : "";
        int i = REGIONS.indexOf(region);
        return i >= 0 ? i : region.equals("Japan") ? 100 : 50;
    }

    /** Picture area from the caption "… - 1024x714"; no size — 0. */
    static long area(String alt) {
        Matcher m = Pattern.compile("(\\d+)x(\\d+)$").matcher(alt);
        return m.find() ? Long.parseLong(m.group(1)) * Long.parseLong(m.group(2)) : 0;
    }
}
