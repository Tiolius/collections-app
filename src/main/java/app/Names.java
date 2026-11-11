package app;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Сравнение названий при импорте и очистка текстов из сервисов. */
final class Names {

    private Names() {}

    /**
     * Название для сравнения: без регистра, знаков ™®©, пунктуации и лишних пробелов; «&» = «and»;
     * римские числа — арабскими («Gothic II» = «Gothic 2», «Final Fantasy IX» = «Final Fantasy 9»).
     */
    static String norm(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[™®©]", "").replace("&", " and ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        StringBuilder b = new StringBuilder();
        for (String w : t.split(" ")) {
            if (b.length() > 0) b.append(' ');
            b.append(w.matches("x{0,3}(ix|iv|v?i{0,3})") && !w.isEmpty() ? String.valueOf(roman(w)) : w);
        }
        return b.toString();
    }

    /** Римское число до 39 (i…xxxix). */
    static int roman(String w) {
        int n = 0;
        for (int i = 0; i < w.length(); i++) {
            int v = w.charAt(i) == 'x' ? 10 : w.charAt(i) == 'v' ? 5 : 1;
            int next = i + 1 < w.length() ? (w.charAt(i + 1) == 'x' ? 10 : w.charAt(i + 1) == 'v' ? 5 : 1) : 0;
            n += v < next ? -v : v;
        }
        return n;
    }

    /** Название без знаков препинания (для повторного поиска: «F.E.A.R.» → «FEAR»). */
    static String plain(String name) {
        return name.replace(".", "").replaceAll("[^\\p{L}\\p{N}']+", " ").trim();
    }

    /** Приписки изданий и платформы в конце названия (после norm; «Complete+» → «complete»): та же игра с ними и без них. */
    private static final Pattern EDITION = Pattern.compile(
            " (dx|hd|pc|complete|redux|remaster(ed)?|hd remaster(ed)?|goty|((definitive|complete|enhanced|deluxe|gold|platinum|ultimate|special"
            + "|anniversary|standard|digital deluxe|game of the year|goty) edition)|director s cut|directors cut)$");

    /** Название без приписок изданий в конце: «atelier ryza … dx» → «atelier ryza …» (можно несколько подряд). */
    static String editionBase(String normName) {
        String s = normName, prev;
        do {
            prev = s;
            s = EDITION.matcher(s).replaceFirst("");
        } while (!s.equals(prev) && !s.isEmpty());
        return s.isEmpty() ? normName : s;
    }

    /** Название для запасного сравнения: без «The» в начале и приписок изданий в конце. */
    static String looseKey(String normName) {
        String s = editionBase(normName);
        return s.startsWith("the ") && s.length() > 4 ? s.substring(4) : s;
    }

    /** «Группа - Альбом» → [группа, альбом] по первому « - » (или « – », « — »); иначе null. */
    static String[] artistAlbum(String s) {
        Matcher m = Pattern.compile("\\s+[-–—]\\s+").matcher(s);
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

    /** Описание из сервиса: без HTML-тегов и сущностей, не длиннее допустимого; пустое — null. */
    static String cleanDescription(String html) {
        String t = htmlText(html.replaceAll("(?i)<br\\s*/?>", "\n")).trim();
        return limit(t);
    }

    /** Текст не длиннее допустимого описания; пустой — null. */
    static String limit(String t) {
        if (t.length() > Repo.MAX_DESCRIPTION) t = t.substring(0, Repo.MAX_DESCRIPTION).trim();
        return t.isEmpty() ? null : t;
    }

    /** Текст из HTML: без тегов, сущности (&amp;quot; &amp;#39; &amp;#x27; …) — знаками. */
    static String htmlText(String html) {
        String t = html.replaceAll("<[^>]+>", "")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">");
        Matcher m = Pattern.compile("&#(x[0-9a-fA-F]{1,6}|\\d{1,7});").matcher(t);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String n = m.group(1);
            int cp = n.startsWith("x") ? Integer.parseInt(n.substring(1), 16) : Integer.parseInt(n);
            m.appendReplacement(b, Matcher.quoteReplacement(Character.isValidCodePoint(cp) ? Character.toString(cp) : ""));
        }
        m.appendTail(b);
        return b.toString().replace("&amp;", "&");
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
