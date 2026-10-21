package app;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Хеши паролей: PBKDF2-HMAC-SHA256 со случайной солью (встроено в Java, без библиотек).
 * Формат строки: pbkdf2$итерации$соль$хеш (соль и хеш в Base64).
 */
public final class Passwords {

    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + b64(salt) + "$" + b64(pbkdf2(password, salt, ITERATIONS));
    }

    public static boolean matches(String password, String stored) {
        if (stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
        byte[] salt = Base64.getDecoder().decode(p[2]);
        byte[] expected = Base64.getDecoder().decode(p[3]);
        return MessageDigest.isEqual(expected, pbkdf2(password, salt, Integer.parseInt(p[1])));
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec((password == null ? "" : password).toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    /** Печатает хеш для пароля (нужно для SQL-скриптов): java -cp ... app.Passwords "пароль" */
    public static void main(String[] args) {
        System.out.println(hash(args.length > 0 ? args[0] : ""));
    }
}
