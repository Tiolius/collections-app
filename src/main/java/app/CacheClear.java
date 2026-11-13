package app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deletes all pictures of the items of a collection ("Settings" → "Cache") in the background: with thousands of pictures it takes a while,
 * the page shows progress (polls /cache/status). One clearing for the whole application at a time;
 * state is in memory (a Job per user, lost after a restart).
 */
@Component
public class CacheClear {

    private static final Logger log = LoggerFactory.getLogger(CacheClear.class);

    /** The user's clearing state. */
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

    /** The user's current or last clearing (or null). */
    public Job job(long uid) {
        return jobs.get(uid);
    }

    /** Whether this collection is being cleared (by anyone) — then no import into it is started. */
    /** Whether anyone is clearing (restoring from a backup is not started meanwhile). */
    public boolean anyActive() {
        return jobs.values().stream().anyMatch(Job::active);
    }

    public boolean active(long collectionId) {
        return jobs.values().stream().anyMatch(j -> j.active() && j.collectionId == collectionId);
    }

    /**
     * Starts clearing; false — the user already has one running. Rights to the collection are checked by the caller.
     * total — how many pictures the items of the collection have now (for progress).
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
