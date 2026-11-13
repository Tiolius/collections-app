package app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** New window of the default browser: parsing Windows registry output and the new-window switch. */
class BrowserWindowTest {

    @Test
    void defaultBrowserFromRegistry() {
        String userChoice = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\http\\UserChoice\r\n"
                + "    ProgId    REG_SZ    FirefoxURL-308046B0AF4A39CB\r\n\r\n";
        assertThat(BrowserWindow.progId(userChoice)).isEqualTo("FirefoxURL-308046B0AF4A39CB");
        assertThat(BrowserWindow.progId("ERROR: The system was unable to find the specified registry key or value.")).isNull();
        String chrome = "\r\nHKEY_CLASSES_ROOT\\ChromeHTML\\shell\\open\\command\r\n"
                + "    (Default)    REG_SZ    \"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" --single-argument %1\r\n";
        assertThat(BrowserWindow.exe(chrome)).isEqualTo("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
        String russian = "    (По умолчанию)    REG_SZ    \"C:\\Program Files\\Mozilla Firefox\\firefox.exe\" -osint -url \"%1\"\r\n";
        assertThat(BrowserWindow.exe(russian)).isEqualTo("C:\\Program Files\\Mozilla Firefox\\firefox.exe");
    }

    @Test
    void newWindowKeyByBrowser() {
        String url = "http://localhost:47321";
        assertThat(BrowserWindow.newWindow("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", url))
                .isEqualTo(List.of("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", "--new-window", url));
        assertThat(BrowserWindow.newWindow("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe", url)).contains("--new-window");
        assertThat(BrowserWindow.newWindow("C:\\Users\\me\\AppData\\Local\\Yandex\\YandexBrowser\\Application\\browser.exe", url)).contains("--new-window");
        assertThat(BrowserWindow.newWindow("C:\\Program Files\\Mozilla Firefox\\FIREFOX.EXE", url)).contains("-new-window");
        assertThat(BrowserWindow.newWindow("C:\\Program Files\\Some\\other.exe", url)).isNull(); // unknown browser — a plain link
    }
}
