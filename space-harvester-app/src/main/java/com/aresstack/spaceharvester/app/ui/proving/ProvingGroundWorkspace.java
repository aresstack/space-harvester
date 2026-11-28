package com.aresstack.spaceharvester.app.ui.proving;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.chat.browser.BrowserSettings;
import com.aresstack.spaceharvester.app.credentials.KeePassLink;
import com.aresstack.spaceharvester.app.proving.KeePassTestCredentials;
import com.aresstack.spaceharvester.app.proving.ProvingGroundRuns;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.AppTimeouts;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
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
import com.aresstack.spaceharvester.provingground.store.TestCaseFile;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
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
 * Zugangsdaten der Testbenutzer kommen erst im Lauf aus KeePass.
 *
 * <p>Aufnehmen mit dem originalen Playwright-Recorder: ● nimmt in den gewählten Testfall auf (hängt an) oder, ist eine
 * Suite gewählt, in einen neuen; ❚❚ hält an und nimmt wieder auf, ■ beendet und speichert. Bearbeiten: Rolle, Benutzer
 * und Beschreibung direkt in der Tabelle (das Fachliche hängt an der Schritt-ID und bleibt beim Verschieben richtig
 * zugeordnet), ▲ ▼ verschieben, der Papierkorb löscht. Jede Änderung wird sofort gespeichert.</p>
 */
public final class ProvingGroundWorkspace extends JPanel {

    /** Wo der Ordner des Testprojekts gemerkt wird (App-Zustand). */
    static final String PROJECT_KEY = "provingGround.project";
    static final String RUN_TIP = "Ausführen: den gewählten Testfall, die Suite oder das ganze Projekt";
    static final String STOP_TIP = "Abbrechen: den laufenden Test beenden";
    static final String CREATE_TIP = "In diesem Ordner ein leeres Testprojekt anlegen";
    static final String SUITE_TIP = "Neue Suite anlegen";
    static final String RECORD_TIP = "Aufnehmen: in den gewählten Testfall, oder bei einer Suite in einen neuen";
    static final String PAUSE_TIP = "Aufnahme anhalten oder fortsetzen";
    static final String UP_TIP = "Schritt nach oben";
    static final String DOWN_TIP = "Schritt nach unten";
    static final String DELETE_TIP = "Schritt löschen";
    /** Rolle „automatisch“: aus der Aktion (Assertion = Then, sonst When). */
    static final String AUTO = "automatisch";

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
    private final IconButton record = new IconButton(Glyph.CIRCLE_FULL, RECORD_TIP).compact();
    private final IconButton pause = new IconButton(ComposerIcons.pause(), PAUSE_TIP).compact();
    private final IconButton up = new IconButton(ComposerIcons.chevronUp(), UP_TIP).compact();
    private final IconButton down = new IconButton(ComposerIcons.chevronDown(), DOWN_TIP).compact();
    private final IconButton delete = new IconButton(Glyph.TRASH, DELETE_TIP).compact();
    /** Schreibt Dateien nacheinander, nie auf dem EDT. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "proving-ground-io");
        worker.setDaemon(true);
        return worker;
    });
    private ProvingGroundRuns.Recording recording;
    /** Ergebnisse des letzten Laufs je Testfall ({@code <suite>/<fall>}). */
    private final Map<String, CaseRunResult> results = new HashMap<>();
    private Path directory;
    private TestProject project;
    /** Der gezeigte Testfall (Aktionen, Fachliches, Suite), oder null. */
    private TestCase shownCase;
    private TestCaseEntry shownEntry;
    private TestSuite shownSuite;

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
        create.addActionListener(event -> {
            if (project == null) {
                createProject();
            } else {
                String name = Dialogs.askText(this, "Neue Suite", "Name der Suite", "");
                if (name != null) {
                    createSuite(name);
                }
            }
        });
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
        stop.addActionListener(event -> {
            if (recording != null) {
                recording.stop();
            } else {
                runs.stop();
            }
        });
        stop.setEnabled(false);
        record.setForeground(Comic.RED);
        record.addActionListener(event -> startRecording());
        pause.setEnabled(false);
        pause.addActionListener(event -> togglePause());
        up.addActionListener(event -> moveSelected(-1));
        down.addActionListener(event -> moveSelected(1));
        delete.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row >= 0 && Dialogs.confirm(this, "Schritt löschen", "Schritt " + (row + 1) + " wirklich löschen?")) {
                deleteStep(row);
            }
        });
        steps.onEdit(this::bindingEdited);
        table.getColumnModel().getColumn(0).setMaxWidth(48);
        table.getColumnModel().getColumn(1).setMaxWidth(110);
        JComboBox<String> roles = Comic.combo();
        for (String role : List.of(AUTO, "Given", "When", "Then")) {
            roles.addItem(role);
        }
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(roles));
        report.setEditable(false);
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Comic.scroll(table), Comic.scroll(report));
        center.setResizeWeight(0.65);
        center.setBorder(null);
        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        tools.setOpaque(false);
        tools.add(up);
        tools.add(down);
        tools.add(delete);
        tools.add(record);
        tools.add(pause);
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
        create.setVisible(directory != null);
        create.setToolTipText(read == null ? CREATE_TIP : SUITE_TIP);
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

    /** Wählt im Baum den Knoten des Testfalls oder der Suite. */
    private void select(String suiteId, String caseId) {
        for (int row = 0; row < tree.getRowCount(); row++) {
            Object value = ((DefaultMutableTreeNode) tree.getPathForRow(row).getLastPathComponent()).getUserObject();
            if (value instanceof Node node && node.suite() != null && node.suite().id().equals(suiteId)
                    && (caseId == null ? node.testCase() == null
                    : node.testCase() != null && node.testCase().id().equals(caseId))) {
                tree.setSelectionRow(row);
                return;
            }
        }
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
        shownCase = null;
        shownEntry = null;
        shownSuite = node == null ? null : node.suite();
        report.setText("");
        updateUsers();
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
            shownCase = testCase;
            shownEntry = entry;
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
        record.setEnabled(false);
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
        record.setEnabled(true);
        stop.setEnabled(false);
        pause.setEnabled(false);
    }

    /** Die Benutzer des Projekts als Auswahl in der Spalte „Benutzer“ (leer = der des Testfalls). */
    private void updateUsers() {
        JComboBox<String> users = Comic.combo();
        users.addItem("");
        if (project != null) {
            project.users().forEach(user -> users.addItem(user.name()));
        }
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(users));
    }

    // ---- Aufnehmen ----

    /** ● : in den gezeigten Testfall (anhängen) oder, bei gewählter Suite, in einen neuen Testfall. */
    void startRecording() {
        Node node = selectedNode();
        if (project == null || directory == null || node == null || node.suite() == null) {
            status.show(new StatusNote("Erst eine Suite oder einen Testfall wählen (neue Suite mit ＋)",
                    StatusNote.Tone.WARNING, null, null));
            return;
        }
        if (node.testCase() != null) {
            if (shownCase == null) {
                return;
            }
            record(node.suite(), shownEntry, new TestCase(shownCase.name(), shownCase.steps()));
        } else {
            String name = Dialogs.askText(this, "Neuer Testfall", "Name des Testfalls", "");
            if (name != null) {
                record(node.suite(), newCase(node.suite(), name), new TestCase(name));
            }
        }
    }

    /** Ein neuer Testfall in {@code suite} mit eigener Datei {@code <suite>/<id>.jsonl}. */
    static TestCaseEntry newCase(TestSuite suite, String name) {
        String id = TestProject.newId(name, suite.cases().stream().map(TestCaseEntry::id).toList());
        return TestCaseEntry.of(id, name, ProjectFile.defaultCaseFile(suite.id(), id));
    }

    private void record(TestSuite suite, TestCaseEntry entry, TestCase target) {
        run.setEnabled(false);
        record.setEnabled(false);
        stop.setEnabled(true);
        pause.setEnabled(true);
        title.setText(entry.name() + " · Aufnahme");
        status.show(StatusNote.plain("Aufnahme läuft – im Browser arbeiten, ■ beendet und speichert"));
        recording = runs.record(target, this::setup,
                live -> SwingUtilities.invokeLater(() -> steps.show(new TestCase(target.name(), live), entry, null)),
                recorded -> SwingUtilities.invokeLater(() -> recorded(suite, entry, recorded)),
                problem -> SwingUtilities.invokeLater(() -> {
                    recording = null;
                    finishedRun();
                    status.show(new StatusNote("Aufnahme nicht gestartet: " + problem, StatusNote.Tone.ERROR,
                            null, null));
                }));
    }

    private void togglePause() {
        if (recording == null) {
            return;
        }
        if (recording.paused()) {
            recording.resume();
            status.show(StatusNote.plain("Aufnahme läuft"));
        } else {
            recording.pause();
            status.show(StatusNote.plain("Aufnahme angehalten – ❚❚ setzt fort"));
        }
    }

    /** Speichert die Aufnahme (Aktionen und, bei einem neuen Testfall, den Eintrag im Projekt). */
    private void recorded(TestSuite suite, TestCaseEntry entry, TestCase recorded) {
        recording = null;
        finishedRun();
        TestCase snapshot = new TestCase(recorded.name(), recorded.steps());
        TestProject changed = project.withSuite(project.suite(suite.id()).withCase(entry));
        saveCase(entry, snapshot, changed, "Aufnahme gespeichert: " + snapshot.size() + " Schritte",
                suite.id(), entry.id());
    }

    // ---- Bearbeiten ----

    /** Rolle, Benutzer oder Beschreibung eines Schritts geändert (Tabelle). */
    private void bindingEdited(String stepId, StepBinding binding) {
        if (shownEntry == null || shownSuite == null) {
            return;
        }
        TestCaseEntry entry = shownEntry.withBinding(stepId, binding);
        shownEntry = entry;
        TestProject changed = project.withSuite(project.suite(shownSuite.id()).withCase(entry));
        project = changed;
        steps.show(shownCase, entry, null);
        Path base = directory;
        save(() -> ProjectFile.write(changed, base), "Gespeichert");
    }

    void moveSelected(int delta) {
        int row = table.getSelectedRow();
        if (shownCase == null || row < 0 || row + delta < 0 || row + delta >= shownCase.size()) {
            return;
        }
        shownCase.move(row, row + delta);
        steps.show(shownCase, shownEntry, null);
        table.setRowSelectionInterval(row + delta, row + delta);
        writeShownCase(project);
    }

    void deleteStep(int row) {
        if (shownCase == null || row < 0 || row >= shownCase.size()) {
            return;
        }
        shownCase.remove(row);
        shownEntry = shownEntry.prunedTo(shownCase);
        TestProject changed = project.withSuite(project.suite(shownSuite.id()).withCase(shownEntry));
        project = changed;
        steps.show(shownCase, shownEntry, null);
        writeShownCase(changed);
    }

    private void writeShownCase(TestProject changed) {
        TestCase snapshot = new TestCase(shownCase.name(), shownCase.steps());
        TestCaseEntry entry = shownEntry;
        Path base = directory;
        save(() -> {
            TestCaseFile.write(snapshot, ProjectFile.caseFile(base, entry));
            ProjectFile.write(changed, base);
        }, "Gespeichert");
    }

    /** Neue Suite im Projekt (Verzeichnis entsteht mit dem ersten Testfall). */
    void createSuite(String name) {
        if (project == null) {
            return;
        }
        String id = TestProject.newId(name, project.suites().stream().map(TestSuite::id).toList());
        TestProject changed = project.withSuite(TestSuite.of(id, name, List.of()));
        Path base = directory;
        io.execute(() -> {
            try {
                ProjectFile.write(changed, base);
                SwingUtilities.invokeLater(() -> reopen(base, id, null));
            } catch (IOException broken) {
                SwingUtilities.invokeLater(() -> status.show(new StatusNote("Speichern fehlgeschlagen: "
                        + broken.getMessage(), StatusNote.Tone.ERROR, null, null)));
            }
        });
    }

    private void saveCase(TestCaseEntry entry, TestCase snapshot, TestProject changed, String done, String suiteId,
                          String caseId) {
        Path base = directory;
        io.execute(() -> {
            try {
                TestCaseFile.write(snapshot, ProjectFile.caseFile(base, entry));
                ProjectFile.write(changed, base);
                SwingUtilities.invokeLater(() -> {
                    reopen(base, suiteId, caseId);
                    status.show(StatusNote.plain(done));
                });
            } catch (IOException broken) {
                SwingUtilities.invokeLater(() -> status.show(new StatusNote("Speichern fehlgeschlagen: "
                        + broken.getMessage(), StatusNote.Tone.ERROR, null, null)));
            }
        });
    }

    private interface Write {
        void run() throws IOException;
    }

    private void save(Write write, String done) {
        io.execute(() -> {
            try {
                write.run();
                SwingUtilities.invokeLater(() -> status.show(StatusNote.plain(done)));
            } catch (IOException broken) {
                SwingUtilities.invokeLater(() -> status.show(new StatusNote("Speichern fehlgeschlagen: "
                        + broken.getMessage(), StatusNote.Tone.ERROR, null, null)));
            }
        });
    }

    /** Liest das Projekt neu und wählt danach Suite und Testfall. */
    private void reopen(Path base, String suiteId, String caseId) {
        try {
            TestProject read = ProjectFile.read(base);
            directory = base;
            show(read, read.name() + ": " + count(read) + " Testfälle", StatusNote.Tone.PLAIN);
            select(suiteId, caseId);
        } catch (IOException broken) {
            status.show(new StatusNote("Testprojekt nicht lesbar: " + broken.getMessage(), StatusNote.Tone.ERROR,
                    null, null));
        }
    }

    /** Wartet, bis alles geschrieben ist (für Tests). */
    void flush() throws Exception {
        io.submit(() -> { }).get();
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

    /**
     * Die Schritte eines Testfalls: Nr., Rolle, Benutzer, Schritt (Beschreibung, sonst Playwrights Aktion), Ergebnis.
     * Rolle, Benutzer und Schritt sind bearbeitbar; eine Änderung geht als neues Fachliches des Schritts hinaus.
     */
    static final class StepsModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Nr.", "Rolle", "Benutzer", "Schritt", "Ergebnis"};
        private final List<Object[]> rows = new ArrayList<>();
        private TestCase testCase;
        private TestCaseEntry entry;
        private java.util.function.BiConsumer<String, StepBinding> onEdit = (id, binding) -> { };

        void onEdit(java.util.function.BiConsumer<String, StepBinding> listener) {
            onEdit = listener;
        }

        void show(TestCase shown, TestCaseEntry shownEntry, CaseRunResult result) {
            rows.clear();
            testCase = shown;
            entry = shownEntry;
            if (shown != null) {
                List<StepResult> played = result == null || result.steps() == null ? List.of()
                        : result.steps().steps();
                for (int i = 0; i < shown.size(); i++) {
                    TestStep step = shown.step(i);
                    StepBinding binding = shownEntry.binding(step.id());
                    StepRole role = binding.role() != null ? binding.role() : StepRole.of(step);
                    String user = binding.user() != null ? binding.user()
                            : shownEntry.user() != null ? shownEntry.user() : "";
                    String what = binding.description().isEmpty() ? step.describe() : binding.description();
                    String outcome = i < played.size() ? outcome(played.get(i)) : "";
                    rows.add(new Object[]{i + 1, label(role), user, what, outcome});
                }
            }
            fireTableDataChanged();
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return entry != null && column >= 1 && column <= 3;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (entry == null || testCase == null || row >= testCase.size()) {
                return;
            }
            TestStep step = testCase.step(row);
            StepBinding binding = entry.binding(step.id());
            String text = value == null ? "" : value.toString().strip();
            StepBinding changed = switch (column) {
                case 1 -> binding.withRole(role(text));
                case 2 -> binding.withUser(text.isEmpty() || text.equals(entry.user()) ? null : text);
                case 3 -> binding.withDescription(text.equals(step.describe()) ? "" : text);
                default -> binding;
            };
            if (!changed.equals(binding)) {
                onEdit.accept(step.id(), changed);
            }
        }

        private static StepRole role(String text) {
            return switch (text) {
                case "Given" -> StepRole.GIVEN;
                case "When" -> StepRole.WHEN;
                case "Then" -> StepRole.THEN;
                default -> null;
            };
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
