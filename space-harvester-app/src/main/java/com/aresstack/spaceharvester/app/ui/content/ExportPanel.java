package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Downloads;
import com.aresstack.spaceharvester.app.ui.Facts;
import com.aresstack.spaceharvester.app.event.Severity;
import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import com.aresstack.spaceharvester.app.ui.Feedback;
import com.aresstack.spaceharvester.app.ui.Problems;
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
 * Ein Favoriten-Ordner (ADR 0034, Sammlung) verhält sich genauso: je Favorit eine Zeile, ein Auftragspaar und eine
 * Datei (eine Seite samt Unterseiten, ein Space, eine ganze Quelle wieder je Space).
 *
 * <p>Übergang bis zur Bestandsübersicht des Core: „Stand“ ist der jüngste fertige Download genau dieser Auswahl
 * (ganzer Space bzw. dieselbe Startseite); die Ausgabe arbeitet auf seinem Datenbestand. Änderungen kommen über die
 * Auftragsmeldungen des Core (ADR 0027), nicht über einen Zeitgeber.
 */
public final class ExportPanel extends JPanel {

    /**
     * Die Auswahl links im Explorer (ADR 0029, ADR 0034 §3), versiegelt: ein Knoten des Baums ({@link Node}: Quelle,
     * Space oder Seite samt Unterseiten), die globale Auswahl über alle Wissensquellen ({@link All}, Angelo 16.11.2026:
     * die Überschrift „Wissensquellen“ ist gewählt) oder eine Sammlung ({@link Collection}: ein Favoriten-Ordner).
     * {@link #toCore} verpackt nur {@link #roots}; „global“ heißt {@link All}, nie „ohne Quelle“, damit eine Sammlung
     * nicht als „Alle Wissensquellen“ gilt.
     *
     * <p>{@link #sourceId}, {@link #spaceKey} und {@link #pageId} gibt es nur für einen {@link Node}; {@link All} und
     * {@link Collection} liefern dort {@code null}. Stellen, die eine Sammlung annehmen, gehen über {@link #roots}.
     */
    public sealed interface Selection permits Selection.Node, Selection.All, Selection.Collection {

        /** Alle Wissensquellen: die Überschrift des Explorers ist gewählt, kein Knoten im Baum. */
        Selection ALL = new All();

        /** Ein Knoten aus Quelle, Space und Seite; ohne Quelle die globale Auswahl {@link #ALL}. */
        static Selection of(String sourceId, String spaceKey, String pageId, String title) {
            return sourceId == null ? ALL : new Node(sourceId, spaceKey, pageId, title);
        }

        /** Die Überschrift; {@code null} bei einem Schlüssel ({@link #normalized}). */
        String title();

        /** Quelle eines {@link Node}, sonst {@code null}. */
        default String sourceId() {
            return null;
        }

        /** Space eines {@link Node}, sonst {@code null}. */
        default String spaceKey() {
            return null;
        }

        /** Seite eines {@link Node}, sonst {@code null}. */
        default String pageId() {
            return null;
        }

        /** Ob dies die globale Auswahl über alle Wissensquellen ist. */
        default boolean global() {
            return this instanceof All;
        }

        /** Ob dies eine Seite samt Unterseiten ist. */
        default boolean page() {
            return false;
        }

        /** Ob dies ein ganzer Space ist. */
        default boolean space() {
            return false;
        }

        /**
         * Die Wurzeln für den Core (ADR 0029): Quelle, Space oder Seite samt allen Unterseiten; die globale Auswahl
         * als eine Wurzel je Wissensquelle der Laufzeit; eine Sammlung mit ihren aufgelösten Wurzeln.
         */
        List<com.aresstack.spaceharvester.application.holdings.Selection.Root> roots(AppRuntime runtime);

        /** Dieselbe Auswahl für den Core: nur {@link #roots} verpackt. */
        default com.aresstack.spaceharvester.application.holdings.Selection toCore(AppRuntime runtime) {
            return new com.aresstack.spaceharvester.application.holdings.Selection(roots(runtime));
        }

        /**
         * Ob ein Download zu dieser Auswahl gehört: für Space und Seite genau derselbe Space und dieselbe Startseite
         * (oder keine); für eine Quelle jeder ihrer Spaces, für „Wissensquellen“ jeder Space jeder Quelle.
         */
        default boolean matches(JobStatus job) {
            return job.type() == JobType.EXPORT && covers(job);
        }

        /** Ob ein Auftrag „Datei erzeugen“ zu dieser Auswahl gehört (wie {@link #matches}). */
        default boolean matchesConversion(JobStatus job) {
            return job.type() == JobType.CONVERSION && covers(job);
        }

        /** Ob der Auftrag in dieser Auswahl liegt: Quelle und „Wissensquellen“ decken alle ihre Spaces ab. */
        boolean covers(JobStatus job);

        /** Dieselbe Auswahl ohne Überschrift, als Schlüssel; die Überschrift kann je Aufruf anders lauten. */
        Selection normalized();

        /** Der Space eines Auftrags als Auswahl (Schlüssel ohne Überschrift). */
        static Selection ofJob(JobStatus job) {
            return of(job.sourceId(), job.partitionKey(), job.rootPageId(), null);
        }

        /** Ob der Auftrag unter einer Wurzel liegt: eine Quelle deckt alle ihre Spaces ab, sonst genau dieselbe. */
        private static boolean covers(com.aresstack.spaceharvester.application.holdings.Selection.Root root,
                                      JobStatus job) {
            if (root.spaceKey() == null) {
                return root.sourceId().equals(job.sourceId());
            }
            return Objects.equals(job.sourceId(), root.sourceId())
                    && Objects.equals(job.partitionKey(), root.spaceKey())
                    && Objects.equals(job.rootPageId(), root.pageId());
        }

        /** Ein Knoten im Baum: Quelle, Space oder Seite samt Unterseiten; {@code title} für die Überschrift. */
        record Node(String sourceId, String spaceKey, String pageId, String title) implements Selection {

            public Node {
                Objects.requireNonNull(sourceId, "sourceId");
            }

            @Override
            public boolean page() {
                return pageId != null;
            }

            @Override
            public boolean space() {
                return spaceKey != null && pageId == null;
            }

            /** Die eine Wurzel dieses Knotens. */
            public com.aresstack.spaceharvester.application.holdings.Selection.Root root() {
                return new com.aresstack.spaceharvester.application.holdings.Selection.Root(sourceId, spaceKey,
                        pageId);
            }

            @Override
            public List<com.aresstack.spaceharvester.application.holdings.Selection.Root> roots(AppRuntime runtime) {
                return List.of(root());
            }

            @Override
            public boolean covers(JobStatus job) {
                return Selection.covers(root(), job);
            }

            @Override
            public Selection normalized() {
                return title == null ? this : new Node(sourceId, spaceKey, pageId, null);
            }
        }

        /** Alle Wissensquellen: eine Wurzel je Quelle der Laufzeit. */
        record All() implements Selection {

            @Override
            public String title() {
                return "Alle Wissensquellen";
            }

            @Override
            public List<com.aresstack.spaceharvester.application.holdings.Selection.Root> roots(AppRuntime runtime) {
                return runtime.workspace().sources().stream()
                        .map(source -> com.aresstack.spaceharvester.application.holdings.Selection.Root.source(
                                source.id()))
                        .toList();
            }

            @Override
            public boolean covers(JobStatus job) {
                return job.sourceId() != null;
            }

            @Override
            public Selection normalized() {
                return this;
            }
        }

        /**
         * Eine Sammlung (ADR 0034): ein Favoriten-Ordner mit den Wurzeln seiner Favoriten. Die aufgelösten Wurzeln
         * stehen in {@code equals}, damit eine Änderung am Ordner ab der nächsten Frage gilt; {@code withSubfolders}
         * ist der Zustand der Checkbox „mit Unterseiten“ (aus = direkte Favoriten, an = samt Unterordnern), nach dem
         * die App {@code roots} aufgelöst hat. Ordner-ID und Titel sind nur Beschriftung der App.
         *
         * @param folderId       der Ordner
         * @param title          Überschrift (Ordnername) oder {@code null} als Schlüssel
         * @param roots          die aufgelösten Wurzeln, je Favorit eine (Seite samt Unterseiten, Space oder Quelle)
         * @param withSubfolders ob die Unterordner rekursiv mitzählen
         */
        record Collection(String folderId, String title,
                          List<com.aresstack.spaceharvester.application.holdings.Selection.Root> roots,
                          boolean withSubfolders) implements Selection {

            public Collection {
                Objects.requireNonNull(folderId, "folderId");
                roots = List.copyOf(roots);
            }

            @Override
            public List<com.aresstack.spaceharvester.application.holdings.Selection.Root> roots(AppRuntime runtime) {
                return roots;
            }

            @Override
            public boolean covers(JobStatus job) {
                return roots.stream().anyMatch(root -> Selection.covers(root, job));
            }

            @Override
            public Selection normalized() {
                return title == null ? this : new Collection(folderId, null, roots, withSubfolders);
            }

            /**
             * Die einzige Auflösung Ordner → Wurzeln (ADR 0034 §6): je Favorit des Ordners seine Wurzel
             * ({@link com.aresstack.spaceharvester.app.ui.Favorites.Favorite#root}), ohne Doppelte, in
             * Baumreihenfolge; mit {@code withSubfolders} auch die Favoriten aller Unterordner rekursiv.
             */
            public static Collection of(com.aresstack.spaceharvester.app.ui.Favorites favorites, String folderId,
                                        String title, boolean withSubfolders) {
                List<com.aresstack.spaceharvester.application.holdings.Selection.Root> roots = favorites
                        .favoritesIn(folderId, withSubfolders).stream()
                        .map(com.aresstack.spaceharvester.app.ui.Favorites.Favorite::root)
                        .distinct()
                        .toList();
                return new Collection(folderId, title, roots, withSubfolders);
            }

            /** Derselbe Ordner neu aufgelöst, mit {@code on} als Stellung der Checkbox „mit Unterseiten“. */
            public Collection withSubfolders(com.aresstack.spaceharvester.app.ui.Favorites favorites, boolean on) {
                return of(favorites, folderId, title, on);
            }
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
    static final String ALL_TITLE = "Alle Wissensquellen?";
    static final String NO_SPACES = "keine Spaces bekannt";
    /** Ein leerer Favoriten-Ordner (ADR 0034): nichts zu exportieren. */
    static final String NO_FAVORITES = "keine Favoriten im Ordner";
    /** Text einer Zeile ohne laufenden Auftrag und ohne Datei; solche Zeilen zeigt die Fläche nicht (siehe unten). */
    static final String NOTHING_YET = "noch nichts ausgegeben";
    /*
     * Die Karte „Stand“ mit „Samt Unterseiten laden“, „Nur diese Seite laden“ und „Bilder/Anhänge beschaffen“ ist weg
     * (Angelo, 20.11.2026): Das ist Indexieren, im Kontextmenü des Baums; wie viel lokal liegt, zeigt dort das
     * Bestandszeichen.
     */
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
    /** Die letzte Meldung der Platte in der Statuszeile; nur sie endet mit {@code feedback.clear()}. */
    private String feedbackStatus;
    private final Map<String, IconButton> formatButtons = new LinkedHashMap<>();
    /** Die Knöpfe der Exporte aus der Quelle ({@link #SOURCE_EXPORTS}). */
    private final Map<String, IconButton> sourceButtons = new LinkedHashMap<>();
    /** „Bilder: …“ mit Hinweis, solange sie noch beschafft werden; bricht um, damit der Hinweis nie abgeschnitten wird. */
    final JTextArea cardImages = Comic.note("");
    /** Die Dateien dieses Downloads im Ausgabeordner und laufende Exporte, eine Zeile je Datei. */
    final JTextArea files = Comic.note("");
    /** Die Knöpfe der Ausgabe (Formate und Exporte aus der Quelle) für die untere Leiste. */
    private final JPanel formatBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 4, 0));
    /**
     * Was ein Klick auf einen Knopf der Ausgabe tut, wenn nicht diese Fläche den Auftrag startet: die Fläche ohne
     * Reiter öffnet dafür einen Export-Reiter (ADR 0045 §5); {@code null} startet hier.
     */
    private java.util.function.BiConsumer<Selection, String> launcher;
    /** Ein Knopf, der gedrückt wurde, bevor diese Fläche ihre Auswahl geladen hatte; läuft nach dem ersten Zeigen. */
    private String pendingStart;
    private boolean disposed;
    private final ComposerButton openOutput = Comic.button("Ordner öffnen", "Den Ausgabeordner anzeigen");
    /** Hinweise der letzten Konvertierung des gezeigten Downloads (fehlende Bilder, Diagramme …), aufklappbar. */
    final ComposerButton toggleNotices = Comic.button("Hinweise anzeigen", "Was in der Datei fehlt oder vereinfacht ist");
    final JTextArea noticeList = Comic.note("");
    private final JComponent outputPlate;
    /** Über der Ausgabe, nur auf der Seite ohne Reiter: „Aus dieser Seite erzeugt“ ({@link #extra}). */
    private final JPanel extraSlot = new JPanel(new BorderLayout());
    /**
     * Für eine Quelle, „Wissensquellen“ und eine Sammlung statt {@link #files}: je Space bzw. Favorit eine Zeile mit
     * ihren laufenden Aufträgen und fertigen Dateien; die jüngste Datei öffnet ein Knopf in der Zeile.
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
    /** Anmeldungen bei den Bestandsmeldungen des Core je Wurzel der Auswahl (die Zeile „Bestand“). */
    private final List<Subscription> holdingsSubscriptions = new ArrayList<>();
    private List<com.aresstack.spaceharvester.application.holdings.Selection.Root> holdingsKey;
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
    /** Die Anmeldung bei {@link AppContext#onChange}; ein geschlossener Export-Reiter meldet sich damit ab. */
    private final Subscription contextSubscription;
    private final Subscription outputSubscription;
    /** Ein Export aus der Quelle, dessen Auftrag gerade erst angefordert wird. */
    private Pending sourceStarting;
    /** Zählt Abfragen; nur das Ergebnis der jüngsten wird gezeigt. */
    private int request;

    public ExportPanel(AppContext context) {
        super(new BorderLayout(0, 8));
        this.context = context;
        setOpaque(false);
        openOutput.addActionListener(e -> {
            if (context.settings() != null) {
                SettingsView.open(context.exportDirectory(), feedback);
            }
        });
        toggleNotices.addActionListener(e -> {
            noticeList.setVisible(!noticeList.isVisible());
            renderNotices();
        });

        // Die Knöpfe der Ausgabe stehen mittig in der unteren Leiste mit der Lupe (Angelo 27.11.2026, ADR 0045 §5);
        // die Seitenmitte holt sie mit formatBar() dorthin.
        JPanel formats = formatBar;
        formats.setOpaque(false);
        for (String format : FORMATS) {
            IconButton button = new IconButton(FormatIcon.of(format.equals("markdown-package") ? "markdown"
                    : format.equals("json-package") ? "json" : format), EXPORT_TIPS.get(format));
            button.addActionListener(e -> start(format));
            formatButtons.put(format, button);
            formats.add(button);
        }
        formats.add(javax.swing.Box.createHorizontalStrut(10));
        for (String kind : SOURCE_EXPORTS) {
            IconButton button = new IconButton(FormatIcon.of(kind), SOURCE_TIPS.get(kind));
            button.addActionListener(e -> start(kind));
            sourceButtons.put(kind, button);
            formats.add(button);
        }
        JPanel outputHead = new JPanel(new BorderLayout(0, 4));
        outputHead.setOpaque(false);
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
        outputBody.add(Comic.row(openOutput), BorderLayout.SOUTH);
        outputPlate = Comic.plate("Ausgabe", Comic.GREEN, outputBody);

        // Kein eigener Titel und kein Hinweistext (Angelo): die Kopfzeile nennt die Auswahl, der Reiter heißt Export.
        // „Gestartet“ und das Ergebnis eines Exports stehen in der Statuszeile des Hauptfensters: die Laufmeldung
        // bleibt, bis das Ergebnis sie ersetzt, das Ergebnis bleibt, bis es der Benutzer mit ✕ wegklickt (Architekt
        // 17.11.2026); die Platte zeigt nur noch Warnungen und Probleme.
        feedback.statusLine((text, tone) -> {
            if (text.isEmpty()) {
                // Nur die eigene Meldung beenden; steht inzwischen die einer anderen Ansicht dort, bleibt sie.
                if (feedbackStatus != null) {
                    context.events().publish(StatusMessageEvent.clear(feedbackStatus));
                }
                feedbackStatus = null;
            } else {
                feedbackStatus = text;
                context.events().publish(new StatusMessageEvent(text, Integer.MAX_VALUE, Severity.INFO,
                        tone != Comic.BLUE));
            }
        });
        add(feedback, BorderLayout.NORTH);
        extraSlot.setOpaque(false);
        add(Comic.column(extraSlot, outputPlate), BorderLayout.CENTER);
        toggleNotices.setVisible(false);
        noticeList.setVisible(false);
        render(Data.NONE);
        contextSubscription = context.onChange(ignored -> {
            if (!disposed) {
                subscribe();
                refresh();
            }
        });
        // Wechselt das Projekt oder sein Speicherort, zeigt die Liste die Dateien des neuen Ordners.
        outputSubscription = context.onExportDirectoryChange(() -> {
            if (!disposed) {
                refresh();
            }
        });
    }

    /** Ob die Fläche die Daten ihrer Auswahl zeigt (für Tests: geladen). */
    boolean showsSelection() {
        return selection != null && Objects.equals(shown.selection(), selection);
    }

    /** Die Knöpfe der Ausgabe, für die untere Leiste der Seitenmitte. */
    public JComponent formatBar() {
        return formatBar;
    }

    /** Ein Klick auf einen Knopf der Ausgabe geht an {@code launcher} statt einen Auftrag hier zu starten. */
    void launcher(java.util.function.BiConsumer<Selection, String> launcher) {
        this.launcher = launcher;
    }

    /** Startet den Export {@code key} (ein Format oder ein Export aus der Quelle), sobald die Auswahl geladen ist. */
    void startWhenShown(String key) {
        if (key == null) {
            return;
        }
        if (Objects.equals(shown.selection(), selection) && selection != null) {
            run(key);
        } else {
            pendingStart = key;
        }
    }

    /** Ein Knopf der Ausgabe: an den {@link #launcher} oder hier starten. */
    void start(String key) {
        if (launcher != null) {
            if (selection != null) {
                launcher.accept(selection, key);
            }
            return;
        }
        run(key);
    }

    private void run(String key) {
        if (SOURCE_EXPORTS.contains(key)) {
            startSource(key);
        } else {
            output(key);
        }
    }

    /** Wie der Reiter eines Exports heißt: Format oder Export aus der Quelle. */
    static String exportTitle(String key) {
        return SOURCE_EXPORTS.contains(key) ? sourceTitle(key) : Format.title(key);
    }

    /** Meldet die Fläche ab (Reiter geschlossen); laufende Aufträge laufen weiter. */
    void dispose() {
        disposed = true;
        contextSubscription.close();
        outputSubscription.close();
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        holdingsSubscriptions.forEach(Subscription::close);
        holdingsSubscriptions.clear();
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

    /** Setzt den Abschnitt über der Ausgabe (die lokalen Artefakte der Auswahl, ADR 0047 §5a). */
    void extra(JComponent extra) {
        extraSlot.removeAll();
        if (extra != null) {
            extra.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0));
            extraSlot.add(extra, BorderLayout.CENTER);
        }
        extraSlot.revalidate();
        extraSlot.repaint();
    }

    /** Für Tests: ersetzt die Rückfrage vor einem Start für „Wissensquellen“. */
    void confirmAll(java.util.function.BiPredicate<String, String> confirmer) {
        this.confirmAll = Objects.requireNonNull(confirmer, "confirmer");
    }

    /**
     * Die Auswahl für den Core: für eine Quelle, „Wissensquellen“ und eine Sammlung ausdrücklich je Zeile der
     * Dateiliste eine Wurzel ({@link #parts}), wie der Download-Knopf in der Seitenleiste; der Core selbst kennt bei
     * einer Quellen-Wurzel nur Spaces mit Seiten im Cache, und ein Format-Knopf soll auch nie geladene Spaces erst
     * laden (Angelo 16./17.11.).
     */
    private com.aresstack.spaceharvester.application.holdings.Selection coreSelection(AppRuntime runtime,
                                                                                       Selection current) {
        if (current.spaceKey() != null || !Objects.equals(shown.selection(), current) || shown.spaces().isEmpty()) {
            return current.toCore(runtime);
        }
        return new com.aresstack.spaceharvester.application.holdings.Selection(shown.spaces().stream()
                .map(row -> row.part().root()).toList());
    }

    /** Für Tests: die Wurzeln, die ein Start für die gezeigte Auswahl dem Core gibt. */
    com.aresstack.spaceharvester.application.holdings.Selection coreSelectionForTest() {
        return coreSelection(context.runtime(), selection);
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
     * Meldet sich für den Bestand jeder Wurzel der Auswahl an ({@link SelectionHoldings}), damit die Zeile „Bestand“
     * auch nach Abgleich, Import oder Bereinigung stimmt (ADR 0027); eine Quelle hört auf alle ihre Spaces, eine
     * Sammlung auf die ihrer Favoriten, „Wissensquellen“ nur auf die Aufträge. Eine andere Auswahl oder Laufzeit meldet
     * neu an.
     */
    private void subscribeHoldings(AppRuntime runtime) {
        Selection current = selection;
        List<com.aresstack.spaceharvester.application.holdings.Selection.Root> key = runtime == null
                || current == null || current.global() ? null : SelectionHoldings.areas(runtime, current);
        if (runtime == subscribed && Objects.equals(key, holdingsKey)) {
            return;
        }
        holdingsSubscriptions.forEach(Subscription::close);
        holdingsSubscriptions.clear();
        holdingsKey = key;
        if (key == null) {
            return;
        }
        holdingsSubscriptions.addAll(SelectionHoldings.watch(runtime, key, new HoldingsListener() {
            @Override
            public void pagesChanged(String sourceId, String spaceKey, Set<String> pageIds) {
                scheduleRefresh();
            }

            @Override
            public void spaceChanged(String sourceId, String spaceKey) {
                scheduleRefresh();
            }
        }));
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
        if (runtime == null) {
            render(Data.NONE);
            return;
        }
        Set<String> watchedJobs = Set.copyOf(watched.keySet());
        Set<String> watchedSource = Set.copyOf(sourceJobs.keySet());
        if (current == null) {
            render(Data.NONE);
            // Ohne Auswahl enden hier gestartete Exporte trotzdem in der Statuszeile (Ergebnis ersetzt „gestartet“).
            if (!watchedJobs.isEmpty() || !watchedSource.isEmpty()) {
                Background.run(() -> finished(runtime, watchedJobs, watchedSource), data -> {
                    if (ticket == request) {
                        finish(data);
                    }
                }, error -> { });
            }
            return;
        }
        Path directory = context.exportDirectory();
        Map<Selection, String> preferred = Map.copyOf(lastOutput);
        Map<com.aresstack.spaceharvester.application.holdings.Selection.Root, String> names = favoriteNames();
        Background.run(() -> gather(runtime, current, directory, watchedJobs, watchedSource, preferred, names),
                data -> {
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

    /**
     * Die Namen der Favoriten je Wurzel für die Zeilen einer Sammlung (ADR 0034): der Titel des Favoriten; auf dem EDT
     * gelesen, weil der Ordnerbaum dort lebt.
     */
    private Map<com.aresstack.spaceharvester.application.holdings.Selection.Root, String> favoriteNames() {
        if (!(selection instanceof Selection.Collection) || context.favorites() == null) {
            return Map.of();
        }
        Map<com.aresstack.spaceharvester.application.holdings.Selection.Root, String> names = new HashMap<>();
        for (com.aresstack.spaceharvester.app.ui.Favorites.Favorite favorite : context.favorites().all()) {
            names.putIfAbsent(favorite.root(), favorite.title());
        }
        return names;
    }

    /**
     * Der Stand der Auswahl: jüngster fertiger Download, laufender Auftrag, Datenbestand, Dateien; für eine Quelle,
     * „Wissensquellen“ und eine Sammlung je Zeile der Dateiliste ({@link #parts}).
     */
    private static Data gather(AppRuntime runtime, Selection selection, Path directory, Set<String> watched,
                               Set<String> watchedSource, Map<Selection, String> preferred,
                               Map<com.aresstack.spaceharvester.application.holdings.Selection.Root, String> names) {
        List<JobStatus> jobs = runtime.workspace().jobs();
        Data ended = finished(jobs, runtime, watched, watchedSource);
        List<JobStatus> finished = ended.finished();
        List<JobStatus> finishedSource = ended.finishedSource();
        Map<String, ExportConversions.ConvertedFile> produced = ended.produced();
        boolean restQueries = sourceIds(runtime, selection).stream()
                .anyMatch(id -> runtime.workspace().sourceQueryExportsSupported(id));
        // Der Bestand der Auswahl aus dem Core (ADR 0029): Seite samt Unterseiten, Quelle über alle ihre Spaces; eine
        // Sammlung nachsichtig, ein veralteter Favorit bricht nicht den ganzen Ordner (ADR 0034 §4).
        PageSelection holdings = SelectionHoldings.resolve(runtime, selection);
        if (selection.spaceKey() == null) {
            List<SpaceRow> rows = new ArrayList<>();
            for (Part part : parts(runtime, selection, names)) {
                Selection.Node whole = part.node();
                JobStatus newest = newest(jobs, whole, preferred.get(whole));
                ExportDatasetInfo dataset = newest == null ? null
                        : runtime.workspace().exportDataset(newest.id()).orElse(null);
                List<JobStatus> sourceRunning = jobs.stream().filter(job -> watchedSource.contains(job.id())
                        && !job.state().terminal() && whole.covers(job)).toList();
                rows.add(new SpaceRow(part.label(), whole, running(jobs, whole), conversion(jobs, whole),
                        dataset == null ? List.of() : outputFiles(directory, dataset.exportId()), sourceRunning));
            }
            // Spaces, von denen der Bestand nichts hat: die Zeile „Bestand“ darf dann nicht grün sein.
            Set<String> loaded = new java.util.HashSet<>();
            for (SpaceSummary summary : holdings.spaces()) {
                if (summary.counts().stored() > 0 || summary.inventoried()) {
                    loaded.add(summary.sourceId() + "\u0000" + summary.spaceKey());
                }
            }
            int notLoaded = (int) rows.stream().filter(row -> !loaded.contains(row.part().sourceId() + "\u0000"
                    + row.part().spaceKey())).count();
            return new Data(selection, null, running(jobs, selection), null, List.of(), rows, holdings,
                    conversion(jobs, selection), finished, finishedSource, produced, restQueries, notLoaded,
                    directory);
        }
        JobStatus newest = newest(jobs, selection, preferred.get(selection.normalized()));
        ExportDatasetInfo dataset = newest == null ? null
                : runtime.workspace().exportDataset(newest.id()).orElse(null);
        List<String> files = dataset == null ? List.of() : outputFiles(directory, dataset.exportId());
        return new Data(selection, newest, running(jobs, selection), dataset, files, List.of(), holdings,
                conversion(jobs, selection), finished, finishedSource, produced, restQueries, 0, directory);
    }

    /** Die Quellen der Auswahl: alle bei „Wissensquellen“, die eine eines Knotens, die der Favoriten einer Sammlung. */
    private static List<String> sourceIds(AppRuntime runtime, Selection selection) {
        return switch (selection) {
            case Selection.All ignored -> runtime.workspace().sources().stream().map(HarvestSource::id).toList();
            case Selection.Node node -> List.of(node.sourceId());
            case Selection.Collection collection -> collection.roots(runtime).stream()
                    .map(com.aresstack.spaceharvester.application.holdings.Selection.Root::sourceId).distinct()
                    .toList();
        };
    }

    /**
     * Eine Zeile der Dateiliste mit eigenen Aufträgen und Dateien: ein Space oder bei einer Sammlung auch eine Seite
     * samt Unterseiten.
     */
    record Part(String label, Selection.Node node) {
    }

    /**
     * Die Zeilen der Dateiliste, in die ein Format-Knopf die Auswahl teilt, je Zeile ein Auftragspaar und eine Datei
     * (Angelo 17.11.2026): für eine Quelle je Space, für „Wissensquellen“ je Space jeder Quelle mit Quellentitel davor,
     * für eine Sammlung je Favorit (ADR 0034 §1: eine Seite samt Unterseiten, ein Space, eine Quelle wieder je Space)
     * mit dem Namen des Favoriten. Ein Favorit, dessen Quelle es nicht mehr gibt oder nicht sichtbar ist, fällt weg.
     */
    static List<Part> parts(AppRuntime runtime, Selection selection,
                            Map<com.aresstack.spaceharvester.application.holdings.Selection.Root, String> names) {
        List<Part> parts = new ArrayList<>();
        switch (selection) {
            case Selection.All ignored -> {
                for (HarvestSource source : runtime.workspace().sources()) {
                    spaces(runtime, source.id(), source.title() == null || source.title().isBlank() ? source.id()
                            : source.title(), parts);
                }
            }
            case Selection.Node node -> {
                if (node.spaceKey() == null) {
                    spaces(runtime, node.sourceId(), null, parts);
                }
            }
            case Selection.Collection collection -> {
                for (var root : collection.roots(runtime)) {
                    String name = names.get(root);
                    if (root.spaceKey() == null) {
                        spaces(runtime, root.sourceId(), name == null ? root.sourceId() : name, parts);
                    } else {
                        parts.add(new Part(name != null ? name : root.pageId() == null ? root.spaceKey()
                                : "Seite " + root.pageId(), new Selection.Node(root.sourceId(), root.spaceKey(),
                                root.pageId(), null)));
                    }
                }
            }
        }
        // Ein Space doppelt (Favorit der ganzen Quelle und desselben Space) gibt nur eine Zeile und einen Auftrag.
        Map<Selection.Node, Part> unique = new LinkedHashMap<>();
        parts.forEach(part -> unique.putIfAbsent(part.node(), part));
        return List.copyOf(unique.values());
    }

    /** Je Space der Quelle eine Zeile, mit {@code prefix} davor; eine nicht mehr vorhandene Quelle liefert keine. */
    private static void spaces(AppRuntime runtime, String sourceId, String prefix, List<Part> parts) {
        List<Partition> spaces;
        try {
            spaces = runtime.workspace().spaces(sourceId);
        } catch (RuntimeException gone) {
            return;
        }
        for (Partition space : spaces) {
            String name = space.name() == null || space.name().isBlank() ? space.key() : space.name();
            parts.add(new Part(prefix == null ? name : prefix + " › " + name,
                    new Selection.Node(sourceId, space.key(), null, null)));
        }
    }

    /** Ein noch nicht beendeter Auftrag „Datei erzeugen“ dieser Auswahl (wartend, laufend, angehalten). */
    static JobStatus conversion(List<JobStatus> jobs, Selection selection) {
        return jobs.stream().filter(selection::matchesConversion).filter(job -> !job.state().terminal())
                .max(Comparator.comparing(JobStatus::createdAt)).orElse(null);
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

    /** Nur die beendeten Aufträge, auf die diese Ansicht wartet (Ergebnis in die Statuszeile), ohne Stand der Auswahl. */
    private static Data finished(AppRuntime runtime, Set<String> watched, Set<String> watchedSource) {
        return finished(runtime.workspace().jobs(), runtime, watched, watchedSource);
    }

    private static Data finished(List<JobStatus> jobs, AppRuntime runtime, Set<String> watched,
                                 Set<String> watchedSource) {
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
        return new Data(null, null, null, null, List.of(), List.of(), null, null, finished, finishedSource, produced,
                false, 0, null);
    }

    /** Meldet die beendeten Aufträge aus {@code data}: Datei oder Grund in die Statuszeile, Knöpfe neu. */
    private void finish(Data data) {
        data.finished().forEach(job -> finishOutput(job, data.produced().get(job.id())));
        data.finishedSource().forEach(this::finishSource);
        if (pendingStart != null && Objects.equals(data.selection(), selection)) {
            String key = pendingStart;
            pendingStart = null;
            run(key);
        }
    }

    /** Alles, was {@link #render} zeigt; {@link #NONE} ohne Auswahl oder Laufzeit. */
    record Data(Selection selection, JobStatus newest, JobStatus running, ExportDatasetInfo dataset,
                List<String> files, List<SpaceRow> spaces, PageSelection holdings, JobStatus conversion,
                List<JobStatus> finished, List<JobStatus> finishedSource,
                Map<String, ExportConversions.ConvertedFile> produced, boolean restQueries, int notLoaded,
                Path directory) {
        static final Data NONE = new Data(null, null, null, null, List.of(), List.of(), null, null, List.of(),
                List.of(), Map.of(), false, 0, null);
    }

    /**
     * Eine Zeile der Dateiliste ({@link Part}): ihr laufender Download, ihr offener Auftrag „Datei erzeugen“, die
     * Dateien ihrer jüngsten Ausgabe und ihre laufenden Exporte aus der Quelle.
     */
    record SpaceRow(String label, Selection.Node part, JobStatus running, JobStatus conversion,
                    List<String> files, List<JobStatus> sourceRunning) {

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
        renderOutput(data);
        revalidate();
        repaint();
        finish(data);
    }

    /**
     * Die Dateiliste einer Quelle oder Sammlung: je Zeile ({@link #parts}) laufende Aufträge und fertige Dateien, die
     * jüngste Datei öffnet der Knopf in der Zeile (Architekt 17.11.2026); ohne Zeilen nur „keine Spaces bekannt“ bzw.
     * bei einem leeren Ordner „keine Favoriten im Ordner“.
     */
    private void renderSpaces(Selection owner, List<SpaceRow> rows, Path directory) {
        spaces.reset();
        if (rows.isEmpty()) {
            if (owner instanceof Selection.Collection) {
                spaces.add("Favoriten", NO_FAVORITES, Comic.MUTED);
            } else {
                spaces.add("Spaces", NO_SPACES, Comic.MUTED);
            }
            return;
        }
        // Ob schon ausgegeben wurde, merkt sich die App nur intern (Angelo 27.11.2026): Zeilen ohne laufenden Auftrag
        // und ohne Datei bleiben weg.
        boolean any = false;
        for (SpaceRow row : rows) {
            if (row.idle()) {
                continue;
            }
            any = true;
            ComposerButton open = null;
            if (!row.files().isEmpty() && directory != null) {
                Path file = directory.resolve(row.files().get(0));
                open = Comic.button("Öffnen", file.toString());
                open.addActionListener(e -> SettingsView.open(file, feedback));
            }
            spaces.add(row.label(), row.text(), Comic.INK, open,
                    row.files().isEmpty() ? null : String.join("\n", row.files()));
        }
        spaces.setVisible(any);
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

    private void renderOutput(Data data) {
        ExportDatasetInfo dataset = data.dataset();
        // Der Ordner, aus dem die Dateien gelesen wurden; ein Projektwechsel dazwischen löst sie nicht falsch auf.
        Path directory = data.directory() != null ? data.directory() : context.exportDirectory();
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
            renderSpaces(data.selection(), data.spaces(), directory);
            return;
        }
        if (dataset == null) {
            cardImages.setVisible(false);
            // Ob schon ausgegeben wurde, merkt sich die App nur intern (Angelo 27.11.2026); gezeigt wird nur ein
            // laufender Auftrag.
            String running = conversionText(data.conversion());
            files.setVisible(running != null);
            files.setText(running == null ? "" : running);
            files.setForeground(Comic.MUTED);
            toggleNotices.setVisible(false);
            noticeList.setVisible(false);
            return;
        }
        // Nur, was die Ausgabe gerade einschränkt.
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
        if (current instanceof Selection.Collection collection && collection.roots().isEmpty()) {
            return NO_FAVORITES;
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

    /**
     * Die Rückfrage für „Wissensquellen“ (Angelo 17.11.2026): sie erklärt, dass für jede Wissensquelle und jeden
     * ihrer Spaces ein eigener Auftrag startet und eine eigene Datei entsteht.
     */
    String allQuestion(String what) {
        return "Wirklich " + what + " für alle Wissensquellen erzeugen? Für jede Wissensquelle und jeden ihrer "
                + Texts.count(shown.spaces().size(), "Space", "Spaces") + " startet sofort ein eigener Auftrag, und je "
                + "Space entsteht eine eigene Datei im Ausgabeordner.";
    }

    /**
     * Ob die Format-Knöpfe für die gezeigte Auswahl erst indexieren: ohne Datenbestand, solange nicht jede lesbare Seite
     * aktuell lokal liegt ({@code Workspace.exportFiles}).
     */
    private boolean loadsFirst() {
        return shownDataset == null && (shownHoldings == null || shownHoldings.counts().local()
                < shownHoldings.counts().pages() - shownHoldings.counts().locked());
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
        Path directory = context.exportDirectory();
        starting = current;
        startingFormat = format;
        exportStatus(Format.title(format) + " wird eingeplant …", Severity.INFO, false);
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
            exportStatus(Format.title(format) + ": " + (current.spaceKey() == null
                    ? Texts.count(jobs.size() / 2, "Auftrag", "Aufträge") + " " + perPart(current) + " eingeplant, "
                    + "die Dateien entstehen in " + name(directory)
                    : data.state() == JobState.COMPLETED ? "Datei wird erzeugt …"
                    : loadsFirst() ? "Seiten werden erst geladen, dann entsteht die Datei …"
                    : "Daten aus dem Bestand, dann entsteht die Datei …"), Severity.INFO, false);
            if (Objects.equals(current, selection)) {
                context.navigator().followJobs();
            }
            refresh();
        }, error -> {
            starting = null;
            failedStart(Format.title(format), error, current);
            renderButtons();
        });
    }

    /** Wie eine Auswahl in Aufträge zerfällt: je Space, bei einer Sammlung je Favorit. */
    static String perPart(Selection selection) {
        return selection instanceof Selection.Collection ? "je Favorit" : "je Space";
    }

    /**
     * Meldungen gestarteter Exporte gehören dem Hauptfenster, nicht der Auswahl (Architekt 17.11.2026): sie stehen in
     * der Statuszeile, ein Auswahlwechsel beendet sie nicht, das Ergebnis ersetzt „gestartet“ auch bei anderer Auswahl.
     *
     * @param closable das Ergebnis bleibt, bis es der Benutzer mit ✕ wegklickt; die Laufmeldung ersetzt das Ergebnis
     */
    private void exportStatus(String text, Severity severity, boolean closable) {
        context.events().publish(new StatusMessageEvent(text, Integer.MAX_VALUE, severity, closable));
    }

    /** Ein Export ließ sich nicht starten: Orange mit ✕ in der Statuszeile; der Grund auf der Platte, passt die Auswahl. */
    private void failedStart(String what, Throwable error, Selection owner) {
        exportStatus(what + " nicht gestartet: " + Problems.describe(error).title(), Severity.WARN, true);
        if (Objects.equals(owner, selection)) {
            feedback.problem(error);
        }
    }

    /** Ein Export scheiterte: Orange mit ✕ in der Statuszeile, der Grund bleibt unter Aufträge (und auf der Platte). */
    private String failedText(String what, JobStatus job) {
        return what + ": " + Texts.type(job) + " " + Texts.state(job).toLowerCase(Locale.GERMAN)
                + " · Grund unter Aufträge";
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
        // Das Ergebnis ersetzt „gestartet“ in der Statuszeile, auch wenn inzwischen etwas anderes gewählt ist.
        Consumer<Feedback> show = f -> { };
        if (job.state() == JobState.COMPLETED && file != null) {
            lastFile.put(exportId, file);
            Path directory = file.path().getParent();
            String notices = file.noticeCount() == 0 ? ""
                    : ", " + Texts.count(file.noticeCount(), "Hinweis", "Hinweise");
            exportStatus(file.path().getFileName() + " gespeichert in " + (directory == null ? "" : name(directory))
                    + notices + remaining, Severity.INFO, true);
        } else if (job.state() == JobState.COMPLETED) {
            exportStatus(format + " erzeugt; die Datei steht im Ausgabeordner" + remaining, Severity.INFO, true);
        } else {
            exportStatus(failedText(format, job) + remaining, Severity.WARN, true);
            show = f -> f.warning(format + ": " + Texts.type(job) + " "
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
        return running == null && !sourceRunning(context.runtime().workspace().jobs(), selection, kind) ? null
                : sourceTitle(kind) + " läuft schon";
    }

    /**
     * Ob für die Auswahl schon ein Export dieser Art aus der Quelle läuft, auch aus einem anderen Export-Reiter
     * gestartet: jeder Reiter hat seine eigene Fläche, die Aufträge stehen aber im gemeinsamen Arbeitsbereich.
     */
    static boolean sourceRunning(List<JobStatus> jobs, Selection selection, String kind) {
        return jobs.stream().anyMatch(job -> sourceExport(job.type()) && sourceKind(job.type()).equals(kind)
                && !job.state().terminal() && selection.covers(job));
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
        Path directory = context.exportDirectory();
        com.aresstack.spaceharvester.application.metadata.AnalyticsQueries queries =
                context.settings().analytics().toQueries();
        sourceStarting = new Pending(current, kind);
        exportStatus(sourceTitle(kind) + " werden aus der Quelle geholt …", Severity.INFO, false);
        renderButtons();
        com.aresstack.spaceharvester.application.holdings.Selection roots = coreSelection(runtime, current);
        Background.run(() -> kind.equals("analytics")
                        ? runtime.workspace().startAnalyticsExport(roots, directory, queries)
                        : runtime.workspace().startMetadataExport(roots, directory), jobs -> {
            sourceStarting = null;
            for (JobStatus job : jobs) {
                sourceJobs.put(job.id(), new Pending(current, kind));
            }
            exportStatus(sourceTitle(kind) + ": " + Texts.count(jobs.size(), "Auftrag", "Aufträge")
                    + (jobs.size() == 1 ? " eingeplant; die Datei entsteht in "
                    : " " + perPart(current) + " eingeplant; die Dateien entstehen in ") + name(directory),
                    Severity.INFO, false);
            context.navigator().followJobs();
            renderButtons();
            refresh();
        }, error -> {
            sourceStarting = null;
            failedStart(sourceTitle(kind), error, current);
            renderButtons();
        });
    }

    /** Ein Export aus der Quelle ist beendet: die Rückmeldung nennt Datei oder Grund, wenn die Auswahl noch passt. */
    private void finishSource(JobStatus job) {
        Pending export = job == null || !job.state().terminal() ? null : sourceJobs.remove(job.id());
        if (export == null) {
            return;
        }
        // Der Ordner, in den der Auftrag geschrieben hat, nicht der des inzwischen vielleicht anderen Projekts.
        String written = job.parameters().get(SourceQueryExports.DIRECTORY);
        Path directory = written == null || written.isBlank() ? context.exportDirectory() : Path.of(written);
        String file = SourceQueryExports.fileName(job.type(), job.partitionKey(), job.rootPageId(), job.id());
        long open = sourceJobs.values().stream().filter(export::equals).count();
        if (job.state() == JobState.COMPLETED) {
            exportStatus(file + " gespeichert in " + (directory == null ? "Ausgabeordner" : name(directory))
                    + (job.incomplete() ? " · " + Texts.count(job.progress().failed(), "Seite", "Seiten")
                    + " ohne Antwort, Gründe in der Datei" : "")
                    + (export.selection().spaceKey() != null ? "" : open == 0 ? " · alle Dateien fertig"
                    : " · noch " + Texts.count((int) open, "Datei", "Dateien") + " offen"), Severity.INFO, true);
        } else {
            exportStatus(failedText(sourceTitle(export.format()), job), Severity.WARN, true);
            if (Objects.equals(export.selection(), selection)) {
                feedback.warning(sourceTitle(export.format()) + " " + Texts.state(job).toLowerCase(Locale.GERMAN),
                        job.error() == null ? null : job.error().message());
            }
        }
        renderButtons();
    }

    /** Für Tests: ob die Meldungsplatte gerade sichtbar ist (nur bei Warnungen und Problemen). */
    boolean feedbackVisibleForTest() {
        return feedback.isVisible();
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
        Path directory = context.exportDirectory();
        starting = current;
        startingFormat = format;
        exportStatus(Format.title(format) + " wird eingeplant …", Severity.INFO, false);
        showConverted(exportId, f -> { });
        Background.run(() -> runtime.workspace().startConversion(exportId, format, directory), job -> {
            starting = null;
            watched.put(job.id(), current);
            exportStatus(Format.title(format) + " wird erzeugt …", Severity.INFO, false);
            refresh();
        }, error -> {
            starting = null;
            exportStatus(Format.title(format) + " nicht gestartet: " + Problems.describe(error).title(),
                    Severity.WARN, true);
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
