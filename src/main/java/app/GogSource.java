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

/** GOG (без ключа): старые игры для ПК, которых нет в Steam; картинки — скриншоты и обложка коробки, описание по-русски. */
@Component
class GogSource extends ImportSource {

    /** GOG ограничений не публикует; раз в секунду — чтобы не нагружать. */
    @Value("${gog.api-interval-ms:1000}")
    private long intervalMs;
    /** Каталог (поиск) и API (карточка игры с описанием). */
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
     * Игра GOG с таким же названием: картинки и описание; null — не найдена.
     * Поиск — в английском каталоге, затем в русском (у переведённых игр там русские названия);
     * как у Steam — ещё раз без знаков препинания.
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
     * Подходящий результат поиска: точное совпадение названия (поле field), иначе без «The» и приписки издания;
     * из нескольких — первый (результаты уже упорядочены по релевантности).
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
     * Картинки и описание найденной игры. Картинки — из результата поиска: скриншоты в полном размере
     * (в адресе убирается «_{formatter}»), обложка коробки (coverVertical). Описание — из карточки игры по-русски,
     * если перевод есть (иначе GOG отдаёт английское); только если оно нужно.
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
