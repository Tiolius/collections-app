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
 * FantLab (книги, API без ключа): название объекта — «Автор - Книга» или просто «Название»;
 * картинки — обложка произведения и обложки изданий, описание — аннотация.
 */
@Component
class FantlabSource extends ImportSource {

    /** FantLab просит не чаще раза в 1,2 с (Crawl-delay в robots.txt). */
    @Value("${fantlab.api-interval-ms:1200}")
    private long intervalMs;
    @Value("${fantlab.api-url:https://api.fantlab.ru}")
    private String apiUrl;
    /** Сайт: пути к обложкам в ответах API — относительно него. */
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

    /** Книга: «Автор - Книга» (как у дисков; автор проверяется), без « - » — просто «Название». null — не найдена. */
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
        // Произведения с тем же названием (русским или оригинальным) и, если указан, тем же автором — от самого популярного
        // (по числу оценок): иначе вместо романа можно получить одноимённый сценарий или цикл
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
                // своя обложка произведения, за ней (или вместо неё) — обложки изданий
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

    /** Название: русское, оригинальное или одно из альтернативных переводов («Основание» = «Академия» Азимова). */
    static boolean sameTitle(JsonNode w, String title) {
        String t = norm(title);
        boolean same = norm(w.path("rusname").asText("")).equals(t) || norm(w.path("name").asText("")).equals(t);
        for (String alt : w.path("altname").asText("").split(";"))
            same |= !alt.isBlank() && norm(alt).equals(t);
        return same;
    }

    /** Обложки изданий (расширенная карточка): издания с картинкой, русские — в первую очередь. */
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
     * Тот же автор: каждое слово из написанного пользователем совпадает началом (до 5 букв) со словом в именах авторов FantLab —
     * «Стругацкие», «Стругацкий» и «Аркадий и Борис Стругацкие» подходят друг к другу.
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

    /** Аннотация без разметки FantLab ([b], [i], [url=…], [spoiler] и т. п. — остаётся текст); пустая — null. */
    static String cleanFantlab(String s) {
        String t = s.replaceAll("\\[/?[a-zA-Z]+(=[^\\]]*)?\\]", "")
                .replace("\r\n", "\n").replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        return Names.limit(t);
    }
}
