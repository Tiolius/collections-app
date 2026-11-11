package app;

import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Основа тестов с базой: приложение без веб-сервера на временной папке данных (одна на все такие тесты — контекст
 * Spring общий); у каждого теста свой пользователь (newUser), язык — русский.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class DbTest {

    static final String DATA;
    static {
        try {
            DATA = Files.createTempDirectory("collections-db").toString();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DATA_DIR", () -> DATA);
        r.add("updates.enabled", () -> "false");
    }

    private static final AtomicInteger USERS = new AtomicInteger();

    @Autowired Repo repo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void russian() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
    }

    /** Новый пользователь (с начальными типами коллекций). */
    long newUser() {
        return repo.createUser("user" + USERS.incrementAndGet(), "Kwq7-zmlpt-Rvx9", "user");
    }

    /** Новая коллекция пользователя с типом kind. */
    long newCollection(long uid, String name, String kind) {
        long cid = repo.createCollection(uid);
        repo.saveCollection(uid, cid, name, kind, null, false);
        return cid;
    }

    /** Новый объект с названием и полями из формы (null — поля нет). */
    long newItem(long uid, long cid, Repo.ItemForm form) {
        long id = repo.createItem(cid);
        repo.saveItem(uid, id, repo.collection(uid, cid).kind(), form, java.util.Map.of(), java.util.Set.of());
        return id;
    }

    static Repo.ItemForm form(String name) {
        return new Repo.ItemForm(name, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
