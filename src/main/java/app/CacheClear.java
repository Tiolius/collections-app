package app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Удаление всех картинок объектов коллекции («Настройки» → «Кэш») в фоне: при тысячах картинок это долго,
 * страница показывает прогресс (опрашивает /cache/status). Одна очистка на всё приложение за раз;
 * состояние — в памяти (Job на пользователя, после перезапуска теряется).
 */
@Component
public class CacheClear {

    private static final Logger log = LoggerFactory.getLogger(CacheClear.class);

    /** Состояние очистки пользователя. */
    public static class Job {
        public final long collectionId;
        public final String collectionName;
        public volatile String state = "queued"; // queued | running | done | failed
        public volatile int total, done;

        Job(long collectionId, String collectionName, int total) {
            this.collectionId = collectionId;
            this.collectionName = collectionName;
            this.total = total;
        }

        public boolean active() {
            return state.equals("queued") || state.equals("running");
        }
    }

    private final ImageFiles files;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cache-clear");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();

    public CacheClear(ImageFiles files) {
        this.files = files;
    }

    /** Текущая или последняя очистка пользователя (или null). */
    public Job job(long uid) {
        return jobs.get(uid);
    }

    /** Есть ли идущая очистка этой коллекции (у кого угодно) — тогда импорт в неё не запускать. */
    public boolean active(long collectionId) {
        return jobs.values().stream().anyMatch(j -> j.active() && j.collectionId == collectionId);
    }

    /**
     * Запускает очистку; false — у пользователя уже идёт очистка. Права на коллекцию проверяет вызывающий код.
     * total — сколько картинок у объектов коллекции сейчас (для прогресса).
     */
    public synchronized boolean start(long uid, Entry collection, int total) {
        Job old = jobs.get(uid);
        if (old != null && old.active()) return false;
        Job job = new Job(collection.id(), collection.name(), total);
        jobs.put(uid, job);
        worker.submit(() -> {
            job.state = "running";
            try {
                job.done = files.clearCollectionItems(job.collectionId, n -> job.done = n);
                job.total = Math.max(job.total, job.done);
                job.state = "done";
            } catch (RuntimeException e) {
                log.warn("Очистка картинок коллекции {} не удалась: {}", job.collectionId, e.toString());
                job.state = "failed";
            }
        });
        return true;
    }
}
