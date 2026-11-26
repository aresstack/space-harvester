package com.aresstack.spaceharvester.application.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Zwei Sperren für „Daten bereinigen“ hintereinander (Füllung, dann Beschaffung); nur erfundene Quellen. */
class SourceDataPurgeQuiesceTest {

    private final List<String> steps = new ArrayList<>();

    private SourceDataPurge.Quiesce named(String name) {
        return sourceId -> {
            steps.add(name + " hält " + sourceId);
            return () -> steps.add(name + " gibt frei");
        };
    }

    @Test
    void bothHoldInOrderAndReleaseInReverse() throws Exception {
        try (AutoCloseable held = named("Füllung").andThen(named("Beschaffung")).quiesce("wiki")) {
            assertThat(steps).containsExactly("Füllung hält wiki", "Beschaffung hält wiki");
        }

        assertThat(steps).containsExactly("Füllung hält wiki", "Beschaffung hält wiki", "Beschaffung gibt frei",
                "Füllung gibt frei");
    }

    @Test
    void whenTheSecondCannotHoldTheFirstIsReleasedAgain() {
        SourceDataPurge.Quiesce failing = sourceId -> {
            throw new InterruptedException("erfunden");
        };

        assertThatThrownBy(() -> named("Füllung").andThen(failing).quiesce("wiki"))
                .isInstanceOf(InterruptedException.class);
        assertThat(steps).containsExactly("Füllung hält wiki", "Füllung gibt frei");
    }
}
