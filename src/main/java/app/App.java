package app;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Locale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@SpringBootApplication
public class App {

    /** Порт программы для Windows — тот же, что был у Docker: адрес в закладках не меняется. */
    static final int DESKTOP_PORT = 47321;

    public static void main(String[] args) {
        // Запуск из CollectionsApp.exe (jpackage): лаунчер передаёт путь к exe в jpackage.app-path
        String exe = System.getProperty("jpackage.app-path");
        boolean desktop = exe != null;
        if (desktop) {
            // Данные — в папке data рядом с exe; адрес — http://localhost:47321, только с этого компьютера
            if (System.getenv("DATA_DIR") == null)
                System.setProperty("DATA_DIR", Path.of(exe).toAbsolutePath().getParent().resolve("data").toString());
            System.setProperty("server.port", String.valueOf(DESKTOP_PORT));
            System.setProperty("server.address", "127.0.0.1");
            // Уже запущена (второй двойной щелчок по exe) — просто открыть браузер
            if (running()) {
                openBrowser();
                return;
            }
        }
        // Папка для базы должна существовать до подключения к SQLite
        String dataDir = System.getProperty("DATA_DIR", System.getenv().getOrDefault("DATA_DIR", "/data"));
        new File(dataDir).mkdirs();
        SpringApplication app = new SpringApplication(App.class);
        if (desktop) app.addListeners((ApplicationReadyEvent e) -> {
            System.out.println();
            System.out.println("Collections App запущено: http://localhost:" + DESKTOP_PORT);
            System.out.println("Данные: " + new File(dataDir).getAbsolutePath());
            System.out.println("Чтобы остановить приложение, закройте это окно.");
            openBrowser();
        });
        app.run(args);
    }

    /** Отвечает ли уже что-то на порту программы. */
    private static boolean running() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", DESKTOP_PORT), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Открывает приложение в браузере по умолчанию (без AWT: Spring запускается без графики). */
    private static void openBrowser() {
        String url = "http://localhost:" + DESKTOP_PORT;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
                : os.contains("mac") ? new String[] {"open", url} : new String[] {"xdg-open", url};
        try {
            new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            System.out.println("Откройте в браузере: " + url);
        }
    }
}
