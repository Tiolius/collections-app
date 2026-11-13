package app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Windows program: one run per data folder, texts of the icon and message windows. */
class DesktopAppTest {

    @Test
    void dataFolderIsLockedByFirstStart(@TempDir Path dir) throws Exception {
        try {
            assertThat(DesktopApp.lock(dir)).isTrue();
            assertThat(DesktopApp.lock(dir)).isFalse(); // a second run only opens the browser
        } finally {
            DesktopApp.unlock(); // otherwise Windows cannot delete the temporary folder
        }
        assertThat(DesktopApp.lock(dir)).isTrue(); // after exit it can be started again
        DesktopApp.unlock();
    }

    @Test
    void portOfRunningProgramIsReadByNextStart(@TempDir Path dir) throws Exception {
        try {
            assertThat(DesktopApp.lock(dir)).isTrue();
            assertThat(DesktopApp.savedPort(dir)).isEqualTo(-1); // still starting
            DesktopApp.savePort(51234);
            assertThat(DesktopApp.savedPort(dir)).isEqualTo(51234);
        } finally {
            DesktopApp.unlock();
        }
    }

    @Test
    void busyPortIsReplacedByFreeOne() throws Exception {
        int first = App.freePort();
        if (first != App.DESKTOP_PORT) return; // 47321 is already taken on this machine — the check below is not about that
        try (var busy = new java.net.ServerSocket(App.DESKTOP_PORT, 1, java.net.InetAddress.getLoopbackAddress())) {
            int other = App.freePort();
            assertThat(other).isNotEqualTo(App.DESKTOP_PORT).isPositive();
        }
    }

    @Test
    void messagesAreFormatted() {
        assertThat(DesktopApp.t("desktop.startFailed", "Port 47321 is in use", "C:\\app\\data\\app.log"))
                .contains("Port 47321 is in use").contains("C:\\app\\data\\app.log").contains("\n\n");
        assertThat(DesktopApp.t("desktop.exit")).isNotBlank();
        assertThat(DesktopApp.t("desktop.portBusy", "47321")).contains("47321");
    }

    @Test
    void windowColorsComeFromEveryThemeOfApp() {
        // every pair and mode in app.css has all the colours the program windows need
        for (String pair : Repo.THEMES.keySet())
            for (boolean dark : new boolean[] {false, true})
                assertThat(DesktopTheme.vars(pair, dark)).as(pair + (dark ? " dark" : " light"))
                        .containsKeys("page", "paper", "card2", "text", "muted", "border", "blue", "on-blue", "btn2-border", "red");
        assertThat(DesktopTheme.of("night", true).paper()).isEqualTo(java.awt.Color.decode("#18191c"));
        assertThat(DesktopTheme.of("нет такой", false).paper()).isEqualTo(java.awt.Color.decode("#ffffff")); // the first pair
        assertThat(DesktopTheme.of("night", false).font().getFamily()).startsWith("Inter");
    }
}
