package app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/** Требования к паролям и файл Excel без библиотек. */
class PasswordsXlsxTest {

    @BeforeAll
    static void messages() {
        ResourceBundleMessageSource m = new ResourceBundleMessageSource();
        m.setBasename("messages");
        m.setDefaultEncoding("UTF-8");
        m.setFallbackToSystemLocale(false);
        new I18n(m);
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
    }

    @Test
    void weakPasswordsAreRejected() {
        assertThat(Passwords.problem("short", "u")).isNotNull();                    // короче 12
        assertThat(Passwords.problem("aaaaaaaaaaaaaa", "u")).isNotNull();           // мало разных символов
        assertThat(Passwords.problem("abcabcabcabcabc", "u")).isNotNull();          // повтор
        assertThat(Passwords.problem("qwertyuiopasd", "u")).isNotNull();            // ряд клавиш
        assertThat(Passwords.problem("1234567890123", "u")).isNotNull();            // ряд цифр
        assertThat(Passwords.problem("myname-is-stanislav", "stanislav")).isNotNull(); // имя пользователя
        assertThat(Passwords.problem("P@ssw0rd2024!", "u")).isNotNull();            // распространённое слово с заменами
    }

    @Test
    void goodPasswordsPass() {
        assertThat(Passwords.problem("Kwq7-zmlpt-Rvx9", "tester")).isNull();
        assertThat(Passwords.problem("синий кит плывёт домой", "u")).isNull();
    }

    @Test
    void hashMatchesOnlyItsPassword() {
        String h = Passwords.hash("Kwq7-zmlpt-Rvx9");
        assertThat(Passwords.matches("Kwq7-zmlpt-Rvx9", h)).isTrue();
        assertThat(Passwords.matches("Kwq7-zmlpt-Rvx8", h)).isFalse();
        assertThat(Passwords.hash("Kwq7-zmlpt-Rvx9")).isNotEqualTo(h); // своя соль
    }

    @Test
    void xlsxRoundTrip() throws Exception {
        List<Object> row = Arrays.asList("Gothic II", 1499, null, "Есть");
        Xlsx.Sheet sheet = new Xlsx.Sheet("Игры: диски/PC", List.of("Название", "Цена (₽)", "Теги", "Наличие"),
                List.of(row, Arrays.asList("<&> \"кавычки\"", 2.5, "a, b", null)), List.of(30, 10, 10, 10));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Xlsx.write(out, List.of(sheet, new Xlsx.Sheet("игры: диски/pc", List.of("A"), List.of(), List.of(10))));
        List<Xlsx.Sheet> read = Xlsx.read(new ByteArrayInputStream(out.toByteArray()));
        assertThat(read).hasSize(2);
        // недопустимые знаки — пробелы, одинаковые без учёта регистра — с номером
        assertThat(read.get(0).name()).isEqualTo("Игры диски PC");
        assertThat(read.get(1).name()).isNotEqualToIgnoringCase(read.get(0).name());
        List<List<Object>> rows = read.get(0).rows();
        assertThat(rows.get(0)).containsExactly("Название", "Цена (₽)", "Теги", "Наличие");
        assertThat(rows.get(1).get(0)).isEqualTo("Gothic II");
        assertThat(rows.get(1).get(1)).isEqualTo(1499.0);
        assertThat(rows.get(1).get(3)).isEqualTo("Есть");
        assertThat(rows.get(2).get(0)).isEqualTo("<&> \"кавычки\"");
        assertThat(rows.get(2).get(1)).isEqualTo(2.5);
    }

    @Test
    void notAnXlsxIsRejected() {
        assertThatThrownBy(() -> Xlsx.read(new ByteArrayInputStream("hello".getBytes()))).isInstanceOf(java.io.IOException.class);
        Set<String> used = new HashSet<>();
        assertThat(Xlsx.sheetName("x".repeat(40), used)).hasSize(31);
    }
}
