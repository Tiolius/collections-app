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

    /** Порт этого запуска: DESKTOP_PORT, а если он занят другой программой — любой свободный (freePort). */
    private static int port = DESKTOP_PORT;

    public static void main(String[] args) {
        // Запуск из CollectionsApp.exe (jpackage): лаунчер передаёт путь к exe в jpackage.app-path.
        // Окна консоли нет: управление — значок у часов (DesktopApp), ошибки — окном, журнал — logs/app.log рядом с exe (data — только для данных)
        String exe = System.getProperty("jpackage.app-path");
        boolean desktop = exe != null;
        if (desktop) {
            // Данные — в папке data рядом с exe; адрес — http://localhost:47321 (занят — другой свободный), только с этого компьютера
            if (System.getenv("DATA_DIR") == null)
                System.setProperty("DATA_DIR", Path.of(exe).toAbsolutePath().getParent().resolve("data").toString());
            System.setProperty("server.address", "127.0.0.1");
        }
        // Папка для базы должна существовать до подключения к SQLite
        String dataDir = System.getProperty("DATA_DIR", System.getenv().getOrDefault("DATA_DIR", "/data"));
        new File(dataDir).mkdirs();
        if (desktop) {
            // Уже запущена (второй двойной щелчок по exe) — дождаться её запуска и просто открыть браузер
            // (порт — из файла блокировки: у запущенной он мог оказаться не 47321)
            if (!DesktopApp.lock(Path.of(dataDir))) {
                for (int i = 0; i < 120; i++) {
                    int saved = DesktopApp.savedPort(Path.of(dataDir));
                    if (saved > 0) port = saved;
                    if (saved > 0 && running()) break;
                    sleep(500);
                }
                openBrowser();
                return;
            }
            // Порт 47321 занят другой программой — запуск на свободном порту, без ошибки
            port = freePort();
            System.setProperty("server.port", String.valueOf(port));
            DesktopApp.savePort(port);
            System.setProperty("logging.file.name", logFile(exe).getAbsolutePath());
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
                    // окна программы — в паре тем пользователя (первого, обычно он один)
                    var jdbc = e.getApplicationContext().getBean(org.springframework.jdbc.core.JdbcTemplate.class);
                    DesktopApp.setPair(() -> jdbc.queryForList("SELECT pair FROM user_theme ORDER BY user_id LIMIT 1", String.class)
                            .stream().findFirst().orElse("night"));
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
            String reason = portBusy ? DesktopApp.t("desktop.portBusy", String.valueOf(port)) : cause.getMessage();
            fail(true, DesktopApp.t("desktop.startFailed", reason, logFile(exe).getAbsolutePath()));
            System.exit(1);
        }
    }

    /** Журнал программы для Windows: папка logs рядом с exe (не в data — там только данные). */
    private static File logFile(String exe) {
        return Path.of(exe).toAbsolutePath().getParent().resolve("logs").resolve("app.log").toFile();
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

    /** DESKTOP_PORT, если он свободен, иначе свободный порт, который выберет система. */
    static int freePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(DESKTOP_PORT, 1, java.net.InetAddress.getLoopbackAddress())) {
            return DESKTOP_PORT;
        } catch (java.io.IOException busy) {
            try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                return s.getLocalPort();
            } catch (java.io.IOException e) {
                return DESKTOP_PORT; // не выбрать — пусть запуск сообщит об ошибке
            }
        }
    }

    /** Отвечает ли уже что-то на порту программы. */
    private static boolean running() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Открывает приложение в браузере по умолчанию (без AWT: работает и в Docker, где графики нет). */
    private static void openBrowser() {
        String url = "http://localhost:" + port;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // Windows: новое окно браузера по умолчанию (BrowserWindow), не узнали браузер — обычное открытие ссылки
        java.util.List<String> window = os.contains("win") ? BrowserWindow.command(url) : null;
        String[] cmd = window != null ? window.toArray(String[]::new)
                : os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
                : os.contains("mac") ? new String[] {"open", url} : new String[] {"xdg-open", url};
        try {
            new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            System.out.println("Open in your browser: " + url);
        }
    }
}
