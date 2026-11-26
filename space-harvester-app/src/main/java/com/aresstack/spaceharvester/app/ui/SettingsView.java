package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AnalyticsSettings;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.ConsoleOutput;
import com.aresstack.spaceharvester.app.settings.ViewerBackground;
import com.aresstack.spaceharvester.app.ui.controls.DurationField;
import com.aresstack.spaceharvester.application.metadata.AnalyticsQueries;
import com.aresstack.spaceharvester.source.network.ProxySettings;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Einstellungen und Diagnose in Bereichen wie im AskAI-Client: Die Bereiche stehen untereinander im linken Drawer
 * ({@link #drawer()}, an Stelle des Explorers, solange die Einstellungen offen sind), rechts nur der gewählte Bereich.
 * „Allgemein“ (welche Datei gilt, was daran nicht stimmt, wo die Daten liegen, welche Ordner gelesen werden dürfen,
 * Versionen, was im Terminal erscheint, Darstellung: Hintergrund des Bildbetrachters), „KI-Dienst“ und „Modelle“ ({@link AiSettingsPanel}, zentral für alle
 * Quellen), „Netzwerk“ (Proxy, Serverzertifikate und Wiederholungen), „Cache“ (Aktualisierung gespeicherter Seiten,
 * Frist für gesperrte Seiten) und „Sicherheit“
 * (KeePass-Kopplung für KI-Dienst und Quellen, Tresor). Geheimnisse erscheinen nur als „gespeichert“ oder „fehlt“;
 * lokal gespeicherte Zugangsdaten lassen sich dort je Eintrag löschen. „Bibliotheken“ ({@link LibrariesView}) hängt
 * als letzte Zeile am Boden des Drawers über „Zurück“, getrennt von den Einstellungsbereichen darüber. Jede
 * Änderung schreibt die Datei und startet die Laufzeit mit ihr neu; von Hand geänderte Dateien übernimmt „Neu laden“.
 */
public final class SettingsView implements View {

    static final String GENERAL = "Allgemein";
    static final String AI = "KI-Dienst";
    static final String MODELS = "Modelle";
    /** Bereich „Wortlisten“: die Wörter für den Stand und die Glossarregeln, aus „Modelle“ herausgezogen. */
    static final String WORDS = "Wortlisten";
    static final String NETWORK = "Netzwerk";
    static final String CACHE = "Cache";
    /** Bereich „Video“: Marken, Standbilder und Player ({@code space-harvester-app.video.*}). */
    static final String VIDEO = "Video";
    /**
     * Bereich „Mainframe“: Verbindung, 3270-Terminal und Maus-Aktionen des Command Deck (aus MainframeMate). Hier liegt
     * die Verbindung, mit der „Neue Verbindung“ direkt verbindet (Angelo, 23.11.2026).
     */
    static final String TERMINAL = "Mainframe";
    static final String CONNECTION_HEADING = "Verbindung";
    static final String NDV = "NDV";
    static final String BETAVIEW = "BetaView";
    /** Bereich „Browser“: die Profile der Websuche im Chat (Extern, Intern), wie im Enterprise AI Client. */
    static final String BROWSER = "Browser";
    /** Bereich „Diagramme“: Diagramm-Ansicht „Visuell“ und Systemfunktionen (MainframeMate: Einstellungen › Systemfunktionen). */
    static final String DIAGRAMS = "Diagramme";
    static final String SECURITY = "Sicherheit";
    static final String LIBRARIES = "Bibliotheken";
    /** Die Diagnose (ehemals Bereich „Status“): Auftragsereignisse und Verbindungsprüfungen, {@link StatusView}. */
    static final String DIAGNOSIS = "Diagnose";
    /** Tooltip der Tresor-Zeilen, deren Wert nur in KeePass liegt: lokal gibt es nichts zu löschen. */
    static final String KEEPASS_TIP = "Die Zugangsdaten liegen nur in KeePass und werden bei Bedarf über die Kopplung "
            + "gelesen; lokal ist nichts gespeichert. Die Verbindung trennt „Entkoppeln“ im Abschnitt KeePass.";
    static final String EXAMPLE = "/com/aresstack/spaceharvester/app/space-harvester-app.example.yml";
    /** Platte unter Allgemein und Zeile auf der Abschlussseite des Assistenten. */
    static final String CONSOLE_TITLE = "Terminal-Ausgabe";
    static final String CONSOLE_HINT = "Meldungen ab dieser Stufe erscheinen im Terminal, aus dem die App gestartet "
            + "wurde; „Warnungen“ zeigt also auch Fehler. Die Protokolldatei (siehe Diagnose) enthält immer alle "
            + "Meldungen. Gilt sofort, ohne Neustart.";

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final Facts file = new Facts();
    private final JTextArea problems = Comic.note("");
    private final Facts storage = new Facts();
    private final Facts network = new Facts();
    private final Facts cache = new Facts();
    /** Bereich „Netzwerk“, Platte „Wiederholungen“ ({@code jobs.retry.*}, ADR 0025). */
    final Facts retry = new Facts();
    /** Reiter „Sicherheit“: wo Geheimnisse liegen, je Eintrag nur „gespeichert“ oder „fehlt“, nie der Wert. */
    final Facts security = new Facts();
    /** Reiter „Sicherheit“, Abschnitt „KeePass“: die eine Kopplung der App. */
    final KeePassPairingPanel keePass;
    /** Meldungen im Abschnitt „Tresor“ (gelöschte Zugangsdaten). */
    final Feedback securityFeedback = new Feedback();
    /** Reiter „Sicherheit“, Platte „Für KI freigegebene Seiten“ (ADR 0028). */
    final PageReleasesPanel releases;
    /** Die Löschknöpfe des Reiters „Sicherheit“ in Zeilenreihenfolge, für Tests. */
    final java.util.List<IconButton> deleteButtons = new java.util.ArrayList<>();
    private final java.util.Map<IconButton, Runnable> deletions = new java.util.HashMap<>();
    private final ComposerButton editCache = Comic.button(Glyph.PENCIL, "Alle Quellen anpassen …",
            "Erkennung, Aktualisierung und Bereinigung für alle Quellen ändern; je Quelle unter Wissensquellen › "
                    + "Synchronisierung");
    final ComposerButton viewChangeLog = Comic.button(Glyph.TABLE, ChangeLogDialog.TITLE + " …",
            "Jede protokollierte Seitenänderung einsehen und durchsuchen");
    /** Rechts: der gewählte Bereich. */
    private final java.awt.CardLayout pages = new java.awt.CardLayout();
    private final JPanel pageDeck = new JPanel(pages);
    /** Links im Drawer: die Bereiche untereinander. */
    private final JPanel drawer = new JPanel(new BorderLayout(0, 6));
    private final JPanel navRows = new JPanel();
    /** Unten im Drawer, über „Zurück“: Zeilen, die nicht mitscrollen (Bibliotheken). */
    private final JPanel bottomRows = new JPanel();
    private final java.util.Map<String, SettingsNavRow> rows = new java.util.LinkedHashMap<>();
    /** Bereich „BetaView“: Standard-Suchfilter wie in MainframeMate. */
    final BetaViewSettingsPanel betaView = new BetaViewSettingsPanel();
    final BrowserSettingsPanel browser = new BrowserSettingsPanel();
    private String selected;
    /** „Zurück“ im Drawer: verlässt die Einstellungen (setzt das Hauptfenster). */
    private Runnable leave = () -> { };
    final ComposerButton back = Comic.button(ComposerIcons.chevronLeft(), "Zurück",
            "Einstellungen verlassen und zum vorigen Bereich zurückkehren");
    private final AiSettingsPanel ai;
    /** Platte „Dateizugriff“ unter Allgemein: freigegebene Ordner für Ordner-Quellen. */
    final FileAccessEditor fileAccess;
    final StandingWordsEditor standingWords;
    /** Bereich „Video“: alle Werte für Videos. */
    final VideoSettingsPanel video;
    /** Bereich „Terminal“: Aktionsverzögerung, F-Tasten, Sternenhimmel, Maus-Aktionen. */
    final TerminalSettingsPanel terminal =
            new TerminalSettingsPanel(com.aresstack.spaceharvester.app.ui.deck.TerminalSettings.shared());
    /** Bereich „Mainframe“: Stichprobe, Lesegröße, Budget und Frist der MVS-Binärdiagnose (app-state.json). */
    static final String MVS_DIAGNOSIS_HEADING = "MVS-Binärdiagnose und Hex-Ansicht";
    final NumberSettingsPanel<com.aresstack.spaceharvester.app.ui.deck.MvsDiagnosisSettings.Value> mvsDiagnosis =
            new NumberSettingsPanel<>(MVS_DIAGNOSIS_HEADING,
                    "Alle Werte der MVS-Binärdiagnose auf ihre Vorgabe zurücksetzen",
                    com.aresstack.spaceharvester.app.ui.deck.MvsDiagnosisSettings.Value.class,
                    () -> com.aresstack.spaceharvester.app.ui.deck.MvsDiagnosisSettings.shared().values(),
                    NumberSettingsPanel.Store.immediately(
                            values -> com.aresstack.spaceharvester.app.ui.deck.MvsDiagnosisSettings.shared().save(values)));
    /** Bereich „Mainframe“: Fristen des Command Deck außerhalb des Terminals (app-state.json). */
    static final String DECK_TIMEOUTS_HEADING = "Fristen im Command Deck";
    final NumberSettingsPanel<com.aresstack.spaceharvester.app.ui.deck.DeckTimeouts.Value> deckTimeouts =
            new NumberSettingsPanel<>(DECK_TIMEOUTS_HEADING, "Alle Fristen im Command Deck auf ihre Vorgabe zurücksetzen",
                    com.aresstack.spaceharvester.app.ui.deck.DeckTimeouts.Value.class,
                    () -> com.aresstack.spaceharvester.app.ui.deck.DeckTimeouts.shared().values(),
                    NumberSettingsPanel.Store.immediately(
                            values -> com.aresstack.spaceharvester.app.ui.deck.DeckTimeouts.shared().save(values)));
    /** Bereich „NDV“: Natural-Blockfarben im Editor (wie MainframeMate). */
    final NdvSettingsPanel ndv = new NdvSettingsPanel();
    final NdvAnalysisSettingsPanel ndvAnalysis = new NdvAnalysisSettingsPanel();
    final GlossaryRulesEditor glossaryRules;
    /** Bereich „Bibliotheken“: die ausgelieferten Open-Source-Bibliotheken mit Lizenz. */
    final LibrariesView libraries = new LibrariesView();
    private final Facts runtime = new Facts();
    private final Feedback feedback = new Feedback();
    private final ComposerButton reload = Comic.primary("Neu laden", "Konfiguration neu lesen und Laufzeit neu starten");
    private final ComposerButton create = Comic.button("Beispiel anlegen", "Beispielkonfiguration an diesem Ort anlegen");
    private final ComposerButton openFile = Comic.button("Datei öffnen", "Konfiguration im Standardeditor öffnen");
    private final ComposerButton openFolder = Comic.button("Ordner öffnen", "Ordner der Konfiguration öffnen");
    private final ComposerButton openData = Comic.button("Ordner öffnen", "Datenordner des lokalen Bestands im Dateimanager öffnen");
    private final ComposerButton editPageSize = Comic.button(SettingDialogs.PAGE_SIZE_TITLE + " …",
            "Größere Dateien lädt die App nicht herunter, sondern legt nur ihre Kopfdaten ab");
    /** Der Ausgabeordner als Feld; „Durchsuchen …“, „Öffnen“ und „Zurück auf Downloads“ sitzen im Feld. */
    final com.aresstack.spaceharvester.app.ui.controls.FolderField outputFolder =
            new com.aresstack.spaceharvester.app.ui.controls.FolderField(Comic.field(24));
    final javax.swing.JButton openOutput = outputFolder.addButton(
            com.aresstack.spaceharvester.app.ui.controls.InputFrame.flatButton("Öffnen",
                    "Den Ausgabeordner anzeigen"));
    final javax.swing.JButton resetOutput = outputFolder.addButton(
            com.aresstack.spaceharvester.app.ui.controls.InputFrame.flatButton("Zurück auf Downloads",
                    "Wieder den Downloads-Ordner des Benutzers verwenden"));
    static final String OUTPUT_HINT = "Alle Exporte: Dateien aus dem Export-Reiter, Metadaten und Analytics, PPTX "
            + "und PDF aus Videos; „Speichern unter“ beginnt hier";
    private final ComposerButton about = Comic.button("Über Space Harvester", "Version und Herkunft");
    /**
     * Platte „Terminal-Ausgabe“ unter Allgemein (Angelo 12.11.2026): welche Meldungen im Terminal erscheinen,
     * {@code logging.threshold.console}; Einträge in der Reihenfolge von {@link ConsoleOutput}.
     */
    final JComboBox<String> console = Comic.combo();
    /** Was die Auswahl zeigt; eine Wahl desselben Werts schreibt nichts. */
    private ConsoleOutput shownConsole;
    /** Während {@link #renderConsole} die Auswahl setzt, ist das keine Wahl des Benutzers. */
    private boolean showingConsole;
    /** Platte „Darstellung“ unter Allgemein (Angelo 17.11.2026). */
    static final String DISPLAY_TITLE = "Darstellung";
    /** Zeile der Platte „Darstellung“ und Zeile auf der Abschlussseite des Assistenten. */
    static final String VIEWER_TITLE = "Hintergrund des Bildbetrachters";
    static final String VIEWER_HINT = "Die Fläche hinter dem Bild in „Bilder der Seite“. Gilt beim nächsten Öffnen, "
            + "ohne Neustart.";
    /** Auswahl „Hintergrund des Bildbetrachters“, {@code space-harvester-app.viewer.background}; Einträge in der
     * Reihenfolge von {@link ViewerBackground}. */
    final JComboBox<String> viewer = Comic.combo();
    /** Was die Auswahl zeigt; eine Wahl desselben Werts schreibt nichts. */
    private ViewerBackground shownViewer;
    /** Während {@link #renderViewer} die Auswahl setzt, ist das keine Wahl des Benutzers. */
    private boolean showingViewer;
    /** Platte „Analytics-Export“ unter Allgemein und Zeile auf der Abschlussseite des Assistenten. */
    static final String ANALYTICS_TITLE = "Analytics-Export";
    static final String ANALYTICS_HINT = "Leer heißt Vorgabe: der Pfad, den die Analytics-Oberfläche von Confluence "
            + "10.2 selbst aufruft. Platzhalter {spaceKey}, {from}, {to}. Gilt für den nächsten Export, ohne Neustart.";
    /**
     * Platte „Analytics-Export“ (Angelo 16.11.2026): Zeitraum und die Pfade des Analytics-Plugins,
     * {@code space-harvester-app.analytics}; der Platzhalter jedes Feldes ist die Vorgabe, eine Eingabe ersetzt sie.
     */
    final DurationField analyticsPeriod = new DurationField(3650);
    final PlaceholderField analyticsDetails = new PlaceholderField(40, AnalyticsQueries.DEFAULT_DETAILS);
    final PlaceholderField analyticsActivity = new PlaceholderField(40, AnalyticsQueries.DEFAULT_ACTIVITY_BY_DATE);
    final PlaceholderField analyticsContentViews = new PlaceholderField(40, AnalyticsQueries.DEFAULT_CONTENT_VIEWS);
    /** Was die Felder zeigen; eine Eingabe desselben Werts schreibt nichts. */
    private AnalyticsSettings shownAnalytics;
    /** Während {@link #renderAnalytics} die Felder setzt, ist das keine Eingabe des Benutzers. */
    private boolean showingAnalytics;
    final ComposerButton setup = Comic.button(SetupWizard.TITLE + " …",
            "Netzwerk, KI-Dienst, Modelle und erste Wissensquelle Schritt für Schritt einrichten");
    private final ComposerButton proxy = Comic.button("Proxy ändern …", "Proxy für alle Quellen einstellen und auflösen");
    private final ComposerButton tls = Comic.button("Serverzertifikate …",
            "Festlegen, welchen Zertifizierungsstellen die App bei allen Quellen glaubt");
    final ComposerButton editRetry = Comic.button(SettingDialogs.RETRY_TITLE + " …",
            "Weitere Versuche bei Netzfehlern und wann sich ein Auftrag selbst anhält");

    public SettingsView(AppContext context) {
        this.context = context;
        this.ai = new AiSettingsPanel(context);
        this.fileAccess = new FileAccessEditor(context);
        this.standingWords = new StandingWordsEditor(context);
        this.video = new VideoSettingsPanel(context);
        this.glossaryRules = new GlossaryRulesEditor(context);
        this.keePass = new KeePassPairingPanel(context);
        this.releases = new PageReleasesPanel(context);
        keePass.onChange(() -> {
            ai.refreshPairing();
            render();
        });
        ai.onPairingChange(keePass::render);
        root.setBackground(Comic.SURFACE);
        problems.setForeground(Comic.RED);
        reload.addActionListener(e -> reload());
        create.addActionListener(e -> createExample());
        openFile.addActionListener(e -> open(context.configFile()));
        openFolder.addActionListener(e -> open(context.configFile().toAbsolutePath().getParent()));
        openData.addActionListener(e -> {
            if (context.settings() != null) {
                open(context.settings().storage().dataDirectory());
            }
        });
        outputFolder.dialogTitle("Ausgabeordner wählen");
        outputFolder.onChange(this::saveOutput);
        outputFolder.onInvalid(input -> feedback.warning("Ausgabeordner nicht geändert",
                "„" + input + "“ ist kein Ordner."));
        openOutput.addActionListener(e -> {
            if (context.settings() != null) {
                open(context.settings().outputDirectory());
            }
        });
        resetOutput.addActionListener(e -> saveOutput(null));
        for (ConsoleOutput level : ConsoleOutput.values()) {
            console.addItem(level.label());
        }
        console.setToolTipText("Welche Meldungen im Terminal erscheinen, aus dem die App gestartet wurde");
        console.addActionListener(e -> chooseConsole());
        for (ViewerBackground background : ViewerBackground.values()) {
            viewer.addItem(background.label());
        }
        viewer.setToolTipText("Farbe der Fläche hinter dem Bild im Bildbetrachter");
        viewer.addActionListener(e -> chooseViewer());
        analyticsPeriod.onChange(this::saveAnalytics);
        for (PlaceholderField field : List.of(analyticsDetails, analyticsActivity, analyticsContentViews)) {
            field.setToolTipText("Pfad relativ zur Confluence der Quelle, unter /rest/");
            field.addActionListener(e -> saveAnalytics());
            field.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    saveAnalytics();
                }
            });
        }
        about.addActionListener(e -> AboutDialog.open(root, context));
        setup.addActionListener(e -> {
            SetupWizard.open(root, context);
            render();
            ai.render();
        });
        proxy.addActionListener(e -> SettingDialogs.proxy(root, context, null,
                () -> feedback.success("Proxy übernommen")).open());
        tls.addActionListener(e -> SettingDialogs.tls(root, context,
                () -> feedback.success("Serverzertifikate übernommen")).open());
        editRetry.addActionListener(e -> SettingDialogs.retry(root, context,
                () -> feedback.success("Wiederholungen übernommen")).open());
        editCache.addActionListener(e -> SettingDialogs.syncRules(root, context, null,
                () -> feedback.success("Regeln aller Quellen übernommen")).open());
        viewChangeLog.addActionListener(e -> ChangeLogDialog.open(root, context));
        JPanel config = new JPanel(new BorderLayout(0, 8));
        config.setOpaque(false);
        config.add(file, BorderLayout.NORTH);
        config.add(problems, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(Comic.row(reload, create, openFile, openFolder, setup), BorderLayout.NORTH);
        south.add(feedback, BorderLayout.SOUTH);
        config.add(south, BorderLayout.SOUTH);
        JPanel data = new JPanel(new BorderLayout(0, 8));
        data.setOpaque(false);
        data.add(storage, BorderLayout.CENTER);
        data.add(Comic.row(openData, editPageSize), BorderLayout.SOUTH);
        editPageSize.addActionListener(e -> SettingDialogs.pageSize(root, context,
                () -> feedback.success(SettingDialogs.PAGE_SIZE_TITLE + " übernommen")).open());
        SettingDialogs.Rows outputPanel = new SettingDialogs.Rows();
        outputPanel.add("Ordner", outputFolder, OUTPUT_HINT);
        JPanel diagnosis = new JPanel(new BorderLayout(0, 8));
        diagnosis.setOpaque(false);
        diagnosis.add(runtime, BorderLayout.CENTER);
        diagnosis.add(Comic.row(about), BorderLayout.SOUTH);
        SettingDialogs.Rows consoleRows = new SettingDialogs.Rows();
        consoleRows.add("Anzeigen", console, CONSOLE_HINT);
        SettingDialogs.Rows displayRows = new SettingDialogs.Rows();
        displayRows.add(VIEWER_TITLE, viewer, VIEWER_HINT);
        SettingDialogs.Rows analyticsRows = new SettingDialogs.Rows();
        analyticsRows.add("Zeitraum", analyticsPeriod, "Bis heute zurück; Analytics bewahrt standardmäßig 12 Monate "
                + "auf.");
        analyticsRows.add("Space-Details", analyticsDetails, ANALYTICS_HINT);
        analyticsRows.add("Aktivität je Datum", analyticsActivity, "Anlagen, Änderungen und Aufrufe des Space im "
                + "Zeitraum. " + ANALYTICS_HINT);
        analyticsRows.add("Seitenliste", analyticsContentViews, "Aufrufe, Leser und letzter Aufruf je Seite; wird "
                + "zu Ende geblättert: {nextPageToken} ist das Token der vorigen Antwort, der Parameter davor (pageToken) "
                + "kann je Instanz anders heißen. " + ANALYTICS_HINT);
        page(GENERAL, Comic.column(
                Comic.plate("Konfiguration", Comic.BLUE, config),
                Comic.plate("Ausgabeordner", Comic.GREEN, outputPanel),
                Comic.plate(DISPLAY_TITLE, Comic.BLUE, displayRows),
                Comic.plate(ANALYTICS_TITLE, Comic.PETROL, analyticsRows),
                Comic.plate("Ablage", Comic.PETROL, data),
                Comic.plate(FileAccessEditor.HEADING, Comic.ORANGE, fileAccess.component()),
                Comic.plate("Diagnose", Comic.DARK, diagnosis),
                Comic.plate(CONSOLE_TITLE, Comic.DARK, consoleRows)));
        page(AI, Comic.column(Comic.plate("KI-Dienst", Comic.YELLOW, ai.serviceComponent())));
        page(MODELS, Comic.column(Comic.plate("Modelle für alle Quellen", Comic.YELLOW, ai.modelsComponent())));
        // Die Wortlisten stehen in einem eigenen Bereich: Wörter für den Stand (Aussagenanalyse, ADR 0032) und
        // Glossarregeln.
        page(WORDS, Comic.column(Comic.plate(null, Comic.PETROL, standingWords.component()),
                Comic.plate(null, Comic.PETROL, glossaryRules.component())));
        page(NETWORK, Comic.column(Comic.plate("Netz und Zertifikate", Comic.ORANGE,
                withButtons(network, proxy, tls)),
                Comic.plate(SettingDialogs.RETRY_TITLE, Comic.PETROL, withButtons(retry, editRetry))));
        page(CACHE, Comic.column(Comic.plate(SettingDialogs.SYNC_ALL_TITLE, Comic.PETROL,
                withButtons(cache, editCache, viewChangeLog))));
        page(VIDEO, Comic.column(Comic.plate(VideoSettingsPanel.HEADING, Comic.PETROL, video.component())));
        page(TERMINAL, Comic.column(Comic.plate(CONNECTION_HEADING, Comic.PETROL,
                        com.aresstack.spaceharvester.app.ui.deck.ConnectionSettings.form(
                                new com.aresstack.spaceharvester.app.ui.deck.KeePassServerAccounts(context::keePass))),
                Comic.plate(TerminalSettingsPanel.HEADING, Comic.PETROL, terminal.component()),
                Comic.plate(TerminalSettingsPanel.MOUSE_HEADING, Comic.PETROL, terminal.mouseComponent()),
                Comic.plate(MVS_DIAGNOSIS_HEADING, Comic.PETROL, mvsDiagnosis.component()),
                Comic.plate(DECK_TIMEOUTS_HEADING, Comic.PETROL, deckTimeouts.component())));
        page(NDV, Comic.column(Comic.plate(NdvSettingsPanel.HEADING, Comic.PETROL, ndv.component()),
                Comic.plate(NdvAnalysisSettingsPanel.ORDER_HEADING, Comic.PETROL, ndvAnalysis.orderComponent()),
                Comic.plate(NdvAnalysisSettingsPanel.STEPLIB_HEADING, Comic.PETROL, ndvAnalysis.stepLibComponent()),
                Comic.plate(NdvAnalysisSettingsPanel.RULES_HEADING, Comic.ORANGE, ndvAnalysis.rulesComponent())));
        page(BETAVIEW, Comic.column(
                Comic.plate(BetaViewSettingsPanel.CONNECTION_HEADING, Comic.BLUE, betaView.connection()),
                Comic.plate(BetaViewSettingsPanel.HEADING, Comic.BLUE, betaView.component())));
        page(BROWSER, Comic.column(
                Comic.plate(BrowserSettingsPanel.HEADING, Comic.ORANGE, browser.note()),
                Comic.plate("Profil Extern", Comic.BLUE, browser.external()),
                Comic.plate("Profil Intern", Comic.ORANGE, browser.internal()),
                Comic.plate(BrowserSettingsPanel.CHANGES_HEADING, Comic.RED, browser.changes())));
        var deck = new com.aresstack.spaceharvester.app.ui.deck.visual.DiagramSettingsPanel();
        page(DIAGRAMS, Comic.column(
                Comic.plate(com.aresstack.spaceharvester.app.ui.deck.visual.DiagramSettingsPanel.DIAGRAM_HEADING,
                        Comic.BLUE, deck.diagramComponent()),
                Comic.plate(com.aresstack.spaceharvester.app.ui.deck.visual.DiagramSettingsPanel.FUNCTIONS_HEADING,
                        Comic.PETROL, deck.functionsComponent())));
        JPanel vault = new JPanel(new BorderLayout(0, 8));
        vault.setOpaque(false);
        vault.add(Comic.wrapped("Geheimnisse stehen nie in der Konfigurationsdatei: Tokens, Passwörter, der API-Key des "
                + "KI-Dienstes und das Proxy-Passwort liegen im Tresor dieses Windows-Kontos (DPAPI), als Dateien nur "
                + "für dieses Konto lesbar; in der Datei steht nur der Platzhalter ${NAME}. Der Knopf rechts löscht die "
                + "lokal gespeicherten Zugangsdaten eines Eintrags sofort; ändern an der Quelle, unter KI-Dienst bzw. "
                + "Proxy.", Comic.TEXT, 12.5f), BorderLayout.NORTH);
        vault.add(security, BorderLayout.CENTER);
        vault.add(securityFeedback, BorderLayout.SOUTH);
        page(SECURITY, Comic.column(Comic.plate(PageReleasesPanel.HEADING, Comic.PETROL, releases.component()),
                Comic.plate(KeePassPairingPanel.HEADING, Comic.BLUE, keePass.component()),
                Comic.plate("Tresor", Comic.RED, vault)));
        page(DIAGNOSIS, diagnosisHolder);
        page(LIBRARIES, Comic.column(Comic.plate(LibrariesView.HEADING, Comic.DARK, libraries.component())),
                bottomRows);
        pageDeck.setOpaque(false);
        root.add(pageDeck, BorderLayout.CENTER);
        buildDrawer();
        select(GENERAL);
        context.onChange(ignored -> {
            render();
            fileAccess.render();
            standingWords.render();
            glossaryRules.render();
            keePass.render();
            if (!VIDEO.equals(selected)) {
                video.render();
            }
            // Eingaben in den offenen Bereichen „KI-Dienst“ und „Modelle“ bleiben stehen; beim nächsten Öffnen gilt der
            // neue Stand.
            if (!AI.equals(selected) && !MODELS.equals(selected)) {
                ai.render();
            }
        });
        render();
    }

    private void page(String name, JComponent content) {
        page(name, content, navRows);
    }

    /** @param into die Zeilenliste im Drawer: {@link #navRows} (scrollt) oder {@link #bottomRows} (am Boden) */
    private void page(String name, JComponent content, JPanel into) {
        pageDeck.add(content, name);
        SettingsNavRow row = new SettingsNavRow(name, () -> select(name));
        row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        rows.put(name, row);
        into.add(row);
        into.add(javax.swing.Box.createVerticalStrut(2));
    }

    /**
     * Der Drawer wie im AskAI-Client ({@code ChatWorkspacePanel.renderSettingsRows} und {@code buildSettingsFooter}):
     * Überschrift, die Bereiche als Zeilen untereinander, unten der Weg zurück. Zwischen beiden, am Boden angeheftet
     * und nicht mitscrollend, die Zeile „Bibliotheken“ (Angelo, 09.11.2026).
     */
    private void buildDrawer() {
        drawer.setOpaque(false);
        drawer.add(Comic.caption(AppContext.Section.SETTINGS.title()), BorderLayout.NORTH);
        navRows.setLayout(new javax.swing.BoxLayout(navRows, javax.swing.BoxLayout.Y_AXIS));
        navRows.setOpaque(false);
        navRows.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 0, 4, 0));
        javax.swing.JScrollPane scroll = Comic.scroll(navRows);
        scroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        drawer.add(scroll, BorderLayout.CENTER);
        back.addActionListener(e -> leave.run());
        bottomRows.setLayout(new javax.swing.BoxLayout(bottomRows, javax.swing.BoxLayout.Y_AXIS));
        bottomRows.setOpaque(false);
        bottomRows.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 0, 4, 0));
        JPanel foot = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        foot.setOpaque(false);
        foot.add(back);
        JPanel floor = new JPanel(new BorderLayout(0, 6));
        floor.setOpaque(false);
        floor.add(bottomRows, BorderLayout.NORTH);
        floor.add(foot, BorderLayout.SOUTH);
        drawer.add(floor, BorderLayout.SOUTH);
    }

    /** Zeigt rechts den Bereich {@code name} und markiert ihn links im Drawer. */
    /** Seite „Diagnose“: nimmt den Status-Bereich auf, sobald das Hauptfenster ihn übergibt. */
    private final JPanel diagnosisHolder = new JPanel(new BorderLayout());
    private View diagnosis;

    /** Hängt die Diagnose ({@link StatusView}) als Seite ein; beim Öffnen der Seite lädt sie neu. */
    public void diagnosis(View view) {
        diagnosis = view;
        diagnosisHolder.setOpaque(false);
        diagnosisHolder.removeAll();
        diagnosisHolder.add(view.component(), BorderLayout.CENTER);
        diagnosisHolder.revalidate();
    }

    void select(String name) {
        if (!rows.containsKey(name)) {
            return;
        }
        selected = name;
        rows.forEach((title, row) -> row.setSelected(title.equals(name)));
        pages.show(pageDeck, name);
        if (DIAGNOSIS.equals(name) && diagnosis != null) {
            diagnosis.shown();
        }
        if (BROWSER.equals(name)) {
            // Der Chat kann inzwischen Hosts gemerkt haben („Immer erlauben“/„Immer verbieten“).
            browser.load();
        }
        if ((AI.equals(name) || MODELS.equals(name)) && !ai.changed()) {
            ai.render();
        }
    }

    /** Der gewählte Bereich. */
    String selected() {
        return selected;
    }

    /** Die Navigation der Bereiche für den linken Drawer des Hauptfensters. */
    public JComponent drawer() {
        return drawer;
    }

    /** Was „Zurück“ im Drawer tut (das Hauptfenster zeigt den vorigen Bereich). */
    public void onLeave(Runnable leave) {
        this.leave = leave == null ? () -> { } : leave;
    }

    /** Für Tests: die Zeilen der Navigation in ihrer Reihenfolge. */
    /**
     * Ein Papierkorb-Knopf in der Zeile eines Eintrags: löscht nach Rückfrage die lokal gespeicherten Zugangsdaten
     * (Datei im Tresor und den Wert dieser Sitzung), ohne den Wert je anzuzeigen, und startet die Laufzeit neu, damit
     * auch die Kopien in den laufenden Quellen und im KI-Dienst verschwinden; sofort, nicht erst beim nächsten Start.
     */
    private IconButton deleteButton(String what, Background.ThrowingRunnable delete) {
        IconButton button = new IconButton(Glyph.TRASH, "Lokal gespeicherte Zugangsdaten löschen: " + what);
        Runnable run = () -> Background.execute(delete, () -> context.reload(() -> {
            render();
            ai.render();
            securityFeedback.success("Gelöscht: " + what + "; die Laufzeit wurde ohne diese Zugangsdaten neu gestartet");
        }), securityFeedback::problem);
        button.addActionListener(e -> {
            if (Dialogs.restartAllowed(root, context) && Dialogs.confirm(root, "Zugangsdaten löschen?", what
                    + " wirklich löschen? Die lokal gespeicherte Datei wird gelöscht und die Laufzeit neu gestartet, "
                    + "damit der Wert sofort nirgends mehr gilt. Die App fragt beim nächsten Zugriff neu danach.")) {
                run.run();
            }
        });
        deleteButtons.add(button);
        deletions.put(button, run);
        return button;
    }

    /** Löscht wie der Knopf, ohne Rückfrage; für Tests. */
    void deleteWithoutAsking(IconButton button) {
        deletions.get(button).run();
    }

    java.util.List<SettingsNavRow> navRows() {
        return java.util.List.copyOf(rows.values());
    }

    private void renderSecurity(AppSettings settings) {
        security.reset();
        deleteButtons.clear();
        deletions.clear();
        security.add("Tresor", context.vault().describe());
        if (settings == null) {
            return;
        }
        // Je Zeile: woher der Wert kommt. Nur was lokal in einer Datei liegt, bekommt den Papierkorb; aus KeePass
        // gelesene Werte liegen nie lokal, dort gibt es nichts zu löschen.
        com.aresstack.spaceharvester.app.settings.AiAccess access = settings.aiAccess();
        boolean aiKeyStored = context.aiVault().has(AiSettingsPanel.KEY_ID);
        String aiState;
        String aiTip = null;
        if (aiKeyStored) {
            // Eine Datei liegt noch da, auch wenn der Dienst inzwischen aus KeePass liest: Der Papierkorb zeigt sie an.
            aiState = "im Tresor" + (access.fromKeePass() ? "; nicht mehr genutzt, der Dienst liest aus KeePass („"
                    + access.entry() + "“)" : "");
        } else if (access.fromKeePass()) {
            aiState = "in KeePass („" + access.entry() + "“)" + (context.aiKeyAvailable() ? "" : "; noch nicht gelesen");
            aiTip = KEEPASS_TIP;
        } else if (context.aiKeyAvailable()) {
            aiState = "nur für diese Sitzung";
        } else {
            aiState = settings.ai().configured() ? "fehlt" : "kein KI-Dienst eingerichtet";
        }
        security.add("API-Key des KI-Dienstes", aiState, Comic.INK,
                aiKeyStored ? deleteButton("API-Key des KI-Dienstes", () -> {
                    context.aiVault().remove(AiSettingsPanel.KEY_ID);
                    context.sessionAiKey(null);
                }) : null, aiTip);
        ProxySettings proxy = settings.proxy();
        if (proxy != null && proxy.authentication() != null) {
            String username = proxy.authentication().username();
            boolean proxyStored = context.proxyVault().has(AppContext.PROXY_KEY, username);
            security.add("Proxy-Passwort", proxyStored ? "im Tresor"
                    : context.proxyPasswordAvailable(username) ? "nur für diese Sitzung" : "fehlt", Comic.INK,
                    proxyStored ? deleteButton("Proxy-Passwort", () -> {
                        context.proxyVault().remove(AppContext.PROXY_KEY);
                        context.sessionProxyPassword(null, null);
                    }) : null);
        }
        for (AppSettings.SourceEntry source : settings.sources()) {
            String state;
            String tip = null;
            boolean stored = context.vault().has(source.id());
            String keePassEntry = settings.keePassEntry(source.id());
            if (stored) {
                state = "im Tresor" + (keePassEntry != null ? "; nicht mehr genutzt, die Quelle liest aus KeePass („"
                        + keePassEntry + "“)" : "");
            } else if (keePassEntry != null && source.secretMissing()) {
                state = "in KeePass („" + keePassEntry + "“)"
                        + (context.secrets().has(source) ? "" : "; noch nicht gelesen");
                tip = KEEPASS_TIP;
            } else if (source.secretMissing()) {
                state = context.secrets().has(source) ? "nur für diese Sitzung" : "fehlt";
            } else if (source.secretVariable() != null) {
                state = "aus ${" + source.secretVariable() + "}";
            } else {
                state = "keins nötig";
            }
            security.add("Zugang " + source.config().title(), state, Comic.INK,
                    stored ? deleteButton("Zugangsdaten von „" + source.config().title() + "“", () -> {
                        context.vault().remove(source.id());
                        context.secrets().remove(source.id());
                    }) : null, tip);
        }
        try {
            if (new ConfigFileEditor(context.loader()).fileHasPlaintextSecret(context.configFile())) {
                security.add("Konfigurationsdatei", "enthält ein Geheimnis im Klartext; bitte durch ${NAME} ersetzen",
                        Comic.RED);
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // Nicht lesbar: steht schon unter Allgemein als Problem.
        }
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void shown() {
        render();
        ai.render();
        if (BROWSER.equals(selected)) {
            browser.load();
        }
        // Die Diagnose hält ihre Abfrage an, solange sie nicht sichtbar ist; steht ihre Seite noch an, läuft sie weiter.
        if (DIAGNOSIS.equals(selected) && diagnosis != null) {
            diagnosis.shown();
        }
    }

    /** Öffnet den Reiter „KI-Dienst“, z. B. vom Hinweis an der semantischen Suche. */
    public void showAi() {
        select(AI);
    }

    /** Für Tests. */
    AiSettingsPanel aiPanel() {
        return ai;
    }

    private void render() {
        Path config = context.configFile().toAbsolutePath();
        boolean exists = Files.isRegularFile(config);
        file.reset();
        file.add("Datei", config.toString())
                .add("Vorhanden", exists ? "ja" : "nein – es gelten die Standards ohne Quellen",
                        exists ? Comic.INK : Comic.ORANGE);
        create.setVisible(!exists);
        openFile.setEnabled(exists);
        AppSettings settings = context.settings();
        StringBuilder text = new StringBuilder();
        if (context.startProblem() != null) {
            Problems.Problem p = Problems.describe(context.startProblem());
            text.append("Start fehlgeschlagen: ").append(p.title()).append(". ").append(p.message()).append('\n');
        }
        if (settings != null) {
            settings.problems().forEach(problem -> text.append("• ").append(problem).append('\n'));
        }
        if (context.runtime() != null) {
            context.runtime().rejectedSources().forEach(problem -> text.append("• ").append(problem).append('\n'));
            context.runtime().lastRejection().forEach(problem -> text.append("• ").append(problem).append('\n'));
        }
        problems.setText(text.toString().strip());
        problems.setVisible(!text.isEmpty());

        storage.reset();
        network.reset();
        outputFolder.path(settings == null ? null : settings.outputDirectory());
        outputFolder.editable(settings != null);
        openOutput.setEnabled(settings != null);
        resetOutput.setVisible(settings != null
                && !settings.outputDirectory().equals(AppSettings.defaultOutputDirectory()));
        if (settings != null) {
            AppSettings.Storage s = settings.storage();
            storage.add("Daten (Cache, Aufträge, Analysen)", s.dataDirectory().toString())
                    .add("Exporte", s.exportDirectory().toString())
                    .add("Anhänge", s.assetDirectory().toString())
                    .add("Größter Anhang", Texts.sizeLimit(s.maxAssetBytes()))
                    .add(SettingDialogs.PAGE_SIZE_TITLE, Texts.bytes(s.maxPageBytes()))
                    .add(SettingDialogs.TEXT_SIZE_TITLE, s.maxTextFileBytes() == 0 ? "nie"
                            : "bis " + Texts.bytes(s.maxTextFileBytes()))
                    .add(SettingDialogs.CHECK_TIMEOUT_TITLE, ConfigFileEditor.duration(s.checkTimeout())
                            + ", große Dateien " + ConfigFileEditor.duration(s.largeFileTimeout()))
                    .add("Zeitgrenze der Suche", s.searchTimeout().toString().substring(2).toLowerCase(Locale.ROOT))
                    .add("Größter Seitenbaum", s.maxTreeNodes() + " Seiten")
                    .add("PDF-Schrift", s.pdfFont() == null ? "eingebaut" : s.pdfFont().toString());
            ProxySettings proxy = settings.proxy();
            network.add("Proxy", proxy == null ? "direkt" : proxy.mode().name().toLowerCase(Locale.ROOT).replace('_', '-'));
            if (proxy != null && proxy.pacUrl() != null) {
                network.add("PAC-Adresse", proxy.pacUrl().toString());
            }
            if (proxy != null && proxy.proxyHost() != null) {
                network.add("Proxy-Host", proxy.proxyHost() + (proxy.proxyPort() == null ? "" : ":" + proxy.proxyPort()));
            }
            if (proxy != null && proxy.authentication() != null) {
                String user = proxy.authentication().username();
                network.add("Proxy-Anmeldung", user + (context.proxyPasswordAvailable(user) ? " · Passwort gespeichert"
                        : " · Passwort fehlt"));
            }
            if (proxy != null && proxy.pacDiscoveryScript() != null) {
                network.add("PAC-Skript", "eigenes Skript");
            }
            if (proxy != null && !proxy.nonProxyHosts().isEmpty()) {
                network.add("Ohne Proxy", String.join(", ", proxy.nonProxyHosts()));
            }
            network.add("Transport", settings.asyncTransport().name().toLowerCase(Locale.ROOT).replace('_', '-'));
            network.add("Serverzertifikate", settings.tls().describe() + " (alle Quellen ohne eigenes Bundle)");
            settings.trustStores().values().forEach(store -> network.add("Vertrauensquelle " + store.name(),
                    (store.pem() != null ? "PEM " + store.pem() : store.location() + " (" + (store.type() == null
                            ? "Typ nach Endung" : store.type()) + ")")
                            + (store.password() == null ? "" : " · Passwort gesetzt")));
            for (AppSettings.SourceEntry source : settings.sources()) {
                var certificate = source.config().clientCertificate();
                if (certificate != null && certificate.enabled()) {
                    network.add("Client-Zertifikat " + source.id(),
                            certificate.type().name().toLowerCase(Locale.ROOT).replace('_', '-')
                                    + (certificate.path() == null ? "" : " · " + certificate.path())
                                    + (certificate.alias() == null ? "" : " · Alias " + certificate.alias())
                                    + (certificate.thumbprint() == null ? "" : " · Fingerabdruck "
                                    + Texts.shortHash(certificate.thumbprint())));
                }
            }
        }
        renderSecurity(settings);
        renderConsole(settings);
        renderViewer(settings);
        renderAnalytics(settings);
        retry.reset();
        if (settings != null) {
            var r = settings.jobLimits().retry();
            retry.add(SettingDialogs.RETRY_ATTEMPTS, r.maxAttempts() == 0 ? "keine (aus)"
                    : r.maxAttempts() + " · Wartezeit " + ConfigFileEditor.delay(r.initialDelay()) + " bis "
                    + ConfigFileEditor.delay(r.maxDelay()));
            retry.add(SettingDialogs.RETRY_PAUSE, Texts.count(r.pauseAfterFailures(), "Fehler", "Fehlern")
                    + " nacheinander ohne Verbindung");
        }
        cache.reset();
        if (settings != null) {
            AppSettings.Cache c = settings.cache();
            SyncSettings sync = settings.sync();
            cache.add(SettingDialogs.CHANGE_SEARCH_TITLE, SettingDialogs.every(sync.interval()))
                    .add(SettingDialogs.INVENTORY_TITLE, SettingDialogs.every(sync.inventoryInterval()))
                    .add(SettingDialogs.MODE_TITLE, SettingDialogs.modeLabel(sync.mode()))
                    .add(SettingDialogs.CATCH_UP_TITLE, sync.catchUpOnStart() ? "an" : "aus")
                    .add(SettingDialogs.BEFORE_SEARCH_TITLE, sync.beforeSearch() ? "an" : "aus");
            cache.add(SettingDialogs.REFRESH_TITLE, SettingDialogs.refreshStrategy(c));
            if (!c.maxAge().isZero()) {
                cache.add(SettingDialogs.CACHE_MAX_AGE, ConfigFileEditor.duration(c.maxAge()) + " ohne Rückfrage, danach "
                        + (c.versionCheck() ? "Version prüfen" : "neu laden"));
            }
            java.time.Duration kept = c.withheldRetention();
            cache.add(SettingDialogs.WITHHELD_RETENTION, kept.equals(java.time.Duration.ofDays(kept.toDays()))
                    ? Texts.count(kept.toDays(), "Tag", "Tagen") : ConfigFileEditor.duration(kept));
            cache.add(SettingDialogs.BACKGROUND_TITLE, settings.sync().backgroundFill()
                    ? "an · " + settings.sync().backgroundParallelism() + " gleichzeitig" : "aus");
            cache.add(SettingDialogs.CHANGE_LOG_RETENTION, SettingDialogs.changeLogRetention(c.changeLog()));
            cache.add(SettingDialogs.SHOW_AFTER_UPDATE, c.changeLog().showAfterUpdate() ? "an" : "aus");
        }
        runtime.reset();
        AppRuntime current = context.runtime();
        runtime.add("App", context.version())
                .add("Java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")")
                .add("System", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                        + System.getProperty("os.arch"))
                .add("Protokoll", logDirectory().resolve("space-harvester-app.log").toString())
                .add("Laufzeit", current == null ? "nicht gestartet" : "läuft", current == null ? Comic.RED : Comic.GREEN)
                .add("Quellen", current == null ? "–" : String.valueOf(current.workspace().sources().size()))
                .add("Warten auf Zugangsdaten", current == null || current.waitingForSecret().isEmpty() ? "keine"
                        : String.join(", ", current.waitingForSecret()));
        if (current != null) {
            runtime.add("Analysen", String.join(", ", current.workspace().enrichmentProcessors()))
                    .add("Exportformate", String.join(", ", current.workspace().exportFormats()));
        }
        root.revalidate();
        root.repaint();
    }

    private static JComponent withButtons(JComponent content, ComposerButton... buttons) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        panel.add(content, BorderLayout.CENTER);
        panel.add(Comic.row(buttons), BorderLayout.SOUTH);
        return panel;
    }

    /** Wie logback.xml: {@code SPACE_HARVESTER_LOG_DIR} oder {@code <Benutzerordner>/.space-harvester/logs}. */
    public static Path logDirectory() {
        String configured = System.getenv("SPACE_HARVESTER_LOG_DIR");
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".space-harvester", "logs") : Path.of(configured);
    }

    private void reload() {
        if (!Dialogs.restartAllowed(root, context)) {
            return;
        }
        reload.setEnabled(false);
        feedback.working("Konfiguration wird neu geladen …");
        context.reload(() -> {
            reload.setEnabled(true);
            if (context.runtime() == null) {
                feedback.problem(context.startProblem());
            } else if (!context.runtime().lastRejection().isEmpty()) {
                feedback.warning("Nicht übernommen", "Der Core hat die Änderung abgelehnt; die bisherige Einstellung "
                        + "gilt weiter. Details unter Konfiguration.");
            } else if (!context.settings().problems().isEmpty()) {
                feedback.warning("Neu geladen, mit Hinweisen", "Quellen mit Fehlern fehlen, bis die Datei stimmt.");
            } else {
                feedback.success("Neu geladen");
            }
        });
    }

    private void createExample() {
        Path target = context.configFile().toAbsolutePath();
        Background.execute(() -> {
            Files.createDirectories(target.getParent());
            try (InputStream in = SettingsView.class.getResourceAsStream(EXAMPLE)) {
                if (in == null) {
                    throw new IOException("Beispielkonfiguration fehlt im JAR");
                }
                Files.copy(in, target);
            }
        }, () -> {
            feedback.success("Beispiel angelegt: " + target + ". Datei anpassen, dann „Neu laden“.");
            render();
            open(target);
        }, feedback::problem);
    }

    /** @param directory neuer Ordner oder {@code null} für Downloads */
    void saveOutput(Path directory) {
        feedback.working("Ausgabeordner wird gespeichert …");
        context.outputDirectory(directory, () -> feedback.success("Ausgabeordner: "
                + context.settings().outputDirectory()), error -> {
                    // Nicht gespeichert: das Feld zeigt wieder den geltenden Ordner.
                    outputFolder.path(context.settings() == null ? null : context.settings().outputDirectory());
                    feedback.problem(error);
                });
    }

    /** Zeigt die Terminal-Ausgabe aus der Datei; eine offene Liste bleibt unberührt. */
    private void renderConsole(AppSettings settings) {
        ConsoleOutput current = settings == null ? ConsoleOutput.DEFAULT : settings.consoleOutput();
        if (console.isPopupVisible()) {
            return;
        }
        showingConsole = true;
        try {
            if (console.getSelectedIndex() != current.ordinal()) {
                console.setSelectedIndex(current.ordinal());
            }
        } finally {
            showingConsole = false;
        }
        shownConsole = current;
    }

    /** Zeigt den Hintergrund des Bildbetrachters aus der Datei; eine offene Liste bleibt unberührt. */
    private void renderViewer(AppSettings settings) {
        ViewerBackground current = settings == null ? ViewerBackground.DEFAULT : settings.viewerBackground();
        if (viewer.isPopupVisible()) {
            return;
        }
        showingViewer = true;
        try {
            if (viewer.getSelectedIndex() != current.ordinal()) {
                viewer.setSelectedIndex(current.ordinal());
            }
        } finally {
            showingViewer = false;
        }
        shownViewer = current;
    }

    /** Zeigt den Export „Analytics“ aus der Datei; ein Feld, in dem gerade getippt wird, bleibt unberührt. */
    private void renderAnalytics(AppSettings settings) {
        AnalyticsSettings current = settings == null ? AnalyticsSettings.DEFAULT : settings.analytics();
        if (current.equals(shownAnalytics)) {
            return;
        }
        showingAnalytics = true;
        try {
            if (!analyticsPeriod.spinner().isFocusOwner()) {
                analyticsPeriod.value(current.effectivePeriod());
            }
            show(analyticsDetails, current.details());
            show(analyticsActivity, current.activityByDate());
            show(analyticsContentViews, current.contentViews());
        } finally {
            showingAnalytics = false;
        }
        shownAnalytics = current;
    }

    private static void show(PlaceholderField field, String value) {
        if (!field.isFocusOwner() && !Objects.equals(field.getText(), value == null ? "" : value)) {
            field.setText(value == null ? "" : value);
        }
    }

    /**
     * Übernimmt die Felder des Exports „Analytics“, sobald sich etwas ändert (Eingabe, Enter oder Verlassen des
     * Feldes), und speichert sie in der Datei; ein Pfad außerhalb von /rest/ wird gemeldet und nicht gespeichert.
     */
    private void saveAnalytics() {
        if (showingAnalytics || context.settings() == null) {
            return;
        }
        java.time.Duration period = analyticsPeriod.value();
        if (period != null && !AnalyticsSettings.wholeDays(period)) {
            feedback.problem(new IllegalArgumentException(ANALYTICS_TITLE + ": der Zeitraum muss ganze Tage "
                    + "umfassen, mindestens einen; nicht gespeichert"));
            return;
        }
        AnalyticsSettings chosen = new AnalyticsSettings(
                period == null || period.equals(AnalyticsSettings.DEFAULT_PERIOD) ? null : period,
                analyticsDetails.getText(), analyticsActivity.getText(), analyticsContentViews.getText());
        if (chosen.equals(shownAnalytics)) {
            return;
        }
        for (String path : java.util.Arrays.asList(chosen.details(), chosen.activityByDate(), chosen.contentViews())) {
            if (path != null && !AnalyticsQueries.validPath(path)) {
                feedback.problem(new IllegalArgumentException(ANALYTICS_TITLE + ": „" + path + "“ ist kein Pfad "
                        + "unter /rest/ (ohne Leerzeichen); nicht gespeichert"));
                return;
            }
        }
        shownAnalytics = chosen;
        context.analytics(chosen, () -> feedback.success(ANALYTICS_TITLE + (chosen.isDefault() ? ": Vorgaben"
                : " gespeichert")), feedback::problem);
    }

    /** Übernimmt die gewählte Terminal-Ausgabe sofort und speichert sie in der Datei, ohne Neustart der Laufzeit. */
    private void chooseConsole() {
        int index = console.getSelectedIndex();
        if (showingConsole || index < 0) {
            return;
        }
        ConsoleOutput chosen = ConsoleOutput.values()[index];
        if (chosen == shownConsole) {
            return;
        }
        shownConsole = chosen;
        context.consoleOutput(chosen, () -> feedback.success(CONSOLE_TITLE + ": " + chosen.label()),
                feedback::problem);
    }

    /** Speichert den gewählten Hintergrund des Bildbetrachters in der Datei, ohne Neustart der Laufzeit. */
    private void chooseViewer() {
        int index = viewer.getSelectedIndex();
        if (showingViewer || index < 0) {
            return;
        }
        ViewerBackground chosen = ViewerBackground.values()[index];
        if (chosen == shownViewer) {
            return;
        }
        shownViewer = chosen;
        context.viewerBackground(chosen, () -> feedback.success(VIEWER_TITLE + ": " + chosen.label()),
                feedback::problem);
    }

    private void open(Path path) {
        open(path, feedback);
    }

    /** Öffnet einen Ordner oder den Ordner einer Datei im Dateimanager; legt einen fehlenden Ordner an. */
    public static void open(Path path, Feedback feedback) {
        if (path == null) {
            return;
        }
        Background.execute(() -> {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Öffnen wird von diesem System nicht unterstützt");
            }
            Files.createDirectories(Files.isRegularFile(path) ? path.getParent() : path);
            Desktop.getDesktop().open(path.toFile());
        }, () -> { }, feedback::problem);
    }
}
