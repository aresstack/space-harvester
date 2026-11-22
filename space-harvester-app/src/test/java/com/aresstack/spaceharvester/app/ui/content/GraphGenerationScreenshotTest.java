package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.StatusLabel;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.services.ServicePlan;
import com.aresstack.spaceharvester.application.background.services.ServicePreview;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.knowledge.GraphNeighbourhood;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs;
import com.aresstack.spaceharvester.application.knowledge.PageGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.export.KnowledgeGraphExportUseCase;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Bildschirmfotos von „Wissensgraph erzeugen“ (W5) zur Sichtprüfung: Kopfleiste mit dem Knopf, Rückfrage, Statuszeile
 * im Lauf und am Ende, je bei 960 und 900 px Fensterbreite (erfundene Daten: Quelle „wiki“, Space „DEV“). Läuft nur mit
 * der Umgebungsvariable {@code SCREENSHOTS_DIR}, allein gestartet und mit Bildschirm (etwa
 * {@code xvfb-run -a -s "-screen 0 3000x2000x24"}), weil die Rückfrage ein echtes Fenster ist.
 */
class GraphGenerationScreenshotTest {

    private static final PageKey PLAN = new PageKey("wiki", "1");
    private static final KnowledgeGraph.PageRef PLAN_REF = new KnowledgeGraph.PageRef(PLAN, "DEV", "Plan",
            PageStatements.State.CURRENT, null);
    private static final String DOC = "wiki/1@0123456789ab";
    private static final String SKIPPED = "„Bilder beschreiben“ ist abgeschaltet und wurde bewusst ausgelassen";

    @Test
    void renderGraphGeneration() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        Assumptions.assumeTrue(directory != null && !directory.isBlank());
        // Erst hier, damit der Test ohne die Variable nichts an AWT ändert; wirkt nur, solange AWT noch nicht lief.
        System.setProperty("java.awt.headless", "false");
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("sun.java2d.uiScale", "2");
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "braucht einen Bildschirm (Xvfb)");
        Path out = Path.of(directory);
        Files.createDirectories(out);
        for (int width : new int[] {960, 900}) {
            render(width, out);
        }
    }

    private void render(int width, Path out) throws Exception {
        Actions actions = new Actions();
        KnowledgeGraphTab[] tab = new KnowledgeGraphTab[1];
        JFrame[] frame = new JFrame[1];
        JPanel[] root = new JPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new KnowledgeGraphTab(() -> actions, new Runner());
            StatusLabel status = new StatusLabel();
            status.setOpaque(false);
            // Derselbe Weg wie im Hauptfenster (PageDetailPanel#showTabStatus): die Ablage dahinter bleibt grau.
            tab[0].onNote(note -> status.show(PageDetailPanel.withStore("Graph", note, AnalyzerChoice.MODEL)));
            root[0] = new JPanel(new BorderLayout());
            root[0].setBackground(Comic.SURFACE);
            root[0].add(tab[0], BorderLayout.CENTER);
            root[0].add(statusLine(status), BorderLayout.SOUTH);
            frame[0] = new JFrame("Space Harvester");
            frame[0].setUndecorated(true);
            frame[0].setContentPane(root[0]);
            frame[0].setSize(width, 560);
            frame[0].setLocation(0, 0);
            frame[0].setVisible(true);
            tab[0].page(PLAN);
        });
        settle();

        // Kopfleiste mit dem Knopf
        SwingUtilities.invokeAndWait(() -> write(tab[0].header(), null, out.resolve("w5-kopf-" + width + ".png")));

        // Rückfrage als echtes Overlay über dem Fenster
        actions.preview = new GraphGeneration.Preview(new ServicePreview(List.of(
                stage("images", "Bilder beschreiben", ServicePreview.State.DISABLED, 0, 0, 0, 0),
                stage("statements", "Aussagenanalyse", ServicePreview.State.PLANNED, 12, 4, 0, 3),
                stage("glossary", "Glossar", ServicePreview.State.RUNNING, 0, 0, 3, 0),
                stage("knowledge-graph", "Wissensgraph", ServicePreview.State.PLANNED, 15, 0, 0, 1)),
                List.of(), 0), AnalyzerChoice.MODEL, 3);
        SwingUtilities.invokeLater(tab[0]::clickGenerate);
        JDialog dialog = awaitDialog();
        settle();
        SwingUtilities.invokeAndWait(() -> write(root[0], dialog, out.resolve("w5-overlay-" + width + ".png")));
        SwingUtilities.invokeAndWait(dialog::dispose);
        settle();

        // Dieselbe Rückfrage ohne bereites Chat-Modell: Zeile „Aussagenanalyse“ gelb „kein Chat-Modell (wird ausgelassen)“
        actions.preview = new GraphGeneration.Preview(actions.preview.plan(), AnalyzerChoice.MODEL, 3, false);
        SwingUtilities.invokeLater(tab[0]::clickGenerate);
        JDialog noModel = awaitDialog();
        settle();
        SwingUtilities.invokeAndWait(() -> write(root[0], noModel,
                out.resolve("w5-overlay-ohne-modell-" + width + ".png")));
        SwingUtilities.invokeAndWait(noModel::dispose);
        settle();

        // Lauf: Fortschritt mit ✕, gelb die ausgelassene Stufe
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.QUEUED, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Aussagenanalyse"), part("p-3", JobState.QUEUED, "Aussagenanalyse"),
                part("p-4", JobState.QUEUED, "Wissensgraph"))), List.of(SKIPPED), 0);
        SwingUtilities.invokeAndWait(() -> {
            tab[0].confirmGenerateWith(preview -> true);
            tab[0].clickGenerate();
            actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                    part("p-2", JobState.RUNNING, "Aussagenanalyse"), part("p-3", JobState.QUEUED, "Aussagenanalyse"),
                    part("p-4", JobState.QUEUED, "Wissensgraph")));
        });
        settle();
        SwingUtilities.invokeAndWait(() -> write(root[0], null, out.resolve("w5-lauf-" + width + ".png")));

        // Ende: gelb ausgelassen und ohne KI-Freigabe, rot die gescheiterte Vorstufe mit Kreispfeil
        actions.graphNotes = List.of("Aussagenanalyse: 3 Seiten bewusst ausgelassen (ohne KI-Freigabe oder Modell)");
        actions.grown = true;
        SwingUtilities.invokeAndWait(() -> actions.watcher.accept(group(
                part("p-1", JobState.COMPLETED, "Aussagenanalyse"), part("p-2", JobState.FAILED, "Aussagenanalyse"),
                part("p-3", JobState.COMPLETED, "Aussagenanalyse"), part("p-4", JobState.COMPLETED, "Wissensgraph"))));
        settle();
        SwingUtilities.invokeAndWait(() -> write(root[0], null, out.resolve("w5-ende-" + width + ".png")));
        SwingUtilities.invokeAndWait(frame[0]::dispose);
    }

    /** Die Statuszeile des Hauptfensters ({@code ui.StatusLine}, paketprivat) mit dem Stand des Reiters vorn. */
    private static JComponent statusLine(StatusLabel lead) {
        try {
            Class<?> type = Class.forName("com.aresstack.spaceharvester.app.ui.StatusLine");
            java.lang.reflect.Constructor<?> create = type.getDeclaredConstructor(JProgressBar.class,
                    JComponent.class, JComponent.class, JComponent.class, JComponent.class, JComponent.class);
            create.setAccessible(true);
            Class<?> copyright = Class.forName("com.aresstack.spaceharvester.app.ui.CopyrightLink");
            java.lang.reflect.Constructor<?> link = copyright.getDeclaredConstructor();
            link.setAccessible(true);
            JPanel alert = new JPanel();
            alert.setOpaque(false);
            JLabel app = new JLabel(" ");
            JLabel jobs = new JLabel(" ");
            return (JComponent) create.newInstance(new JProgressBar(), alert, lead, app, jobs, link.newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JDialog awaitDialog() throws Exception {
        for (int i = 0; i < 100; i++) {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()) {
                    return dialog;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("keine Rückfrage");
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(150);
        }
    }

    /** Doppelt aufgelöst; mit {@code dialog} darüber an seiner Stelle auf dem Bildschirm. */
    private static void write(JComponent component, JDialog dialog, Path file) {
        try {
            component.validate();
            BufferedImage image = new BufferedImage(component.getWidth() * 2, component.getHeight() * 2,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.scale(2, 2);
            // Die Kopfleiste ist durchsichtig: der Grund des Reiters dahinter.
            g.setColor(Comic.SURFACE);
            g.fillRect(0, 0, component.getWidth(), component.getHeight());
            component.paint(g);
            if (dialog != null) {
                Point at = dialog.getLocationOnScreen();
                Point base = component.getLocationOnScreen();
                Graphics2D over = (Graphics2D) g.create();
                over.translate(at.x - base.x, at.y - base.y);
                Component content = dialog.getRootPane();
                content.paint(over);
                over.dispose();
            }
            g.dispose();
            ImageIO.write(image, "png", file.toFile());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ServicePreview.Stage stage(String id, String title, ServicePreview.State state, int pages,
                                              int toLoad, int reused, int parts) {
        return new ServicePreview.Stage(id, title, state, pages, toLoad, reused, parts);
    }

    private static BackgroundGroup group(JobStatus... parts) {
        return new BackgroundGroup("g-1", KnowledgeGraphJobs.KIND, "Wissensgraph", List.of(parts));
    }

    private static JobStatus part(String id, JobState state, String stage) {
        return new JobStatus(id, JobType.BACKGROUND, "wiki", "DEV", "app", state, JobProgress.NONE,
                state == JobState.FAILED ? new JobError("TEST", "Fehler") : null, false, Instant.EPOCH, null, null,
                "1", null, Map.of(BackgroundJobs.GROUP, "g-1", BackgroundJobs.STEP_TITLE, stage, BackgroundJobs.KIND,
                "Wissensgraph".equals(stage) ? KnowledgeGraphJobs.KIND : "statements"), false, false, null);
    }

    private static final class Runner implements KnowledgeGraphTab.Runner {
        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            T result;
            try {
                result = work.call();
            } catch (Throwable e) {
                onFailure.accept(e);
                return;
            }
            onSuccess.accept(result);
        }
    }

    /** Seite „Plan“ in DEV mit den Begriffen Ziel und Start; nach dem Lauf kommt „Nachtlauf“ dazu. */
    private static final class Actions implements KnowledgeGraphTab.Actions {
        GraphGeneration.Preview preview;
        ServicePlan plan = new ServicePlan(Optional.empty(), List.of(), 0);
        Consumer<BackgroundGroup> watcher;
        List<String> graphNotes = List.of();
        boolean grown;

        @Override
        public GraphNeighbourhood.Subgraph page(PageKey key, GraphNeighbourhood.Options options) {
            String statement = DOC + "#s1.v3";
            String ziel = DOC + "#s1.t6";
            String start = DOC + "#s1.t2";
            List<GraphNeighbourhood.Node> nodes = new java.util.ArrayList<>(List.of(
                    node(DOC, PageGraph.NodeKind.DOCUMENT, "Plan", null),
                    node(statement, PageGraph.NodeKind.STATEMENT, "erreicht", "erreichen"),
                    node(start, PageGraph.NodeKind.MENTION, "der Start", "Start"),
                    node(ziel, PageGraph.NodeKind.MENTION, "das Ziel", "Ziel")));
            List<GraphNeighbourhood.Edge> edges = new java.util.ArrayList<>(List.of(
                    edge(DOC, statement, GraphNeighbourhood.EdgeType.CONTAINS, "STRUCTURE", null),
                    edge(statement, start, GraphNeighbourhood.EdgeType.AFFECTED_OBJECT, "PARSER", "subj"),
                    edge(statement, ziel, GraphNeighbourhood.EdgeType.AFFECTED_OBJECT, "PARSER", "obj")));
            if (grown) {
                String nacht = DOC + "#s2.t4";
                nodes.add(node(nacht, PageGraph.NodeKind.MENTION, "den Nachtlauf", "Nachtlauf"));
                edges.add(edge(DOC, nacht, GraphNeighbourhood.EdgeType.CONTAINS, "STRUCTURE", null));
            }
            return new GraphNeighbourhood.Subgraph(DOC, nodes, edges, false, 0);
        }

        private static GraphNeighbourhood.Node node(String id, PageGraph.NodeKind kind, String label, String lemma) {
            return new GraphNeighbourhood.Node(id, kind, label, lemma, PLAN_REF, 0, 4, kind == PageGraph.NodeKind
                    .STATEMENT);
        }

        private static GraphNeighbourhood.Edge edge(String from, String to, GraphNeighbourhood.EdgeType type,
                                                    String origin, String relation) {
            return new GraphNeighbourhood.Edge(from, to, type, origin, relation, null, relation == null ? null : "s1",
                    true);
        }

        @Override
        public GraphNeighbourhood.Subgraph node(String nodeId, GraphNeighbourhood.Options options) {
            return GraphNeighbourhood.Subgraph.empty();
        }

        @Override
        public GraphNeighbourhood.Subgraph term(String term, GraphNeighbourhood.Options options) {
            return GraphNeighbourhood.Subgraph.empty();
        }

        @Override
        public JobStatus export(KnowledgeGraphExportUseCase.Scope scope, boolean includeStale) {
            return null;
        }

        @Override
        public int deleteAllStatements() {
            return 0;
        }

        @Override
        public GraphGeneration.Preview previewGraph(PageSet pages) {
            return preview;
        }

        @Override
        public ServicePlan startGraph(PageSet pages) {
            return plan;
        }

        @Override
        public AutoCloseable watchGraph(String groupId, Consumer<BackgroundGroup> listener) {
            watcher = listener;
            return () -> { };
        }

        @Override
        public List<String> graphNotes(BackgroundGroup group) {
            return graphNotes;
        }
    }
}
