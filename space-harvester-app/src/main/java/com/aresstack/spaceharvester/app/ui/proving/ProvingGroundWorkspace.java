package com.aresstack.spaceharvester.app.ui.proving;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.chat.browser.BrowserSettings;
import com.aresstack.spaceharvester.app.credentials.KeePassLink;
import com.aresstack.spaceharvester.app.proving.KeePassTestCredentials;
import com.aresstack.spaceharvester.app.proving.ProvingGroundRuns;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.AppTimeouts;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.controls.ComicTreeRenderer;
import com.aresstack.spaceharvester.app.ui.controls.FolderField;
import com.aresstack.spaceharvester.app.ui.controls.LeftSidebarPane;
import com.aresstack.spaceharvester.app.ui.controls.SideHead;
import com.aresstack.spaceharvester.app.ui.controls.StatusLabel;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.app.ui.deck.DeckBars;
import com.aresstack.spaceharvester.app.ui.perspective.Perspective;
import com.aresstack.spaceharvester.app.ui.perspective.Perspectives;
import com.aresstack.spaceharvester.provingground.model.StepBinding;
import com.aresstack.spaceharvester.provingground.model.StepRole;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestCaseEntry;
import com.aresstack.spaceharvester.provingground.model.TestProject;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.aresstack.spaceharvester.provingground.model.TestSuite;
import com.aresstack.spaceharvester.provingground.player.StepResult;
import com.aresstack.spaceharvester.provingground.run.CaseRunResult;
import com.aresstack.spaceharvester.provingground.run.ProjectRunResult;
import com.aresstack.spaceharvester.provingground.store.ProjectFile;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/**
 * Die Perspektive Proving Ground (#1153), erste Ausbaustufe: links das Testprojekt (Ordner mit {@code projekt.json}
 * und den Playwright-JSONL-Dateien) als Baum Projekt › Suiten › Testfälle, rechts die Schritte des gewählten
 * Testfalls mit Rolle (Given/When/Then), Benutzer und Beschreibung, oben rechts Ausführen und Abbrechen. Ausgeführt
 * wird, was im Baum gewählt ist (Testfall, Suite oder das ganze Projekt), im Browser aus Einstellungen › Browser; die
 * Zugangsdaten der Testbenutzer kommen erst im Lauf aus KeePass. Aufnehmen und Bearbeiten folgen.
 */
public final class ProvingGroundWorkspace extends JPanel {

    /** Wo der Ordner des Testprojekts gemerkt wird (App-Zustand). */
    static final String PROJECT_KEY = "provingGround.project";
    static final String RUN_TIP = "Ausführen: den gewählten Testfall, die Suite oder das ganze Projekt";
    static final String STOP_TIP = "Abbrechen: den laufenden Test beenden";
    static final String CREATE_TIP = "In diesem Ordner ein leeres Testprojekt anlegen";

    private final Supplier<KeePassLink> keePass;
    private final Supplier<Path> dataDirectory;
    private final ProvingGroundRuns runs = new ProvingGroundRuns();
    private final StatusLabel status = new StatusLabel();
    private final FolderField folder = new FolderField(Comic.field(20));
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Kein Testprojekt");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final JLabel title = Comic.caption("Proving Ground");
    private final StepsModel steps = new StepsModel();
    private final JTable table = Comic.table(new JTable(steps));
    private final JTextArea report = Comic.code();
    private final IconButton run = new IconButton(ComposerIcons.play(), RUN_TIP).compact();
    private final IconButton stop = new IconButton(ComposerIcons.stop(), STOP_TIP).compact();
    private final IconButton create = new IconButton(ComposerIcons.plus(12), CREATE_TIP).compact();
    /** Ergebnisse des letzten Laufs je Testfall ({@code <suite>/<fall>}). */
    private final Map<String, CaseRunResult> results = new HashMap<>();
    private Path directory;
    private TestProject project;

    /**
     * @param keePass       die KeePass-Kopplung der App (still gelesen, wie im Command Deck)
     * @param dataDirectory Datenordner der App; Traces und Bildschirmfotos der Läufe liegen darunter in
     *                      {@code proving-ground/<Zeitpunkt>}
     */
    public ProvingGroundWorkspace(Supplier<KeePassLink> keePass, Supplier<Path> dataDirectory) {
        super(new BorderLayout());
        this.keePass = keePass;
        this.dataDirectory = dataDirectory;
        setBackground(Comic.SURFACE);
        folder.dialogTitle("Ordner des Testprojekts wählen");
        folder.onChange(this::open);
        tree.setCellRenderer(new ComicTreeRenderer());
        tree.setRootVisible(true);
        tree.addTreeSelectionListener(event -> selected());
        create.addActionListener(event -> createProject());
        create.setVisible(false);
        JPanel sidebar = new JPanel(new BorderLayout(0, 6));
        sidebar.setOpaque(false);
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);
        top.add(new SideHead("Testprojekt", null).actions(create), BorderLayout.NORTH);
        top.add(folder, BorderLayout.CENTER);
        sidebar.add(top, BorderLayout.NORTH);
        sidebar.add(Comic.scroll(tree), BorderLayout.CENTER);

        run.addActionListener(event -> runSelection());
        stop.addActionListener(event -> runs.stop());
        stop.setEnabled(false);
        table.getColumnModel().getColumn(0).setMaxWidth(48);
        table.getColumnModel().getColumn(1).setMaxWidth(80);
        report.setEditable(false);
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Comic.scroll(table), Comic.scroll(report));
        center.setResizeWeight(0.65);
        center.setBorder(null);
        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        tools.setOpaque(false);
        tools.add(run);
        tools.add(stop);
        content.add(DeckBars.head(title, tools), BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        add(new LeftSidebarPane(sidebar, content, 280).withDividerToggle(), BorderLayout.CENTER);

        String remembered = AppState.shared().get(PROJECT_KEY);
        if (remembered != null && !remembered.isBlank()) {
            folder.path(Path.of(remembered));
            open(Path.of(remembered));
        } else {
            status.show(StatusNote.plain("Ordner des Testprojekts wählen"));
        }
    }

    /** Die Perspektive mit dieser Arbeitsfläche. */
    public Perspective perspective() {
        return new Perspective(Perspectives.PROVING_GROUND, "Proving Ground",
                "Webtests aufzeichnen, bearbeiten, ausführen und auswerten", true, null, this, null, null, null,
                null, status);
    }

    /** Liest das Testprojekt im Ordner, im Hintergrund. */
    void open(Path chosen) {
        if (chosen == null) {
            return;
        }
        AppState.shared().put(PROJECT_KEY, chosen.toString());
        CompletableFuture.supplyAsync(() -> {
            try {
                return Files.exists(chosen.resolve(ProjectFile.FILE_NAME)) ? ProjectFile.read(chosen) : null;
            } catch (IOException broken) {
                throw new IllegalStateException(broken.getMessage(), broken);
            }
        }).whenComplete((read, failure) -> SwingUtilities.invokeLater(() -> {
            directory = chosen;
            results.clear();
            if (failure != null) {
                show(null, "Testprojekt nicht lesbar: " + cause(failure), StatusNote.Tone.ERROR);
            } else if (read == null) {
                show(null, "Kein Testprojekt in diesem Ordner – mit ＋ anlegen", StatusNote.Tone.WARNING);
            } else {
                List<String> problems = read.problems();
                show(read, problems.isEmpty() ? read.name() + ": " + count(read) + " Testfälle"
                        : "Testprojekt fehlerhaft: " + String.join(" · ", problems),
                        problems.isEmpty() ? StatusNote.Tone.PLAIN : StatusNote.Tone.ERROR);
            }
        }));
    }

    private void show(TestProject read, String note, StatusNote.Tone tone) {
        project = read;
        create.setVisible(read == null && directory != null);
        root.removeAllChildren();
        root.setUserObject(read == null ? "Kein Testprojekt" : new Node(read.name().isEmpty() ? "Testprojekt"
                : read.name(), null, null));
        if (read != null) {
            for (TestSuite suite : read.suites()) {
                DefaultMutableTreeNode suiteNode = new DefaultMutableTreeNode(new Node(suite.name(), suite, null));
                for (TestCaseEntry testCase : suite.cases()) {
                    suiteNode.add(new DefaultMutableTreeNode(new Node(testCase.name(), suite, testCase)));
                }
                root.add(suiteNode);
            }
        }
        treeModel.reload();
        for (int row = 0; row < tree.getRowCount(); row++) {
            tree.expandRow(row);
        }
        tree.setSelectionRow(0);
        status.show(new StatusNote(note, tone, null, null));
    }

    /** Ein leeres Testprojekt mit dem Namen des Ordners. */
    void createProject() {
        Path target = directory;
        if (target == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                ProjectFile.write(TestProject.of(target.getFileName() == null ? "Testprojekt"
                        : target.getFileName().toString(), List.of()), target);
            } catch (IOException broken) {
                throw new IllegalStateException(broken.getMessage(), broken);
            }
        }).whenComplete((nothing, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) {
                status.show(new StatusNote("Anlegen fehlgeschlagen: " + cause(failure), StatusNote.Tone.ERROR,
                        null, null));
            } else {
                open(target);
            }
        }));
    }

    /** Zeigt die Schritte des gewählten Testfalls; Suite und Projekt zeigen keine Schritte. */
    private void selected() {
        Node node = selectedNode();
        title.setText(node == null ? "Proving Ground" : node.label());
        steps.show(null, null, null);
        report.setText("");
        if (node == null || node.testCase() == null || directory == null) {
            return;
        }
        TestCaseEntry entry = node.testCase();
        String reference = TestProject.reference(node.suite(), entry);
        Path base = directory;
        CompletableFuture.supplyAsync(() -> {
            try {
                return ProjectFile.readCase(base, entry);
            } catch (IOException broken) {
                throw new IllegalStateException(broken.getMessage(), broken);
            }
        }).whenComplete((testCase, failure) -> SwingUtilities.invokeLater(() -> {
            if (selectedNode() != node) {
                return;
            }
            if (failure != null) {
                report.setText("Testfall nicht lesbar: " + cause(failure));
                return;
            }
            CaseRunResult result = results.get(reference);
            steps.show(testCase, entry, result);
            report.setText(result == null ? "" : result.render());
        }));
    }

    /** Führt aus, was im Baum gewählt ist. */
    void runSelection() {
        if (project == null || directory == null) {
            return;
        }
        Node node = selectedNode();
        List<String> only = new ArrayList<>();
        if (node != null && node.testCase() != null) {
            only.add(TestProject.reference(node.suite(), node.testCase()));
        } else if (node != null && node.suite() != null) {
            node.suite().cases().forEach(c -> only.add(TestProject.reference(node.suite(), c)));
        }
        if (node != null && node.suite() != null && only.isEmpty()) {
            status.show(StatusNote.plain("Diese Suite hat keine Testfälle"));
            return;
        }
        Path output = dataDirectory.get().resolve("proving-ground")
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        run.setEnabled(false);
        stop.setEnabled(true);
        status.show(StatusNote.plain("Test läuft …"));
        TestProject current = project;
        Path base = directory;
        runs.run(current, base, only, this::setup,
                () -> new KeePassTestCredentials(keePass), output,
                result -> SwingUtilities.invokeLater(() -> finished(result, output)),
                problem -> SwingUtilities.invokeLater(() -> {
                    finishedRun();
                    status.show(new StatusNote("Test nicht gestartet: " + problem, StatusNote.Tone.ERROR, null,
                            null));
                }));
    }

    /** Browser aus Einstellungen › Browser, sichtbar; Playwrights Zeitgrenzen für die Schritte. */
    private ProvingGroundRuns.Setup setup() {
        BrowserSettings settings = BrowserSettings.shared();
        String channel = settings.channel();
        return new ProvingGroundRuns.Setup(channel, settings.executable(channel), dataDirectory.get(),
                AppTimeouts.shared().get(AppTimeouts.Value.BROWSER_START), Duration.ZERO, false);
    }

    private void finished(ProjectRunResult result, Path output) {
        finishedRun();
        result.cases().forEach(c -> results.put(c.reference(), c));
        long failed = result.cases().stream().filter(c -> !c.passed()).count();
        status.show(new StatusNote(result.passed() ? "Bestanden: " + result.cases().size() + " Testfälle"
                : failed + " von " + result.cases().size() + " Testfällen fehlgeschlagen",
                result.passed() ? StatusNote.Tone.PLAIN : StatusNote.Tone.ERROR, null, null));
        selected();
        if (selectedNode() == null || selectedNode().testCase() == null) {
            report.setText(result.render() + "\nTraces und Bildschirmfotos: " + output);
        }
        tree.repaint();
    }

    private void finishedRun() {
        run.setEnabled(true);
        stop.setEnabled(false);
    }

    private Node selectedNode() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return value instanceof Node node ? node : null;
    }

    /** Die Stand-Anzeige der Perspektive in der Statuszeile (für Tests). */
    JComponent statusLine() {
        return status;
    }

    StepsModel steps() {
        return steps;
    }

    private static int count(TestProject read) {
        return read.suites().stream().mapToInt(s -> s.cases().size()).sum();
    }

    private static String cause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    /** Ein Knoten im Baum: das Projekt (beides null), eine Suite oder ein Testfall. */
    record Node(String label, TestSuite suite, TestCaseEntry testCase) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** Die Schritte eines Testfalls: Nr., Rolle, Benutzer, Beschreibung (sonst Playwrights Aktion), Ergebnis. */
    static final class StepsModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Nr.", "Rolle", "Benutzer", "Schritt", "Ergebnis"};
        private final List<Object[]> rows = new ArrayList<>();

        void show(TestCase testCase, TestCaseEntry entry, CaseRunResult result) {
            rows.clear();
            if (testCase != null) {
                List<StepResult> played = result == null || result.steps() == null ? List.of()
                        : result.steps().steps();
                for (int i = 0; i < testCase.size(); i++) {
                    TestStep step = testCase.step(i);
                    StepBinding binding = entry.binding(step.id());
                    StepRole role = binding.role() != null ? binding.role() : StepRole.of(step);
                    String user = binding.user() != null ? binding.user()
                            : entry.user() != null ? entry.user() : "";
                    String what = binding.description().isEmpty() ? step.describe() : binding.description();
                    String outcome = i < played.size() ? outcome(played.get(i)) : "";
                    rows.add(new Object[]{i + 1, label(role), user, what, outcome});
                }
            }
            fireTableDataChanged();
        }

        private static String outcome(StepResult step) {
            return switch (step.status()) {
                case PASSED -> "✓ " + step.durationMillis() + " ms";
                case FAILED -> "✗ " + step.error();
                case SKIPPED -> "○ übersprungen";
            };
        }

        private static String label(StepRole role) {
            return switch (role) {
                case GIVEN -> "Given";
                case WHEN -> "When";
                case THEN -> "Then";
            };
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            return rows.get(row)[column];
        }
    }
}
