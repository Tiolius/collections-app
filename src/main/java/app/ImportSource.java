package app;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Источник импорта картинок и описаний («Настройки» → «Импорт»): ищет объект по названию (и полям объекта)
 * и возвращает картинки и описание. Запросы — через ImportFetch (паузы и повторы общие для всех источников).
 */
abstract class ImportSource {

    /** Найденное: картинки (по порядку, без повторов) и описание (null — нет). */
    record Found(List<byte[]> images, String description) {}

    protected final ImportFetch fetch;

    ImportSource(ImportFetch fetch) {
        this.fetch = fetch;
    }

    /** Ключ источника (ImageImport.STEAM…) — в форме, в задании и для паузы между запросами. */
    abstract String key();

    /** Есть ли у источника обложки коробок: тогда есть выбор первой картинки — скриншот или обложка. */
    boolean covers() {
        return false;
    }

    /** Пауза между запросами к источнику, мс. */
    abstract long intervalMs(ImageImport.Job job);

    /** Заголовки запросов (имя, значение, …). */
    String[] headers(ImageImport.Job job) {
        return new String[0];
    }

    /** Ответ сервиса не 200 и не 404: здесь источник может остановить импорт (ImportFetch.Fatal). */
    void checkStatus(ImageImport.Job job, int code) throws ImportFetch.Fatal {}

    /** Можно ли скачивать картинку с этого адреса: только адреса картинок источника (и заглушки, если адрес подменён). */
    abstract boolean imageUrl(URI u, String url);

    /** Объект в источнике: картинки (если нужны) и описание (если нужно); null — не найден. */
    abstract Found find(ImageImport.Job job, Entry item, boolean wantImage, boolean wantDescription) throws Exception;

    // ---------- общее для источников ----------

    protected JsonNode api(ImageImport.Job job, String url) throws Exception {
        return fetch.api(this, job, url);
    }

    protected String page(ImageImport.Job job, String url) throws Exception {
        return fetch.page(this, job, url);
    }

    protected byte[] download(String url) throws Exception {
        return fetch.download(this, url);
    }

    protected List<byte[]> downloadAll(List<String> urls, int max) throws Exception {
        return fetch.downloadAll(this, urls, max);
    }

    /** https-адрес на хосте host или его поддомене (suffix начинается с точки: «.steamstatic.com»). */
    static boolean https(URI u, String... hosts) {
        if (!"https".equals(u.getScheme()) || u.getHost() == null) return false;
        for (String h : hosts)
            if (h.startsWith(".") ? u.getHost().endsWith(h) : u.getHost().equals(h)) return true;
        return false;
    }

    /** Адрес начинается с одного из адресов сервиса (заглушки при проверке). */
    static boolean under(String url, String... bases) {
        for (String b : bases)
            if (url.startsWith(b + "/")) return true;
        return false;
    }

    /**
     * Порядок картинок игры: режим «скриншот» — скриншоты (если их нет — обложка),
     * иначе — обложка, за ней скриншоты.
     */
    static List<String> coverAndShots(ImageImport.Job job, String cover, List<String> shots) {
        List<String> urls = new ArrayList<>();
        if (job.screenshot && !shots.isEmpty()) {
            urls.addAll(shots);
        } else {
            if (cover != null && !cover.isEmpty()) urls.add(cover);
            if (!job.screenshot) urls.addAll(shots);
        }
        return urls;
    }
}
