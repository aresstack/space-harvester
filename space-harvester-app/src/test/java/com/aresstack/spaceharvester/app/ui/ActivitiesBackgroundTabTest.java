package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.controls.BackgroundWait;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.observe.Subscription;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Reiter „Hintergrund“ der Auftragsverwaltung (Angelo 21.11.2026): jeder Auftrag steht in genau einem Reiter, je
 * Hintergrundgruppe eine Zeile mit ihren Unteraufträgen darunter. Nur erfundene Quellen und Titel.
 */
class ActivitiesBackgroundTabTest {

    private static final Instant T = Instant.parse("2026-11-21T18:00:00Z");

    static JobStatus job(String id, JobType type, String space, JobState state, Map<String, String> parameters,
                         long minute, JobProgress progress) {
        return new JobStatus(id, type, "wiki", space, "space-harvester-core", state, progress, null, false,
                T.plusSeconds(60 * minute), state == JobState.QUEUED ? null : T.plusSeconds(60 * minute), null, null,
                null, parameters, false, false, null);
    }

    static Map<String, String> group(String id, String title) {
        return Map.of(BackgroundJobs.GROUP, id, BackgroundJobs.TITLE, title, BackgroundJobs.KIND, "pages");
    }

    static List<JobStatus> sample() {
        return List.of(
                job("s1", JobType.SYNC, "ALPHA", JobState.RUNNING, Map.of(), 1, new JobProgress(40, 12, 12, 0)),
                job("e1", JobType.EXPORT, "BETA", JobState.COMPLETED, Map.of(), 2, new JobProgress(8, 8, 8, 0)),
                job("b1", JobType.BACKGROUND, "ALPHA", JobState.RUNNING, group("g1", "Nachbarseiten laden"), 3,
                        new JobProgress(20, 5, 5, 0)),
                job("b2", JobType.BACKGROUND, "BETA", JobState.QUEUED, group("g1", "Nachbarseiten laden"), 3,
                        new JobProgress(0, 0, 0, 0)),
                job("b3", JobType.BACKGROUND, "GAMMA", JobState.COMPLETED, group("g2", "Bilder beschreiben"), 0,
                        new JobProgress(14, 14, 13, 1)));
    }

    @Test
    void everyJobStandsInExactlyOneTab() {
        List<JobStatus> jobs = sample();

        assertThat(ActivitiesView.visible(jobs, false)).extracting(JobStatus::id).containsExactly("s1", "e1");
        List<JobStatus> background = ActivitiesView.visible(jobs, true);
        // „Bilder beschreiben“ hat nur einen Teil: keine Klammer, die Zeile steht für sich, nach Alter einsortiert.
        assertThat(background).extracting(JobStatus::id).containsExactly(ActivitiesView.GROUP_PREFIX + "g1", "b3",
                "b1", "b2");
        // Sortiert wie Exporte mit Bildern: jede Gruppe mit ihren Unteraufträgen darunter.
        assertThat(ActivitiesView.grouped(background, java.util.Set.of())).extracting(JobStatus::id)
                .containsExactly(ActivitiesView.GROUP_PREFIX + "g1", "b1", "b2", "b3");
    }

    @Test
    void aGroupRowSumsItsPartsAndRunsWhileOneRuns() {
        List<JobStatus> rows = ActivitiesView.groupRows(ActivitiesView.tabJobs(sample(), true));

        JobStatus neighbours = rows.get(0);
        assertThat(ActivitiesView.groupRow(neighbours)).isTrue();
        assertThat(Texts.type(neighbours)).isEqualTo("Nachbarseiten laden");
        assertThat(neighbours.state()).isEqualTo(JobState.RUNNING);
        assertThat(neighbours.progress()).isEqualTo(new JobProgress(20, 5, 5, 0));
        assertThat(neighbours.lifecycle().actions()).contains(JobAction.PAUSE, JobAction.CANCEL);
        // Die Gruppe „Bilder beschreiben“ hat nur einen Teil und darum keine eigene Zeile.
        assertThat(rows).hasSize(1);
    }

    /**
     * Angelo 24.11.2026: „Da stehen nur die Quellen, anstatt dass da in der Klammer steht, was es ist.“ Die Klammer
     * nennt in der Spalte „Quelle“ ihre Art; die Teile darunter nennen ihre Quelle.
     */
    @Test
    void theBracketSaysWhatItIsAndItsPartsNameTheirSource() {
        List<JobStatus> rows = ActivitiesView.groupRows(ActivitiesView.tabJobs(sample(), true));

        assertThat(ActivitiesView.source(rows.get(0))).isEqualTo("Nachbarseiten laden");
        assertThat(ActivitiesView.source(sample().get(2))).isEqualTo("wiki");
    }

    /**
     * Angelo 22.11.2026: „Hintergrundfüllung“ sagte nicht, was passiert, und aufgeklappt stand darunter wieder nur
     * „Hintergrundfüllung“. Ein einzelner Teil steht ohne Klammer und heißt nach dem, was er tut; neueste zuerst.
     */
    @Test
    void aSinglePartStandsWithoutABracketAndSaysWhatItDoes() {
        Map<String, String> fill = Map.of(BackgroundJobs.GROUP, "g-fill", BackgroundJobs.TITLE, "Hintergrundfüllung",
                BackgroundJobs.KIND, "fill");
        Map<String, String> older = Map.of(BackgroundJobs.GROUP, "g-old", BackgroundJobs.TITLE, "Hintergrundfüllung",
                BackgroundJobs.KIND, "fill");
        List<JobStatus> jobs = List.of(
                job("f1", JobType.BACKGROUND, "OMEGA", JobState.RUNNING, fill, 5, new JobProgress(9, 3, 3, 0)),
                job("f0", JobType.BACKGROUND, "SIGMA", JobState.COMPLETED, older, 1, new JobProgress(2, 2, 2, 0)),
                job("b1", JobType.BACKGROUND, "ALPHA", JobState.RUNNING, group("g1", "Nachbarseiten laden"), 3,
                        new JobProgress(20, 5, 5, 0)),
                job("b2", JobType.BACKGROUND, "BETA", JobState.QUEUED, group("g1", "Nachbarseiten laden"), 3,
                        JobProgress.NONE));

        List<JobStatus> shown = ActivitiesView.grouped(ActivitiesView.visible(jobs, true), java.util.Set.of());

        assertThat(shown).extracting(JobStatus::id).containsExactly("f1", ActivitiesView.GROUP_PREFIX + "g1", "b1",
                "b2", "f0");
        assertThat(shown.get(0)).satisfies(row -> {
            assertThat(ActivitiesView.groupRow(row)).isFalse();
            assertThat(Texts.type(row)).isEqualTo("Fehlende und veraltete Seiten laden");
        });
        assertThat(Texts.type(shown.get(1))).isEqualTo("Nachbarseiten laden");
    }

    /** Unter der Klammer einer gemischten Gruppe nennt jede Zeile ihre Stufe, die Klammer das Ziel. */
    @Test
    void underAMixedBracketEachRowNamesItsStep() {
        Map<String, String> statements = Map.of(BackgroundJobs.GROUP, "g-w5", BackgroundJobs.TITLE, "Wissensgraph",
                BackgroundJobs.KIND, "statements", BackgroundJobs.STEP_TITLE, "Aussagenanalyse");
        Map<String, String> closing = Map.of(BackgroundJobs.GROUP, "g-w5", BackgroundJobs.TITLE, "Wissensgraph",
                BackgroundJobs.KIND, "knowledge-graph", BackgroundJobs.STEP_TITLE, "Wissensgraph");
        List<JobStatus> shown = ActivitiesView.grouped(ActivitiesView.visible(List.of(
                job("k1", JobType.BACKGROUND, "DEV", JobState.RUNNING, statements, 1, new JobProgress(12, 4, 4, 0)),
                job("k2", JobType.BACKGROUND, "DEV", JobState.QUEUED, closing, 1, JobProgress.NONE)), true),
                java.util.Set.of());

        assertThat(shown).extracting(Texts::type).containsExactly("Wissensgraph", "Aussagenanalyse", "Wissensgraph");
    }

    /**
     * W5: „Wissensgraph erzeugen“ steht im Reiter „Hintergrund“ als eine Gruppe „Wissensgraph“ aus der Aussagenanalyse
     * und dem Abschlussteil; sie läuft, solange ein Teil läuft, und lässt sich als Ganzes abbrechen.
     */
    @Test
    void aKnowledgeGraphGroupIsOneRowNamedAfterTheGraph() {
        Map<String, String> statements = Map.of(BackgroundJobs.GROUP, "g-w5", BackgroundJobs.TITLE, "Wissensgraph",
                BackgroundJobs.KIND, "statements", BackgroundJobs.GROUP_KIND, "knowledge-graph",
                BackgroundJobs.STEP_TITLE, "Aussagenanalyse");
        Map<String, String> closing = Map.of(BackgroundJobs.GROUP, "g-w5", BackgroundJobs.TITLE, "Wissensgraph",
                BackgroundJobs.KIND, "knowledge-graph", BackgroundJobs.GROUP_KIND, "knowledge-graph",
                BackgroundJobs.STEP_TITLE, "Wissensgraph");
        List<JobStatus> rows = ActivitiesView.groupRows(ActivitiesView.tabJobs(List.of(
                job("k1", JobType.BACKGROUND, "DEV", JobState.RUNNING, statements, 1, new JobProgress(12, 4, 4, 0)),
                job("k2", JobType.BACKGROUND, "DEV", JobState.QUEUED, closing, 1, JobProgress.NONE)), true));

        assertThat(rows).hasSize(1);
        assertThat(ActivitiesView.groupRow(rows.get(0))).isTrue();
        assertThat(Texts.type(rows.get(0))).isEqualTo("Wissensgraph");
        assertThat(rows.get(0).state()).isEqualTo(JobState.RUNNING);
        assertThat(rows.get(0).lifecycle().actions()).contains(JobAction.CANCEL);
    }

    /** Die Suchleiste unten findet auch in der Auftragsliste und färbt dort jede Spalte, auch Art und Zustand. */
    @Test
    void theSearchBarMarksItsHitsInTheJobList() throws Exception {
        Path home = Files.createTempDirectory("bgfind");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context, null);
                view.tabs.select(ActivitiesView.JOBS_TAB);
                view.showJobsForTest(sample());
                javax.swing.JTable table = view.table();
                FindBar bar = new FindBar(view::component);
                for (int column : new int[] {0, 1, 2}) {
                    String shown = ((javax.swing.JLabel) table.prepareRenderer(table.getCellRenderer(0, column), 0,
                            column)).getText();
                    assertThat(bar.search(shown)).as("Spalte " + column).isPositive();
                    java.awt.Component hit = table.prepareRenderer(table.getCellRenderer(0, column), 0, column);
                    assertThat(hit.getBackground()).as("Spalte " + column).isIn(ReaderFind.CURRENT_HIT,
                            ReaderFind.HIT);
                    assertThat(hit.isOpaque()).isTrue();
                    // Die Nachbarzeile derselben Spalte bleibt, wie sie war: ein Renderer zeichnet alle Zellen.
                    java.awt.Component other = table.prepareRenderer(table.getCellRenderer(1, column), 1, column);
                    if (!shown.equals(((javax.swing.JLabel) other).getText())) {
                        assertThat(other.getBackground()).isNotIn(ReaderFind.CURRENT_HIT, ReaderFind.HIT);
                    }
                }
                // Ein gewählter Treffer lässt weder Markierung noch Auswahlfarbe in der Spalte zurück.
                String state = ((javax.swing.JLabel) table.prepareRenderer(table.getCellRenderer(0, 2), 0, 2))
                        .getText();
                bar.search(state);
                table.getCellRenderer(0, 2).getTableCellRendererComponent(table, table.getValueAt(0, 2), true,
                        false, 0, 2);
                bar.search("");
                for (int row = 0; row < table.getRowCount(); row++) {
                    assertThat(table.prepareRenderer(table.getCellRenderer(row, 2), row, 2).getBackground())
                            .as("Zeile " + row).isEqualTo(table.getBackground());
                }
            });
        } finally {
            context.shutdown();
        }
    }

    /** Eine Meldung ohne gewählten Auftrag (z. B. „Nicht übernommen“) öffnet den Detailbereich, ihr ✕ schließt ihn. */
    @Test
    void aMessageWithoutASelectionOpensTheDetailAreaAndClosesItAgain() throws Exception {
        Path home = Files.createTempDirectory("bgfeedback");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            ActivitiesView[] view = new ActivitiesView[1];
            SwingUtilities.invokeAndWait(() -> {
                view[0] = new ActivitiesView(context, null);
                view[0].showJobsForTest(sample());
                assertThat(view[0].detailsShown()).isFalse();
                view[0].feedbackForTest().warning("Nicht übernommen", "Erlaubt sind 1 bis 16.");
            });
            // Das Erscheinen meldet AWT über die Ereignisschlange.
            SwingUtilities.invokeAndWait(() -> { });
            SwingUtilities.invokeAndWait(() -> {
                assertThat(view[0].detailsShown()).isTrue();
                view[0].feedbackForTest().clear();
            });
            SwingUtilities.invokeAndWait(() -> { });
            SwingUtilities.invokeAndWait(() -> assertThat(view[0].detailsShown()).isFalse());
        } finally {
            context.shutdown();
        }
    }

    @Test
    void theViewShowsTheTabsAndCountsRunningBackgroundJobs() throws Exception {
        Path home = Files.createTempDirectory("bgtab");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context, null);
                view.tabs.select(ActivitiesView.JOBS_TAB);
                view.showJobsForTest(sample());
                assertThat(view.rowsForTest()).extracting(JobStatus::id).containsExactly("s1", "e1");
                assertThat(view.tabs.count(ActivitiesView.BACKGROUND_TAB)).isEqualTo(1);
                assertThat(view.tabs.button(ActivitiesView.BACKGROUND_TAB).getToolTipText())
                        .isEqualTo("1 Hintergrundauftrag läuft");

                view.tabs.select(ActivitiesView.BACKGROUND_TAB);
                assertThat(view.backgroundTab()).isTrue();
                // Die Gruppe mit b1 und b2; „Bilder beschreiben“ hat nur einen Teil und darum keine Klammer.
                assertThat(view.rowsForTest()).hasSize(4);
                assertThat(view.pauseAll.getToolTipText()).contains("Hintergrundaufträge");
                view.tabs.select(ActivitiesView.JOBS_TAB);
            });
        } finally {
            context.shutdown();
        }
    }

    @Test
    void inTheSidebarTheActionsOfTheSelectedRowStandInTheFootAndTheHeadKeepsItsMenu() throws Exception {
        Path home = Files.createTempDirectory("bgtab320");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context, null);
                view.tabs.select(ActivitiesView.BACKGROUND_TAB);
                view.showJobsForTest(sample());
                javax.swing.JComponent root = view.component();
                root.setSize(320, 520);
                layout(root);
                layout(root);
                assertThat(view.narrow()).isTrue();
                // Fußzeile: Lupe, Auf-/Zuklappen und die Aktionen der gewählten Zeile; ohne Auswahl gesperrt.
                assertThat(SwingUtilities.isDescendingFrom(view.selectedRow(), view.findBar())).isTrue();
                assertThat(view.selectedButtons()).hasSize(ActivitiesView.SELECTED_SLOTS)
                        .noneMatch(java.awt.Component::isEnabled)
                        .allMatch(button -> ActivitiesView.NO_JOB_SELECTED.equals(button.getToolTipText()));
                java.awt.Rectangle visible = new java.awt.Rectangle(0, 0, 320, 520);
                for (javax.swing.JComponent part : view.selectedButtons()) {
                    assertThat(visible.contains(SwingUtilities.convertRectangle(part.getParent(), part.getBounds(),
                            root))).as("Knopf in der Fußzeile sichtbar").isTrue();
                }
                // „Alle anhalten“ und „Alle abbrechen“ stehen schmal im Menü, ebenso „Seitenleiste einklappen“.
                assertThat(SwingUtilities.isDescendingFrom(view.pauseAll, root)).isFalse();
                List<String> menu = new java.util.ArrayList<>();
                for (java.awt.Component item : view.moreJobsMenu().getComponents()) {
                    if (item instanceof javax.swing.JMenuItem entry) {
                        menu.add(entry.getText());
                    }
                }
                assertThat(menu).contains(view.pauseAll.getText(), view.cancelAll.getText(),
                        com.aresstack.spaceharvester.app.ui.content.ChatSidebar.COLLAPSE);
                // Seit der Umschalter nur Symbole zeigt (Angelo 27.11.2026), hat der Besen auch schmal Platz im Kopf
                // und steht darum nicht zusätzlich im Menü.
                assertThat(view.deleteFinished.isVisible()).isTrue();
                assertThat(menu).doesNotContain(ActivitiesView.DELETE_FINISHED);
                // Kopfzeile: an der Mindestbreite bleiben Vergrößern und das Dreieck ganz sichtbar.
                for (javax.swing.JComponent part : List.<javax.swing.JComponent>of(view.enlarge, view.moreJobs)) {
                    assertThat(visible.contains(SwingUtilities.convertRectangle(part.getParent(), part.getBounds(),
                            root))).as("Werkzeug in der Kopfzeile sichtbar").isTrue();
                    assertThat(part.getWidth()).isGreaterThanOrEqualTo(part.getPreferredSize().width);
                }
                // Eine gewählte Zeile schaltet ihre Aktionen frei.
                // Eine Zeile schaltet ihre Aktionen frei (ohne laufenden Dienst leert die Auswahl selbst die Liste).
                view.showSelectedActions(1);
                assertThat(view.selectedButtons()).anyMatch(java.awt.Component::isEnabled)
                        .noneMatch(button -> ActivitiesView.NO_JOB_SELECTED.equals(button.getToolTipText()));
                view.tabs.select(ActivitiesView.JOBS_TAB);
            });
        } finally {
            context.shutdown();
        }
    }

    @Test
    void at900PixelsTheTabsWithTheirCountAndEveryToolStayVisible() throws Exception {
        Path home = Files.createTempDirectory("bgtab900");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context, null);
                view.tabs.select(ActivitiesView.BACKGROUND_TAB);
                view.showJobsForTest(sample());
                javax.swing.JComponent root = view.component();
                root.setSize(900, 520);
                layout(root);
                java.awt.Rectangle visible = new java.awt.Rectangle(0, 0, 900, 520);
                for (javax.swing.JComponent part : List.<javax.swing.JComponent>of(
                        view.tabs.button(ActivitiesView.JOBS_TAB), view.tabs.button(ActivitiesView.BACKGROUND_TAB),
                        view.deleteFinished, view.moreJobs)) {
                    java.awt.Rectangle bounds = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(),
                            root);
                    assertThat(part.isShowing() || part.isVisible()).isTrue();
                    assertThat(visible.contains(bounds)).as(part.getClass().getSimpleName() + " " + bounds).isTrue();
                    // Nicht vom Nachbarn verdeckt: die volle Breite, die das Teil braucht.
                    assertThat(bounds.width).as("Breite " + part.getClass().getSimpleName())
                            .isGreaterThanOrEqualTo(Math.min(part.getPreferredSize().width, part.getMinimumSize()
                                    .width));
                }
                // Die Wippe steht links in der Kopfzeile, einzeilig, beide Hälften gleich breit und ganz zu lesen.
                javax.swing.AbstractButton jobs = view.tabs.button(ActivitiesView.JOBS_TAB);
                javax.swing.AbstractButton background = view.tabs.button(ActivitiesView.BACKGROUND_TAB);
                assertThat(jobs.getY()).isEqualTo(background.getY());
                assertThat(jobs.getWidth()).isEqualTo(background.getWidth());
                assertThat(background.getWidth()).isGreaterThanOrEqualTo(background.getPreferredSize().width);
                assertThat(background.getX()).isEqualTo(jobs.getX() + jobs.getWidth());
                // Die Aktionen für alle Aufträge und das Auf- und Zuklappen stehen in der Werkzeugleiste unten.
                javax.swing.JComponent tools = view.tools();
                for (javax.swing.JComponent part : List.<javax.swing.JComponent>of(view.expandAll, view.collapseAll,
                        view.pauseAll, view.cancelAll)) {
                    // Die Aufträge bringen ihre Fußzeile mit der Lupe selbst mit (Seitenleiste).
                    assertThat(SwingUtilities.isDescendingFrom(part, view.findBar())).isTrue();
                    assertThat(SwingUtilities.isDescendingFrom(part, tools)).isTrue();
                }
                tools.setSize(600, 30);
                layout(tools);
                assertThat(view.expandAll.getParent().getX()).isZero();
                assertThat(view.pauseAll.getParent().getX()).isGreaterThan(view.collapseAll.getParent().getWidth());
                view.tabs.select(ActivitiesView.JOBS_TAB);
            });
        } finally {
            context.shutdown();
        }
    }

    /**
     * Der Detailbereich steht nur, solange ein Auftrag gewählt ist (Angelo 22.11.2026): Ohne Auswahl gehört die
     * ganze Fläche der Liste; ein Klick in die leere Fläche unter den Zeilen hebt die Auswahl wieder auf.
     */
    @Test
    void theDetailAreaOpensWithASelectionAndAClickIntoTheEmptyAreaClosesIt() throws Exception {
        Path home = Files.createTempDirectory("details");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context, null);
                view.tabs.select(ActivitiesView.BACKGROUND_TAB);
                view.showJobsForTest(sample());
                javax.swing.JComponent root = view.component();
                root.setSize(900, 520);
                layout(root);
                javax.swing.JTable table = view.table();
                assertThat(table.getRowCount()).isPositive();
                assertThat(view.detailsShown()).isFalse();
                assertThat(view.detailScroll.isShowing() || view.detailScroll.isVisible()).isFalse();

                table.getSelectionModel().setSelectionInterval(0, 0);
                layout(root);
                assertThat(view.detailsShown()).isTrue();
                assertThat(view.detailScroll.getHeight()).isPositive();
                // Mit den Details steht auch der Trenner wieder da, direkt über ihnen.
                javax.swing.JSplitPane split = (javax.swing.JSplitPane) view.detailScroll.getParent();
                java.awt.Component divider = ((javax.swing.plaf.basic.BasicSplitPaneUI) split.getUI()).getDivider();
                assertThat(divider.getHeight()).isEqualTo(
                        com.aresstack.spaceharvester.app.ui.controls.ComicDivider.THICKNESS);
                assertThat(divider.getY() + divider.getHeight()).isEqualTo(view.detailScroll.getY());
                assertThat(divider.getY()).isGreaterThanOrEqualTo(90);

                // Unter den Zeilen ist die Tabelle zu Ende: Der Klick trifft die leere Fläche der Liste.
                java.awt.Component viewport = table.getParent();
                viewport.dispatchEvent(new java.awt.event.MouseEvent(viewport, java.awt.event.MouseEvent.MOUSE_CLICKED,
                        0, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 5, viewport.getHeight() - 2, 1, false,
                        java.awt.event.MouseEvent.BUTTON1));
                assertThat(table.getSelectedRow()).isEqualTo(-1);
                assertThat(view.detailsShown()).isFalse();

                // Und wieder auf: so oft man will, nicht nur beim ersten Mal. (Ohne Laufzeit leert das Nachlesen die
                // Liste, deshalb stehen die Aufträge hier neu.)
                view.showJobsForTest(sample());
                table.getSelectionModel().setSelectionInterval(0, 0);
                assertThat(view.detailsShown()).isTrue();
                viewport.dispatchEvent(new java.awt.event.MouseEvent(viewport, java.awt.event.MouseEvent.MOUSE_CLICKED,
                        0, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 5, viewport.getHeight() - 2, 1, false,
                        java.awt.event.MouseEvent.BUTTON1));
                assertThat(view.detailsShown()).isFalse();
                view.tabs.select(ActivitiesView.JOBS_TAB);
            });
        } finally {
            context.shutdown();
        }
    }

    private static void layout(java.awt.Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                layout(child);
            }
        }
    }

    @Test
    void backgroundWaitShowsTheRingUntilTheGroupIsFinished() throws Exception {
        List<Consumer<BackgroundGroup>> listeners = new ArrayList<>();
        List<BackgroundGroup> done = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            BusyOverlay overlay = new BusyOverlay(new JPanel());
            BackgroundWait wait = new BackgroundWait(overlay, (groupId, listener) -> {
                listeners.add(listener);
                return Subscription.NONE;
            });
            wait.follow("g1", "Nachbarseiten werden geladen …", done::add);
            assertThat(overlay.isBusy()).isTrue();
            assertThat(wait.waiting()).isTrue();

            listeners.get(0).accept(new BackgroundGroup("g1", "pages", "Nachbarseiten laden", List.of()));

            assertThat(overlay.isBusy()).isFalse();
            assertThat(wait.waiting()).isFalse();
            assertThat(done).hasSize(1);
        });
    }

    @Test
    void aReplacedWaitForTheSameGroupIgnoresTheOldQueuedCompletion() throws Exception {
        List<Consumer<BackgroundGroup>> listeners = new ArrayList<>();
        List<String> done = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            BusyOverlay overlay = new BusyOverlay(new JPanel());
            BackgroundWait wait = new BackgroundWait(overlay, (groupId, listener) -> {
                listeners.add(listener);
                return Subscription.NONE;
            });
            BackgroundGroup finished = new BackgroundGroup("g1", "pages", "Nachbarseiten laden", List.of());
            wait.follow("g1", "erstes Warten", group -> done.add("alt"));
            wait.follow("g1", "zweites Warten", group -> done.add("neu"));

            // Die schon eingereihte Meldung des ersetzten Wartens kommt noch an: sie löst nichts aus.
            listeners.get(0).accept(finished);
            assertThat(done).isEmpty();
            assertThat(overlay.isBusy()).isTrue();

            listeners.get(1).accept(finished);
            assertThat(done).containsExactly("neu");
            assertThat(overlay.isBusy()).isFalse();
        });
    }

    @Test
    void tabJobsHoldOnlyRealJobsOfTheirTabNeverGroupRows() {
        List<JobStatus> background = ActivitiesView.tabJobs(sample(), true);
        assertThat(background).extracting(JobStatus::id).containsExactly("b1", "b2", "b3");
        assertThat(ActivitiesView.tabJobs(sample(), false)).extracting(JobStatus::id).containsExactly("s1", "e1");
        assertThat(ActivitiesView.visible(sample(), true)).anyMatch(ActivitiesView::groupRow);
        assertThat(background).noneMatch(ActivitiesView::groupRow);
    }

    static JobStatus part(String id, JobState state, boolean cancelRequested, boolean pauseRequested,
                          com.aresstack.spaceharvester.application.jobs.PauseReason reason, boolean incomplete) {
        return new JobStatus(id, JobType.BACKGROUND, "wiki", "ALPHA", "space-harvester-core", state,
                new JobProgress(4, 4, 4, 0), null, cancelRequested, T, T, state.terminal() ? T : null, null, null,
                group("g9", "Nachbarseiten laden"), incomplete, pauseRequested, reason);
    }

    @Test
    void theGroupStateFollowsTheLifecycleOfItsParts() {
        // Ein Teil hält gerade an, der andere ist schon angehalten: „Anhalten angefordert“, nicht „Läuft“.
        JobStatus pausing = ActivitiesView.groupRows(List.of(
                part("p1", JobState.RUNNING, true, true, null, false),
                part("p2", JobState.PAUSED, false, false, null, false))).get(0);
        assertThat(pausing.lifecycle().key()).isEqualTo("PAUSE_REQUESTED");
        assertThat(Texts.state(pausing)).isEqualTo("Anhalten angefordert");

        // Verbindung verloren geht vor dem Anhalten des Benutzers.
        JobStatus lost = ActivitiesView.groupRows(List.of(
                part("p1", JobState.PAUSED, false, false,
                        com.aresstack.spaceharvester.application.jobs.PauseReason.USER, false),
                part("p2", JobState.PAUSED, false, false,
                        com.aresstack.spaceharvester.application.jobs.PauseReason.CONNECTION_LOST, false))).get(0);
        assertThat(lost.lifecycle().key()).isEqualTo("PAUSED_CONNECTION_LOST");

        // Ein unvollständiger Teil macht die Gruppe „Fertig, unvollständig“.
        JobStatus partial = ActivitiesView.groupRows(List.of(
                part("p1", JobState.COMPLETED, false, false, null, false),
                part("p2", JobState.COMPLETED, false, false, null, true))).get(0);
        assertThat(partial.lifecycle().key()).isEqualTo("COMPLETED_WITH_ERRORS");
    }

    @Test
    void theHelpSitsBehindAQuestionMarkButton() throws Exception {
        Path home = Files.createTempDirectory("bghelp");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context, null);
                List<com.aresstack.spaceharvester.app.ui.controls.HelpButton> help = new ArrayList<>();
                collect(view.component(), help);
                assertThat(help).hasSize(1);
                assertThat(help.get(0).popup()).isNotNull();
            });
        } finally {
            context.shutdown();
        }
    }

    private static void collect(java.awt.Component component,
                                List<com.aresstack.spaceharvester.app.ui.controls.HelpButton> found) {
        if (component instanceof com.aresstack.spaceharvester.app.ui.controls.HelpButton help) {
            found.add(help);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collect(child, found);
            }
        }
    }
}
