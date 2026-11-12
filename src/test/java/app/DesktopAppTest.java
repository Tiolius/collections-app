package app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Программа для Windows: один запуск на папку данных, тексты значка и окон сообщений. */
class DesktopAppTest {

    @Test
    void dataFolderIsLockedByFirstStart(@TempDir Path dir) throws Exception {
        try {
            assertThat(DesktopApp.lock(dir)).isTrue();
            assertThat(DesktopApp.lock(dir)).isFalse(); // второй запуск — только открыть браузер
        } finally {
            DesktopApp.unlock(); // иначе в Windows временную папку не удалить
        }
        assertThat(DesktopApp.lock(dir)).isTrue(); // после выхода — снова можно запустить
        DesktopApp.unlock();
    }

    @Test
    void portOfRunningProgramIsReadByNextStart(@TempDir Path dir) throws Exception {
        try {
            assertThat(DesktopApp.lock(dir)).isTrue();
            assertThat(DesktopApp.savedPort(dir)).isEqualTo(-1); // ещё запускается
            DesktopApp.savePort(51234);
            assertThat(DesktopApp.savedPort(dir)).isEqualTo(51234);
        } finally {
            DesktopApp.unlock();
        }
    }

    @Test
    void busyPortIsReplacedByFreeOne() throws Exception {
        int first = App.freePort();
        if (first != App.DESKTOP_PORT) return; // 47321 уже чем-то занят на этой машине — проверка ниже не о том
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
        // у каждой пары и режима в app.css есть все цвета, нужные окнам программы
        for (String pair : Repo.THEMES.keySet())
            for (boolean dark : new boolean[] {false, true})
                assertThat(DesktopTheme.vars(pair, dark)).as(pair + (dark ? " dark" : " light"))
                        .containsKeys("page", "paper", "card2", "text", "muted", "border", "blue", "on-blue", "btn2-border", "red");
        assertThat(DesktopTheme.of("night", true).paper()).isEqualTo(java.awt.Color.decode("#18191c"));
        assertThat(DesktopTheme.of("нет такой", false).paper()).isEqualTo(java.awt.Color.decode("#ffffff")); // первая пара
        assertThat(DesktopTheme.of("night", false).font().getFamily()).startsWith("Inter");
    }
}
