package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** „Analysieren“ im Kontextmenü des Explorers (#499 E): Einträge, Umfang, Bestätigung und Start. */
class ExplorerAnalyzeTest {

    private static final Explorer.PageItem LEAF = new Explorer.PageItem("wiki", "OPS", "p1", "Blatt", false);
    private static final Explorer.PageItem PARENT = new Explorer.PageItem("wiki", "OPS", "p2", "Eltern", true);
    private static final Explorer.PageItem UNKNOWN = new Explorer.PageItem("wiki", "OPS", "p3", "Offen", null);
    private static final Explorer.SpaceItem SPACE = new Explorer.SpaceItem("wiki", "OPS", "Betrieb");
    private static final Explorer.SourceItem SOURCE = new Explorer.SourceItem("wiki", "Wiki");

    @TempDir
    Path home;

    private AppContext context;
    private Explorer explorer;
    private final FakeAnalyses analyses = new FakeAnalyses();
    private final List<String> confirmations = new CopyOnWriteArrayList<>();
    private final List<String> actions = new CopyOnWriteArrayList<>();
    private final List<String> shownJobs = new CopyOnWriteArrayList<>();
    private final List<String> notices = new CopyOnWriteArrayList<>();
    private volatile Explorer.AnalysisChoice answer = new Explorer.AnalysisChoice(false, false);

    @BeforeEach
    void setUp() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        context.events().subscribe(StatusMessageEvent.class, event -> notices.add(event.getPayload().getText()));
        context.setNavigator((AppContext.Navigator) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AppContext.Navigator.class}, (proxy, method, args) -> {
                    if (method.getName().equals("showJob")) {
                        shownJobs.add((String) args[0]);
                    }
                    return null;
                }));
        SwingUtilities.invokeAndWait(() -> explorer = new Explorer(context));
    }

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    private void useFakes() throws Exception {
        SwingUtilities.invokeAndWait(() -> explorer.analyses(analyses, (parent, title, text, action) -> {
            confirmations.add(text);
            actions.add(action);
            return answer;
        }));
    }

    private List<String> labels(Explorer.Item item) throws Exception {
        List<String> labels = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> Arrays.stream(explorer.menu(item).getComponents())
                .map(c -> ((JMenuItem) c).getText()).forEach(labels::add));
        return labels;
    }

    private JMenuItem entry(Explorer.Item item, String label) throws Exception {
        JMenuItem[] found = new JMenuItem[1];
        SwingUtilities.invokeAndWait(() -> {
            JPopupMenu menu = explorer.menu(item);
            found[0] = Arrays.stream(menu.getComponents()).map(JMenuItem.class::cast)
                    .filter(c -> c.getText().equals(label)).findFirst().orElseThrow();
        });
        return found[0];
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("Zeitlimit").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    void entriesFollowIndexingInTheRightOrder() throws Exception {
        useFakes();
        // „Indexieren“ zuerst, „Seite indexieren“ nur an Seiten mit Unterseiten (Angelo, 20.11.2026); dahinter
        // „Favorit“ und „Änderungen nachholen“, auch an der Quelle (Angelo, 16.11.2026). „Ausschließen“ an Spaces und
        // Seiten, nicht an der Quelle (Angelo, 26.11.2026). „Umbenennen“ nur an der Quelle (Angelo, 26.11.2026).
        assertThat(labels(LEAF)).containsExactly("Öffnen", Explorer.INDEX, Explorer.EXCLUDE, Explorer.FAVORITE,
                Explorer.REFRESH, Explorer.ANALYZE, Explorer.CHECK);
        assertThat(labels(PARENT)).containsExactly("Öffnen", Explorer.INDEX, Explorer.PAGE_ONLY, Explorer.EXCLUDE,
                Explorer.FAVORITE, Explorer.REFRESH, Explorer.ANALYZE, Explorer.ANALYZE_PAGE, Explorer.CHECK,
                Explorer.CHECK_PAGE);
        assertThat(labels(UNKNOWN)).containsExactly("Öffnen", Explorer.INDEX, Explorer.PAGE_ONLY, Explorer.EXCLUDE,
                Explorer.FAVORITE, Explorer.REFRESH, Explorer.ANALYZE, Explorer.ANALYZE_PAGE, Explorer.CHECK,
                Explorer.CHECK_PAGE);
        assertThat(labels(SPACE)).containsExactly(Explorer.INDEX, Explorer.EXCLUDE, Explorer.FAVORITE,
                Explorer.REFRESH, Explorer.ANALYZE, Explorer.CHECK);
        assertThat(labels(SOURCE)).containsExactly(Explorer.INDEX, Explorer.FAVORITE, Explorer.REFRESH,
                Explorer.RENAME, Explorer.ANALYZE, Explorer.CHECK);
        assertThat(entry(SOURCE, Explorer.RENAME).getToolTipText()).isEqualTo(Explorer.RENAME_TIP);
        assertThat(labels(new Explorer.MoreItem("c"))).isEmpty();
        assertThat(labels(new Explorer.Note(Explorer.LOADING))).isEmpty();
        assertThat(entry(PARENT, Explorer.ANALYZE).isEnabled()).isTrue();
        assertThat(entry(PARENT, Explorer.ANALYZE).getToolTipText()).isEqualTo(Explorer.ANALYZE_TIP);
    }

    @Test
    void scopesFollowTheChosenNode() {
        assertThat(Explorer.analysisScope(LEAF, false)).isEqualTo(new Scope.SinglePage("wiki", "OPS", "p1"));
        assertThat(Explorer.analysisScope(PARENT, false)).isEqualTo(new Scope.Subtree("wiki", "OPS", "p2"));
        assertThat(Explorer.analysisScope(PARENT, true)).isEqualTo(new Scope.SinglePage("wiki", "OPS", "p2"));
        assertThat(Explorer.analysisScope(SPACE, false)).isEqualTo(new Scope.Space("wiki", "OPS"));
        assertThat(Explorer.analysisScope(SOURCE, false)).isEqualTo(new Scope.Source("wiki"));
        assertThat(Explorer.analysisScope(new Explorer.MoreItem("c"), false)).isNull();
    }

    @Test
    void entriesAreDisabledWithAReasonWhenAnalysisIsUnavailable() throws Exception {
        // Ohne gestartete Laufzeit (echte Umsetzung über Workspace).
        JMenuItem withoutRuntime = entry(PARENT, Explorer.ANALYZE);
        assertThat(withoutRuntime.isEnabled()).isFalse();
        assertThat(withoutRuntime.getToolTipText()).isEqualTo(Explorer.ANALYZE_NOT_STARTED);

        analyses.unavailable = Explorer.ANALYZE_UNAVAILABLE;
        useFakes();
        for (Explorer.Item item : List.of(LEAF, PARENT, SPACE, SOURCE)) {
            JMenuItem analyze = entry(item, Explorer.ANALYZE);
            assertThat(analyze.isEnabled()).isFalse();
            assertThat(analyze.getToolTipText()).isEqualTo(Explorer.ANALYZE_UNAVAILABLE);
        }
        JMenuItem only = entry(PARENT, Explorer.ANALYZE_PAGE);
        assertThat(only.isEnabled()).isFalse();
        assertThat(only.getToolTipText()).isEqualTo(Explorer.ANALYZE_UNAVAILABLE);
        assertThat(entry(PARENT, Explorer.INDEX).isEnabled()).isTrue();
    }

    @Test
    void checkingStartsAtOnceForOnePageAndAsksWithYesOrNoForMore() throws Exception {
        useFakes();
        List<String> questions = new CopyOnWriteArrayList<>();
        boolean[] yes = {false};
        SwingUtilities.invokeAndWait(() -> explorer.checkConfirmation((parent, title, question) -> {
            assertThat(title).isEqualTo(Explorer.CHECK);
            questions.add(question);
            return yes[0];
        }));

        clickLater(LEAF, Explorer.CHECK);
        await(() -> !shownJobs.isEmpty());
        assertThat(analyses.checks).containsExactly(new Check(new Scope.SinglePage("wiki", "OPS", "p1"),
                StatementAnalysisJobs.CheckOptions.of(Explorer.ANALYSIS_LIMIT,
                        com.aresstack.spaceharvester.application.configuration.CheckSettings.DEFAULT)));
        assertThat(questions).as("eine Seite ohne Rückfrage").isEmpty();
        clickLater(PARENT, Explorer.CHECK_PAGE);
        await(() -> analyses.checks.size() == 2);
        assertThat(analyses.checks.get(1).scope()).isEqualTo(new Scope.SinglePage("wiki", "OPS", "p2"));

        analyses.estimate = scope -> new StatementAnalysisJobs.Estimate(scope, 12, false, false);
        clickLater(SPACE, Explorer.CHECK);
        await(() -> !questions.isEmpty());
        assertThat(questions.getFirst()).startsWith("Die Aussagen von 12 Seiten wirklich prüfen?")
                .contains("nur Seiten mit KI-Freigabe");
        assertThat(analyses.checks).as("„Nein“ startet nichts").hasSize(2);

        yes[0] = true;
        analyses.estimate = scope -> new StatementAnalysisJobs.Estimate(scope, Explorer.ANALYSIS_LIMIT + 1, true,
                false);
        clickLater(SPACE, Explorer.CHECK);
        await(() -> analyses.checks.size() == 3);
        assertThat(questions.getLast()).startsWith("Die Aussagen von mehr als ").contains("wirklich prüfen?");
        assertThat(analyses.checks.get(2)).isEqualTo(new Check(new Scope.Space("wiki", "OPS"),
                StatementAnalysisJobs.CheckOptions.of(0,
                        com.aresstack.spaceharvester.application.configuration.CheckSettings.DEFAULT)));
        assertThat(analyses.started).as("nie eine Analyse als Folge").isEmpty();
    }

    @Test
    void checkingIsDisabledWithItsOwnReasonWithoutAModel() throws Exception {
        analyses.checkUnavailable = Explorer.CHECK_NO_MODEL;
        useFakes();
        JMenuItem check = entry(PARENT, Explorer.CHECK);
        assertThat(check.isEnabled()).isFalse();
        assertThat(check.getToolTipText()).isEqualTo(Explorer.CHECK_NO_MODEL);
        assertThat(entry(PARENT, Explorer.CHECK_PAGE).isEnabled()).isFalse();
        assertThat(entry(PARENT, Explorer.ANALYZE).isEnabled()).as("Analysieren braucht kein Chat-Modell").isTrue();
    }

    @Test
    void aSinglePageStartsWithoutDialog() throws Exception {
        useFakes();
        clickLater(LEAF, Explorer.ANALYZE);
        await(() -> !shownJobs.isEmpty());
        assertThat(analyses.started).containsExactly(new Started(new Scope.SinglePage("wiki", "OPS", "p1"),
                StatementAnalysisJobs.Options.DEFAULT.withAnalyzer(com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER)));
        assertThat(analyses.estimated).isEmpty();
        assertThat(confirmations).isEmpty();
        assertThat(shownJobs).containsExactly("job-1");

        // „Nur diese Seite analysieren“ an einer Elternseite: ebenfalls ohne Dialog, ohne Kinder.
        clickLater(PARENT, Explorer.ANALYZE_PAGE);
        await(() -> shownJobs.size() == 2);
        assertThat(analyses.started.get(1).scope()).isEqualTo(new Scope.SinglePage("wiki", "OPS", "p2"));
        assertThat(confirmations).isEmpty();
    }

    @Test
    void aSubtreeIsCountedThenConfirmedThenStartedWithTheLimit() throws Exception {
        useFakes();
        analyses.estimate = scope -> new StatementAnalysisJobs.Estimate(scope, 42, false, false);
        answer = new Explorer.AnalysisChoice(true, false);
        clickLater(PARENT, Explorer.ANALYZE);
        await(() -> !shownJobs.isEmpty());
        assertThat(analyses.estimated).containsExactly(new Scope.Subtree("wiki", "OPS", "p2"));
        assertThat(confirmations).singleElement().asString().startsWith("42 Seiten wirklich analysieren?")
                .contains("Eltern");
        assertThat(actions).containsExactly(Explorer.ANALYZE);
        assertThat(analyses.started).containsExactly(new Started(new Scope.Subtree("wiki", "OPS", "p2"),
                new StatementAnalysisJobs.Options(true, Explorer.ANALYSIS_LIMIT).withAnalyzer(com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER)));
    }

    @Test
    void theTickedStageStartsTheJobWithTheSimplificationOfTheSettings() throws Exception {
        useFakes();
        analyses.estimate = scope -> new StatementAnalysisJobs.Estimate(scope, 7, false, false);
        answer = new Explorer.AnalysisChoice(false, true);
        clickLater(SPACE, Explorer.ANALYZE);
        await(() -> !shownJobs.isEmpty());
        // Ohne Angabe in der Datei gilt die Vorgabe des Core: „nur wo nötig“, zwei Abschnitte zugleich.
        assertThat(analyses.started).containsExactly(new Started(new Scope.Space("wiki", "OPS"),
                new StatementAnalysisJobs.Options(false, Explorer.ANALYSIS_LIMIT,
                        new com.aresstack.spaceharvester.application.knowledge.StatementAnalysis
                                .SimplificationRequest(com.aresstack.spaceharvester.application.configuration
                                .SimplificationSettings.Mode.WHERE_NEEDED, 2)).withAnalyzer(
                        com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER)));
    }

    /** Fahrplan A3: Mit dem KI-Analysator sagt der Dialog, dass die Sätze an den KI-Dienst gehen. */
    @Test
    void theDialogTextFollowsTheChosenAnalyzer() {
        String question = Explorer.analysisQuestion(new StatementAnalysisJobs.Estimate(new Scope.Space("wiki", "OPS"),
                12, false, false), "X");
        assertThat(Explorer.analysisText(question,
                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER))
                .contains("läuft lokal").doesNotContain("KI-Dienst");
        assertThat(Explorer.analysisText(question,
                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL))
                .startsWith("12 Seiten wirklich analysieren?").contains("eingestellten KI-Dienst",
                        "nur Seiten mit KI-Freigabe", "ausgelassen").doesNotContain("läuft lokal");
    }

    @Test
    void theAnalyzerChosenInTheDialogStartsTheJob() throws Exception {
        useFakes();
        analyses.estimate = scope -> new StatementAnalysisJobs.Estimate(scope, 7, false, false);
        answer = new Explorer.AnalysisChoice(false, false,
                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL);
        clickLater(SPACE, Explorer.ANALYZE);
        await(() -> !shownJobs.isEmpty());
        assertThat(analyses.started).containsExactly(new Started(new Scope.Space("wiki", "OPS"),
                new StatementAnalysisJobs.Options(false, Explorer.ANALYSIS_LIMIT, null,
                        com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL)));
    }

    @Test
    void beyondTheLimitTheUserConfirmsAllPages() throws Exception {
        useFakes();
        analyses.estimate = scope -> new StatementAnalysisJobs.Estimate(scope, Explorer.ANALYSIS_LIMIT, true, false);
        clickLater(SOURCE, Explorer.ANALYZE);
        await(() -> !shownJobs.isEmpty());
        assertThat(confirmations).singleElement().asString().startsWith("Wirklich mehr als 500 Seiten analysieren?");
        assertThat(actions).containsExactly("Alle analysieren");
        assertThat(analyses.started).containsExactly(new Started(new Scope.Source("wiki"),
                new StatementAnalysisJobs.Options(false, 0).withAnalyzer(com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER)));
    }

    @Test
    void cancellingTheDialogStartsNothing() throws Exception {
        useFakes();
        analyses.estimate = scope -> new StatementAnalysisJobs.Estimate(scope, 3, false, true);
        answer = null;
        clickLater(SPACE, Explorer.ANALYZE);
        await(() -> !confirmations.isEmpty());
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(analyses.estimated).containsExactly(new Scope.Space("wiki", "OPS"));
        assertThat(analyses.started).isEmpty();
        assertThat(notices).doesNotContain("Analyse wird gestartet …");
    }

    @Test
    void aFailedEstimateIsReportedInTheStatusLine() throws Exception {
        useFakes();
        analyses.estimate = scope -> {
            throw new IllegalStateException("kaputt");
        };
        clickLater(SPACE, Explorer.ANALYZE);
        await(() -> notices.stream().anyMatch(n -> n.startsWith("Analyse ließ sich nicht starten")));
        assertThat(confirmations).isEmpty();
        assertThat(analyses.started).isEmpty();
    }

    @Test
    void theQuestionNamesExactExceededAndEstimatedCounts() {
        Scope scope = new Scope.Space("wiki", "OPS");
        assertThat(Explorer.analysisQuestion(new StatementAnalysisJobs.Estimate(scope, 1, false, false), "X"))
                .startsWith("1 Seite wirklich analysieren?").contains("Umfang: X.").contains("heruntergeladen")
                .doesNotContain("Schätzung").doesNotContain("Sicherheitsgrenze");
        assertThat(Explorer.analysisQuestion(new StatementAnalysisJobs.Estimate(scope, 12, false, false), "X"))
                .startsWith("12 Seiten wirklich analysieren?");
        assertThat(Explorer.analysisQuestion(new StatementAnalysisJobs.Estimate(scope, 500, true, false), "X"))
                .startsWith("Wirklich mehr als 500 Seiten analysieren?").contains("Sicherheitsgrenze von 500 Seiten")
                .contains("alle Seiten");
        assertThat(Explorer.analysisQuestion(new StatementAnalysisJobs.Estimate(scope, 7, false, true), "X"))
                .startsWith("Etwa 7 Seiten wirklich analysieren? (Schätzung)").contains("nicht vollständig");
        assertThat(Explorer.analysisQuestion(new StatementAnalysisJobs.Estimate(scope, 0, false, true), "X"))
                .startsWith("Umfang noch unbekannt (Schätzung)");
        assertThat(Explorer.analysisQuestion(new StatementAnalysisJobs.Estimate(scope, 500, true, true), "X"))
                .startsWith("Wirklich mehr als 500 Seiten analysieren?");
        assertThat(Explorer.analysisWhere(PARENT)).contains("Eltern").contains("samt allen Unterseiten");
        assertThat(Explorer.analysisWhere(SOURCE)).contains("Wiki");
    }

    private void clickLater(Explorer.Item item, String label) throws Exception {
        JMenuItem entry = entry(item, label);
        SwingUtilities.invokeAndWait(entry::doClick);
    }

    private record Started(Scope scope, StatementAnalysisJobs.Options options) {
    }

    private record Check(Scope scope, StatementAnalysisJobs.CheckOptions options) {
    }

    private static final class FakeAnalyses implements Explorer.Analyses {
        volatile String unavailable;
        volatile java.util.function.Function<Scope, StatementAnalysisJobs.Estimate> estimate =
                scope -> new StatementAnalysisJobs.Estimate(scope, 1, false, false);
        final List<Scope> estimated = new CopyOnWriteArrayList<>();
        final List<Started> started = new CopyOnWriteArrayList<>();
        volatile String checkUnavailable;
        final List<Check> checks = new CopyOnWriteArrayList<>();

        @Override
        public String unavailable() {
            return unavailable;
        }

        @Override
        public String checkUnavailable() {
            return checkUnavailable;
        }

        @Override
        public JobStatus startCheck(Scope scope, StatementAnalysisJobs.CheckOptions options) {
            checks.add(new Check(scope, options));
            return new JobStatus("check-" + checks.size(), JobType.STATEMENT_CHECK, scope.sourceId(),
                    scope.spaceKey(), "local", JobState.QUEUED, new JobProgress(0, 0, 0, 0), null, false,
                    Instant.now(), null, null);
        }

        @Override
        public StatementAnalysisJobs.Estimate estimate(Scope scope, int limit) {
            assertThat(limit).isEqualTo(Explorer.ANALYSIS_LIMIT);
            estimated.add(scope);
            return estimate.apply(scope);
        }

        @Override
        public JobStatus start(Scope scope, StatementAnalysisJobs.Options options) {
            started.add(new Started(scope, options));
            return new JobStatus("job-" + started.size(), JobType.STATEMENT_ANALYSIS, scope.sourceId(),
                    scope.spaceKey(), "local", JobState.QUEUED, new JobProgress(0, 0, 0, 0), null, false,
                    Instant.now(), null, null);
        }
    }
}
