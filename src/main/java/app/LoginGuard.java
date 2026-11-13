package app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Protection against password guessing: after several failed attempts a key (address + name, address, user)
 * is locked for a while. Kept in memory; counters reset after a restart.
 */
@Component
public class LoginGuard {

    /** Lock after this many failed attempts in a row… */
    public static final int MAX_FAILS = 5;
    /** …from one address with any names (guessing names) */
    public static final int MAX_FAILS_PER_IP = 30;
    /** …for this many minutes. */
    public static final int LOCK_MINUTES = 15;

    private record State(int fails, long lockedUntil) {}

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** How many more minutes at least one of the keys is locked (0 — not locked). */
    public long lockedMinutes(String... keys) {
        long now = System.currentTimeMillis(), until = 0;
        for (String k : keys) {
            State s = states.get(k);
            if (s != null && s.lockedUntil() > now) until = Math.max(until, s.lockedUntil());
        }
        return until == 0 ? 0 : (until - now + 59_999) / 60_000;
    }

    /** A failed attempt: limit — how many attempts before this key is locked. */
    public void fail(String key, int limit) {
        long now = System.currentTimeMillis();
        if (states.size() > 10_000) states.values().removeIf(s -> s.lockedUntil() < now && s.fails() < limit);
        states.compute(key, (k, s) -> {
            int fails = (s == null || (s.lockedUntil() != 0 && s.lockedUntil() < now) ? 0 : s.fails()) + 1;
            return new State(fails, fails >= limit ? now + LOCK_MINUTES * 60_000L : 0);
        });
    }

    /** A successful attempt — the key's counter is reset. */
    public void success(String key) {
        states.remove(key);
    }
}
