package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.deck.DeckTimeouts;
import com.aresstack.spaceharvester.app.ui.deck.DeckTimeouts.Value;
import com.aresstack.spaceharvester.deck.betaview.http.HttpBetaViewServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeckTimeoutsTest {

    @TempDir
    Path dir;

    @Test
    void theDefaultsAreTheFormerFixedValues() {
        DeckTimeouts timeouts = new DeckTimeouts(new AppState(dir.resolve(AppState.FILE_NAME)));

        assertThat(timeouts.get(Value.FTP_CHAT)).isEqualTo(Duration.ofSeconds(120));
        assertThat(timeouts.get(Value.EAKTE_DOCUMENT)).isEqualTo(Duration.ofSeconds(120));
        assertThat(timeouts.get(Value.BETAVIEW)).isEqualTo(HttpBetaViewServer.DEFAULT_TIMEOUT);
    }

    @Test
    void aChangedTimeoutIsReadOnTheNextUse() {
        AppState state = new AppState(dir.resolve(AppState.FILE_NAME));
        DeckTimeouts timeouts = new DeckTimeouts(state);

        timeouts.save(Map.of(Value.FTP_CHAT, 600L));

        assertThat(new DeckTimeouts(state).get(Value.FTP_CHAT)).isEqualTo(Duration.ofMinutes(10));
        assertThat(state.get("deck.timeouts.eakte-document-seconds")).isNull();
    }
}
