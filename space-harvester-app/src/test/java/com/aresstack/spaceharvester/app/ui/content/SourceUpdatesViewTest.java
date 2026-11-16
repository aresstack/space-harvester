package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeLogListener;
import com.aresstack.spaceharvester.application.changelog.ChangeReport;
import com.aresstack.spaceharvester.application.changelog.ChangeUpdate;
import com.aresstack.spaceharvester.application.changelog.ReportedPage;
import com.aresstack.spaceharvester.application.changelog.UpdateMark;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.observe.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class SourceUpdatesViewTest {

    private static final Instant FIRST = Instant.parse("2026-11-10T08:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-11-16T18:30:00Z");

    private final FakeActions actions = new FakeActions();
    private final SourceUpdatesView view = new SourceUpdatesView(() -> actions, new SyncRunner());
    private final List<ReportedPage> opened = new ArrayList<>();
    private final List<String> status = new ArrayList<>();

    @BeforeEach
    void setUp() {
        view.zone(ZoneOffset.UTC);
        view.onOpen(opened::add);
        view.onStatus(status::add);
    }

    @Test
    void theOpenUpdateComesFirstAndIsMarkedAsNotYetAnUpdate() {
        view.source("wiki", "Confluence HaVdB");

        assertThat(view.updateHeadings()).containsExactly("Seit dem letzten Update", "16.11.2026, 18:30 · angelo",
                "10.11.2026, 08:00 · angelo");
        assertThat(view.counts(actions.open())).startsWith("noch nicht abgeschlossen").contains("1 neu");
        assertThat(view.counts(actions.initial())).isEqualTo("Erstbefüllung · 340 Seiten");
        assertThat(view.selectedUpdate()).as("das neueste gewählt").isEqualTo(ChangeUpdate.OPEN);
        assertThat(view.subscribed()).isTrue();
    }

    @Test
    void anUpdateListsItsPagesByKindWithPathAndPendingContentAndLocalCountsApart() {
        view.source("wiki", "Confluence HaVdB");

        assertThat(view.clickUpdate("16.11.2026, 18:30 · angelo")).isTrue();

        List<String> texts = view.reportTexts();
        assertThat(texts).contains("Geändert (1)", "Umbenannt (1)", "Entfernt (1)", "Betriebshandbuch", "Alte Seite")
                .contains("CLUK › Fachverfahren · Version 7 statt 6 · Inhalt noch nicht geladen")
                .contains("CLUK · früher „Kurzbeschreibung“")
                .contains("Außerdem im Bestand: 4 Inhalte erstmals lokal geladen, 1 geänderter Inhalt nachgeladen.");
        assertThat(texts).as("geladen und nachgeladen sind keine Änderungen der Quelle")
                .noneMatch(text -> text.startsWith("Erstmals geladen") || text.startsWith("Nachgeladen"));
        assertThat(view.copyText()).contains("Geändert\n- Betriebshandbuch");
    }

    @Test
    void aClickOpensAChangedPageButRemovedPagesAreNotClickable() {
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("16.11.2026, 18:30 · angelo");

        assertThat(view.clickPage("Betriebshandbuch")).isTrue();
        assertThat(opened).singleElement().satisfies(page -> assertThat(page.pageId()).isEqualTo("11"));
        assertThat(view.clickPage("Alte Seite")).isFalse();
        assertThat(opened).hasSize(1);
    }

    @Test
    void theInitialFillShowsOnlyCountsWithoutAPageList() {
        view.source("wiki", "Confluence HaVdB");

        view.clickUpdate("10.11.2026, 08:00 · angelo");

        assertThat(view.reportTexts()).contains(
                "Erstbefüllung: 340 Seiten kamen in den Bestand. Eine Seitenliste gibt es dafür nicht.",
                "Space CLUK: 300 Seiten", "Space DEV: 40 Seiten");
    }

    @Test
    void aMessageLeavesAFinishedUpdateAloneAndKeepsTheChoice() throws Exception {
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("16.11.2026, 18:30 · angelo");
        int updates = actions.updateCalls;
        int reports = actions.reportCalls;
        int renders = view.reportRenders();

        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });

        assertThat(actions.updateCalls).isEqualTo(updates + 1);
        assertThat(view.selectedUpdate()).as("die Ansicht springt nicht").isEqualTo(actions.second().id());
        assertThat(actions.reportCalls).as("ein abgeschlossenes Update ändert sich nie mehr").isEqualTo(reports);
        assertThat(view.reportRenders()).isEqualTo(renders);
    }

    @Test
    void aMessageRereadsTheOpenUpdateButRedrawsOnlyWhenItChanged() throws Exception {
        view.source("wiki", "Confluence HaVdB");
        int reports = actions.reportCalls;
        int renders = view.reportRenders();

        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(actions.reportCalls).isEqualTo(reports + 1);
        assertThat(view.reportRenders()).as("unverändert: nichts neu gezeichnet").isEqualTo(renders);

        actions.openGrows = true;
        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(view.reportRenders()).isEqualTo(renders + 1);
        assertThat(view.reportTexts()).contains("Noch eine Seite");
        assertThat(view.selectedUpdate()).isEqualTo(ChangeUpdate.OPEN);
    }

    @Test
    void withoutChangesTheViewSaysHowUpdatesComeAbout() {
        actions.none = true;

        view.source("wiki", "Confluence HaVdB");

        assertThat(view.updateHeadings()).isEmpty();
        assertThat(view.reportTexts()).containsExactly(SourceUpdatesView.NO_UPDATES);
    }

    @Test
    void leavingTheSourceEndsTheSubscription() {
        view.source("wiki", "Confluence HaVdB");

        view.source(null, null);

        assertThat(actions.closed).isTrue();
        assertThat(view.subscribed()).isFalse();
        assertThat(view.sourceId()).isNull();
    }

    @Test
    void anUpdateWithoutSummaryOffersToSummarizeButNotTheInitialFill() {
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("16.11.2026, 18:30 · angelo");

        assertThat(view.summarizeOffered()).isTrue();
        assertThat(view.summarizeLabel()).isEqualTo(SourceUpdatesView.SUMMARIZE);
        assertThat(view.reportTexts()).doesNotContain("Zusammenfassung");

        view.clickUpdate("10.11.2026, 08:00 · angelo");
        assertThat(view.summarizeOffered()).as("Erstbefüllung").isFalse();
        assertThat(actions.summarizeCalls).as("nie von selbst").isZero();
    }

    @Test
    void summarizingRunsAsAJobAndShowsTheSummaryAboveTheKindsWithLinksToItsPages() throws Exception {
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("16.11.2026, 18:30 · angelo");

        view.clickSummarize();

        assertThat(actions.summarizeCalls).isEqualTo(1);
        assertThat(view.busy()).as("BusyOverlay über dem Bericht").isTrue();
        assertThat(view.summarizeOffered()).as("nie zwei zugleich").isFalse();
        assertThat(status).contains(SourceUpdatesView.SUMMARIZING);
        view.clickUpdate("10.11.2026, 08:00 · angelo");
        assertThat(view.busy()).as("nur über dem Update, das zusammengefasst wird").isFalse();
        view.clickUpdate("16.11.2026, 18:30 · angelo");
        assertThat(view.busy()).isTrue();

        actions.finish(JobState.COMPLETED);

        assertThat(view.busy()).isFalse();
        List<String> texts = view.reportTexts();
        assertThat(texts).containsSubsequence("Zusammenfassung", "Erstellt am 16.11.2026, 19:00 mit gpt-test",
                "Das Betriebshandbuch wurde überarbeitet.", "→ Betriebshandbuch", "Geändert (1)");
        assertThat(view.summarizeLabel()).isEqualTo(SourceUpdatesView.RESUMMARIZE);
        assertThat(status).last().isEqualTo("Zusammenfassung erstellt");
        assertThat(view.copyText()).contains("Zusammenfassung (Erstellt am").contains("überarbeitet");

        assertThat(view.clickSummaryLink("Betriebshandbuch")).isTrue();
        assertThat(opened).singleElement().satisfies(page -> assertThat(page.pageId()).isEqualTo("11"));
        assertThat(view.clickSummaryLink("Alte Seite")).as("entfernte Seiten öffnen nichts").isFalse();
    }

    @Test
    void aStoredSummaryIsReadBackWithStaleAndUnreadableNotes() {
        actions.stored = new SourceUpdatesView.Summary(List.of(new SourceUpdatesView.Statement("Eine neue Seite.",
                List.of("20"))), "gpt-test", SECOND, 2, true);
        view.source("wiki", "Confluence HaVdB");

        assertThat(view.reportTexts()).contains("Eine neue Seite.", "→ Neue Seite",
                "Das Update hat sich danach geändert; diese Zusammenfassung kennt nicht alles.",
                "2 Änderungen durfte die KI nicht lesen; sie fehlen in der Zusammenfassung.");
        assertThat(view.summarizeLabel()).isEqualTo(SourceUpdatesView.RESUMMARIZE);
        assertThat(view.counts(summarized(actions.second()))).endsWith(" · zusammengefasst");
    }

    @Test
    void cancellingEndsTheJobAndKeepsThePreviousSummary() throws Exception {
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("16.11.2026, 18:30 · angelo");
        view.clickSummarize();

        view.cancelRunningSummary();

        assertThat(actions.cancelled).isEqualTo("job-1");
        assertThat(view.busy()).isFalse();
        assertThat(view.reportTexts()).doesNotContain("Zusammenfassung");
        assertThat(status).last().isEqualTo("Abgebrochen; die bisherige Zusammenfassung bleibt.");
        assertThat(view.summarizeOffered()).isTrue();
    }

    @Test
    void aFailedJobSaysWhyAndOffersItAgain() throws Exception {
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("16.11.2026, 18:30 · angelo");
        view.clickSummarize();

        actions.finish(JobState.FAILED);

        assertThat(status).last().isEqualTo("Zusammenfassung fehlgeschlagen: Modell nicht erreichbar");
        assertThat(view.busy()).isFalse();
        assertThat(view.summarizeOffered()).isTrue();
    }

    @Test
    void withoutAChatModelSummarizingIsNotOffered() {
        actions.available = false;
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("16.11.2026, 18:30 · angelo");

        assertThat(view.summarizeOffered()).isFalse();
        assertThat(view.summarizeTip()).isEqualTo(SourceUpdatesView.NO_MODEL);
    }

    /** Bildschirmfoto der Quellenseite zur Sichtprüfung; nur mit der Umgebungsvariable {@code SCREENSHOTS_DIR}. */
    @Test
    void renderSourcePage() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(directory != null && !directory.isBlank());
        java.nio.file.Path out = java.nio.file.Files.createDirectories(java.nio.file.Path.of(directory));
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            view.source("wiki", "Confluence HaVdB");
            view.clickUpdate("16.11.2026, 18:30 · angelo");
            view.clickSummarize();
            actions.finishNow(JobState.COMPLETED);
            AnalysisTabsScreenshotTest.write(view, out.resolve("quellenseite-updates.png"));
        });
    }

    private static ChangeUpdate summarized(ChangeUpdate u) {
        return new ChangeUpdate(u.id(), u.sourceId(), u.from(), u.to(), u.mark(), u.initial(), u.kinds(), u.spaces(),
                u.pages(), u.loaded(), u.reloaded(), true);
    }

    static final class SyncRunner implements SourceUpdatesView.Runner {
        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            try {
                onSuccess.accept(work.call());
            } catch (Exception e) {
                onFailure.accept(e);
            }
        }
    }

    static final class FakeActions implements SourceUpdatesView.Actions {

        boolean none;
        boolean closed;
        int updateCalls;
        int reportCalls;
        boolean openGrows;
        int summarizeCalls;
        boolean available = true;
        String cancelled;
        JobListener jobs;
        SourceUpdatesView.Summary stored;

        static JobStatus job(JobState state) {
            return new JobStatus("job-1", JobType.EXPORT, "wiki", null, "local", state, new JobProgress(1, 0, 0, 0),
                    state == JobState.FAILED ? new JobError("X", "Modell nicht erreichbar") : null, false, SECOND,
                    null, null, null, null, Map.of(), false, false, null);
        }

        /** Der Core beendet den Auftrag: Ablage, Meldung des Auftrags (Zustell-Thread), dann EDT. */
        void finish(JobState state) throws Exception {
            finishNow(state);
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        }

        void finishNow(JobState state) {
            if (state == JobState.COMPLETED) {
                stored = summaryOf();
            }
            jobs.changed(job(state));
        }

        private static SourceUpdatesView.Summary summaryOf() {
            return new SourceUpdatesView.Summary(List.of(
                    new SourceUpdatesView.Statement("Das Betriebshandbuch wurde überarbeitet.", List.of("11")),
                    new SourceUpdatesView.Statement("Eine alte Seite ist weggefallen, eine andere heißt jetzt "
                            + "„Kurzbeschreibung DATENH“.", List.of("13", "12"))),
                    "gpt-test", SECOND.plusSeconds(1800), 0, false);
        }
        ChangeLogListener listener;

        ChangeUpdate open() {
            return new ChangeUpdate(ChangeUpdate.OPEN, "wiki", SECOND, null, null, false, Map.of(ChangeKind.NEW, 1),
                    Map.of("CLUK", 1), 1, 0, 0, false);
        }

        ChangeUpdate second() {
            Map<ChangeKind, Integer> kinds = new EnumMap<>(ChangeKind.class);
            kinds.put(ChangeKind.CONTENT_CHANGED, 1);
            kinds.put(ChangeKind.RENAMED, 1);
            kinds.put(ChangeKind.REMOVED, 1);
            return new ChangeUpdate(String.valueOf(SECOND.toEpochMilli()), "wiki", FIRST, SECOND,
                    new UpdateMark("wiki", SECOND, "angelo", List.of("CLUK"), 0, false), false, kinds,
                    Map.of("CLUK", 3), 3, 4, 1, false);
        }

        ChangeUpdate initial() {
            Map<String, Integer> spaces = new HashMap<>();
            spaces.put("CLUK", 300);
            spaces.put("DEV", 40);
            return new ChangeUpdate(String.valueOf(FIRST.toEpochMilli()), "wiki", null, FIRST,
                    new UpdateMark("wiki", FIRST, "angelo", List.of("CLUK", "DEV"), 0, true), true,
                    Map.of(ChangeKind.NEW, 340), spaces, 340, 0, 0, false);
        }

        @Override
        public List<ChangeUpdate> updates(String sourceId) {
            updateCalls++;
            return none ? List.of() : List.of(open(), second(), initial());
        }

        @Override
        public ChangeReport report(String sourceId, String updateId) {
            reportCalls++;
            if (updateId.equals(initial().id())) {
                return new ChangeReport(initial(), List.of());
            }
            if (updateId.equals(ChangeUpdate.OPEN)) {
                List<ReportedPage> pages = new ArrayList<>(List.of(page("20", ChangeKind.NEW, "Neue Seite",
                        List.of("CLUK"), null, null, null, false)));
                if (openGrows) {
                    pages.add(page("21", ChangeKind.NEW, "Noch eine Seite", List.of("CLUK"), null, null, null, false));
                }
                return new ChangeReport(open(), pages);
            }
            return new ChangeReport(second(), List.of(
                    page("11", ChangeKind.CONTENT_CHANGED, "Betriebshandbuch", List.of("CLUK", "Fachverfahren"),
                            null, "6", "7", true),
                    page("12", ChangeKind.RENAMED, "Kurzbeschreibung DATENH", List.of("CLUK"), "Kurzbeschreibung",
                            null, null, false),
                    page("13", ChangeKind.REMOVED, "Alte Seite", List.of(), null, null, null, false)));
        }

        @Override
        public SourceUpdatesView.Summary summary(String sourceId, String updateId) {
            return stored;
        }

        @Override
        public boolean summariesAvailable() {
            return available;
        }

        @Override
        public JobStatus summarize(String sourceId, String updateId) {
            summarizeCalls++;
            return job(JobState.QUEUED);
        }

        @Override
        public JobStatus job(String jobId) {
            return job(JobState.RUNNING);
        }

        @Override
        public JobStatus cancelJob(String jobId) {
            cancelled = jobId;
            return job(JobState.CANCELLED);
        }

        @Override
        public Subscription subscribeJobs(JobListener listener) {
            jobs = listener;
            return () -> jobs = null;
        }

        @Override
        public Subscription subscribe(String sourceId, ChangeLogListener listener) {
            this.listener = listener;
            return () -> closed = true;
        }

        private static ReportedPage page(String id, ChangeKind kind, String title, List<String> path,
                                         String oldTitle, String oldVersion, String newVersion,
                                         boolean pending) {
            return new ReportedPage("wiki", "CLUK", id, kind, Set.of(kind), title, path,
                    URI.create("https://wiki.example/" + id), oldVersion, newVersion, oldTitle, title, null, null,
                    null, null, pending, SECOND, null);
        }
    }
}
