package com.aresstack.spaceharvester.app.ui.proving;

import com.aresstack.spaceharvester.browser.runtime.BrowserChannel;
import com.aresstack.spaceharvester.browser.runtime.BrowserExecutables;
import com.aresstack.spaceharvester.provingground.player.JUnitExport;
import javax.swing.JFileChooser;
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
import com.aresstack.spaceharvester.app.ui.FindBar;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.controls.HelpButton;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.controls.CenterOffer;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
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
import java.awt.CardLayout;
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
import java.util.function.UnaryOperator;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
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
    /** Die zuletzt eingegebene Startadresse einer Aufnahme (App-Zustand). */
    static final String START_URL_KEY = "provingGround.startUrl";
    static final String RUN_TIP = "Ausführen: den gewählten Testfall, die Suite oder das ganze Projekt";
    static final String STOP_TIP = "Abbrechen: den laufenden Test beenden";
    static final String CREATE_TIP = "In diesem Ordner ein leeres Testprojekt anlegen";
    static final String SUITE_TIP = "Neue Suite anlegen";
    static final String RECORD_TIP = "Aufnehmen: in den gewählten Testfall, oder bei einer Suite in einen neuen";
    static final String PAUSE_TIP = "Aufnahme anhalten oder fortsetzen";
    static final String UP_TIP = "Schritt nach oben";
    static final String DOWN_TIP = "Schritt nach unten";
    static final String DELETE_TIP = "Schritt löschen";
    static final String SCREENSHOT_TIP = "Bildvergleich der ganzen Seite nach dem gewählten Schritt einfügen"
            + " (wie toHaveScreenshot): der erste Lauf legt das Referenzbild an, jeder weitere vergleicht";
    static final String EXPORT_TIP = "JUnit-Test (Playwright-Java-Code): speichern oder hier ausführen – Benutzer,"
            + " Passwörter und Einmalcodes übergibt Space Harvester aus KeePass";
    static final String EXPORT_NO_CASE = "JUnit-Export: zuerst einen Testfall wählen";
    static final String SCREENSHOT_NO_STEP = "Bildvergleich: zuerst einen Testfall mit Schritten wählen";
    /** Ordner des Testprojekts, solange keiner gewählt ist (unter dem Datenordner der App). */
    static final String DEFAULT_PROJECT = "Testprojekt";
    /** Name der Suite, die ● anlegt, wenn das Projekt noch keine hat. */
    static final String FIRST_SUITE = "Allgemein";
    static final String NO_PROJECT = "Noch kein Testprojekt in diesem Ordner – „Testprojekt anlegen“ in der Mitte";
    static final String NO_SUITE = "Noch keine Suite – „Erste Suite anlegen“ in der Mitte oder Plus links oben";
    static final String NO_CASES = "Noch keine Testfälle – mit dem roten Aufnahmeknopf einen aufnehmen";
    static final String RECORDING = "Aufnahme läuft – im Browser arbeiten; das schwarze Quadrat (Stopp) beendet und speichert";
    static final String RECORDING_PAUSED = "Aufnahme angehalten – der Pause-Knopf setzt fort";
    static final String OFFER_PROJECT = "Testprojekt anlegen";
    static final String OFFER_SUITE = "Erste Suite anlegen";
    static final String OFFER_RECORD = "Testfall aufnehmen";
    static final String STEPS = "Schritte";
    static final String HOOKS = "Hooks";
    /** Größe des „?“-Knopfs, wie die der übrigen Ansichten (Bereich, Leseansicht). */
    static final int HELP_SIZE = 24;
    /** Die „?“-Hilfe je Reiter (Angelo 28.11.): jeder Reiter erklärt sich selbst. */
    static final String HELP_STEPS = "Die Schritte des gewählten Testfalls, so wie Playwright sie aufgenommen hat: "
            + "jede Zeile ist Playwright-Java-Code und wird beim Abspielen genau so ausgeführt.\n\n"
            + "Rolle (Given, When, Then) und Beschreibung machen daraus einen lesbaren Ablauf. In der Spalte Benutzer "
            + "lässt sich ein Schritt einem anderen Testbenutzer geben; er läuft dann in dessen eigenem Browser. "
            + "„Testfall läuft als“ legt den Benutzer für alle übrigen Schritte fest.\n\n"
            + "Oben: ● nimmt weitere Schritte auf, ▶ spielt ab, das Bild fügt einen Bildvergleich ein, der Pfeil "
            + "speichert den Testfall als JUnit-Test oder führt ihn als JUnit-Test aus.\n\n"
            + "Werte aus KeePass statt fester Texte: {{user.login}} = Benutzername, {{secret.Passwort}} = Passwort des "
            + "KeePass-Eintrags, {{secret.PIN}} = sein Zusatzfeld „PIN“ (für jedes weitere Passwort ein Zusatzfeld), "
            + "{{otp()}} = aktueller Einmalcode aus dem Zusatzfeld „otp“, {{otp('pr')}} = der Code des Benutzers pr. "
            + "Die Aufnahme schreibt bei Passwortfeldern {{secret.…}} von selbst; in den Testdateien steht nie ein "
            + "Wert.";
    static final String HELP_HOOKS = "Hooks setzen Werte und prüfen Ergebnisse rund um die Schritte, für die gewählte "
            + "Ebene: das Projekt, eine Suite oder einen Testfall.\n\n"
            + "beforeAll und beforeEach setzen Variablen vor den Schritten (einmal je Lauf oder vor jedem Testfall), "
            + "z. B. start = die Startadresse; in den Schritten steht dann {{start}}. Vorlagen werden bei jeder "
            + "Verwendung neu ausgewertet. afterEach und afterAll prüfen danach, z. B. ob {{title()}} gleich "
            + "„Konto“ ist; „Prüfung“ und „Erwartet“ sagen wie.\n\n"
            + "Ausdrücke in {{…}} dürfen Funktionen nutzen: {{otp()}}, {{title()}}, {{url()}}, {{text('#id')}}. Werte "
            + "der Ebene darüber gelten weiter, eigene überdecken sie.";
    static final String HELP_PRECONDITIONS = "Vorbedingungen sind andere Testfälle, die vorher laufen, z. B. „Anmelden“: "
            + "einmal aufnehmen, überall als Vorbedingung eintragen. Ihr Browser bleibt offen, der Testfall macht "
            + "dort weiter, wo die Vorbedingung aufgehört hat.\n\n"
            + "Am Projekt eingetragen gelten sie für jeden Testfall, an einer Suite für deren Testfälle, am Testfall nur "
            + "für ihn. Jede läuft je Testfall einmal, auch wenn sie mehrfach eingetragen ist. Schlägt eine fehl, "
            + "läuft der Testfall nicht.";
    static final String HELP_USERS = "Die Testbenutzer des Projekts. Jeder hat einen Namen (z. B. sb) und einen "
            + "KeePass-Eintrag; Benutzername, Passwort und weitere Felder holt Space Harvester beim Abspielen still "
            + "über die bestehende KeePass-Anbindung, gespeichert wird nichts.\n\n"
            + "So geht es: In KeePass je Testbenutzer einen Eintrag mit Benutzername und Passwort anlegen. Das "
            + "OTP-Geheimnis als Zusatzfeld „otp“ (Base32 oder otpauth://-Adresse; „TOTP Seed“ geht auch), jedes "
            + "weitere Passwort als Zusatzfeld mit eigenem Namen, z. B. „PIN“. Hier den Eintrag wählen. In den "
            + "Schritten dann {{user.login}}, {{secret.Passwort}}, {{secret.PIN}} und {{otp()}}.\n\n"
            + "Der Standard-Benutzer gilt für alle Schritte ohne eigenen Benutzer. Jeder Benutzer läuft in seinem "
            + "eigenen Browser-Kontext, so lassen sich z. B. Sachbearbeiter und Prüfer in einem Testfall abwechseln.";
    static final String PRECONDITIONS = "Vorbedingungen";
    static final String USERS = "Benutzer";
    /** Benutzer des Testfalls „Standard“: der Standardbenutzer des Projekts. */
    static final String STANDARD_USER = "Standard des Projekts";
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
    /** Bericht des letzten Laufs, sichtbar bei Projekt und Suite. */
    private String lastRun = "";
    private final IconButton run = new IconButton(ComposerIcons.play(), RUN_TIP).compact();
    private final IconButton stop = new IconButton(ComposerIcons.stop(), STOP_TIP).compact();
    private final IconButton create = new IconButton(ComposerIcons.plus(12), CREATE_TIP).compact();
    private final IconButton record = new IconButton(Glyph.CIRCLE_FULL, RECORD_TIP).compact();
    private final IconButton pause = new IconButton(ComposerIcons.pause(), PAUSE_TIP).compact();
    private final IconButton up = new IconButton(ComposerIcons.chevronUp(), UP_TIP).compact();
    private final IconButton down = new IconButton(ComposerIcons.chevronDown(), DOWN_TIP).compact();
    private final IconButton delete = new IconButton(Glyph.TRASH, DELETE_TIP).compact();
    private final IconButton screenshot = new IconButton(ComposerIcons.picture(), SCREENSHOT_TIP).compact();
    private final IconButton export = new IconButton(ComposerIcons.export(), EXPORT_TIP).compact();
    /** Schreibt Dateien nacheinander, nie auf dem EDT. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "proving-ground-io");
        worker.setDaemon(true);
        return worker;
    });
    private CenterOffer offer;
    private Runnable offerAction = () -> { };
    private final ScopeEditor hooks = new ScopeEditor();
    private final PreconditionsEditor preconditions = new PreconditionsEditor();
    private final UsersEditor users = new UsersEditor();
    private final JComboBox<String> caseUser = Comic.combo();
    private final CardLayout cards = new CardLayout();
    /** Die Leiste mit der Lupe unter der Arbeitsfläche, wie überall in Space Harvester; sucht im gezeigten Reiter. */
    private final FindBar findBar = new FindBar(this::shownView);
    /** Die „?“-Hilfe unten rechts, je Reiter eine; nur die des gezeigten ist sichtbar. */
    private final java.util.Map<String, HelpButton> helps = new java.util.LinkedHashMap<>();
    private String shownCard = STEPS;
    private final JPanel views = new JPanel(cards);
    /**
     * Die Umschalter der Ansichten unten in der Fußleiste, als Symbolknöpfe wie die Reiter der Analysen (Angelo
     * 28.11.2026). Projekt, Suite und Testfall zeigen je ihre Auswahl daraus und merken, was zuletzt gewählt war.
     */
    private final com.aresstack.spaceharvester.app.ui.FootTabs viewTabs =
            new com.aresstack.spaceharvester.app.ui.FootTabs(viewIcons());
    private static final List<String> PROJECT_VIEWS = List.of(HOOKS, PRECONDITIONS, USERS);
    private static final List<String> SUITE_VIEWS = List.of(HOOKS, PRECONDITIONS);
    private static final List<String> CASE_VIEWS = List.of(STEPS, HOOKS, PRECONDITIONS);
    /** Je Art des Knotens (seine Ansichten) die zuletzt gewählte Ansicht. */
    private final Map<List<String>, String> chosenViews = new HashMap<>();
    private boolean switchingViews;
    /** Während die Ansicht gefüllt wird, sind Änderungen der Auswahlfelder keine Bearbeitung. */
    private boolean filling;
    /** Die Ansichten, die zum gewählten Knoten gehören, oder null. */
    private List<String> currentViews;
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
                askSuite();
            }
        });
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
        screenshot.addActionListener(event -> askScreenshotCheck());
        JPopupMenu exportMenu = new JPopupMenu();
        JMenuItem exportFile = new JMenuItem("Als JUnit-Test speichern …");
        exportFile.addActionListener(event -> askExport());
        JMenuItem exportRun = new JMenuItem("Als JUnit-Test ausführen (Werte aus KeePass)");
        exportRun.addActionListener(event -> runShownCaseAsJUnit());
        exportMenu.add(exportFile);
        exportMenu.add(exportRun);
        export.addActionListener(event -> exportMenu.show(export, 0, export.getHeight()));
        steps.onEdit(this::bindingEdited);
        table.getColumnModel().getColumn(0).setMaxWidth(48);
        table.getColumnModel().getColumn(1).setMaxWidth(110);
        JComboBox<String> roles = Comic.combo();
        for (String role : List.of(AUTO, "Given", "When", "Then")) {
            roles.addItem(role);
        }
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(roles));
        report.setEditable(false);
        caseUser.addActionListener(event -> caseUserChosen());
        JPanel userRow = new JPanel(new BorderLayout(8, 0));
        userRow.setOpaque(false);
        userRow.add(Comic.text("Testfall läuft als"), BorderLayout.WEST);
        userRow.add(caseUser, BorderLayout.CENTER);
        JPanel stepsView = new JPanel(new BorderLayout(0, 6));
        stepsView.setOpaque(false);
        stepsView.add(userRow, BorderLayout.NORTH);
        stepsView.add(Comic.scroll(table), BorderLayout.CENTER);
        views.setOpaque(false);
        views.add(stepsView, STEPS);
        views.add(hooks, HOOKS);
        views.add(preconditions, PRECONDITIONS);
        views.add(users, USERS);
        viewTabs.tabs().onSelect(name -> {
            if (!switchingViews && currentViews != null) {
                chosenViews.put(currentViews, name);
                showCard(name);
            }
        });
        viewTabs.bar().setVisible(false);
        // The report under every view: a run of the project or a suite shows its result there, too (Codex #1770).
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, views, Comic.scroll(report));
        center.setResizeWeight(0.65);
        center.setBorder(null);
        ComicDivider.install(center, Comic.PALETTE);
        offer = new CenterOffer(center, OFFER_PROJECT, CREATE_TIP).onAccept(() -> offerAction.run());
        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        tools.setOpaque(false);
        tools.add(up);
        tools.add(down);
        tools.add(screenshot);
        tools.add(export);
        tools.add(delete);
        tools.add(record);
        tools.add(pause);
        tools.add(run);
        tools.add(stop);
        content.add(DeckBars.head(title, tools), BorderLayout.NORTH);
        content.add(offer, BorderLayout.CENTER);
        JPanel help = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        help.setOpaque(false);
        for (String[] each : new String[][] {{STEPS, HELP_STEPS}, {HOOKS, HELP_HOOKS},
                {PRECONDITIONS, HELP_PRECONDITIONS}, {USERS, HELP_USERS}}) {
            HelpButton button = new HelpButton(HELP_SIZE, each[0], each[1]);
            helps.put(each[0], button);
            help.add(button);
        }
        findBar.help(help);
        // Die Umschalter der Ansichten stehen unten in der Fußleiste, wie die Reiter der Analysen (Angelo
        // 28.11.2026); oben bleiben nur die Aktionen.
        findBar.tools(viewTabs.bar());
        content.add(findBar, BorderLayout.SOUTH);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK), "find");
        getActionMap().put("find", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                findBar.focus();
            }
        });
        showCard(STEPS);
        add(new LeftSidebarPane(sidebar, content, 280).withDividerToggle(), BorderLayout.CENTER);

        String remembered = AppState.shared().get(PROJECT_KEY);
        Path start = remembered != null && !remembered.isBlank() ? Path.of(remembered)
                : dataDirectory.get().resolve("proving-ground").resolve(DEFAULT_PROJECT);
        folder.path(start);
        open(start);
    }

    /** Die Perspektive mit dieser Arbeitsfläche. */
    public Perspective perspective() {
        return new Perspective(Perspectives.PROVING_GROUND, "Proving Ground",
                "Webtests aufzeichnen, bearbeiten, ausführen und auswerten", true, null, this, null, null, null,
                null, status);
    }

    /** Zählt die Leseaufträge von {@link #open}; nur das Ergebnis des letzten gilt (nur im EDT gelesen/geschrieben). */
    private long openRequests;
    /** Ob das zuletzt angeforderte Öffnen noch liest (dann gibt es kein Angebot in der Mitte). */
    private boolean opening;

    /** Der Ordner des gezeigten Testprojekts. */
    Path directory() {
        return directory;
    }

    /** Liest das Testprojekt im Ordner, im Hintergrund. */
    void open(Path chosen) {
        if (chosen == null) {
            return;
        }
        AppState.shared().put(PROJECT_KEY, chosen.toString());
        long request = ++openRequests;
        // Until the new folder is read, the offer in the middle would act on the folder shown before (CI main
        // ffdd05e: „Testprojekt anlegen“ wrote into the remembered folder while the chosen one was still loading).
        opening = true;
        noOffer();
        CompletableFuture.supplyAsync(() -> {
            try {
                return Files.exists(chosen.resolve(ProjectFile.FILE_NAME)) ? ProjectFile.read(chosen) : null;
            } catch (IOException broken) {
                throw new IllegalStateException(broken.getMessage(), broken);
            }
        }).whenComplete((read, failure) -> SwingUtilities.invokeLater(() -> {
            if (request != openRequests) {
                return; // a later open() was asked for; an earlier read finishing last must not win
            }
            opening = false;
            directory = chosen;
            results.clear();
            if (failure != null) {
                show(null, "Testprojekt nicht lesbar: " + cause(failure), StatusNote.Tone.ERROR);
            } else if (read == null) {
                show(null, NO_PROJECT, StatusNote.Tone.WARNING);
            } else {
                List<String> problems = read.problems();
                show(read, problems.isEmpty() ? read.name() + ": " + Texts.count(count(read), "Testfall", "Testfälle")
                        : "Testprojekt fehlerhaft: " + String.join(" · ", problems),
                        problems.isEmpty() ? StatusNote.Tone.PLAIN : StatusNote.Tone.ERROR);
            }
        }));
    }

    private void show(TestProject read, String note, StatusNote.Tone tone) {
        project = read;
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
        if (read != null && read.suites().isEmpty() && tone == StatusNote.Tone.PLAIN) {
            status.show(StatusNote.plain(NO_SUITE));
        }
        updateOffer();
    }

    /** Das Angebot in der Mitte führt Schritt für Schritt: Projekt anlegen, Suite anlegen, Testfall aufnehmen. */
    private void updateOffer() {
        Node node = selectedNode();
        boolean busy = recording != null || !run.isEnabled() || opening;
        if (directory == null || busy) {
            noOffer();
        } else if (project == null) {
            offer(OFFER_PROJECT, CREATE_TIP, this::createProject);
        } else if (project.suites().isEmpty()) {
            offer(OFFER_SUITE, SUITE_TIP, this::askSuite);
        } else if (node != null && node.testCase() != null) {
            noOffer();
        } else if (node != null && node.suite() != null && !node.suite().cases().isEmpty()) {
            noOffer();
        } else if (node != null && node.suite() == null && count(project) > 0) {
            noOffer();
        } else {
            offer(OFFER_RECORD, RECORD_TIP, this::startRecording);
        }
    }

    private void offer(String label, String tip, Runnable action) {
        offer.button().setText(label);
        offer.button().setToolTipText(tip);
        offerAction = action;
        offer.offer(true);
        // solange der Weg geführt wird: leere Schrittansicht, keine Editoren
        viewTabs.bar().setVisible(false);
        showCard(STEPS);
    }

    private void noOffer() {
        offer.offer(false);
        viewTabs.bar().setVisible(currentViews != null);
        if (currentViews != null) {
            showCard(viewTabs.tabs().selected());
        }
    }

    private void askSuite() {
        String name = Dialogs.askText(this, "Neue Suite", "Name der Suite", "");
        if (name != null && !name.isBlank()) {
            createSuite(name.strip());
        }
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
        // Project and suite show the report of the last run, a test case its own result (below)
        report.setText(node == null || node.testCase() == null ? lastRun : "");
        updateUsers();
        showEditors(node);
        updateOffer();
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
            shownEntry = project.suite(node.suite().id()).testCase(entry.id());
            steps.show(testCase, shownEntry, result);
            report.setText(result == null ? "" : result.render());
        }));
    }

    /** Führt aus, was im Baum gewählt ist. */
    void runSelection() {
        if (project == null || directory == null) {
            status.show(new StatusNote(NO_PROJECT, StatusNote.Tone.WARNING, null, null));
            return;
        }
        if (count(project) == 0) {
            status.show(new StatusNote(project.suites().isEmpty() ? NO_SUITE : NO_CASES, StatusNote.Tone.WARNING,
                    null, null));
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
        start(only, null);
    }

    private void start(List<String> only, JUnitExport.Launch junit) {
        Path output = dataDirectory.get().resolve("proving-ground")
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        run.setEnabled(false);
        record.setEnabled(false);
        stop.setEnabled(true);
        offer.offer(false);
        status.show(StatusNote.plain("Test läuft …"));
        TestProject current = project;
        Path base = directory;
        runs.run(current, base, only, this::setup,
                () -> new KeePassTestCredentials(keePass), output, junit,
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
        status.show(new StatusNote(result.passed() ? "Bestanden: " + Texts.count(result.cases().size(), "Testfall", "Testfälle")
                : failed + " von " + result.cases().size() + " Testfällen fehlgeschlagen",
                result.passed() ? StatusNote.Tone.PLAIN : StatusNote.Tone.ERROR, null, null));
        lastRun = result.render() + "\nTraces und Bildschirmfotos: " + output;
        selected();
        tree.repaint();
    }

    private void finishedRun() {
        run.setEnabled(true);
        record.setEnabled(true);
        stop.setEnabled(false);
        pause.setEnabled(false);
        updateOffer();
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

    private static Map<String, javax.swing.Icon> viewIcons() {
        Map<String, javax.swing.Icon> icons = new java.util.LinkedHashMap<>();
        icons.put(STEPS, ProvingTabIcons.steps());
        icons.put(HOOKS, ProvingTabIcons.hooks());
        icons.put(PRECONDITIONS, ProvingTabIcons.preconditions());
        icons.put(USERS, ProvingTabIcons.users());
        return icons;
    }

    /** Die Umschalter der Ansichten in der Fußleiste (für Tests). */
    com.aresstack.spaceharvester.app.ui.FootTabs viewTabs() {
        return viewTabs;
    }

    /** Zeigt den Reiter {@code name}; Hilfe und Suche folgen ihm. */
    private void showCard(String name) {
        shownCard = name;
        cards.show(views, name);
        helps.forEach((card, button) -> button.setVisible(card.equals(name)));
        findBar.areaChanged();
    }

    /** Der gezeigte Reiter, in dem die Lupe sucht. */
    private JComponent shownView() {
        for (java.awt.Component card : views.getComponents()) {
            if (card.isVisible() && card instanceof JComponent view) {
                return view;
            }
        }
        return views;
    }

    /** Der gezeigte Reiter (für Tests). */
    String shownCard() {
        return shownCard;
    }

    /** Die „?“-Hilfe des gezeigten Reiters (für Tests). */
    HelpButton shownHelp() {
        return helps.get(shownCard);
    }

    /** Die Leiste mit der Lupe (für Tests). */
    FindBar findBar() {
        return findBar;
    }

    /** Zeigt zum gewählten Knoten die passenden Ansichten: Projekt, Suite oder Testfall. */
    private void showEditors(Node node) {
        currentViews = null;
        if (project == null || node == null) {
            showCard(STEPS);
            viewTabs.bar().setVisible(false);
            return;
        }
        List<String> toggle;
        if (node.testCase() != null) {
            toggle = CASE_VIEWS;
            TestCaseEntry entry = project.suite(node.suite().id()).testCase(node.testCase().id());
            String self = TestProject.reference(node.suite(), entry);
            hooks.show(entry.scope(), true, scope -> editCase(entry.id(), c -> c.withScope(scope)));
            preconditions.show(project, entry.preconditions(), self,
                    list -> editCase(entry.id(), c -> c.withPreconditions(list)));
            fillCaseUser(entry);
        } else if (node.suite() != null) {
            toggle = SUITE_VIEWS;
            TestSuite suite = project.suite(node.suite().id());
            hooks.show(suite.scope(), false, scope -> editSuite(suite.id(), s -> s.withScope(scope)));
            preconditions.show(project, suite.preconditions(), null,
                    list -> editSuite(suite.id(), s -> s.withPreconditions(list)));
        } else {
            toggle = PROJECT_VIEWS;
            hooks.show(project.scope(), false, scope -> edit(p -> p.withScope(scope)));
            preconditions.show(project, project.preconditions(), null, list -> edit(p -> p.withPreconditions(list)));
            users.show(project.users(), project.defaultUser(), (list, standard, renamed) -> {
                edit(p -> p.withUsers(list, standard, renamed));
                updateUsers();
            });
            loadVaultEntries();
        }
        currentViews = toggle;
        String chosen = chosenViews.getOrDefault(toggle, toggle.get(0));
        switchingViews = true;
        try {
            viewTabs.tabs().showOnly(toggle);
            viewTabs.tabs().select(chosen);
        } finally {
            switchingViews = false;
        }
        viewTabs.bar().setVisible(true);
        showCard(chosen);
    }

    /** Die Auswahl „Testfall läuft als“: Standard des Projekts oder einer der Benutzer. */
    private void fillCaseUser(TestCaseEntry entry) {
        filling = true;
        try {
            caseUser.removeAllItems();
            caseUser.addItem(STANDARD_USER);
            project.users().forEach(user -> caseUser.addItem(user.name()));
            caseUser.setSelectedItem(entry.user() == null ? STANDARD_USER : entry.user());
        } finally {
            filling = false;
        }
    }

    private void caseUserChosen() {
        if (filling || shownSuite == null || selectedNode() == null || selectedNode().testCase() == null) {
            return;
        }
        Object chosen = caseUser.getSelectedItem();
        String user = chosen == null || STANDARD_USER.equals(chosen) ? null : chosen.toString();
        String caseId = selectedNode().testCase().id();
        editCase(caseId, c -> c.withUser(user));
        if (shownEntry != null && shownEntry.id().equals(caseId)) {
            steps.show(shownCase, shownEntry, results.get(TestProject.reference(shownSuite, shownEntry)));
        }
    }

    /** Die Titel der KeePass-Einträge für die Benutzer-Tabelle, still gelesen; ohne Kopplung bleibt nur Eintippen. */
    private void loadVaultEntries() {
        CompletableFuture.supplyAsync(() -> {
            KeePassLink link = keePass.get();
            if (link == null || !link.paired()) {
                return List.<String>of();
            }
            try {
                return link.entriesSilently().stream().map(e -> e.title())
                        .filter(t -> t != null && !t.isBlank()).sorted().toList();
            } catch (Exception unavailable) {
                return List.<String>of();
            }
        }).thenAccept(titles -> SwingUtilities.invokeLater(() -> users.entries(titles)));
    }

    /** Ändert das Projekt und speichert es; der gezeigte Testfall folgt der Änderung. */
    void edit(UnaryOperator<TestProject> change) {
        if (project == null || directory == null) {
            return;
        }
        TestProject changed = change.apply(project);
        project = changed;
        if (shownSuite != null) {
            shownSuite = changed.suite(shownSuite.id());
            if (shownEntry != null && shownSuite != null) {
                shownEntry = shownSuite.testCase(shownEntry.id());
            }
        }
        Path base = directory;
        List<String> problems = changed.problems();
        save(() -> ProjectFile.write(changed, base), problems.isEmpty() ? "Gespeichert"
                : "Gespeichert – noch offen: " + String.join(" · ", problems));
    }

    private void editSuite(String suiteId, UnaryOperator<TestSuite> change) {
        edit(p -> p.withSuite(change.apply(p.suite(suiteId))));
    }

    private void editCase(String caseId, UnaryOperator<TestCaseEntry> change) {
        Node node = selectedNode();
        if (node == null || node.suite() == null) {
            return;
        }
        String suiteId = node.suite().id();
        editSuite(suiteId, s -> s.withCase(change.apply(s.testCase(caseId))));
    }

    ScopeEditor hooks() {
        return hooks;
    }

    PreconditionsEditor preconditions() {
        return preconditions;
    }

    UsersEditor users() {
        return users;
    }

    TestProject project() {
        return project;
    }

    // ---- Aufnehmen ----

    /** ● : in den gezeigten Testfall (anhängen) oder, bei gewählter Suite, in einen neuen Testfall. */
    void startRecording() {
        Node node = selectedNode();
        if (project == null || directory == null) {
            status.show(new StatusNote(NO_PROJECT, StatusNote.Tone.WARNING, null, null));
            return;
        }
        if (node == null || node.suite() == null) {
            // Projekt gewählt: in die erste Suite, oder in eine neue, wenn es noch keine gibt
            TestSuite suite = project.suites().isEmpty()
                    ? TestSuite.of(TestProject.newId(FIRST_SUITE, List.of()), FIRST_SUITE, List.of())
                    : project.suites().get(0);
            recordNew(suite);
            return;
        }
        if (node.testCase() != null) {
            if (shownCase == null) {
                return;
            }
            record(node.suite(), shownEntry, new TestCase(shownCase.name(), shownCase.steps()), null);
        } else {
            recordNew(node.suite());
        }
    }

    /** Ein neuer Testfall in {@code suite} mit eigener Datei {@code <suite>/<id>.jsonl}. */
    static TestCaseEntry newCase(TestSuite suite, String name) {
        String id = TestProject.newId(name, suite.cases().stream().map(TestCaseEntry::id).toList());
        return TestCaseEntry.of(id, name, ProjectFile.defaultCaseFile(suite.id(), id));
    }

    /** Fragt Name und Startadresse des neuen Testfalls und nimmt auf. */
    private void recordNew(TestSuite suite) {
        String name = Dialogs.askText(this, "Neuer Testfall", "Name des Testfalls", "");
        if (name == null || name.isBlank()) {
            return;
        }
        String remembered = AppState.shared().get(START_URL_KEY);
        String url = Dialogs.askOptionalText(this, "Neuer Testfall",
                "Startadresse – leer lassen, um sie im Browser einzugeben",
                remembered == null ? "" : remembered);
        if (url == null) {
            return;
        }
        if (!url.isBlank()) {
            AppState.shared().put(START_URL_KEY, url.strip());
        }
        record(suite, newCase(suite, name.strip()), new TestCase(name.strip()), url);
    }

    private void record(TestSuite suite, TestCaseEntry entry, TestCase target, String startUrl) {
        run.setEnabled(false);
        record.setEnabled(false);
        stop.setEnabled(true);
        pause.setEnabled(true);
        title.setText(entry.name() + " · Aufnahme");
        offer.offer(false);
        status.show(StatusNote.plain(RECORDING));
        recording = runs.record(target, startUrl, this::setup,
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
            status.show(StatusNote.plain(RECORDING_PAUSED));
        }
    }

    /** Speichert die Aufnahme (Aktionen und, bei einem neuen Testfall, den Eintrag im Projekt). */
    private void recorded(TestSuite suite, TestCaseEntry entry, TestCase recorded) {
        recording = null;
        finishedRun();
        TestCase snapshot = new TestCase(recorded.name(), recorded.steps());
        TestSuite base = project.suite(suite.id()) != null ? project.suite(suite.id()) : suite;
        TestProject changed = project.withSuite(base.withCase(entry));
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

    private void askScreenshotCheck() {
        if (shownCase == null || shownCase.size() == 0) {
            status.show(new StatusNote(SCREENSHOT_NO_STEP, StatusNote.Tone.WARNING, null, null));
            return;
        }
        int row = table.getSelectedRow() >= 0 ? table.getSelectedRow() : shownCase.size() - 1;
        String name = Dialogs.askText(this, "Bildvergleich", "Name des Referenzbilds",
                shownCase.name() + "-" + (row + 2));
        if (name != null) {
            insertScreenshotCheck(row, name);
        }
    }

    /**
     * Fügt nach Schritt {@code row} einen Bildvergleich der ganzen Seite dieses Schritts ein; der erste Lauf legt das
     * Referenzbild an, jeder weitere vergleicht.
     */
    void insertScreenshotCheck(int row, String name) {
        if (shownCase == null || row < 0 || row >= shownCase.size() || name == null || name.isBlank()) {
            return;
        }
        shownCase.insert(row + 1, TestStep.assertScreenshot(shownCase.step(row).pageGuid(), null, name.strip()));
        steps.show(shownCase, shownEntry, null);
        table.setRowSelectionInterval(row + 1, row + 1);
        writeShownCase(project);
    }

    private void askExport() {
        if (shownCase == null) {
            status.show(new StatusNote(EXPORT_NO_CASE, StatusNote.Tone.WARNING, null, null));
            return;
        }
        JUnitExport.Result result = exportShownCase();
        JFileChooser chooser = new JFileChooser(directory == null ? null : directory.toFile());
        chooser.setSelectedFile(new java.io.File(chooser.getCurrentDirectory(), result.className() + ".java"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            writeExport(result, chooser.getSelectedFile().toPath());
        }
    }

    /**
     * Der gezeigte Testfall als JUnit-Test, mit dem Browser aus Einstellungen › Browser; Schritte anderer Benutzer
     * laufen darin in deren eigenem Browser-Kontext.
     */
    JUnitExport.Result exportShownCase() {
        List<String> users = new ArrayList<>();
        for (int i = 0; i < shownCase.size(); i++) {
            users.add(project == null || shownEntry == null ? null : project.userName(shownEntry, shownCase.step(i)));
        }
        return JUnitExport.export(new TestCase(shownCase.name(), shownCase.steps()), users, junitLaunch());
    }

    private JUnitExport.Launch junitLaunch() {
        ProvingGroundRuns.Setup current = setup();
        BrowserChannel channel = BrowserChannel.of(current.channel());
        return new JUnitExport.Launch(channel.firefoxFamily(), channel.launchChannel(),
                BrowserExecutables.effective(channel.id(), current.executable()));
    }

    /**
     * Führt den gezeigten Testfall als seinen exportierten JUnit-Test aus; Benutzer, Passwörter und Einmalcodes
     * übergibt Space Harvester aus KeePass, der Test liest KeePass nie selbst.
     */
    void runShownCaseAsJUnit() {
        if (shownCase == null || shownSuite == null || shownEntry == null) {
            status.show(new StatusNote(EXPORT_NO_CASE, StatusNote.Tone.WARNING, null, null));
            return;
        }
        start(List.of(TestProject.reference(shownSuite, shownEntry)), junitLaunch());
    }

    void writeExport(JUnitExport.Result result, Path target) {
        String done = "Exportiert: " + target.getFileName() + (result.notExported().isEmpty() ? ""
                : " – nicht übernommen (braucht Proving Ground): " + String.join(" · ", result.notExported()));
        save(() -> Files.writeString(target, result.source(), java.nio.charset.StandardCharsets.UTF_8), done);
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
            show(read, read.name() + ": " + Texts.count(count(read), "Testfall", "Testfälle"), StatusNote.Tone.PLAIN);
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

    /** Das Angebot in der Mitte (für Tests). */
    CenterOffer offer() {
        return offer;
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
                    String what = !binding.description().isEmpty() ? binding.description()
                            : step.code() != null ? step.code().strip() : step.describe();
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
