package app;

import static app.Names.cleanDescription;
import static app.Names.enc;
import static app.Names.looseKey;
import static app.Names.norm;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** GOG (no key): old PC games that are not on Steam; pictures — screenshots and the box cover, description in Russian. */
@Component
class GogSource extends ImportSource {

    /** GOG publishes no limits; once a second so as not to load it. */
    @Value("${gog.api-interval-ms:1000}")
    private long intervalMs;
    /** Catalogue (search) and API (game card with a description). */
    @Value("${gog.catalog-url:https://catalog.gog.com}")
    private String catalogUrl;
    @Value("${gog.api-url:https://api.gog.com}")
    private String apiUrl;

    GogSource(ImportFetch fetch) {
        super(fetch);
    }

    @Override
    String key() {
        return ImageImport.GOG;
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
        return https(u, ".gog-statics.com") || under(url, catalogUrl);
    }

    /**
     * A GOG game with the same name: pictures and description; null — not found.
     * Search in the English catalogue, then in the Russian one (translated games have Russian names there);
     * as with Steam — once more without punctuation.
     */
    @Override
    Found find(ImageImport.Job job, Entry item, boolean wantImage, boolean wantDescription) throws Exception {
        String name = item.name(), key = norm(name);
        if (key.isEmpty()) return null;
        String plain = Names.plain(name);
        for (String locale : List.of("en-US", "ru-RU"))
            for (String term : plain.equals(name) ? List.of(name) : List.of(name, plain)) {
                JsonNode res = api(job, catalogUrl + "/v1/catalog?limit=20&productType=in:game,pack&order=desc:score"
                        + "&countryCode=US&locale=" + locale + "&query=" + enc("like:" + term));
                JsonNode p = res == null ? null : bestMatch(res.path("products"), "title", key);
                if (p != null) return found(job, p, wantImage, wantDescription);
            }
        return null;
    }

    /**
     * A matching search result: exact name match (field field), otherwise without "The" and an edition suffix;
     * of several — the first (results are already ordered by relevance).
     */
    static JsonNode bestMatch(JsonNode results, String field, String key) {
        JsonNode loose = null;
        for (JsonNode it : results) {
            String n = norm(it.path(field).asText());
            if (key.equals(n)) return it;
            if (loose == null && looseKey(key).equals(looseKey(n))) loose = it;
        }
        return loose;
    }

    /**
     * Pictures and description of the found game. Pictures come from the search result: full-size screenshots
     * ("_{formatter}" removed from the address), the box cover (coverVertical). Description — from the game card in Russian
     * if there is a translation (otherwise GOG returns English); only if it is needed.
     */
    private Found found(ImageImport.Job job, JsonNode p, boolean wantImage, boolean wantDescription) throws Exception {
        String description = null;
        if (wantDescription) {
            JsonNode d = api(job, apiUrl + "/products/" + p.path("id").asLong() + "?expand=description&locale=ru-RU");
            if (d != null) {
                String full = d.path("description").path("full").asText("");
                description = cleanDescription(full.isBlank() ? d.path("description").path("lead").asText("") : full);
                if (description != null) description = description.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
            }
        }
        List<byte[]> imgs = List.of();
        if (wantImage) {
            List<String> shots = new ArrayList<>();
            for (JsonNode sh : p.path("screenshots")) shots.add(sh.asText("").replace("_{formatter}", ""));
            imgs = downloadAll(coverAndShots(job, p.path("coverVertical").asText(""), shots), job.maxImages);
        }
        return new Found(imgs, description);
    }
}
