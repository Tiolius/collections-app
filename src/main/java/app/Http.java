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
 * Запросы к внешним сайтам (импорт, Old-Games.ru, проверка обновлений): один клиент на приложение, заголовок
 * User-Agent «CollectionsApp/<версия>», переходы по перенаправлениям, системный прокси.
 */
@Component
public class Http {

    /** Наибольший размер загружаемой картинки. */
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).proxy(java.net.ProxySelector.getDefault()).build();
    private final String userAgent;

    public Http(@Value("${app.version}") String appVersion) {
        this.userAgent = "CollectionsApp/" + appVersion;
    }

    /** GET с ответом-текстом (UTF-8); headers — пары «имя, значение». */
    public HttpResponse<String> text(String url, String... headers) throws IOException, InterruptedException {
        return client.send(request(url, headers), HttpResponse.BodyHandlers.ofString());
    }

    /** Картинка по адресу или null: ответ не 200, не PNG/JPEG/GIF или больше MAX_IMAGE_BYTES. */
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
