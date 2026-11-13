package app;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * «Запомнить меня на этом компьютере» (галочка при входе): браузер получает cookie со случайным ключом входа на 90 дней,
 * в базе — только его SHA-256 (remember_tokens). Пароль нигде не сохраняется. Без сессии, но с действующим ключом —
 * вход сам (WebConfig). Ключ перестаёт действовать при выходе на этом компьютере (Remember.forget), смене пароля
 * (Repo.setPassword удаляет все ключи пользователя) и удалении пользователя.
 */
@Component
public class Remember {

    static final String COOKIE = "remember";
    static final Duration TTL = Duration.ofDays(90);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Repo repo;
    private final boolean secure;

    public Remember(Repo repo, @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        this.repo = repo;
        this.secure = secure;
    }

    /** Новый ключ входа пользователя для этого браузера. */
    public void issue(long uid, HttpServletResponse res) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        repo.saveRememberToken(hash(token), uid, System.currentTimeMillis() + TTL.toMillis());
        res.addHeader("Set-Cookie", cookie(token, TTL).toString());
    }

    /** Пользователь по ключу входа из cookie (действующему) или null. */
    public Long user(HttpServletRequest req) {
        String token = token(req);
        return token == null ? null : repo.rememberTokenUser(hash(token), System.currentTimeMillis());
    }

    /** Забыть этот компьютер: ключ удаляется из базы, cookie — из браузера. */
    public void forget(HttpServletRequest req, HttpServletResponse res) {
        String token = token(req);
        if (token == null) return;
        repo.deleteRememberToken(hash(token));
        res.addHeader("Set-Cookie", cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration age) {
        // недоступна скриптам и не отправляется с чужих сайтов — как cookie сессии
        return ResponseCookie.from(COOKIE, value).path("/").httpOnly(true).sameSite("Lax").secure(secure).maxAge(age).build();
    }

    private static String token(HttpServletRequest req) {
        if (req.getCookies() != null)
            for (Cookie c : req.getCookies())
                if (COOKIE.equals(c.getName()) && !c.getValue().isEmpty()) return c.getValue();
        return null;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
