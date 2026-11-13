package app;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colours and font of the Windows program windows are the same as in the application: theme variables are read from static/app.css
 * (the block of the theme pair and mode), the font is Inter. The pair is the one the user chose in "Display"; dark or light —
 * as Windows apps ("Settings" → "Personalization" → "Colors").
 */
record DesktopTheme(Color page, Color paper, Color card2, Color text, Color muted, Color border,
                    Color accent, Color onAccent, Color btnBorder, Color red, Font font, Font bold) {

    private static final Pattern BLOCK = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Pattern VAR = Pattern.compile("--([a-z0-9-]+):\\s*(#[0-9a-fA-F]{6})\\b");

    /** Theme of pair (a Repo.THEMES key; unknown — the first pair), dark or light. */
    static DesktopTheme of(String pair, boolean dark) {
        Map<String, Color> v = vars(pair, dark);
        if (v.isEmpty()) v = vars("night", dark);
        Font regular = font("Inter-Regular.otf"), semi = font("Inter-SemiBold.otf");
        return new DesktopTheme(v.get("page"), v.get("paper"), v.get("card2"), v.get("text"), v.get("muted"), v.get("border"),
                v.get("blue"), v.get("on-blue"), v.get("btn2-border"), v.get("red"), regular, semi);
    }

    /** Colour variables (#rrggbb) of the pair and mode block from app.css: name without "--" → colour. */
    static Map<String, Color> vars(String pair, boolean dark) {
        Map<String, Color> res = new HashMap<>();
        Matcher b = BLOCK.matcher(css());
        while (b.find()) {
            String sel = b.group(1);
            if (!sel.contains("[data-pair=\"" + pair + "\"]") || sel.contains("data-theme=\"dark\"") != dark) continue;
            Matcher m = VAR.matcher(b.group(2));
            while (m.find()) res.put(m.group(1), Color.decode(m.group(2)));
            break;
        }
        return res;
    }

    /** Dark mode of Windows apps (registry AppsUseLightTheme = 0); not Windows — light (or the desktop.dark property). */
    static boolean windowsDark() {
        String forced = System.getProperty("desktop.dark"); // for checking the windows outside Windows
        if (forced != null) return Boolean.parseBoolean(forced);
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return false;
        try {
            Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return out.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0\\b.*");
        } catch (IOException e) {
            return false;
        }
    }

    private static String css() {
        try (InputStream in = DesktopTheme.class.getResourceAsStream("/static/app.css")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Font font(String file) {
        try (InputStream in = DesktopTheme.class.getResourceAsStream("/desktop/" + file)) {
            return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(14f);
        } catch (Exception e) {
            return new Font(Font.SANS_SERIF, file.contains("Semi") ? Font.BOLD : Font.PLAIN, 14);
        }
    }
}
