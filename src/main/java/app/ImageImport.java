package app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Component;

/**
 * Импорт картинок и описаний объектов («Настройки» → «Импорт») из сервисов игр, музыки и книг.
 * Каждый сервис — свой класс (ImportSource: SteamSource, GogSource, NintendoSource, LaunchboxSource,
 * DiscogsSource, FantlabSource), запросы с паузами и повторами — ImportFetch.
 * Импорт идёт в фоне, по одному на всё приложение (лимиты сервисов — на адрес, а не на пользователя).
 */
@Component
public class ImageImport {

    public static final String STEAM = "steam", GOG = "gog", DISCOGS = "discogs", FANTLAB = "fantlab",
            NINTENDO = "nintendo", LAUNCHBOX = "launchbox";
    /** Источники: ключ → название для страницы. */
    public static final Map<String, String> SOURCES = new LinkedHashMap<>();
    static {
        SOURCES.put(STEAM, "Steam");
        SOURCES.put(GOG, "GOG");
        SOURCES.put(NINTENDO, "Nintendo eShop");
        SOURCES.put(LAUNCHBOX, "LaunchBox");
        SOURCES.put(DISCOGS, "Discogs");
        SOURCES.put(FANTLAB, "FantLab");
    }

    /** Столько ошибок сети подряд — сервис недоступен, импорт останавливается. */
    static final int MAX_ERRORS_IN_ROW = 10;

    /** Состояние импорта пользователя (читается страницей через /import/status). */
    public static class Job {
        public final long collectionId;
        public final String collectionName;
        public final String source;
        public final boolean images, descriptions;
        /** GOG, Nintendo eShop, LaunchBox: первая картинка — скриншот, а не обложка коробки. */
        public final boolean screenshot;
        /** Сколько картинок загружать на объект (1…ImageFiles.MAX_IMAGES), если у источника они есть. */
        public final int maxImages;
        public volatile String state = "queued"; // queued | running | done | cancelled | failed
        public volatile int total, processed, found, loaded, described, notFound, errors; // found — найдено в источнике; loaded — картинок, described — описаний
        public volatile String current = "";
        public volatile String message = "";      // пауза из-за ограничений сервиса, причина остановки
        volatile boolean cancel;
        /** Личный токен Discogs пользователя (пусто — без токена); на страницу не попадает. */
        final String discogsToken;
        /** Язык сообщений (message) — язык интерфейса при запуске импорта. */
        final java.util.Locale locale = org.springframework.context.i18n.LocaleContextHolder.getLocale();

        Job(long collectionId, String collectionName, String source, boolean images, boolean descriptions,
            boolean screenshot, int maxImages, String discogsToken) {
            this.screenshot = screenshot;
            this.maxImages = Math.max(1, Math.min(ImageFiles.MAX_IMAGES, maxImages));
            this.discogsToken = discogsToken == null ? "" : discogsToken.trim();
            this.collectionId = collectionId;
            this.collectionName = collectionName;
            this.source = source;
            this.images = images;
            this.descriptions = descriptions;
        }

        public boolean active() {
            return state.equals("queued") || state.equals("running");
        }

        public String sourceName() {
            return SOURCES.get(source);
        }
    }

    private final Repo repo;
    /** Источники по ключу. */
    private final Map<String, ImportSource> sources = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "image-import");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();

    public ImageImport(Repo repo, List<ImportSource> sources) {
        this.repo = repo;
        for (ImportSource s : sources) this.sources.put(s.key(), s);
    }

    /** Текущий или последний импорт пользователя (или null). */
    public Job job(long uid) {
        return jobs.get(uid);
    }

    /** Идёт ли импорт у кого-нибудь (восстановление из копии в это время не запускается). */
    public boolean anyActive() {
        return jobs.values().stream().anyMatch(Job::active);
    }

    /**
     * Запускает импорт; false — у пользователя уже идёт импорт. Права на коллекцию и источник проверяет вызывающий код.
     * onlyMissing — не трогать уже заполненное: картинку загружать только тем, у кого её нет, описание — так же.
     * discogsToken — личный токен Discogs пользователя (null — нет): без него Discogs отдаёт результаты и Notes,
     * но без обложек (и только 25 запросов в минуту).
     */
    public synchronized boolean start(long uid, Entry collection, boolean onlyMissing, String source,
                                      boolean images, boolean descriptions, boolean screenshot, int maxImages,
                                      String discogsToken) {
        Job old = jobs.get(uid);
        if (old != null && old.active()) return false;
        boolean hasToken = discogsToken != null && !discogsToken.isBlank();
        // Без токена обложек у Discogs нет — только описания
        boolean img = images && (!source.equals(DISCOGS) || hasToken);
        Job job = new Job(collection.id(), collection.name(), source, img, descriptions,
                screenshot && sources.get(source).covers(), maxImages, discogsToken);
        List<Entry> items = repo.items(collection.id(), "name", false, Map.of()).stream()
                .filter(e -> needImage(job, e, onlyMissing) || needDescription(job, e, onlyMissing)).toList();
        job.total = items.size();
        jobs.put(uid, job);
        worker.submit(() -> run(job, items, onlyMissing));
        return true;
    }

    private static boolean needImage(Job job, Entry e, boolean onlyMissing) {
        return job.images && (!onlyMissing || !e.hasImage());
    }

    private static boolean needDescription(Job job, Entry e, boolean onlyMissing) {
        return job.descriptions && (!onlyMissing || e.description() == null);
    }

    public void cancel(long uid) {
        Job job = jobs.get(uid);
        if (job != null) job.cancel = true;
    }

    private void run(Job job, List<Entry> items, boolean onlyMissing) {
        job.state = "running";
        int errorsInRow = 0;
        try {
            for (Entry item : items) {
                if (job.cancel) {
                    job.state = "cancelled";
                    return;
                }
                job.current = item.name();
                try {
                    boolean img = needImage(job, item, onlyMissing), descr = needDescription(job, item, onlyMissing);
                    ImportSource.Found f = sources.get(job.source).find(job, item, img, descr);
                    if (f == null) {
                        job.notFound++;
                    } else {
                        job.found++;
                        if (img && !f.images().isEmpty()) {
                            repo.setItemImages(item.id(), f.images());
                            job.loaded++;
                        }
                        if (descr && f.description() != null) {
                            repo.setItemDescription(item.id(), f.description());
                            job.described++;
                        }
                    }
                    errorsInRow = 0;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    job.state = "cancelled";
                    return;
                } catch (ImportFetch.Fatal e) {
                    job.message = e.getMessage();
                    job.state = "failed";
                    return;
                } catch (Exception e) {
                    job.errors++;
                    if (++errorsInRow >= MAX_ERRORS_IN_ROW) {
                        job.processed++;
                        job.message = I18n.t(job.locale, "import.unavailable", job.sourceName());
                        job.state = "failed";
                        return;
                    }
                }
                job.processed++;
            }
            job.state = "done";
        } finally {
            job.current = "";
            if (!job.state.equals("failed")) job.message = "";
        }
    }
}
