package app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Whole import (job, source, requests, saving pictures and description) on a temporary database
 * and a Discogs stub: pictures come from the most popular CD release of the same album.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ImportIntegrationTest {

    static final HttpServer STUB;
    static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    static final Path DATA;
    static final byte[] VINYL = TestImages.png(255, 0, 0), CD = TestImages.png(0, 0, 255), CD_BACK = TestImages.png(0, 0, 128);

    static {
        try {
            DATA = Files.createTempDirectory("collections-test");
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/", ImportIntegrationTest::handle);
            STUB.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static String base() {
        return "http://127.0.0.1:" + STUB.getAddress().getPort();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DATA_DIR", DATA::toString);
        r.add("updates.enabled", () -> "false");
        r.add("discogs.api-url", ImportIntegrationTest::base);
        r.add("discogs.api-interval-ms", () -> "1");
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(), q = ex.getRequestURI().getQuery();
        REQUESTS.add(path + (q == null ? "" : "?" + q));
        String title = "Pink Floyd - The Wall", b = base();
        byte[] body;
        String type = "application/json";
        switch (path) {
            case "/img/vinyl.png" -> { body = VINYL; type = "image/png"; }
            case "/img/cd.png" -> { body = CD; type = "image/png"; }
            case "/img/cdback.png" -> { body = CD_BACK; type = "image/png"; }
            case "/database/search" -> body = (q.contains("type=master")
                    ? "{'results':[{'id':10,'title':'" + title + "','cover_image':'" + b + "/img/vinyl.png','community':{'have':900}}]}"
                    : "{'results':[{'id':1,'master_id':10,'title':'" + title + "','format':['Vinyl'],'cover_image':'" + b + "/img/vinyl.png','community':{'have':5000}},"
                    + "{'id':2,'master_id':10,'title':'" + title + "','format':['CD'],'cover_image':'" + b + "/img/cd.png','community':{'have':3000}}]}")
                    .replace('\'', '"').getBytes(StandardCharsets.UTF_8);
            case "/masters/10" -> body = "{\"notes\":\"Rock [b]opera[/b].\",\"main_release\":1}".getBytes(StandardCharsets.UTF_8);
            case "/releases/2" -> body = ("{\"images\":[{\"type\":\"secondary\",\"uri\":\"" + b + "/img/cdback.png\"},"
                    + "{\"type\":\"primary\",\"uri\":\"" + b + "/img/cd.png\"}]}").getBytes(StandardCharsets.UTF_8);
            default -> {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
        }
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(body);
        }
    }

    @Autowired Repo repo;
    @Autowired ImageImport images;
    @Autowired JdbcTemplate jdbc;

    @Test
    void discogsImportTakesCdEditionImages() throws Exception {
        long uid = repo.createUser("tester", "Kwq7-zmlpt-Rvx9", "admin");
        long cid = repo.createCollection(uid);
        jdbc.update("UPDATE collections SET name = 'CD' WHERE id = ?", cid);
        long item = repo.createItem(cid);
        jdbc.update("UPDATE items SET name = 'Pink Floyd - The Wall' WHERE id = ?", item);

        assertThat(images.start(uid, repo.collection(uid, cid), false, ImageImport.DISCOGS, true, true, true, 5,
                "teststubtoken123")).isTrue();
        ImageImport.Job job = images.job(uid);
        for (int i = 0; i < 200 && job.active(); i++) Thread.sleep(50);

        assertThat(job.state).isEqualTo("done");
        assertThat(job.found).isEqualTo(1);
        Entry e = repo.item(uid, item);
        assertThat(e.description()).isEqualTo("Rock opera.");
        assertThat(e.pics()).isEqualTo(sha(CD) + "," + sha(CD_BACK));
        // releases were searched on CD and by number of owners
        assertThat(REQUESTS).anyMatch(r -> r.contains("type=release") && r.contains("format=CD") && r.contains("sort=have"));
    }

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
