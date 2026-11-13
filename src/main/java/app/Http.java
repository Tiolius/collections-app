package app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Requests to external sites (import, Old-Games.ru, update check): one client per application, User-Agent
 * "CollectionsApp/<version>", follows redirects, system proxy.
 */
@Component
public class Http {

    /** Largest picture size to download. */
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).proxy(java.net.ProxySelector.getDefault()).build();
    private final String userAgent;

    public Http(@Value("${app.version}") String appVersion) {
        this.userAgent = "CollectionsApp/" + appVersion;
    }

    /** GET with a text response (UTF-8); headers — "name, value" pairs. */
    public HttpResponse<String> text(String url, String... headers) throws IOException, InterruptedException {
        return client.send(request(url, headers), HttpResponse.BodyHandlers.ofString());
    }

    /** Picture at the address or null: response not 200, not PNG/JPEG/GIF or larger than MAX_IMAGE_BYTES. */
    public byte[] image(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> r = client.send(request(url), HttpResponse.BodyHandlers.ofByteArray());
        byte[] b = r.body();
        return r.statusCode() == 200 && b.length <= MAX_IMAGE_BYTES && Repo.imageType(b) != null ? b : null;
    }

    private HttpRequest request(String url, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", userAgent).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return b.build();
    }
}
