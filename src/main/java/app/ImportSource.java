package app;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Source for importing pictures and descriptions ("Settings" → "Import"): finds the item by name (and item fields)
 * and returns pictures and description. Requests go through ImportFetch (pauses and retries shared by all sources).
 */
abstract class ImportSource {

    /** What was found: pictures (in order, no duplicates) and description (null — none). */
    record Found(List<byte[]> images, String description) {}

    protected final ImportFetch fetch;

    ImportSource(ImportFetch fetch) {
        this.fetch = fetch;
    }

    /** Source key (ImageImport.STEAM…) — in the form, in the job and for the pause between requests. */
    abstract String key();

    /** Whether the source has box covers: then the first picture can be chosen — a screenshot or the cover. */
    boolean covers() {
        return false;
    }

    /** Pause between requests to the source, ms. */
    abstract long intervalMs(ImageImport.Job job);

    /** Request headers (name, value, …). */
    String[] headers(ImageImport.Job job) {
        return new String[0];
    }

    /** Service response neither 200 nor 404: here the source may stop the import (ImportFetch.Fatal). */
    void checkStatus(ImageImport.Job job, int code) throws ImportFetch.Fatal {}

    /** Whether a picture may be downloaded from this address: only the source's picture addresses (and stubs if the address is replaced). */
    abstract boolean imageUrl(URI u, String url);

    /** The item in the source: pictures (if needed) and description (if needed); null — not found. */
    abstract Found find(ImageImport.Job job, Entry item, boolean wantImage, boolean wantDescription) throws Exception;

    // ---------- shared by sources ----------

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

    /** An https address on host host or its subdomain (suffix starts with a dot: ".steamstatic.com"). */
    static boolean https(URI u, String... hosts) {
        if (!"https".equals(u.getScheme()) || u.getHost() == null) return false;
        for (String h : hosts)
            if (h.startsWith(".") ? u.getHost().endsWith(h) : u.getHost().equals(h)) return true;
        return false;
    }

    /** The address starts with one of the service addresses (stubs in tests). */
    static boolean under(String url, String... bases) {
        for (String b : bases)
            if (url.startsWith(b + "/")) return true;
        return false;
    }

    /**
     * Order of game pictures: "screenshot" mode — screenshots (if none — the cover),
     * otherwise — the cover, then screenshots.
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
