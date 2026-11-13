package app;

import static app.Names.artistAlbum;
import static app.Names.enc;
import static app.Names.norm;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * FantLab (books, API without a key): item name — "Author - Book" or just "Title";
 * pictures — the cover of the work and covers of editions, description — the annotation.
 */
@Component
class FantlabSource extends ImportSource {

    /** FantLab asks for at most one request per 1.2 s (Crawl-delay in robots.txt). */
    @Value("${fantlab.api-interval-ms:1200}")
    private long intervalMs;
    @Value("${fantlab.api-url:https://api.fantlab.ru}")
    private String apiUrl;
    /** Site: cover paths in API responses are relative to it. */
    @Value("${fantlab.site-url:https://fantlab.ru}")
    private String site;

    FantlabSource(ImportFetch fetch) {
        super(fetch);
    }

    @Override
    String key() {
        return ImageImport.FANTLAB;
    }

    @Override
    long intervalMs(ImageImport.Job job) {
        return intervalMs;
    }

    @Override
    boolean imageUrl(URI u, String url) {
        return https(u, "fantlab.ru", ".fantlab.ru") || under(url, site);
    }

    /** Book: "Author - Book" (as with discs; the author is checked), without " - " — just "Title". null — not found. */
    @Override
    Found find(ImageImport.Job job, Entry item, boolean wantImage, boolean wantDescription) throws Exception {
        String[] at = artistAlbum(item.name());
        return at != null ? findWork(job, at[0], at[1], wantImage, wantDescription)
                : findWork(job, null, item.name().trim(), wantImage, wantDescription);
    }

    private Found findWork(ImageImport.Job job, String author, String title, boolean wantImage, boolean wantDescription) throws Exception {
        if (norm(title).isEmpty()) return null;
        JsonNode res = api(job, apiUrl + "/search-works?page=1&q=" + enc(title));
        if (res == null) return null;
        // Works with the same title (Russian or original) and, if given, the same author — from the most popular
        // (by number of ratings): otherwise a novel could be confused with a screenplay or series of the same name
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode w : res.path("matches"))
            if (sameTitle(w, title) && (author == null
                    || sameAuthor(author, w.path("all_autor_rusname").asText("") + " " + w.path("all_autor_name").asText(""))))
                matches.add(w);
        matches.sort(Comparator.comparingLong((JsonNode w) -> w.path("markcount").asLong()).reversed());
        for (JsonNode w : matches) {
            long workId = w.path("work_id").asLong();
            JsonNode work = api(job, apiUrl + "/work/" + workId);
            if (work == null) continue;
            String image = work.path("image").asText("");
            List<byte[]> imgs = List.of();
            if (wantImage) {
                // the work's own cover, then (or instead) — edition covers
                List<String> urls = new ArrayList<>();
                if (!image.isEmpty()) urls.add(image);
                if (image.isEmpty() || job.maxImages > 1) urls.addAll(editionCovers(job, workId));
                imgs = downloadAll(urls.stream().map(u -> u.startsWith("/") ? site + u : u).toList(), job.maxImages);
            }
            String descr = wantDescription ? cleanFantlab(work.path("work_description").asText("")) : null;
            if (imgs.isEmpty() && descr == null) continue;
            return new Found(imgs, descr);
        }
        return null;
    }

    /** Title: Russian, original or one of the alternative translations (Asimov's "Основание" = "Академия"). */
    static boolean sameTitle(JsonNode w, String title) {
        String t = norm(title);
        boolean same = norm(w.path("rusname").asText("")).equals(t) || norm(w.path("name").asText("")).equals(t);
        for (String alt : w.path("altname").asText("").split(";"))
            same |= !alt.isBlank() && norm(alt).equals(t);
        return same;
    }

    /** Edition covers (extended card): editions with a picture, Russian ones first. */
    private List<String> editionCovers(ImageImport.Job job, long workId) throws Exception {
        JsonNode ext = api(job, apiUrl + "/work/" + workId + "/extended");
        if (ext == null) return List.of();
        List<String> ru = new ArrayList<>(), other = new ArrayList<>();
        for (JsonNode block : ext.path("editions_blocks"))
            for (JsonNode e : block.path("list")) {
                if (e.path("pic_num").asInt() <= 0 || e.path("edition_id").asLong() <= 0) continue;
                String url = "/images/editions/big/" + e.path("edition_id").asLong();
                ("ru".equals(e.path("lang_code").asText()) ? ru : other).add(url);
            }
        ru.addAll(other);
        return ru;
    }

    /**
     * Same author: each word the user wrote matches the start (up to 5 letters) of a word in FantLab author names —
     * "Стругацкие", "Стругацкий" and "Аркадий и Борис Стругацкие" match each other.
     */
    static boolean sameAuthor(String given, String fantlabNames) {
        List<String> names = List.of(norm(fantlabNames).split(" "));
        for (String w : norm(given).split(" ")) {
            if (w.length() < 2) continue;
            String stem = w.substring(0, Math.min(5, w.length()));
            if (names.stream().noneMatch(n -> n.startsWith(stem))) return false;
        }
        return true;
    }

    /** Annotation without FantLab markup ([b], [i], [url=…], [spoiler] etc. — the text stays); empty — null. */
    static String cleanFantlab(String s) {
        String t = s.replaceAll("\\[/?[a-zA-Z]+(=[^\\]]*)?\\]", "")
                .replace("\r\n", "\n").replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        return Names.limit(t);
    }
}
