package app;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;

/**
 * Windows program without a console window: a tray icon with the menu "Open", "Data folder", "Exit",
 * double-clicking the icon opens the application in the default browser. Startup errors — in a message window.
 * Menu and windows are our own, in the application's colours and font (DesktopTheme), without the system frame.
 * Texts — messages*.properties (desktop.* keys) in the Windows language: Russian or English.
 */
final class DesktopApp {

    private static final ResourceBundle TEXT = ResourceBundle.getBundle("messages",
            "ru".equals(Locale.getDefault().getLanguage()) ? I18n.RU : Locale.ENGLISH);

    /** Lock of the data folder by this run: the reference is needed, otherwise the file is closed on garbage collection and the lock is released. */
    private static FileLock dataLock;

    private DesktopApp() {}

    static String t(String key, Object... args) {
        return java.text.MessageFormat.format(TEXT.getString(key), args);
    }

    /**
     * Takes the data folder for this run: false — the program is already running (or still starting) from another exe.
     * The lock is released by itself when the program exits.
     */
    static boolean lock(Path dataDir) {
        java.nio.channels.FileChannel ch;
        try {
            ch = java.nio.channels.FileChannel.open(dataDir.resolve(".lock"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
        } catch (IOException e) {
            return true; // could not check — start, a busy port will show an error
        }
        FileLock lock = null;
        try {
            // a byte far beyond the end of the file is locked: on Windows locked bytes cannot be read by other processes,
            // and a second run must read the start of the file (the program port, savePort)
            lock = ch.tryLock(LOCK_AT, 1, false); // null — taken by another process
        } catch (java.nio.channels.OverlappingFileLockException | IOException e) {
            // already taken in this same process (or cannot check) — treated as taken
        }
        if (lock == null) {
            try {
                ch.close(); // on Windows an open file prevents deleting the folder
            } catch (IOException ignored) {
                // could not close — does not matter
            }
            return false;
        }
        dataLock = lock; // the file stays open until the program exits
        return true;
    }

    /** The user's theme pair (after start — from the database); before start — the first pair. */
    private static volatile java.util.function.Supplier<String> pair = () -> "night";

    /** Window theme — on every opening: the pair from "Display", dark or light — as Windows apps are now. */
    private static DesktopTheme theme() {
        String p;
        try {
            p = pair.get();
        } catch (RuntimeException e) {
            p = "night";
        }
        return DesktopTheme.of(p, DesktopTheme.windowsDark());
    }

    /** Where to take the theme pair from: program windows in the same colours as the application. */
    static void setPair(java.util.function.Supplier<String> source) {
        pair = source;
    }

    /** Position of the lock in the .lock file: beyond the port written in it. */
    private static final long LOCK_AT = 1L << 30;

    /** Writes the port of this run to .lock: a second run of the exe opens the browser on it. */
    static void savePort(int port) {
        if (dataLock == null) return;
        try {
            var ch = dataLock.channel();
            ch.truncate(0);
            ch.write(java.nio.ByteBuffer.wrap(String.valueOf(port).getBytes(java.nio.charset.StandardCharsets.US_ASCII)), 0);
            ch.force(false);
        } catch (IOException e) {
            // not written — a second run opens the default port
        }
    }

    /** Port of the already running program from .lock; not written yet (still starting) — -1. */
    static int savedPort(Path dataDir) {
        try {
            return Integer.parseInt(java.nio.file.Files.readString(dataDir.resolve(".lock"),
                    java.nio.charset.StandardCharsets.US_ASCII).trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    /** Releases the data folder lock (for tests: on Windows a file in use cannot be deleted). */
    static void unlock() throws IOException {
        if (dataLock == null) return;
        dataLock.release();
        dataLock.channel().close();
        dataLock = null;
    }

    /** Tray icon; exit — stops the application. No tray (not Windows) — the application just works without the icon. */
    static void show(Runnable open, File dataDir, Runnable exit) {
        EventQueue.invokeLater(() -> {
            if (!SystemTray.isSupported() || !tray(open, dataDir, exit))
                org.slf4j.LoggerFactory.getLogger(DesktopApp.class).warn("Tray icon is not available");
        });
    }

    /** Tray icon: double-click — open; right click — our own menu in the application's colours (the system one cannot be styled). */
    private static boolean tray(Runnable open, File dataDir, Runnable exit) {
        TrayIcon icon = new TrayIcon(icon(), "Collections App");
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> open.run());
        icon.addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e))
                    menu(e.getXOnScreen(), e.getYOnScreen(), open, dataDir, () -> {
                        SystemTray.getSystemTray().remove(icon);
                        exit.run();
                    });
            }
        });
        try {
            SystemTray.getSystemTray().add(icon);
            return true;
        } catch (AWTException e) {
            return false;
        }
    }

    /** Icon menu: a frameless window at the pointer (above the taskbar), closes on a click elsewhere. */
    private static void menu(int x, int y, Runnable open, File dataDir, Runnable exit) {
        DesktopTheme th = theme();
        JDialog d = new JDialog();
        d.setUndecorated(true);
        d.setAlwaysOnTop(true);
        JPanel box = new Rounded(th, 12, th.paper());
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        box.add(menuItem(th, d, t("desktop.open"), th.text(), open));
        box.add(menuItem(th, d, t("desktop.dataFolder"), th.text(), () -> openFolder(dataDir)));
        JPanel sep = new JPanel();
        sep.setOpaque(false);
        sep.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(5, 4, 5, 4),
                BorderFactory.createMatteBorder(1, 0, 0, 0, th.border())));
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 11));
        box.add(sep);
        box.add(menuItem(th, d, t("desktop.exit"), th.red(), exit));
        frame(d, box);
        d.pack();
        Rectangle screen = d.getGraphicsConfiguration().getBounds();
        d.setLocation(Math.max(screen.x, Math.min(x, screen.x + screen.width - d.getWidth())),
                y - d.getHeight() >= screen.y ? y - d.getHeight() : y);
        d.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) { d.dispose(); }
        });
        d.setVisible(true);
        d.toFront();
    }

    private static JComponent menuItem(DesktopTheme th, JDialog menu, String text, Color color, Runnable action) {
        JLabel item = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                if (getClientProperty("hover") != null) {
                    Graphics2D g2 = smooth(g);
                    g2.setColor(th.card2());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                }
                super.paintComponent(g);
            }
        };
        item.setFont(th.font());
        item.setForeground(color);
        item.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 24));
        item.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        item.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { item.putClientProperty("hover", true); item.repaint(); }
            @Override public void mouseExited(MouseEvent e) { item.putClientProperty("hover", null); item.repaint(); }
            @Override public void mouseReleased(MouseEvent e) { menu.dispose(); action.run(); }
        });
        return item;
    }

    private static void openFolder(File dir) {
        try {
            Desktop.getDesktop().open(dir);
        } catch (Exception e) {
            error(t("desktop.folderFailed", dir.getAbsolutePath()));
        }
    }

    /** Error message in a window in the application's colours (waits until it is closed). */
    static void error(String message) {
        Runnable show = () -> {
            DesktopTheme th = theme();
            JDialog d = new JDialog((java.awt.Frame) null, "Collections App", true);
            d.setIconImage(icon());
            d.setUndecorated(true);
            JPanel body = new JPanel(new BorderLayout(14, 20));
            body.setOpaque(false);
            body.add(new Badge(th), BorderLayout.WEST);
            JTextArea text = new JTextArea(message);
            text.setEditable(false);
            text.setOpaque(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setFont(th.font());
            text.setForeground(th.text());
            text.setSize(new Dimension(420, Short.MAX_VALUE)); // width for wrapping lines — before computing the window height
            text.setPreferredSize(new Dimension(420, text.getPreferredSize().height));
            body.add(text, BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            buttons.setOpaque(false);
            JButton ok = button(th, "OK", true, d::dispose);
            buttons.add(ok);
            body.add(buttons, BorderLayout.SOUTH);
            frame(d, panel(th, d, body, d::dispose));
            d.getRootPane().setDefaultButton(ok);
            d.pack();
            d.setLocationRelativeTo(null);
            d.setVisible(true);
        };
        if (EventQueue.isDispatchThread()) show.run();
        else try {
            EventQueue.invokeAndWait(show);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Window without the system frame: transparent background (if Windows supports it) — the panel draws rounded corners. */
    private static void frame(Window w, JComponent content) {
        if (w.getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT))
            w.setBackground(new Color(0, 0, 0, 0));
        else w.setBackground(theme().paper()); // corners without transparency — in the block colour
        if (w instanceof RootPaneContainer r) r.setContentPane(content);
    }

    /** Window block: header (icon, title, close cross; the window is dragged by it) and content with padding. */
    private static JPanel panel(DesktopTheme th, Window w, JComponent body, Runnable close) {
        JPanel p = new Rounded(th, 18, th.paper());
        p.setLayout(new BorderLayout());
        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, th.border()),
                BorderFactory.createEmptyBorder(10, 16, 10, 10)));
        JLabel title = new JLabel("Collections App", new ImageIcon(icon().getScaledInstance(20, 20, Image.SCALE_SMOOTH)), JLabel.LEFT);
        title.setIconTextGap(10);
        title.setFont(th.bold().deriveFont(15f));
        title.setForeground(th.text());
        head.add(title, BorderLayout.CENTER);
        JButton x = button(th, "", false, close);
        x.putClientProperty("flat", true);
        x.setForeground(th.muted());
        x.setPreferredSize(new Dimension(32, 32));
        head.add(x, BorderLayout.EAST);
        MouseAdapter drag = new MouseAdapter() {
            private Point from;
            @Override public void mousePressed(MouseEvent e) { from = e.getPoint(); }
            @Override public void mouseDragged(MouseEvent e) {
                Point at = e.getLocationOnScreen();
                w.setLocation(at.x - from.x, at.y - from.y);
            }
        };
        head.addMouseListener(drag);
        head.addMouseMotionListener(drag);
        p.add(head, BorderLayout.NORTH);
        body.setBorder(BorderFactory.createEmptyBorder(20, 24, 18, 24));
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    /** Button like the application's .btn: primary — accent fill, secondary — border; height 36, radius 10. */
    private static JButton button(DesktopTheme th, String text, boolean primary, Runnable action) {
        JButton b = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = smooth(g);
                boolean hover = getModel().isRollover();
                if (getClientProperty("flat") != null) { // close cross: drawn with lines (the font has no ✕ sign)
                    if (hover) { g2.setColor(th.card2()); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20); }
                    g2.setColor(hover ? th.text() : th.muted());
                    g2.setStroke(new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                    int c = getWidth() / 2, m = getHeight() / 2, r = 5;
                    g2.drawLine(c - r, m - r, c + r, m + r);
                    g2.drawLine(c - r, m + r, c + r, m - r);
                    return;
                } else if (primary) {
                    g2.setColor(hover ? th.accent().brighter() : th.accent());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                } else {
                    if (hover) { g2.setColor(th.card2()); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20); }
                    g2.setColor(th.btnBorder());
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
                }
                super.paintComponent(g);
            }
        };
        b.setFont(th.bold());
        b.setForeground(primary ? th.onAccent() : th.text());
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setMargin(new Insets(0, 0, 0, 0));
        Dimension size = b.getPreferredSize();
        b.setPreferredSize(new Dimension(Math.max(36, size.width + 32), 36));
        b.addActionListener(e -> action.run());
        return b;
    }

    private static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }

    /** Block panel: background, thin border, radius (like the application's blocks). */
    private static final class Rounded extends JPanel {
        private final DesktopTheme th;
        private final int radius;
        private final Color fill;

        Rounded(DesktopTheme th, int radius, Color fill) {
            this.th = th;
            this.radius = radius;
            this.fill = fill;
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth((Graphics2D) g.create());
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius * 2, radius * 2);
            g2.setColor(th.border());
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius * 2, radius * 2);
            g2.dispose();
        }
    }

    /** Error badge: a red circle with "!" (like the warning icon in the application's confirmation window). */
    private static final class Badge extends JComponent {
        private final DesktopTheme th;

        Badge(DesktopTheme th) {
            this.th = th;
            setPreferredSize(new Dimension(36, 36));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth((Graphics2D) g.create());
            g2.setColor(new Color(th.red().getRed(), th.red().getGreen(), th.red().getBlue(), 36));
            g2.fillOval(0, 0, 36, 36);
            g2.setColor(th.red());
            g2.setFont(th.bold().deriveFont(20f));
            java.awt.FontMetrics fm = g2.getFontMetrics();
            g2.drawString("!", (36 - fm.stringWidth("!")) / 2, (36 - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    private static Image icon() {
        try (var in = DesktopApp.class.getResourceAsStream("/desktop/icon.png")) {
            return ImageIO.read(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
