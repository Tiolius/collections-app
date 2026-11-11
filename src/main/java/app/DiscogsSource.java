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
 * Discogs (музыка): название объекта — «Группа - Альбом», ищется альбом с тем же названием у той же группы.
 * Описание — Notes главной версии альбома (иначе её основного издания); картинки — самого популярного издания
 * этого же альбома на носителе объекта (CD, винил, кассета). Токен Discogs — у каждого пользователя свой
 * (вкладка «Импорт»): без него Discogs отдаёт результаты и Notes, но без обложек, и только 25 запросов в минуту.
 */
@Component
class DiscogsSource extends ImportSource {

    /** С токеном — до 60 запросов в минуту; без токена — 25 (не чаще раза в 2,5 с). */
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
     * Альбом «Группа - Альбом» (без разделителя — не ищем): картинки (если нужны) и Notes (если нужно описание); null — не найден.
     * Сначала главная версия альбома (master), если её нет — отдельные издания (release).
     * Картинки — издания на носителе объекта (поле «Формат»: винил, кассета; иначе CD): у главной версии картинки
     * её основного издания, а это часто винил; нет такого издания — картинки главной версии.
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
            // Подходящие результаты (название «Группа - Альбом» совпадает) — от самого популярного:
            // у одноимённых фильма, сборника или бутлега владельцев на Discogs гораздо меньше, чем у самого альбома
            List<JsonNode> matches = new ArrayList<>();
            for (JsonNode r : res.path("results"))
                if (sameAlbum(r, artist, album)) matches.add(r);
            matches.sort(Comparator.comparingLong((JsonNode r) -> r.path("community").path("have").asLong()).reversed());
            for (JsonNode r : matches) {
                Map<String, JsonNode> cache = new HashMap<>(); // карточки альбома: для Notes и картинок — один запрос
                long id = r.path("id").asLong();
                List<byte[]> imgs = List.of();
                if (wantImage) {
                    // Издание на нужном носителе того же альбома; нет — картинки найденного результата
                    long masterId = type.equals("master") ? id : r.path("master_id").asLong();
                    JsonNode media = mediaRelease(job, artist, album, format, masterId);
                    JsonNode src = media != null ? media : r;
                    String srcType = media != null ? "release" : type;
                    List<String> urls = new ArrayList<>();
                    // несколько картинок — из карточки альбома (обложка, оборот, вкладыш…), иначе — обложка из поиска
                    if (job.maxImages > 1) urls.addAll(images(job, srcType, src.path("id").asLong(), cache));
                    String cover = src.path("cover_image").asText("");
                    // обложка из поиска — если из карточки ничего нет (иначе была бы та же картинка в другом размере);
                    // нет обложки — у Discogs пустая заглушка spacer.gif
                    if (urls.isEmpty() && realCover(cover)) urls.add(cover);
                    imgs = downloadAll(urls, job.maxImages);
                }
                String notes = wantDescription ? notes(job, type, id, cache) : null;
                if (imgs.isEmpty() && notes == null) continue; // ни обложки, ни примечаний — следующий результат
                return new Found(imgs, notes);
            }
        }
        return null;
    }

    /** Результат поиска — тот же альбом: название «Группа - Альбом» совпадает. */
    private static boolean sameAlbum(JsonNode r, String artist, String album) {
        String[] t = artistAlbum(r.path("title").asText(""));
        return t != null && norm(t[1]).equals(norm(album)) && sameArtist(t[0], artist);
    }

    private static boolean realCover(String url) {
        return !url.isEmpty() && !url.contains("spacer.gif");
    }

    /** Носитель для поиска изданий по полю «Формат» объекта: винил, кассета; всё остальное (CD, пусто) — CD. */
    static String format(String itemFormat) {
        String f = itemFormat == null ? "" : itemFormat.toLowerCase(Locale.ROOT);
        if (f.matches(".*(vinyl|винил|пластинк|\\blp\\b).*")) return "Vinyl";
        if (f.matches(".*(cass|кассет|\\bmc\\b).*")) return "Cassette";
        return "CD";
    }

    /**
     * Самое популярное издание (release) альбома на носителе format — с обложкой; null — такого нет.
     * Поиск — сразу по числу владельцев (sort=have): по умолчанию Discogs сортирует по релевантности,
     * и самые популярные издания могут не попасть в первые 50.
     * Тот же альбом — тот же master (masterId > 0), иначе — то же название «Группа - Альбом».
     */
    private JsonNode mediaRelease(ImageImport.Job job, String artist, String album, String format, long masterId) throws Exception {
        JsonNode res = api(job, apiUrl + "/database/search?type=release&format=" + enc(format)
                + "&sort=have&sort_order=desc&per_page=50&artist=" + enc(artist) + "&release_title=" + enc(album));
        return res == null ? null : bestMedia(res.path("results"), artist, album, format, masterId);
    }

    /** Из результатов поиска изданий — самое популярное издание того же альбома на носителе format с обложкой. */
    static JsonNode bestMedia(JsonNode results, String artist, String album, String format, long masterId) {
        JsonNode best = null;
        for (JsonNode r : results) {
            if (masterId > 0 ? r.path("master_id").asLong() != masterId : !sameAlbum(r, artist, album)) continue;
            // носитель — в списке форматов издания (на случай, если Discogs не учёл фильтр)
            boolean hasFormat = false;
            for (JsonNode f : r.path("format")) hasFormat |= f.asText("").equalsIgnoreCase(format);
            if (!hasFormat || !realCover(r.path("cover_image").asText(""))) continue;
            if (best == null || r.path("community").path("have").asLong() > best.path("community").path("have").asLong()) best = r;
        }
        return best;
    }

    /** Запрос с запоминанием ответа в пределах одного альбома. */
    private JsonNode cached(ImageImport.Job job, String url, Map<String, JsonNode> cache) throws Exception {
        if (cache.containsKey(url)) return cache.get(url);
        JsonNode n = api(job, url);
        cache.put(url, n);
        return n;
    }

    /** Адреса картинок из карточки (master или release), главная — первой; без токена Discogs их не отдаёт. */
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

    /** Notes альбома: у главной версии (master), если там пусто — у её основного издания; у издания (release) — его Notes. */
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
     * Notes без разметки Discogs: [url=…]текст[/url] → текст, [a=Имя]/[l=Имя] → Имя,
     * ссылки на записи по номеру ([a123], [m=123]) и теги [b] [i] [u] убираются, пробелы перед знаками препинания — тоже; пустое — null.
     */
    static String cleanNotes(String s) {
        String t = s.replaceAll("(?is)\\[url=[^\\]]*\\](.*?)\\[/url\\]", "$1")
                .replaceAll("\\[[alrm]=?\\d+\\]", "")          // ссылки по номеру: [a45467], [m=11329]
                .replaceAll("\\[[alrm]=([^\\]]+)\\]", "$1")    // по имени: [a=Имя], [l=Студия]
                .replaceAll("(?i)\\[/?[biu]\\]", "")
                .replaceAll("[ \\t]+([,.;:!?)])", "$1").replaceAll("[ \\t]{2,}", " ")
                .replace("\r\n", "\n").replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        return Names.limit(t);
    }
}
