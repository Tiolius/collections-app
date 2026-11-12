package app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Программа для Windows: один запуск на папку данных, тексты значка и окон сообщений. */
class DesktopAppTest {

    @Test
    void dataFolderIsLockedByFirstStart(@TempDir Path dir) {
        assertThat(DesktopApp.lock(dir)).isTrue();
        assertThat(DesktopApp.lock(dir)).isFalse(); // второй запуск — только открыть браузер
    }

    @Test
    void messagesAreFormatted() {
        assertThat(DesktopApp.t("desktop.startFailed", "Port 47321 is in use", "C:\\app\\data\\app.log"))
                .contains("Port 47321 is in use").contains("C:\\app\\data\\app.log").contains("\n\n");
        assertThat(DesktopApp.t("desktop.exit")).isNotBlank();
        assertThat(DesktopApp.t("desktop.portBusy", "47321")).contains("47321");
    }
}
