package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.release.PageReleaseService.Group;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Die Tabelle der freigegebenen Seiten benennt Knoten und Rückfrage ehrlich (ADR 0028). */
class PageReleasesPanelTest {

    @Test
    void theNodeColumnNamesTheWholeSpaceOrThePageWithItsChildren() {
        Instant at = Instant.parse("2026-11-13T21:00:00Z");
        assertThat(PageReleasesPanel.nodeText(new Group("wiki", "DEV", null, null, 12, at)))
                .isEqualTo(PageReleasesPanel.WHOLE_SPACE);
        assertThat(PageReleasesPanel.nodeText(new Group("wiki", "DEV", "42", "Handbuch", 3, at)))
                .isEqualTo("Handbuch samt Unterseiten");
        assertThat(PageReleasesPanel.nodeText(new Group("wiki", "DEV", "42", null, 3, at)))
                .isEqualTo("Seite 42 samt Unterseiten");
    }

    @Test
    void theQuestionSaysWirklichAndThatTheAiResultsGo() {
        Instant at = Instant.parse("2026-11-13T21:00:00Z");
        Group one = new Group("wiki", "DEV", "42", "Handbuch", 3, at);
        assertThat(PageReleasesPanel.question(List.of(one))).isEqualTo("Die Freigabe von 3 Seiten (Space DEV, "
                + "Handbuch samt Unterseiten) wirklich entfernen? Die KI-Ergebnisse dieser Seiten werden gelöscht.");
        assertThat(PageReleasesPanel.question(List.of(one, new Group("wiki", "OPS", null, null, 1, at))))
                .startsWith("Die Freigabe von 4 Seiten in 2 Gruppen wirklich entfernen?");
    }
}
