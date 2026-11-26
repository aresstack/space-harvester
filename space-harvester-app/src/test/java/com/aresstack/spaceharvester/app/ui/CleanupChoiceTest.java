package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs.CleanupPreview;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** „Ohne Erwerbsgrund bereinigen“: Anzahl und Umfang vorab, „Grund unbekannt“ nur auf ausdrückliche Wahl. */
class CleanupChoiceTest {

    private final CleanupChoice choice = CleanupChoice.of(List.of(
            new CleanupPreview("ftp", "HLQ", Set.of("a", "b"), Set.of("c"), null),
            new CleanupPreview("ftp", "LIB", Set.of(), Set.of(), "Inventur abgebrochen"),
            new CleanupPreview("ftp", "OTHER", Set.of("d"), Set.of(), null)));

    @Test
    void theQuestionNamesCountScopeUnknownAndIncompleteAreas() {
        assertThat(choice.withoutReason()).isEqualTo(3);
        assertThat(choice.unknown()).isEqualTo(1);
        assertThat(choice.incomplete()).isEqualTo(1);
        assertThat(choice.text("Host")).contains("Wirklich 3 Ressourcen ohne Erwerbsgrund", "HLQ (2 + 1 unbekannt)",
                "OTHER (1)", "1 Ressource hat einen unbekannten Grund", "1 Bereich bleibt unangetastet",
                "Originale in der Quelle bleiben unberührt").doesNotContain("LIB");
    }

    @Test
    void unknownReasonsAreOnlyChosenWhenTicked() {
        assertThat(choice.chosen(false)).containsOnlyKeys("HLQ", "OTHER");
        assertThat(choice.chosen(false).get("HLQ")).containsExactlyInAnyOrder("a", "b");
        assertThat(choice.chosen(true).get("HLQ")).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void onlyUnknownReasonsStillAskWirklich() {
        CleanupChoice onlyUnknown = CleanupChoice.of(List.of(new CleanupPreview("ftp", "HLQ", Set.of(), Set.of("c"),
                null)));
        assertThat(onlyUnknown.text("Host")).contains("Wirklich bereinigen?", "HLQ (0 + 1 unbekannt)");
        assertThat(onlyUnknown.chosen(false)).isEmpty();
    }
}
