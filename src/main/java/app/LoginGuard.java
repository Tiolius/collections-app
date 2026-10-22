package app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Защита от подбора пароля: после нескольких неудачных попыток ключ (адрес + имя, адрес, пользователь)
 * блокируется на время. Хранится в памяти, после перезапуска счётчики обнуляются.
 */
@Component
public class LoginGuard {

    /** Блокировка после стольких неудачных попыток подряд… */
    public static final int MAX_FAILS = 5;
    /** …с одного адреса по любым именам (перебор имён) */
    public static final int MAX_FAILS_PER_IP = 30;
    /** …на столько минут. */
    public static final int LOCK_MINUTES = 15;

    private record State(int fails, long lockedUntil) {}

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** Сколько ещё минут заблокирован хотя бы один из ключей (0 — не заблокирован). */
    public long lockedMinutes(String... keys) {
        long now = System.currentTimeMillis(), until = 0;
        for (String k : keys) {
            State s = states.get(k);
            if (s != null && s.lockedUntil() > now) until = Math.max(until, s.lockedUntil());
        }
        return until == 0 ? 0 : (until - now + 59_999) / 60_000;
    }

    /** Неудачная попытка: limit — сколько попыток до блокировки для этого ключа. */
    public void fail(String key, int limit) {
        long now = System.currentTimeMillis();
        if (states.size() > 10_000) states.values().removeIf(s -> s.lockedUntil() < now && s.fails() < limit);
        states.compute(key, (k, s) -> {
            int fails = (s == null || (s.lockedUntil() != 0 && s.lockedUntil() < now) ? 0 : s.fails()) + 1;
            return new State(fails, fails >= limit ? now + LOCK_MINUTES * 60_000L : 0);
        });
    }

    /** Удачная попытка — счётчик ключа сбрасывается. */
    public void success(String key) {
        states.remove(key);
    }
}
