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

    // ---------- требования к новому паролю ----------
    // По рекомендациям NIST SP 800-63B: важна длина и непредсказуемость, а не обязательные
    // «заглавная + цифра + символ». Разрешены любые символы, включая пробелы, — удобно вводить фразу.

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    /** Минимум разных символов (отсекает «aaaaaaaaaaaa», «abababababab»). */
    private static final int MIN_DISTINCT = 5;

    /** Ряды клавиатуры, алфавиты и цифры: пароль не может быть их куском (в т.ч. задом наперёд). */
    private static final String[] SEQUENCES = {
            "01234567890", "abcdefghijklmnopqrstuvwxyz", "абвгдеёжзийклмнопрстуфхцчшщъыьэюя",
            "qwertyuiopasdfghjklzxcvbnm", "qazwsxedcrfvtgbyhnujmikolp", "1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik9ol0p",
            "1q2w3e4r5t6y7u8i9o0p", "q1w2e3r4t5y6u7i8o9p0", "йцукенгшщзхъфывапролджэячсмитьбю"};

    /** Слова, на которых строят слабые пароли: пароль не может состоять только из них плюс цифры и знаки. */
    private static final java.util.Set<String> WEAK_WORDS = java.util.Set.of(
            "password", "passwords", "passw", "pass", "qwerty", "admin", "administrator", "root", "user", "login",
            "welcome", "letmein", "iloveyou", "monkey", "dragon", "master", "secret", "football", "baseball",
            "sunshine", "princess", "superman", "batman", "trustno", "changeme", "default", "test", "guest",
            "collection", "collections", "collectionsapp", "пароль", "админ", "администратор", "коллекция", "коллекции",
            "привет", "любовь", "qwertyuiop", "asdfghjkl", "zxcvbnm", "abc", "abcd", "abcdef");

    /**
     * Почему пароль не подходит (текст для пользователя) или null, если подходит.
     * name — имя пользователя: пароль не должен его содержать.
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
        // Буквы пароля без цифр и знаков; и вариант с заменами вида P@ssw0rd (цифры и знаки по краям не трогаем)
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

    /** Слабое слово, в т.ч. повторённое («passwordpassword»). */
    private static boolean weak(String letters) {
        return WEAK_WORDS.contains(letters) || WEAK_WORDS.contains(letters.replaceAll("^(.+?)\\1+$", "$1"));
    }

    /** Типичные замены букв цифрами и знаками: @ → a, 0 → o, 1 → l, 3 → e, $ → s и т. п. */
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
        // Очень длинный пароль не проверяем: расчёт хеша для него был бы лишней нагрузкой на сервер
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

    /** Печатает хеш для пароля (нужно для SQL-скриптов): java -cp ... app.Passwords "пароль" */
    public static void main(String[] args) {
        System.out.println(hash(args.length > 0 ? args[0] : ""));
    }
}
