package app;

import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * Программа для Windows без окна консоли: значок у часов (трей) с меню «Открыть», «Папка данных», «Выход»,
 * двойной щелчок по значку — приложение в браузере по умолчанию. Ошибки запуска — окном с сообщением.
 * Тексты — messages*.properties (ключи desktop.*) на языке Windows: русский или английский.
 * Если трея нет (не Windows) — вместо значка маленькое окно с теми же кнопками.
 */
final class DesktopApp {

    private static final ResourceBundle TEXT = ResourceBundle.getBundle("messages",
            "ru".equals(Locale.getDefault().getLanguage()) ? I18n.RU : Locale.ENGLISH);

    /** Блокировка папки данных этим запуском: ссылка нужна, иначе файл закроется при сборке мусора и блокировка снимется. */
    private static FileLock dataLock;

    private DesktopApp() {}

    static String t(String key, Object... args) {
        return java.text.MessageFormat.format(TEXT.getString(key), args);
    }

    /**
     * Захватывает папку данных за этим запуском: false — программа уже запущена (или ещё запускается) другим exe.
     * Блокировка снимается сама при выходе из программы.
     */
    @SuppressWarnings("resource") // файл держится открытым до конца работы программы
    static boolean lock(Path dataDir) {
        try {
            FileLock lock = new RandomAccessFile(dataDir.resolve(".lock").toFile(), "rw").getChannel().tryLock();
            if (lock == null) return false;
            dataLock = lock;
            return true;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return false; // уже захвачена в этом же процессе
        } catch (IOException e) {
            return true; // не удалось проверить — запускаемся, занятый порт покажет ошибку
        }
    }

    /** Значок у часов (или окно, если трея нет); exit — остановка приложения. */
    static void show(Runnable open, File dataDir, Runnable exit) {
        EventQueue.invokeLater(() -> {
            if (SystemTray.isSupported() && tray(open, dataDir, exit)) return;
            window(open, dataDir, exit);
        });
    }

    private static boolean tray(Runnable open, File dataDir, Runnable exit) {
        PopupMenu menu = new PopupMenu();
        MenuItem openItem = new MenuItem(t("desktop.open"));
        openItem.addActionListener(e -> open.run());
        MenuItem folder = new MenuItem(t("desktop.dataFolder"));
        folder.addActionListener(e -> openFolder(dataDir));
        MenuItem quit = new MenuItem(t("desktop.exit"));
        menu.add(openItem);
        menu.add(folder);
        menu.addSeparator();
        menu.add(quit);
        TrayIcon icon = new TrayIcon(icon(), "Collections App", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> open.run()); // двойной щелчок по значку
        quit.addActionListener(e -> {
            SystemTray.getSystemTray().remove(icon);
            exit.run();
        });
        try {
            SystemTray.getSystemTray().add(icon);
            return true;
        } catch (AWTException e) {
            return false;
        }
    }

    private static void window(Runnable open, File dataDir, Runnable exit) {
        JFrame f = new JFrame("Collections App");
        f.setIconImage(icon());
        f.setLayout(new FlowLayout(FlowLayout.CENTER, 12, 12));
        f.add(new JLabel(t("desktop.running")));
        JButton openButton = new JButton(t("desktop.open"));
        openButton.addActionListener(e -> open.run());
        JButton folder = new JButton(t("desktop.dataFolder"));
        folder.addActionListener(e -> openFolder(dataDir));
        JButton quit = new JButton(t("desktop.exit"));
        quit.addActionListener(e -> exit.run());
        f.add(openButton);
        f.add(folder);
        f.add(quit);
        f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { exit.run(); }
        });
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    private static void openFolder(File dir) {
        try {
            Desktop.getDesktop().open(dir);
        } catch (Exception e) {
            error(t("desktop.folderFailed", dir.getAbsolutePath()));
        }
    }

    /** Сообщение об ошибке окном Windows (ждёт, пока его закроют). */
    static void error(String message) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // стандартный вид Swing
        }
        JOptionPane.showMessageDialog(null, message, "Collections App", JOptionPane.ERROR_MESSAGE);
    }

    private static Image icon() {
        try (var in = DesktopApp.class.getResourceAsStream("/desktop-icon.png")) {
            return ImageIO.read(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
