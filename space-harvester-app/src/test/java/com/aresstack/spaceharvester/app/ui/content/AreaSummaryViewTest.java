package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.changelog.SummaryPage;
import com.aresstack.spaceharvester.application.changelog.SummaryStatement;
import com.aresstack.spaceharvester.application.changelog.SummaryUnavailableException;
import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Umschalter „Änderungen | Bereich“: der Überblick über den ganzen Bereich zum Lesen und zum Hören. */
class AreaSummaryViewTest {

    private static final Instant AT = Instant.parse("2026-11-24T09:00:00Z");

    private final SourceUpdatesViewTest.FakeActions shared = new SourceUpdatesViewTest.FakeActions();
    private final FakeArea actions = new FakeArea();

    private AreaSummaryView view(AreaSummaryView.Mode mode, boolean visible) {
        AreaSummaryView view = new AreaSummaryView(() -> actions, new SourceUpdatesViewTest.SyncRunner(), mode);
        view.zone(ZoneOffset.UTC);
        view.showingForTest(() -> visible);
        return view;
    }

    private static UpdateSummary overview(String source) {
        return new UpdateSummary(source, UpdateSummary.AREA, "BAU", "Der Bereich beschreibt das Beispielarchiv.",
                List.of(new SummaryStatement("Hier steht, wie Akten abgelegt werden.",
                        List.of(new SummaryPage("20", "Ablage", "BAU", source)), "Ablage",
                        "Ausführlich: Jede Akte bekommt ein Zeichen, dann wandert sie ins Archiv.")),
                "area-summary-v1:gpt-test", AT, 0, false, Map.of("BAU", "Bauwesen Beispiel"), "bereich-key");
    }

    @Test
    void readingShowsTheOverviewWithoutRedOrGreenAndOpensItsPages() {
        actions.stored = overview("wiki-lesen");
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);
        List<String> opened = new ArrayList<>();
        view.onOpen((source, page) -> opened.add(source + "/" + page));

        view.area("wiki-lesen", "BAU");

        assertThat(view.summary()).isEqualTo(actions.stored);
        assertThat(view.texts()).contains("Der Bereich beschreibt das Beispielarchiv.", "Ablage",
                "Hier steht, wie Akten abgelegt werden.");
        assertThat(actions.summarized).isEmpty();
        assertThat(view.summarizeOffered()).isTrue();
        assertThat(view.copyText()).contains("Ablage", "Hier steht, wie Akten abgelegt werden.",
                "Ausführlich: Jede Akte bekommt ein Zeichen");
    }

    @Test
    void listeningShowsTheDetailedExplanationInlineForThePodcast() {
        actions.stored = overview("wiki-hoeren");
        AreaSummaryView view = view(AreaSummaryView.Mode.LISTEN, true);

        view.area("wiki-hoeren", "BAU");

        assertThat(view.texts()).anyMatch(text -> text != null
                && text.contains("Ausführlich: Jede Akte bekommt ein Zeichen"));
        // Zum Hören gibt es keinen eigenen Zusammenfassen-Knopf; die Leseansicht erstellt den Überblick.
        assertThat(view.summarizeOffered()).isFalse();
    }

    @Test
    void aMissingOverviewIsCreatedOnceOnOpeningAsAnOrdinaryJob() throws Exception {
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);

        view.area("wiki-neu", null);

        assertThat(actions.summarized).containsExactly("wiki-neu|null|false");
        assertThat(view.busy()).isTrue();

        shared.jobNow = JobState.COMPLETED;
        actions.stored = overview("wiki-neu");
        shared.jobs.forEach(listener -> listener.changed(SourceUpdatesViewTest.FakeActions.job("job-area-1",
                JobState.COMPLETED)));
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(view.busy()).isFalse();
        assertThat(view.summary()).isEqualTo(actions.stored);

        // Wieder geöffnet: nicht noch einmal von selbst, auch ohne Überblick.
        actions.stored = null;
        view.area(null, null);
        view.area("wiki-neu", null);
        assertThat(actions.summarized).hasSize(1);
    }

    @Test
    void hiddenViewsAndMissingModelsStartNothingAndSayWhy() {
        AreaSummaryView hidden = view(AreaSummaryView.Mode.READ, false);
        hidden.area("wiki-verdeckt", null);
        assertThat(actions.summarized).isEmpty();

        actions.available = false;
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);
        view.area("wiki-ohne-modell", null);
        assertThat(actions.summarized).isEmpty();
        assertThat(view.texts()).contains(AreaSummaryView.NO_MODEL);
    }

    @Test
    void anEmptyAreaSaysSoInsteadOfOfferingToSummarize() {
        actions.failEmpty = true;
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);

        view.area("wiki-leer", "LEER");

        assertThat(view.texts()).contains(AreaSummaryView.EMPTY);
        assertThat(view.summarizeOffered()).isFalse();
        assertThat(view.busy()).isFalse();
    }

    @Test
    void theToggleIsRememberedGloballyAndDefaultsToChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String before = com.aresstack.spaceharvester.app.ui.AppState.shared().get(AreaSummaryView.SCOPE_KEY);
            try {
                com.aresstack.spaceharvester.app.ui.AppState.shared().put(AreaSummaryView.SCOPE_KEY, (String) null);
                assertThat(AreaSummaryView.areaChosen()).isFalse();
                assertThat(AreaSummaryView.scopeToggle().selected()).isEqualTo(AreaSummaryView.CHANGES);

                AreaSummaryView.choose(true);
                assertThat(AreaSummaryView.areaChosen()).isTrue();
                assertThat(AreaSummaryView.scopeToggle().selected()).isEqualTo(AreaSummaryView.AREA);
            } finally {
                com.aresstack.spaceharvester.app.ui.AppState.shared().put(AreaSummaryView.SCOPE_KEY, before);
            }
        });
    }

    private final class FakeArea implements AreaSummaryView.Actions {

        UpdateSummary stored;
        boolean available = true;
        boolean failEmpty;
        final List<String> summarized = new ArrayList<>();

        @Override
        public UpdateSummary area(String sourceId, String spaceKey) {
            return stored;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public JobStatus summarize(String sourceId, String spaceKey, boolean interactive) {
            if (failEmpty) {
                throw new SummaryUnavailableException(SummaryUnavailableException.Reason.EMPTY_AREA, sourceId);
            }
            summarized.add(sourceId + "|" + spaceKey + "|" + interactive);
            return SourceUpdatesViewTest.FakeActions.job("job-area-" + summarized.size(), JobState.QUEUED);
        }

        @Override
        public SourceUpdatesView.Actions shared() {
            return shared;
        }
    }
}
