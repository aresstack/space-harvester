package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Downloads;
import com.aresstack.spaceharvester.app.ui.Facts;
import com.aresstack.spaceharvester.app.ui.Feedback;
import com.aresstack.spaceharvester.app.ui.FormatIcon;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.SettingsView;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.export.ExportAssetCollection;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.holdings.HoldingsListener;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.SpaceSummary;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.metadata.SourceQueryExports;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.Partition;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reiter „Export“ der Seitenansicht (Angelo, 13.11.2026): Was links im Explorer gewählt ist, bestimmt den Inhalt,
 * eine eigene Auswahl gibt es nicht. Für einen Space oder eine Seite samt Unterseiten steht oben die Karte
 * <b>Stand</b> (jüngster fertiger Download, laufender Auftrag, Laden bzw. Aktualisieren) und darunter die Karte
 * <b>Ausgabe</b> mit den Format-Knöpfen PDF, Word, Markdown, JSON und Analyse, dem Ausgabeordner, den erzeugten
 * Dateien und den Hinweisen der Konvertierung; beides zog aus „Aufträge“ hierher um, dort bleibt nur der
 * Download-Manager. Für eine Wissensquelle und für „Wissensquellen“ (alle Quellen) gilt dasselbe über alle ihre
 * Spaces (Angelo 17.11.2026): jeder Format-Knopf startet je Space eigene Aufträge und getrennte Dateien, auch
 * Confluence-Metadaten und Analytics; die Dateiliste nennt je Space seine laufenden Aufträge und fertigen Dateien.
 * Die Übersicht der Spaces mit ihrem Stand steht im Reiter „Info“, nicht hier.
 *
 * <p>Übergang bis zur Bestandsübersicht des Core: „Stand“ ist der jüngste fertige Download genau dieser Auswahl
 * (ganzer Space bzw. dieselbe Startseite); die Ausgabe arbeitet auf seinem Datenbestand. Änderungen kommen über die
 * Auftragsmeldungen des Core (ADR 0027), nicht über einen Zeitgeber.
 */
public final class ExportPanel extends JPanel {

    /**
     * Die Auswahl links im Explorer: Quelle, Space oder Seite (samt Unterseiten); {@code title} für die Überschrift.
     * Ohne Quelle ({@link #ALL}) ist es die globale Auswahl über alle Wissensquellen (Angelo, 16.11.2026): die
     * Überschrift „Wissensquellen“ im Explorer ist gewählt.
     */
    public record Selection(String sourceId, String spaceKey, String pageId, String title) {

        /** Alle Wissensquellen: die Überschrift des Explorers ist gewählt, kein Knoten im Baum. */
        public static final Selection ALL = new Selection(null, null, null, "Alle Wissensquellen");

        /** Ob dies die globale Auswahl über alle Wissensquellen ist. */
        public boolean global() {
            return sourceId == null;
        }

        boolean page() {
            return pageId != null;
        }

        boolean space() {
            return spaceKey != null && pageId == null;
        }

        /**
         * Dieselbe Auswahl für den Core (ADR 0029): Quelle, Space oder Seite samt allen Unterseiten; die globale
         * Auswahl als eine Wurzel je Wissensquelle der Laufzeit.
         */
        public com.aresstack.spaceharvester.application.holdings.Selection toCore(AppRuntime runtime) {
            if (global()) {
                return new com.aresstack.spaceharvester.application.holdings.Selection(
                        runtime.workspace().sources().stream().map(source ->
                                com.aresstack.spaceharvester.application.holdings.Selection.Root.source(source.id()))
                                .toList());
            }
            com.aresstack.spaceharvester.application.holdings.Selection.Root root = pageId != null
                    ? com.aresstack.spaceharvester.application.holdings.Selection.Root.subtree(sourceId, spaceKey, pageId)
                    : spaceKey != null
                    ? com.aresstack.spaceharvester.application.holdings.Selection.Root.space(sourceId, spaceKey)
                    : com.aresstack.spaceharvester.application.holdings.Selection.Root.source(sourceId);
            return com.aresstack.spaceharvester.application.holdings.Selection.of(root);
        }

        /**
         * Ob ein Download zu dieser Auswahl gehört: für Space und Seite genau derselbe Space und dieselbe Startseite
         * (oder keine); für eine Quelle jeder ihrer Spaces, für „Wissensquellen“ jeder Space jeder Quelle.
         */
        boolean matches(JobStatus job) {
            return job.type() == JobType.EXPORT && covers(job);
        }

        /** Ob ein Auftrag „Datei erzeugen“ zu dieser Auswahl gehört (wie {@link #matches}). */
        boolean matchesConversion(JobStatus job) {
            return job.type() == JobType.CONVERSION && covers(job);
        }

        /** Ob der Auftrag in dieser Auswahl liegt: Quelle und „Wissensquellen“ decken alle ihre Spaces ab. */
        boolean covers(JobStatus job) {
            if (global()) {
                return job.sourceId() != null;
            }
            if (spaceKey == null) {
                return sourceId.equals(job.sourceId());
            }
            return same(job);
        }

        private boolean same(JobStatus job) {
            return Objects.equals(job.sourceId(), sourceId) && Objects.equals(job.partitionKey(), spaceKey)
                    && Objects.equals(job.rootPageId(), pageId);
        }

        /** Dieselbe Auswahl ohne Überschrift, als Schlüssel; die Überschrift kann je Aufruf anders lauten. */
        Selection normalized() {
            return title == null ? this : new Selection(sourceId, spaceKey, pageId, null);
        }

        /** Der Space eines Auftrags als Auswahl (Schlüssel ohne Überschrift). */
        static Selection ofJob(JobStatus job) {
            return new Selection(job.sourceId(), job.partitionKey(), job.rootPageId(), null);
        }
    }

    /** Formate der Knöpfe, in der Reihenfolge aus Angelos Skizze. */
    public static final List<String> FORMATS = List.of("pdf", "docx", "markdown-package", "json-package", "analysis");
    private static final Map<String, String> EXPORT_TIPS = Map.of("pdf", "Als PDF exportieren", "docx",
            "Als Word-Dokument exportieren", "markdown-package",
            "Als Markdown exportieren (ZIP mit Seiten und Bildern)", "json-package",
            "Als JSON exportieren (ZIP mit Seiten, Bildern und Diagrammen)", "analysis",
            "Analyse exportieren (analysis.json: Analysedaten je Seite, Originaldaten unverändert)");
    static final String NOT_LOADED = "Noch nicht geladen";
    /** Ein berührter Space ohne Inventur: der Cache kennt dort nur einzeln geladene Seiten. */
    static final String INCOMPLETE = "lückenhaft, Space ohne Inventur";
    static final String LOAD_SPACE = "Ganzen Space laden";
    /** Für eine Quelle bzw. „Wissensquellen“: alle Spaces laden, je Space ein Auftrag. */
    static final String LOAD_ALL = "Alle Spaces laden";
    static final String ALL_TITLE = "Alle Wissensquellen?";
    /** Rückfrage nur bei „Wissensquellen“ (Architekt 17.11.2026), wie beim Aktualisieren im Explorer: Ja/Nein. */
    static final String LOAD_ALL_QUESTION = "Wirklich alle Wissensquellen laden? Für jeden Space jeder Wissensquelle "
            + "startet sofort ein eigener Auftrag.";
    static final String NO_SPACES = "keine Spaces bekannt";
    static final String NOTHING_YET = "noch nichts ausgegeben";
    static final String REFRESH = "Aktualisieren";
    static final String LOAD_SUBTREE = "Samt Unterseiten laden";
    static final String LOAD_PAGE = "Nur diese Seite laden";
    static final String COLLECT = "Bilder/Anhänge beschaffen";
    static final String NOTHING_LOCAL = "Nichts lokal gespeichert";
    static final String COLLECT_TIP = "Holt Bilder und, bei eingeschalteten Anhängen, Anhänge der lokal gespeicherten "
            + "Seiten dieser Auswahl; was schon im Bestand liegt, wird nicht erneut abgerufen";
    static final String NO_DATASET = "Noch nichts ausgegeben; die Format-Knöpfe geben die Auswahl aus dem Bestand "
            + "aus, ohne die Quelle zu lesen.";
    static final String NO_HOLDINGS = "Kein Bestand für diese Auswahl: ein Format-Knopf lädt erst die Seiten von der "
            + "Quelle und erzeugt dann die Datei.";
    static final String NOT_AVAILABLE = "kein Datenbestand vorhanden";
    static final String NO_SELECTION = "keine Auswahl";
    /** Die Format-Knöpfe sperrt nur ein offener Auftrag der Auswahl (Architekt 17.11.2026), wie beim Space. */
    static final String IN_PROGRESS = " wird gerade erzeugt";
    /** Zusatz im Tooltip eines Format-Knopfs ohne Bestand (Angelo 16.11.2026). */
    static final String LOADS_FIRST = "lädt erst die Seiten von der Quelle";
    /**
     * Die beiden Exporte aus der Quelle (Angelo 16.11.2026) rechts neben den Format-Knöpfen: „Confluence-Metadaten“
     * (dokumentierte REST-Schnittstellen) und „Analytics“ (Seitenaufrufe über das Analytics-Plugin), je als eigener
     * Auftrag unter Aufträge und eigene JSON-Datei im Ausgabeordner; anders als die Formate lesen sie die Quelle.
     */
    public static final List<String> SOURCE_EXPORTS = List.of("metadata", "analytics");
    private static final Map<String, String> SOURCE_TIPS = Map.of("metadata",
            "Confluence-Metadaten exportieren (eigene JSON-Datei: Historie und alle Bearbeiter, Versionen, "
                    + "Kommentare, Properties und Restriktionen je Seite über die dokumentierten REST-Schnittstellen; "
                    + "liest die Quelle, Auftrag unter Aufträge)", "analytics",
            "Analytics exportieren (eigene JSON-Datei: Seitenaufrufe je Seite über das Confluence-Analytics-Plugin; "
                    + "liest die Quelle, Auftrag unter Aufträge)");
    static final String ONLY_CONFLUENCE = "nur für Confluence-Quellen";
    static final String NO_PAGES = "keine Seite bekannt, erst laden";

    private final AppContext context;
    private final Feedback feedback = new Feedback();
    private final Facts stand = new Facts();
    private final ComposerButton load = Comic.button(LOAD_SPACE, "Startet den Download sofort; der Auftrag läuft "
            + "unter Aufträge");
    private final ComposerButton loadPage = Comic.button(LOAD_PAGE, "Nur diese Seite laden, ohne Unterseiten");
    private final ComposerButton showJob = Comic.button("Auftrag anzeigen", "Den Auftrag unter Aufträge zeigen");
    /** „Bilder/Anhänge beschaffen“ für die Auswahl ohne Export (ADR 0029, Schritt 7): nur die lokal gespeicherten Seiten. */
    private final ComposerButton collect = Comic.button(COLLECT, COLLECT_TIP);
    private final JComponent standPlate;
    private final Map<String, IconButton> formatButtons = new LinkedHashMap<>();
    /** Die Knöpfe der Exporte aus der Quelle ({@link #SOURCE_EXPORTS}). */
    private final Map<String, IconButton> sourceButtons = new LinkedHashMap<>();
    /** „Bilder: …“ mit Hinweis, solange sie noch beschafft werden; bricht um, damit der Hinweis nie abgeschnitten wird. */
    final JTextArea cardImages = Comic.note("");
    /** Die Dateien dieses Downloads im Ausgabeordner und laufende Exporte, eine Zeile je Datei. */
    final JTextArea files = Comic.note("");
    private final JLabel output = new JLabel();
    private final ComposerButton openOutput = Comic.button("Ordner öffnen", "Den Ausgabeordner anzeigen");
    /** Hinweise der letzten Konvertierung des gezeigten Downloads (fehlende Bilder, Diagramme …), aufklappbar. */
    final ComposerButton toggleNotices = Comic.button("Hinweise anzeigen", "Was in der Datei fehlt oder vereinfacht ist");
    final JTextArea noticeList = Comic.note("");
    private final JComponent outputPlate;
    /**
     * Für eine Quelle und „Wissensquellen“ statt {@link #files}: je Space eine Zeile mit seinen laufenden Aufträgen
     * und fertigen Dateien; die jüngste Datei öffnet ein Knopf in der Zeile.
     */
    final Facts spaces = new Facts();
    /** Rückfrage vor einem Start für „Wissensquellen“ (Ja/Nein); Tests setzen eine eigene Antwort. */
    private java.util.function.BiPredicate<String, String> confirmAll =
            (title, question) -> com.aresstack.spaceharvester.app.ui.Dialogs.confirm(this, title, question);

    private Selection selection;
    /** Was der Reiter gerade zeigt. */
    private Data shown = Data.NONE;
    /** Datenbestand, den die Karte „Ausgabe“ gerade zeigt. */
    private ExportDatasetInfo shownDataset;
    /** Dateien des gezeigten Downloads im Ausgabeordner (Name enthält die Export-ID). */
    private List<String> outputFiles = List.of();
    /** Letzte erzeugte Datei je Download, für ihre Hinweise. */
    private final Map<String, ExportConversions.ConvertedFile> lastFile = new HashMap<>();
    /** Letzte Rückmeldung einer Datei je Download; sie bleibt stehen, während die Ansicht auffrischt. */
    private final Map<String, Consumer<Feedback>> converted = new HashMap<>();
    /** Anmeldung bei den Auftragsmeldungen des Core; je Laufzeit eine. */
    private Subscription subscription;
    private AppRuntime subscribed;
    /** Anmeldung bei den Bestandsmeldungen des Core für Quelle und Space der Auswahl (die Zeile „Bestand“). */
    private Subscription holdingsSubscription;
    private String holdingsKey;
    /** Eine Auffrischung ist schon angestoßen; weitere Meldungen bis dahin verfallen. */
    private boolean refreshPending;
    /** Der Bestand der gezeigten Auswahl; {@code null} ohne Auswahl oder für eine Quelle. */
    private PageSelection shownHoldings;
    /** Ob die Quelle der gezeigten Auswahl REST-Abfragen beantwortet (Confluence): Exporte aus der Quelle. */
    private boolean shownRestQueries;
    /**
     * Aufträge „Datei erzeugen“, die diese Ansicht gestartet hat und deren Ende sie meldet, je Auftrag die Auswahl; sie
     * gehören zum Auftrag, nicht zur gezeigten Auswahl: ein Auswahlwechsel verbirgt nur die Rückmeldung, die Datei
     * entsteht trotzdem (Angelo 16.11.2026: Exporte sind eigene Aufträge mit Status unter Aufträge).
     */
    private final Map<String, Selection> watched = new LinkedHashMap<>();
    /** Eine Auswahl, deren Aufträge gerade erst angefordert werden (IDs noch unbekannt), mit dem Format. */
    private Selection starting;
    private String startingFormat;
    /** Je Auswahl der jüngste Daten-Auftrag eines Format-Knopfs: die Karte zeigt seinen Datenbestand. */
    private final Map<Selection, String> lastOutput = new HashMap<>();

    /** Ein laufender Export aus der Quelle, mit seiner Art. */
    record Pending(Selection selection, String format) {
    }
    /**
     * Laufende Exporte aus der Quelle je Auftrag, mit Auswahl und Art; sie laufen unter Aufträge weiter, auch wenn
     * die Auswahl wechselt. Nach dem Ende nennt die Rückmeldung die Datei, sofern die Karte noch diese Auswahl zeigt.
     */
    private final Map<String, Pending> sourceJobs = new LinkedHashMap<>();
    /** Ein Export aus der Quelle, dessen Auftrag gerade erst angefordert wird. */
    private Pending sourceStarting;
    /** Zählt Abfragen; nur das Ergebnis der jüngsten wird gezeigt. */
    private int request;

    public ExportPanel(AppContext context) {
        super(new BorderLayout(0, 8));
        this.context = context;
        setOpaque(false);
        output.setFont(Comic.regular(13f));
        output.setForeground(Comic.INK);
        load.addActionListener(e -> load(true));
        loadPage.addActionListener(e -> load(false));
        collect.addActionListener(e -> collect());
        showJob.addActionListener(e -> {
            JobStatus job = shown.running() != null ? shown.running() : shown.newest();
            if (job != null) {
                context.navigator().showJob(job.id());
            }
        });
        openOutput.addActionListener(e -> {
            if (context.settings() != null) {
                SettingsView.open(context.settings().outputDirectory(), feedback);
            }
        });
        toggleNotices.addActionListener(e -> {
            noticeList.setVisible(!noticeList.isVisible());
            renderNotices();
        });

        JPanel standBody = new JPanel(new BorderLayout(0, 8));
        standBody.setOpaque(false);
        standBody.add(stand, BorderLayout.CENTER);
        standBody.add(Comic.row(load, loadPage, collect, showJob), BorderLayout.SOUTH);
        standPlate = Comic.plate("Stand", Comic.PETROL, standBody);

        JPanel formats = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        formats.setOpaque(false);
        for (String format : FORMATS) {
            IconButton button = new IconButton(FormatIcon.of(format.equals("markdown-package") ? "markdown"
                    : format.equals("json-package") ? "json" : format), EXPORT_TIPS.get(format));
            button.addActionListener(e -> output(format));
            formatButtons.put(format, button);
            formats.add(button);
        }
        formats.add(javax.swing.Box.createHorizontalStrut(10));
        for (String kind : SOURCE_EXPORTS) {
            IconButton button = new IconButton(FormatIcon.of(kind), SOURCE_TIPS.get(kind));
            button.addActionListener(e -> startSource(kind));
            sourceButtons.put(kind, button);
            formats.add(button);
        }
        JPanel outputHead = new JPanel(new BorderLayout(0, 4));
        outputHead.setOpaque(false);
        outputHead.add(formats, BorderLayout.NORTH);
        outputHead.add(cardImages, BorderLayout.CENTER);
        JPanel produced = new JPanel(new BorderLayout(0, 4));
        produced.setOpaque(false);
        JPanel list = new JPanel(new BorderLayout());
        list.setOpaque(false);
        list.add(files, BorderLayout.NORTH);
        list.add(spaces, BorderLayout.CENTER);
        produced.add(list, BorderLayout.NORTH);
        produced.add(Comic.row(toggleNotices), BorderLayout.CENTER);
        produced.add(noticeList, BorderLayout.SOUTH);
        JPanel outputBody = new JPanel(new BorderLayout(0, 6));
        outputBody.setOpaque(false);
        outputBody.add(outputHead, BorderLayout.NORTH);
        outputBody.add(produced, BorderLayout.CENTER);
        outputBody.add(Comic.row(output, openOutput), BorderLayout.SOUTH);
        outputPlate = Comic.plate("Ausgabe", Comic.GREEN, outputBody);

        // Kein eigener Titel und kein Hinweistext (Angelo): die Kopfzeile nennt die Auswahl, der Reiter heißt Export.
        add(feedback, BorderLayout.NORTH);
        add(Comic.column(standPlate, outputPlate), BorderLayout.CENTER);
        toggleNotices.setVisible(false);
        noticeList.setVisible(false);
        render(Data.NONE);
        context.onChange(ignored -> {
            subscribe();
            refresh();
        });
    }

    /** Die Auswahl links hat gewechselt (Explorer); {@code null} ohne Auswahl. */
    public void selection(Selection selection) {
        if (Objects.equals(selection, this.selection)) {
            return;
        }
        this.selection = selection;
        feedback.clear();
        noticeList.setVisible(false);
        refresh();
    }

    Selection selection() {
        return selection;
    }

    /** Für Tests: ersetzt die Rückfrage vor einem Start für „Wissensquellen“. */
    void confirmAll(java.util.function.BiPredicate<String, String> confirmer) {
        this.confirmAll = Objects.requireNonNull(confirmer, "confirmer");
    }

    /**
     * Die Auswahl für den Core: für eine Quelle und „Wissensquellen“ ausdrücklich je Space eine Wurzel aus der
     * Space-Liste der Quelle, wie der Download-Knopf in der Seitenleiste; der Core selbst kennt bei einer Quellen-Wurzel
     * nur Spaces mit Seiten im Cache, und ein Format-Knopf soll auch nie geladene Spaces erst laden (Angelo 16./17.11.).
     */
    private com.aresstack.spaceharvester.application.holdings.Selection coreSelection(AppRuntime runtime,
                                                                                       Selection current) {
        if (current.spaceKey() != null || !Objects.equals(shown.selection(), current) || shown.spaces().isEmpty()) {
            return current.toCore(runtime);
        }
        return new com.aresstack.spaceharvester.application.holdings.Selection(shown.spaces().stream().map(row ->
                com.aresstack.spaceharvester.application.holdings.Selection.Root.space(row.sourceId(),
                        row.space().key())).toList());
    }

    /** Nur „Wissensquellen“ fragt vor einem Start zurück (Architekt 17.11.2026); eine Quelle startet sofort. */
    private boolean confirmed(String question) {
        return selection == null || !selection.global() || confirmAll.test(ALL_TITLE, question);
    }

    /** Meldet sich bei den Auftragsmeldungen der aktuellen Laufzeit an (ADR 0027): Downloads ändern den Stand. */
    private void subscribe() {
        AppRuntime runtime = context.runtime();
        subscribeHoldings(runtime);
        if (runtime == subscribed) {
            return;
        }
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        subscribed = runtime;
        if (runtime == null) {
            return;
        }
        subscription = runtime.workspace().subscribeJobs(new JobListener() {
            @Override
            public void changed(JobStatus status) {
                if (affects(status)) {
                    scheduleRefresh();
                }
            }

            @Override
            public void removed(JobStatus last) {
                if (affects(last)) {
                    // Ein in den Aufträgen gelöschter Auftrag endet nie mehr: nicht weiter als laufend führen, sonst
                    // bliebe der Knopf dieser Auswahl gesperrt.
                    SwingUtilities.invokeLater(() -> {
                        watched.remove(last.id());
                        sourceJobs.remove(last.id());
                    });
                    scheduleRefresh();
                }
            }
        });
    }

    /**
     * Meldet sich für den Bestand von Quelle und Space der Auswahl an, damit die Zeile „Bestand“ auch nach Abgleich,
     * Import oder Bereinigung stimmt (ADR 0027); eine andere Auswahl oder Laufzeit meldet neu an.
     */
    private void subscribeHoldings(AppRuntime runtime) {
        Selection current = selection;
        // Eine Quelle meldet sich für alle ihre Spaces an (Space null); „Wissensquellen“ nur für die Aufträge.
        String key = runtime == null || current == null || current.global() ? null
                : current.sourceId() + "\u0000" + current.spaceKey();
        if (runtime == subscribed && Objects.equals(key, holdingsKey)) {
            return;
        }
        if (holdingsSubscription != null) {
            holdingsSubscription.close();
            holdingsSubscription = null;
        }
        holdingsKey = key;
        if (key == null) {
            return;
        }
        try {
            holdingsSubscription = runtime.workspace().subscribeHoldings(current.sourceId(), current.spaceKey(),
                    new HoldingsListener() {
                        @Override
                        public void pagesChanged(String sourceId, String spaceKey, Set<String> pageIds) {
                            scheduleRefresh();
                        }

                        @Override
                        public void spaceChanged(String sourceId, String spaceKey) {
                            scheduleRefresh();
                        }
                    });
        } catch (RuntimeException gone) {
            // Die Quelle gibt es in dieser Laufzeit nicht mehr; der Baum räumt die Auswahl gleich weg.
            holdingsSubscription = null;
        }
    }

    /**
     * Ob ein Auftrag den Stand ändern kann: Exporte und ihre Folgeaufträge (Bilder, Anhänge), deren Stand und Zähler
     * der Datensatz aus den Aufträgen ableitet.
     */
    static boolean affects(JobStatus status) {
        return status.type() == JobType.EXPORT || status.type() == JobType.CONVERSION || status.type().download()
                || sourceExport(status.type());
    }

    /** Die Aufträge der Exporte aus der Quelle. */
    static boolean sourceExport(JobType type) {
        return type == JobType.METADATA_EXPORT || type == JobType.ANALYTICS_EXPORT;
    }

    static String sourceKind(JobType type) {
        return type == JobType.ANALYTICS_EXPORT ? "analytics" : "metadata";
    }

    /** Vom Zustell-Thread des Core: einmal auf dem EDT auffrischen, auch bei mehreren Meldungen hintereinander. */
    private void scheduleRefresh() {
        synchronized (this) {
            if (refreshPending) {
                return;
            }
            refreshPending = true;
        }
        SwingUtilities.invokeLater(() -> {
            synchronized (this) {
                refreshPending = false;
            }
            refresh();
        });
    }

    /** Liest den Stand der Auswahl im Hintergrund und zeichnet ihn; nur das Ergebnis der jüngsten Abfrage zählt. */
    void refresh() {
        // Die Laufzeit kann schon vor dem Reiter da sein: beim ersten Lesen anmelden, dann liest kein Abruf zu früh.
        subscribe();
        AppRuntime runtime = context.runtime();
        Selection current = selection;
        // Jede Anfrage entwertet die vorige, auch die leere: ein noch laufender Abruf darf die Auswahl nicht zurückholen.
        int ticket = ++request;
        if (runtime == null || current == null) {
            render(Data.NONE);
            return;
        }
        Path directory = context.settings() == null ? null : context.settings().outputDirectory();
        Set<String> watchedJobs = Set.copyOf(watched.keySet());
        Set<String> watchedSource = Set.copyOf(sourceJobs.keySet());
        Map<Selection, String> preferred = Map.copyOf(lastOutput);
        Background.run(() -> gather(runtime, current, directory, watchedJobs, watchedSource, preferred), data -> {
            if (ticket == request) {
                render(data);
            }
        }, error -> {
            if (ticket == request) {
                render(Data.NONE);
                feedback.problem(error);
            }
        });
    }

    /** Der Stand der Auswahl: jüngster fertiger Download, laufender Auftrag, Datenbestand, Dateien; je Space einer Quelle. */
    private static Data gather(AppRuntime runtime, Selection selection, Path directory, Set<String> watched,
                               Set<String> watchedSource, Map<Selection, String> preferred) {
        List<JobStatus> jobs = runtime.workspace().jobs();
        List<JobStatus> finished = jobs.stream().filter(job -> watched.contains(job.id()) && job.state().terminal())
                .toList();
        List<JobStatus> finishedSource = jobs.stream().filter(job -> watchedSource.contains(job.id())
                && job.state().terminal()).toList();
        // Beendete Aufträge „Datei erzeugen“, auf die diese Ansicht wartet, samt ihrer Datei (Workspace.producedFile).
        Map<String, ExportConversions.ConvertedFile> produced = new HashMap<>();
        for (JobStatus job : finished) {
            if (job.state() == JobState.COMPLETED) {
                runtime.workspace().producedFile(job.id()).ifPresent(file -> produced.put(job.id(), file));
            }
        }
        // Eine Quelle: ihre Spaces; „Wissensquellen“: die Spaces aller Quellen, je mit Quellentitel davor.
        List<HarvestSource> sources = selection.global() ? runtime.workspace().sources()
                : runtime.workspace().sources().stream().filter(s -> s.id().equals(selection.sourceId())).toList();
        boolean restQueries = sources.stream().anyMatch(s -> runtime.workspace().sourceQueryExportsSupported(s.id()));
        // Der Bestand der Auswahl aus dem Core (ADR 0029): Seite samt Unterseiten, Quelle über alle ihre Spaces.
        PageSelection holdings = runtime.workspace().resolveSelection(selection.toCore(runtime));
        if (selection.spaceKey() == null) {
            List<SpaceRow> rows = new ArrayList<>();
            for (HarvestSource source : sources) {
                String prefix = selection.global()
                        ? (source.title() == null || source.title().isBlank() ? source.id() : source.title()) : null;
                for (Partition space : runtime.workspace().spaces(source.id())) {
                    Selection whole = new Selection(source.id(), space.key(), null, null);
                    JobStatus newest = newest(jobs, whole, preferred.get(whole));
                    ExportDatasetInfo dataset = newest == null ? null
                            : runtime.workspace().exportDataset(newest.id()).orElse(null);
                    List<JobStatus> sourceRunning = jobs.stream().filter(job -> watchedSource.contains(job.id())
                            && !job.state().terminal() && whole.covers(job)).toList();
                    rows.add(new SpaceRow(prefix, source.id(), space, running(jobs, whole), conversion(jobs, whole),
                            dataset == null ? List.of() : outputFiles(directory, dataset.exportId()), sourceRunning));
                }
            }
            return new Data(selection, null, running(jobs, selection), null, List.of(), rows, holdings,
                    conversion(jobs, selection), finished, finishedSource, produced, restQueries);
        }
        JobStatus newest = newest(jobs, selection, preferred.get(selection.normalized()));
        ExportDatasetInfo dataset = newest == null ? null
                : runtime.workspace().exportDataset(newest.id()).orElse(null);
        List<String> files = dataset == null ? List.of() : outputFiles(directory, dataset.exportId());
        return new Data(selection, newest, running(jobs, selection), dataset, files, List.of(), holdings,
                conversion(jobs, selection), finished, finishedSource, produced, restQueries);
    }

    /** Ein noch nicht beendeter Auftrag „Datei erzeugen“ dieser Auswahl (wartend, laufend, angehalten). */
    static JobStatus conversion(List<JobStatus> jobs, Selection selection) {
        return jobs.stream().filter(selection::matchesConversion).filter(job -> !job.state().terminal())
                .max(Comparator.comparing(JobStatus::createdAt)).orElse(null);
    }

    /** „120 von 127 Seiten lokal“, bei lückenhafter Auflösung mit Hinweis; ohne bekannte Seiten „keine Seite bekannt“. */
    static String holdingsText(PageSelection holdings) {
        if (holdings.size() == 0) {
            return holdings.complete() ? "keine Seite bekannt" : "keine Seite bekannt · " + INCOMPLETE;
        }
        return holdings.counts().stored() + " von " + Texts.count(holdings.size(), "Seite", "Seiten") + " lokal"
                + (holdings.complete() ? "" : " · " + INCOMPLETE);
    }

    /**
     * Die jüngste Ausgabe aus dem Bestand dieser Auswahl ({@code preferred}) hat Vorrang: der Core kann einen älteren,
     * unveränderten Auftrag wiederverwenden, dessen Datenbestand dann die Datei liefert, nicht der jüngste Download.
     */
    static JobStatus newest(List<JobStatus> jobs, Selection selection, String preferred) {
        return jobs.stream().filter(job -> job.id().equals(preferred) && selection.matches(job)
                && job.state() == JobState.COMPLETED).findFirst().orElseGet(() -> newest(jobs, selection));
    }

    /** Der jüngste erfolgreich beendete Download genau dieser Auswahl. */
    static JobStatus newest(List<JobStatus> jobs, Selection selection) {
        return jobs.stream().filter(selection::matches).filter(job -> job.state() == JobState.COMPLETED)
                .max(Comparator.comparing((JobStatus job) -> job.finishedAt() == null ? Instant.EPOCH
                        : job.finishedAt()).thenComparing(JobStatus::createdAt)).orElse(null);
    }

    /** Ein noch nicht beendeter Download dieser Auswahl (wartend, laufend, angehalten). */
    static JobStatus running(List<JobStatus> jobs, Selection selection) {
        return jobs.stream().filter(selection::matches).filter(job -> !job.state().terminal())
                .max(Comparator.comparing(JobStatus::createdAt)).orElse(null);
    }

    /** Alles, was {@link #render} zeigt; {@link #NONE} ohne Auswahl oder Laufzeit. */
    record Data(Selection selection, JobStatus newest, JobStatus running, ExportDatasetInfo dataset,
                List<String> files, List<SpaceRow> spaces, PageSelection holdings, JobStatus conversion,
                List<JobStatus> finished, List<JobStatus> finishedSource,
                Map<String, ExportConversions.ConvertedFile> produced, boolean restQueries) {
        static final Data NONE = new Data(null, null, null, null, List.of(), List.of(), null, null, List.of(),
                List.of(), Map.of(), false);
    }

    /**
     * Ein Space in der Dateiliste einer Quelle: sein laufender Download, sein offener Auftrag „Datei erzeugen“, die
     * Dateien seiner jüngsten Ausgabe und seine laufenden Exporte aus der Quelle; {@code source} nur bei
     * „Wissensquellen“ (Quellentitel davor).
     */
    record SpaceRow(String source, String sourceId, Partition space, JobStatus running, JobStatus conversion,
                    List<String> files, List<JobStatus> sourceRunning) {

        String label() {
            String name = space.name() == null || space.name().isBlank() ? space.key() : space.name();
            return source == null ? name : source + " › " + name;
        }

        /** „Download läuft · 3 von 9 Seiten · PDF: wird erzeugt … · Analytics läuft … · ab12.pdf“ oder „noch nichts ausgegeben“. */
        String text() {
            List<String> parts = new ArrayList<>();
            if (running != null) {
                parts.add("Download " + Texts.state(running).toLowerCase(Locale.GERMAN) + " · " + progress(running));
            }
            String open = conversionText(conversion);
            if (open != null) {
                parts.add(open);
            }
            for (JobStatus job : sourceRunning) {
                parts.add(sourceTitle(sourceKind(job.type())) + " " + Texts.state(job).toLowerCase(Locale.GERMAN));
            }
            parts.addAll(files);
            return parts.isEmpty() ? NOTHING_YET : String.join(" · ", parts);
        }

        boolean idle() {
            return running == null && conversion == null && sourceRunning.isEmpty() && files.isEmpty();
        }
    }

    private void render(Data data) {
        shown = data;
        shownHoldings = data.holdings();
        shownRestQueries = data.restQueries();
        Selection current = data.selection();
        boolean none = current == null;
        standPlate.setVisible(!none);
        outputPlate.setVisible(!none);
        if (none) {
            if (context.runtime() == null) {
                feedback.working("Startet …");
            }
            shownDataset = null;
            revalidate();
            repaint();
            return;
        }
        renderStand(data);
        renderOutput(data);
        revalidate();
        repaint();
        data.finished().forEach(job -> finishOutput(job, data.produced().get(job.id())));
        data.finishedSource().forEach(this::finishSource);
    }

    /**
     * Die Dateiliste einer Quelle: je Space eine Zeile mit laufenden Aufträgen und fertigen Dateien, die jüngste
     * Datei öffnet der Knopf in der Zeile (Architekt 17.11.2026); ohne Spaces nur „keine Spaces bekannt“.
     */
    private void renderSpaces(List<SpaceRow> rows, Path directory) {
        spaces.reset();
        if (rows.isEmpty()) {
            spaces.add("Spaces", NO_SPACES, Comic.MUTED);
            return;
        }
        for (SpaceRow row : rows) {
            ComposerButton open = null;
            if (!row.files().isEmpty() && directory != null) {
                Path file = directory.resolve(row.files().get(0));
                open = Comic.button("Öffnen", file.toString());
                open.addActionListener(e -> SettingsView.open(file, feedback));
            }
            spaces.add(row.label(), row.text(), row.idle() ? Comic.MUTED : Comic.INK, open,
                    row.files().isEmpty() ? null : String.join("\n", row.files()));
        }
    }

    /**
     * Der Stand eines Space aus dem Bestand: „120 von 127 Seiten lokal · Stand vom 13.11.2026 19:05“, ohne Inventur
     * mit Hinweis, „Download läuft · 90 von 127 Seiten“ oder „Noch nicht geladen“, wenn der Cache nichts hat.
     */
    static String standText(SpaceSummary holdings, JobStatus running) {
        if (running != null) {
            return "Download " + Texts.state(running).toLowerCase(Locale.GERMAN) + " · " + progress(running);
        }
        if (holdings == null || holdings.counts().stored() == 0 && !holdings.inventoried()) {
            return NOT_LOADED;
        }
        String local = holdings.counts().stored() + " von "
                + Texts.count(holdings.counts().pages(), "Seite", "Seiten") + " lokal";
        return holdings.inventoried() ? local + " · Stand vom " + Texts.time(holdings.asOf())
                : local + " · " + INCOMPLETE;
    }

    /** Grün, wenn alles lokal und inventarisiert; grau ohne Stand; sonst orange wie die Zeile „Bestand“. */
    static java.awt.Color standTone(SpaceSummary holdings, JobStatus running) {
        if (running != null) {
            return Comic.INK;
        }
        if (holdings == null || holdings.counts().stored() == 0 && !holdings.inventoried()) {
            return Comic.MUTED;
        }
        return holdings.inventoried() && holdings.counts().stored() >= holdings.counts().pages() ? Comic.GREEN
                : Comic.ORANGE;
    }

    private static String progress(JobStatus job) {
        JobProgress p = job.progress();
        return p.discovered() == 0 ? "noch nichts gefunden" : p.processed() + " von " + p.discovered() + " Seiten";
    }

    private void renderStand(Data data) {
        Selection current = data.selection();
        JobStatus newest = data.newest();
        JobStatus running = data.running();
        ExportDatasetInfo dataset = data.dataset();
        boolean source = current.spaceKey() == null;
        stand.reset();
        if (running != null && source) {
            long open = data.spaces().stream().filter(row -> row.running() != null).count();
            stand.add("Aufträge", Texts.count((int) open, "Download", "Downloads") + " offen",
                    Comic.tone(Texts.tone(running)));
        } else if (running != null) {
            stand.add("Auftrag", Texts.state(running) + " · " + progress(running), Comic.tone(Texts.tone(running)));
        }
        PageSelection holdings = data.holdings();
        boolean stored = holdings != null && holdings.counts().stored() > 0;
        if (holdings != null) {
            boolean all = holdings.size() > 0 && holdings.counts().stored() >= holdings.size();
            stand.add("Bestand", holdingsText(holdings) + (current.page() ? " · samt Unterseiten" : ""),
                    holdings.size() == 0 ? Comic.MUTED : all && holdings.complete() ? Comic.GREEN : Comic.ORANGE);
        }
        if (newest == null) {
            // Ohne Download in der Auftragsablage sagt der Bestand den Stand; nur ohne beides „Noch nicht geladen“.
            if (!stored) {
                stand.add("Stand", NOT_LOADED, Comic.MUTED);
            }
        } else {
            stand.add("Stand vom", Texts.time(newest.finishedAt()));
            stand.add("Seiten", dataset == null ? String.valueOf(newest.progress().written())
                    : String.valueOf(dataset.pages()));
            if (dataset != null) {
                stand.add("Bilder", images(dataset.images()),
                        dataset.images() == ExportDatasetInfo.Images.INCOMPLETE ? Comic.RED : Comic.INK);
                if (dataset.media().total() > 0) {
                    stand.add("Medien", media(dataset.media()),
                            dataset.media().missing() + dataset.media().signInRequired() > 0 ? Comic.RED : Comic.INK);
                }
                stand.add("Vollständig", dataset.complete() ? "ja" : "nein",
                        dataset.complete() ? Comic.GREEN : Comic.ORANGE);
            } else {
                stand.add("Datenbestand", "nicht mehr vorhanden", Comic.ORANGE);
            }
        }
        boolean loaded = newest != null || stored;
        load.setText(current.page() ? LOAD_SUBTREE : loaded ? REFRESH : source ? LOAD_ALL : LOAD_SPACE);
        load.setToolTipText(current.page() ? "Lädt die Seite samt Unterseiten erneut; Vorhandenes nur bei Änderung"
                : source ? (loaded ? "Lädt alle Spaces erneut, je Space ein Auftrag; Vorhandenes nur bei Änderung"
                : "Lädt alle Spaces, je Space ein Auftrag")
                : loaded ? "Lädt den ganzen Space erneut; Vorhandenes nur bei Änderung" : "Lädt den ganzen Space");
        loadPage.setVisible(current.page());
        load.setEnabled(running == null);
        loadPage.setEnabled(running == null);
        collect.setEnabled(stored);
        collect.setToolTipText(stored ? COLLECT_TIP : COLLECT_TIP + " · " + NOTHING_LOCAL + ", erst laden");
        showJob.setVisible(running != null || newest != null);
    }

    private void renderOutput(Data data) {
        ExportDatasetInfo dataset = data.dataset();
        Path directory = context.settings() == null ? null : context.settings().outputDirectory();
        output.setText("Ausgabeordner: " + (directory == null ? "–" : name(directory)));
        output.setToolTipText(directory == null ? null : directory.toString());
        outputFiles = data.files();
        boolean other = dataset == null || shownDataset == null
                || !shownDataset.exportId().equals(dataset.exportId());
        shownDataset = dataset;
        if (other) {
            noticeList.setVisible(false);
        }
        renderButtons();
        boolean source = data.selection().spaceKey() == null;
        files.setVisible(!source);
        spaces.setVisible(source);
        if (source) {
            cardImages.setVisible(false);
            toggleNotices.setVisible(false);
            noticeList.setVisible(false);
            renderSpaces(data.spaces(), directory);
            return;
        }
        if (dataset == null) {
            cardImages.setVisible(false);
            String running = conversionText(data.conversion());
            files.setText(running != null ? running
                    : shownHoldings == null || shownHoldings.counts().stored() == 0 ? NO_HOLDINGS : NO_DATASET);
            files.setForeground(Comic.MUTED);
            toggleNotices.setVisible(false);
            noticeList.setVisible(false);
            return;
        }
        // Die Zahlen stehen schon in „Stand“; hier nur, was die Ausgabe gerade einschränkt.
        boolean limited = dataset.images() != ExportDatasetInfo.Images.COMPLETE
                || dataset.media().missing() + dataset.media().signInRequired() > 0;
        cardImages.setVisible(limited);
        cardImages.setText(limited ? cardImages(dataset.images(), dataset.media()) : " ");
        cardImages.setForeground(dataset.images() == ExportDatasetInfo.Images.INCOMPLETE
                || dataset.media().missing() + dataset.media().signInRequired() > 0 ? Comic.RED : Comic.INK);
        renderFiles(data.conversion());
        renderNotices();
        Consumer<Feedback> conversion = converted.get(dataset.exportId());
        if (conversion != null) {
            conversion.accept(feedback);
        }
    }

    /** „PDF: wartet auf die Daten …“, „PDF: wird erzeugt …“ oder „PDF: angehalten“; {@code null} ohne Auftrag. */
    static String conversionText(JobStatus conversion) {
        if (conversion == null) {
            return null;
        }
        String format = Format.title(conversion.parameter(ExportConversions.FORMAT));
        if (conversion.state() == JobState.QUEUED) {
            return format + ": wartet auf die Daten …";
        }
        if (conversion.state() == JobState.RUNNING) {
            return format + ": wird erzeugt …";
        }
        return format + ": " + Texts.state(conversion).toLowerCase(Locale.GERMAN);
    }

    /** Erzeugte Dateien des gezeigten Downloads und ein offener Auftrag „Datei erzeugen“ der Auswahl. */
    private void renderFiles(JobStatus conversion) {
        StringBuilder text = new StringBuilder();
        String running = conversionText(conversion);
        if (running != null) {
            text.append(running);
        }
        for (String name : outputFiles) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(name);
        }
        files.setText(text.isEmpty() ? "Noch nichts exportiert; die Format-Knöpfe erzeugen die Datei."
                : text.toString());
        files.setForeground(text.isEmpty() ? Comic.MUTED : Comic.INK);
    }

    /** Startet den Download der Auswahl sofort; der Auftrag läuft unter Aufträge, die Ansicht bleibt hier. */
    private void load(boolean withChildren) {
        Selection current = selection;
        AppRuntime runtime = context.runtime();
        if (current == null || runtime == null || current.spaceKey() == null && !confirmed(LOAD_ALL_QUESTION)) {
            return;
        }
        if (current.spaceKey() == null) {
            // Eine Quelle bzw. alle Quellen: je Space ein eigener Download, wie der Knopf in der Seitenleiste.
            List<String> sources = current.global() ? runtime.workspace().sources().stream().map(HarvestSource::id)
                    .toList() : List.of(current.sourceId());
            feedback.working("Downloads werden gestartet …");
            load.setEnabled(false);
            for (String sourceId : sources) {
                Downloads.start(context, sourceId, List.of(), result -> {
                    feedback.working("Downloads gestartet, je Space ein Auftrag");
                    context.navigator().followJobs();
                    refresh();
                }, error -> {
                    feedback.problem(error);
                    load.setEnabled(true);
                });
            }
            return;
        }
        feedback.working("Download wird gestartet …");
        load.setEnabled(false);
        loadPage.setEnabled(false);
        Consumer<Downloads.Started> started = result -> {
            feedback.working(current.page() ? "Download der Seite" + (withChildren ? " samt Unterseiten" : "")
                    + " gestartet" : "Download des Space gestartet");
            context.navigator().followJobs();
            refresh();
        };
        Consumer<Throwable> failed = error -> {
            feedback.problem(error);
            load.setEnabled(true);
            loadPage.setEnabled(true);
        };
        if (current.page()) {
            Downloads.startPage(context, current.sourceId(), current.spaceKey(), current.pageId(), withChildren,
                    started, failed);
        } else {
            Downloads.start(context, current.sourceId(), List.of(current.spaceKey()), started, failed);
        }
    }

    /**
     * Bilder und Anhänge der Auswahl beschaffen, ohne Export ({@code Workspace.collectAssets}): die Aufträge laufen
     * unter Aufträge; ein offener kommt beim zweiten Klick zurück, ein beendeter läuft im selben Eintrag erneut. Ohne
     * lokal gespeicherte Seiten meldet die Statuszeile „Nichts lokal gespeichert“.
     */
    void collect() {
        AppRuntime runtime = context.runtime();
        Selection current = selection;
        if (runtime == null || current == null || !confirmed("Wirklich Bilder und Anhänge für alle Spaces aller "
                + "Wissensquellen beschaffen? Je Space startet sofort ein eigener Auftrag.")) {
            return;
        }
        collect.setEnabled(false);
        feedback.working("Bilder und Anhänge werden eingeplant …");
        com.aresstack.spaceharvester.application.holdings.Selection roots = coreSelection(runtime, current);
        Background.run(() -> runtime.workspace().collectAssets(roots), jobs -> {
            collect.setEnabled(true);
            if (!Objects.equals(current, selection)) {
                return;
            }
            if (jobs.isEmpty()) {
                feedback.info(NOTHING_LOCAL, "Erst laden; beschafft werden nur Bilder und Anhänge lokal gespeicherter Seiten");
                return;
            }
            feedback.working(Texts.count(jobs.size(), "Auftrag", "Aufträge") + " eingeplant: "
                    + jobs.stream().map(job -> Texts.type(job.type())).distinct()
                            .collect(java.util.stream.Collectors.joining(", ")));
            context.navigator().followJobs();
        }, error -> {
            collect.setEnabled(true);
            if (Objects.equals(current, selection)) {
                feedback.problem(error);
            }
        });
    }

    /**
     * Dateien dieses Downloads im Ausgabeordner, neueste zuerst: Name {@code <Space>-<erste 8 Zeichen der
     * Export-ID>[-n].<Endung>} wie {@code ExportConversions}; halbfertige {@code .part}-Dateien zählen nicht.
     */
    static List<String> outputFiles(Path directory, String exportId) {
        if (directory == null || !java.nio.file.Files.isDirectory(directory)) {
            return List.of();
        }
        String mark = "-" + exportId.substring(0, Math.min(8, exportId.length()));
        try (java.util.stream.Stream<Path> entries = java.nio.file.Files.list(directory)) {
            return entries.filter(java.nio.file.Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().startsWith(".")
                            && file.getFileName().toString().contains(mark))
                    .sorted(Comparator.comparing((Path file) -> file.toFile().lastModified()).reversed())
                    .map(file -> file.getFileName().toString()).toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    /**
     * Warum dieses Format gerade nicht geht, oder {@code null}: ohne Auswahl eines Space oder einer Seite, oder solange
     * ein Auftrag „Datei erzeugen“ dieser Auswahl offen ist. Ohne Bestand geht es (Angelo 16.11.2026): der Auftrag lädt
     * dann erst die Seiten.
     */
    String exportBlocked(String format) {
        Selection current = selection;
        if (current == null || context.runtime() == null) {
            return NO_SELECTION;
        }
        if (starting != null && starting.equals(current)) {
            return Format.title(startingFormat) + IN_PROGRESS;
        }
        JobStatus open = shown.conversion();
        if (open != null && Objects.equals(shown.selection(), current)) {
            return Format.title(open.parameter(ExportConversions.FORMAT)) + IN_PROGRESS;
        }
        return null;
    }

    /** Die Rückfrage für „Wissensquellen“: „Wirklich für alle 7 Spaces aller Wissensquellen PDF erzeugen?“ */
    String allQuestion(String what) {
        return "Wirklich für alle " + Texts.count(shown.spaces().size(), "Space", "Spaces")
                + " aller Wissensquellen " + what + " erzeugen? Je Space startet sofort ein eigener Auftrag.";
    }

    /** Ob die Format-Knöpfe für die gezeigte Auswahl erst laden müssten: weder Datenbestand noch Bestand. */
    private boolean loadsFirst() {
        return shownDataset == null && (shownHoldings == null || shownHoldings.counts().stored() == 0);
    }

    /**
     * Ein Format-Knopf (Angelo 16.11.2026: Exporte sind eigene Aufträge): {@code Workspace.exportFiles} startet je
     * Space bzw. Teilbaum der Auswahl zwei Aufträge, erst die Daten, dann „Datei erzeugen“, das von selbst startet,
     * sobald die Daten fertig sind. Mit Bestand sind die Daten die Ausgabe aus dem Bestand (ADR 0029, wiederverwendet,
     * wenn sich nichts geändert hat), ohne Bestand lädt der erste Auftrag die Seiten erst von der Quelle. Beide laufen
     * unter Aufträge; das Ende kommt per Auftragsmeldung, die Datei samt Hinweisen liest {@link #finishOutput}.
     */
    void output(String format) {
        AppRuntime runtime = context.runtime();
        Selection current = selection;
        if (runtime == null || context.settings() == null || current == null || exportBlocked(format) != null
                || !confirmed(allQuestion(Format.title(format)))) {
            return;
        }
        Path directory = context.settings().outputDirectory();
        starting = current;
        startingFormat = format;
        feedback.working(Format.title(format) + " wird eingeplant …");
        renderButtons();
        com.aresstack.spaceharvester.application.holdings.Selection roots = coreSelection(runtime, current);
        Background.run(() -> {
            List<JobStatus> jobs = runtime.workspace().exportFiles(roots, List.of(Downloads.FORMAT), format, directory);
            if (jobs.size() < 2) {
                throw new IllegalStateException("Kein Space in der Auswahl");
            }
            return jobs;
        }, jobs -> {
            starting = null;
            // Je Space ein Paar: erst die Daten, dann „Datei erzeugen“; die Datei gehört der Auswahl, die sie bestellte.
            for (int i = 0; i + 1 < jobs.size(); i += 2) {
                lastOutput.put(Selection.ofJob(jobs.get(i)), jobs.get(i).id());
                watched.put(jobs.get(i + 1).id(), current);
            }
            JobStatus data = jobs.get(0);
            if (Objects.equals(current, selection)) {
                feedback.working(Format.title(format) + ": " + (current.spaceKey() == null
                        ? Texts.count(jobs.size() / 2, "Auftrag", "Aufträge") + " je Space eingeplant, "
                        + "die Dateien entstehen in " + name(directory)
                        : data.state() == JobState.COMPLETED ? "Datei wird erzeugt …"
                        : loadsFirst() ? "Seiten werden erst geladen, dann entsteht die Datei …"
                        : "Daten aus dem Bestand, dann entsteht die Datei …"));
                context.navigator().followJobs();
            }
            refresh();
        }, error -> {
            starting = null;
            if (Objects.equals(current, selection)) {
                feedback.problem(error);
            }
            renderButtons();
        });
    }

    /**
     * Ein Auftrag „Datei erzeugen“ dieser Ansicht ist beendet: die Datei samt Hinweisen steht in der Rückmeldung und
     * an der Karte, sofern sie noch den Download zeigt; scheiterte er, steht der Grund dort.
     */
    private void finishOutput(JobStatus job, ExportConversions.ConvertedFile file) {
        Selection owner = job == null || !job.state().terminal() ? null : watched.remove(job.id());
        if (owner == null) {
            return;
        }
        String exportId = job.parameter(ExportAssetCollection.EXPORT_ID);
        String format = Format.title(job.parameter(ExportConversions.FORMAT));
        // Für eine Quelle nennt die Rückmeldung jede fertige Datei und wie viele noch offen sind (ersetzt „Gestartet“).
        long open = watched.values().stream().filter(owner::equals).count();
        String remaining = owner.spaceKey() == null ? (open == 0 ? " · alle Dateien fertig"
                : " · noch " + Texts.count((int) open, "Datei", "Dateien") + " offen") : "";
        Consumer<Feedback> show;
        if (job.state() == JobState.COMPLETED && file != null) {
            lastFile.put(exportId, file);
            Path directory = file.path().getParent();
            String notices = file.noticeCount() == 0 ? ""
                    : ", " + Texts.count(file.noticeCount(), "Hinweis", "Hinweise");
            show = f -> f.success(file.path().getFileName() + " gespeichert in "
                    + (directory == null ? "" : name(directory)) + notices + remaining);
        } else if (job.state() == JobState.COMPLETED) {
            show = f -> f.success(format + " erzeugt; die Datei steht im Ausgabeordner" + remaining);
        } else {
            show = f -> f.warning(format + ": " + Texts.type(job.type()) + " "
                    + Texts.state(job).toLowerCase(Locale.GERMAN) + remaining,
                    job.error() == null ? null : job.error().message());
        }
        if (owner.spaceKey() == null) {
            if (Objects.equals(owner, selection)) {
                show.accept(feedback);
            }
        } else {
            showConverted(exportId, show);
        }
        renderButtons();
    }

    private void renderButtons() {
        for (Map.Entry<String, IconButton> entry : formatButtons.entrySet()) {
            String blocked = exportBlocked(entry.getKey());
            entry.getValue().setEnabled(blocked == null);
            entry.getValue().setToolTipText(EXPORT_TIPS.get(entry.getKey())
                    + (blocked != null ? " · " + blocked : loadsFirst() ? " · " + LOADS_FIRST : ""));
        }
        for (Map.Entry<String, IconButton> entry : sourceButtons.entrySet()) {
            String blocked = sourceBlocked(entry.getKey());
            entry.getValue().setEnabled(blocked == null);
            entry.getValue().setToolTipText(blocked == null ? SOURCE_TIPS.get(entry.getKey())
                    : SOURCE_TIPS.get(entry.getKey()) + " · " + blocked);
        }
    }

    /** Warum dieser Export aus der Quelle für die gezeigte Auswahl gerade nicht geht, oder {@code null}. */
    String sourceBlocked(String kind) {
        if (selection == null || context.runtime() == null) {
            return NOT_AVAILABLE;
        }
        if (!shownRestQueries) {
            return ONLY_CONFLUENCE;
        }
        if (shownHoldings == null || shownHoldings.size() == 0) {
            return NO_PAGES;
        }
        Pending running = sourcePendingFor(selection, kind);
        return running == null ? null : sourceTitle(kind) + " läuft schon";
    }

    static String sourceTitle(String kind) {
        return kind.equals("analytics") ? "Analytics" : "Confluence-Metadaten";
    }

    private Pending sourcePendingFor(Selection selection, String kind) {
        if (sourceStarting != null && sourceStarting.selection().equals(selection)
                && sourceStarting.format().equals(kind)) {
            return sourceStarting;
        }
        return sourceJobs.values().stream().filter(p -> p.selection().equals(selection) && p.format().equals(kind))
                .findFirst().orElse(null);
    }

    /**
     * Export aus der Quelle (Angelo 16.11.2026): startet je Space bzw. Teilbaum der Auswahl einen Auftrag
     * „Confluence-Metadaten“ bzw. „Analytics“ ({@code Workspace.startMetadataExport}, {@code startAnalyticsExport});
     * er läuft unter Aufträge, die Datei entsteht im Ausgabeordner, die Rückmeldung hier nennt sie nach dem Ende.
     */
    void startSource(String kind) {
        AppRuntime runtime = context.runtime();
        Selection current = selection;
        if (runtime == null || context.settings() == null || current == null || sourceBlocked(kind) != null
                || !confirmed(allQuestion(sourceTitle(kind)))) {
            return;
        }
        Path directory = context.settings().outputDirectory();
        com.aresstack.spaceharvester.application.metadata.AnalyticsQueries queries =
                context.settings().analytics().toQueries();
        sourceStarting = new Pending(current, kind);
        feedback.working(sourceTitle(kind) + " werden aus der Quelle geholt …");
        renderButtons();
        com.aresstack.spaceharvester.application.holdings.Selection roots = coreSelection(runtime, current);
        Background.run(() -> kind.equals("analytics")
                        ? runtime.workspace().startAnalyticsExport(roots, directory, queries)
                        : runtime.workspace().startMetadataExport(roots, directory), jobs -> {
            sourceStarting = null;
            for (JobStatus job : jobs) {
                sourceJobs.put(job.id(), new Pending(current, kind));
            }
            if (Objects.equals(current, selection)) {
                feedback.working(sourceTitle(kind) + ": " + Texts.count(jobs.size(), "Auftrag", "Aufträge")
                        + (jobs.size() == 1 ? " eingeplant; die Datei entsteht in "
                        : " je Space eingeplant; die Dateien entstehen in ") + name(directory));
            }
            context.navigator().followJobs();
            renderButtons();
            refresh();
        }, error -> {
            sourceStarting = null;
            if (Objects.equals(current, selection)) {
                feedback.problem(error);
            }
            renderButtons();
        });
    }

    /** Ein Export aus der Quelle ist beendet: die Rückmeldung nennt Datei oder Grund, wenn die Auswahl noch passt. */
    private void finishSource(JobStatus job) {
        Pending export = job == null || !job.state().terminal() ? null : sourceJobs.remove(job.id());
        if (export == null) {
            return;
        }
        if (Objects.equals(export.selection(), selection)) {
            Path directory = context.settings() == null ? null : context.settings().outputDirectory();
            String file = SourceQueryExports.fileName(job.type(), job.partitionKey(), job.rootPageId(), job.id());
            long open = sourceJobs.values().stream().filter(export::equals).count();
            if (job.state() == JobState.COMPLETED) {
                feedback.success(file + " gespeichert in " + (directory == null ? "Ausgabeordner" : name(directory))
                        + (job.incomplete() ? " · " + Texts.count(job.progress().failed(), "Seite", "Seiten")
                        + " ohne Antwort, Gründe in der Datei" : "")
                        + (export.selection().spaceKey() != null ? "" : open == 0 ? " · alle Dateien fertig"
                        : " · noch " + Texts.count((int) open, "Datei", "Dateien") + " offen"));
            } else {
                feedback.warning(sourceTitle(export.format()) + " " + Texts.state(job).toLowerCase(Locale.GERMAN),
                        job.error() == null ? null : job.error().message());
            }
        }
        renderButtons();
    }

    /** Der gezeigte Datenbestand, für Tests. */
    ExportDatasetInfo shownDataset() {
        return shownDataset;
    }

    /**
     * Die Datei im Format zu einem schon vorhandenen Datenbestand, als Auftrag „Datei erzeugen“
     * ({@code Workspace.startConversion}); ein offener Auftrag mit gleichem Export, Format und Ordner kommt zurück.
     */
    void exportAs(String exportId, String format) {
        AppRuntime runtime = context.runtime();
        Selection current = selection;
        if (runtime == null || context.settings() == null || current == null) {
            return;
        }
        Path directory = context.settings().outputDirectory();
        starting = current;
        startingFormat = format;
        showConverted(exportId, f -> f.working(Format.title(format) + " wird eingeplant …"));
        Background.run(() -> runtime.workspace().startConversion(exportId, format, directory), job -> {
            starting = null;
            watched.put(job.id(), current);
            showConverted(exportId, f -> f.working(Format.title(format) + " wird erzeugt …"));
            refresh();
        }, error -> {
            starting = null;
            showConverted(exportId, f -> f.problem(error));
        });
    }

    /** Merkt die Rückmeldung für ihren Download und zeigt sie nur, wenn die Karte gerade diesen Download zeigt. */
    private void showConverted(String exportId, Consumer<Feedback> show) {
        converted.put(exportId, show);
        if (shownDataset == null || !shownDataset.exportId().equals(exportId)) {
            return;
        }
        renderButtons();
        renderFiles(shown.conversion());
        noticeList.setVisible(false);
        renderNotices();
        show.accept(feedback);
    }

    /** Knopf und Liste der Hinweise zur zuletzt erzeugten Datei des gezeigten Downloads. */
    private void renderNotices() {
        ExportConversions.ConvertedFile file = shownDataset == null ? null : lastFile.get(shownDataset.exportId());
        boolean any = file != null && file.noticeCount() > 0;
        toggleNotices.setVisible(any);
        if (!any) {
            noticeList.setVisible(false);
            return;
        }
        toggleNotices.setText(noticeList.isVisible() ? "Hinweise ausblenden"
                : Texts.count(file.noticeCount(), "Hinweis", "Hinweise") + " anzeigen");
        noticeList.setText(notices(file));
    }

    /** Die Hinweise in verständlichen Worten, eine Zeile je Hinweis. */
    static String notices(ExportConversions.ConvertedFile file) {
        StringBuilder text = new StringBuilder();
        for (ConversionNotice notice : file.notices()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("Seite ").append(notice.pageId()).append(": ").append(kind(notice.kind()))
                    .append(" „").append(notice.reference()).append('“');
            String reason = reason(notice.reason());
            if (reason != null) {
                text.append(" (").append(reason).append(')');
            }
        }
        int more = file.noticeCount() - file.notices().size();
        if (more > 0) {
            text.append("\n… und ").append(Texts.count(more, "weiterer Hinweis", "weitere Hinweise"));
        }
        return text.toString();
    }

    private static String kind(ConversionNotice.Kind kind) {
        return switch (kind) {
            case MISSING_IMAGE -> "fehlendes Bild";
            case MISSING_DIAGRAM -> "fehlendes Diagramm";
            case MISSING_ATTACHMENT -> "fehlender Anhang";
            case OMITTED_CONTENT -> "ausgelassener Inhalt";
        };
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return switch (reason) {
            case "missing" -> "bei der Quelle nicht gefunden";
            case "forbidden" -> "keine Berechtigung";
            case "too-large" -> "zu groß";
            case "unavailable" -> "Quelle nicht erreichbar";
            case "not-collected" -> "Bilder noch nicht beschafft";
            case "render-failed" -> "Darstellung fehlgeschlagen";
            case "not-rendered" -> "von der Quelle nicht dargestellt";
            case "view-count-mismatch" -> "Darstellung der Quelle unvollständig";
            default -> reason;
        };
    }

    private static String name(Path directory) {
        Path name = directory.getFileName();
        return name == null ? directory.toString() : name.toString();
    }

    /** Zeile der Karte; Exportieren bleibt in jedem Stand möglich. */
    static String cardImages(ExportDatasetInfo.Images images) {
        return switch (images) {
            case NOT_COLLECTED -> "Bilder: nicht beschafft, Dokumente enthalten nur Verweise";
            case COLLECTING -> "Bilder: werden beschafft · Dokumente, die du jetzt exportierst, entstehen noch ohne "
                    + "Bilder";
            case COMPLETE -> "Bilder: vollständig";
            case INCOMPLETE -> "Bilder: unvollständig · fehlende Bilder nennt das Dokument";
        };
    }

    /** Wie {@link #cardImages(ExportDatasetInfo.Images)}, dazu die Zählung der Medien, sobald welche bekannt sind. */
    static String cardImages(ExportDatasetInfo.Images images, ExportDatasetInfo.Media media) {
        String line = cardImages(images);
        return media.total() == 0 ? line : line + "\nMedien: " + media(media);
    }

    /** Die Medien eines Downloads in Worten; nur Zähler über null erscheinen. */
    static String media(ExportDatasetInfo.Media media) {
        List<String> parts = new ArrayList<>();
        if (media.stored() > 0) {
            parts.add(media.stored() + " gesichert");
        }
        if (media.missing() > 0) {
            parts.add(media.missing() + (media.missing() == 1 ? " fehlt" : " fehlen"));
        }
        if (media.signInRequired() > 0) {
            parts.add(media.signInRequired() + " Anmeldung erforderlich");
        }
        // Bewusst Ausgelassenes getrennt nach Grund, damit es nicht wie ein Fehler wirkt (Angelo, 13.11.2026).
        if (media.redundant() > 0) {
            parts.add(media.redundant() + (media.redundant() == 1 ? " Diagramm-Vorschau" : " Diagramm-Vorschauen")
                    + " nicht verwendet (aus Quelle gezeichnet)");
        }
        if (media.excluded() > 0) {
            parts.add(media.excluded() + " nach Einstellung ausgelassen");
        }
        return parts.isEmpty() ? "keine" : String.join(" · ", parts);
    }

    private static String images(ExportDatasetInfo.Images images) {
        return switch (images) {
            case NOT_COLLECTED -> "noch nicht beschafft, Dokumente enthalten nur Verweise";
            case COLLECTING -> "werden beschafft …";
            case COMPLETE -> "vollständig";
            case INCOMPLETE -> "unvollständig, fehlende Bilder nennt das Dokument";
        };
    }

    /** Zielformat mit Anzeigenamen in der Reihenfolge aus Angelos Skizze; unbekannte folgen mit ihrem Kürzel. */
    record Format(String id, String title) {

        private static final List<String> ORDER = List.of("pdf", "docx", "markdown", "markdown-package", "json-package",
                "json", "analysis");
        private static final Map<String, String> TITLES = Map.of("pdf", "PDF-Dokument", "docx", "Word-Dokument",
                "markdown", "Markdown", "markdown-package", "Markdown mit Medien", "json-package", "JSON mit Medien",
                "json", "JSON", "analysis", "Analyse");

        static String title(String format) {
            return TITLES.getOrDefault(format, format.toUpperCase(Locale.ROOT));
        }

        static List<Format> ordered(List<String> formats) {
            return formats.stream().sorted(Comparator.comparingInt((String f) -> ORDER.contains(f) ? ORDER.indexOf(f)
                    : ORDER.size())).map(f -> new Format(f, TITLES.getOrDefault(f, f.toUpperCase(Locale.ROOT))))
                    .toList();
        }

        @Override
        public String toString() {
            return title;
        }
    }
}
