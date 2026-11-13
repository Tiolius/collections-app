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

    /** Port of the Windows program — the same as Docker had, so bookmarked addresses do not change. */
    static final int DESKTOP_PORT = 47321;

    /** Port of this run: DESKTOP_PORT, or any free one if another program uses it (freePort). */
    private static int port = DESKTOP_PORT;

    public static void main(String[] args) {
        // Started from CollectionsApp.exe (jpackage): the launcher passes the exe path in jpackage.app-path.
        // No console window: control — the tray icon (DesktopApp), errors — in a window, log — logs/app.log next to the exe (data is for data only)
        String exe = System.getProperty("jpackage.app-path");
        boolean desktop = exe != null;
        if (desktop) {
            // Data — in the data folder next to the exe; address — http://localhost:47321 (busy — another free one), this computer only
            if (System.getenv("DATA_DIR") == null)
                System.setProperty("DATA_DIR", Path.of(exe).toAbsolutePath().getParent().resolve("data").toString());
            System.setProperty("server.address", "127.0.0.1");
        }
        // The database folder must exist before connecting to SQLite
        String dataDir = System.getProperty("DATA_DIR", System.getenv().getOrDefault("DATA_DIR", "/data"));
        new File(dataDir).mkdirs();
        if (desktop) {
            // Already running (a second double-click on the exe) — wait until it starts and just open the browser
            // (port — from the lock file: the running one may not be on 47321)
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
            // Port 47321 is used by another program — start on a free port, without an error
            port = freePort();
            System.setProperty("server.port", String.valueOf(port));
            DesktopApp.savePort(port);
            System.setProperty("logging.file.name", logFile(exe).getAbsolutePath());
        }
        // A database of a previous version (before 1.18) is not carried over: it is moved aside into a backup file, the application starts with a clean database
        try {
            DataReset.resetIfOld(Path.of(dataDir));
        } catch (java.io.IOException e) {
            fail(desktop, desktop ? DesktopApp.t("desktop.resetFailed", e.getMessage()) : "Previous data could not be moved aside: " + e.getMessage());
            return;
        }
        SpringApplication app = new SpringApplication(App.class);
        if (desktop) {
            app.setHeadless(false); // tray icon and message windows need graphics
            app.addListeners((ApplicationReadyEvent e) -> {
                try {
                    // program windows use the user's theme pair (the first user, usually the only one)
                    var jdbc = e.getApplicationContext().getBean(org.springframework.jdbc.core.JdbcTemplate.class);
                    DesktopApp.setPair(() -> jdbc.queryForList("SELECT pair FROM user_theme ORDER BY user_id LIMIT 1", String.class)
                            .stream().findFirst().orElse("night"));
                    DesktopApp.show(App::openBrowser, new File(dataDir).getAbsoluteFile(),
                            () -> System.exit(SpringApplication.exit(e.getApplicationContext())));
                } catch (Throwable t) { // without graphics the application still works — just without the icon
                    org.slf4j.LoggerFactory.getLogger(App.class).warn("Tray icon is not available", t);
                }
                openBrowser();
            });
        }
        try {
            app.run(args);
        } catch (RuntimeException e) {
            if (!desktop) throw e;
            // Spring has already written the details to the log; the user gets a short message with the reason and the log path
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

    /** Log of the Windows program: the logs folder next to the exe (not in data — that is for data only). */
    private static File logFile(String exe) {
        return Path.of(exe).toAbsolutePath().getParent().resolve("logs").resolve("app.log").toFile();
    }

    /** Startup error: in the Windows program — in a window, otherwise — to the console. */
    private static void fail(boolean desktop, String message) {
        System.out.println(message);
        if (desktop) try {
            DesktopApp.error(message);
        } catch (Throwable t) {
            // a window cannot be shown (no graphics) — the message stays in the console and the log
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** DESKTOP_PORT if it is free, otherwise a free port chosen by the system. */
    static int freePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(DESKTOP_PORT, 1, java.net.InetAddress.getLoopbackAddress())) {
            return DESKTOP_PORT;
        } catch (java.io.IOException busy) {
            try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                return s.getLocalPort();
            } catch (java.io.IOException e) {
                return DESKTOP_PORT; // cannot choose — let the start report the error
            }
        }
    }

    /** Whether something already answers on the program port. */
    private static boolean running() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Opens the application in the default browser (without AWT: works in Docker too, where there are no graphics). */
    private static void openBrowser() {
        String url = "http://localhost:" + port;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // Windows: a new window of the default browser (BrowserWindow); browser not recognised — the link is opened the usual way
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
