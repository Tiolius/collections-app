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
 * Steam: объект сопоставляется с игрой по названию, картинки — скриншоты галереи игры (нет скриншотов — шапка
 * страницы игры, header.jpg), описание — краткое описание игры (по-русски, если есть).
 */
@Component
class SteamSource extends ImportSource {

    /** Около 200 запросов за 5 минут. */
    @Value("${steam.api-interval-ms:1500}")
    private long intervalMs;
    @Value("${steam.store-url:https://store.steampowered.com}")
    private String steamUrl;

    /** Регион и язык поиска Steam: США по-английски (основной) и Россия по-русски (русские названия). */
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
     * Игра с таким же названием: картинка (если нужна) и краткое описание (если нужно); null — в Steam такой игры нет.
     * Карточка игры (appdetails, по-русски) запрашивается, только если нужно описание или шапки нет рядом с картинкой из поиска.
     */
    @Override
    Found find(ImageImport.Job job, Entry item, boolean wantImage, boolean wantDescription) throws Exception {
        String name = item.name(), key = norm(name);
        if (key.isEmpty()) return null;
        // Поиск Steam спотыкается о знаки («Batman: … - Game of the Year Edition», «F.E.A.R.»):
        // если по названию как есть совпадения нет — ещё раз по названию без знаков препинания
        String plain = Names.plain(name);
        // Русские названия Steam находит только в поиске с регионом Россия и русским языком
        // («Бельтион: Свод Равновесия» = Beltion: Beyond Ritual): для названий на кириллице — ещё и так
        List<String> regions = name.matches("(?s).*\\p{IsCyrillic}.*") ? List.of(SEARCH_EN, SEARCH_RU) : List.of(SEARCH_EN);
        for (String region : regions)
            for (String term : plain.equals(name) ? List.of(name) : List.of(name, plain)) {
                Found f = findTerm(job, region, term, key, false, wantImage, wantDescription);
                if (f != null) return f;
            }
        // Последняя попытка — по части до двоеточия: консольные издания с подзаголовком продаются в Steam
        // как ПК-издания («Disgaea 2: Dark Hero Days» → «Disgaea 2 PC», «Disgaea 4: A Promise Revisited» →
        // «Disgaea 4 Complete+»). Засчитывается только результат с припиской издания или платформы:
        // иначе консольная «Fallout: Brotherhood of Steel» получила бы картинки первой Fallout
        int colon = name.indexOf(':');
        String prefix = colon > 0 ? name.substring(0, colon).trim() : "";
        if (!norm(prefix).isEmpty()) return findTerm(job, SEARCH_EN, prefix, norm(prefix), true, wantImage, wantDescription);
        return null;
    }

    /** Поиск в Steam по term; editionOnly — засчитывать только издания key (с припиской издания или платформы). */
    private Found findTerm(ImageImport.Job job, String region, String term, String key, boolean editionOnly,
                           boolean wantImage, boolean wantDescription) throws Exception {
        JsonNode res = api(job, steamUrl + "/api/storesearch/?" + region + "&term=" + enc(term));
        if (res == null) return null;
        // Точное совпадение названия; если его нет — то же название без «The» в начале и приписки издания
        // (DX, Remastered, GOTY…): «Banner Saga 3» = «The Banner Saga 3»; старое издание после выхода нового
        // Steam часто снимает с продажи, и поиск находит только новое
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
        // описание и скриншоты — в одной карточке игры, один запрос
        if (wantDescription || wantImage) details = appDetails(job, appId, wantImage);
        if (wantDescription) description = cleanDescription(details.path("short_description").asText(""));
        List<byte[]> imgs = List.of();
        if (wantImage) {
            // скриншоты галереи в полном размере; если их нет — шапка страницы игры
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

    /** Шапка игры: рядом с маленькой картинкой из поиска; если её там нет — адрес из карточки игры; иначе маленькая картинка. */
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
     * Карточка игры Steam (data из appdetails) на русском, со скриншотами — если нужны; пустой узел, если её нет.
     * Регион — США, как у поиска: без него Steam берёт регион по адресу, и для игр, которые в России не продаются
     * (например, Sword Art Online: Fatal Bullet), отдаёт пустую карточку — без скриншотов и описания.
     */
    private JsonNode appDetails(ImageImport.Job job, long appId, boolean screenshots) throws Exception {
        JsonNode d = api(job, steamUrl + "/api/appdetails?filters=basic" + (screenshots ? ",screenshots" : "")
                + "&cc=us&l=russian&appids=" + appId);
        return d == null ? fetch.json().createObjectNode() : d.path(String.valueOf(appId)).path("data");
    }
}
