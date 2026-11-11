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
 * База игр LaunchBox (без ключа; консоли всех поколений: PlayStation, Nintendo, Sega…): то же название и та же
 * платформа (если у объекта она указана и известна). Сайт разбирается как страницы: поиск (/games/results/…)
 * и страница игры — описание (Overview) и картинки с подписями типа.
 */
@Component
class LaunchboxSource extends ImportSource {

    /** LaunchBox ограничений не публикует; раз в секунду — чтобы не нагружать. */
    @Value("${launchbox.api-interval-ms:1000}")
    private long intervalMs;
    @Value("${launchbox.site-url:https://gamesdb.launchbox-app.com}")
    private String site;

    /** Игра в результатах поиска: адрес страницы, номер, название, платформа. */
    private static final Pattern CARD = Pattern.compile(
            "href=\"(/games/details/(\\d+)-[^\"]*)\">.*?<h3[^>]*>(.*?)</h3>\\s*<p[^>]*>(.*?)</p>", Pattern.DOTALL);
    /** Картинка на странице игры: адрес и подпись «Игра - Тип (Регион) - ШxВ». */
    private static final Pattern IMAGE = Pattern.compile(
            "<img src=\"(https://images\\.launchbox-app\\.com/+[^\"]+)\"[^>]*alt=\"([^\"]*)\"");
    /** Регионы обложек по предпочтению: сначала европейские (как у дисков в России), японские — в последнюю очередь. */
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

    /** Из одинаковых по названию — самая ранняя запись базы (обычно исходная игра, а не порт или сборка); null — не найдена. */
    @Override
    Found find(ImageImport.Job job, Entry item, boolean wantImage, boolean wantDescription) throws Exception {
        String name = item.name(), key = norm(name);
        if (key.isEmpty()) return null;
        // Сайт перенаправляет поиск на адрес в нижнем регистре (для кириллицы — по кругу): сразу так
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

    /** Описание игры — абзацы раздела Overview; пустое — null. */
    static String overview(String html) {
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
        return Names.limit(b.toString());
    }

    /**
     * Картинки игры по порядку: лицевая сторона коробки (одна — лучший регион; нет — восстановленная)
     * и скриншоты (сначала игровые, крупные первыми, затем титульный экран) — в порядке, заданном режимом.
     */
    static List<String> images(ImageImport.Job job, String html) {
        String cover = null, rebuilt = null;
        int coverRank = Integer.MAX_VALUE, rebuiltRank = Integer.MAX_VALUE;
        List<String[]> gameplay = new ArrayList<>(); // [адрес, площадь]
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

    /** Место региона подписи («… (Europe) - 1621x1394») среди предпочтительных; прочие — после них, Япония — последней. */
    static int regionRank(String alt) {
        Matcher m = Pattern.compile("\\(([^()]*)\\) - \\d+x\\d+$").matcher(alt);
        String region = m.find() ? m.group(1) : "";
        int i = REGIONS.indexOf(region);
        return i >= 0 ? i : region.equals("Japan") ? 100 : 50;
    }

    /** Площадь картинки по подписи «… - 1024x714»; нет размера — 0. */
    static long area(String alt) {
        Matcher m = Pattern.compile("(\\d+)x(\\d+)$").matcher(alt);
        return m.find() ? Long.parseLong(m.group(1)) * Long.parseLong(m.group(2)) : 0;
    }
}
