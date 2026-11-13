package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows program: the application opens in a new window of the default browser rather than a tab in an open one.
 * The default browser comes from the registry (UserChoice → ProgId → open command → exe), the new window from its switch:
 * Chrome, Edge, Brave, Opera, Vivaldi, Yandex Browser — "--new-window", Firefox — "-new-window".
 * Unknown browser — the link is opened the usual way (in a tab).
 */
final class BrowserWindow {

    private static final Pattern PROG_ID = Pattern.compile("ProgId\\s+REG_SZ\\s+(\\S+)");
    private static final Pattern COMMAND = Pattern.compile("REG_(?:EXPAND_)?SZ\\s+(?:\"([^\"]+\\.exe)\"|(\\S+\\.exe))", Pattern.CASE_INSENSITIVE);
    private static final List<String> CHROMIUM = List.of("chrome.exe", "msedge.exe", "brave.exe", "opera.exe", "launcher.exe",
            "vivaldi.exe", "browser.exe", "chromium.exe");
    private static final List<String> FIREFOX = List.of("firefox.exe", "librewolf.exe", "waterfox.exe");

    private BrowserWindow() {}

    /** Command that opens url in a new window of the default browser, or null (browser not recognised). */
    static List<String> command(String url) {
        String progId = progId(reg("HKCU\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\http\\UserChoice", "/v", "ProgId"));
        if (progId == null) return null;
        String exe = exe(reg("HKCR\\" + progId + "\\shell\\open\\command", "/ve"));
        return exe == null ? null : newWindow(exe, url);
    }

    /** Browser ProgId from the output of "reg query … /v ProgId". */
    static String progId(String regOutput) {
        Matcher m = PROG_ID.matcher(regOutput);
        return m.find() ? m.group(1) : null;
    }

    /** Path to the exe from the open command (for example {@code "C:\…\chrome.exe" --single-argument %1}). */
    static String exe(String regOutput) {
        Matcher m = COMMAND.matcher(regOutput);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
    }

    /** Runs the exe with the new-window switch; unknown browser — null. */
    static List<String> newWindow(String exe, String url) {
        String name = exe.substring(Math.max(exe.lastIndexOf('\\'), exe.lastIndexOf('/')) + 1).toLowerCase(Locale.ROOT);
        if (CHROMIUM.contains(name)) return List.of(exe, "--new-window", url);
        if (FIREFOX.contains(name)) return List.of(exe, "-new-window", url);
        return null;
    }

    private static String reg(String key, String... args) {
        try {
            var cmd = new java.util.ArrayList<>(List.of("reg", "query", key));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
