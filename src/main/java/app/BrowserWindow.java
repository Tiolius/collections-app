package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Программа для Windows: приложение открывается в новом окне браузера по умолчанию, а не вкладкой в уже открытом.
 * Браузер по умолчанию — из реестра (UserChoice → ProgId → команда открытия → exe), новое окно — его ключом:
 * у Chrome, Edge, Brave, Opera, Vivaldi, Яндекс Браузера — «--new-window», у Firefox — «-new-window».
 * Не узнали браузер — обычное открытие ссылки (вкладкой).
 */
final class BrowserWindow {

    private static final Pattern PROG_ID = Pattern.compile("ProgId\\s+REG_SZ\\s+(\\S+)");
    private static final Pattern COMMAND = Pattern.compile("REG_(?:EXPAND_)?SZ\\s+(?:\"([^\"]+\\.exe)\"|(\\S+\\.exe))", Pattern.CASE_INSENSITIVE);
    private static final List<String> CHROMIUM = List.of("chrome.exe", "msedge.exe", "brave.exe", "opera.exe", "launcher.exe",
            "vivaldi.exe", "browser.exe", "chromium.exe");
    private static final List<String> FIREFOX = List.of("firefox.exe", "librewolf.exe", "waterfox.exe");

    private BrowserWindow() {}

    /** Команда открытия url в новом окне браузера по умолчанию или null (браузер не узнан). */
    static List<String> command(String url) {
        String progId = progId(reg("HKCU\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\http\\UserChoice", "/v", "ProgId"));
        if (progId == null) return null;
        String exe = exe(reg("HKCR\\" + progId + "\\shell\\open\\command", "/ve"));
        return exe == null ? null : newWindow(exe, url);
    }

    /** ProgId браузера из ответа «reg query … /v ProgId». */
    static String progId(String regOutput) {
        Matcher m = PROG_ID.matcher(regOutput);
        return m.find() ? m.group(1) : null;
    }

    /** Путь к exe из команды открытия («"C:\…\chrome.exe" --single-argument %1»). */
    static String exe(String regOutput) {
        Matcher m = COMMAND.matcher(regOutput);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
    }

    /** Запуск exe с ключом нового окна; незнакомый браузер — null. */
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
