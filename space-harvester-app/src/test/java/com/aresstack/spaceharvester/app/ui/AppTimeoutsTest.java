package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.AppTimeouts.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppTimeoutsTest {

    @TempDir
    Path dir;

    @Test
    void theDefaultsAreTheFormerFixedValues() {
        AppTimeouts timeouts = new AppTimeouts(new AppState(dir.resolve(AppState.FILE_NAME)));

        assertThat(timeouts.get(Value.DOWNLOAD_CONNECT)).isEqualTo(Duration.ofSeconds(30));
        assertThat(timeouts.get(Value.DOWNLOAD_RESPONSE)).isEqualTo(Duration.ofSeconds(60));
        assertThat(timeouts.get(Value.FIREFOX_START)).isEqualTo(Duration.ofSeconds(30));
        assertThat(timeouts.get(Value.DIAGRAM_LABELS)).isEqualTo(Duration.ofSeconds(30));
        assertThat(timeouts.get(Value.AUDIO_SILENCE)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aChangedTimeoutIsReadOnTheNextUseAndDefaultsAreNotStored() {
        AppState state = new AppState(dir.resolve(AppState.FILE_NAME));
        new AppTimeouts(state).save(Map.of(Value.FIREFOX_START, 90L));

        assertThat(new AppTimeouts(state).get(Value.FIREFOX_START)).isEqualTo(Duration.ofSeconds(90));
        assertThat(state.get("app.timeouts.firefox-start-seconds")).isEqualTo("90");
        assertThat(state.get("app.timeouts.download-connect-seconds")).isNull();
        assertThatThrownBy(() -> new AppTimeouts(state).save(Map.of(Value.AUDIO_SILENCE, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
