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
        // Запуск из CollectionsApp.exe (jpackage): лаунчер передаёт путь к exe в jpackage.app-path.
        // Окна консоли нет: управление — значок у часов (DesktopApp), ошибки — окном, журнал — data/app.log
        String exe = System.getProperty("jpackage.app-path");
        boolean desktop = exe != null;
        if (desktop) {
            // Данные — в папке data рядом с exe; адрес — http://localhost:47321, только с этого компьютера
            if (System.getenv("DATA_DIR") == null)
                System.setProperty("DATA_DIR", Path.of(exe).toAbsolutePath().getParent().resolve("data").toString());
            System.setProperty("server.port", String.valueOf(DESKTOP_PORT));
            System.setProperty("server.address", "127.0.0.1");
        }
        // Папка для базы должна существовать до подключения к SQLite
        String dataDir = System.getProperty("DATA_DIR", System.getenv().getOrDefault("DATA_DIR", "/data"));
        new File(dataDir).mkdirs();
        if (desktop) {
            // Уже запущена (второй двойной щелчок по exe) — дождаться её запуска и просто открыть браузер
            if (!DesktopApp.lock(Path.of(dataDir))) {
                for (int i = 0; i < 120 && !running(); i++) sleep(500);
                openBrowser();
                return;
            }
            System.setProperty("logging.file.name", new File(dataDir, "app.log").getAbsolutePath());
        }
        // База прежней версии (до 1.18) не переносится: откладывается в резервный файл, приложение начинает с чистой базы
        try {
            DataReset.resetIfOld(Path.of(dataDir));
        } catch (java.io.IOException e) {
            fail(desktop, desktop ? DesktopApp.t("desktop.resetFailed", e.getMessage()) : "Previous data could not be moved aside: " + e.getMessage());
            return;
        }
        SpringApplication app = new SpringApplication(App.class);
        if (desktop) {
            app.setHeadless(false); // значок у часов и окна сообщений — нужна графика
            app.addListeners((ApplicationReadyEvent e) -> {
                try {
                    DesktopApp.show(App::openBrowser, new File(dataDir).getAbsoluteFile(),
                            () -> System.exit(SpringApplication.exit(e.getApplicationContext())));
                } catch (Throwable t) { // без графики приложение всё равно работает — только без значка
                    org.slf4j.LoggerFactory.getLogger(App.class).warn("Tray icon is not available", t);
                }
                openBrowser();
            });
        }
        try {
            app.run(args);
        } catch (RuntimeException e) {
            if (!desktop) throw e;
            // Spring уже записал подробности в журнал; пользователю — коротко, с причиной и путём к журналу
            Throwable cause = e;
            boolean portBusy = false;
            for (Throwable c = e; c != null; c = c.getCause()) {
                portBusy |= c instanceof java.net.BindException || c instanceof org.springframework.boot.web.server.PortInUseException;
                cause = c;
            }
            String reason = portBusy ? DesktopApp.t("desktop.portBusy", String.valueOf(DESKTOP_PORT)) : cause.getMessage();
            fail(true, DesktopApp.t("desktop.startFailed", reason, new File(dataDir, "app.log").getAbsolutePath()));
            System.exit(1);
        }
    }

    /** Ошибка запуска: в программе для Windows — окном, иначе — в консоль. */
    private static void fail(boolean desktop, String message) {
        System.out.println(message);
        if (desktop) try {
            DesktopApp.error(message);
        } catch (Throwable t) {
            // окна не показать (нет графики) — остаётся сообщение в консоли и журнале
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    /** Открывает приложение в браузере по умолчанию (без AWT: работает и в Docker, где графики нет). */
    private static void openBrowser() {
        String url = "http://localhost:" + DESKTOP_PORT;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
                : os.contains("mac") ? new String[] {"open", url} : new String[] {"xdg-open", url};
        try {
            new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            System.out.println("Open in your browser: " + url);
        }
    }
}
