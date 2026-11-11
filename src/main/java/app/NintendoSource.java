package app;

import static app.Names.cleanDescription;
import static app.Names.enc;
import static app.Names.looseKey;
import static app.Names.norm;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Nintendo eShop (европейский каталог, без ключа): игры Switch, Wii U, 3DS и переиздания старых игр;
 * то же название и та же платформа (поле «Платформа» объекта, если указано). Картинки — обложка коробки
 * и скриншоты со страницы игры, описание — краткое описание из каталога.
 */
@Component
class NintendoSource extends ImportSource {

    /** Nintendo ограничений не публикует; раз в секунду — чтобы не нагружать. */
    @Value("${nintendo.api-interval-ms:1000}")
    private long intervalMs;
    /** Поиск европейского каталога и сайт (страницы игр со скриншотами). */
    @Value("${nintendo.search-url:https://searching.nintendo-europe.com}")
    private String searchUrl;
    @Value("${nintendo.site-url:https://www.nintendo.com}")
    private String site;

    /** Скриншот на странице игры (полный размер и уменьшенная копия «_TM_Standard»). */
    private static final Pattern SHOT = Pattern.compile(
            "(?:https://www\\.nintendo\\.com)?(/eu/media/images/06_screenshots/[^\"'\\s<>]+?\\.(?:jpe?g|png|gif|bmp))");

    NintendoSource(ImportFetch fetch) {
        super(fetch);
    }

    @Override
    String key() {
        return ImageImport.NINTENDO;
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
        return https(u, "www.nintendo.com") || under(url, site);
    }

    /** Игра из каталога: то же название и та же платформа; null — не найдена. Русского каталога нет — ищется по-английски. */
    @Override
    Found find(ImageImport.Job job, Entry item, boolean wantImage, boolean wantDescription) throws Exception {
        String key = norm(item.name());
        if (key.isEmpty()) return null;
        // без знаков препинания: двоеточие и т. п. — часть языка запросов поиска
        JsonNode res = api(job, searchUrl + "/en/select?fq=type:GAME&rows=30&wt=json&q=" + enc(Names.plain(item.name())));
        if (res == null) return null;
        Set<String> wanted = Platforms.codes(item.platform());
        JsonNode exact = null, loose = null;
        for (JsonNode d : res.path("response").path("docs")) {
            List<String> systems = new ArrayList<>();
            for (JsonNode s : d.path("system_names_txt")) systems.add(s.asText(""));
            if (!Platforms.same(wanted, systems)) continue;
            String n = norm(d.path("title").asText(""));
            if (exact == null && key.equals(n)) exact = d;
            if (loose == null && looseKey(key).equals(looseKey(n))) loose = d;
        }
        JsonNode d = exact != null ? exact : loose;
        if (d == null) return null;
        List<byte[]> imgs = List.of();
        if (wantImage) {
            String cover = d.path("image_url").asText("");
            if (cover.isEmpty()) cover = d.path("image_url_sq_s").asText("");
            // страница игры (со скриншотами) — только если скриншоты нужны
            List<String> shots = job.screenshot || job.maxImages > 1 ? shots(job, d.path("url").asText("")) : List.of();
            imgs = downloadAll(coverAndShots(job, cover, shots), job.maxImages);
        }
        String descr = null;
        if (wantDescription) {
            descr = cleanDescription(d.path("product_catalog_description_s").asText(""));
            if (descr == null) descr = cleanDescription(d.path("excerpt").asText(""));
        }
        return new Found(imgs, descr);
    }

    /**
     * Адреса скриншотов со страницы игры (path — адрес страницы из каталога, от корня сайта) по порядку.
     * У старых игр полные скриншоты — в BMP (такие не принимаются), вместо них — копия «_TM_Standard» в PNG/JPEG.
     */
    private List<String> shots(ImageImport.Job job, String path) throws Exception {
        if (!path.startsWith("/")) return List.of();
        String html = page(job, site + path);
        if (html == null) return List.of();
        Set<String> all = new LinkedHashSet<>();
        Matcher m = SHOT.matcher(html);
        while (m.find()) all.add(m.group(1));
        List<String> res = new ArrayList<>();
        for (String p : all) {
            if (p.contains("_TM_")) continue;
            String shot = p;
            if (p.toLowerCase(Locale.ROOT).endsWith(".bmp")) {
                String small = p.substring(0, p.lastIndexOf('.')) + "_TM_Standard.";
                shot = all.stream().filter(s -> s.startsWith(small) && !s.toLowerCase(Locale.ROOT).endsWith(".bmp")).findFirst().orElse(null);
            }
            if (shot != null) res.add(site + shot);
        }
        // по номеру в имени файла (…_01.jpg, …_02_enGB.png): на некоторых страницах они идут в обратном порядке
        res.sort(Comparator.comparingInt(NintendoSource::shotNumber));
        return res;
    }

    /** Номер скриншота из имени файла («NSwitch_Hades_03_enGB.png» → 3); нет номера — в конец. */
    static int shotNumber(String url) {
        Matcher m = Pattern.compile("_(\\d{1,3})(?:_[A-Za-z]{2,5})?(?:_TM_Standard)?\\.\\w+$").matcher(url);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }
}
