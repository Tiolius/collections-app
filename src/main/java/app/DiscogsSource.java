package app;

import static app.Names.artistAlbum;
import static app.Names.enc;
import static app.Names.norm;
import static app.Names.sameArtist;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Discogs (music): item name — "Group - Album", an album with the same name by the same group is searched for.
 * Description — Notes of the album's master version (otherwise of its main release); pictures — of the most popular release
 * of the same album on the item's media (CD, vinyl, cassette). Each user has their own Discogs token
 * ("Import" tab): without it Discogs returns results and Notes, but no covers, and only 25 requests a minute.
 */
@Component
class DiscogsSource extends ImportSource {

    /** With a token — up to 60 requests a minute; without — 25 (at most once per 2.5 s). */
    @Value("${discogs.api-interval-ms:1100}")
    private long intervalMs;
    @Value("${discogs.api-url:https://api.discogs.com}")
    private String apiUrl;

    DiscogsSource(ImportFetch fetch) {
        super(fetch);
    }

    @Override
    String key() {
        return ImageImport.DISCOGS;
    }

    @Override
    long intervalMs(ImageImport.Job job) {
        return job.discogsToken.isEmpty() ? Math.max(intervalMs, 2500) : intervalMs;
    }

    @Override
    String[] headers(ImageImport.Job job) {
        return job.discogsToken.isEmpty() ? new String[0] : new String[] {"Authorization", "Discogs token=" + job.discogsToken};
    }

    @Override
    void checkStatus(ImageImport.Job job, int code) throws ImportFetch.Fatal {
        if (code == 401) throw new ImportFetch.Fatal(I18n.t(job.locale, "import.badToken"));
    }

    @Override
    boolean imageUrl(URI u, String url) {
        return https(u, ".discogs.com") || under(url, apiUrl);
    }

    /**
     * Album "Group - Album" (no separator — not searched): pictures (if needed) and Notes (if a description is needed); null — not found.
     * First the album's master version, if there is none — individual releases.
     * Pictures — of a release on the item's media (the "Format" field: vinyl, cassette; otherwise CD): the master version has the pictures
     * of its main release, which is often vinyl; no such release — the pictures of the master version.
     */
    @Override
    Found find(ImageImport.Job job, Entry item, boolean wantImage, boolean wantDescription) throws Exception {
        String[] aa = artistAlbum(item.name());
        if (aa == null) return null;
        String artist = aa[0], album = aa[1];
        String format = format(item.format());
        for (String type : List.of("master", "release")) {
            JsonNode res = api(job, apiUrl + "/database/search?type=" + type + "&per_page=10&artist=" + enc(artist)
                    + "&release_title=" + enc(album));
            if (res == null) continue;
            // Matching results (the name "Group - Album" matches) — from the most popular:
            // a film, compilation or bootleg of the same name has far fewer owners on Discogs than the album itself
            List<JsonNode> matches = new ArrayList<>();
            for (JsonNode r : res.path("results"))
                if (sameAlbum(r, artist, album)) matches.add(r);
            matches.sort(Comparator.comparingLong((JsonNode r) -> r.path("community").path("have").asLong()).reversed());
            for (JsonNode r : matches) {
                Map<String, JsonNode> cache = new HashMap<>(); // album cards: one request for Notes and pictures
                long id = r.path("id").asLong();
                List<byte[]> imgs = List.of();
                if (wantImage) {
                    // A release of the same album on the needed media; none — the pictures of the found result
                    long masterId = type.equals("master") ? id : r.path("master_id").asLong();
                    JsonNode media = mediaRelease(job, artist, album, format, masterId);
                    JsonNode src = media != null ? media : r;
                    String srcType = media != null ? "release" : type;
                    List<String> urls = new ArrayList<>();
                    // several pictures — from the album card (cover, back, insert…), otherwise — the cover from the search
                    if (job.maxImages > 1) urls.addAll(images(job, srcType, src.path("id").asLong(), cache));
                    String cover = src.path("cover_image").asText("");
                    // the cover from the search — if the card has nothing (otherwise it would be the same picture in another size);
                    // no cover — Discogs has an empty spacer.gif placeholder
                    if (urls.isEmpty() && realCover(cover)) urls.add(cover);
                    imgs = downloadAll(urls, job.maxImages);
                }
                String notes = wantDescription ? notes(job, type, id, cache) : null;
                if (imgs.isEmpty() && notes == null) continue; // neither cover nor notes — next result
                return new Found(imgs, notes);
            }
        }
        return null;
    }

    /** The search result is the same album: the name "Group - Album" matches. */
    private static boolean sameAlbum(JsonNode r, String artist, String album) {
        String[] t = artistAlbum(r.path("title").asText(""));
        return t != null && norm(t[1]).equals(norm(album)) && sameArtist(t[0], artist);
    }

    private static boolean realCover(String url) {
        return !url.isEmpty() && !url.contains("spacer.gif");
    }

    /** Media for searching releases by the item's "Format" field: vinyl, cassette; everything else (CD, empty) — CD. */
    static String format(String itemFormat) {
        String f = itemFormat == null ? "" : itemFormat.toLowerCase(Locale.ROOT);
        if (f.matches(".*(vinyl|винил|пластинк|\\blp\\b).*")) return "Vinyl";
        if (f.matches(".*(cass|кассет|\\bmc\\b).*")) return "Cassette";
        return "CD";
    }

    /**
     * The most popular release of the album on media format — with a cover; null — there is none.
     * The search is sorted by number of owners right away (sort=have): by default Discogs sorts by relevance,
     * and the most popular releases may not get into the first 50.
     * The same album — the same master (masterId > 0), otherwise — the same name "Group - Album".
     */
    private JsonNode mediaRelease(ImageImport.Job job, String artist, String album, String format, long masterId) throws Exception {
        JsonNode res = api(job, apiUrl + "/database/search?type=release&format=" + enc(format)
                + "&sort=have&sort_order=desc&per_page=50&artist=" + enc(artist) + "&release_title=" + enc(album));
        return res == null ? null : bestMedia(res.path("results"), artist, album, format, masterId);
    }

    /** From release search results — the most popular release of the same album on media format with a cover. */
    static JsonNode bestMedia(JsonNode results, String artist, String album, String format, long masterId) {
        JsonNode best = null;
        for (JsonNode r : results) {
            if (masterId > 0 ? r.path("master_id").asLong() != masterId : !sameAlbum(r, artist, album)) continue;
            // media — in the release's format list (in case Discogs ignored the filter)
            boolean hasFormat = false;
            for (JsonNode f : r.path("format")) hasFormat |= f.asText("").equalsIgnoreCase(format);
            if (!hasFormat || !realCover(r.path("cover_image").asText(""))) continue;
            if (best == null || r.path("community").path("have").asLong() > best.path("community").path("have").asLong()) best = r;
        }
        return best;
    }

    /** A request with the answer remembered within one album. */
    private JsonNode cached(ImageImport.Job job, String url, Map<String, JsonNode> cache) throws Exception {
        if (cache.containsKey(url)) return cache.get(url);
        JsonNode n = api(job, url);
        cache.put(url, n);
        return n;
    }

    /** Picture addresses from a card (master or release), the main one first; without a token Discogs does not return them. */
    private List<String> images(ImageImport.Job job, String type, long id, Map<String, JsonNode> cache) throws Exception {
        if (id <= 0) return List.of();
        JsonNode d = cached(job, apiUrl + (type.equals("master") ? "/masters/" : "/releases/") + id, cache);
        List<String> res = new ArrayList<>();
        if (d != null)
            for (JsonNode im : d.path("images")) {
                String uri = im.path("uri").asText("");
                if (uri.isEmpty()) continue;
                if ("primary".equals(im.path("type").asText())) res.add(0, uri);
                else res.add(uri);
            }
        return res;
    }

    /** Album Notes: of the master version, if empty there — of its main release; of a release — its own Notes. */
    private String notes(ImageImport.Job job, String type, long id, Map<String, JsonNode> cache) throws Exception {
        if (id <= 0) return null;
        if (type.equals("master")) {
            JsonNode m = cached(job, apiUrl + "/masters/" + id, cache);
            if (m == null) return null;
            String notes = cleanNotes(m.path("notes").asText(""));
            if (notes != null) return notes;
            long main = m.path("main_release").asLong();
            if (main <= 0) return null;
            id = main;
        }
        JsonNode rel = cached(job, apiUrl + "/releases/" + id, cache);
        return rel == null ? null : cleanNotes(rel.path("notes").asText(""));
    }

    /**
     * Notes without Discogs markup: [url=…]text[/url] → text, [a=Name]/[l=Name] → Name,
     * links to records by number ([a123], [m=123]) and tags [b] [i] [u] are removed, spaces before punctuation too; empty — null.
     */
    static String cleanNotes(String s) {
        String t = s.replaceAll("(?is)\\[url=[^\\]]*\\](.*?)\\[/url\\]", "$1")
                .replaceAll("\\[[alrm]=?\\d+\\]", "")          // links by number: [a45467], [m=11329]
                .replaceAll("\\[[alrm]=([^\\]]+)\\]", "$1")    // by name: [a=Name], [l=Studio]
                .replaceAll("(?i)\\[/?[biu]\\]", "")
                .replaceAll("[ \\t]+([,.;:!?)])", "$1").replaceAll("[ \\t]{2,}", " ")
                .replace("\r\n", "\n").replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        return Names.limit(t);
    }
}
