package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.controls.TabHeader;
import com.aresstack.spaceharvester.app.ui.ParserModelSetup;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.application.check.CheckUnavailableException;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerUnavailableException;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Der Unterreiter „Analysen → Aussagen“ (#499): experimentelle Aussagenanalyse der geöffneten Seite mit MaltParser.
 * Zeigt eine gespeicherte Analyse ohne neu zu rechnen; nur „Mit MaltParser analysieren“ startet den Parser, im
 * Hintergrund und abbrechbar. Kernaussagen zuerst, dann die übrigen; je Aussage Beteiligte mit Rolle, Aktiv/Passiv,
 * Satzart, Beleg und Hinweise. Ein Klick auf eine Aussage springt zum Beleg im Reiter „Text“. Darunter die belegten
 * Verbindungen zu anderen manuell analysierten Seiten (lexikalische Kandidaten, keine Identität) und die
 * aufklappbaren Syntaxdetails (CoNLL-U). Späte Ergebnisse einer verlassenen Seite werden verworfen.
 *
 * <p>Alle öffentlichen Methoden nur auf dem EDT; Core-Aufrufe laufen über {@link Background}.
 */
final class StatementsTab extends JPanel {

    static final String ANALYZE = "Mit MaltParser analysieren";
    static final String DELETE_TIP = "Aussagen-Analyse dieser Seite löschen";
    static final String DELETE_QUESTION = "Wirklich die Aussagen-Analyse dieser Seite samt Anhängen löschen? Die Seite "
            + "bleibt.";
    static final String CANCEL = "Abbrechen";
    static final String NOT_ANALYSED = "Noch nicht analysiert. „" + ANALYZE + "“ zerlegt die Sätze dieser Seite "
            + "lokal; beim ersten Mal wird das Modell geladen (einige Sekunden).";
    static final String NOT_AVAILABLE = "In dieser Installation ist kein Parsermodell hinterlegt.";
    static final String CHECKING = "Aussagen werden geprüft …";
    static final String RUNNING = "Wird analysiert … (beim ersten Mal wird das Modell geladen)";
    static final String NO_PAGE = "Keine Seite geladen.";
    static final String LOADING_PAGE = "Seite wird geladen …";
    static final String PAGE_UNAVAILABLE = "Seite nicht geladen – Aussagen nicht verfügbar.";
    static final String SIMPLIFY = "Sätze vereinfachen";
    static final String WITH_STAGE = "mit Vorstufe „" + SIMPLIFY + "“";
    static final String SIMPLIFY_TIP = "Gilt für „" + ANALYZE + "“: Ein Chat-Modell formt die Sätze vor dem MaltParser zu einfachen "
            + "Hauptsätzen um; nur für Seiten mit KI-Freigabe. Betriebsart und Modell unter Einstellungen › Modelle";
    static final String SIMPLIFY_NO_MODEL = "Die Vorstufe „" + SIMPLIFY + "“ lief nicht: Es ist kein Modell dafür "
            + "eingestellt (Einstellungen › Modelle).";
    static final String SIMPLIFY_NOT_RELEASED = "Die Vorstufe „" + SIMPLIFY + "“ lief nicht: Die Seite ist nicht für "
            + "die KI freigegeben.";
    static final String STAGE_WITHDRAWN = "Die KI-Freigabe der Seite wurde entzogen; die Aussagen der Vorstufe sind "
            + "entfernt. Bitte neu ohne Vorstufe rechnen.";
    static final String NO_EVIDENCE = "Belege und Sprünge entfallen, weil sich der Text geändert hat.";

    private final Supplier<Actions> workspace;
    private final Runner runner;
    /** Installation des Parsermodells; {@code null} in Tests ohne Installation. */
    private final ParserModelSetup setup;
    /** Meldung „Parsermodell fehlt“ mit Link, Anleitung und „Jetzt installieren“; sonst unsichtbar. */
    final JPanel modelBanner = new JPanel(new BorderLayout(0, 6));
    final JTextArea modelText = note();
    final JLabel modelLink = new JLabel();
    final JLabel modelProgress = Comic.muted(" ");
    final com.aresstack.enterpriseai.ui.comic.control.ComposerButton installModel = Comic.button(
            ParserModelSetup.INSTALL_NOW, "Lädt das deutsche MaltParser-Modell aus dem GitHub-Release, prüft es und "
                    + "entpackt es ins Datenverzeichnis");
    private ParserModelSetup.State modelState = ParserModelSetup.State.INSTALLED;
    private final TabHeader head = new TabHeader("Aussagen dieser Seite");
    private final JLabel progress = Comic.muted(" ");
    private final JTextArea hint = note();
    private final JPanel list = new JPanel();
    private final JTextArea syntax = Comic.code();
    private final JPanel syntaxPanel = new JPanel(new BorderLayout(0, 4));
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton analyze = Comic.button(ANALYZE,
            "Analysiert nur diese Seite lokal mit MaltParser und übernimmt die Aussagen in den Wissensgraphen");
    /** „Aussagen prüfen“ (ADR 0032, FL4) für die offene Seite; nur mit aktueller Analyse. */
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton check = Comic.button(CheckTexts.JOB,
            "Lässt das Chat-Modell die Aussagen dieser Seite beurteilen; nur für Seiten mit KI-Freigabe");
    /** „Aussagen-Analyse löschen“ für die offene Seite (Angelo 17.11.2026); nur mit gespeicherter Analyse. */
    private final IconButton delete = new IconButton(Glyph.TRASH, DELETE_TIP);
    private Predicate<String> confirmDelete = question -> Dialogs.confirm(this, "Aussagen-Analyse löschen?",
            question);
    /** Ob eine Analyse gespeichert ist (aktuell oder veraltet): nur dann lässt sie sich löschen. */
    private boolean stored;
    /** Ob die angezeigte Analyse aktuell ist: nur dann lässt sie sich prüfen. */
    private boolean current;
    /** „mit Vorstufe“ (ADR 0032, FL3): gilt für den nächsten Klick auf „Mit MaltParser analysieren“. */
    private final javax.swing.JCheckBox simplify = Comic.checkBox(WITH_STAGE);
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton cancel = Comic.button(CANCEL,
            "Bricht den laufenden Vorgang ab; die bisherige Analyse bleibt");
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton syntaxToggle = Comic.button(
            "Syntaxdetails anzeigen", "Vollständige UD-Abhängigkeiten (CoNLL-U) zur Diagnose");
    private final List<String> lines = new ArrayList<>();
    private final List<Runnable> jumps = new ArrayList<>();
    private Consumer<List<TermsTab.Span>> onJump = spans -> { };
    private Runnable onAnalysed = () -> { };
    private Consumer<String> onStatus = text -> { };
    /** Die Aussagen als Text für die Zwischenablage, wie angezeigt. */
    private final StringBuilder copyText = new StringBuilder();

    /** Der Inhalt des Reiters für die Zwischenablage; leer ohne angezeigte Aussagen. */
    String copyText() {
        return copyText.toString();
    }
    /** Die Erläuterung zum Stand, ob sie im Reiter oder in der Statuszeile steht. */
    private String explanation = " ";
    private PageSnapshot snapshot;
    private PageText text;
    /** Steigt bei jeder Seite; Ergebnisse mit anderer Nummer gehören zu einer verlassenen Seite. */
    private int generation;
    /** Steigt bei jeder Seite und jeder Analyse; späte Syntaxdetails einer früheren Fassung werden verworfen. */
    private int syntaxGeneration;
    /** Laufende Analyse; bleibt nach „Abbrechen“ gesetzt, bis ihr Lauf wirklich endet. */
    private AtomicBoolean running;

    /** @param workspace die Aktionen der laufenden App oder {@code null}, solange keine läuft */
    StatementsTab(Supplier<Workspace> workspace, ParserModelSetup setup,
                  Supplier<com.aresstack.spaceharvester.application.configuration.SimplificationSettings> simplification,
                  Supplier<com.aresstack.spaceharvester.application.configuration.CheckSettings> checkSettings) {
        this(() -> Actions.of(workspace.get(), simplification, checkSettings), new Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                Background.run(work, onSuccess, onFailure);
            }
        }, setup);
    }

    StatementsTab(Supplier<Actions> workspace, Runner runner) {
        this(workspace, runner, null);
    }

    StatementsTab(Supplier<Actions> workspace, Runner runner, ParserModelSetup setup) {
        super(new BorderLayout(0, 10));
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.setup = setup;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        simplify.setToolTipText(SIMPLIFY_TIP);
        head.badge("Experimentell", Comic.PETROL).action(progress, cancel, analyze, delete)
                .copy(copyText::toString);
        analyze.addActionListener(e -> analyze());
        delete.addActionListener(e -> deleteAnalysis());
        delete.setEnabled(false);
        check.addActionListener(e -> check());
        check.setEnabled(false);
        cancel.addActionListener(e -> cancelRun());
        cancel.setVisible(false);
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        syntax.setRows(12);
        syntaxPanel.setOpaque(false);
        JPanel toggleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        toggleRow.setOpaque(false);
        toggleRow.add(syntaxToggle);
        syntaxPanel.add(toggleRow, BorderLayout.NORTH);
        syntaxToggle.addActionListener(e -> toggleSyntax());
        JPanel body = new com.aresstack.spaceharvester.app.ui.controls.ScrollColumn(new BorderLayout(0, 8));
        body.setOpaque(false);
        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.setOpaque(false);
        north.add(modelBanner(), BorderLayout.NORTH);
        north.add(hint, BorderLayout.CENTER);
        body.add(north, BorderLayout.NORTH);
        body.add(list, BorderLayout.CENTER);
        body.add(syntaxPanel, BorderLayout.SOUTH);
        // Die Option steht in einer eigenen Zeile rechts unter dem Knopf, zu dem sie gehört: In der Kopfzeile selbst
        // überdeckt sie bei schmalem Fenster das Abzeichen neben dem Titel.
        // „Aussagen prüfen“ steht in derselben Zeile links: Neben „Mit MaltParser analysieren“ in der Kopfzeile ist bei
        // schmalem Fenster kein Platz, und rechts neben dem Ankreuzfeld läse es sich, als gälte das Feld für die Prüfung.
        JPanel options = new JPanel(new BorderLayout(8, 0));
        options.setOpaque(false);
        JPanel checkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        checkRow.setOpaque(false);
        checkRow.add(check);
        options.add(checkRow, BorderLayout.WEST);
        options.add(simplify, BorderLayout.EAST);
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);
        top.add(head, BorderLayout.NORTH);
        top.add(options, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(Comic.scroll(body), BorderLayout.CENTER);
        render(PageStatements.missing(PageStatements.NOT_ANALYSED), List.of());
        if (setup != null) {
            setup.listen(this::modelStatus);
        }
    }

    /** Baut die Meldung „Parsermodell fehlt“ (Strang F stellt sie auf den MessageBanner der Bibliothek um). */
    private JComponent modelBanner() {
        modelBanner.setOpaque(true);
        modelBanner.setBackground(new Color(0xFFF7E6));
        modelBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, Comic.ORANGE),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        JLabel heading = Comic.text("Parsermodell nicht installiert");
        heading.setFont(Comic.semiBold(13f));
        modelText.setOpaque(false);
        modelLink.setForeground(Comic.BLUE);
        modelLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        modelLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (setup != null) {
                    setup.releasePage().ifPresent(StatementsTab::browse);
                }
            }
        });
        installModel.addActionListener(e -> {
            if (setup == null) {
                return;
            }
            if (setup.status().state() == ParserModelSetup.State.INSTALLING) {
                setup.cancel();
            } else {
                setup.install();
            }
        });
        JPanel text = new JPanel(new BorderLayout(0, 4));
        text.setOpaque(false);
        text.add(heading, BorderLayout.NORTH);
        text.add(modelText, BorderLayout.CENTER);
        text.add(modelLink, BorderLayout.SOUTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(installModel);
        actions.add(modelProgress);
        modelBanner.add(text, BorderLayout.CENTER);
        modelBanner.add(actions, BorderLayout.SOUTH);
        modelBanner.setVisible(false);
        return modelBanner;
    }

    /** Stand der Installation: Meldung zeigen, solange das Modell fehlt; nach Erfolg ausblenden. */
    void modelStatus(ParserModelSetup.Status status) {
        modelState = status.state();
        boolean installing = status.state() == ParserModelSetup.State.INSTALLING;
        boolean show = status.state() == ParserModelSetup.State.NOT_INSTALLED || installing;
        if (show) {
            String directory = setup == null ? "" : setup.directory().map(Object::toString).orElse("");
            modelText.setText("„" + ANALYZE + "“ braucht das deutsche MaltParser-Modell. „"
                    + ParserModelSetup.INSTALL_NOW + "“ lädt es aus dem GitHub-Release und richtet es ein. Manuell: "
                    + "die ZIP " + (setup == null ? "" : setup.zipName()) + " von der Release-Seite herunterladen "
                    + "und nach " + directory + " entpacken.");
            String page = setup == null ? "" : setup.releasePage().orElse("");
            modelLink.setText("<html><u>Release-Seite öffnen</u></html>");
            modelLink.setToolTipText(page);
            installModel.setText(installing ? ParserModelSetup.CANCEL : ParserModelSetup.INSTALL_NOW);
            String message = status.message() == null ? " " : status.message();
            if (installing && status.percent() >= 0) {
                message = status.percent() + " % – " + message;
            }
            modelProgress.setText(message);
            modelProgress.setForeground(status.failed() ? Comic.ORANGE : Comic.MUTED);
        }
        modelBanner.setVisible(show);
        if (status.state() == ParserModelSetup.State.INSTALLED && NOT_AVAILABLE_MODEL.equals(hint.getText())) {
            hint.setText(status.message() == null ? " " : status.message());
        }
        analyze.setEnabled(running == null && snapshot != null && !show);
        modelBanner.revalidate();
        modelBanner.repaint();
    }

    private static final String NOT_AVAILABLE_MODEL = "Das Parsermodell fehlt; siehe Meldung oben.";

    private boolean modelMissing() {
        return modelState == ParserModelSetup.State.NOT_INSTALLED
                || modelState == ParserModelSetup.State.INSTALLING;
    }

    private static void browse(String target) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(target));
            }
        } catch (Exception ignored) {
            // Ohne Browser bleibt die Adresse im Tooltip lesbar.
        }
    }

    /** Meldet eine neu gespeicherte Analyse (etwa damit der Graph-Reiter neu lädt). */
    void onAnalysed(Runnable listener) {
        onAnalysed = Objects.requireNonNull(listener, "listener");
    }

    /** {@code listener} bekommt die Erläuterung zur gezeigten Analyse für die Statuszeile des Hauptfensters. */
    void onStatus(Consumer<String> listener) {
        onStatus = Objects.requireNonNull(listener, "listener");
    }

    /** {@code listener} springt zu Fundstellen im Reiter „Text“ ({@code PageDetailPanel#jumpTo}). */
    void onJump(Consumer<List<TermsTab.Span>> listener) {
        onJump = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Die geöffnete Seite mit ihrem Lesetext; lädt die gespeicherte Analyse im Hintergrund, rechnet nie. Eine laufende
     * Analyse der vorigen Seite wird abgebrochen, ihr Ergebnis verworfen.
     */
    void page(PageSnapshot page, PageText readerText) {
        if (page != null && sameRunningPage(page.key()) && sameText(readerText)) {
            // Dieselbe Seite mit demselben Lesetext neu geladen (Anmeldung, „Aktualität prüfen“): die laufende
            // Analyse läuft weiter. Bei geändertem Text gilt sie nicht mehr und wird wie bei einem Seitenwechsel
            // abgebrochen.
            snapshot = page;
            text = readerText;
            return;
        }
        if (page == null) {
            neutral(NO_PAGE);
            return;
        }
        if (setup != null) {
            // Ein von Hand entpacktes Modell gilt ab der nächsten Seite ohne Neustart.
            setup.refresh();
        }
        generation++;
        syntaxGeneration++;
        cancelRun();
        snapshot = page;
        text = readerText;
        syntax.setText("");
        showSyntax(false);
        Actions actions = workspace.get();
        if (actions == null) {
            render(PageStatements.missing(PageStatements.NOT_ANALYSED), List.of());
            return;
        }
        int mine = generation;
        loading();
        runner.run(() -> new Loaded(actions.statements(page), actions.statementAnalysisAvailable(),
                actions.statementLinks(page.key())), loaded -> {
            if (mine != generation) {
                return;
            }
            render(loaded.statements(), loaded.links());
            if (!loaded.available()) {
                analyze.setEnabled(false);
                hint.setText(NOT_AVAILABLE);
            }
        }, error -> {
            if (mine != generation) {
                return;
            }
            render(PageStatements.missing(PageStatements.NOT_ANALYSED), List.of());
            hint.setText("Aussagen nicht geladen: " + Problems.describe(error).title());
        });
    }

    /** Lädt die gespeicherte Analyse der geöffneten Seite neu (z. B. nachdem alle Aussagen gelöscht wurden). */
    void reload() {
        if (snapshot != null) {
            page(snapshot, text);
        }
    }

    /**
     * Eine Seite wird geladen: neutraler Zustand ohne Aussage über die Analyse. Läuft gerade eine Analyse genau
     * dieser Seite, bleibt sie und ihr Stand stehen.
     */
    void pending(com.aresstack.spaceharvester.application.snapshot.PageKey key) {
        if (key != null && sameRunningPage(key)) {
            return;
        }
        neutral(LOADING_PAGE);
    }

    /** Die Seite konnte nicht geladen werden: keine Aussage über ihre Analyse. */
    void unavailable() {
        neutral(PAGE_UNAVAILABLE);
    }

    private boolean sameText(PageText readerText) {
        return text != null && readerText != null && text.text().equals(readerText.text());
    }

    private boolean sameRunningPage(com.aresstack.spaceharvester.application.snapshot.PageKey key) {
        return running != null && !running.get() && snapshot != null && snapshot.key().equals(key);
    }

    private void neutral(String message) {
        generation++;
        syntaxGeneration++;
        cancelRun();
        snapshot = null;
        text = null;
        copyText.setLength(0);
        syntax.setText("");
        showSyntax(false);
        syntaxPanel.setVisible(false);
        list.removeAll();
        lines.clear();
        jumps.clear();
        setState(" ", Comic.MUTED);
        hint.setText(message);
        analyze.setEnabled(false);
        current = false;
        stored = false;
        check.setEnabled(false);
        delete.setEnabled(false);
        list.revalidate();
        list.repaint();
    }

    private void analyze() {
        Actions actions = workspace.get();
        PageSnapshot page = snapshot;
        if (actions == null || page == null || running != null) {
            return;
        }
        int mine = generation;
        AtomicBoolean cancelled = new AtomicBoolean();
        running = cancelled;
        analyze.setEnabled(false);
        check.setEnabled(false);
        cancel.setVisible(true);
        progress.setText(RUNNING);
        boolean withStage = simplify.isSelected();
        String[] stageNote = new String[1];
        runner.run(() -> {
            PageStatements result;
            if (withStage) {
                // Vor dem Lauf gefragt: Der Core analysiert sonst still ohne Vorstufe, und der Benutzer wüsste es nicht.
                stageNote[0] = actions.simplificationUnavailable(page.key()).map(StatementsTab::stageNote)
                        .orElse(null);
                result = actions.analyzeSimplified(page, cancelled::get);
            } else {
                result = actions.analyzeStatements(page, cancelled::get);
            }
            return new Loaded(result, true, actions.statementLinks(page.key()));
        }, loaded -> {
            finished(cancelled);
            if (mine != generation) {
                return;
            }
            // Neue Analyse: Syntaxdetails der vorigen gelten nicht mehr; ein offenes Feld lädt neu.
            syntaxGeneration++;
            onAnalysed.run();
            syntax.setText("");
            render(loaded.statements(), loaded.links());
            if (stageNote[0] != null) {
                explanation = explanation.isBlank() ? stageNote[0] : explanation + " " + stageNote[0];
                onStatus.accept(explanation);
            }
            if (syntaxPanel.getComponentCount() > 1) {
                loadSyntax();
            }
        }, error -> {
            finished(cancelled);
            if (mine != generation) {
                return;
            }
            progress.setText(error instanceof CancellationException ? "Abgebrochen; die bisherige Analyse bleibt."
                    : " ");
            if (error instanceof AnalyzerUnavailableException unavailable && setup != null
                    && unavailable.reason() == AnalyzerUnavailableException.Reason.MODEL_MISSING) {
                setup.refresh();
                hint.setText(NOT_AVAILABLE_MODEL);
            } else if (error instanceof AnalyzerUnavailableException unavailable) {
                hint.setText(unavailable.getMessage());
            } else if (!(error instanceof CancellationException)) {
                hint.setText("Analyse fehlgeschlagen: " + Problems.describe(error).title()
                        + ". Die bisherige Analyse bleibt.");
            }
        });
    }

    /**
     * „Aussagen prüfen“ für die offene Seite: derselbe Fortschritt und dasselbe „Abbrechen“ wie bei der Analyse. Ohne
     * Modell, ohne KI-Freigabe der Seite oder ohne aktuelle Analyse sagt die Statuszeile den Grund.
     */
    private void check() {
        Actions actions = workspace.get();
        PageSnapshot page = snapshot;
        if (actions == null || page == null || running != null || !current) {
            return;
        }
        int mine = generation;
        AtomicBoolean cancelled = new AtomicBoolean();
        running = cancelled;
        analyze.setEnabled(false);
        check.setEnabled(false);
        cancel.setVisible(true);
        progress.setText(CHECKING);
        runner.run(() -> {
            // Vor dem Lauf gefragt, damit ohne Modell oder Freigabe nichts an den Dienst geht.
            Optional<CheckUnavailableException.Reason> unavailable = actions.checkUnavailable(page.key());
            if (unavailable.isPresent()) {
                throw new CheckUnavailableException(unavailable.get());
            }
            return new Loaded(actions.checkStatements(page, cancelled::get), true,
                    actions.statementLinks(page.key()));
        }, loaded -> {
            finished(cancelled);
            if (mine != generation) {
                return;
            }
            onAnalysed.run();
            render(loaded.statements(), loaded.links());
        }, error -> {
            finished(cancelled);
            if (mine != generation) {
                return;
            }
            progress.setText(error instanceof CancellationException ? "Abgebrochen; die bisherige Analyse bleibt."
                    : " ");
            if (error instanceof CheckUnavailableException unavailable) {
                status(CheckTexts.unavailable(unavailable.reason()));
            } else if (!(error instanceof CancellationException)) {
                status("„" + CheckTexts.JOB + "“ fehlgeschlagen: " + Problems.describe(error).title()
                        + ". Die bisherige Analyse bleibt.");
            }
        });
    }

    /** Eine Meldung zum letzten Vorgang in der Statuszeile; die Aussagen bleiben stehen. */
    /**
     * Löscht nach der Rückfrage die gespeicherte Aussagen-Analyse der offenen Seite samt Anhängen und enthaltenen
     * Dateien; die Seite bleibt. Danach zeigt der Reiter „Nicht analysiert“, Graph und Stand lesen neu.
     */
    void deleteAnalysis() {
        Actions actions = workspace.get();
        PageSnapshot page = snapshot;
        if (actions == null || page == null || running != null || !stored || !confirmDelete.test(DELETE_QUESTION)) {
            return;
        }
        delete.setEnabled(false);
        int mine = generation;
        runner.run(() -> actions.deleteAnalysis(page.key()), deleted -> {
            if (mine != generation) {
                return;
            }
            status(deleted == 0 ? "Keine Aussagen-Analyse zu dieser Seite"
                    : Texts.count(deleted, "Aussagen-Analyse", "Aussagen-Analysen") + " gelöscht");
            reload();
            onAnalysed.run();
        }, error -> {
            if (mine != generation) {
                return;
            }
            delete.setEnabled(stored);
            status("Aussagen-Analyse nicht gelöscht: " + error.getClass().getSimpleName());
        });
    }

    /** Für Tests: statt des Dialogs; bekommt die Frage. */
    void confirmDeleteWith(Predicate<String> confirm) {
        confirmDelete = Objects.requireNonNull(confirm, "confirm");
    }

    IconButton deleteButton() {
        return delete;
    }

    private void status(String message) {
        explanation = message;
        onStatus.accept(message);
    }

    /** Der Lauf ist wirklich zu Ende: erst jetzt ist „Analysieren“ wieder möglich. */
    private void finished(AtomicBoolean run) {
        if (running == run) {
            running = null;
            analyze.setEnabled(snapshot != null && !modelMissing());
            check.setEnabled(snapshot != null && current);
            delete.setEnabled(snapshot != null && stored);
            cancel.setVisible(false);
            progress.setText(" ");
        }
    }

    /**
     * Bittet die laufende Analyse aufzuhören. Bis ihr Lauf endet ({@link #finished}), bleibt „Analysieren“ gesperrt:
     * nie zwei Läufe zugleich, und ein abgebrochener Lauf überschreibt keinen neuen.
     */
    private void cancelRun() {
        AtomicBoolean run = running;
        if (run != null && !run.get()) {
            run.set(true);
            analyze.setEnabled(false);
            check.setEnabled(false);
            cancel.setVisible(false);
            progress.setText("Wird abgebrochen …");
        }
    }

    /** Warum die Vorstufe nicht lief, als Satz für die Statuszeile. */
    static String stageNote(String reason) {
        return "NOT_RELEASED".equals(reason) ? SIMPLIFY_NOT_RELEASED : SIMPLIFY_NO_MODEL;
    }

    private void toggleSyntax() {
        boolean show = syntaxPanel.getComponentCount() < 2;
        showSyntax(show);
        if (show && syntax.getText().isEmpty()) {
            loadSyntax();
        }
    }

    private void loadSyntax() {
        Actions actions = workspace.get();
        PageSnapshot page = snapshot;
        if (actions == null || page == null) {
            return;
        }
        int mine = syntaxGeneration;
        runner.run(() -> actions.syntaxDetails(page), raw -> {
            if (mine == syntaxGeneration) {
                syntax.setText(raw.orElse("Keine Syntaxdetails gespeichert."));
                syntax.setCaretPosition(0);
            }
        }, error -> {
            if (mine == syntaxGeneration) {
                syntax.setText("Syntaxdetails nicht geladen: " + Problems.describe(error).title());
            }
        });
    }

    private void showSyntax(boolean show) {
        if (syntaxPanel.getComponentCount() > 1) {
            syntaxPanel.remove(1);
        }
        if (show) {
            syntaxPanel.add(Comic.scroll(syntax), BorderLayout.CENTER);
        }
        syntaxToggle.setText(show ? "Syntaxdetails ausblenden" : "Syntaxdetails anzeigen");
        syntaxPanel.revalidate();
        syntaxPanel.repaint();
    }

    private void loading() {
        analyze.setEnabled(false);
        setState("Wird geladen …", Comic.MUTED);
        list.removeAll();
        lines.clear();
        jumps.clear();
        copyText.setLength(0);
        hint.setText(" ");
        list.revalidate();
        list.repaint();
    }

    /** Zeigt Stand, Aussagen und Verbindungen; veraltete Aussagen bleiben lesbar, aber ausgegraut. */
    void render(PageStatements statements, List<KnowledgeGraph.Link> links) {
        analyze.setEnabled(running == null && snapshot != null && !modelMissing());
        current = statements.state() == PageStatements.State.CURRENT && statements.analysis() != null;
        stored = statements.analysis() != null;
        check.setEnabled(running == null && snapshot != null && current);
        delete.setEnabled(running == null && snapshot != null && stored);
        list.removeAll();
        lines.clear();
        jumps.clear();
        copyText.setLength(0);
        syntaxPanel.setVisible(statements.analysis() != null);
        switch (statements.state()) {
            case MISSING -> setState("Nicht analysiert", Comic.MUTED);
            case CURRENT -> setState("Aktuell", Comic.GREEN);
            case STALE -> setState("Veraltet", Comic.ORANGE);
        }
        hint.setText(hint(statements));
        if (statements.analysis() != null) {
            boolean stale = statements.state() == PageStatements.State.STALE;
            // Spannen gelten nur für denselben Lesetext: aktuell, oder veraltet nur wegen des Analysators.
            boolean offsets = statements.state() == PageStatements.State.CURRENT
                    || stale && PageStatements.ANALYZER_CHANGED.equals(statements.reason());
            if (!offsets) {
                hint.setText(hint.getText() + " " + NO_EVIDENCE);
            }
            ParsedStatements result = statements.analysis().result();
            Map<String, ParsedStatements.Mention> mentions = new LinkedHashMap<>();
            result.mentions().forEach(mention -> mentions.put(mention.id(), mention));
            List<ParsedStatements.Statement> core = result.statements().stream()
                    .filter(ParsedStatements.Statement::core).toList();
            List<ParsedStatements.Statement> other = result.statements().stream()
                    .filter(statement -> !statement.core()).toList();
            section("Kernaussagen", core, result, mentions, stale, offsets);
            section("Weitere Aussagen", other, result, mentions, stale, offsets);
            if (result.statements().isEmpty()) {
                list.add(Comic.muted("Keine Aussagen mit Vollverb erkannt."));
            }
            links(links, mentions, offsets);
            // Mit Aussagen steht die Erläuterung („Analysiert am …“) in der Statuszeile, nicht über der Liste.
            explanation = hint.getText();
            onStatus.accept(explanation);
            hint.setText(" ");
        } else {
            explanation = hint.getText();
            onStatus.accept(" ");
        }
        list.revalidate();
        list.repaint();
    }

    private void section(String heading, List<ParsedStatements.Statement> statements, ParsedStatements result,
                         Map<String, ParsedStatements.Mention> mentions, boolean stale, boolean offsets) {
        if (statements.isEmpty()) {
            return;
        }
        JLabel label = Comic.text(heading + " (" + statements.size() + ")");
        label.setFont(Comic.semiBold(13f));
        label.setAlignmentX(LEFT_ALIGNMENT);
        list.add(label);
        lines.add(heading);
        copyText.append(heading).append(" (").append(statements.size()).append(")\n\n");
        for (ParsedStatements.Statement statement : statements) {
            List<String> body = describe(statement, result, mentions, offsets ? sentence(result, statement) : null);
            lines.add(String.join(" | ", body));
            copyText.append(String.join("\n", body)).append("\n\n");
            list.add(entry(body, stale, offsets ? span(result, statement) : null));
            list.add(Box.createVerticalStrut(4));
        }
        list.add(Box.createVerticalStrut(8));
    }

    private void links(List<KnowledgeGraph.Link> links, Map<String, ParsedStatements.Mention> mentions,
                       boolean offsets) {
        JLabel label = Comic.text("Verbindungen zu anderen analysierten Seiten");
        label.setFont(Comic.semiBold(13f));
        label.setAlignmentX(LEFT_ALIGNMENT);
        list.add(label);
        lines.add("Verbindungen");
        if (links.isEmpty()) {
            JLabel none = Comic.muted("Keine: Verbindungen entstehen nur zu Seiten, die ebenfalls manuell analysiert "
                    + "wurden und die Sie lesen dürfen.");
            none.setAlignmentX(LEFT_ALIGNMENT);
            list.add(none);
            return;
        }
        for (KnowledgeGraph.Link link : links) {
            String line = link(link);
            lines.add(line);
            List<TermsTab.Span> spans = link.mentions().stream().map(mentions::get).filter(Objects::nonNull)
                    .map(mention -> new TermsTab.Span(mention.start(), mention.end())).toList();
            list.add(entry(List.of(line), link.target().state() == PageStatements.State.STALE,
                    spans.isEmpty() || !offsets ? null : spans));
        }
    }

    static String link(KnowledgeGraph.Link link) {
        String target = (link.target().title() == null ? link.target().key().pageId() : link.target().title())
                + " (" + link.target().key().sourceId() + ")";
        String kind = link.match() == KnowledgeGraph.Match.LEXICAL
                ? "gleiche Grundform – Kandidat, keine bestätigte Identität"
                : "über eine bestätigte Begriffsbeziehung";
        String stale = link.target().state() == PageStatements.State.STALE ? " · veraltet" : "";
        return "„" + link.term() + "“ → " + target + " · " + link.targetMentions().size() + " Erwähnung(en) · "
                + kind + stale;
    }

    /** Die Zeilen einer Aussage: Prädikat, Beteiligte je Rolle, Form, nominale Bezüge, Hinweise, Beleg. */
    static List<String> describe(ParsedStatements.Statement statement, ParsedStatements result,
                                 Map<String, ParsedStatements.Mention> mentions, String evidence) {
        List<String> body = new ArrayList<>();
        String lemma = statement.predicateLemma();
        body.add(statement.predicateText() + (lemma == null || lemma.equals(statement.predicateText()) ? ""
                : " (" + lemma + ")"));
        for (ParsedStatements.Role role : ParsedStatements.Role.values()) {
            List<String> parts = new ArrayList<>();
            for (ParsedStatements.Argument argument : statement.withRole(role)) {
                ParsedStatements.Mention mention = mentions.get(argument.mention());
                if (mention == null) {
                    continue;
                }
                parts.add(mention.text() + qualifier(argument, mention));
            }
            if (!parts.isEmpty()) {
                body.add(roleLabel(role) + ": " + String.join(", ", parts));
            }
        }
        body.add((statement.voice() == ParsedStatements.Voice.ACTIVE ? "Aktiv" : "Passiv") + " · "
                + switch (statement.clause()) {
                    case MAIN -> "Hauptsatz";
                    case COORDINATED -> "koordinierter Satz";
                    case SUBORDINATE -> "Nebensatz";
                });
        String features = features(statement.features());
        if (!features.isEmpty()) {
            body.add("Merkmale: " + features);
        }
        ParsedStatements.Derivation derivation = statement.derivation();
        if (derivation.origin() == ParsedStatements.Origin.VORSTUFE) {
            // ADR 0032, FL3: Die Aussage stammt aus einem vereinfachten Satz; der Originalsatz bleibt der Beleg.
            body.add(CheckTexts.ORIGIN_LABEL + ": Vorstufe „" + SIMPLIFY + "“ · Kennzeichen: "
                    + stageFlag(derivation.stageFlag()));
        }
        boolean correction = derivation.origin() == ParsedStatements.Origin.MODELL;
        if (correction) {
            // ADR 0032, FL4: Die Aussage stammt aus dem Satz, mit dem das Modell eine andere vervollständigt hat.
            body.add(CheckTexts.ORIGIN_LABEL + ": " + CheckTexts.ORIGIN_MODEL);
        }
        if (statement.check() != null) {
            body.add(CheckTexts.VERDICT_LABEL + ": " + CheckTexts.verdict(statement.check()));
            String lowered = CheckTexts.lowered(statement);
            if (lowered != null) {
                body.add(CheckTexts.QUALITY_LABEL + ": " + lowered);
            }
        }
        for (ParsedStatements.Attachment attachment : result.attachments()) {
            boolean involved = statement.arguments().stream()
                    .anyMatch(argument -> argument.mention().equals(attachment.head()));
            ParsedStatements.Mention head = mentions.get(attachment.head());
            ParsedStatements.Mention dependent = mentions.get(attachment.dependent());
            if (involved && head != null && dependent != null) {
                body.add("Nominaler Bezug: " + head.text() + " → " + dependent.text() + " ("
                        + switch (attachment.kind()) {
                            case GENITIVE -> "Genitiv";
                            case PREPOSITIONAL -> "mit „" + attachment.marker() + "“";
                            case OTHER -> "Ergänzung";
                        } + "; kein Besitz behauptet)");
            }
        }
        List<String> notes = statement.findings().stream().sorted().map(StatementsTab::finding)
                .filter(Objects::nonNull).toList();
        if (!notes.isEmpty()) {
            body.add("Hinweise: " + String.join("; ", notes));
        }
        boolean staged = derivation.origin() == ParsedStatements.Origin.VORSTUFE;
        if (evidence != null && !evidence.isBlank()) {
            body.add((staged || correction ? "Beleg (Originalsatz): „" : "Beleg: „") + evidence.strip() + "“");
        }
        if (staged) {
            body.add("Vereinfachter Satz: „" + derivation.simplifiedSentence().strip() + "“");
        }
        if (correction) {
            body.add(CheckTexts.MODEL_SENTENCE_LABEL + ": „" + derivation.simplifiedSentence().strip() + "“");
        }
        return body;
    }

    private static String qualifier(ParsedStatements.Argument argument, ParsedStatements.Mention mention) {
        List<String> notes = new ArrayList<>();
        if (argument.marker() != null && !mention.text().toLowerCase().startsWith(argument.marker().toLowerCase())) {
            notes.add(argument.marker());
        }
        if (mention.unresolved()) {
            notes.add("Bezug ungeklärt");
        }
        if (!argument.certain()) {
            notes.add("unsicher");
        } else if (argument.basis() == ParsedStatements.Basis.COORDINATION) {
            notes.add("übernommen");
        } else if (argument.basis() == ParsedStatements.Basis.CASE_HEURISTIC) {
            notes.add("aus dem Kasus geschlossen");
        }
        return notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")";
    }

    /** Abweichungen vom Standard (nicht verneint, ohne Modalverb, gilt), leer wenn nichts abweicht oder unbekannt. */
    static String features(ParsedStatements.Features features) {
        List<String> parts = new ArrayList<>();
        if (features.negation() == ParsedStatements.Negation.NEGATED) {
            parts.add("verneint");
        }
        switch (features.modality()) {
            case MUSS -> parts.add("muss");
            case SOLL -> parts.add("soll");
            case KANN -> parts.add("kann");
            case DARF -> parts.add("darf");
            default -> { }
        }
        switch (features.temporality()) {
            case GEPLANT -> parts.add("geplant");
            case FRUEHER -> parts.add("früher");
            case VERWORFEN -> parts.add("verworfen");
            default -> { }
        }
        return String.join(" · ", parts);
    }

    /** Das Kennzeichen der Vorstufe in Worten; nie als Zeitbezug zu lesen. */
    static String stageFlag(ParsedStatements.StageFlag flag) {
        return switch (flag) {
            case FACT -> "Tatsache";
            case CONDITION -> "Bedingung";
            case PLANNING -> "Planung oder Absicht";
            case DISCARDED -> "verworfen oder früher";
            case NONE -> "keines";
        };
    }

    static String roleLabel(ParsedStatements.Role role) {
        return switch (role) {
            case ACTOR -> "Handelnder";
            case AFFECTED_OBJECT -> "Betroffen";
            case RECIPIENT -> "Empfänger";
            case PREPOSITIONAL_OBJECT -> "Präpositionalobjekt";
            case INSTRUMENT -> "Mittel";
            case TIME -> "Zeit";
            case CIRCUMSTANCE -> "Umstand";
            case BEARER -> "Träger";
        };
    }

    static String finding(String code) {
        return switch (code) {
            case "PASSIVE" -> "Passiv: das Subjekt ist betroffen, nicht handelnd";
            case "SUBORDINATE_CLAUSE" -> "Nebensatz";
            case "SUBJECT_FROM_COORDINATION" -> "Subjekt vom koordinierten Prädikat übernommen";
            case "NO_SUBJECT" -> "kein Subjekt erkannt";
            case "NO_ACCUSATIVE_OBJECT" -> "kein Akkusativobjekt";
            case "COORDINATED_OBJECTS" -> "mehrere koordinierte Objekte";
            case "UNRESOLVED_PRONOUN" -> "Pronomen mit ungeklärtem Bezug";
            case "CASE_CONFLICT" -> "Kasus widerspricht der Satzstruktur";
            case "HEURISTIC_PARTICIPANT" -> "Beteiligter aus dem Kasus geschlossen";
            case "UNCONFIRMED_DATIVE" -> "Dativ nicht bestätigt";
            case "TIME_FROM_NOMINAL" -> "Zeitangabe hing am Empfänger und ist nur unsicher zugeordnet";
            case "CLAUSE_PARTICIPANT" -> "ein Beteiligter ist ein Teilsatz";
            case "OBJECT_FROM_COORDINATION" -> "Objekt vom gereihten Verb übernommen (unsicher)";
            case "RELATIVE_PRONOUN_RESOLVED" -> "Relativpronomen auf sein Bezugswort aufgelöst";
            case "PRONOUN_FROM_COORDINATION" -> "Pronomen auf das Objekt des gereihten Verbs bezogen (unsicher)";
            case "SUBJECT_OF_INFINITIVE" -> "Infinitivgruppe ohne eigenes Subjekt";
            case "AGENT_FROM_DURCH" -> "Handelnder aus der durch-Gruppe abgeleitet (unsicher)";
            case "AGENT_FROM_VON" -> "Handelnder aus der von-Gruppe bei „ist … zu“ abgeleitet (unsicher)";
            case "LEMMA_FROM_FORM" -> "Grundform des Verbs aus der Wortform erzeugt";
            case "STATE_PROPERTY" -> "Zustand: Eigenschaft mit „sein“, „werden“ oder „bleiben“, kein Verb";
            case "STATE_ROLE_NOUN" -> "Zustand: Rollennomen oder Begriffsbestimmung, kein Verb";
            case "STATE_CHANGE" -> "Zustandswechsel („wird …“): der Zustand tritt erst ein";
            default -> code;
        };
    }

    static String hint(PageStatements statements) {
        if (statements.state() == PageStatements.State.MISSING) {
            return PageStatements.NO_TEXT.equals(statements.reason())
                    ? "Diese Seite hat keinen analysierbaren Text." : NOT_ANALYSED;
        }
        if (statements.state() == PageStatements.State.STALE) {
            String why = switch (String.valueOf(statements.reason())) {
                case PageStatements.TEXT_CHANGED -> "Der Text der Seite hat sich seit der Analyse geändert.";
                case PageStatements.ANALYZER_CHANGED -> "Parser, Modell oder Regeln haben sich seit der Analyse "
                        + "geändert.";
                case PageStatements.NO_TEXT -> "Die Seite hat derzeit keinen analysierbaren Text.";
                case PageStatements.STAGE_WITHDRAWN -> STAGE_WITHDRAWN;
                default -> "Die Analyse passt nicht mehr zur Seite.";
            };
            return why + " Die Aussagen unten sind veraltet; „" + ANALYZE + "“ ersetzt sie.";
        }
        return "Analysiert am " + statements.analysis().analyzedAt().toString().replace('T', ' ').replace("Z", " UTC")
                + " mit " + statements.analysis().analyzerVersion().replace('|', ' ')
                + ", " + stage(statements.analysis().stage()) + checked(statements)
                + ". Grammatische Zuordnung, keine geprüften Fakten.";
    }

    /** Was „Aussagen prüfen“ an der Seite hinterlassen hat, als Satzteil; leer ohne Urteile. */
    private static String checked(PageStatements statements) {
        String checked = CheckTexts.checked(statements.analysis().result());
        return checked == null ? "" : ", " + checked;
    }

    /**
     * Ob die Seite mit der Vorstufe gerechnet wurde ({@code PageStatementAnalysis#stage}: Betriebsart, Modell und
     * Anweisung), als Satzteil hinter „Analysiert am … mit …“.
     */
    static String stage(String stage) {
        if (stage == null || stage.isBlank()
                || com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis.STAGE_WITHDRAWN
                .equals(stage)) {
            return "ohne Vorstufe";
        }
        int cut = stage.indexOf('|');
        String mode = cut < 0 ? stage : stage.substring(0, cut);
        String version = cut < 0 ? "" : stage.substring(cut + 1).replace('|', ' ').strip();
        return "mit Vorstufe „" + SIMPLIFY + "“ (" + ("always".equals(mode) ? "immer" : "nur wo nötig")
                + (version.isEmpty() ? "" : ", " + version) + ")";
    }

    private String sentence(ParsedStatements result, ParsedStatements.Statement statement) {
        Optional<ParsedStatements.Sentence> found = result.sentences().stream()
                .filter(sentence -> sentence.index() == statement.sentence()).findFirst();
        if (found.isEmpty() || text == null || found.get().end() > text.text().length()) {
            return null;
        }
        return text.text().substring(found.get().start(), found.get().end());
    }

    private static List<TermsTab.Span> span(ParsedStatements result, ParsedStatements.Statement statement) {
        return result.sentences().stream().filter(sentence -> sentence.index() == statement.sentence())
                .findFirst().map(sentence -> List.of(new TermsTab.Span(sentence.start(), sentence.end())))
                .orElse(List.of(new TermsTab.Span(statement.start(), statement.end())));
    }

    private JComponent entry(List<String> body, boolean stale, List<TermsTab.Span> jump) {
        JTextArea area = note();
        area.setText(String.join("\n", body));
        area.setForeground(stale ? Comic.MUTED : Comic.INK);
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, stale ? Comic.MUTED : Comic.PETROL),
                BorderFactory.createEmptyBorder(4, 8, 4, 4)));
        row.add(area, BorderLayout.CENTER);
        row.setAlignmentX(LEFT_ALIGNMENT);
        if (jump != null && !jump.isEmpty()) {
            area.setToolTipText("Zum Beleg im Reiter „Text“ springen");
            area.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            Runnable go = () -> onJump.accept(jump);
            jumps.add(go);
            area.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    go.run();
                }
            });
        }
        return row;
    }

    private void setState(String label, Color tone) {
        head.status(label.isBlank() ? null : label, tone);
    }

    private static JTextArea note() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFont(Comic.regular(13f));
        area.setForeground(Comic.INK);
        return area;
    }

    /** Die Aktionen des Reiters; in der App {@link Workspace}. Alle blockieren und laufen nie auf dem EDT. */
    interface Actions {

        PageStatements statements(PageSnapshot page);

        boolean statementAnalysisAvailable();

        List<KnowledgeGraph.Link> statementLinks(com.aresstack.spaceharvester.application.snapshot.PageKey key);

        PageStatements analyzeStatements(PageSnapshot page, java.util.function.BooleanSupplier cancelled);

        Optional<String> syntaxDetails(PageSnapshot page);

        /** Wie {@link #analyzeStatements}, mit der Vorstufe „Sätze vereinfachen“ nach den Einstellungen. */
        default PageStatements analyzeSimplified(PageSnapshot page, java.util.function.BooleanSupplier cancelled) {
            return analyzeStatements(page, cancelled);
        }

        /** Warum die Vorstufe für diese Seite nicht laufen kann ({@code NO_MODEL}, {@code NOT_RELEASED}); sonst leer. */
        default Optional<String> simplificationUnavailable(
                com.aresstack.spaceharvester.application.snapshot.PageKey key) {
            return Optional.empty();
        }

        /** Warum „Aussagen prüfen“ für diese Seite nicht laufen kann (kein Modell, keine KI-Freigabe); sonst leer. */
        default Optional<CheckUnavailableException.Reason> checkUnavailable(
                com.aresstack.spaceharvester.application.snapshot.PageKey key) {
            return Optional.empty();
        }

        /**
         * Löscht die gespeicherte Aussagen-Analyse der Seite samt Anhängen und enthaltenen Dateien; die Seite bleibt.
         * Liefert die Zahl der gelöschten Analysen.
         */
        default int deleteAnalysis(com.aresstack.spaceharvester.application.snapshot.PageKey key) {
            return 0;
        }

        /** „Aussagen prüfen“: Das Modell beurteilt die Aussagen der gespeicherten, aktuellen Analyse der Seite. */
        default PageStatements checkStatements(PageSnapshot page, java.util.function.BooleanSupplier cancelled) {
            return statements(page);
        }

        /** {@code null} ohne laufende App. */
        static Actions of(Workspace workspace,
                Supplier<com.aresstack.spaceharvester.application.configuration.SimplificationSettings> simplification,
                Supplier<com.aresstack.spaceharvester.application.configuration.CheckSettings> checkSettings) {
            if (workspace == null) {
                return null;
            }
            return new Actions() {
                @Override
                public int deleteAnalysis(com.aresstack.spaceharvester.application.snapshot.PageKey key) {
                    return workspace.deleteStatementAnalysis(key);
                }

                @Override
                public Optional<CheckUnavailableException.Reason> checkUnavailable(
                        com.aresstack.spaceharvester.application.snapshot.PageKey key) {
                    return workspace.checkUnavailable(key);
                }

                @Override
                public PageStatements checkStatements(PageSnapshot page,
                                                      java.util.function.BooleanSupplier cancelled) {
                    return workspace.checkStatements(page, cancelled, checkSettings.get());
                }

                @Override
                public PageStatements analyzeSimplified(PageSnapshot page,
                                                        java.util.function.BooleanSupplier cancelled) {
                    return workspace.analyzeStatements(page, cancelled, simplification.get());
                }

                @Override
                public Optional<String> simplificationUnavailable(
                        com.aresstack.spaceharvester.application.snapshot.PageKey key) {
                    return workspace.simplificationUnavailable(key);
                }

                @Override
                public PageStatements statements(PageSnapshot page) {
                    return workspace.statements(page);
                }

                @Override
                public boolean statementAnalysisAvailable() {
                    return workspace.statementAnalysisAvailable();
                }

                @Override
                public List<KnowledgeGraph.Link> statementLinks(
                        com.aresstack.spaceharvester.application.snapshot.PageKey key) {
                    return workspace.statementLinks(key);
                }

                @Override
                public PageStatements analyzeStatements(PageSnapshot page,
                                                        java.util.function.BooleanSupplier cancelled) {
                    return workspace.analyzeStatements(page, cancelled);
                }

                @Override
                public Optional<String> syntaxDetails(PageSnapshot page) {
                    return workspace.syntaxDetails(page);
                }
            };
        }
    }

    /** Führt Core-Aufrufe aus; in der App {@link Background}, in Tests synchron. */
    interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    private record Loaded(PageStatements statements, boolean available, List<KnowledgeGraph.Link> links) {
    }

    // ---- für Tests

    List<String> lines() {
        return List.copyOf(lines);
    }

    String stateLabel() {
        return head.statusText() == null ? " " : head.statusText();
    }

    /** Die Erläuterung, ob sie im Reiter steht oder in der Statuszeile. */
    String hintText() {
        return hint.getText().isBlank() ? explanation : hint.getText();
    }

    boolean analyzeOffered() {
        return analyze.isVisible() && analyze.isEnabled();
    }

    boolean cancelOffered() {
        return cancel.isVisible();
    }

    boolean checkOffered() {
        return check.isVisible() && check.isEnabled();
    }

    void clickCheck() {
        check.doClick();
    }

    /** Kreuzt „Sätze vereinfachen“ an oder ab. */
    void simplify(boolean on) {
        simplify.setSelected(on);
    }

    void clickAnalyze() {
        analyze.doClick();
    }

    void clickCancel() {
        cancel.doClick();
    }

    String progressText() {
        return progress.getText();
    }

    int jumpCount() {
        return jumps.size();
    }

    void clickSyntax() {
        syntaxToggle.doClick();
    }

    String syntaxText() {
        return syntax.getText();
    }

    void clickFirstEntry() {
        if (!jumps.isEmpty()) {
            jumps.getFirst().run();
        }
    }
}
