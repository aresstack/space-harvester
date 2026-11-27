package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.runtime.SearchMethods;
import com.aresstack.spaceharvester.app.ui.controls.InfoPopup;
import com.aresstack.spaceharvester.app.ui.controls.MarkedTextRenderer;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.query.progressive.NeighborLoader;
import com.aresstack.spaceharvester.application.query.DocumentKind;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.SearchExpansion;
import com.aresstack.spaceharvester.application.query.PageAddresses;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearchListener;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.SearchModeIcon;
import com.aresstack.spaceharvester.app.ui.content.WordSearch;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Future;

/**
 * Globale Suche als reine Schlagwortsuche über den Cache ({@code KeywordSearch} des Core, ADR 0013): Die native Suche
 * jeder angehakten Quelle entdeckt passende Seiten, der Core legt sie mit den Rechten des Aufrufers in den Cache, und
 * die Treffer kommen aus dessen Volltextindex, mit Textauszug aus dem Cache. Die Funde der Quellen stehen sofort als
 * vorläufige Treffer da ({@code Results.ranked()}, ADR 0016); während der Core lädt, zeigt die Ansicht seinen
 * Ladestand und ordnet geladene Treffer nach vorn, ohne dass einer verschwindet. Die Suchlogik bleibt im Core, die
 * Ansicht stellt nur dar. Eine Synchronisierung ist nie Voraussetzung. Unter der Liste steht je Quelle, ob sie keine eigene Suche kennt oder nicht erreichbar war, statt
 * still „keine Treffer“ zu melden; ein unvollständiges Ergebnis ist so gekennzeichnet.
 * Unter der Suchzeile steht je Quelle ein Häkchen: „Alle“ (Vorgabe) sucht ohne {@code sourceIds}, eine einzelne Quelle
 * mit {@code sourceId}, mehrere mit {@code sourceIds} (ADR 0009). Die Space-Auswahl bietet die Spaces der angehakten
 * Quellen an. Das Häkchen „Semantische Suche“ ist die progressive Suche des Core (ADR 0017): Originalanfrage zuerst,
 * dann Varianten (vom Chat-Modell, sonst nach Regeln) als Schlagwörter, alle Treffer gegen die Originalanfrage
 * bewertet und eingeordnet, bis der Core einen Abschlussgrund meldet; ohne „Weitere laden“.
 * „Weitere laden“ folgt dem Cursor des Core, der nur für dieselbe Anfrage gilt. Nur die jeweils letzte Anfrage darf die
 * Liste ändern.
 */
public final class SearchView implements View {

    private static final System.Logger LOG = System.getLogger(SearchView.class.getName());

    /**
     * Treffer je Stapel: ein Bildschirm voll statt des Höchstwerts des Core ({@link KeywordSearch#MAX_LIMIT}), damit
     * eine Suche nicht für jede Eingabe hundert Seiten in den Cache holt.
     */
    public static final int LIMIT = 25;
    /** Schluss der Fortschrittszeile, wenn nichts mehr nachlädt. */
    static final String DONE = "Die Suche ist abgeschlossen";
    /** Abstand der Abfragen des Ladestands ({@code KeywordSearch.progress}), solange Seiten in den Cache laden. */
    static final String SEMANTIC = "Semantische Suche";
    /** Suchmethode „Nachbarseiten“ (R2). */
    static final String NEIGHBORS = "Nachbarseiten einbeziehen";
    /** Suchmethode „Nur Fachbegriffe“ (K5b). */
    static final String TECHNICAL_TERMS = "Nur Fachbegriffe";
    static final String TECHNICAL_TERMS_TIP = "Zeigt statt aller Begriffe nur die Fachbegriffe des Glossars und ihre "
            + "Synonyme zum Prüfen; der Chat übernimmt diese Methode nicht";
    static final String TECHNICAL_TERMS_MODE = "Modus: Wortsuche – nur Fachbegriffe";
    /** Modus der Wortsuche (Angelo 27.11.2026): sucht nur nach Begriffen, nicht nach Seiten. */
    static final String WORDS_MODE = "Modus: Wortsuche";
    static final String WORD_KINDS = "Worttypen";
    static final String WORD_KIND_TIP = "Nach Wörtern dieses Typs suchen, wie im Reiter „Begriffe“";
    static final String WORD_SYNONYMS_TIP = "Passt der Suchtext zu einem Fachbegriff, werden auch die Wörter zu seinen "
            + "Synonymen gesucht";
    /** Tooltip der Lupe je Modus: was gerade gilt und was ein Klick tut. */
    static final String PAGE_MODE_TIP = "Seitensuche – Klick wechselt zur Wortsuche (nur Begriffe)";
    static final String WORD_MODE_TIP = "Wortsuche (nur Begriffe) – Klick wechselt zur Seitensuche";
    /** Schlüssel im Application State: die zuletzt gewählte Suche und die Wahl der Wortsuche. */
    static final String STATE_MODE = "search.mode";
    static final String STATE_WORDS = "words";
    static final String STATE_PAGES = "pages";
    static final String STATE_WORD_KIND = "search.words.kind.";
    static final String STATE_TECHNICAL = "search.words.technical";
    static final String STATE_WORD_SYNONYMS = "search.words.synonyms";
    static final String STATE_WORD_PAGES = "search.words.pages";
    static final String STATE_WORD_DOCUMENTS = "search.words.documents";
    /** Statt der Dateitypen unterscheidet die Wortsuche nur Seiten und Anhänge (Angelo 27.11.2026). */
    static final String WORD_ORIGIN = "Seiten und Anhänge";
    static final String WORD_PAGES = "Seiten";
    static final String WORD_DOCUMENTS = "Anhänge";
    /** Titel und Fragen der Hinweise, wenn Fachbegriffe oder Verben im Bereich fehlen. */
    static final String TECHNICAL_OFFER_TITLE = "Fachbegriffe ermitteln?";
    static final String VERBS_OFFER_TITLE = "Aussagen analysieren?";
    /** Gelbe Zeile, wenn die Nachbarseiten nicht geprüft werden konnten; kein Fehler der Suche. */
    static final String NEIGHBORS_UNCHECKED = "Nachbarseiten konnten nicht geprüft werden";
    /** Statuszeile, solange fehlende Nachbarn im Hintergrund laden. */
    static final String NEIGHBORS_LOADING = "weitere Nachbarseiten laden im Hintergrund";
    /** Suchmethode „Synonyme der Fachbegriffe“ (K5a). */
    static final String SYNONYMS = "Synonyme der Fachbegriffe einbeziehen";
    /** Suchmethode „Fachbegriffe“ (W8). */
    static final String TERM_PAGES = "Seiten zu Fachbegriffen einbeziehen";
    /** Suchmethode „Nur Überschriften durchsuchen“. */
    static final String HEADINGS_ONLY = "Nur Überschriften durchsuchen";
    static final String HEADINGS_ONLY_TIP = "Sucht nur in Seitentiteln und Überschriften, nicht im Fließtext; nur im "
            + "lokalen Index und immer als Schlagwortsuche";
    /** Ein Satz, wie bei den Nachbarseiten. */
    static final String TERM_PAGES_TIP = "Nennt die Anfrage einen bestätigten Fachbegriff, kommen Seiten dazu, auf "
            + "denen der Wissensgraph Aussagen zu ihm belegt";
    /** Gelbe Zeile, wenn die Seiten zu Fachbegriffen nicht ganz gesucht werden konnten; kein Fehler der Suche. */
    static final String TERM_PAGES_UNCHECKED = "Seiten zu Fachbegriffen nicht ganz gesucht";
    static final String TERM_PAGES_RUNNING = "Seiten zu Fachbegriffen werden gesucht …";
    /** Frist der Seiten zu Fachbegriffen im Schlagwortmodus: dieselbe wie die der ganzen progressiven Suche. */
    static final java.time.Duration TERM_PAGES_DEADLINE =
            com.aresstack.spaceharvester.application.query.progressive.SearchProfile.DEFAULT_TIME_BUDGET;
    /** Gelbe Zeile, wenn der Bereich größer war als die Grenze des Begriffswissens. */
    static final String TERM_PAGES_CAPPED = "Seiten zu Fachbegriffen: nur die ersten "
            + com.aresstack.spaceharvester.application.knowledge.TermKnowledge.MAX_PAGES
            + " Seiten des Bereichs gelesen (Grenze)";
    /** Gelbe Zeile, wenn das Glossar nicht lesbar ist; die Suche läuft ohne die Synonyme, kein Fehler. */
    static final String SYNONYMS_UNREADABLE = "Glossar nicht lesbar, ohne Synonyme der Fachbegriffe gesucht";

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 12));
    /** Die ganze Ansicht: oben {@link #heading} über volle Breite, darunter {@link #root} mit Innenabstand. */
    private final JPanel frame = new JPanel(new BorderLayout());
    /**
     * Überschrift „Suche in …“ wie im Lesebereich (Angelo, 23.11.2026): nennt den Bereich der letzten Suche, ein
     * Klick markiert ihn gelb und wählt ihn links im Baum; rechts wiederholt der Kreispfeil die Suche darin.
     */
    final com.aresstack.spaceharvester.app.ui.controls.SelectableHeading heading =
            new com.aresstack.spaceharvester.app.ui.controls.SelectableHeading(HEADING_EMPTY);
    static final String HEADING_EMPTY = "Suche";
    /** So viele Bereiche nennt die Überschrift mit Namen, mehr nur als Zahl. */
    static final int MAX_NAMED_AREAS = 3;
    static final String HEADING_PREFIX = "Suche in ";
    static final String REPEAT_TIP = "Suche im selben Bereich wiederholen (nicht im links gewählten)";
    /** Wiederholt die letzte Suche in ihrem Bereich; erst nach einer Suche wählbar. */
    final javax.swing.AbstractButton repeat;
    /** Zurück zwischen den Suchen dieser Sitzung (Begriff und Bereich); gedrückt halten zeigt den Verlauf. */
    final com.aresstack.spaceharvester.app.ui.controls.BackButton backVisit;
    /** Was links im Baum markiert ist; leer für „Wissensquellen“. */
    private List<Explorer.Scope> marked = List.of();
    /** Der Bereich der letzten Suche als Schnappschuss beim Start; {@code null} vor der ersten. */
    List<Explorer.Scope> searchedScopes;
    /** Wählt links den Bereich der Suche; {@code null} ohne Explorer. */
    private java.util.function.Consumer<List<Explorer.Scope>> locateListener;
    /**
     * Abgesetzt über den Treffern (Angelo, 23.11.2026): die Seite zu einer eingefügten Adresse bzw. Seiten, deren
     * Titel dem Suchtext genau oder fast entspricht. Ausgeblendet, solange es keine gibt.
     */
    final PinnedHits pinned = new PinnedHits();
    /** Zählt die Titelabfragen; eine überholte verfällt. */
    private int titleRequest;
    private final JTextField query = Comic.field(30);
    /** Progressive Suche des Core (ADR 0017): Originalanfrage, dann Varianten bis zum Qualitätsziel. */
    final JCheckBox semantic = checkBox(SEMANTIC, "Sucht zusätzlich mit Varianten der Anfrage und ordnet alle Treffer "
            + "nach ihrer Nähe zur ursprünglichen Anfrage");
    /**
     * Suchmethode „Nachbarseiten“ (R2, Angelo 21.11.2026: zuschaltbar, Standard aus): nimmt Eltern-, Unter- und
     * verlinkte Seiten der besten Treffer dazu, wenn sie die Anfrage auch treffen. Die Stellung bleibt erhalten
     * ({@link SearchMethods}); der Chat folgt ihr.
     */
    final JCheckBox neighbors = checkBox(NEIGHBORS, NEIGHBORS_TIP);
    /**
     * Suchmethode „Synonyme der Fachbegriffe einbeziehen“ (K5a, Angelo 21.11.2026: zuschaltbar, Standard aus, gilt für
     * die Sitzung): Zu bestätigten Fachbegriffen des Glossars kommen ihre vorgeschlagenen Synonyme als Anfragen dazu.
     * Im Modus „Nur Fachbegriffe“ wirkungslos, bleibt aber bedienbar. Der Chat folgt ihr ({@link SearchMethods}).
     */
    final JCheckBox glossarySynonyms = checkBox(SYNONYMS, SYNONYMS_TIP);
    /**
     * Suchmethode „Fachbegriffe“ (W8, Angelo 21.11.2026: zuschaltbar, Standard aus): Nennt die Anfrage einen
     * bestätigten Fachbegriff, kommen Seiten mit Aussagen zu ihm aus dem Wissensgraphen dazu. Die Stellung bleibt
     * erhalten ({@link SearchMethods}).
     */
    final JCheckBox termPages = checkBox(TERM_PAGES, TERM_PAGES_TIP);
    /**
     * Suchmethode „Nur Überschriften durchsuchen“ (Angelo 27.11.2026): Seitentitel und alle Überschriften, kein
     * Fließtext. Die Stellung bleibt erhalten ({@link SearchMethods}).
     */
    final JCheckBox headingsOnly = checkBox(HEADINGS_ONLY, HEADINGS_ONLY_TIP);
    /** Die Suche, nach deren Stapel die Seiten zu Fachbegriffen noch gesucht werden; -1 ohne. */
    private int termPagesFor = -1;
    /** Stopp der laufenden Suche nach Seiten zu Fachbegriffen (Schlagwortmodus) oder {@code null}. */
    java.util.concurrent.atomic.AtomicBoolean termPagesStop;
    /** Abbrechen der progressiven Suche ist angefordert; der Core meldet das Ende. */
    private boolean runningCancelRequested;
    /** Stand der Seiten zu Fachbegriffen für die Statuszeile: angehängt, laufend, gelber Hinweis. */
    private int termPagesAdded;
    private boolean termPagesRunning;
    private String termPagesNoteText;
    /** Wie oft die Suche nach Seiten zu Fachbegriffen im Schlagwortmodus startete (für Tests). */
    int termSearchesStarted;
    /** Der zuletzt in die Statuszeile geschriebene Teil zu Fachbegriffen. */
    private String shownTermTail = "";
    /** Gelber Zusatz der Statuszeile zur Synonymsuche der laufenden Anfrage (Kürzung, Glossar nicht lesbar), sonst leer. */
    private String synonymNote = "";
    /**
     * Suchmethode „Nur Fachbegriffe“ (K5b, Angelo 21.11.2026; Standard aus, gilt für die Sitzung): statt der
     * Trefferliste die Fachbegriffe des Glossars links und ihre Synonyme rechts, je mit Bestätigen und Ablehnen
     * ({@link TechnicalTermsPanel}). Eine Pflegeansicht; der Chat übernimmt sie nicht.
     */
    final JCheckBox technicalTerms = checkBox(TECHNICAL_TERMS, TECHNICAL_TERMS_TIP);
    /**
     * Die Lupe links im Suchfeld (Angelo 27.11.2026): ein Klick wechselt zwischen Seitensuche und Wortsuche; in der
     * Wortsuche steht ein kleines „w“ hinter der Lupe. Die letzte Wahl merkt der Application State.
     */
    final SearchModeIcon modeIcon = new SearchModeIcon();
    /** Ob die Wortsuche gilt: nur Begriffe, keine Seiten. */
    boolean words;
    /** Die Worttypen der Wortsuche wie die Abschnitte des Reiters „Begriffe“; gemerkt im Application State. */
    final Map<WordSearch.Kind, JCheckBox> wordKindBoxes = new java.util.EnumMap<>(WordSearch.Kind.class);
    /** „Synonyme der Fachbegriffe einbeziehen“ der Wortsuche; gemerkt im Application State. */
    final JCheckBox wordSynonyms = checkBox(SYNONYMS, WORD_SYNONYMS_TIP);
    /** Wortsuche: Begriffe der Seiten selbst bzw. ihrer Anhänge und enthaltenen Dateien; gemerkt im Application State. */
    final JCheckBox wordPages = checkBox(WORD_PAGES, "Begriffe der Seiten selbst durchsuchen");
    final JCheckBox wordDocuments = checkBox(WORD_DOCUMENTS, "Begriffe der Anhänge und enthaltenen Dateien durchsuchen");
    /** Die Wörter der Wortsuche in der Mitte statt der Seiten. */
    final WordHitsPanel wordsPanel;
    /** Der zuletzt in der Wortsuche gesuchte Text; wechselt der Bereich links, sucht sie damit neu. */
    private String searchedWords;
    /** Zählt Wortsuchen; Antworten einer überholten verfallen. */
    private int wordRequest;
    /** Die Ansicht der Methode „Nur Fachbegriffe“ in der Mitte statt der Treffer. */
    final TechnicalTermsPanel termsPanel;
    /** Mitte: Trefferliste oder Fachbegriffe. */
    private final java.awt.CardLayout centerCards = new java.awt.CardLayout();
    private final JPanel center = new JPanel(centerCards);
    private static final String HITS_CARD = "hits";
    private static final String TERMS_CARD = "terms";
    private static final String WORDS_CARD = "words";
    /** Die Suche, nach deren Stapel die Nachbarseiten noch gesucht werden; -1 ohne. */
    private int neighborsFor = -1;
    /** Beobachtet das Nachladen fehlender Nachbarn der laufenden Anfrage; {@code null} ohne. */
    private Subscription neighborLoadWatch;
    /**
     * Zählt Anfragen, nicht Stapel: „Weitere laden“ bleibt dieselbe Anfrage, nachgeladene Nachbarn gehören weiter
     * dazu (Auflage AT). {@link #request} zählt dagegen jeden Stapel.
     */
    private volatile int searchId;
    /**
     * Kennung der laufenden Schlagwortsuche im Core (Fundstelle 23): gleich für ihre Stapel und ihr Neusortieren, damit
     * sie jeden Fund nur einmal zum Katalogisieren anbietet.
     */
    private String keywordSearchId;
    /** Nachbar-Treffer, die ankamen, während ein Stapel lud; sie folgen nach dem Stapel, sonst schnitte er sie ab. */
    private final List<KeywordSearch.Hit> heldNeighbors = new ArrayList<>();
    /** Seiten zu Fachbegriffen, die während eines ladenden Stapels kamen; sie folgen nach ihm (wie die Nachbarn). */
    private final List<KeywordSearch.Hit> heldTermPages = new ArrayList<>();
    /** Läuft die Nachbarsuche dieser Anfrage, wartet „Weitere laden“: Der nächste Stapel verwürfe sonst ihre Treffer. */
    private int neighborsPending = -1;
    /** Die Suchanfragen der semantischen Suche als Schlagwörter; die ursprüngliche steht im Suchfeld. */
    final JPanel tags = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    /** Die laufende semantische Suche; {@code null}, wenn keine läuft. */
    private ProgressiveSearch running;
    /** Der Umfang aus dem Explorer: „In: Alle Wissensquellen“ bzw. die gewählten Quellen und Spaces. */
    final JLabel scopeLabel = Comic.muted(" ");
    /** Der Modus der nächsten Suche: Schlagwortsuche oder semantisch, wie der Core ihn meldet. */
    final JLabel modeLabel = Comic.muted(" ");
    /**
     * Ein Häkchen je eingetragener Wissensquelle (Angelo 20.11.2026, ersetzt „Überall suchen“ aus #515): Vorgabe alle
     * an; abgehakt nimmt die nächste Suche die Quelle aus, auch wenn sie im Explorer gewählt ist. Nur der Benutzer
     * schaltet um, keine Baumauswahl und keine Konfigurationsänderung; eine neue Quelle kommt angehakt dazu. Gilt für
     * die Sitzung, nicht darüber hinaus.
     */
    final Map<String, JCheckBox> sourceBoxes = new LinkedHashMap<>();
    /**
     * Ein Häkchen je Dokumentart ({@link DocumentKind}: Seiten, PDF, Word, …), Vorgabe alle an; abgehakt fehlt die
     * Art in der nächsten Suche. Gilt für die Sitzung wie die Quellen.
     */
    final Map<DocumentKind, JCheckBox> kindBoxes = new java.util.EnumMap<>(DocumentKind.class);
    /** Die Dateitypen der nächsten Suche: „Dateitypen: alle“ oder die angehakten. */
    final JLabel kindsLabel = Comic.muted(" ");
    /** Die Suchleiste der Kopfzeile des Hauptfensters: Suchfeld mit Pfeil für die Suchoptionen (#515). */
    private final com.aresstack.spaceharvester.app.ui.controls.InputFrame searchBar =
            com.aresstack.spaceharvester.app.ui.controls.InputFrame.search(query, modeIcon);
    /** Pfeil rechts im Suchfeld: öffnet {@link #options}. */
    final javax.swing.JButton optionsButton =
            com.aresstack.spaceharvester.app.ui.controls.InputFrame.flatButton("\u25BE", "Suchoptionen");
    /** Die Suchoptionen als Popup am Suchfeld: Quellen, Dateitypen, Semantische Suche, Umfang, Modus, Hinweise. */
    final javax.swing.JPopupMenu options = new javax.swing.JPopupMenu();
    /** Warum die semantische Suche gesperrt ist bzw. woher ihre Varianten kommen, lesbar im Popup. */
    final JLabel semanticNote = Comic.muted(" ");
    /** Läuft vor jeder Suche aus dem Suchfeld: das Hauptfenster zeigt die Trefferansicht. */
    private Runnable onSubmit = () -> { };
    private final ComposerButton run = Comic.primary("Suchen", "Im gewählten Umfang suchen (Titel und Inhalt)");
    private final ComposerButton more = Comic.button("Weitere laden", "Die nächsten Treffer derselben Suche laden");
    /** Paketweit für Tests. */
    final HitsModel model = new HitsModel();
    private final JTable table = Comic.table(new JTable(model));
    /** Einzelheiten des Treffergrunds (R1). */
    private final InfoPopup reasons = new InfoPopup();
    /** Auszug mit der Marke des Treffergrunds vorn. */
    private final MarkedTextRenderer excerpts = new MarkedTextRenderer(row -> row < model.rows.size()
            ? model.rows.get(row).mark() : null);
    /**
     * Drehender Ring über der Trefferliste, solange eine Suche läuft, mit roter Stopp-Fläche „Abbrechen“ in der Mitte
     * (Klick oder Esc). Die Treffer, die schon da sind, bleiben stehen und bedienbar, der Schleier lässt die Maus durch.
     */
    final BusyOverlay overlay = new BusyOverlay(Comic.scroll(table));
    /** Die laufende Schlagwortsuche bzw. das Laden des nächsten Stapels im Hintergrund; {@code null} ohne. */
    /** Der laufende Stapel; paketweit für Tests. */
    Future<?> searching;
    final JLabel status = Comic.muted(" ");
    /** Wie viel des Bereichs lokal liegt (K6), aus dem Bestand; nach Meldungen des Bestands neu gezählt. */
    final JLabel localShare = Comic.muted(" ");
    /** Anmeldungen beim Bestand für die Quellen bzw. Spaces des Bereichs; ersetzt bei jedem neuen Bereich. */
    private final List<Subscription> localShareWatch = new java.util.ArrayList<>();
    /** Zählt Bereiche; ein Ergebnis zu einem überholten Bereich wird verworfen. */
    private int localShareRequest;
    /** Eine Zählung läuft; Meldungen währenddessen lösen genau eine weitere danach aus. */
    private boolean localShareCounting;
    private boolean localShareAgain;
    /** Der Bereich der zuletzt angemeldeten Zählung, damit gleicher Bereich nicht neu anmeldet. */
    private com.aresstack.spaceharvester.application.holdings.Selection localShareArea;
    private AppRuntime localShareRuntime;
    private final Feedback feedback = new Feedback();
    /** Stand der Suche in der Statuszeile des Hauptfensters: Ladestand bzw. Ende, Abbrechen, Zahl der Treffer. */
    final JLabel progressText = Comic.muted(" ");
    /** Nach dem Ende: „Fertig“, „Abgebrochen“ oder „Fehlgeschlagen“. */
    final JLabel endState = new JLabel(" ");
    /** Der Stand für die Statuszeile ({@link #statusInfo()}); die Ansicht hat keine eigene Aktionszeile mehr. */
    private final JPanel statusInfo = new JPanel();
    final ComposerButton cancel = Comic.button("Abbrechen", "Das Laden der gefundenen Seiten abbrechen; Geladenes "
            + "bleibt im Cache");
    /**
     * Anmeldung für den Ladestand des laufenden Stapels (ADR 0027, Angelo 13.11.2026: kein Zeitgeber); steigt die
     * Zahl geladener Seiten, wird derselbe Stapel neu geordnet ({@link #refresh}).
     */
    private Subscription progressSubscription = Subscription.NONE;
    /** Kennung des ladenden Stapels für {@code progress} und {@code cancel}; {@code null}, wenn nichts lädt. */
    String progressId;
    /** Zuletzt gemeldete geladene Seiten; steigt die Zahl, sucht die Ansicht denselben Stapel erneut. */
    private int lastLoaded;
    /** Ob der erste Stapel leer kam, während der Core noch lädt; „Keine Treffer“ erst nach dem Laden. */
    private boolean awaitingHits;
    /** Ob ein erneutes Suchen desselben Stapels läuft; nie zwei zugleich. */
    private boolean refreshing;
    /** Ob das Laden abgebrochen wurde; dann sucht die Ansicht nicht erneut, denn das stieße das Laden wieder an. */
    private boolean cancelled;
    /** Zeile, ab der der angezeigte Stapel in der Liste steht; nur er wird neu geordnet. */
    private int batchStart;
    /** Anfrage und Cursor der zuletzt angezeigten Suche, für „Weitere laden“; der Cursor gilt nur für sie. */
    private KeywordSearch.Query lastQuery;
    /** Der zuletzt gesuchte Text; wechselt der Bereich links, sucht die Suche damit neu (Angelo, 21.11.2026). */
    private String searchedText;
    /** Anfrage der laufenden semantischen Suche; für das Nachreichen geladener Nachbarn. */
    private KeywordSearch.Query progressiveQuery;
    /** Ob ein Stapel derselben Suche unvollständig war; bleibt bis zur nächsten Suche. */
    private boolean incomplete;
    /** Zustand je Quelle aus dem ersten Stapel, ergänzt um spätere Ausfälle. */
    private final Map<String, KeywordSearch.SourceState> states = new LinkedHashMap<>();
    private String nextCursor;
    /** Cursor des zuletzt angezeigten Stapels ({@code null} für den ersten); das Nachfragen wiederholt genau ihn. */
    private String shownCursor;
    /** Zählt Suchen und „Weitere laden“; Ergebnisse einer überholten Anfrage werden verworfen. */
    private int request;
    /** Quellen aus der Auswahl im Explorer; leer für alle. */
    private final Set<String> chosen = new TreeSet<>();
    /** Während {@link #scope} die Häkchen der Quellen setzt, lösen sie keinen eigenen Umfang aus. */
    private boolean syncingBoxes;
    /** Wählt links die angehakten Quellen; {@code null} ohne Explorer. */
    private java.util.function.Consumer<List<Explorer.Scope>> sourcesListener;
    /** Gewählte Knoten mit Namen für die Umfangszeile. */
    private List<Explorer.Scope> scopes = List.of();

    public SearchView(AppContext context) {
        this.context = context;
        root.setBackground(Comic.SURFACE);
        frame.setBackground(Comic.SURFACE);
        frame.add(heading, BorderLayout.NORTH);
        frame.add(root, BorderLayout.CENTER);
        backVisit = new com.aresstack.spaceharvester.app.ui.controls.BackButton("Zurück zur vorigen Suche bzw. zum vorigen Filter")
                .navigator(new com.aresstack.spaceharvester.app.ui.controls.BackButton.Navigator() {
                    @Override
                    public com.aresstack.spaceharvester.app.ui.controls.BackButton.History history() {
                        List<String> forward = new ArrayList<>();
                        for (int i = visit + 1; i < visits.size(); i++) {
                            forward.add(visitName(visits.get(i)));
                        }
                        List<String> back = new ArrayList<>();
                        for (int i = visit - 1; i >= 0; i--) {
                            back.add(visitName(visits.get(i)));
                        }
                        return new com.aresstack.spaceharvester.app.ui.controls.BackButton.History(forward, back);
                    }

                    @Override
                    public void go(int steps) {
                        int target = visit + steps;
                        if (steps != 0 && target >= 0 && target < visits.size()) {
                            revisit(target);
                        }
                    }
                });
        repeat = heading.tool(Glyph.SYNC, REPEAT_TIP, this::repeat);
        repeat.setEnabled(false);
        heading.onSelect(() -> {
            table.clearSelection();
            pinned.clearSelection();
            if (searchedScopes != null && locateListener != null) {
                locateListener.accept(searchedScopes);
            }
        });
        pinned.onSelect(hit -> {
            table.clearSelection();
            heading.selected(false);
            context.navigator().hintPage(hit.sourceId(), hit.spaceKey(), hit.pageId());
        });
        pinned.onOpen(hit -> context.navigator().openPage(hit.sourceId(), hit.pageId()));
        query.setToolTipText("Wörter in Titel und Inhalt; die Quellen werden live durchsucht");
        query.addActionListener(e -> {
            onSubmit.run();
            search();
        });
        run.addActionListener(e -> search());
        more.addActionListener(e -> loadMore());
        more.setEnabled(false);
        cancel.addActionListener(e -> cancelLoading());
        endState.setFont(Comic.semiBold(13f));
        endState.setForeground(Comic.INK);
        endState.setVisible(false);
        cancel.setVisible(false);
        more.setVisible(false);
        semantic.setSelected(false);
        semantic.addItemListener(e -> mode());
        termsPanel = new TechnicalTermsPanel(error -> feedback.problem(error));
        wordsPanel = new WordHitsPanel(place -> context.navigator().openPage(place.sourceId(), place.pageId()),
                this::wordStatus, error -> feedback.problem(error));
        AppState state = AppState.shared();
        words = STATE_WORDS.equals(state.get(STATE_MODE));
        modeIcon.word(words);
        modeIcon.setToolTipText(words ? WORD_MODE_TIP : PAGE_MODE_TIP);
        modeIcon.addActionListener(e -> switchMode(!words));
        // „Nur Fachbegriffe“ gehört zur Wortsuche (Angelo 27.11.2026) und bleibt wie die Worttypen gemerkt.
        technicalTerms.setSelected(state.getBoolean(STATE_TECHNICAL, false));
        technicalTerms.addItemListener(e -> {
            AppState.shared().put(STATE_TECHNICAL, technicalTerms.isSelected());
            mode();
            if (technicalOn() && !restoringHistory) {
                searchTechnicalTerms();
            }
        });
        for (WordSearch.Kind kind : WordSearch.Kind.values()) {
            JCheckBox box = checkBox(kind.label(), WORD_KIND_TIP);
            // Vorgabe: Akronyme und Bezeichner; Verben gibt es erst nach der Aussagenanalyse.
            box.setSelected(state.getBoolean(STATE_WORD_KIND + kind.name(), kind != WordSearch.Kind.VERBS));
            box.addItemListener(e -> AppState.shared().put(STATE_WORD_KIND + kind.name(), box.isSelected()));
            wordKindBoxes.put(kind, box);
        }
        wordPages.setSelected(state.getBoolean(STATE_WORD_PAGES, true));
        wordPages.addItemListener(e -> AppState.shared().put(STATE_WORD_PAGES, wordPages.isSelected()));
        wordDocuments.setSelected(state.getBoolean(STATE_WORD_DOCUMENTS, true));
        wordDocuments.addItemListener(e -> AppState.shared().put(STATE_WORD_DOCUMENTS, wordDocuments.isSelected()));
        wordSynonyms.setSelected(state.getBoolean(STATE_WORD_SYNONYMS, false));
        wordSynonyms.addItemListener(e -> {
            AppState.shared().put(STATE_WORD_SYNONYMS, wordSynonyms.isSelected());
            mode();
        });
        neighbors.setSelected(SearchMethods.user().neighbors());
        neighbors.addItemListener(e -> {
            SearchMethods.user().neighbors(neighbors.isSelected());
            mode();
        });
        termPages.setSelected(SearchMethods.user().termPages());
        termPages.addItemListener(e -> {
            SearchMethods.user().termPages(termPages.isSelected());
            mode();
            if (termPages.isSelected() && !restoringHistory) {
                // Nur sinnvoll mit Fachbegriffen: fehlen sie im Bereich, sagt die Suche es gleich (Angelo 27.11.2026).
                checkTechnicalTerms(TERM_PAGES);
            }
        });
        headingsOnly.setSelected(SearchMethods.user().headingsOnly());
        headingsOnly.addItemListener(e -> {
            SearchMethods.user().headingsOnly(headingsOnly.isSelected());
            mode();
        });
        glossarySynonyms.setSelected(SearchMethods.user().glossarySynonyms());
        glossarySynonyms.addItemListener(e -> {
            SearchMethods.user().glossarySynonyms(glossarySynonyms.isSelected());
            mode();
        });
        tags.setOpaque(false);
        tags.setVisible(false);
        for (DocumentKind kind : DocumentKind.values()) {
            JCheckBox box = checkBox(kind.label(), "Nur Dokumente dieser Art durchsuchen; abgehakt fehlt sie");
            box.setSelected(true);
            box.addItemListener(e -> kinds());
            kindBoxes.put(kind, box);
        }
        kinds();
        context.onChange(ignored -> refreshSources());
        refreshSources();
        // Schon die Auswahl eines Treffers zeigt seine Seite im Explorer; ein Doppelklick öffnet sie.
        // Nachkommende Treffer bauen die Tabelle neu und melden dieselbe Auswahl noch einmal; dieselbe Seite wird dann
        // nicht erneut im Explorer gezeigt.
        String[] hinted = {null};
        table.getSelectionModel().addListSelectionListener(e -> {
            int row = table.getSelectedRow();
            if (!e.getValueIsAdjusting() && row >= 0 && table.getSelectedRowCount() == 1) {
                heading.selected(false);
                pinned.clearSelection();
                Hit hit = model.rows.get(table.convertRowIndexToModel(row));
                String key = hit.sourceId() + "\u0000" + hit.pageId();
                if (!key.equals(hinted[0])) {
                    hinted[0] = key;
                    context.navigator().hintPage(hit.sourceId(), hit.spaceKey(), hit.pageId());
                }
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1 && showReasons(e)) {
                    return;
                }
                if (e.getClickCount() == 2) {
                    reasons.setVisible(false);
                }
                int row = table.getSelectedRow();
                if (e.getClickCount() == 2 && row >= 0) {
                    open(model.rows.get(table.convertRowIndexToModel(row)));
                }
            }
        });
        table.setToolTipText(ROW_TIP);
        // Nur filtern, nie sortieren: die Rangfolge kommt aus dem Core.
        for (int column = 0; column < model.getColumnCount(); column++) {
            sorter.setSortable(column, false);
        }
        table.setRowSorter(sorter);
        table.getColumnModel().getColumn(HitsModel.EXCERPT).setCellRenderer(excerpts);
        // Ganz vorn der Stern (Angelo 27.11.2026): gefüllt als Favorit, in einer Gruppe in deren Farbe mit Streifen.
        table.getColumnModel().getColumn(HitsModel.TITLE).setCellRenderer(new TitleRenderer());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    hitMenu(e);
                } else if (javax.swing.SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1 && onStar(e)) {
                    toggleFavorite(hitAt(e));
                    e.consume();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    hitMenu(e);
                }
            }
        });
        context.favorites().onChange(() -> javax.swing.SwingUtilities.invokeLater(table::repaint));
        table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                boolean mark = onMark(e);
                Cursors.set(table, mark ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                        : java.awt.Cursor.getDefaultCursor());
                // Bei einem Nachbarn nennt der Tooltip der Marke die Ausgangsseite (UX-Manager 21.11.2026).
                String tip = mark ? model.rows.get(table.convertRowIndexToModel(table.rowAtPoint(e.getPoint())))
                        .markTip() : onStar(e) ? GroupMenu.STAR_TIP : null;
                table.setToolTipText(tip != null ? tip : ROW_TIP);
            }
        });

        buildSearchBar();
        JPanel head = new JPanel(new BorderLayout(0, 8));
        head.setOpaque(false);
        head.add(tags, BorderLayout.CENTER);
        head.add(feedback, BorderLayout.SOUTH);
        root.add(head, BorderLayout.NORTH);
        // „Weitere laden“ über die volle Breite, direkt unter der Trefferliste; nur, solange weitere Treffer folgen.
        JPanel list = new JPanel(new BorderLayout(0, 6));
        list.setOpaque(false);
        overlay.setHighlight(Comic.BLUE);
        overlay.setVeilColor(Comic.SURFACE);
        overlay.onCancel(this::cancelSearch);
        list.add(overlay, BorderLayout.CENTER);
        list.add(pinned, BorderLayout.NORTH);
        more.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        more.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, more.getPreferredSize().height));
        list.add(more, BorderLayout.SOUTH);
        center.setOpaque(false);
        center.add(list, HITS_CARD);
        center.add(termsPanel, TERMS_CARD);
        center.add(wordsPanel, WORDS_CARD);
        root.add(center, BorderLayout.CENTER);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Der Stand steht in der Statuszeile des Hauptfensters: Ladestand, Abbrechen bzw. Endzustand, Treffer.
        // Wird es eng, kürzt nur der Hinweis rechts (mit „…“, ganz im Tooltip); Abbrechen bleibt immer sichtbar.
        statusInfo.setOpaque(false);
        statusInfo.setLayout(new javax.swing.BoxLayout(statusInfo, javax.swing.BoxLayout.X_AXIS));
        for (JComponent part : List.of(progressText, cancel, endState)) {
            part.setMinimumSize(part.getPreferredSize());
            part.addPropertyChangeListener("text", e -> {
                part.setMinimumSize(null);
                part.setMinimumSize(part.getPreferredSize());
            });
            statusInfo.add(part);
            statusInfo.add(javax.swing.Box.createHorizontalStrut(8));
        }
        status.setMinimumSize(new java.awt.Dimension(0, 0));
        status.addPropertyChangeListener("text", e -> status.setToolTipText(
                status.getText() == null || status.getText().isBlank() ? null : status.getText()));
        statusInfo.add(status);
        statusInfo.add(javax.swing.Box.createHorizontalStrut(8));
        localShare.setToolTipText(LocalShare.TIP);
        localShare.setMinimumSize(new java.awt.Dimension(0, 0));
        statusInfo.add(localShare);
    }

    /**
     * Ein Klick auf die Marke des Treffergrunds vorn im Auszug zeigt die Einzelheiten im Overlay (R1).
     *
     * @return ob der Klick die Marke traf
     */
    private boolean showReasons(MouseEvent e) {
        if (!onMark(e)) {
            return false;
        }
        Hit hit = model.rows.get(table.convertRowIndexToModel(table.rowAtPoint(e.getPoint())));
        reasons.text(HitReasons.HEADING, HitReasons.details(hit.reasons())).showAt(table, e.getX(), e.getY());
        return true;
    }

    /** Mit der Passage-ID seines Absatzes springt der Treffer dorthin, sonst öffnet er die Seite. */
    private void open(Hit hit) {
        if (hit.passageId() != null) {
            context.navigator().openPassage(hit.sourceId(), hit.passageId());
        } else {
            context.navigator().openPage(hit.sourceId(), hit.pageId());
        }
    }

    /** Der Treffer unter der Maus oder {@code null}. */
    private Hit hitAt(MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        return row < 0 ? null : model.rows.get(table.convertRowIndexToModel(row));
    }

    /** Ob die Maus auf dem Stern vorn im Titel steht. */
    private boolean onStar(MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        int column = table.columnAtPoint(e.getPoint());
        if (row < 0 || column < 0 || table.convertColumnIndexToModel(column) != HitsModel.TITLE) {
            return false;
        }
        java.awt.Rectangle cell = table.getCellRect(row, column, false);
        return e.getX() < cell.x + TitleRenderer.STAR_END;
    }

    /**
     * Rechtsklick auf einen Treffer: auf dem Stern nur die Gruppen und „Neue Gruppe …“, sonst Öffnen, Favorit und
     * „Gruppe zuordnen“ (Angelo 27.11.2026).
     */
    private void hitMenu(MouseEvent e) {
        Hit hit = hitAt(e);
        if (hit == null) {
            return;
        }
        javax.swing.JPopupMenu menu = onStar(e) ? starMenu(hit) : hitMenu(hit);
        menu.show(table, e.getX(), e.getY());
    }

    /** Rechtsklick auf den Stern eines Treffers. */
    javax.swing.JPopupMenu starMenu(Hit hit) {
        return GroupMenu.popup(table, context.favorites(), groupOf(hit), id -> assignGroup(hit, id));
    }

    /** Das Rechtsklick-Menü eines Treffers. */
    javax.swing.JPopupMenu hitMenu(Hit hit) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem open = new javax.swing.JMenuItem("Öffnen");
        open.addActionListener(e -> open(hit));
        menu.add(open);
        boolean favorite = isFavorite(hit);
        javax.swing.JMenuItem star = new javax.swing.JMenuItem(favorite ? "Favorit entfernen" : "Als Favorit markieren",
                favorite ? Glyph.STAR_FILLED : Glyph.STAR);
        star.addActionListener(e -> toggleFavorite(hit));
        menu.add(star);
        menu.add(GroupMenu.submenu(table, context.favorites(), groupOf(hit), id -> assignGroup(hit, id)));
        return menu;
    }

    /**
     * Der Space des Treffers; ein vorläufiger Treffer kennt ihn nicht, dann der seines Favoriten (gleiche Quelle und
     * Seite), sonst {@code null}.
     */
    private String spaceOf(Hit hit) {
        if (hit.spaceKey() != null) {
            return hit.spaceKey();
        }
        return context.favorites().all().stream()
                .filter(f -> f.sourceId().equals(hit.sourceId()) && hit.pageId().equals(f.pageId()))
                .map(Favorites.Favorite::spaceKey).findFirst().orElse(null);
    }

    private boolean isFavorite(Hit hit) {
        String space = spaceOf(hit);
        return space != null && context.favorites().contains(hit.sourceId(), space, hit.pageId());
    }

    private String groupOf(Hit hit) {
        String space = spaceOf(hit);
        return space == null ? null : context.favorites().groupOf(hit.sourceId(), space, hit.pageId()).orElse(null);
    }

    private com.aresstack.spaceharvester.app.group.GroupColor colorOf(Hit hit) {
        String space = spaceOf(hit);
        return space == null ? null : context.favorites().colorOf(hit.sourceId(), space, hit.pageId()).orElse(null);
    }

    /** Stern am Treffer: derselbe Weg wie der Stern im Baum (Pfad aus der Position der Seite). */
    void toggleFavorite(Hit hit) {
        if (hit != null) {
            GroupMenu.pageFavorite(context, hit.sourceId(), spaceOf(hit), hit.pageId(), hit.title(),
                    context.favorites()::toggle);
        }
    }

    /** „Gruppe zuordnen“ am Treffer: Favorit in dieser Gruppe, aus einer anderen wandert er heraus. */
    void assignGroup(Hit hit, String groupId) {
        GroupMenu.pageFavorite(context, hit.sourceId(), spaceOf(hit), hit.pageId(), hit.title(),
                favorite -> context.favorites().assign(favorite, groupId));
    }

    /** Der Titel eines Treffers, vorn der Stern; in einer Gruppe deren Farbstreifen links. */
    private final class TitleRenderer extends javax.swing.table.DefaultTableCellRenderer {

        private static final int PAD = 3;
        /** Bis hierher (vom linken Zellrand) reicht die Klickfläche des Sterns. */
        static final int STAR_END = 2 * PAD + com.aresstack.spaceharvester.app.ui.controls.GroupStripe.WIDTH
                + Glyph.STAR.getIconWidth() + PAD;

        TitleRenderer() {
            putClientProperty("html.disable", Boolean.TRUE);
            setIconTextGap(6);
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(JTable owner, Object value, boolean selected,
                                                                boolean focus, int row, int column) {
            super.getTableCellRendererComponent(owner, value, selected, focus, row, column);
            Hit hit = model.rows.get(owner.convertRowIndexToModel(row));
            com.aresstack.spaceharvester.app.group.GroupColor color = colorOf(hit);
            setIcon(GroupMenu.star(isFavorite(hit), color));
            setBorder(color != null
                    ? new com.aresstack.spaceharvester.app.ui.controls.GroupStripe(
                            com.aresstack.spaceharvester.app.ui.controls.GroupColors.of(color), PAD)
                    : BorderFactory.createEmptyBorder(0, 2 * PAD
                            + com.aresstack.spaceharvester.app.ui.controls.GroupStripe.WIDTH, 0, 0));
            return this;
        }
    }

    /** Ob die Maus auf der Marke des Treffergrunds steht; dasselbe Maß wie beim Zeichnen. */
    private boolean onMark(MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        int column = table.columnAtPoint(e.getPoint());
        if (row < 0 || column < 0 || table.convertColumnIndexToModel(column) != HitsModel.EXCERPT) {
            return false;
        }
        return excerpts.markBounds(table, row, column).contains(e.getPoint());
    }

    /** Hinweise zur Suche, früher im Tooltip des Infosymbols, jetzt lesbar im Popup. */
    static final String HELP = "<html><b>Hinweise zur Suche</b><br>"
            + "Schlagwortsuche über Titel und Inhalt, direkt in den Quellen.<br>"
            + "Wo gesucht wird, wählst du im Explorer links: eine Wissensquelle, einen Space<br>"
            + "oder mit Strg-Klick mehrere; ohne Auswahl in allen angehakten Quellen.<br>"
            + "Die Häkchen oben nehmen Quellen und Dateitypen aus der Suche.<br>"
            + "Semantische Suche: zusätzlich Varianten der Anfrage, alle Treffer nach Nähe zur Anfrage<br>"
            + "geordnet; braucht ein Embedding-Modell (Einstellungen › Modelle).<br>"
            + "Die Lupe links im Suchfeld wechselt zur Wortsuche (Lupe mit „w“): sie sucht nur Begriffe<br>"
            + "der angehakten Worttypen im Bereich; ein Klick auf ein Wort zeigt seine Seiten.<br>"
            + "Nur Fachbegriffe (Wortsuche): links die Fachbegriffe des Glossars zum Suchtext (auch über Synonyme), rechts<br>"
            + "ihre Synonyme, je zum Bestätigen oder Ablehnen; leeres Feld zeigt alle des Bereichs. Eine Pflege-<br>"
            + "ansicht ohne Seitentreffer, der Chat übernimmt sie deshalb nicht.<br>"
            + "Eine Seitenadresse oder ein Lesezeichen (space-harvester://…) öffnet die Seite direkt.<br>"
            + "Doppelklick auf einen Treffer öffnet ihn unter Inhalte.</html>";

    /** Suchfeld mit Pfeil rechts; der Pfeil öffnet die Suchoptionen unter dem Feld (#515). */
    private void buildSearchBar() {
        query.getAccessibleContext().setAccessibleName("Suchbegriff");
        optionsButton.setToolTipText("Suchoptionen");
        optionsButton.getAccessibleContext().setAccessibleName("Suchoptionen");
        optionsButton.setFocusable(true);
        optionsButton.addActionListener(e -> showOptions());
        // Wie in AskAI: Lupe links, ✕ (bei Text) und ▾ rechts IM Feld, kein loser Knopf daneben.
        searchBar.addButton(optionsButton);
        // Pfeil nach unten bzw. Alt+Pfeil nach unten im Feld öffnet die Optionen auch per Tastatur.
        query.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("alt DOWN"), "searchOptions");
        query.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("DOWN"), "searchOptions");
        query.getActionMap().put("searchOptions", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                showOptions();
            }
        });
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBackground(Comic.SURFACE);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JLabel help = new JLabel(HELP);
        help.setForeground(Comic.MUTED);
        for (JComponent part : List.of(semanticNote, scopeLabel, kindsLabel, modeLabel, help)) {
            part.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            panel.add(part);
            panel.add(javax.swing.Box.createVerticalStrut(part == help ? 0 : 4));
        }
        notes = panel;
        rebuildOptions();
        options.getAccessibleContext().setAccessibleName("Suchoptionen");
    }

    /** Die Hinweise unter den Häkchen; bleiben beim Neuaufbau des Popups. */
    private JPanel notes;

    /**
     * Baut das Popup neu, in der Reihenfolge der Entscheidung: wo (Quellen), was (Dateitypen), wie (Semantische
     * Suche), darunter Umfang, Dateitypen, Modus und Hinweise. Läuft einmal beim Aufbau und wenn Quellen dazukommen
     * oder wegfallen.
     */
    private void rebuildOptions() {
        options.removeAll();
        if (!sourceBoxes.isEmpty()) {
            options.add(caption("Wissensquellen"));
            sourceBoxes.values().forEach(box -> options.add(menuItem(box)));
            options.addSeparator();
        }
        if (words) {
            options.add(caption(WORD_ORIGIN));
            options.add(menuItem(wordPages));
            options.add(menuItem(wordDocuments));
        } else {
            options.add(caption("Dateitypen"));
            kindBoxes.values().forEach(box -> options.add(menuItem(box)));
        }
        options.addSeparator();
        // Der Hinweis zur semantischen Suche gilt nur in der Seitensuche.
        semanticNote.setVisible(!words);
        kindsLabel.setVisible(!words);
        if (words) {
            // Wortsuche (Angelo 27.11.2026): statt der Seitenmethoden die Worttypen, „Nur Fachbegriffe“ und Synonyme.
            options.add(caption(WORD_KINDS));
            wordKindBoxes.values().forEach(box -> options.add(menuItem(box)));
            options.addSeparator();
            options.add(caption("Suchmethoden"));
            options.add(menuItem(headingsOnly));
            options.add(menuItem(technicalTerms));
            options.add(menuItem(wordSynonyms));
        } else {
            // Die Suchmethoden als Gruppe (UX-Manager 21.11.2026); weitere kommen hier dazu.
            options.add(caption("Suchmethoden"));
            options.add(menuItem(semantic));
            options.add(menuItem(headingsOnly));
            options.add(menuItem(neighbors));
            options.add(menuItem(glossarySynonyms));
            options.add(menuItem(termPages));
        }
        options.add(notes);
        options.revalidate();
    }

    private static JLabel caption(String text) {
        JLabel label = Comic.caption(text);
        label.setBorder(BorderFactory.createEmptyBorder(6, 10, 2, 10));
        return label;
    }

    /**
     * Ein Menüpunkt mit demselben Modell wie das Häkchen: per Pfeiltasten erreichbar, Leertaste/Enter schaltet, das
     * Popup bleibt beim Umschalten offen.
     */
    private static javax.swing.JCheckBoxMenuItem menuItem(JCheckBox box) {
        com.aresstack.spaceharvester.app.ui.controls.PartialCheckMenuItem item =
                new com.aresstack.spaceharvester.app.ui.controls.PartialCheckMenuItem(box.getText());
        item.setModel(box.getModel());
        // Name, Liste der Spaces und „teilweise“ folgen dem Häkchen.
        box.addPropertyChangeListener("text", e -> item.setText(box.getText()));
        box.addPropertyChangeListener(com.aresstack.spaceharvester.app.ui.controls.PartialCheckMenuItem.PARTIAL,
                e -> com.aresstack.spaceharvester.app.ui.controls.PartialCheckMenuItem.partial(item,
                        com.aresstack.spaceharvester.app.ui.controls.PartialCheckMenuItem.partial(box)));
        com.aresstack.spaceharvester.app.ui.controls.PartialCheckMenuItem.partial(item,
                com.aresstack.spaceharvester.app.ui.controls.PartialCheckMenuItem.partial(box));
        item.setToolTipText(box.getToolTipText());
        box.addPropertyChangeListener("ToolTipText", e -> item.setToolTipText(box.getToolTipText()));
        item.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", Boolean.TRUE);
        item.setEnabled(box.isEnabled());
        box.addPropertyChangeListener("enabled", e -> item.setEnabled(box.isEnabled()));
        return item;
    }

    /** Öffnet die Optionen rechtsbündig unter dem Suchfeld; Swing hält das Popup im Bildschirm. */
    void showOptions() {
        if (!searchBar.isShowing()) {
            return;
        }
        java.awt.Dimension size = options.getPreferredSize();
        options.show(searchBar, Math.max(0, searchBar.getWidth() - size.width), searchBar.getHeight());
        // Der erste Menüpunkt ist gleich gewählt, damit die Tastatur sofort darauf wirkt.
        for (java.awt.Component component : options.getComponents()) {
            if (component instanceof javax.swing.MenuElement first) {
                javax.swing.MenuSelectionManager.defaultManager().setSelectedPath(
                        new javax.swing.MenuElement[] {options, first});
                break;
            }
        }
    }

    /** Die Suchleiste für die Kopfzeile des Hauptfensters; es gibt nur dieses eine Suchfeld. */
    public JComponent searchBar() {
        return searchBar;
    }

    /** Läuft, bevor eine Suche aus dem Suchfeld startet; das Hauptfenster wechselt damit zur Trefferansicht. */
    public void onSubmit(Runnable action) {
        this.onSubmit = java.util.Objects.requireNonNull(action, "action");
    }

    /** Der Text im Suchfeld. */
    String queryText() {
        return query.getText();
    }

    @Override
    public JComponent statusInfo() {
        return statusInfo;
    }

    @Override
    public JComponent component() {
        return frame;
    }

    @Override
    public void shown() {
        query.requestFocusInWindow();
    }

    /** Sucht nach einem Text, z. B. aus dem Suchfeld der Kopfleiste. */
    public void search(String text) {
        query.setText(text);
        search();
    }

    /** Wie {@link #scope(List)} für ganze Quellen. */
    void restrictTo(Collection<String> sourceIds) {
        scope(sourceIds.stream().map(id -> new Explorer.Scope(id, null)).toList());
    }

    /**
     * Der Umfang aus der Auswahl im Explorer: die genannten Quellen, bei einem Space nur dieser; leer sucht in allen
     * Wissensquellen. Eine laufende Suche bleibt; die nächste nutzt den neuen Umfang.
     */
    public void scope(List<Explorer.Scope> selection) {
        marked = List.copyOf(selection);
        use(selection);
        if (!lastChange) {
            return;
        }
        if (technicalOn() && termsRuntime != null) {
            // Bereich = links gewählt, auch für „Nur Fachbegriffe“; die Liste beginnt dann oben.
            searchTechnicalTerms();
        } else if (words) {
            // Die Wortsuche richtet sich wie die Seitensuche nach der Auswahl links.
            if (searchedWords != null && searchedWords.equals(query.getText().trim())) {
                searchWords();
            }
        } else if (searchedText != null && searchedText.equals(query.getText().trim())) {
            if (isAddress(searchedText)) {
                search();
            } else {
                filterTo(selection);
            }
        }
    }

    /**
     * Der Baum als Filter auf die eine Trefferliste (Angelo, 23.11.2026): Quellen, die für den Begriff schon durchsucht
     * sind, werden nur gefiltert; fehlende werden allein nachgesucht und eingereiht. Bleibt der Filter dünn und folgen
     * weitere Treffer, lädt die Liste einen Stapel nach. Die Überschrift nennt den Filter.
     */
    void filterTo(List<Explorer.Scope> area) {
        Set<String> wanted = new LinkedHashSet<>(searchedSources());
        Set<String> missing = new LinkedHashSet<>(wanted);
        missing.removeAll(covered);
        if (!missing.isEmpty() && covered.isEmpty()) {
            // Nichts Ganzes durchsucht (die letzte Suche galt nur Spaces oder Seiten): ganz neu suchen.
            search();
            return;
        }
        searchedScopes = List.copyOf(area);
        remember(searchedText, searchedScopes);
        heading.title(HEADING_PREFIX + areaTitle(searchedScopes));
        heading.selected(false);
        applyFilter();
        if (!missing.isEmpty()) {
            searchMissing(missing);
        } else if (table.getRowCount() < THIN && nextCursor != null) {
            loadMore();
        }
    }

    /** Weniger sichtbare Treffer als diese im Filter: einen Stapel nachladen. */
    static final int THIN = 10;
    /** Quellen, die für {@link #searchedText} ganz durchsucht sind; nach einem Abgleich verfällt die Quelle. */
    final Set<String> covered = new LinkedHashSet<>();
    /** Anmeldungen beim Bestand der durchsuchten Quellen, damit ein Abgleich ihre Treffer verfallen lässt. */
    private final List<Subscription> coverWatch = new ArrayList<>();
    private final javax.swing.table.TableRowSorter<HitsModel> sorter = new javax.swing.table.TableRowSorter<>(model);

    /** Zeigt nur die Zeilen im Filter {@link #searchedScopes}. */
    private void applyFilter() {
        List<Explorer.Scope> area = searchedScopes;
        sorter.setRowFilter(area == null || area.isEmpty() ? null : new javax.swing.RowFilter<HitsModel, Integer>() {
            @Override
            public boolean include(Entry<? extends HitsModel, ? extends Integer> entry) {
                int row = entry.getIdentifier();
                return row >= model.rows.size() || inArea(area, model.rows.get(row));
            }
        });
    }

    /** Merkt die ganz durchsuchten Quellen einer startenden Suche und meldet sich für ihren Abgleich an. */
    private void cover(Set<String> sources, boolean replace) {
        if (replace) {
            covered.clear();
            coverWatch.forEach(Subscription::close);
            coverWatch.clear();
        }
        AppRuntime runtime = context.runtime();
        for (String source : sources) {
            if (!covered.add(source) || runtime == null) {
                continue;
            }
            try {
                coverWatch.add(runtime.workspace().subscribeHoldings(source, null,
                        new com.aresstack.spaceharvester.application.holdings.HoldingsListener() {
                            @Override
                            public void pagesChanged(String s, String space, Set<String> pageIds) {
                                // Seiten, die die Suche selbst lädt, ändern die Treffer nicht.
                            }

                            @Override
                            public void spaceChanged(String s, String space) {
                                // Abgleich oder Bereinigen: die gemerkten Treffer dieser Quelle verfallen.
                                SwingUtilities.invokeLater(() -> covered.remove(source));
                            }
                        }));
            } catch (RuntimeException e) {
                // Ohne Anmeldung bleibt die Quelle gemerkt bis zur nächsten Suche.
            }
        }
    }

    /** Sucht den Begriff nur in den noch fehlenden Quellen und reiht die Treffer nach ihrer Stellung ein. */
    private void searchMissing(Set<String> missing) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || lastQuery == null) {
            search();
            return;
        }
        KeywordSearch.Query q = new KeywordSearch.Query(searchedText, missing.size() == 1 ? missing.iterator().next()
                : null, missing.size() > 1 ? missing : Set.of(), Set.of(), Set.of()).ofKinds(chosenKinds())
                .withGlossarySynonyms(synonymsOn()).withHeadingsOnly(headingsOnly.isSelected());
        // Eigene Anfragenummer: Antworten der vorigen Suche (Neusortieren, Nachbarn, Ladestand) dürfen die
        // nachgesuchte Quelle nicht mehr überschreiben. Bis 23.11.2026 kam ein spätes Neusortieren der alten Quelle
        // nach dem ersten Stapel an, setzte deren (leeren) Cursor und „Weitere laden“ blieb aus (CI 45213208517).
        int before = request;
        int id = ++request;
        if (neighborsFor == before) {
            neighborsFor = id;
        }
        if (termPagesFor == before) {
            termPagesFor = id;
        }
        cover(missing, false);
        starting("Suche in " + String.join(", ", missing.stream().map(this::title).toList()) + " …");
        String core = java.util.UUID.randomUUID().toString();
        Background.run(() -> runtime.workspace().keywordSearchIn(core, q, null, LIMIT, null), results -> {
            overlay.setBusy(false);
            if (id != request) {
                return;
            }
            Hit selected = selectedHit();
            model.set(interleave(searchedText, model.rows, hits(results)));
            batchStart = 0;
            select(selected);
            // „Weitere laden“ setzt ab jetzt die Suche der nachgesuchten Quellen fort.
            keywordSearchId = core;
            shownCursor = null;
            show(q, results);
            if (results.progress() != null && !results.progress().done()) {
                track(results.progress());
            } else {
                finished(false);
            }
        }, error -> {
            overlay.setBusy(false);
            missing.forEach(covered::remove);
            feedback.problem(error);
        });
    }

    /**
     * Führt zwei nach Rang geordnete Listen zusammen (Angelo, 23.11.2026): Die Ränge zweier Suchen sind nicht
     * vergleichbar, darum entscheidet an jeder Stelle der Punktwert des Core ({@code HitRelevance}) zwischen den beiden
     * vordersten Treffern; die Reihenfolge innerhalb jeder Liste bleibt. Bei Gleichstand gewinnt die erste Liste.
     * Doppelte Seiten bleiben einmal, an der früheren Stelle.
     */
    static List<Hit> interleave(String query, List<Hit> first, List<Hit> second) {
        List<Hit> merged = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        int a = 0;
        int b = 0;
        while (a < first.size() || b < second.size()) {
            Hit next;
            if (b >= second.size() || a < first.size() && relevance(query, first.get(a)) >= relevance(query,
                    second.get(b))) {
                next = first.get(a++);
            } else {
                next = second.get(b++);
            }
            if (seen.add(next.sourceId() + "\u0000" + next.pageId() + "\u0000" + next.passageId())) {
                merged.add(next);
            }
        }
        return merged;
    }

    static double relevance(String query, Hit hit) {
        return com.aresstack.spaceharvester.application.query.HitRelevance.of(query, hit.title(), hit.snippet());
    }

    /** Übernimmt einen Bereich für die nächste Suche, ohne selbst zu suchen. */
    private void use(List<Explorer.Scope> selection) {
        // Erst die Häkchen, dann der Umfang: er liest die angehakten Quellen.
        Set<String> known = knownSources();
        syncBoxesToScope(selection.stream().filter(scope -> scope.sourceId() != null
                && known.contains(scope.sourceId())).distinct().toList());
        applyScope(selection);
    }

    /**
     * Die auszuschließenden Spaces einer gemischten Auswahl: links eine Quelle ganz und von einer anderen nur einzelne
     * Spaces. Die Anfrage kennt nur eine Liste von Spaces für alle Quellen; darum fallen bei der teilweise gewählten
     * Quelle ihre übrigen Spaces weg, die ganze bleibt ganz. Ein Space-Schlüssel, den auch eine ganz gewählte Quelle
     * hat, wird nicht ausgeschlossen (lieber etwas mehr als dort etwas weniger). Leer, wenn nicht gemischt.
     *
     * @param spacesOf die bekannten Spaces einer Quelle (Bestand)
     */
    static Set<String> exclusions(List<Explorer.Scope> scopes, Set<String> searched,
                                  java.util.function.Function<String, ? extends Collection<String>> spacesOf) {
        Map<String, Set<String>> partial = new LinkedHashMap<>();
        Set<String> whole = new LinkedHashSet<>();
        for (Explorer.Scope scope : scopes) {
            if (!searched.contains(scope.sourceId())) {
                continue;
            }
            if (scope.spaceKey() == null) {
                whole.add(scope.sourceId());
            } else {
                partial.computeIfAbsent(scope.sourceId(), id -> new LinkedHashSet<>()).add(scope.spaceKey());
            }
        }
        partial.keySet().removeAll(whole);
        if (partial.isEmpty() || whole.isEmpty()) {
            return Set.of();
        }
        Set<String> excluded = new TreeSet<>();
        partial.forEach((source, chosenSpaces) -> spacesOf.apply(source).stream()
                .filter(space -> !chosenSpaces.contains(space)).forEach(excluded::add));
        whole.forEach(source -> excluded.removeAll(spacesOf.apply(source)));
        return Set.copyOf(excluded);
    }

    /** Höchstens so viele Spaces nennt eine teilweise gewählte Quelle hinter ihrem Namen, dann „+n“. */
    static final int MAX_LISTED_SPACES = 3;

    /**
     * Die Häkchen unter „Wissensquellen“ folgen der Auswahl links (Angelo, 22.11.2026): ohne Auswahl (Überschrift
     * „Wissensquellen“) alle angehakt, eine ganz gewählte Quelle angehakt, eine Quelle mit nur einzelnen gewählten
     * Spaces teilweise (dritter Zustand) mit den Spaces hinter dem Namen, alle übrigen abgehakt.
     */
    private void syncBoxesToScope(List<Explorer.Scope> scopes) {
        syncingBoxes = true;
        try {
            sourceBoxes.forEach((id, box) -> {
                List<String> spaces = scopes.stream().filter(scope -> id.equals(scope.sourceId()))
                        .map(Explorer.Scope::spaceKey).toList();
                boolean whole = scopes.isEmpty() ? globalSearchSource(id) : spaces.contains(null);
                boolean partial = !whole && !spaces.isEmpty();
                box.setSelected(whole || partial);
                com.aresstack.spaceharvester.app.ui.controls.PartialCheckMenuItem.partial(box, partial);
                box.setText(boxText(sourceTitle(box), partial ? spaces : List.of()));
            });
        } finally {
            syncingBoxes = false;
        }
    }

    /** „Confluence HaVdB · CL2, CL3“; ohne Spaces nur der Name. */
    static String boxText(String title, List<String> spaces) {
        if (spaces.isEmpty()) {
            return title;
        }
        List<String> listed = spaces.subList(0, Math.min(spaces.size(), MAX_LISTED_SPACES));
        String more = spaces.size() > MAX_LISTED_SPACES ? " +" + (spaces.size() - MAX_LISTED_SPACES) : "";
        return title + " · " + String.join(", ", listed) + more;
    }

    private static String sourceTitle(JCheckBox box) {
        Object title = box.getClientProperty(SOURCE_TITLE);
        return title instanceof String text ? text : box.getText();
    }

    /** Client-Property des Häkchens einer Quelle: ihr Name ohne Liste der Spaces. */
    private static final String SOURCE_TITLE = "SearchView.sourceTitle";

    /**
     * Rückmeldung: Ein an- oder abgehaktes Häkchen unter „Wissensquellen“ wählt links dieselben Quellen (Angelo,
     * 22.11.2026). Anhaken wählt die ganze Quelle, auch eine teilweise gewählte; Abhaken nimmt die Quelle samt ihren
     * Spaces heraus. Alle ganz angehakt heißt „Wissensquellen“. Die letzte Quelle lässt sich nicht abhaken.
     */
    private void sourceChecked(String id) {
        JCheckBox box = sourceBoxes.get(id);
        boolean wasPartial = com.aresstack.spaceharvester.app.ui.controls.PartialCheckMenuItem.partial(box);
        boolean whole = wasPartial || box.isSelected();
        List<Explorer.Scope> base = scopes.isEmpty()
                ? sourceBoxes.keySet().stream().filter(this::globalSearchSource)
                        .map(source -> new Explorer.Scope(source, null)).toList()
                : scopes;
        List<Explorer.Scope> next = new ArrayList<>(base.stream().filter(scope -> !id.equals(scope.sourceId()))
                .toList());
        if (whole) {
            next.add(new Explorer.Scope(id, null));
        }
        if (next.isEmpty()) {
            // Nichts mehr zu durchsuchen wäre „alles“ links: das Häkchen bleibt.
            syncBoxesToScope(scopes);
            return;
        }
        boolean all = new java.util.HashSet<>(next).equals(sourceBoxes.keySet().stream()
                .filter(this::globalSearchSource).map(source -> new Explorer.Scope(source, null))
                .collect(java.util.stream.Collectors.toSet()));
        List<Explorer.Scope> wanted = all ? List.of() : List.copyOf(next);
        if (sourcesListener != null) {
            // Der Baum wählt und meldet die Auswahl zurück ({@link #scope}).
            sourcesListener.accept(wanted);
        }
        scope(wanted);
    }

    /** Wer links die Quellen wählt, die in der Suche angehakt werden (das Hauptfenster: der Explorer). */
    public void onSourcesChecked(java.util.function.Consumer<List<Explorer.Scope>> listener) {
        this.sourcesListener = listener;
    }

    /** Wer links den Bereich der Suche wählt, wenn ihre Überschrift angeklickt wird (das Hauptfenster: der Explorer). */
    public void onLocate(java.util.function.Consumer<List<Explorer.Scope>> listener) {
        this.locateListener = listener;
    }

    /** Der Bereich als Text für die Überschrift: „Wissensquellen“, eine Quelle oder „Quelle › Space“. */
    /** Name einer früheren Suche im Verlauf: Begriff und Bereich. */
    private String visitName(Visit visit) {
        String area = areaTitle(visit.area());
        return visit.text().isBlank() ? area : "„" + visit.text().strip() + "“ in " + area;
    }

    String areaTitle(List<Explorer.Scope> area) {
        if (area.isEmpty()) {
            return "Wissensquellen";
        }
        if (area.size() > MAX_NAMED_AREAS) {
            // Mehrfachauswahl (Strg/Shift): ab vier Knoten nur die Zahl; ein Klick markiert alle wieder im Baum.
            return area.size() + " Bereichen";
        }
        return String.join(", ", area.stream().map(scope -> scope.pageId() != null
                ? (scope.pageTitle() == null ? scope.pageId() : scope.pageTitle())
                : title(scope.sourceId()) + (scope.spaceKey() == null ? "" : " › " + scope.spaceKey())).toList());
    }

    /**
     * Merkt den Bereich einer startenden Suche für Überschrift und Wiederholen und sucht abgesetzt nach Seiten, deren
     * Titel dem Text entspricht (nur die Titelsuche der Quellen, die Suche selbst bleibt, wie sie ist).
     */
    private void started(AppRuntime runtime, String text) {
        searchedScopes = List.copyOf(scopes);
        // Ganz durchsucht sind nur Quellen ohne Space- oder Seitengrenze; der Filter braucht sie nicht neu.
        Set<String> whole = new LinkedHashSet<>(searchedSources());
        scopes.stream().filter(scope -> scope.spaceKey() != null || scope.pageId() != null)
                .forEach(scope -> whole.remove(scope.sourceId()));
        cover(whole, true);
        applyFilter();
        heading.title(HEADING_PREFIX + areaTitle(searchedScopes));
        heading.selected(false);
        repeat.setEnabled(true);
        pinned.clear();
        int id = ++titleRequest;
        // Global ist chosenSources() leer (Anfrage über alle); die Titelsuche fragt jede durchsuchte Quelle einzeln.
        Set<String> sources = searchedSources();
        Set<String> spaces = chosenSpaces();
        List<Explorer.Scope> area = searchedScopes;
        List<String> issueAddresses = issueAddresses(text);
        // Eine Seitennummer (Angelo, 27.11.2026) fragt jede Quelle per Lesezeichen, wie ein Vorgangsschlüssel.
        boolean byPageId = issueAddresses.isEmpty();
        List<String> keyAddresses = byPageId ? pageIdAddresses(configuredSources(), text) : issueAddresses;
        String keyCaption = byPageId ? PinnedHits.PAGE_ID : PinnedHits.TICKET;
        Background.run(() -> {
            // Ein Vorgangsschlüssel (HAVDBAR-86012) ist bei Jira eine Adresse unter …/browse/KEY (Angelo, 23.11.2026).
            List<Hit> tickets = new ArrayList<>();
            for (String address : keyAddresses) {
                try {
                    runtime.workspace().resolveAddress(address).matches().stream().map(Hit::of)
                            .filter(hit -> inArea(area, hit) && sources.contains(hit.sourceId())
                                    && !tickets.contains(hit))
                            .forEach(tickets::add);
                } catch (RuntimeException e) {
                    // Nicht erreichbar: dann eben ohne Ticket oben; die Suche selbst meldet Fehler.
                }
            }
            return tickets.isEmpty() ? java.util.Map.entry(PinnedHits.TITLES, titleHits(runtime, sources, spaces, text)
                    .stream().filter(hit -> inArea(area, hit)).toList())
                    : java.util.Map.entry(keyCaption, (List<Hit>) tickets);
        }, found -> {
            if (id == titleRequest) {
                pinned.show(found.getKey(), found.getValue());
            }
        }, error -> { });
    }

    /** Ein Jira-Vorgangsschlüssel wie {@code HAVDBAR-86012}. */
    static final java.util.regex.Pattern ISSUE_KEY = java.util.regex.Pattern.compile("(?i)[A-Z][A-Z0-9_]*-[0-9]{1,12}");

    /**
     * Zu einem Vorgangsschlüssel die Adressen {@code <Basis-URL>/browse/KEY} aller eingetragenen Quellen mit Basis-URL
     * (bei Jira unter {@code options.baseUrl}); welche Quelle sie kennt, entscheidet die Adressauflösung des Core.
     */
    List<String> issueAddresses(String text) {
        String key = text == null ? "" : text.trim();
        if (!ISSUE_KEY.matcher(key).matches() || context.settings() == null) {
            return List.of();
        }
        Set<String> addresses = new LinkedHashSet<>();
        for (var entry : context.settings().sources()) {
            var config = entry.config();
            String base = config.baseUrl() != null ? config.baseUrl().toString() : config.options().get("baseUrl");
            if (base != null && !base.isBlank()) {
                addresses.add(base.replaceAll("/+$", "") + "/browse/" + key.toUpperCase(java.util.Locale.ROOT));
            }
        }
        return List.copyOf(addresses);
    }

    /** Eine Seitennummer, wie Confluence und andere Quellen sie als Seiten-ID führen (nur Ziffern). */
    static final java.util.regex.Pattern PAGE_ID = java.util.regex.Pattern.compile("[0-9]{1,19}");

    /**
     * Zu einer Seitennummer die Lesezeichen {@code space-harvester://<Quelle>/page/<Nummer>} aller eingetragenen
     * Quellen; jede Quelle löst sie gleich auf (aus dem Bestand, sonst gezielt live), keine Sonderfälle je Quellentyp.
     * Auch der Titelfilter im Explorer nutzt das.
     */
    static List<String> pageIdAddresses(List<String> sourceIds, String text) {
        String id = PageAddresses.normalize(text);
        if (id == null || !PAGE_ID.matcher(id).matches()) {
            return List.of();
        }
        List<String> addresses = new ArrayList<>();
        for (String sourceId : sourceIds) {
            try {
                addresses.add(new com.aresstack.spaceharvester.application.query.PageBookmark(sourceId, id, null)
                        .value());
            } catch (IllegalArgumentException unusable) {
                // Quellen-ID taugt nicht für Lesezeichen: diese Quelle fällt weg.
            }
        }
        return List.copyOf(addresses);
    }

    /** Die IDs der eingetragenen Quellen, in der Reihenfolge der Einstellungen. */
    private List<String> configuredSources() {
        return context.settings() == null ? List.of()
                : context.settings().sources().stream().map(entry -> entry.id()).toList();
    }

    /**
     * Ob ein Treffer im Bereich liegt: alles bei leerem Bereich, sonst eine gewählte Quelle ganz oder ihr gewählter
     * Space (bei gemischter Auswahl hat die Anfrage nur eine Space-Liste für alle Quellen).
     */
    static boolean inArea(List<Explorer.Scope> area, Hit hit) {
        return area == null || area.isEmpty() || area.stream().anyMatch(scope -> scope.sourceId().equals(hit.sourceId())
                && (scope.spaceKey() == null || scope.spaceKey().equals(hit.spaceKey())));
    }

    /**
     * Statuszeile einer Quelle ohne Online-Suche: Gesucht wurde nur im Index; was nicht indexiert ist, muss der Benutzer
     * erst indexieren (Angelo 27.11.2026).
     */
    static final String NOT_ONLINE_SEARCHABLE = "keine Online-Suche, nur Indexiertes durchsucht – zum Durchsuchen "
            + "zuerst indexieren (Rechtsklick › " + Explorer.INDEX + ")";

    /** Titel der Rückfrage, ob eine nicht durchsuchbare Quelle indexiert werden soll. */
    static final String INDEX_OFFER_TITLE = "Quelle indexieren?";

    /**
     * Die Rückfrage (Angelo 27.11.2026): Eine Quelle ohne Online-Suche findet die Suche nur, soweit sie indexiert ist.
     * Ist nichts davon indexiert, fragt die App einmal je Sitzung, ob jetzt indexiert werden soll; nur nach „Ja“ startet
     * der Auftrag, und er lässt sich unter Aufträge abbrechen.
     */
    static String indexOfferQuestion(String title) {
        return "„" + title + "“ kann nicht online durchsucht werden und ist noch nicht indexiert. Die Suche findet ihre "
                + "Inhalte erst, wenn sie indexiert sind. Das Indexieren läuft als Auftrag, den du unter Aufträge "
                + "jederzeit abbrechen kannst. Quelle wirklich jetzt indexieren?";
    }

    /** Fragt, ob eine Quelle indexiert werden soll; {@code true} nur nach „Ja“. */
    interface IndexOffer {
        boolean accepted(String sourceTitle);
    }

    /** Quellen, denen das Indexieren in dieser Sitzung schon angeboten wurde: jede höchstens einmal. */
    private final Set<String> indexOffered = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Die Rückfrage; ohne Bildschirm (Tests) wird nie gefragt. */
    IndexOffer indexOffer = title -> !java.awt.GraphicsEnvironment.isHeadless()
            && Dialogs.confirm(component(), INDEX_OFFER_TITLE, indexOfferQuestion(title));

    /**
     * Bietet das Indexieren für jede Quelle ohne Online-Suche ({@code INDEXED}) an, die nirgends zum Indexieren markiert
     * ist. Das Nachsehen liest nur die Markierungen; nach „Ja“ startet derselbe Auftrag wie „Indexieren“ im Baum.
     */
    private void offerIndexing(List<KeywordSearch.SourceState> sources) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        List<String> candidates = new ArrayList<>();
        for (KeywordSearch.SourceState state : sources) {
            if (state.status() == KeywordSearch.SourceState.Status.INDEXED && indexOffered.add(state.sourceId())) {
                candidates.add(state.sourceId());
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        Background.run(() -> notIndexed(candidates, id -> indexedAnywhere(runtime, id), indexOffered::remove),
                notIndexed -> {
                    for (String sourceId : notIndexed) {
                        if (indexOffer.accepted(title(sourceId))) {
                            context.navigator().index(sourceId, null, null);
                        }
                    }
                }, error -> candidates.forEach(indexOffered::remove));
    }

    /**
     * Die Kandidaten, die nirgends zum Indexieren markiert sind. Jede Quelle wird für sich geprüft: Ist eine gerade
     * nicht erreichbar (etwa ein fehlendes Verzeichnis), fragt die App die übrigen trotzdem, und diese eine wird bei
     * einer späteren Suche erneut geprüft statt als „schon angeboten“ zu gelten.
     */
    static List<String> notIndexed(List<String> candidates, java.util.function.Predicate<String> indexed,
                                   java.util.function.Consumer<String> unavailable) {
        List<String> notIndexed = new ArrayList<>();
        for (String sourceId : candidates) {
            try {
                if (!indexed.test(sourceId)) {
                    notIndexed.add(sourceId);
                }
            } catch (RuntimeException failure) {
                unavailable.accept(sourceId);
            }
        }
        return notIndexed;
    }

    /** Ob in irgendeinem Space der Quelle etwas zum Indexieren markiert ist. */
    static boolean indexedAnywhere(AppRuntime runtime, String sourceId) {
        return runtime.workspace().spaces(sourceId).stream()
                .anyMatch(space -> runtime.workspace().indexedAnywhere(sourceId, space.key()));
    }

    /** Höchstens so viele Titeltreffer stehen oben. */
    static final int MAX_TITLE_HITS = 5;

    private static List<Hit> titleHits(AppRuntime runtime, Set<String> sources, Set<String> spaces, String text) {
        List<Hit> hits = new ArrayList<>();
        for (String source : sources) {
            // Die Titelsuche der Quellen sucht Teilzeichenfolgen: für Tippfehler und Satzzeichen auch das längste Wort.
            for (String term : titleTerms(text)) {
                try {
                    for (HarvestSource.PageNode page : runtime.workspace().findByTitle(source, spaces, term, 20)
                            .pages()) {
                        Hit hit = new Hit(source, page.pageId(), page.title(), page.partitionKey(), "", null);
                        if (similarTitle(page.title(), text) && hits.stream().noneMatch(known
                                -> known.sourceId().equals(source) && known.pageId().equals(page.pageId()))) {
                            hits.add(hit);
                        }
                    }
                } catch (RuntimeException e) {
                    // Ohne Titelsuche der Quelle bleibt der Bereich leer; die Suche selbst meldet Fehler.
                }
            }
        }
        hits.sort(java.util.Comparator.comparingInt(hit -> titleDistance(hit.title(), text)));
        return hits.size() > MAX_TITLE_HITS ? List.copyOf(hits.subList(0, MAX_TITLE_HITS)) : hits;
    }

    /** Die Anfragen an die Titelsuche: der Text und, wenn er mehrere Wörter hat, sein längstes Wort. */
    static List<String> titleTerms(String text) {
        String trimmed = text.trim();
        String longest = java.util.Arrays.stream(normalTitle(trimmed).split(" "))
                .max(java.util.Comparator.comparingInt(String::length)).orElse("");
        return longest.length() >= 4 && !longest.equalsIgnoreCase(trimmed) ? List.of(trimmed, longest)
                : List.of(trimmed);
    }

    /** Titel ohne Groß-/Kleinschreibung, Satzzeichen und mehrfache Leerzeichen. */
    static String normalTitle(String text) {
        return text == null ? "" : text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** Abstand zweier Titel nach Bearbeitungsschritten (Levenshtein) über die normalisierte Form. */
    static int titleDistance(String title, String text) {
        String a = normalTitle(title);
        String b = normalTitle(text);
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    /**
     * Ob ein Titel dem Suchtext genau oder sehr ähnlich entspricht: gleich bis auf Schreibung und Satzzeichen, wenige
     * Tippfehler (ein Schritt je fünf Zeichen) oder der Text macht den Titel zu mindestens 70 % aus.
     */
    static boolean similarTitle(String title, String text) {
        String a = normalTitle(title);
        String b = normalTitle(text);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b) || titleDistance(a, b) <= Math.max(1, b.length() / 5)) {
            return true;
        }
        return a.contains(b) && b.length() * 10 >= a.length() * 7;
    }

    /** Wiederholt die letzte Suche in ihrem gemerkten Bereich, nicht im links gewählten (Angelo, 23.11.2026). */
    void repeat() {
        if (searchedScopes == null || searchedText == null) {
            return;
        }
        query.setText(searchedText);
        use(searchedScopes);
        search();
    }


    /** Eine frühere Suche: Begriff, Bereich (Filter) und wie weit die Liste gescrollt war. */
    record Visit(String text, List<Explorer.Scope> area, int scroll) {
        Visit(String text, List<Explorer.Scope> area) {
            this(text, area, 0);
        }

        boolean same(Visit other) {
            return text.equals(other.text) && area.equals(other.area);
        }
    }

    private javax.swing.JViewport viewport() {
        java.awt.Container parent = table.getParent();
        return parent instanceof javax.swing.JViewport port ? port : null;
    }

    /** Merkt die Scrollposition der gezeigten Suche, bevor eine andere kommt. */
    private void keepScroll() {
        javax.swing.JViewport port = viewport();
        if (visit >= 0 && port != null) {
            Visit shown = visits.get(visit);
            visits.set(visit, new Visit(shown.text(), shown.area(), port.getViewPosition().y));
        }
    }

    /** Die Suchen dieser Sitzung in Reihenfolge; {@link #visit} zeigt auf die gezeigte. */
    final List<Visit> visits = new ArrayList<>();
    int visit = -1;
    /** Während Zurück/Vor eine frühere Suche wiederholt, kommt sie nicht neu in den Verlauf. */
    private boolean revisiting;
    private boolean restoringHistory;
    private java.util.function.Consumer<SearchLocation> searchLocationListener = location -> { };

    record SearchLocation(String text, List<Explorer.Scope> area, boolean words, boolean technical, boolean semantic,
                          boolean neighbors, boolean synonyms, boolean termPages, Set<DocumentKind> kinds, String label,
                          Set<WordSearch.Kind> wordKinds, boolean wordSynonyms, boolean headingsOnly,
                          boolean wordPages, boolean wordDocuments) {
        SearchLocation {
            area = List.copyOf(area);
            kinds = Set.copyOf(kinds);
            wordKinds = Set.copyOf(wordKinds);
        }
    }

    void onSearchLocation(java.util.function.Consumer<SearchLocation> listener) {
        searchLocationListener = java.util.Objects.requireNonNull(listener);
    }

    private void publishSearchLocation(String text, List<Explorer.Scope> area) {
        if (!restoringHistory) {
            Set<DocumentKind> selectedKinds = kindBoxes.entrySet().stream()
                    .filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            searchLocationListener.accept(new SearchLocation(text, area, words, technicalTerms.isSelected(),
                    semantic.isSelected(), neighbors.isSelected(), glossarySynonyms.isSelected(), termPages.isSelected(),
                    selectedKinds, "Suche: " + visitName(new Visit(text, area)), chosenWordKinds(),
                    wordSynonyms.isSelected(), headingsOnly.isSelected(),
                    wordPages.isSelected(), wordDocuments.isSelected()));
        }
    }

    void restoreSearchLocation(SearchLocation location) {
        restoringHistory = true;
        revisiting = true;
        try {
            marked = location.area();
            use(location.area());
            query.setText(location.text());
            if (location.words() != words) {
                switchMode(location.words());
            }
            technicalTerms.setSelected(location.technical());
            wordKindBoxes.forEach((kind, box) -> box.setSelected(location.wordKinds().contains(kind)));
            wordSynonyms.setSelected(location.wordSynonyms());
            wordPages.setSelected(location.wordPages());
            wordDocuments.setSelected(location.wordDocuments());
            semantic.setSelected(location.semantic());
            neighbors.setSelected(location.neighbors());
            glossarySynonyms.setSelected(location.synonyms());
            termPages.setSelected(location.termPages());
            headingsOnly.setSelected(location.headingsOnly());
            kindBoxes.forEach((kind, box) -> box.setSelected(location.kinds().contains(kind)));
            search();
        } finally {
            revisiting = false;
            restoringHistory = false;
        }
    }

    /** Merkt eine startende Suche im Verlauf; Vor-Einträge hinter der gezeigten fallen weg. */
    private void remember(String text, List<Explorer.Scope> area) {
        publishSearchLocation(text, area);
        if (revisiting) {
            return;
        }
        Visit next = new Visit(text, List.copyOf(area));
        if (visit >= 0 && visits.get(visit).same(next)) {
            return;
        }
        keepScroll();
        while (visits.size() > visit + 1) {
            visits.remove(visits.size() - 1);
        }
        visits.add(next);
        visit = visits.size() - 1;
        updateHistoryButtons();
    }

    boolean canGoBack() {
        return visit > 0;
    }

    boolean canGoForward() {
        return visit >= 0 && visit < visits.size() - 1;
    }

    /** Zurück zur vorigen Suche (Begriff und Bereich). */
    void back() {
        if (canGoBack()) {
            revisit(visit - 1);
        }
    }

    /** Vor zur nächsten Suche im Verlauf. */
    void forward() {
        if (canGoForward()) {
            revisit(visit + 1);
        }
    }

    private void revisit(int index) {
        keepScroll();
        visit = index;
        Visit target = visits.get(index);
        revisiting = true;
        try {
            query.setText(target.text());
            use(target.area());
            if (target.text().equals(searchedText)) {
                filterTo(target.area());
                javax.swing.JViewport port = viewport();
                if (port != null) {
                    SwingUtilities.invokeLater(() -> port.setViewPosition(new java.awt.Point(0, target.scroll())));
                }
            } else {
                search();
            }
        } finally {
            revisiting = false;
        }
        updateHistoryButtons();
    }

    private void updateHistoryButtons() {
        backVisit.refresh();
    }

    /** Übernimmt den Umfang; die Häkchen setzt {@link #syncBoxesToScope}. */
    private void applyScope(List<Explorer.Scope> selection) {
        Set<String> known = knownSources();
        List<Explorer.Scope> before = scopes;
        scopes = selection.stream().filter(scope -> scope.sourceId() != null && known.contains(scope.sourceId()))
                .distinct().toList();
        chosen.clear();
        scopes.forEach(scope -> chosen.add(scope.sourceId()));
        Set<String> searched = searchedSources();
        if (searched.isEmpty()) {
            scopeLabel.setText("In: keine Wissensquelle angehakt");
        } else if (scopes.isEmpty()) {
            scopeLabel.setText(searched.size() == sourceBoxes.size() ? "In: Alle Wissensquellen"
                    : "In: " + String.join(", ", searched.stream().map(this::title).toList()));
        } else {
            scopeLabel.setText("In: " + String.join(", ", scopes.stream()
                    .filter(scope -> searched.contains(scope.sourceId()))
                    .map(scope -> title(scope.sourceId())
                            + (scope.spaceKey() == null ? "" : " › " + scope.spaceKey())).toList()));
        }
        watchLocalShare();
        lastChange = !scopes.equals(before);
    }

    /** Ob der letzte {@link #applyScope} den Bereich geändert hat. */
    private boolean lastChange;

    /**
     * Zählt für die Statuszeile, wie viel des Bereichs lokal liegt (K6), und meldet sich beim Bestand der berührten
     * Quellen bzw. Spaces an, damit Laden, Abgleich oder Bereinigen die Zahl nachführen; kein Zeitgeber.
     */
    private void watchLocalShare() {
        AppRuntime runtime = context.runtime();
        com.aresstack.spaceharvester.application.holdings.Selection area = runtime == null ? null
                : LocalShare.selection(searchedSources(), scopes);
        if (runtime == localShareRuntime && java.util.Objects.equals(area, localShareArea)) {
            return;
        }
        localShareRuntime = runtime;
        localShareArea = area;
        localShareWatch.forEach(Subscription::close);
        localShareWatch.clear();
        localShareRequest++;
        localShareAgain = false;
        if (area == null || area.roots().isEmpty()) {
            localShare.setText(" ");
            return;
        }
        com.aresstack.spaceharvester.application.holdings.HoldingsListener listener =
                new com.aresstack.spaceharvester.application.holdings.HoldingsListener() {
                    @Override
                    public void pagesChanged(String source, String space, Set<String> pageIds) {
                        SwingUtilities.invokeLater(SearchView.this::countLocalShare);
                    }

                    @Override
                    public void spaceChanged(String source, String space) {
                        SwingUtilities.invokeLater(SearchView.this::countLocalShare);
                    }
                };
        for (com.aresstack.spaceharvester.application.holdings.Selection.Root root : area.roots()) {
            try {
                localShareWatch.add(runtime.workspace().subscribeHoldings(root.sourceId(), root.spaceKey(),
                        listener));
            } catch (RuntimeException gone) {
                // Die Quelle gibt es in dieser Laufzeit nicht mehr; der nächste Bereich räumt sie weg.
            }
        }
        localShareCounting = false;
        countLocalShare();
    }

    /** Liest die Zahlen aus dem Bestand im Hintergrund; läuft schon eine Zählung, folgt genau eine weitere. */
    private void countLocalShare() {
        AppRuntime runtime = context.runtime();
        com.aresstack.spaceharvester.application.holdings.Selection area = localShareArea;
        if (runtime == null || area == null || area.roots().isEmpty()) {
            return;
        }
        if (localShareCounting) {
            localShareAgain = true;
            return;
        }
        localShareCounting = true;
        int id = localShareRequest;
        Background.run(() -> LocalShare.line(runtime.workspace().resolveSelection(area)), line -> {
            if (id != localShareRequest) {
                return;
            }
            localShareCounting = false;
            localShare.setText(line.isEmpty() ? " " : line);
            if (localShareAgain) {
                localShareAgain = false;
                countLocalShare();
            }
        }, error -> {
            if (id != localShareRequest) {
                return;
            }
            localShareCounting = false;
            localShare.setText(" ");
            LOG.log(System.Logger.Level.INFO, "Bestand des Suchbereichs nicht gezählt ({0})",
                    error.getClass().getName());
        });
    }

    /**
     * Die Quellen, in denen die nächste Suche sucht: die angehakten, bei einer Auswahl im Explorer nur die gewählten
     * darunter. Leer, wenn keine angehakte Quelle übrig bleibt; dann sucht die Ansicht nicht.
     */
    Set<String> searchedSources() {
        Set<String> checked = new LinkedHashSet<>();
        sourceBoxes.forEach((id, box) -> {
            if (box.isSelected() && (chosen.isEmpty() || chosen.contains(id))) {
                checked.add(id);
            }
        });
        return checked;
    }

    /**
     * Die Quellen für die Anfrage an den Core; leer heißt alle (globale Suche, ADR 0009), also ohne Auswahl im
     * Explorer und mit allen Quellen angehakt.
     */
    Set<String> chosenSources() {
        Set<String> searched = searchedSources();
        return chosen.isEmpty() && searched.equals(sourceBoxes.keySet().stream().filter(this::globalSearchSource)
                .collect(java.util.stream.Collectors.toSet())) ? Set.of() : searched;
    }

    /**
     * Die Spaces, in denen die nächste Suche sucht, aus der Auswahl im Explorer unter den angehakten Quellen; leer
     * heißt alle. Eine ganze angehakte Quelle in der Auswahl: alle ihre Spaces, kein Space-Filter für die anderen.
     */
    Set<String> chosenSpaces() {
        Set<String> searched = searchedSources();
        Set<String> spaces = new TreeSet<>();
        for (Explorer.Scope scope : scopes) {
            if (!searched.contains(scope.sourceId())) {
                continue;
            }
            if (scope.spaceKey() == null) {
                return Set.of();
            }
            spaces.add(scope.spaceKey());
        }
        return Set.copyOf(spaces);
    }

    /** Die Dokumentarten der nächsten Suche; leer heißt alle. Ohne jedes Häkchen sucht die Ansicht nicht. */
    Set<DocumentKind> chosenKinds() {
        Set<DocumentKind> checked = java.util.EnumSet.noneOf(DocumentKind.class);
        kindBoxes.forEach((kind, box) -> {
            if (box.isSelected()) {
                checked.add(kind);
            }
        });
        return checked.size() == kindBoxes.size() ? Set.of() : checked;
    }

    /** Ob mindestens eine Dokumentart angehakt ist. */
    private boolean anyKind() {
        return kindBoxes.values().stream().anyMatch(JCheckBox::isSelected);
    }

    /** Nennt die Dateitypen der nächsten Suche unter dem Umfang. */
    private void kinds() {
        Set<DocumentKind> checked = chosenKinds();
        kindsLabel.setText(!anyKind() ? "Dateitypen: keiner angehakt" : checked.isEmpty() ? "Dateitypen: alle"
                : "Dateitypen: " + String.join(", ", checked.stream().map(DocumentKind::label).toList()));
    }

    private boolean globalSearchSource(String id) {
        AppRuntime runtime = context.runtime();
        return runtime == null || runtime.settings().sources().stream()
                .filter(entry -> entry.id().equals(id))
                .allMatch(entry -> entry.config().includeInGlobalSearch());
    }

    private Set<String> knownSources() {
        AppRuntime runtime = context.runtime();
        return runtime == null ? Set.of() : runtime.workspace().sources().stream().map(HarvestSource::id)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** Woher die Varianten der semantischen Suche kommen; entfernte Quellen fallen aus dem Umfang. */
    private void refreshSources() {
        AppRuntime runtime = context.runtime();
        syncSourceBoxes(runtime);
        boolean available = runtime != null && runtime.workspace().semanticRanking();
        semantic.setEnabled(available);
        if (!available) {
            semantic.setSelected(false);
        }
        semantic.setToolTipText(available || runtime == null
                ? semanticTip(runtime == null ? null : available, runtime != null && runtime.workspace().aiVariants())
                : lockedTip(runtime.settings().ai()));
        // Die Nachbarn kommen aus dem lokalen Bestand; ohne Laufzeit gibt es keinen.
        neighbors.setEnabled(runtime != null);
        neighbors.setToolTipText(runtime != null ? NEIGHBORS_TIP
                : "Gesperrt: keine Wissensquelle eingerichtet, also keine Seiten mit Nachbarn");
        glossarySynonyms.setEnabled(runtime != null);
        glossarySynonyms.setToolTipText(runtime != null ? SYNONYMS_TIP
                : "Gesperrt: keine Wissensquelle eingerichtet, also kein Glossar");
        termPages.setEnabled(runtime != null);
        termPages.setToolTipText(runtime != null ? TERM_PAGES_TIP
                : "Gesperrt: keine Wissensquelle eingerichtet, also kein Wissensgraph");
        String tip = semantic.getToolTipText();
        semanticNote.setText(tip == null ? " " : "<html><div style='width:320px'>" + tip + "</div></html>");
        mode();
        scope(scopes);
    }

    /**
     * Ein Häkchen je Quelle der Laufzeit in ihrer Reihenfolge: neue kommen angehakt dazu, entfernte fallen weg,
     * vorhandene behalten ihren Stand (auch beim Umbenennen). Das Popup baut sich nur neu, wenn sich die Liste ändert.
     */
    private void syncSourceBoxes(AppRuntime runtime) {
        List<HarvestSource> sources = runtime == null ? List.of() : runtime.workspace().sources();
        Map<String, JCheckBox> current = new LinkedHashMap<>();
        boolean changed = false;
        for (HarvestSource source : sources) {
            JCheckBox box = sourceBoxes.get(source.id());
            if (box == null) {
                box = checkBox(source.title(), "Diese Wissensquelle durchsuchen; abgehakt fehlt sie in der Suche");
                box.setSelected(globalSearchSource(source.id()));
                String sourceId = source.id();
                box.putClientProperty(SOURCE_TITLE, source.title());
                box.addItemListener(e -> {
                    if (!syncingBoxes) {
                        sourceChecked(sourceId);
                    }
                });
                changed = true;
            } else if (!source.title().equals(box.getClientProperty(SOURCE_TITLE))) {
                box.putClientProperty(SOURCE_TITLE, source.title());
                box.setText(source.title());
                changed = true;
            }
            box.setToolTipText(globalSearchSource(source.id())
                    ? "Diese Wissensquelle durchsuchen"
                    : "Nur gezielte Suche – diese Quelle ausdrücklich anhaken");
            current.put(source.id(), box);
        }
        changed |= !current.keySet().equals(sourceBoxes.keySet());
        if (changed) {
            sourceBoxes.clear();
            sourceBoxes.putAll(current);
            if (notes != null) {
                rebuildOptions();
            }
        }
    }

    /**
     * Der Tooltip des Häkchens: mit Modell, woher die Varianten kommen; ohne Modell, warum es gesperrt ist;
     * {@code null} ohne Laufzeit.
     */
    static String semanticTip(Boolean available, boolean aiVariants) {
        if (available == null) {
            return null;
        }
        if (!available) {
            return "Gesperrt: kein Embedding-Modell eingerichtet (Einstellungen › Modelle); ohne Modell sucht die App "
                    + "nach Schlagwörtern";
        }
        return aiVariants
                ? "Sucht semantisch mit Varianten der Anfrage vom Chat-Modell des KI-Dienstes und ordnet alle Treffer "
                + "nach ihrer Nähe zur ursprünglichen Anfrage"
                : "Sucht semantisch mit Varianten der Anfrage nach Regeln (kein Chat-Modell eingerichtet, unter "
                + "Einstellungen › Modelle) und ordnet alle Treffer nach ihrer Nähe zur ursprünglichen Anfrage";
    }

    /**
     * Warum die semantische Suche gesperrt ist, genau nach der Bedingung des Core ({@code AiServiceSettings
     * .embeddings()}: Adresse, Modell und Vektorlänge): ein eingetragenes Modell ohne Vektorlänge heißt nicht „kein
     * Modell“ (#515).
     */
    static String lockedTip(com.aresstack.spaceharvester.app.settings.AppSettings.AiSettings ai) {
        if (ai == null || ai.baseUrl() == null) {
            return "Gesperrt: kein KI-Dienst eingerichtet (Einstellungen › Modelle); ohne Embedding-Modell sucht die "
                    + "App nach Schlagwörtern";
        }
        if (ai.embeddingModel() != null && ai.embeddingDimensions() == null) {
            return "Gesperrt: Für das Embedding-Modell „" + ai.embeddingModel() + "“ fehlt die Vektorlänge "
                    + "(Einstellungen › Modelle, „Modell prüfen“ misst sie); bis dahin sucht die App nach Schlagwörtern";
        }
        return semanticTip(false, false);
    }

    /** Nennt den Modus der nächsten Suche neben dem Umfang. */
    private void mode() {
        boolean terms = technicalOn();
        modeLabel.setText(terms ? TECHNICAL_TERMS_MODE + (wordSynonyms.isSelected() ? " + Synonyme" : "")
                : words ? WORDS_MODE + (headingsOnly.isSelected() ? " + nur Überschriften" : "")
                + (wordSynonyms.isSelected() ? " + Synonyme" : "")
                : (semantic.isSelected() && !headingsOnly.isSelected() ? "Modus: Semantische Suche"
                        : "Modus: Schlagwortsuche")
                + (headingsOnly.isSelected() ? " + nur Überschriften" : "")
                + (neighbors.isSelected() && neighbors.isEnabled() ? " + Nachbarseiten" : "")
                + (synonymsOn() ? " + Synonyme" : "")
                + (termPagesOn() ? " + Fachbegriffe" : ""));
        centerCards.show(center, terms ? TERMS_CARD : words ? WORDS_CARD : HITS_CARD);
        AppRuntime runtime = context.runtime();
        if (!terms || runtime == null) {
            termsRuntime = null;
            termsPanel.close();
        } else if (runtime != termsRuntime) {
            termsRuntime = runtime;
            termsPanel.access(TechnicalTermsPanel.of(runtime.workspace()));
        }
    }

    /** Ob „Nur Fachbegriffe“ gilt: nur in der Wortsuche (Angelo 27.11.2026). */
    boolean technicalOn() {
        return words && technicalTerms.isSelected();
    }

    /**
     * Wechselt zwischen Seitensuche und Wortsuche (Klick auf die Lupe): Lupe, Suchoptionen und Mitte folgen, der
     * Application State merkt die Wahl. Steht ein Suchtext im Feld, sucht die neue Suche gleich damit.
     */
    void switchMode(boolean toWords) {
        if (toWords == words) {
            return;
        }
        words = toWords;
        AppState.shared().put(STATE_MODE, words ? STATE_WORDS : STATE_PAGES);
        modeIcon.word(words);
        modeIcon.setToolTipText(words ? WORD_MODE_TIP : PAGE_MODE_TIP);
        feedback.clear();
        rebuildOptions();
        mode();
        if (!restoringHistory && (words || !query.getText().trim().isEmpty())
                && context.runtime() != null && !context.runtime().workspace().sources().isEmpty()) {
            search();
        }
    }

    /** Analysiert die Bereiche (Kontextmenü „Analysieren“ des Baums); {@code null} ohne Explorer. */
    private java.util.function.Consumer<List<Explorer.Scope>> analyzeListener;

    /** Wie „Analysieren“ im Baum, wenn die Wortsuche Verben braucht. */
    void onAnalyze(java.util.function.Consumer<List<Explorer.Scope>> listener) {
        this.analyzeListener = java.util.Objects.requireNonNull(listener, "listener");
    }

    /** Fragt, ob Fehlendes erzeugt werden soll; {@code true} nur nach „Ja“. */
    interface PrerequisiteOffer {
        boolean accepted(String title, String question);
    }

    /** Die Rückfrage; ohne Bildschirm (Tests) wird nie gefragt. */
    PrerequisiteOffer prerequisiteOffer = (title, question) -> !java.awt.GraphicsEnvironment.isHeadless()
            && Dialogs.confirm(component(), title, question);

    /** Bereiche, denen Fachbegriffe bzw. die Aussagenanalyse in dieser Sitzung schon angeboten wurden. */
    private final Set<String> prerequisitesOffered = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Die Wurzeln des Bereichs der nächsten Suche: die gewählten Knoten der angehakten Quellen, sonst die Quellen. */
    List<com.aresstack.spaceharvester.application.holdings.Selection.Root> areaRoots() {
        Set<String> searched = searchedSources();
        List<com.aresstack.spaceharvester.application.holdings.Selection.Root> roots = new ArrayList<>();
        for (Explorer.Scope scope : scopes) {
            if (searched.contains(scope.sourceId())) {
                roots.add(new com.aresstack.spaceharvester.application.holdings.Selection.Root(scope.sourceId(),
                        scope.spaceKey(), scope.spaceKey() == null ? null : scope.pageId()));
            }
        }
        if (roots.isEmpty()) {
            searched.forEach(source -> roots.add(
                    com.aresstack.spaceharvester.application.holdings.Selection.Root.source(source)));
        }
        return List.copyOf(roots);
    }

    /** Die Worttypen der nächsten Wortsuche. */
    Set<WordSearch.Kind> chosenWordKinds() {
        Set<WordSearch.Kind> kinds = java.util.EnumSet.noneOf(WordSearch.Kind.class);
        wordKindBoxes.forEach((kind, box) -> {
            if (box.isSelected()) {
                kinds.add(kind);
            }
        });
        return kinds;
    }

    /** Die Wortsuche im Bereich der Auswahl: Begriffe der angehakten Worttypen, „Nur Fachbegriffe“ im Glossar. */
    void searchWords() {
        AppRuntime runtime = context.runtime();
        // Jede neue Wortsuche macht eine noch wartende (Synonyme des Glossars) ungültig, auch wenn sie gleich abbricht.
        int mine = ++wordRequest;
        if (runtime == null) {
            return;
        }
        if (searchedSources().isEmpty()) {
            feedback.warning("Keine Wissensquelle angehakt", "In den Suchoptionen (Pfeil im Suchfeld) ist keine "
                    + "Quelle angehakt, die zur Auswahl im Explorer passt.");
            wordsPanel.clear();
            return;
        }
        if (!wordPages.isSelected() && !wordDocuments.isSelected()) {
            feedback.warning("Weder Seiten noch Anhänge angehakt", "In den Suchoptionen (Pfeil im Suchfeld) Seiten, "
                    + "Anhänge oder beides anhaken.");
            wordsPanel.clear();
            return;
        }
        String text = query.getText().trim();
        searchedWords = text;
        WordSearch search = new WordSearch(runtime, WordSearch.area(areaRoots(), areaTitle(scopes)),
                WordSearch.origin(wordPages.isSelected(), wordDocuments.isSelected()));
        if (technicalOn()) {
            checkTechnicalTerms(search, TECHNICAL_TERMS);
            searchTechnicalTerms();
            return;
        }
        Set<WordSearch.Kind> kinds = chosenWordKinds();
        if (kinds.isEmpty()) {
            feedback.warning("Kein Worttyp angehakt", "In den Suchoptionen (Pfeil im Suchfeld) mindestens einen "
                    + "Worttyp anhaken.");
            wordsPanel.clear();
            return;
        }
        feedback.clear();
        remember(text, scopes);
        heading.title(HEADING_PREFIX + areaTitle(scopes));
        if (kinds.contains(WordSearch.Kind.VERBS)) {
            checkVerbs(search);
        }
        if (headingsOnly.isSelected() && !text.isEmpty()) {
            // „Nur Überschriften durchsuchen“ (Angelo 27.11.2026): nur Wörter der Seiten, deren Titel oder Überschriften
            // den Suchtext enthalten; dieselbe Schlagwortsuche wie die Seitensuche, nur im lokalen Index.
            Set<String> sources = searchedSources();
            KeywordSearch.Query q = new KeywordSearch.Query(text, sources.size() == 1 ? sources.iterator().next()
                    : null, sources.size() > 1 ? sources : Set.of(), Set.of(), Set.of())
                    .withHeadingsOnly(true);
            wordStatus("Überschriften werden durchsucht …");
            Background.run(() -> headingPages(runtime, q), pages -> {
                if (mine == wordRequest) {
                    runWords(runtime, search.only(pages), kinds, text, mine);
                }
            }, error -> {
                if (mine == wordRequest) {
                    feedback.problem(error);
                }
            });
            return;
        }
        runWords(runtime, search, kinds, text, mine);
    }

    /** Alle Seiten, deren Titel oder Überschriften die Anfrage treffen, Stapel für Stapel dem Cursor nach. */
    private static com.aresstack.spaceharvester.application.snapshot.PageSet headingPages(AppRuntime runtime,
                                                                                         KeywordSearch.Query q) {
        Map<String, Set<String>> pages = new LinkedHashMap<>();
        String core = java.util.UUID.randomUUID().toString();
        String cursor = null;
        do {
            KeywordSearch.Results results = runtime.workspace().keywordSearchIn(core, q, cursor, KeywordSearch.MAX_LIMIT,
                    null);
            for (KeywordSearch.Hit hit : results.hits()) {
                pages.computeIfAbsent(hit.sourceId(), id -> new LinkedHashSet<>()).add(hit.pageId());
            }
            cursor = results.nextCursor();
        } while (cursor != null);
        return com.aresstack.spaceharvester.application.snapshot.PageSet.of(pages);
    }

    /** Startet die Liste: mit dem Suchtext, bei „Synonyme der Fachbegriffe“ auch mit deren Synonymen. */
    private void runWords(AppRuntime runtime, WordSearch search, Set<WordSearch.Kind> kinds, String text, int mine) {
        if (!wordSynonyms.isSelected() || text.isEmpty()) {
            wordsPanel.search(search, kinds, List.of(text));
            return;
        }
        com.aresstack.spaceharvester.application.glossary.TechnicalTerms.Scope scope = technicalScope();
        Background.run(() -> wordQueries(runtime, text, scope), queries -> {
            if (mine == wordRequest) {
                wordsPanel.search(search, kinds, queries);
            }
        }, error -> {
            if (mine == wordRequest) {
                // Ohne lesbares Glossar sucht die Wortsuche nur den Text, gelb wie in der Seitensuche.
                wordsPanel.search(search, kinds, List.of(text));
                noteSynonyms(SYNONYMS_UNREADABLE);
            }
        });
    }

    /**
     * Der Suchtext und, passt er zu Fachbegriffen, deren Synonyme als weitere Suchtexte; höchstens so viele wie die
     * Seitensuche ausweitet ({@link SearchExpansion#MAX_EXPANSIONS}).
     */
    static List<String> wordQueries(AppRuntime runtime, String text,
                                    com.aresstack.spaceharvester.application.glossary.TechnicalTerms.Scope scope) {
        Set<String> queries = new LinkedHashSet<>();
        queries.add(text);
        var page = runtime.workspace().technicalTerms(text, scope, SearchExpansion.MAX_EXPANSIONS, null);
        for (var hit : page.hits()) {
            queries.add(hit.entry().term());
            for (var relation : runtime.workspace().technicalSynonyms(hit.entry())) {
                if (queries.size() > SearchExpansion.MAX_EXPANSIONS) {
                    return List.copyOf(queries);
                }
                queries.add(relation.other(hit.entry().normalized()));
            }
        }
        return List.copyOf(queries);
    }

    /** Stand der Wortsuche in der Statuszeile. */
    private void wordStatus(String text) {
        if (words) {
            status.setForeground(Comic.MUTED);
            status.setText(text);
        }
    }

    /** Wie {@link #checkTechnicalTerms(WordSearch, String)} für den Bereich der nächsten Suche. */
    private void checkTechnicalTerms(String method) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || runtime.workspace().sources().isEmpty() || searchedSources().isEmpty()) {
            return;
        }
        checkTechnicalTerms(new WordSearch(runtime, WordSearch.area(areaRoots(), areaTitle(scopes)), null), method);
    }

    /**
     * Fehlen im Bereich Fachbegriffe (das Fachglossar wurde dort nicht oder nur teilweise erstellt), sagt die Suche es
     * gelb und fragt einmal je Bereich, ob sie jetzt ermittelt werden sollen (Angelo 27.11.2026). Nach „Ja“ startet
     * derselbe Lauf wie „Fachbegriffe ermitteln“ im Reiter „Fachglossar“, je fehlender Quelle bzw. Space.
     */
    private void checkTechnicalTerms(WordSearch search, String method) {
        AppRuntime runtime = context.runtime();
        String area = areaTitle(scopes);
        Background.run(() -> new TechnicalCheck(search.roots().size(), search.withoutTechnicalTerms()), check -> {
            if (check.missing().isEmpty()) {
                return;
            }
            boolean none = check.missing().size() == check.roots();
            String where = none ? "in „" + area + "“" : "in " + check.missing().stream()
                    .map(this::rootTitle).collect(java.util.stream.Collectors.joining(", "));
            feedback.warning(none ? "Keine Fachbegriffe im Bereich" : "Fachbegriffe nur teilweise ermittelt",
                    technicalMissing(method, where, none));
            if (!prerequisitesOffered.add("terms\u0000" + check.missing())
                    || !prerequisiteOffer.accepted(TECHNICAL_OFFER_TITLE, technicalQuestion(where, none))) {
                return;
            }
            detectTechnicalTerms(runtime, check.missing());
        }, error -> LOG.log(System.Logger.Level.DEBUG, "Fachbegriffe des Bereichs nicht prüfbar", error));
    }

    private record TechnicalCheck(int roots,
                                  List<com.aresstack.spaceharvester.application.holdings.Selection.Root> missing) {
    }

    /** Der gelbe Hinweis: warum die Methode im Bereich nichts bzw. nicht alles findet. */
    static String technicalMissing(String method, String where, boolean none) {
        return none ? "Für „" + method + "“ braucht es Fachbegriffe; " + where + " wurden noch keine ermittelt, "
                + "die Suche findet deshalb nichts."
                : "Für „" + method + "“ braucht es Fachbegriffe; " + where + " wurden noch keine ermittelt, "
                + "dort findet die Suche nichts.";
    }

    /** Die Rückfrage mit „wirklich“, Antwort Ja oder Nein. */
    static String technicalQuestion(String where, boolean none) {
        return (none ? "Die Fachbegriffe wurden " + where + " noch nicht ermittelt. "
                : "Die Fachbegriffe wurden nur teilweise ermittelt, sie fehlen noch " + where + ". ")
                + "Das Ermitteln läuft als Auftrag mit dem eingestellten KI-Dienst, den du unter Aufträge jederzeit "
                + "abbrechen kannst. Fachbegriffe wirklich jetzt ermitteln?";
    }

    private String rootTitle(com.aresstack.spaceharvester.application.holdings.Selection.Root root) {
        return root.spaceKey() == null ? "„" + title(root.sourceId()) + "“"
                : "„" + root.spaceKey() + "“ (" + title(root.sourceId()) + ")";
    }

    /** Startet „Fachbegriffe ermitteln“ je fehlender Wurzel und zeigt die Aufträge. */
    private void detectTechnicalTerms(AppRuntime runtime,
                                      List<com.aresstack.spaceharvester.application.holdings.Selection.Root> roots) {
        var glossary = runtime.workspace().glossary();
        if (glossary.isEmpty()) {
            return;
        }
        Background.run(() -> {
            List<com.aresstack.spaceharvester.application.jobs.JobStatus> started = new ArrayList<>();
            for (var root : roots) {
                started.addAll(glossary.get().classify(root.sourceId(), root.spaceKey(), root.pageId()));
            }
            return started;
        }, started -> {
            if (!started.isEmpty() && context.navigator() != null) {
                context.navigator().showJob(started.get(0).id());
            }
        }, error -> feedback.problem(error));
    }

    /**
     * Verben gibt es nur aus der Aussagenanalyse: Fehlt sie im Bereich ganz oder teilweise, sagt die Wortsuche es gelb
     * und fragt einmal je Bereich, ob jetzt analysiert werden soll; nach „Ja“ derselbe Ablauf wie „Analysieren“ im
     * Baum.
     */
    private void checkVerbs(WordSearch search) {
        // Analysiert wird genau der durchsuchte Bereich: abgehakte Quellen bleiben draußen, auch bei „alle“.
        List<Explorer.Scope> area = areaRoots().stream().map(root -> new Explorer.Scope(root.sourceId(),
                root.spaceKey(), root.pageId(), null)).toList();
        String title = areaTitle(scopes);
        Background.run(search::verbCoverage, coverage -> {
            if (coverage.complete()) {
                return;
            }
            boolean none = coverage.analysed() == 0;
            feedback.warning(none ? "Keine Verben im Bereich" : "Verben nur teilweise vorhanden",
                    verbsMissing(coverage, title));
            if (analyzeListener == null || !prerequisitesOffered.add("verbs\u0000" + area)
                    || !prerequisiteOffer.accepted(VERBS_OFFER_TITLE, verbsQuestion(coverage, title))) {
                return;
            }
            analyzeListener.accept(area);
        }, error -> LOG.log(System.Logger.Level.DEBUG, "Aussagenanalyse des Bereichs nicht prüfbar", error));
    }

    /** Der gelbe Hinweis zu fehlenden Verben. */
    static String verbsMissing(WordSearch.VerbCoverage coverage, String area) {
        if (coverage.analysed() == 0) {
            return "Verben kommen aus der Aussagenanalyse; in „" + area + "“ wurde noch nichts analysiert, die "
                    + "Suche findet deshalb keine Verben.";
        }
        return "Verben kommen aus der Aussagenanalyse; in „" + area + "“ fehlt sie bei "
                + Texts.count(coverage.unanalysed() + coverage.stale(), "Seite", "Seiten")
                + (coverage.stale() > 0 ? " (davon " + coverage.stale() + " veraltet)" : "")
                + ", deren Verben findet die Suche nicht.";
    }

    /** Die Rückfrage vor dem Analysieren; die Zahl der Seiten nennt danach der Dialog „Analysieren“. */
    static String verbsQuestion(WordSearch.VerbCoverage coverage, String area) {
        return (coverage.analysed() == 0 ? "In „" + area + "“ wurden die Aussagen noch nicht analysiert, deshalb gibt "
                + "es dort keine Verben. " : "In „" + area + "“ sind die Aussagen nur teilweise analysiert. ")
                + "Aussagen wirklich jetzt analysieren?";
    }

    /** Die Laufzeit, an die {@link #termsPanel} gebunden ist; {@code null} ohne. */
    private AppRuntime termsRuntime;

    /** „Nur Fachbegriffe“: Suchtext (leer heißt alle) im links gewählten Bereich. */
    private void searchTechnicalTerms() {
        if (termsRuntime == null) {
            return;
        }
        if (searchedSources().isEmpty()) {
            // Wie die übrige Suche: ohne angehakte Quelle wird nicht gesucht, gelb statt still alles.
            feedback.warning("Keine Wissensquelle angehakt", "In den Suchoptionen (Pfeil im Suchfeld) ist keine "
                    + "der gewählten Quellen angehakt.");
            termsPanel.clear();
            return;
        }
        feedback.clear();
        remember(query.getText().trim(), scopes);
        termsPanel.search(query.getText(), technicalScope());
    }

    /**
     * Der Bereich für „Nur Fachbegriffe“ wie bei der übrigen Suche ({@link #chosenSources()},
     * {@link #chosenSpaces()}): mehrere gewählte Knoten oder abgewählte Quellen weiten nie auf alle auf.
     */
    com.aresstack.spaceharvester.application.glossary.TechnicalTerms.Scope technicalScope() {
        return new com.aresstack.spaceharvester.application.glossary.TechnicalTerms.Scope(chosenSources(),
                chosenSpaces());
    }

    /** Ob die nächste Suche die Seiten zu Fachbegriffen einbezieht (W8). */
    private boolean termPagesOn() {
        return termPages.isSelected() && termPages.isEnabled();
    }

    /** Ob die nächste Suche die Synonyme der Fachbegriffe einbezieht (K5a). */
    private boolean synonymsOn() {
        return glossarySynonyms.isSelected() && glossarySynonyms.isEnabled();
    }

    /**
     * K5a: prüft neben der Suche, ob das Glossar lesbar ist und ob die Grenze {@link SearchExpansion#MAX_EXPANSIONS}
     * Synonyme wegließ; beides steht gelb in der Statuszeile, nie als Fehler.
     */
    private void checkSynonyms(AppRuntime runtime, KeywordSearch.Query q, int id) {
        synonymNote = "";
        if (!q.glossarySynonyms()) {
            return;
        }
        Background.run(() -> runtime.workspace().glossarySynonymsWanted(q),
                available -> {
                    if (id == request && available > SearchExpansion.MAX_EXPANSIONS) {
                        noteSynonyms(synonymsCut(available));
                    }
                }, error -> {
                    if (id == request) {
                        noteSynonyms(SYNONYMS_UNREADABLE);
                    }
                });
    }

    /** „Synonyme der Fachbegriffe: 3 von 5 gesucht (Grenze)“. */
    static String synonymsCut(int available) {
        return "Synonyme der Fachbegriffe: " + SearchExpansion.MAX_EXPANSIONS + " von " + available
                + " gesucht (Grenze)";
    }

    private void noteSynonyms(String note) {
        synonymNote = " · " + note;
        String text = status.getText().strip();
        status.setText((text.isEmpty() ? note : text + synonymNote));
        status.setForeground(Comic.ORANGE);
    }

    /** Tooltip der Trefferliste. */
    static final String ROW_TIP = "Doppelklick öffnet die Seite unter Inhalte, beim Absatz des Auszugs";

    /** Ein Satz (UX-Manager 21.11.2026). */
    static final String NEIGHBORS_TIP = "Nimmt Eltern-, Unter- und verlinkte Seiten der besten Treffer dazu, wenn sie "
            + "die Anfrage auch treffen";

    /** Ein Satz, wie bei den Nachbarseiten. */
    static final String SYNONYMS_TIP = "Sucht zu bestätigten Fachbegriffen des Glossars auch ihre vorgeschlagenen "
            + "Synonyme";

    private static JCheckBox checkBox(String text, String tooltip) {
        JCheckBox box = Comic.checkBox(text);
        box.setToolTipText(tooltip);
        return box;
    }

    /**
     * Adressen, die das Suchfeld direkt öffnet statt zu suchen: Seiten-URL, Lesezeichen, Confluence-Bookmark und die
     * Ressourcen-ID aus den technischen Angaben einer Seite ({@code confluence:<host>/page/<ID>}, {@code files:…}).
     */
    static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile(
            "(?i)(https?|space-harvester|confluence)://\\S+|(confluence|files):[^\\s:/][^\\s]*/\\S+");

    /**
     * Ob {@code text} eine Adresse ist, die direkt geöffnet wird ({@link #ADDRESS}), auch wenn beim Kopieren
     * unsichtbare Zeichen oder Unicode-Bindestriche hineingeraten sind ({@link PageAddresses#normalize}). Auch der
     * Titelfilter im Explorer nutzt das.
     */
    static boolean isAddress(String text) {
        String value = PageAddresses.normalize(text);
        return value != null && ADDRESS.matcher(value).matches();
    }

    /**
     * Öffnet eine Adresse über die Adressauflösung des Core ({@code Harvester.addresses().resolve}, #178): eine Seite
     * direkt unter Inhalte, mehrere (dieselbe Adresse in mehreren Quellen) als Treffer zur Wahl.
     */
    private void open(AppRuntime runtime, String address) {
        int id = ++request;
        searchId++;
        // Das Laden der vorigen Suche endet; ihr Nachfragen darf die Adressauflösung nicht überholen.
        stopLoading();
        run.setEnabled(false);
        more.setVisible(false);
        feedback.clear();
        pinned.clear();
        starting("Adresse wird aufgelöst …");
        searching = Background.start(() -> runtime.workspace().resolveAddress(address), resolution -> {
            if (id != request) {
                return;
            }
            searching = null;
            run.setEnabled(true);
            stopLoading();
            states.clear();
            List<Hit> hits = resolution.matches().stream().map(Hit::of).toList();
            // Die Seite zur Adresse steht abgesetzt oben wie Titeltreffer (Angelo, 23.11.2026), statt sofort zu öffnen.
            model.set(List.of());
            searchedText = address;
            searchedScopes = List.copyOf(scopes);
            heading.title(HEADING_PREFIX + areaTitle(searchedScopes));
            heading.selected(false);
            repeat.setEnabled(true);
            titleRequest++;
            pinned.show(PinnedHits.ADDRESS, hits);
            String unavailable = resolution.unavailableSources().isEmpty() ? ""
                    : " Nicht erreichbar: " + String.join(", ", resolution.unavailableSources()) + ".";
            if (hits.size() == 1) {
                feedback.clear();
                status.setText("Adresse aufgelöst: " + hits.get(0).title());
            } else if (hits.isEmpty()) {
                status.setText(" ");
                feedback.info("Keine Seite zu dieser Adresse", "Keine eingerichtete Quelle kennt eine Seite unter "
                        + "dieser Adresse, die du lesen darfst." + unavailable);
            } else {
                status.setText(Texts.count(hits.size(), "Seite", "Seiten") + " zu dieser Adresse");
                feedback.info("Mehrere Quellen", "Die Adresse passt in mehreren Quellen; Doppelklick oben öffnet die Seite."
                        + unavailable);
            }
        }, error -> {
            if (id != request) {
                return;
            }
            searching = null;
            run.setEnabled(true);
            stopLoading();
            feedback.problem(error);
        });
    }

    private void search() {
        pageScopeRequest++;
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            feedback.warning("Keine Laufzeit", "Die Konfiguration konnte nicht gestartet werden; siehe Einstellungen.");
            return;
        }
        String text = query.getText().trim();
        if (runtime.workspace().sources().isEmpty()) {
            feedback.warning("Keine Quelle eingerichtet", "Wissensquellen legst du unter Wissensquellen verwalten "
                    + "an (rechts oben).");
            return;
        }
        if (words) {
            mode();
            searchWords();
            return;
        }
        if (text.isEmpty()) {
            feedback.warning("Suchtext fehlt", "Ein oder mehrere Schlagwörter eingeben.");
            return;
        }
        if (isAddress(text)) {
            remember(text, scopes);
            open(runtime, text);
            return;
        }
        searchedText = text;
        if (searchedSources().isEmpty()) {
            feedback.warning("Keine Wissensquelle angehakt", "In den Suchoptionen (Pfeil im Suchfeld) ist keine "
                    + "Quelle angehakt, die zur Auswahl im Explorer passt.");
            return;
        }
        if (!anyKind()) {
            feedback.warning("Kein Dateityp angehakt", "In den Suchoptionen (Pfeil im Suchfeld) mindestens einen "
                    + "Dateityp anhaken.");
            return;
        }
        KeywordSearch.Query q;
        try {
            // Eine gewählte Quelle sucht wie die Suche unter der Quelle (sourceId), mehrere mit sourceIds.
            // Umfang als Schnappschuss beim Start: spätere Auswahl oder Optionen ändern diese Suche nicht.
            Set<String> sources = chosenSources();
            String sourceId = sources.size() == 1 ? sources.iterator().next() : null;
            Set<String> spaces = chosenSpaces();
            // Gemischt (ganze Quelle A, aus B nur einzelne Spaces): B ohne seine übrigen Spaces.
            Set<String> excluded = spaces.isEmpty() ? exclusions(scopes, searchedSources(), source -> {
                try {
                    return runtime.workspace().holdingSpaces(source).stream()
                            .map(com.aresstack.spaceharvester.application.holdings.SpaceSummary::spaceKey).toList();
                } catch (RuntimeException e) {
                    return List.of();
                }
            }) : Set.of();
            q = new KeywordSearch.Query(text, sourceId, sources.size() > 1 ? sources : Set.of(), spaces, excluded)
                    .ofKinds(chosenKinds()).withGlossarySynonyms(synonymsOn())
                    .withHeadingsOnly(headingsOnly.isSelected());
        } catch (InvalidQueryException invalid) {
            feedback.warning("Suche so nicht möglich", invalid.getMessage());
            return;
        }
        remember(text, scopes);
        List<Explorer.Scope> pageScopes = scopes.stream().filter(scope -> scope.pageId() != null
                && searchedSources().contains(scope.sourceId())).toList();
        if (pageScopes.isEmpty()) {
            run(runtime, q, text);
            return;
        }
        // Eine Seite als Bereich (Angelo, 23.11.2026): dieselbe Suche, begrenzt auf die Seite samt Unterseiten.
        KeywordSearch.Query spaceQuery = q;
        int pending = ++pageScopeRequest;
        starting("Unterseiten werden ermittelt …");
        Background.run(() -> pagesOf(runtime, pageScopes), pages -> {
            if (pending == pageScopeRequest) {
                run(runtime, spaceQuery.within(pages), text);
            }
        }, error -> {
            if (pending == pageScopeRequest) {
                overlay.setBusy(false);
                feedback.problem(error);
            }
        });
    }

    /** Zählt das Ermitteln der Unterseiten; ein überholtes verfällt. */
    private int pageScopeRequest;

    /** Die gewählten Seiten samt ihrer Nachfahren aus dem Bestand. */
    private static com.aresstack.spaceharvester.application.snapshot.PageSet pagesOf(AppRuntime runtime,
                                                                                     List<Explorer.Scope> pageScopes) {
        Map<String, Set<String>> pages = new LinkedHashMap<>();
        for (Explorer.Scope scope : pageScopes) {
            Set<String> ids = pages.computeIfAbsent(scope.sourceId(), id -> new LinkedHashSet<>());
            ids.add(scope.pageId());
            ids.addAll(runtime.workspace().descendants(scope.sourceId(), scope.spaceKey(), scope.pageId()).pages()
                    .pagesOf(scope.sourceId()));
        }
        return com.aresstack.spaceharvester.application.snapshot.PageSet.of(pages);
    }

    /** Startet die gebaute Anfrage: Schlagwortsuche oder semantisch. */
    private void run(AppRuntime runtime, KeywordSearch.Query q, String text) {
        started(runtime, text);
        // Nur Überschriften kann allein die Schlagwortsuche; die Vektoren gehören zum ganzen Inhalt.
        if (semantic.isSelected() && !q.headingsOnly()) {
            progressive(runtime, q);
            checkSynonyms(runtime, q, request);
            return;
        }
        stopLoading();
        showVariants(List.of());
        int id = ++request;
        searchId++;
        String core = java.util.UUID.randomUUID().toString();
        keywordSearchId = core;
        checkSynonyms(runtime, q, id);
        neighborsFor = neighbors.isSelected() && neighbors.isEnabled() ? id : -1;
        termPagesFor = termPagesOn() ? id : -1;
        resetTermPages();
        run.setEnabled(false);
        more.setVisible(false);
        feedback.clear();
        // Die alte Liste geht; die Funde der Quellen füllen die neue, sobald eine Quelle antwortet.
        model.set(List.of());
        states.clear();
        status.setText(" ");
        starting("Suche läuft …");
        searching = Background.start(() -> runtime.workspace().keywordSearchIn(core, q, null, LIMIT, observer(id)),
                results -> {
            if (id != request) {
                discard(results);
                return;
            }
            searching = null;
            overlay.setBusy(false);
            run.setEnabled(true);
            states.clear();
            incomplete = false;
            shownCursor = null;
            batchStart = 0;
            model.set(hits(results));
            show(q, results);
            awaitingHits = false;
            if (!results.ranked().isEmpty()) {
                feedback.clear();
            } else if (results.progress() != null && !results.progress().done()) {
                // Delegierte Benutzer sehen keine vorläufigen Funde: Treffer erscheinen erst, wenn der Core die
                // geladenen Seiten geprüft hat. Bis dahin spricht der Balken, kein vorzeitiges „Keine Treffer“.
                awaitingHits = true;
                feedback.clear();
            } else if (results.nextCursor() != null) {
                // Dieser Stapel war leer, weitere Kandidaten folgen noch: kein endgültiges „Keine Treffer“.
                feedback.info("Noch keine Treffer", "Im ersten Stapel war nichts Passendes; „Weitere laden“ sucht weiter.");
            } else if (!results.complete()) {
                feedback.warning("Keine Treffer, Suche unvollständig",
                        "Nicht jede Quelle konnte vollständig suchen; siehe Zeile unter der Liste.");
            } else {
                feedback.info("Keine Treffer", searched()
                        ? "Keine Quelle kennt eine passende Seite, die du lesen darfst."
                        : "Keine der gewählten Quellen konnte suchen; siehe Zeile unter der Liste.");
            }
            track(results.progress());
        }, error -> {
            if (id != request) {
                return;
            }
            searching = null;
            overlay.setBusy(false);
            run.setEnabled(true);
            model.set(List.of());
            states.clear();
            reset();
            feedback.problem(error);
        });
    }

    /**
     * Der Beobachter der Suche Nummer {@code id}: Die Funde einer Quelle erscheinen in der Liste, sobald die Quelle
     * geantwortet hat; der fertige Stapel ersetzt sie dann in der Rangfolge des Core. Meldungen einer überholten Suche
     * verfallen.
     */
    private KeywordSearch.Found observer(int id) {
        return (sourceId, hits) -> SwingUtilities.invokeLater(() -> {
            if (id == request) {
                arrived(hits);
            }
        });
    }

    /**
     * Vorläufige Treffer einer Quelle, noch während die anderen suchen: nur angehängt, nie an die Stelle einer schon
     * gezeigten Zeile, denn ein geprüfter Treffer eines früheren Stapels weiß mehr (Space, Auszug, Anhang) als der
     * Fund der Quelle.
     */
    private void arrived(List<KeywordSearch.Hit> hits) {
        if (hits.isEmpty()) {
            return;
        }
        model.append(hits.stream().map(Hit::of).toList());
        status.setText(Texts.count(model.rows.size(), "Treffer", "Treffer") + " · weitere Quellen antworten noch");
        status.setForeground(Comic.MUTED);
    }

    /**
     * Ein Stapel, dessen Suche überholt oder abgebrochen wurde: Sein Laden in den Cache endet, sonst lüde der Core
     * Seiten für eine Liste, die niemand mehr sieht.
     */
    private void discard(KeywordSearch.Results results) {
        AppRuntime runtime = context.runtime();
        KeywordSearch.Progress progress = results.progress();
        if (runtime != null && progress != null && !progress.done()) {
            Background.run(() -> runtime.workspace().cancelKeywordSearch(progress.id()), ignored -> { },
                    ignored -> { });
        }
    }

    /**
     * Semantische Suche (ADR 0017): Der Core sucht mit der ursprünglichen Anfrage und ihren Varianten und meldet nach
     * jeder Freigabe die ganze Liste; die Ansicht sortiert ein, ohne die Auswahl zu verlieren, und zeigt am Ende, warum
     * die Suche endete. Ereignisse einer überholten Suche werden verworfen.
     */
    private void progressive(AppRuntime runtime, KeywordSearch.Query q) {
        stopLoading();
        int id = ++request;
        searchId++;
        states.clear();
        incomplete = false;
        lastQuery = null;
        progressiveQuery = q;
        nextCursor = null;
        model.set(List.of());
        showVariants(List.of());
        feedback.clear();
        status.setText(" ");
        // Reste einer Schlagwortsuche (Seiten zu Fachbegriffen) gehören nicht in diese Statuszeile.
        resetTermPages();
        runningCancelRequested = false;
        starting("Suche läuft · Suchanfragen werden gebildet …");
        cancel.setVisible(true);
        cancel.setEnabled(true);
        ProgressiveSearchListener listener = new ProgressiveSearchListener() {
            @Override
            public void variants(List<ProgressiveResult.Variant> variants) {
                SwingUtilities.invokeLater(() -> {
                    if (id == request) {
                        showVariants(variants);
                    }
                });
            }

            @Override
            public void ranked(List<ProgressiveResult.RankedHit> hits) {
                SwingUtilities.invokeLater(() -> {
                    if (id == request) {
                        showRanked(hits);
                    }
                });
            }

            @Override
            public void completed(ProgressiveResult result) {
                SwingUtilities.invokeLater(() -> {
                    if (id == request) {
                        ended(result);
                    }
                });
            }
        };
        running = runtime.workspace().progressiveSearch(q, listener, neighbors.isSelected() && neighbors.isEnabled(),
                termPagesOn());
    }

    /** Die ganze Liste nach einer Freigabe: neue Treffer eingeordnet, bekannte an ihrem neuen Platz, Auswahl bleibt. */
    private void showRanked(List<ProgressiveResult.RankedHit> hits) {
        Hit selected = selectedHit();
        model.set(hits.stream().map(ranked -> Hit.of(ranked.hit())).toList());
        select(selected);
        status.setText(Texts.count(model.rows.size(), "Treffer", "Treffer") + " · semantisch, nach Nähe zur Anfrage"
                + synonymNote);
        status.setForeground(synonymNote.isEmpty() ? Comic.MUTED : Comic.ORANGE);
    }

    /** Ende der semantischen Suche: Endliste, Grund in der Fortschrittszeile, Hinweis bei keinen Treffern. */
    private void ended(ProgressiveResult result) {
        running = null;
        runningCancelRequested = false;
        overlay.setBusy(false);
        showRanked(result.hits());
        showVariants(result.variants());
        idle(switch (result.reason()) {
            case CANCELLED -> "Abgebrochen";
            case FAILED -> "Fehlgeschlagen";
            default -> "Fertig";
        });
        progressText.setText(completion(result.reason()) + " · "
                + Texts.count(model.rows.size(), "Treffer", "Treffer"));
        if (result.neighbors() == ProgressiveResult.Neighbors.UNCHECKED
                && result.reason() != ProgressiveResult.Completion.CANCELLED) {
            uncheckedNeighbors();
        }
        if (result.reason() != ProgressiveResult.Completion.CANCELLED) {
            String note = termPagesNote(result.technicalTerms());
            if (note != null) {
                noteTermPages(note);
            }
        }
        if (result.neighborLoad() != null && result.reason() != ProgressiveResult.Completion.CANCELLED
                && progressiveQuery != null) {
            followNeighborLoad(searchId, progressiveQuery, result.neighborLoad());
        }
        if (result.reason() == ProgressiveResult.Completion.FAILED) {
            LOG.log(System.Logger.Level.WARNING, "Semantische Suche fehlgeschlagen: {0} Suchanfragen, keine lief",
                    result.variants().size());
            // Die semantische Suche scheitert meist am KI-Dienst (Embeddings), nicht an den Quellen (#515).
            feedback.warning("Suche fehlgeschlagen", failureText(result.failure()));
        } else if (model.rows.isEmpty() && result.reason() != ProgressiveResult.Completion.CANCELLED) {
            feedback.info("Keine Treffer", "Weder die Anfrage noch ihre Varianten fanden eine Seite, die du lesen "
                    + "darfst.");
        }
    }

    /** Der Grund einer gescheiterten semantischen Suche, wie der Core ihn meldet ({@code ProgressiveResult#failure}). */
    static String failureText(com.aresstack.spaceharvester.ai.AiServiceException.Reason reason) {
        String fallback = " Ohne „Semantische Suche“ geht die Schlagwortsuche weiter.";
        if (reason == null) {
            return "Keine Suchanfrage ließ sich ausführen; die Quellen waren nicht erreichbar." + fallback;
        }
        return switch (reason) {
            case NOT_CONFIGURED -> "Kein Embedding-Modell eingerichtet (Einstellungen › Modelle).";
            case UNAUTHORIZED -> "Der KI-Dienst lehnt die Anmeldung ab: API-Key fehlt oder ist ungültig "
                    + "(Einstellungen › Modelle).";
            case UNREACHABLE -> "Der KI-Dienst für das Embedding-Modell ist nicht erreichbar (Adresse, Proxy, "
                    + "Netzwerk).";
            case TIMEOUT -> "Der KI-Dienst hat nicht rechtzeitig geantwortet.";
            case INVALID_RESPONSE -> "Der KI-Dienst lieferte unbrauchbare Embeddings, etwa mit falscher Vektorlänge "
                    + "(„Modell prüfen“ misst sie neu).";
        } + fallback;
    }

    static String completion(ProgressiveResult.Completion reason) {
        return switch (reason) {
            case QUALITY_REACHED -> DONE + " · genug passende Treffer";
            case ALL_VARIANTS_DONE -> DONE + " · alle Suchanfragen gelaufen";
            case BUDGET_EXHAUSTED -> DONE + " · Zeit- oder Abfragebudget ausgeschöpft";
            case CANCELLED -> "Suche abgebrochen";
            case FAILED -> "Suche fehlgeschlagen";
        };
    }

    /** Die Varianten als Schlagwörter und der Stand in der Fortschrittszeile („2 von 6 Suchanfragen“). */
    private void showVariants(List<ProgressiveResult.Variant> variants) {
        tags.removeAll();
        int finished = 0;
        for (ProgressiveResult.Variant variant : variants) {
            ProgressiveResult.Status state = variant.status();
            if (state != ProgressiveResult.Status.PENDING && state != ProgressiveResult.Status.RUNNING) {
                finished++;
            }
            if (variant.query().priority() > 0) {
                tags.add(tag(variant));
            }
        }
        tags.setVisible(tags.getComponentCount() > 0);
        tags.revalidate();
        tags.repaint();
        if (running != null && !variants.isEmpty()) {
            progressText.setText("Suche läuft · " + finished + " von " + Texts.count(variants.size(), "Suchanfrage",
                    "Suchanfragen") + " · " + Texts.count(model.rows.size(), "Treffer", "Treffer"));
        }
    }

    private static JLabel tag(ProgressiveResult.Variant variant) {
        JLabel tag = new JLabel(variant.query().text());
        tag.setFont(Comic.regular(12f));
        tag.setOpaque(true);
        boolean useful = variant.status() == ProgressiveResult.Status.DONE && variant.newHits() > 0;
        boolean dropped = variant.status() == ProgressiveResult.Status.FAILED
                || variant.status() == ProgressiveResult.Status.SKIPPED;
        tag.setBackground(useful ? Comic.YELLOW : java.awt.Color.WHITE);
        tag.setForeground(dropped ? Comic.MUTED : Comic.INK);
        tag.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(dropped ? Comic.LINE
                : Comic.INK, 1, true), BorderFactory.createEmptyBorder(1, 7, 1, 7)));
        tag.setToolTipText("Variante der Anfrage · " + switch (variant.status()) {
            case PENDING -> "wartet";
            case RUNNING -> "läuft";
            case DONE -> Texts.count(variant.newHits(), "neuer Treffer", "neue Treffer");
            case FAILED -> "fehlgeschlagen";
            case SKIPPED -> "übersprungen";
        });
        return tag;
    }

    /** Der nächste Stapel derselben Suche, angehängt an die bisherigen Treffer. */
    private void loadMore() {
        AppRuntime runtime = context.runtime();
        KeywordSearch.Query q = lastQuery;
        String cursor = nextCursor;
        if (runtime == null || q == null || cursor == null) {
            return;
        }
        // Das Laden des vorigen Stapels läuft im Core weiter; die Ansicht folgt ab jetzt dem neuen.
        untrack();
        progressId = null;
        // Bis zur Antwort gibt es keinen weiteren Stapel; ein zweiter Klick verwürfe sonst den laufenden.
        nextCursor = null;
        int id = ++request;
        run.setEnabled(false);
        feedback.clear();
        starting("Weitere Treffer werden geladen …");
        int from = model.rows.size();
        String core = keywordSearchId;
        searching = Background.start(() -> runtime.workspace().keywordSearchIn(core, q, cursor, LIMIT, observer(id)),
                results -> {
            if (id != request) {
                discard(results);
                return;
            }
            searching = null;
            overlay.setBusy(false);
            run.setEnabled(true);
            shownCursor = cursor;
            batchStart = from;
            // Die vorläufigen Funde dieses Stapels weichen dem fertigen Stapel in der Rangfolge des Core.
            model.truncate(from);
            model.add(hits(results));
            show(q, results);
            track(results.progress());
            releaseNeighbors();
            if (model.rows.isEmpty() && results.nextCursor() != null) {
                feedback.info("Noch keine Treffer", "Auch dieser Stapel war leer; „Weitere laden“ sucht weiter.");
            } else if (model.rows.isEmpty()) {
                feedback.info("Keine Treffer", "Keine Quelle kennt eine passende Seite, die du lesen darfst.");
            } else {
                feedback.clear();
            }
        }, error -> {
            if (id != request) {
                return;
            }
            searching = null;
            releaseNeighbors();
            overlay.setBusy(false);
            // Die bisherigen Treffer bleiben stehen; eine neue Suche beginnt von vorn.
            run.setEnabled(true);
            reset();
            feedback.problem(error);
        });
    }

    /**
     * Folgt dem Laden eines Stapels ({@code Results.progress()}): zeigt „x von y“, meldet sich für seinen Ladestand an
     * und liest ihn danach einmal, damit keine Meldung verloren geht; der Core meldet bis {@code done}. Ohne zu
     * ladende Seiten ist die Suche sofort abgeschlossen.
     */
    private void track(KeywordSearch.Progress progress) {
        untrack();
        cancelled = false;
        if (progress == null) {
            progressId = null;
            finished(false);
            return;
        }
        String id = progress.id();
        progressId = id;
        lastLoaded = progress.loaded();
        AppRuntime runtime = context.runtime();
        if (runtime != null) {
            progressSubscription = runtime.workspace().subscribeSearchProgress(id,
                    state -> SwingUtilities.invokeLater(() -> advanced(id, state)));
        }
        progress(progress);
        read();
    }

    /** Beendet das Folgen des laufenden Stapels (neuer Stapel, Abbruch, neue Suche). */
    private void untrack() {
        progressSubscription.close();
        progressSubscription = Subscription.NONE;
    }

    /** Eine Meldung des Core zum Stapel {@code id}: ist mehr geladen, denselben Stapel neu einsortieren. */
    private void advanced(String id, KeywordSearch.Progress state) {
        if (!id.equals(progressId)) {
            return;
        }
        if (state.loaded() > lastLoaded) {
            lastLoaded = state.loaded();
            refresh();
        }
        progress(state);
    }

    /** Liest den Ladestand einmal nach dem Anmelden; kennt der Core den Stapel nicht mehr, lädt nichts mehr. */
    private void read() {
        AppRuntime runtime = context.runtime();
        String id = progressId;
        if (runtime == null || id == null) {
            return;
        }
        Background.run(() -> runtime.workspace().keywordSearchProgress(id), current -> {
            if (!id.equals(progressId)) {
                return;
            }
            if (current.isEmpty()) {
                progressId = null;
                untrack();
                refresh();
                finished(false);
                return;
            }
            advanced(id, current.get());
        }, error -> {
            // Die Anmeldung meldet den nächsten Stand.
        });
    }

    /**
     * Stellt den angezeigten Stapel erneut und ordnet ihn nach der Rangfolge des Core neu ({@code ranked()}): geladene
     * Seiten rücken nach vorn, kein Treffer verschwindet, die Auswahl bleibt. Nie den nächsten Cursor: Neue Funde holt
     * nur „Weitere laden“.
     */
    private void refresh() {
        AppRuntime runtime = context.runtime();
        KeywordSearch.Query q = lastQuery;
        if (runtime == null || q == null || refreshing) {
            return;
        }
        refreshing = true;
        int id = request;
        String cursor = shownCursor;
        int start = batchStart;
        // Fundstelle 23: dieselbe Suche, darum bietet Neusortieren nur Funde an, die sie noch nicht angeboten hat.
        String core = keywordSearchId;
        Background.run(() -> runtime.workspace().keywordSearchIn(core, q, cursor, LIMIT, null), results -> {
            refreshing = false;
            if (id != request) {
                return;
            }
            Hit selected = selectedHit();
            model.reorder(start, hits(results));
            select(selected);
            show(q, results);
            if (progressId == null && !cancelled) {
                finished(false);
            }
        }, error -> refreshing = false);
    }

    /** Bricht das Laden ab; die Treffer bleiben, die Zeile meldet den Abbruch. */
    private void cancelLoading() {
        if (stopTermPages() && progressId == null && running == null) {
            updateCancel();
            return;
        }
        if (running != null) {
            // Der Core bricht Suchanfragen und Ladevorgänge ab und meldet completed(CANCELLED).
            running.cancel();
            runningCancelRequested = true;
            updateCancel();
            return;
        }
        AppRuntime runtime = context.runtime();
        String id = progressId;
        if (runtime == null || id == null) {
            return;
        }
        untrack();
        progressId = null;
        cancelled = true;
        // Kein erneutes Suchen danach: Es lüde die abgebrochenen Seiten wieder in den Cache.
        Background.run(() -> runtime.workspace().cancelKeywordSearch(id), ignored -> finished(true),
                error -> finished(true));
    }

    /**
     * „Stopp“ im Ring über der Liste: Was auch immer gerade läuft, hört auf, und die Treffer, die schon da sind,
     * bleiben stehen und bedienbar. Eine semantische Suche beendet der Core selbst ({@code completed(CANCELLED)}); eine
     * laufende Schlagwortsuche wird unterbrochen, ihr spätes Ergebnis verfällt; danach endet das Laden in den Cache.
     */
    void cancelSearch() {
        boolean terms = stopTermPages();
        if (running != null) {
            running.cancel();
            runningCancelRequested = true;
            updateCancel();
            return;
        }
        if (searching != null) {
            request++;
            searching.cancel(true);
            searching = null;
            overlay.setBusy(false);
            run.setEnabled(true);
            awaitingHits = false;
            // Ohne fertigen Stapel gibt es keinen Cursor: „Weitere laden“ gilt erst wieder nach einer vollständigen
            // Suche.
            nextCursor = null;
            finished(true);
            status.setText(Texts.count(model.rows.size(), "Treffer", "Treffer") + " · Suche abgebrochen");
            status.setForeground(Comic.MUTED);
            shownTermTail = "";
            return;
        }
        if (terms && progressId == null) {
            updateCancel();
            return;
        }
        cancelLoading();
    }

    /** Beendet das Verfolgen ohne Meldung, etwa bei einer neuen Eingabe; der Core bricht das Laden ab. */
    private void stopLoading() {
        // Das Nachladen läuft als Hintergrundauftrag weiter; nur die Ansicht folgt ihm nicht mehr.
        if (neighborLoadWatch != null) {
            neighborLoadWatch.close();
            neighborLoadWatch = null;
        }
        heldNeighbors.clear();
        heldTermPages.clear();
        stopTermPages();
        if (running != null) {
            running.cancel();
            running = null;
        }
        if (searching != null) {
            searching.cancel(true);
            searching = null;
        }
        overlay.setBusy(false);
        AppRuntime runtime = context.runtime();
        String id = progressId;
        untrack();
        progressId = null;
        idle(null);
        progressText.setText("");
        if (runtime != null && id != null) {
            Background.run(() -> runtime.workspace().cancelKeywordSearch(id), ignored -> { }, ignored -> { });
        }
    }

    private Hit selectedHit() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.rows.get(table.convertRowIndexToModel(row));
    }

    private void select(Hit hit) {
        int at = hit == null ? -1 : model.indexOf(hit);
        if (at >= 0) {
            int view = table.convertRowIndexToView(at);
            if (view >= 0) {
                table.getSelectionModel().setSelectionInterval(view, view);
            }
        }
    }

    private void reset() {
        lastQuery = null;
        nextCursor = null;
        shownCursor = null;
        stopLoading();
        updateMore();
        status.setText(Texts.count(model.rows.size(), "Treffer", "Treffer"));
        status.setForeground(Comic.MUTED);
    }

    private void show(KeywordSearch.Query q, KeywordSearch.Results results) {
        lastQuery = q;
        nextCursor = results.nextCursor();
        // complete ist auch false, solange noch Treffer folgen; unvollständig heißt: eine Quelle fiel aus, oder am
        // Ende ließ sich nicht alles prüfen.
        incomplete |= !results.complete() && results.nextCursor() == null
                || results.sources().stream().anyMatch(state -> state.status()
                == KeywordSearch.SourceState.Status.UNAVAILABLE);
        // Folgestapel nennen nur noch offene Quellen; ein späterer Ausfall ersetzt den früheren Zustand.
        results.sources().forEach(state -> states.put(state.sourceId(), state));
        offerIndexing(results.sources());
        updateMore();
        StringBuilder line = new StringBuilder(Texts.count(model.rows.size(), "Treffer", "Treffer"));
        if (results.progress() != null && !results.progress().done()) {
            line.append(" · Seiten laden in den Cache, geladene rücken nach vorn");
        } else if (nextCursor != null) {
            line.append(" · weitere vorhanden");
        }
        for (KeywordSearch.SourceState state : states.values()) {
            switch (state.status()) {
                case NOT_SUPPORTED -> line.append(" · ").append(title(state.sourceId()))
                        .append(": keine eigene Suche");
                case UNAVAILABLE -> line.append(" · ").append(title(state.sourceId())).append(": nicht erreichbar")
                        .append(state.reason() == null ? "" : " (" + reason(state.reason()) + ")");
                case INDEXED -> line.append(" · ").append(title(state.sourceId()))
                        .append(": ").append(NOT_ONLINE_SEARCHABLE);
                case SEARCHED -> {
                    // Von der Quelle gefunden und im Cache gesucht (ADR 0013): der Normalfall, ohne Zusatz.
                }
            }
        }
        if (incomplete) {
            line.append(" · unvollständig: nicht jede Quelle oder jeder Treffer ließ sich prüfen");
        }
        shownTermTail = termTail();
        status.setText(line.append(synonymNote).append(shownTermTail).toString());
        status.setForeground(incomplete || !synonymNote.isEmpty() || termPagesNoteText != null ? Comic.ORANGE
                : Comic.MUTED);
    }

    /** Die schmale Zeile unter dem Suchfeld: Balken und „x von y“, bis der Core meldet, dass nichts mehr lädt. */
    void progress(KeywordSearch.Progress state) {
        if (state.done()) {
            progressId = null;
            untrack();
            if (!state.cancelled()) {
                refresh();
            }
            finished(state.cancelled());
            return;
        }
        progressText.setVisible(true);
        progressText.setText(progressLine(state.loaded(), state.total(), model.rows.size()));
        endState.setVisible(false);
        updateCancel();
        updateMore();
    }

    /** Gelb in der Statuszeile: Ein fehlender Nachbar heißt dann nicht, dass es ihn nicht gibt (Auflage AT). */
    private void uncheckedNeighbors() {
        status.setText(status.getText().strip() + " · " + NEIGHBORS_UNCHECKED);
        status.setForeground(Comic.ORANGE);
    }

    /**
     * Suchmethode „Nachbarseiten“ zur Schlagwortsuche, einmal nach dem ersten fertig geladenen Stapel: Nachbarn der
     * angezeigten Treffer, die dieselbe Anfrage treffen, rücken hinten nach; Auswahl und Position bleiben.
     */
    private void searchNeighbors() {
        AppRuntime runtime = context.runtime();
        KeywordSearch.Query q = lastQuery;
        int id = request;
        if (neighborsFor != id || runtime == null || q == null || model.rows.isEmpty()) {
            return;
        }
        neighborsFor = -1;
        List<KeywordSearch.Hit> shown = shownHits();
        neighborsPending = id;
        updateMore();
        int sid = searchId;
        Background.run(() -> runtime.workspace().neighborSearch(q, shown, true), found -> {
            if (id != request) {
                return;
            }
            neighborsPending = -1;
            updateMore();
            appendNeighbors(found.hits());
            found.load().ifPresent(load -> followNeighborLoad(sid, q, load));
        }, error -> {
            if (id == request) {
                neighborsPending = -1;
                updateMore();
                LOG.log(System.Logger.Level.INFO, "Nachbarseiten nicht gesucht ({0})", error.getClass().getName());
                uncheckedNeighbors();
            }
        });
    }

    /**
     * Suchmethode „Fachbegriffe“ (W8) zur Schlagwortsuche, einmal nach dem ersten fertig geladenen Stapel: Seiten mit
     * Aussagen zu Fachbegriffen der Anfrage rücken hinten nach, ohne schon gezeigte; Auswahl und Position bleiben.
     * Eine neue Suche bricht sie zwischen zwei Proben ab. Kappung, Abbruch oder Scheitern stehen gelb in der
     * Statuszeile, nie als Fehler.
     */
    private void searchTermPages() {
        AppRuntime runtime = context.runtime();
        KeywordSearch.Query q = lastQuery;
        int id = request;
        if (termPagesFor != id || runtime == null || q == null) {
            return;
        }
        termPagesFor = -1;
        List<com.aresstack.spaceharvester.application.snapshot.PageKey> shown = model.rows.stream()
                .map(row -> new com.aresstack.spaceharvester.application.snapshot.PageKey(row.sourceId(),
                        row.pageId())).toList();
        int sid = searchId;
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        termPagesStop = stop;
        long deadline = System.nanoTime() + TERM_PAGES_DEADLINE.toNanos();
        java.util.function.BooleanSupplier cancelled = () -> stop.get() || sid != searchId
                || System.nanoTime() - deadline >= 0;
        termPagesRunning = true;
        termSearchesStarted++;
        // Abbrechen steht bereit, solange sie läuft (wie beim Laden in den Cache).
        updateCancel();
        applyTermTail();
        Background.run(() -> runtime.workspace().termSearch(q, shown, cancelled), found -> {
            if (sid != searchId) {
                return;
            }
            endTermPages(stop);
            ProgressiveResult.TechnicalTerms state = !found.complete() || stop.get()
                    ? ProgressiveResult.TechnicalTerms.UNCHECKED
                    : found.capped() ? ProgressiveResult.TechnicalTerms.CAPPED
                    : ProgressiveResult.TechnicalTerms.CHECKED;
            termPagesNoteText = termPagesNote(state);
            appendTermPages(found.hits());
            applyTermTail();
        }, error -> {
            if (sid == searchId) {
                endTermPages(stop);
                LOG.log(System.Logger.Level.INFO, "Fachbegriffe nicht gesucht ({0})", error.getClass().getName());
                termPagesNoteText = TERM_PAGES_UNCHECKED;
                applyTermTail();
            }
        });
    }

    /** Die Suche nach Seiten zu Fachbegriffen ist zu Ende; ohne weiteres Laden geht Abbrechen wieder. */
    private void endTermPages(java.util.concurrent.atomic.AtomicBoolean stop) {
        termPagesRunning = false;
        if (termPagesStop == stop) {
            termPagesStop = null;
        }
        updateCancel();
    }

    /** Stoppt eine laufende Suche nach Seiten zu Fachbegriffen; gefundene bleiben. {@code true}, wenn eine lief. */
    private boolean stopTermPages() {
        java.util.concurrent.atomic.AtomicBoolean stop = termPagesStop;
        if (stop == null) {
            return false;
        }
        stop.set(true);
        return true;
    }

    /** Neue Suche: kein Stand zu Fachbegriffen mehr. */
    private void resetTermPages() {
        stopTermPages();
        termPagesStop = null;
        termPagesAdded = 0;
        termPagesRunning = false;
        termPagesNoteText = null;
        shownTermTail = "";
    }

    /** Der Teil der Statuszeile zu Fachbegriffen: angehängte Seiten, laufender Schritt, gelber Hinweis. */
    String termTail() {
        StringBuilder tail = new StringBuilder();
        if (termPagesAdded > 0) {
            tail.append(" · ").append(Texts.count(termPagesAdded, "Seite", "Seiten")).append(" zu Fachbegriffen dazu");
        }
        if (termPagesRunning) {
            tail.append(" · ").append(TERM_PAGES_RUNNING);
        }
        if (termPagesNoteText != null) {
            tail.append(" · ").append(termPagesNoteText);
        }
        return tail.toString();
    }

    /**
     * Die einzige Stelle, die den Stand zu Fachbegriffen in die Statuszeile schreibt: Zahl der Treffer vorn neu, der
     * frühere Teil zu Fachbegriffen geht, der aktuelle kommt ans Ende; der Rest der Zeile bleibt.
     */
    void applyTermTail() {
        status.setText(withTermTail(status.getText(), shownTermTail, model.rows.size(), termTail()));
        shownTermTail = termTail();
        if (termPagesNoteText != null) {
            status.setForeground(Comic.ORANGE);
        }
    }

    /** Die Statuszeile mit neuer Trefferzahl und ausgetauschtem Teil zu Fachbegriffen. */
    static String withTermTail(String status, String oldTail, int total, String newTail) {
        String text = status == null ? "" : status.strip();
        if (!oldTail.isEmpty()) {
            // Steht der frühere Teil nicht mehr am Ende (etwa Nachbarseiten danach), geht er trotzdem.
            text = text.replace(oldTail, "").replace(oldTail.strip(), "").strip();
        }
        java.util.regex.Matcher count = java.util.regex.Pattern.compile("^(\\d+|Keine) Treffer").matcher(text);
        if (count.find()) {
            text = Texts.count(total, "Treffer", "Treffer") + text.substring(count.end());
        } else if (text.isEmpty()) {
            text = Texts.count(total, "Treffer", "Treffer");
        }
        if (text.startsWith("· ")) {
            text = text.substring(2);
        }
        return text + newTail;
    }

    /** Die gelbe Zeile zum Stand der Seiten zu Fachbegriffen oder {@code null}, wenn es nichts zu sagen gibt. */
    static String termPagesNote(ProgressiveResult.TechnicalTerms state) {
        return switch (state) {
            case UNCHECKED -> TERM_PAGES_UNCHECKED;
            case CAPPED -> TERM_PAGES_CAPPED;
            case OFF, CHECKED -> null;
        };
    }

    private void noteTermPages(String note) {
        termPagesNoteText = note;
        applyTermTail();
    }

    /** Für Tests: der gelbe Hinweis zu Fachbegriffen. */
    void termPagesNoteForTest(String note) {
        noteTermPages(note);
    }

    /**
     * Hängt Seiten zu Fachbegriffen hinten an, ohne schon gezeigte; Auswahl und Position bleiben. Lädt gerade ein
     * Stapel, warten sie auf ihn, sonst schnitte sein Ende sie wieder ab.
     */
    void appendTermPages(List<KeywordSearch.Hit> found) {
        if (searching != null) {
            heldTermPages.addAll(found);
            return;
        }
        java.util.Set<List<String>> shown = new java.util.HashSet<>();
        model.rows.forEach(row -> shown.add(java.util.Arrays.asList(row.sourceId(), row.pageId(), row.attachment())));
        List<Hit> fresh = new ArrayList<>();
        for (KeywordSearch.Hit hit : found) {
            Hit row = Hit.of(hit);
            if (shown.add(java.util.Arrays.asList(row.sourceId(), row.pageId(), row.attachment()))) {
                fresh.add(row);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        Hit selected = selectedHit();
        model.append(fresh);
        select(selected);
        termPagesAdded += fresh.size();
        // Treffer sind da: ein „Keine Treffer“ von vorhin gilt nicht mehr.
        awaitingHits = false;
        feedback.clear();
        applyTermTail();
    }

    /**
     * Die angezeigten Treffer für die Nachbarsuche, mit ihren Ursachen: Ausgangsseiten sind nur die eigentlichen
     * Treffer, nie schon angehängte Nachbarn (ein Schritt, Auflage AT); bekannt sind alle.
     */
    private List<KeywordSearch.Hit> shownHits() {
        return model.rows.stream().map(row -> new KeywordSearch.Hit(row.sourceId(), row.pageId(), row.spaceKey(),
                row.title(), row.snippet(), null, null, row.modifiedAt(), null).withReasons(row.reasons()))
                .toList();
    }

    /**
     * Hängt Nachbar-Treffer hinten an, ohne schon gezeigte; Auswahl, Position, Quellenzustände und „unvollständig“
     * bleiben. Lädt gerade ein Stapel, warten sie auf ihn.
     */
    void appendNeighbors(List<KeywordSearch.Hit> found) {
        if (searching != null) {
            heldNeighbors.addAll(found);
            return;
        }
        java.util.Set<List<String>> shown = new java.util.HashSet<>();
        model.rows.forEach(row -> shown.add(java.util.Arrays.asList(row.sourceId(), row.pageId(), row.attachment())));
        List<Hit> fresh = new ArrayList<>();
        for (KeywordSearch.Hit hit : found) {
            Hit row = Hit.of(hit);
            if (shown.add(java.util.Arrays.asList(row.sourceId(), row.pageId(), row.attachment()))) {
                fresh.add(row);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        int before = model.rows.size();
        Hit selected = selectedHit();
        model.append(fresh);
        select(selected);
        status.setText(withNeighbors(status.getText(), before, model.rows.size(), fresh.size()));
        if (!shownTermTail.isEmpty()) {
            applyTermTail();
        }
    }

    /** Nach einem Stapel: die zurückgehaltenen Nachbar-Treffer. */
    void releaseNeighbors() {
        if (!heldTermPages.isEmpty()) {
            List<KeywordSearch.Hit> terms = List.copyOf(heldTermPages);
            heldTermPages.clear();
            appendTermPages(terms);
        }
        if (heldNeighbors.isEmpty()) {
            return;
        }
        List<KeywordSearch.Hit> held = List.copyOf(heldNeighbors);
        heldNeighbors.clear();
        appendNeighbors(held);
    }

    /**
     * Die Statuszeile mit neuer Trefferzahl und der Zahl der Nachbarseiten dazu; eine frühere Angabe „… dazu“ zählt
     * mit, alles Übrige der Zeile bleibt stehen.
     */
    static String withNeighbors(String status, int before, int total, int added) {
        String count = Texts.count(before, "Treffer", "Treffer");
        String rest = status.startsWith(count) ? status.substring(count.length()) : "";
        java.util.regex.Matcher earlier = java.util.regex.Pattern
                .compile("^ · (\\d+) Nachbarseiten? dazu").matcher(rest);
        int neighbors = added;
        if (earlier.find()) {
            neighbors += Integer.parseInt(earlier.group(1));
            rest = rest.substring(earlier.end());
        }
        return Texts.count(total, "Treffer", "Treffer") + " · "
                + Texts.count(neighbors, "Nachbarseite", "Nachbarseiten") + " dazu" + rest;
    }

    /**
     * Fehlende Nachbarn laden als Hintergrundauftrag; die Statuszeile sagt es. Ist er fertig, sucht dieselbe Anfrage
     * die Nachbarn erneut (ohne neues Laden) und reicht die Treffer nach, solange die Ansicht noch dieser Anfrage
     * folgt. Observer, kein Abfragen (ADR 0027).
     */
    private void followNeighborLoad(int sid, KeywordSearch.Query q, NeighborLoader.Load load) {
        if (neighborLoadWatch != null) {
            neighborLoadWatch.close();
            neighborLoadWatch = null;
        }
        String loading = " · " + NEIGHBORS_LOADING;
        status.setText(status.getText().strip() + loading);
        java.util.function.Consumer<Boolean> done = cancelled -> SwingUtilities.invokeLater(() -> {
            if (sid != searchId) {
                return;
            }
            if (neighborLoadWatch != null) {
                neighborLoadWatch.close();
                neighborLoadWatch = null;
            }
            status.setText(status.getText().replace(loading, ""));
            AppRuntime runtime = context.runtime();
            if (Boolean.TRUE.equals(cancelled) || runtime == null || model.rows.isEmpty()) {
                return;
            }
            List<KeywordSearch.Hit> shown = shownHits();
            Background.run(() -> runtime.workspace().neighborSearch(q, shown, false), found -> {
                if (sid == searchId) {
                    appendNeighbors(found.hits());
                }
            }, error -> LOG.log(System.Logger.Level.INFO, "Geladene Nachbarseiten nicht gesucht ({0})",
                    error.getClass().getName()));
        });
        // Die Anmeldung liest den Auftragsspeicher: nicht auf dem EDT (Auflage AT).
        Background.run(() -> load.whenDone(done), subscription -> {
            if (sid == searchId) {
                neighborLoadWatch = subscription;
            } else {
                subscription.close();
            }
        }, error -> LOG.log(System.Logger.Level.INFO, "Nachladen der Nachbarseiten nicht verfolgt ({0})",
                error.getClass().getName()));
    }

    /** Ende der Zeile: „Die Suche ist abgeschlossen“ mit der Zahl der Treffer, nach einem Abbruch als solcher. */
    private void finished(boolean cancelled) {
        if (awaitingHits) {
            awaitingHits = !cancelled && model.rows.isEmpty() && refreshing;
            if (model.rows.isEmpty() && !awaitingHits) {
                feedback.info("Keine Treffer", "Keine Quelle kennt eine passende Seite, die du lesen darfst.");
            } else if (!model.rows.isEmpty()) {
                feedback.clear();
            }
        }
        idle(cancelled ? "Abgebrochen" : "Fertig");
        progressText.setText((cancelled ? "Laden abgebrochen" : DONE) + " · "
                + Texts.count(model.rows.size(), "Treffer", "Treffer"));
        // Die Zahl der Treffer nennt danach der Stand rechts daneben; keine zweite Zeile mit derselben Zahl.
        progressText.setVisible(false);
        updateMore();
        if (!cancelled && running == null) {
            searchNeighbors();
            searchTermPages();
        } else if (cancelled) {
            neighborsFor = -1;
            termPagesFor = -1;
        }
    }

    /** Leere Spur ohne Abbrechen; mittig der Endzustand, {@code null} ohne. */
    void idle(String end) {
        updateCancel();
        endState.setText(end == null ? " " : end);
        endState.setVisible(end != null);
    }

    /**
     * Abbrechen aus dem Zustand: sichtbar und aktiv, solange irgendein Schritt läuft (Laden in den Cache, progressive
     * Suche, Seiten zu Fachbegriffen); nach angefordertem Abbruch der progressiven Suche sichtbar, aber gesperrt.
     */
    void updateCancel() {
        java.util.concurrent.atomic.AtomicBoolean stop = termPagesStop;
        boolean terms = stop != null && !stop.get();
        boolean loading = progressId != null && !cancelled;
        boolean semantic = running != null;
        cancel.setVisible(terms || loading || semantic);
        cancel.setEnabled(terms || loading || semantic && !runningCancelRequested);
    }

    /** Sofort beim Start: unbestimmter Balken, bis der Core antwortet. */
    private void starting(String text) {
        overlay.setBusy(true);
        endState.setVisible(false);
        progressText.setText(text);
        progressText.setVisible(true);
        updateCancel();
        more.setVisible(false);
        more.setEnabled(false);
    }

    /** „Weitere laden“ nur nach Abschluss des Ladens und nur, wenn der Core weitere Treffer kennt. */
    private void updateMore() {
        boolean available = nextCursor != null && progressId == null;
        more.setVisible(available);
        more.setEnabled(available && neighborsPending != request);
    }

    /** „12 von 25 Seiten im Cache · 7 Treffer“: geladene gegen gefundene Seiten dieser Suche. */
    static String progressLine(int loaded, int total, int hits) {
        return loaded + " von " + Texts.count(total, "Seite", "Seiten") + " im Cache · "
                + Texts.count(hits, "Treffer", "Treffer");
    }

    /** Ob mindestens eine Quelle gesucht hat. */
    private boolean searched() {
        return states.values().stream().anyMatch(s -> s.status() == KeywordSearch.SourceState.Status.SEARCHED
                || s.status() == KeywordSearch.SourceState.Status.INDEXED
                || s.status() == KeywordSearch.SourceState.Status.LOCAL);
    }

    private String title(String sourceId) {
        AppRuntime runtime = context.runtime();
        if (runtime != null) {
            for (HarvestSource source : runtime.workspace().sources()) {
                if (source.id().equals(sourceId) && source.title() != null && !source.title().isBlank()) {
                    return source.title();
                }
            }
        }
        return sourceId;
    }

    /** {@code SourceAccessException.Reason} verständlich. */
    private static String reason(String reason) {
        return switch (reason) {
            case "ACCESS_DENIED" -> "Anmeldung abgelehnt";
            case "UNAVAILABLE" -> "keine Verbindung";
            case "RATE_LIMITED" -> "gedrosselt";
            case "INVALID_RESPONSE" -> "unerwartete Antwort";
            default -> reason.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        };
    }

    /** Für Tests: die Zeile unter der Liste. */
    String statusText() {
        return status.getText();
    }

    /** Die ganze Liste des Stapels: geladene Treffer zuerst, dann die vorläufigen ({@code ranked()}). */
    private static List<Hit> hits(KeywordSearch.Results results) {
        return results.ranked().stream().map(Hit::of).toList();
    }

    /**
     * Eine Zeile; die Quelle stammt aus dem Treffer selbst, nie aus der Auswahl.
     *
     * @param attachment Dateiname des Anhangs, in dessen Text der Treffer liegt (#183), oder {@code null}
     * @param passageId  Passage-ID des Absatzes, aus dem der Auszug stammt, oder {@code null}
     * @param reasons    warum der Treffer gefunden wurde (R1), wie die Suche es meldet
     */
    record Hit(String sourceId, String pageId, String title, String spaceKey, String snippet, Instant modifiedAt,
               String attachment, String passageId, List<HitReason> reasons) {

        Hit {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        Hit(String sourceId, String pageId, String title, String spaceKey, String snippet, Instant modifiedAt,
            String attachment, String passageId) {
            this(sourceId, pageId, title, spaceKey, snippet, modifiedAt, attachment, passageId, List.of());
        }

        Hit(String sourceId, String pageId, String title, String spaceKey, String snippet, Instant modifiedAt) {
            this(sourceId, pageId, title, spaceKey, snippet, modifiedAt, null, null);
        }

        Hit(String sourceId, String pageId, String title, String spaceKey, String snippet, Instant modifiedAt,
            String attachment) {
            this(sourceId, pageId, title, spaceKey, snippet, modifiedAt, attachment, null);
        }

        /** Der Auszug in einer Zeile; bei einem Anhangstreffer vorn „Treffer im Anhang „Name““. */
        String excerpt() {
            String text = snippet == null ? "" : snippet.replaceAll("\\s+", " ").strip();
            if (attachment == null) {
                return text;
            }
            String where = "Treffer im Anhang „" + attachment + "“";
            return text.isEmpty() ? where : where + " · " + text;
        }

        /** Die Marke des Treffergrunds vor dem Auszug oder {@code null} (R1). */
        MarkedTextRenderer.Mark mark() {
            return HitReasons.mark(reasons);
        }

        /** Tooltip der Marke; bei einem Nachbarn die Ausgangsseite, sonst {@code null}. */
        String markTip() {
            return HitReasons.tip(reasons);
        }

        /** Eine aufgelöste Adresse; statt eines Auszugs die Version. */
        static Hit of(PageAddresses.Match match) {
            return new Hit(match.sourceId(), match.pageId(), match.title(), match.spaceKey(),
                    match.version() == null ? "" : "Version " + match.version(), null);
        }

        static Hit of(KeywordSearch.Hit hit) {
            return new Hit(hit.sourceId(), hit.pageId(), hit.title(), hit.spaceKey(), hit.snippet(), hit.modifiedAt(),
                    hit.attachment() == null ? null : hit.attachment().title().isBlank()
                            ? hit.attachment().attachmentId() : hit.attachment().title(), hit.passageId(),
                    hit.reasons());
        }
    }

    static final class HitsModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Titel", "Quelle", "Space", "Auszug", "Geändert"};
        /** Spalte des Titels, vorn mit dem Stern. */
        static final int TITLE = 0;
        /** Spalte des Auszugs, vorn mit der Marke des Treffergrunds. */
        static final int EXCERPT = 3;
        List<Hit> rows = new ArrayList<>();

        void set(List<Hit> hits) {
            rows = new ArrayList<>(hits);
            fireTableDataChanged();
        }

        /**
         * Hängt Treffer an; eine schon gezeigte Seite (gleiche Quelle und ID) wird ersetzt statt verdoppelt, so kann
         * ein Treffer aus dem Cache einen vorläufigen ersetzen.
         */
        void add(List<Hit> hits) {
            boolean replaced = false;
            List<Hit> appended = new ArrayList<>();
            for (Hit hit : hits) {
                int at = indexOf(hit);
                if (at >= 0) {
                    rows.set(at, hit);
                    replaced = true;
                } else if (appended.stream().noneMatch(other -> same(other, hit))) {
                    appended.add(hit);
                }
            }
            int first = rows.size();
            rows.addAll(appended);
            if (replaced) {
                fireTableDataChanged();
            } else if (!appended.isEmpty()) {
                fireTableRowsInserted(first, rows.size() - 1);
            }
        }

        /** Hängt nur Treffer an, deren Seite noch nicht in der Liste steht; vorhandene Zeilen bleiben unverändert. */
        void append(List<Hit> hits) {
            List<Hit> appended = new ArrayList<>();
            for (Hit hit : hits) {
                if (indexOf(hit) < 0 && appended.stream().noneMatch(other -> same(other, hit))) {
                    appended.add(hit);
                }
            }
            if (!appended.isEmpty()) {
                int first = rows.size();
                rows.addAll(appended);
                fireTableRowsInserted(first, rows.size() - 1);
            }
        }

        /** Nimmt alle Zeilen ab {@code from} weg; davor bleibt alles. */
        void truncate(int from) {
            if (from < rows.size()) {
                int last = rows.size() - 1;
                rows = new ArrayList<>(rows.subList(0, Math.max(0, from)));
                fireTableRowsDeleted(Math.max(0, from), last);
            }
        }

        /**
         * Ordnet die Zeilen ab {@code start} nach {@code ranked} neu: dessen Treffer zuerst in seiner Reihenfolge,
         * danach die übrigen bisherigen in ihrer Reihenfolge. Keine Zeile verschwindet; frühere Stapel bleiben oben.
         */
        void reorder(int start, List<Hit> ranked) {
            int from = Math.min(start, rows.size());
            List<Hit> next = new ArrayList<>(rows.subList(0, from));
            List<Hit> rest = new ArrayList<>(rows.subList(from, rows.size()));
            for (Hit hit : ranked) {
                int fixed = indexIn(next, hit);
                if (fixed >= 0) {
                    if (fixed < from) {
                        next.set(fixed, hit);
                    }
                    continue;
                }
                next.add(hit);
            }
            for (Hit hit : rest) {
                if (indexIn(next, hit) < 0) {
                    next.add(hit);
                }
            }
            if (!next.equals(rows)) {
                rows = next;
                fireTableDataChanged();
            }
        }

        private static int indexIn(List<Hit> list, Hit hit) {
            for (int i = 0; i < list.size(); i++) {
                if (same(list.get(i), hit)) {
                    return i;
                }
            }
            return -1;
        }

        int indexOf(Hit hit) {
            for (int i = 0; i < rows.size(); i++) {
                if (same(rows.get(i), hit)) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean same(Hit a, Hit b) {
            return a.sourceId().equals(b.sourceId()) && a.pageId().equals(b.pageId());
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
            Hit hit = rows.get(row);
            return switch (column) {
                case 0 -> hit.title();
                case 1 -> hit.sourceId();
                case 2 -> hit.spaceKey();
                case 3 -> hit.excerpt();
                default -> Texts.time(hit.modifiedAt());
            };
        }
    }
}
