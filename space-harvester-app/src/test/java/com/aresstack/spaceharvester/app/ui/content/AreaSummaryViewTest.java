package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.changelog.SummaryPage;
import com.aresstack.spaceharvester.application.changelog.SummaryStatement;
import com.aresstack.spaceharvester.application.changelog.SummaryUnavailableException;
import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import com.aresstack.spaceharvester.application.holdings.Selection.Root;
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

    /** Ein Ordner im Baum: der Teilbaum unter ihm, und ein Überblick entsteht nur per Klick (Angelo 26.11.2026). */
    @Test
    void aFolderInTheTreeSummarizesItsSubtreeOnlyOnClick() {
        AreaSummaryView.Target folder = AreaSummaryView.Target.subtree("bscw", "ablage", "ordner");
        assertThat(folder.core().roots()).containsExactly(
                com.aresstack.spaceharvester.application.holdings.Selection.Root.subtree("bscw", "ablage", "ordner"));
        assertThat(folder.identity()).isNotEqualTo(AreaSummaryView.Target.area("bscw", "ablage").identity());
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
    void aMissingOverviewIsCreatedOnlyOnClickNeverOnOpening() throws Exception {
        // Angelo 28.11.2026: Der Überblick startete beim Öffnen jedes Bereichs von selbst und dauerte; jetzt nur per Klick.
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);

        view.area("wiki-neu", null);

        assertThat(actions.summarized).isEmpty();
        assertThat(view.texts()).contains(AreaSummaryView.NO_SUMMARY);
        assertThat(view.summarizeOffered()).isTrue();

        view.clickSummarize();
        assertThat(actions.summarized).containsExactly("wiki-neu|null|true");
        assertThat(view.busy()).isTrue();

        shared.jobNow = JobState.COMPLETED;
        actions.stored = overview("wiki-neu");
        shared.jobs.forEach(listener -> listener.changed(SourceUpdatesViewTest.FakeActions.job("job-area-1",
                JobState.COMPLETED)));
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(view.busy()).isFalse();
        assertThat(view.summary()).isEqualTo(actions.stored);

        // Wieder geöffnet: nichts von selbst, auch ohne Überblick.
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
        view.clickSummarize();

        assertThat(view.texts()).contains(AreaSummaryView.EMPTY);
        assertThat(view.summarizeOffered()).isFalse();
        assertThat(view.busy()).isFalse();
    }

    private static ExportPanel.Selection.Collection folder(String id, Root... roots) {
        return new ExportPanel.Selection.Collection(id, "Mein Ordner", List.of(roots), false);
    }

    @Test
    void aFolderIsSummarizedOverItsResolvedRootsAndOpensPagesInTheirOwnSource() {
        ExportPanel.Selection.Collection folder = folder("ordner-a", Root.space("wiki", "BAU"),
                Root.subtree("jira", "PRJ", "7"));
        actions.stored = overview("selection-abc");
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);
        List<String> opened = new ArrayList<>();
        view.onOpen((source, page) -> opened.add(source + "/" + page));

        view.folder(folder);

        assertThat(actions.asked).last().satisfies(target -> assertThat(target.core().roots())
                .containsExactly(Root.space("wiki", "BAU"), Root.subtree("jira", "PRJ", "7")));
        assertThat(view.folder()).isEqualTo(folder);
        assertThat(view.sourceId()).isNull();
        assertThat(view.texts()).contains("Der Bereich beschreibt das Beispielarchiv.");
        assertThat(actions.summarized).isEmpty();
    }

    @Test
    void aChangedFolderOnlyShowsStaleAndNeverStartsTheModelByItself() {
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);
        actions.stored = overview("selection-abc");
        view.folder(folder("ordner-b", Root.space("wiki", "BAU")));
        assertThat(actions.summarized).isEmpty();

        // Ein weiterer Favorit: andere Wurzeln, also (noch) kein Überblick; trotzdem kein KI-Lauf von selbst.
        actions.stored = null;
        view.folder(folder("ordner-b", Root.space("wiki", "BAU"), Root.source("jira")));
        assertThat(actions.summarized).isEmpty();
        assertThat(view.texts()).contains(AreaSummaryView.NO_SUMMARY);
        assertThat(view.summarizeOffered()).isTrue();
    }

    @Test
    void aNeverSummarizedFolderStartsOnlyOnClick() {
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);
        view.folder(folder("ordner-c", Root.space("wiki", "BAU")));
        assertThat(actions.summarized).isEmpty();
        view.clickSummarize();
        assertThat(actions.summarized).containsExactly("ordner:ordner-c|null|true");
    }

    @Test
    void aSummaryTheUserDeletedNeverStartsByItselfAgainOnlyAClickBringsItBack() {
        // ADR 0047 §5a: „Löschen“ unter „Aus dieser Seite erzeugt“; gilt über Neustarts (Zustand der Anwendung).
        ExportPanel.Selection.Collection folder = folder("ordner-geloescht", Root.space("wiki", "BAU"));
        AreaSummaryView.deletedByUser(folder, true);
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);
        view.folder(folder);
        assertThat(actions.summarized).isEmpty();
        assertThat(view.summarizeOffered()).isTrue();

        AreaSummaryView.deletedByUser(folder, false);
        assertThat(AreaSummaryView.deletedByUser(AreaSummaryView.Target.of(folder))).isFalse();
    }

    /**
     * Angelo 27.11.2026: Der Ring verschwand, der Auftrag lief weiter, das fertige Ergebnis erschien nicht. Der laufende
     * Überblick gehört zum Ordner: wer weg- und zurückwechselt oder die andere Ansicht öffnet, sieht den Ring wieder, und
     * das Ende zeigt den Überblick ohne neues Öffnen.
     */
    @Test
    void aRunningOverviewStaysWithItsFolderAcrossSwitchesAndViews() throws Exception {
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);
        ExportPanel.Selection.Collection folder = folder("ordner-lauf", Root.space("wiki", "BAU"));
        view.folder(folder);
        view.clickSummarize();
        assertThat(actions.summarized).containsExactly("ordner:ordner-lauf|null|true");
        assertThat(view.busy()).isTrue();

        view.area(null, null);
        assertThat(view.busy()).isFalse();
        view.folder(folder);
        assertThat(view.busy()).as("zurück am Ordner: der Ring des laufenden Auftrags").isTrue();
        assertThat(actions.summarized).as("kein zweiter Start").hasSize(1);

        AreaSummaryView listen = view(AreaSummaryView.Mode.LISTEN, false);
        listen.folder(folder);
        assertThat(listen.busy()).as("die Podcast-Ansicht kennt den laufenden Auftrag").isTrue();

        shared.jobNow = JobState.COMPLETED;
        actions.stored = overview("selection-lauf");
        shared.jobs.forEach(listener -> listener.changed(SourceUpdatesViewTest.FakeActions.job("job-area-1",
                JobState.COMPLETED)));
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(view.busy()).isFalse();
        assertThat(view.summary()).isEqualTo(actions.stored);
        assertThat(listen.busy()).isFalse();
        assertThat(listen.summary()).isEqualTo(actions.stored);
    }

    /** Angelo: „Teilergebnisse sofort“. Fertige Teile stehen gleich da, ohne Schleier; das Ende ersetzt sie. */
    @Test
    void finishedPartsShowAtOnceWithoutTheVeilAndTheEndReplacesThem() throws Exception {
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);
        view.folder(folder("ordner-teile", Root.space("wiki", "BAU")));
        view.clickSummarize();
        assertThat(view.busy()).isTrue();

        actions.partialNow = overview("selection-teile");
        view.reload();
        assertThat(view.busy()).as("vorläufiger Stand: kein Schleier").isFalse();
        assertThat(view.summary()).isNull();
        assertThat(String.join("\n", view.texts())).contains(AreaSummaryView.PARTIAL, "Ablage");

        actions.partialNow = null;
        actions.stored = overview("selection-teile");
        shared.jobs.forEach(listener -> listener.changed(SourceUpdatesViewTest.FakeActions.job("job-area-1",
                JobState.COMPLETED)));
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(view.summary()).isEqualTo(actions.stored);
        assertThat(String.join("\n", view.texts())).doesNotContain(AreaSummaryView.PARTIAL);
    }

    @Test
    void theReadingViewShowsTheOverviewEvenForAFolderWithABook() throws Exception {
        // Das Buch steht als Artefakt unter „Artefakte“ (ADR 0047, Nachtrag ADR 0044); die Leseansicht zeigt den
        // Überblick und startet ihn nur per Klick, auch wenn am Ordner „Buch“ gewählt ist.
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);
            view.zone(java.time.ZoneId.of("Europe/Berlin"));
            com.aresstack.spaceharvester.app.research.BookEditions.Edition edition =
                    new com.aresstack.spaceharvester.app.research.BookEditions.Edition(2, AT, "# Erfundenes Buch",
                            "Bericht", List.of());
            view.books(id -> edition);
            AreaSummaryView.chooseBook("ordner-wahl", true);

            view.folder(folder("ordner-wahl", Root.space("wiki", "BAU")));

            assertThat(view.bookShown()).isFalse();
            assertThat(view.texts()).contains(AreaSummaryView.NO_SUMMARY);
            assertThat(view.texts()).noneMatch(text -> text.startsWith("Buch, "));
            assertThat(view.summarizeOffered()).isTrue();
            assertThat(actions.summarized).isEmpty();
        });
    }

    @Test
    void changedPagesMarkTheBookInThePodcast() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            AreaSummaryView view = view(AreaSummaryView.Mode.LISTEN, true);
            com.aresstack.spaceharvester.app.research.BookEditions.Edition edition =
                    new com.aresstack.spaceharvester.app.research.BookEditions.Edition(1, AT, "# Buch", "",
                            List.of(new com.aresstack.spaceharvester.app.research.BookEditions.Source("wiki", "1",
                                    "v1")));
            boolean[] changed = {false};
            view.books(id -> edition);
            view.staleness((folderId, shown) -> changed[0]);

            view.folder(folder("ordner-buch", Root.space("wiki", "BAU")));

            assertThat(view.bookShown()).isTrue();
            assertThat(view.bookStale()).isFalse();

            changed[0] = true;
            view.reload();

            assertThat(view.bookStale()).isTrue();
            assertThat(actions.summarized).isEmpty();
        });
    }

    @Test
    void theListeningViewReadsTheBookUnderBookAndTheOverviewUnderArea() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            String before = com.aresstack.spaceharvester.app.ui.AppState.shared().get(AreaSummaryView.BOOK_KEY
                    + "ordner-a");
            try {
                AreaSummaryView view = view(AreaSummaryView.Mode.LISTEN, true);
                actions.stored = overview("selection-abc");
                view.books(id -> new com.aresstack.spaceharvester.app.research.BookEditions.Edition(1, AT,
                        "# Erfundenes Buch\n\nText.", "", List.of()));

                view.folder(folder("ordner-a", Root.space("wiki", "BAU")));

                assertThat(view.edition()).isNotNull();
                assertThat(view.podcast().shown().book()).isNotNull();
                assertThat(view.podcast().shown().book().folderId()).isEqualTo("ordner-a");
                assertThat(view.podcast().shown().book().markdown()).contains("Erfundenes Buch");
                assertThat(actions.summarized).isEmpty();

                AreaSummaryView.chooseBook("ordner-a", false);
                view.bookChanged();

                assertThat(view.podcast().shown().summary()).isEqualTo(actions.stored);
            } finally {
                com.aresstack.spaceharvester.app.ui.AppState.shared().put(AreaSummaryView.BOOK_KEY + "ordner-a",
                        before);
            }
        });
    }

    @Test
    void theBookPodcastMarksTheToldChapterYellowAndNeverToldOnesGrey() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            String before = com.aresstack.spaceharvester.app.ui.AppState.shared().get(AreaSummaryView.BOOK_KEY
                    + "ordner-b");
            try {
                AreaSummaryView view = view(AreaSummaryView.Mode.LISTEN, true);
                String markdown = "# Buch\n\nVorwort.\n\n## Erstes\n\nEins.\n\n## Zweites\n\nZwei.\n";
                int first = markdown.indexOf("## Erstes");
                int second = markdown.indexOf("## Zweites");
                actions.parts = List.of(new com.aresstack.spaceharvester.application.podcast.PagePodcasts.Span(0, first),
                        new com.aresstack.spaceharvester.application.podcast.PagePodcasts.Span(first, second),
                        new com.aresstack.spaceharvester.application.podcast.PagePodcasts.Span(second,
                                markdown.length()));
                view.books(id -> new com.aresstack.spaceharvester.app.research.BookEditions.Edition(1, AT, markdown,
                        "", List.of()));

                view.folder(folder("ordner-b", Root.space("wiki", "BAU")));
                view.followPodcast(List.of(2), java.util.Optional.of(List.of(1, 2)));

                assertThat(view.bookPartMark(2)).isEqualTo(SummaryReport.PODCAST_MARK);
                assertThat(view.bookPartMark(3)).isEqualTo(com.aresstack.spaceharvester.app.ui.Comic.PODCAST_GAP);
                assertThat(view.bookPartMark(1)).isNull();

                view.followPodcast(List.of(3), java.util.Optional.empty());

                assertThat(view.bookPartMark(2)).isNull();
                assertThat(view.bookPartMark(3)).isEqualTo(SummaryReport.PODCAST_MARK);
            } finally {
                com.aresstack.spaceharvester.app.ui.AppState.shared().put(AreaSummaryView.BOOK_KEY + "ordner-b",
                        before);
            }
        });
    }

    @Test
    void anEmptyFolderSaysSoWithoutAskingTheModel() {
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);
        view.folder(folder("ordner-leer"));
        assertThat(view.texts()).contains(AreaSummaryView.NO_FAVORITES);
        assertThat(actions.asked).isEmpty();
        assertThat(actions.summarized).isEmpty();
        assertThat(view.summarizeOffered()).isFalse();
    }

    /**
     * Fehler 24.11.2026: Der Lauf endete sofort, die Ansicht stand wieder auf „Noch kein Überblick“. Jetzt steht der
     * Grund in der Ansicht, ob der Start abgelehnt wird oder der Auftrag scheitert.
     */
    @Test
    void aRefusedStartShowsTheReasonInTheView() {
        actions.failWith = new SummaryUnavailableException(SummaryUnavailableException.Reason.AREA_NOT_LOADED,
                "wiki-ungeladen");
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);
        view.area("wiki-ungeladen", "BAU");
        view.clickSummarize();
        assertThat(view.texts()).contains("Von diesem Bereich ist noch keine Seite geladen; erst laden, dann "
                + "zusammenfassen.", AreaSummaryView.NO_SUMMARY);
        assertThat(view.busy()).isFalse();
    }

    @Test
    void aFailedRunShowsItsReasonInTheViewUntilTheNextStart() throws Exception {
        AreaSummaryView view = view(AreaSummaryView.Mode.READ, true);
        view.area("wiki-fehler", null);
        view.clickSummarize();
        assertThat(view.busy()).isTrue();

        com.aresstack.spaceharvester.application.jobs.JobStatus failed = SourceUpdatesViewTest.FakeActions.job(
                "job-area-1", JobState.FAILED);
        shared.jobs.forEach(listener -> listener.changed(failed));
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(view.busy()).isFalse();
        assertThat(view.texts()).contains("Überblick fehlgeschlagen: Modell nicht erreichbar. "
                + AreaSummaryView.FAILURE_HINT, AreaSummaryView.NO_SUMMARY);
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
        UpdateSummary partialNow;
        boolean available = true;
        boolean failEmpty;
        RuntimeException failWith;
        final List<String> summarized = new ArrayList<>();
        final List<AreaSummaryView.Target> asked = new ArrayList<>();

        @Override
        public UpdateSummary area(AreaSummaryView.Target target) {
            asked.add(target);
            return stored;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public UpdateSummary partial(AreaSummaryView.Target target) {
            return partialNow;
        }

        @Override
        public JobStatus summarize(AreaSummaryView.Target target, boolean interactive) {
            if (failWith != null) {
                throw failWith;
            }
            if (failEmpty) {
                throw new SummaryUnavailableException(SummaryUnavailableException.Reason.EMPTY_AREA,
                        target.sourceId());
            }
            summarized.add((target.folder() == null ? target.sourceId() : "ordner:" + target.folder().folderId())
                    + "|" + target.spaceKey() + "|" + interactive);
            return SourceUpdatesViewTest.FakeActions.job("job-area-" + summarized.size(), JobState.QUEUED);
        }

        @Override
        public SourceUpdatesView.Actions shared() {
            return shared;
        }

        List<com.aresstack.spaceharvester.application.podcast.PagePodcasts.Span> parts = List.of();

        @Override
        public List<com.aresstack.spaceharvester.application.podcast.PagePodcasts.Span> bookParts(
                com.aresstack.spaceharvester.application.podcast.PagePodcasts.Book book) {
            return parts;
        }
    }
}
