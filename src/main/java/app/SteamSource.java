package app;

import static app.Names.cleanDescription;
import static app.Names.editionBase;
import static app.Names.enc;
import static app.Names.looseKey;
import static app.Names.norm;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Steam: the item is matched to a game by name, pictures — the game's gallery screenshots (no screenshots — the header
 * of the game page, header.jpg), description — the short description of the game (in Russian if available).
 */
@Component
class SteamSource extends ImportSource {

    /** About 200 requests per 5 minutes. */
    @Value("${steam.api-interval-ms:1500}")
    private long intervalMs;
    @Value("${steam.store-url:https://store.steampowered.com}")
    private String steamUrl;

    /** Steam search region and language: USA in English (main) and Russia in Russian (Russian names). */
    private static final String SEARCH_EN = "cc=us&l=english", SEARCH_RU = "cc=ru&l=russian";

    SteamSource(ImportFetch fetch) {
        super(fetch);
    }

    @Override
    String key() {
        return ImageImport.STEAM;
    }

    @Override
    long intervalMs(ImageImport.Job job) {
        return intervalMs;
    }

    @Override
    boolean imageUrl(URI u, String url) {
        return https(u, ".steamstatic.com", ".steampowered.com") || under(url, steamUrl);
    }

    /**
     * A game with the same name: picture (if needed) and short description (if needed); null — no such game on Steam.
     * The game card (appdetails, in Russian) is requested only if a description is needed or there is no header next to the search picture.
     */
    @Override
    Found find(ImageImport.Job job, Entry item, boolean wantImage, boolean wantDescription) throws Exception {
        String name = item.name(), key = norm(name);
        if (key.isEmpty()) return null;
        // Steam search stumbles on punctuation ("Batman: … - Game of the Year Edition", "F.E.A.R."):
        // if there is no match by the name as is — once more by the name without punctuation
        String plain = Names.plain(name);
        // Steam finds Russian names only in a search with region Russia and Russian language
        // ("Бельтион: Свод Равновесия" = Beltion: Beyond Ritual): for Cyrillic names — that way too
        List<String> regions = name.matches("(?s).*\\p{IsCyrillic}.*") ? List.of(SEARCH_EN, SEARCH_RU) : List.of(SEARCH_EN);
        for (String region : regions)
            for (String term : plain.equals(name) ? List.of(name) : List.of(name, plain)) {
                Found f = findTerm(job, region, term, key, false, wantImage, wantDescription);
                if (f != null) return f;
            }
        // The last attempt — by the part before the colon: console editions with a subtitle are sold on Steam
        // as PC editions ("Disgaea 2: Dark Hero Days" → "Disgaea 2 PC", "Disgaea 4: A Promise Revisited" →
        // "Disgaea 4 Complete+"). Only a result with an edition or platform suffix counts:
        // otherwise the console "Fallout: Brotherhood of Steel" would get the pictures of the first Fallout
        int colon = name.indexOf(':');
        String prefix = colon > 0 ? name.substring(0, colon).trim() : "";
        if (!norm(prefix).isEmpty()) return findTerm(job, SEARCH_EN, prefix, norm(prefix), true, wantImage, wantDescription);
        return null;
    }

    /** Steam search by term; editionOnly — count only editions of key (with an edition or platform suffix). */
    private Found findTerm(ImageImport.Job job, String region, String term, String key, boolean editionOnly,
                           boolean wantImage, boolean wantDescription) throws Exception {
        JsonNode res = api(job, steamUrl + "/api/storesearch/?" + region + "&term=" + enc(term));
        if (res == null) return null;
        // Exact name match; if none — the same name without a leading "The" and an edition suffix
        // (DX, Remastered, GOTY…): "Banner Saga 3" = "The Banner Saga 3"; Steam often delists an old edition after a new
        // one comes out, and the search finds only the new one
        JsonNode found = null, edition = null;
        for (JsonNode it : res.path("items")) {
            if (!"app".equals(it.path("type").asText("app"))) continue;
            String n = norm(it.path("name").asText());
            if (!editionOnly && key.equals(n)) { found = it; break; }
            if (edition == null && looseKey(key).equals(looseKey(n)) && (!editionOnly || !editionBase(n).equals(n))) edition = it;
        }
        if (found == null) found = edition;
        if (found == null) return null;
        long appId = found.path("id").asLong();
        String tiny = found.path("tiny_image").asText("");
        JsonNode details = null;
        String description = null;
        // description and screenshots — in one game card, one request
        if (wantDescription || wantImage) details = appDetails(job, appId, wantImage);
        if (wantDescription) description = cleanDescription(details.path("short_description").asText(""));
        List<byte[]> imgs = List.of();
        if (wantImage) {
            // full-size gallery screenshots; if there are none — the header of the game page
            List<String> shots = new ArrayList<>();
            for (JsonNode sh : details.path("screenshots")) shots.add(sh.path("path_full").asText(""));
            imgs = downloadAll(shots, job.maxImages);
            if (imgs.isEmpty()) {
                byte[] header = header(job, appId, tiny, details);
                if (header != null) imgs = List.of(header);
            }
        }
        return new Found(imgs, description);
    }

    /** Game header: next to the small search picture; if not there — the address from the game card; otherwise the small picture. */
    private byte[] header(ImageImport.Job job, long appId, String tiny, JsonNode details) throws Exception {
        byte[] img = tiny.contains("/") ? download(tiny.substring(0, tiny.lastIndexOf('/') + 1) + "header.jpg") : null;
        if (img == null) {
            if (details == null) details = appDetails(job, appId, false);
            String header = details.path("header_image").asText("");
            if (!header.isEmpty()) img = download(header);
        }
        if (img == null && !tiny.isEmpty()) img = download(tiny);
        return img;
    }

    /**
     * Steam game card (data from appdetails) in Russian, with screenshots if needed; an empty node if there is none.
     * Region — USA, as in the search: without it Steam takes the region by address, and for games not sold in Russia
     * (for example, Sword Art Online: Fatal Bullet) returns an empty card — without screenshots and description.
     */
    private JsonNode appDetails(ImageImport.Job job, long appId, boolean screenshots) throws Exception {
        JsonNode d = api(job, steamUrl + "/api/appdetails?filters=basic" + (screenshots ? ",screenshots" : "")
                + "&cc=us&l=russian&appids=" + appId);
        return d == null ? fetch.json().createObjectNode() : d.path(String.valueOf(appId)).path("data");
    }
}
