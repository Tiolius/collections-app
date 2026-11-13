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
 * Base for tests with a database: the application without a web server on a temporary data folder (one for all such tests —
 * the Spring context is shared); each test has its own user (newUser), language — Russian.
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

    /** A new user (with the initial collection types). */
    long newUser() {
        return repo.createUser("user" + USERS.incrementAndGet(), "Kwq7-zmlpt-Rvx9", "user");
    }

    /** A new collection of the user with type kind. */
    long newCollection(long uid, String name, String kind) {
        long cid = repo.createCollection(uid);
        repo.saveCollection(uid, cid, name, kind, null, false);
        return cid;
    }

    /** A new item with a name and fields from the form (null — no such field). */
    long newItem(long uid, long cid, Repo.ItemForm form) {
        long id = repo.createItem(cid);
        repo.saveItem(uid, id, repo.collection(uid, cid).kind(), form, java.util.Map.of(), java.util.Set.of());
        return id;
    }

    static Repo.ItemForm form(String name) {
        return new Repo.ItemForm(name, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
