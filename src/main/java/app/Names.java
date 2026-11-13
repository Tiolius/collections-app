package app;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Comparing names on import and cleaning texts from services. */
final class Names {

    private Names() {}

    /**
     * Name for comparison: case-insensitive, without ™®© signs, punctuation and extra spaces; "&" = "and";
     * Roman numerals as Arabic ("Gothic II" = "Gothic 2", "Final Fantasy IX" = "Final Fantasy 9").
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

    /** Roman numeral up to 39 (i…xxxix). */
    static int roman(String w) {
        int n = 0;
        for (int i = 0; i < w.length(); i++) {
            int v = w.charAt(i) == 'x' ? 10 : w.charAt(i) == 'v' ? 5 : 1;
            int next = i + 1 < w.length() ? (w.charAt(i + 1) == 'x' ? 10 : w.charAt(i + 1) == 'v' ? 5 : 1) : 0;
            n += v < next ? -v : v;
        }
        return n;
    }

    /** Name without punctuation (for a repeated search: "F.E.A.R." → "FEAR"). */
    static String plain(String name) {
        return name.replace(".", "").replaceAll("[^\\p{L}\\p{N}']+", " ").trim();
    }

    /** Edition and platform suffixes at the end of a name (after norm; "Complete+" → "complete"): the same game with and without them. */
    private static final Pattern EDITION = Pattern.compile(
            " (dx|hd|pc|complete|redux|remaster(ed)?|hd remaster(ed)?|goty|((definitive|complete|enhanced|deluxe|gold|platinum|ultimate|special"
            + "|anniversary|standard|digital deluxe|game of the year|goty) edition)|director s cut|directors cut)$");

    /** Name without edition suffixes at the end: "atelier ryza … dx" → "atelier ryza …" (several in a row allowed). */
    static String editionBase(String normName) {
        String s = normName, prev;
        do {
            prev = s;
            s = EDITION.matcher(s).replaceFirst("");
        } while (!s.equals(prev) && !s.isEmpty());
        return s.isEmpty() ? normName : s;
    }

    /** Name for the fallback comparison: without a leading "The" and edition suffixes at the end. */
    static String looseKey(String normName) {
        String s = editionBase(normName);
        return s.startsWith("the ") && s.length() > 4 ? s.substring(4) : s;
    }

    /** "Group - Album" → [group, album] by the first " - " (or " – ", " — "); otherwise null. */
    static String[] artistAlbum(String s) {
        Matcher m = Pattern.compile("\\s+[-–—]\\s+").matcher(s);
        if (!m.find()) return null;
        String a = s.substring(0, m.start()).trim(), b = s.substring(m.end()).trim();
        return a.isEmpty() || b.isEmpty() ? null : new String[] {a, b};
    }

    /** Same artist: ignoring case, punctuation, a leading "The" and Discogs numbers like "Name (2)" or "Name*". */
    static boolean sameArtist(String a, String b) {
        return artistKey(a).equals(artistKey(b));
    }

    private static String artistKey(String s) {
        return norm(s.replaceAll("\\s*\\(\\d+\\)$", "").replaceAll("\\*$", "")).replaceAll("^the ", "");
    }

    /** Description from a service: without HTML tags and entities, not longer than allowed; empty — null. */
    static String cleanDescription(String html) {
        String t = htmlText(html.replaceAll("(?i)<br\\s*/?>", "\n")).trim();
        return limit(t);
    }

    /** Text not longer than an allowed description; empty — null. */
    static String limit(String t) {
        if (t.length() > Repo.MAX_DESCRIPTION) t = t.substring(0, Repo.MAX_DESCRIPTION).trim();
        return t.isEmpty() ? null : t;
    }

    /** Text from HTML: without tags, entities (&amp;quot; &amp;#39; &amp;#x27; …) as characters. */
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
