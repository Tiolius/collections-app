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
 * "Remember me" (a tick at sign-in): the browser gets a cookie with a random sign-in key for 90 days,
 * the database keeps only its SHA-256 (remember_tokens). The password is not saved anywhere. No session but a valid key —
 * signed in automatically (WebConfig). The key stops working on sign-out on this computer (Remember.forget), a password
 * change (Repo.setPassword deletes all of the user's keys) and when the user is deleted.
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

    /** A new sign-in key of the user for this browser. */
    public void issue(long uid, HttpServletResponse res) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        repo.saveRememberToken(hash(token), uid, System.currentTimeMillis() + TTL.toMillis());
        res.addHeader("Set-Cookie", cookie(token, TTL).toString());
    }

    /** User by the sign-in key from the cookie (a valid one) or null. */
    public Long user(HttpServletRequest req) {
        String token = token(req);
        return token == null ? null : repo.rememberTokenUser(hash(token), System.currentTimeMillis());
    }

    /** Forget this computer: the key is deleted from the database, the cookie from the browser. */
    public void forget(HttpServletRequest req, HttpServletResponse res) {
        String token = token(req);
        if (token == null) return;
        repo.deleteRememberToken(hash(token));
        res.addHeader("Set-Cookie", cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration age) {
        // not available to scripts and not sent from other sites — like the session cookie
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
