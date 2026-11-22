package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppStateTest {

    @TempDir
    Path dir;

    @Test
    void writesEveryChangeToItsOwnJsonFileAndReadsItBackAfterARestart() throws Exception {
        Path file = dir.resolve("sub").resolve(AppState.FILE_NAME);
        AppState state = new AppState(file);
        state.put("window.width", 1400);
        state.put("window.maximized", true);
        state.putAll(Map.of("sidebar.left.width", "333", "chat.open", "1"));

        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("\"window.width\"").contains("\"sidebar.left.width\"");
        AppState restarted = new AppState(file);
        assertThat(restarted.getInt("window.width", -1)).isEqualTo(1400);
        assertThat(restarted.getBoolean("window.maximized", false)).isTrue();
        assertThat(restarted.getInt("sidebar.left.width", -1)).isEqualTo(333);
        assertThat(restarted.getInt("fehlt", 7)).isEqualTo(7);
    }

    @Test
    void anUnreadableFileMeansDefaultsAndIsReplacedOnTheNextChange() throws Exception {
        Path file = dir.resolve(AppState.FILE_NAME);
        Files.writeString(file, "{ kaputt");
        AppState state = new AppState(file);
        assertThat(state.getInt("window.width", 1280)).isEqualTo(1280);

        state.put("window.width", 1000);
        assertThat(new AppState(file).getInt("window.width", -1)).isEqualTo(1000);
    }

    @Test
    void numbersWrittenByHandAreReadToo() throws Exception {
        Path file = dir.resolve(AppState.FILE_NAME);
        Files.writeString(file, "{\"window.x\": -1920, \"window.maximized\": false, \"chat.width\": \"abc\"}");
        AppState state = new AppState(file);
        assertThat(state.getInt("window.x", 0)).isEqualTo(-1920);
        assertThat(state.getBoolean("window.maximized", true)).isFalse();
        assertThat(state.getInt("chat.width", 420)).isEqualTo(420);
    }
}
