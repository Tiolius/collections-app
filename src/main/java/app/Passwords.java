package app;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashes: PBKDF2-HMAC-SHA256 with a random salt (built into Java, no libraries).
 * String format: pbkdf2$iterations$salt$hash (salt and hash in Base64).
 */
public final class Passwords {

    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {
    }

    // ---------- requirements for a new password ----------
    // Following NIST SP 800-63B: length and unpredictability matter, not mandatory
    // "capital + digit + symbol". Any characters are allowed, including spaces — handy for typing a phrase.

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    /** Minimum number of different characters (rejects "aaaaaaaaaaaa", "abababababab"). */
    private static final int MIN_DISTINCT = 5;

    /** Keyboard rows, alphabets and digits: a password cannot be a piece of them (backwards too). */
    private static final String[] SEQUENCES = {
            "01234567890", "abcdefghijklmnopqrstuvwxyz", "абвгдеёжзийклмнопрстуфхцчшщъыьэюя",
            "qwertyuiopasdfghjklzxcvbnm", "qazwsxedcrfvtgbyhnujmikolp", "1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik9ol0p",
            "1q2w3e4r5t6y7u8i9o0p", "q1w2e3r4t5y6u7i8o9p0", "йцукенгшщзхъфывапролджэячсмитьбю"};

    /** Words weak passwords are built from: a password cannot consist only of them plus digits and symbols. */
    private static final java.util.Set<String> WEAK_WORDS = java.util.Set.of(
            "password", "passwords", "passw", "pass", "qwerty", "admin", "administrator", "root", "user", "login",
            "welcome", "letmein", "iloveyou", "monkey", "dragon", "master", "secret", "football", "baseball",
            "sunshine", "princess", "superman", "batman", "trustno", "changeme", "default", "test", "guest",
            "collection", "collections", "collectionsapp", "пароль", "админ", "администратор", "коллекция", "коллекции",
            "привет", "любовь", "qwertyuiop", "asdfghjkl", "zxcvbnm", "abc", "abcd", "abcdef");

    /**
     * Why the password does not fit (text for the user) or null if it fits.
     * name — the user name: the password must not contain it.
     */
    public static String problem(String password, String name) {
        int len = password.codePointCount(0, password.length());
        if (len < MIN_LENGTH) return I18n.t("password.tooShort", MIN_LENGTH);
        if (len > MAX_LENGTH) return I18n.t("password.tooLong", MAX_LENGTH);
        String p = password.toLowerCase(java.util.Locale.ROOT);
        if (p.codePoints().distinct().count() < MIN_DISTINCT)
            return I18n.t("password.fewChars");
        for (int k = 1; k <= p.length() / 2; k++)
            if (p.length() % k == 0 && p.substring(0, k).repeat(p.length() / k).equals(p))
                return I18n.t("password.repeat");
        for (String s : SEQUENCES) {
            String twice = s + s;
            if (twice.contains(p) || new StringBuilder(twice).reverse().toString().contains(p))
                return I18n.t("password.sequence");
        }
        String n = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        if (n.length() >= 3 && p.contains(n)) return I18n.t("password.hasName");
        // Letters of the password without digits and symbols; and a variant with substitutions like P@ssw0rd (digits and symbols at the edges are kept)
        String letters = p.replaceAll("[^\\p{L}]", "");
        String leet = deLeet(p.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "")).replaceAll("[^\\p{L}]", "");
        if (letters.isEmpty() || weak(letters) || weak(leet))
            return I18n.t("password.common");
        return null;
    }

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + b64(salt) + "$" + b64(pbkdf2(password, salt, ITERATIONS));
    }

    /** A weak word, also repeated ("passwordpassword"). */
    private static boolean weak(String letters) {
        return WEAK_WORDS.contains(letters) || WEAK_WORDS.contains(letters.replaceAll("^(.+?)\\1+$", "$1"));
    }

    /** Typical substitutions of letters with digits and symbols: @ → a, 0 → o, 1 → l, 3 → e, $ → s etc. */
    private static String deLeet(String p) {
        StringBuilder b = new StringBuilder(p.length());
        for (char ch : p.toCharArray())
            b.append(switch (ch) {
                case '@', '4' -> 'a';
                case '0' -> 'o';
                case '1', '!', '|' -> 'l';
                case '3' -> 'e';
                case '$', '5' -> 's';
                case '7', '+' -> 't';
                case '8' -> 'b';
                default -> ch;
            });
        return b.toString();
    }

    public static boolean matches(String password, String stored) {
        if (stored == null) return false;
        // A very long password is not checked: computing its hash would be extra load on the server
        if (password != null && password.length() > MAX_LENGTH * 4) return false;
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

    /** Prints the hash of a password (needed for SQL scripts): java -cp ... app.Passwords "password" */
    public static void main(String[] args) {
        System.out.println(hash(args.length > 0 ? args[0] : ""));
    }
}
