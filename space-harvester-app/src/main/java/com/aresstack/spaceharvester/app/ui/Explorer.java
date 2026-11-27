package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.controls.TreeTextTip;
import com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons;
import com.aresstack.spaceharvester.app.ui.content.AnalyzerNames;
import com.aresstack.spaceharvester.app.event.Severity;
import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.query.SpaceTree;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.InvalidNavigationCursorException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Set;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Der dauerhafte Explorer links im Hauptfenster: ein Baum über alle Wissensquellen, Wissensquelle → Space → Seite →
 * Unterseite. Spaces und Seiten lädt er erst beim Aufklappen, Kindseiten live über die Navigation des Core
 * ({@code PageNavigation.children}, #177, in der Reihenfolge der Quelle, stapelweise mit „Weitere laden …“); Quellen
 * ohne Seitenbaum zeigen den Seitenbaum des Space aus dem Bestand bzw. aus den Metadaten der Quelle. Ein Klick (oder
 * Enter) auf eine Seite öffnet sie unter Inhalte; eine gewählte Quelle oder ein Space wird als Umfang gemeldet. Oben
 * filtert das Feld „Titel filtern …“ nur zur Navigation, nicht zur Suche: Treffer aus den schon geladenen Knoten und aus
 * dem Bestand (Titelsuche des Core).
 */
public final class Explorer {

    static final String LOADING = "Lädt …";
    static final String MORE = "Weitere laden …";
    private static final String TREE = "tree";
    private static final String MATCHES = "matches";
    private static final String FAVORITES = "favorites";
    static final String FAVORITE = "Favorit";
    static final String UNFAVORITE = "Favorit entfernen";
    static final String ONLY_FAVORITES = "Nur Favoriten zeigen";
    static final String STAR_TIP = "Favorit setzen oder entfernen";
    /** Am Zustandspunkt (Angelo, 16.11.2026): beim Darüberfahren Kreispfeile, der Klick holt den Knoten samt Teilbaum nach. */
    static final String REFRESH_TIP = "Klick: Änderungen aus der Quelle nachholen";
    static final String REFRESH = "Änderungen nachholen";
    static final String REFRESH_ALL_TITLE = "Alles aktualisieren?";
    static final String REFRESH_ALL_QUESTION = "Wirklich alle Wissensquellen aktualisieren? Für jeden Space jeder "
            + "Wissensquelle startet sofort ein eigener Auftrag, der die Änderungen nachholt.";
    static final String ALL_NODES = "Alle Knoten zeigen";
    private static final int MATCH_LIMIT = 50;
    private static final int BATCH = 100;
    private static final int ACTION_WIDTH = 30;
    /** Spalte des Bestandszeichens links vom Stern. */
    private static final int BADGE_WIDTH = 22;
    /** Mindestbreite für den Titel vor den Spalten, damit auch bei sehr schmalem Baum wenigstens „…“ bleibt. */
    private static final int MIN_TITLE_WIDTH = 40;
    static final String ALL_TIP = "Alle Wissensquellen wählen: Chat, Export und Analysen beziehen sich dann auf alles";
    /*
     * Indexieren statt Herunterladen (Angelo, 20.11.2026): Erst indexierte Seiten findet die Suche im Volltext. Die
     * Knöpfe stehen nur im Kontextmenü, weil man meist alles indexiert.
     */
    public static final String INDEX = "Indexieren";
    static final String INDEX_TIP = "Lädt den Knoten samt allem darunter vollständig und hält ihn bei jeder "
            + "Synchronisierung aktuell, auch neue Seiten; Bilder und Anhänge folgen danach";
    static final String UNINDEX = "Nicht mehr indexieren";
    static final String UNINDEX_TIP = "Neue Seiten darunter kommen nur noch in den Katalog; was schon geladen ist, "
            + "bleibt und wird weiter aktuell gehalten";
    /*
     * Ausschließen (Angelo, 26.11.2026): für alle Quellen gleich. Der Knoten verschwindet aus Baum und Suche und wird
     * nicht indexiert; gelöscht wird nichts. Wieder einblenden nur unter Wissensquellen › Ausgeschlossen.
     */
    static final String EXCLUDE = "Ausschließen";
    static final String EXCLUDE_TIP = "Blendet den Knoten samt allem darunter aus Baum und Suche aus und indexiert "
            + "ihn nicht mehr; Geladenes bleibt. Wieder einblenden unter Wissensquellen › Ausgeschlossen";
    static final String EXCLUDED_NOTICE = "„%s“ ausgeschlossen · wieder einblenden unter Wissensquellen › "
            + "Ausgeschlossen";
    /*
     * Umbenennen (Angelo, 26.11.2026): Der Anzeigename einer Quelle ist reine Anzeige; derselbe Wert wie „Anzeigename“
     * in der Quellenverwaltung. ID und Ziel bleiben, die Quellen starten nicht neu.
     */
    static final String RENAME = "Umbenennen";
    static final String RENAME_TIP = "Anzeigenamen der Quelle ändern; nur Anzeige, ID und Daten bleiben";
    static final String RENAME_TEXT = "Neuer Anzeigename für die Quelle „%s“ (ID %s). Er dient nur der Anzeige; "
            + "ID, Einstellungen und Daten bleiben.";
    static final String PAGE_ONLY = "Seite indexieren";
    static final String PAGE_ONLY_TIP = "Nur diese Seite ohne Unterseiten laden; Auftrag startet sofort";
    static final String INDEX_ALL = "Alles indexieren";
    static final String INDEX_ALL_TITLE = "Alles indexieren?";
    static final String INDEX_ALL_QUESTION = "Wirklich alle Wissensquellen indexieren? Jeder Space jeder "
            + "Wissensquelle wird vollständig geladen und aktuell gehalten; je Space startet sofort ein Auftrag.";
    static final String ANALYZE = "Analysieren";
    static final String ANALYZE_TIP = "Aussagen lokal mit MaltParser analysieren; mehrere Seiten erst nach Bestätigung,"
            + " Fortschritt unter Aufträge";
    static final String ANALYZE_PAGE = "Nur diese Seite analysieren";
    static final String ANALYZE_PAGE_TIP = "Nur diese Seite ohne Unterseiten analysieren; Auftrag startet sofort";
    static final String ANALYZE_UNAVAILABLE = "Nicht verfügbar: In dieser Installation ist kein Satzanalysator "
            + "(MaltParser-Modell) eingerichtet";
    static final String ANALYZE_NOT_STARTED = "Nicht verfügbar: Die Laufzeit ist nicht gestartet";
    static final String CHECK = com.aresstack.spaceharvester.app.ui.content.CheckTexts.JOB;
    static final String CHECK_TIP = "Das Chat-Modell beurteilt die Aussagen der analysierten Seiten mit KI-Freigabe; "
            + "mehrere Seiten erst nach Bestätigung, Fortschritt unter Aufträge";
    static final String CHECK_PAGE = "Nur diese Seite prüfen";
    static final String CHECK_PAGE_TIP = "Nur die Aussagen dieser Seite ohne Unterseiten prüfen; Auftrag startet sofort";
    static final String CHECK_NO_MODEL = "Nicht verfügbar: Es ist kein Modell für „" + CHECK + "“ eingestellt "
            + "(Einstellungen › Modelle)";
    static final String REANALYZE = "Bereits analysierte Seiten erneut analysieren";
    static final String SIMPLIFY = "Sätze vorher vereinfachen (Vorstufe mit Chat-Modell, nur Seiten mit KI-Freigabe)";
    /** Sicherheitsgrenze: bis hierhin zählt die Schätzung, darüber fragt der Dialog ausdrücklich nach allen Seiten. */
    public static final int ANALYSIS_LIMIT = 500;
    private static final int NOTICE_MS = 8000;

    /** Umfang aus der Auswahl: eine Quelle, ein Space oder nichts (alle). */
    /**
     * Ein Bereich: Quelle, Space oder, mit {@code pageId}, eine Seite samt Unterseiten (Angelo, 23.11.2026);
     * {@code pageTitle} nur für Überschriften.
     */
    public record Scope(String sourceId, String spaceKey, String pageId, String pageTitle) {
        public static final Scope ALL = new Scope(null, null);

        public Scope(String sourceId, String spaceKey) {
            this(sourceId, spaceKey, null, null);
        }
    }

    /** Meldet jede Änderung der Auswahl als Quelle, Space oder Seite (für den Reiter „Export“); {@code null} ohne. */
    public void onSelection(Consumer<com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection> listener) {
        this.selectionListener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Die erste gewählte Zeile als Quelle, Space oder Seite; {@link com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection#ALL} bei gewählter
     * Überschrift; {@code null} ohne Auswahl oder auf einem Hinweis.
     */
    public com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection selection() {
        if (global) {
            return com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection.ALL;
        }
        if (!externalScopes.isEmpty()) {
            Scope scope = externalScopes.getFirst();
            return new com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection.Node(
                    scope.sourceId(), scope.spaceKey(), scope.pageId(),
                    scope.pageTitle() == null ? sourceTitle(scope.sourceId()) : scope.pageTitle());
        }
        TreePath[] paths = tree.getSelectionPaths();
        for (TreePath path : paths == null ? new TreePath[0] : paths) {
            if (path.getLastPathComponent() instanceof Node node) {
                var selection = node.item() instanceof KnowledgeNode known ? known.selection() : null;
                if (selection != null) {
                    return selection;
                }
            }
        }
        return null;
    }

    sealed interface Item permits KnowledgeNode, MoreItem, Note {
    }

    /**
     * Ein Knoten der Wissensquellen, gleich welcher Quelle und welcher Tiefe (Angelo 26.11.2026: Polymorphie statt
     * Sonderfälle nach Ebene oder Quellart): Quelle, Space bzw. Site, Seite, Datei, Ordner oder bloße Gruppierung der
     * Navigation. Was eine Zeile kann (Bestandszeichen, Favorit, Nachholen, Indexieren, Ausschließen, Analysieren) und
     * welchen Bereich sie meint, sagt der Knoten selbst; Baum, Kontextmenü und Zeichnung fragen nur diese Schnittstelle.
     */
    sealed interface KnowledgeNode extends Item permits SourceItem, SpaceItem, PageItem {
        String sourceId();

        /** Der Space des Knotens; {@code null} an der Quelle. */
        String spaceKey();

        /** Seite bzw. Ordner des Knotens; {@code null} an Quelle und Space. */
        String pageId();

        /** Das Bestandszeichen der Zeile (Quelle über die Spaces, die der Baum von ihr kennt). */
        TreeStates.Badge badge(TreeStates states, Map<String, Set<String>> treeSpaces);

        /** Bereich für Chat, Suche und Graph; {@code null}, wenn der Knoten keinen eigenen meint. */
        Scope scope();

        /** Auswahl für den Reiter „Export“; {@code null}, wenn der Knoten nichts Exportierbares meint. */
        com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection.Node selection();

        /**
         * Umfang von „Analysieren“: Blatt bzw. {@code pageOnly} → nur der Knoten, sonst samt allen Nachfahren;
         * {@code null}, wenn es nichts zu analysieren gibt.
         */
        StatementAnalysisJobs.Scope analysisScope(boolean pageOnly);

        /** Favorit werden (Stern rechts in der Zeile). */
        default boolean starrable() {
            return true;
        }

        /** Samt Teilbaum von der Quelle nachholen (Kreispfeile auf dem Bestandszeichen, Kontextmenü). */
        default boolean refreshable() {
            return true;
        }

        /** Zum Indexieren markieren (Knoten samt allem darunter). */
        default boolean indexable() {
            return true;
        }

        /** Aus Baum, Suche und Index ausschließen; eine ganze Quelle blendet man unter „Im Wissensbaum anzeigen“ aus. */
        default boolean excludable() {
            return true;
        }

        /** Zeigt der Titelfilter den Knoten als Treffer. */
        default boolean findable() {
            return false;
        }
    }

    record SourceItem(String sourceId, String title) implements KnowledgeNode {
        @Override
        public String spaceKey() {
            return null;
        }

        @Override
        public String pageId() {
            return null;
        }

        @Override
        public TreeStates.Badge badge(TreeStates states, Map<String, Set<String>> treeSpaces) {
            return states.all(List.of(sourceId), treeSpaces);
        }

        @Override
        public Scope scope() {
            return new Scope(sourceId, null);
        }

        @Override
        public com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection.Node selection() {
            return new com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection.Node(sourceId, null, null,
                    title);
        }

        @Override
        public StatementAnalysisJobs.Scope analysisScope(boolean pageOnly) {
            return new StatementAnalysisJobs.Scope.Source(sourceId);
        }

        @Override
        public boolean excludable() {
            return false;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    record SpaceItem(String sourceId, String spaceKey, String name) implements KnowledgeNode {
        @Override
        public String pageId() {
            return null;
        }

        @Override
        public TreeStates.Badge badge(TreeStates states, Map<String, Set<String>> treeSpaces) {
            return states.space(sourceId, spaceKey);
        }

        @Override
        public Scope scope() {
            return new Scope(sourceId, spaceKey);
        }

        @Override
        public com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection.Node selection() {
            return new com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection.Node(sourceId, spaceKey, null,
                    name);
        }

        @Override
        public StatementAnalysisJobs.Scope analysisScope(boolean pageOnly) {
            return new StatementAnalysisJobs.Scope.Space(sourceId, spaceKey);
        }

        @Override
        public String toString() {
            return name.equals(spaceKey) ? spaceKey : name + " (" + spaceKey + ")";
        }
    }

    /**
     * Ein Knoten unterhalb des Space: Seite bzw. Datei ({@link DocumentItem}), Ordner ({@link FolderItem}) oder bloße
     * Gruppierung der Navigation ({@link GroupItem}). Welche Klasse, entscheidet {@link #of} allein aus der Art, die
     * die Quelle meldet; in jeder Tiefe gleich.
     */
    sealed interface PageItem extends KnowledgeNode permits DocumentItem, FolderItem, GroupItem {
        String title();

        /** Ob die Quelle Kinder meldet; {@code null}, wenn sie es nicht weiß. */
        Boolean hasChildren();

        HarvestSource.PageNode.Kind kind();

        static PageItem of(String sourceId, String spaceKey, String pageId, String title, Boolean hasChildren,
                           HarvestSource.PageNode.Kind kind) {
            return switch (kind == null ? HarvestSource.PageNode.Kind.PAGE : kind) {
                case FOLDER -> new FolderItem(sourceId, spaceKey, pageId, title, hasChildren);
                case GROUP -> new GroupItem(sourceId, spaceKey, pageId, title, hasChildren);
                case PAGE, FILE -> new DocumentItem(sourceId, spaceKey, pageId, title, hasChildren, kind == null
                        ? HarvestSource.PageNode.Kind.PAGE : kind);
            };
        }

        static PageItem of(String sourceId, String spaceKey, String pageId, String title, Boolean hasChildren) {
            return of(sourceId, spaceKey, pageId, title, hasChildren, HarvestSource.PageNode.Kind.PAGE);
        }

        /** Blatt: die Quelle sagt ausdrücklich, dass der Knoten keine Kinder hat (wie die Darstellung im Baum). */
        default boolean leaf() {
            return Boolean.FALSE.equals(hasChildren());
        }

        @Override
        default TreeStates.Badge badge(TreeStates states, Map<String, Set<String>> treeSpaces) {
            return states.page(sourceId(), spaceKey(), pageId(), !leaf());
        }

        @Override
        default Scope scope() {
            return new Scope(sourceId(), spaceKey(), pageId(), title());
        }

        @Override
        default com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection.Node selection() {
            return new com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection.Node(sourceId(), spaceKey(),
                    pageId(), title());
        }

        @Override
        default StatementAnalysisJobs.Scope analysisScope(boolean pageOnly) {
            return pageOnly || leaf()
                    ? new StatementAnalysisJobs.Scope.SinglePage(sourceId(), spaceKey(), pageId())
                    : new StatementAnalysisJobs.Scope.Subtree(sourceId(), spaceKey(), pageId());
        }

        @Override
        default boolean findable() {
            return true;
        }
    }

    /** Seite (Confluence, Wiki …) oder Datei: hat eigenen Inhalt zum Lesen. */
    record DocumentItem(String sourceId, String spaceKey, String pageId, String title, Boolean hasChildren,
                        HarvestSource.PageNode.Kind kind) implements PageItem {
        @Override
        public String toString() {
            return title;
        }
    }

    /**
     * Ordner, den die Quelle als eigene Ressource führt (Ordner-Quelle, BSCW, SharePoint-Bibliotheken und -Ordner):
     * kein eigener Inhalt, aber im Bestand mit Teilbaum; darum alles wie an einer Seite.
     */
    record FolderItem(String sourceId, String spaceKey, String pageId, String title, Boolean hasChildren)
            implements PageItem {
        @Override
        public HarvestSource.PageNode.Kind kind() {
            return HarvestSource.PageNode.Kind.FOLDER;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /**
     * Bloße Gruppierung der Navigation (MediaWiki „Alle Seiten“, Kategorien): keine Ressource im Bestand, kein
     * Teilbaum, den Ausschließen, Indexieren oder Favoriten treffen könnten. Ihr Bereich ist der Space.
     */
    record GroupItem(String sourceId, String spaceKey, String pageId, String title, Boolean hasChildren)
            implements PageItem {
        @Override
        public HarvestSource.PageNode.Kind kind() {
            return HarvestSource.PageNode.Kind.GROUP;
        }

        @Override
        public TreeStates.Badge badge(TreeStates states, Map<String, Set<String>> treeSpaces) {
            return states.folder(sourceId, spaceKey, pageId);
        }

        @Override
        public Scope scope() {
            return new Scope(sourceId, spaceKey);
        }

        @Override
        public com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection.Node selection() {
            return null;
        }

        @Override
        public StatementAnalysisJobs.Scope analysisScope(boolean pageOnly) {
            return null;
        }

        @Override
        public boolean starrable() {
            return false;
        }

        @Override
        public boolean refreshable() {
            return false;
        }

        @Override
        public boolean indexable() {
            return false;
        }

        @Override
        public boolean excludable() {
            return false;
        }

        @Override
        public boolean findable() {
            return false;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** Platzhalter für den nächsten Stapel Kindseiten. */
    /**
     * „Weitere laden“ bzw. „Ordner neu laden“ ({@code cursor} null). {@code cached}: der nächste Stapel kommt aus dem
     * Cache (Vorschau, ADR 0040 Punkt 11), nicht von der Quelle; der Abgleich mit der Quelle ersetzt ihn.
     */
    record MoreItem(String cursor, boolean cached) implements Item {
        MoreItem(String cursor) {
            this(cursor, false);
        }

        @Override
        public String toString() {
            return cursor == null ? "Ordner neu laden" : MORE;
        }
    }

    /** „Lädt …“, „Keine Seiten“ oder eine Fehlermeldung. */
    record Note(String text) implements Item {
        @Override
        public String toString() {
            return text;
        }
    }

    /** Ein Treffer des Titelfilters. */
    record Match(String sourceId, String spaceKey, String pageId, String title, String where) {
        @Override
        public String toString() {
            return title + "  ·  " + where;
        }
    }

    /** Knoten mit Ladezustand; Kinder kommen erst beim Aufklappen. */
    static final class Node extends DefaultMutableTreeNode {
        boolean loaded;
        boolean loading;
        /** Die gezeigten Kinder kommen aus dem Cache; die Abfrage bei der Quelle steht noch aus oder ist gescheitert. */
        boolean fromCache;
        /** Seit dem ersten Stapel der laufenden Abfrage von der Quelle bestätigte Kinder; {@code null} ohne. */
        java.util.Set<String> confirmed;
        /** Läuft einmal, sobald die Kinder geladen sind (siehe {@link #reveal}). */
        Runnable then;

        Node(Item item) {
            super(item);
        }

        Item item() {
            return (Item) getUserObject();
        }
    }

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 6));
    private final PlaceholderField filter = new PlaceholderField(14, "Titel filtern …");
    private final Node top = new Node(new Note("Wissensquellen"));
    /** Kopf: Überschrift und Filterfeld; als globale Auswahl gelb hinterlegt wie ein gewählter Knoten im Baum. */
    private final com.aresstack.spaceharvester.app.ui.controls.SideHead head =
            new com.aresstack.spaceharvester.app.ui.controls.SideHead("Wissensquellen", filter);
    private final JLabel heading = head.heading();
    /** Rechts neben der Überschrift, tut dasselbe wie der Klick auf sie: zeigt, dass man hier wählen kann. */
    private final IconButton selectAll = new IconButton(Glyph.SELECT_ALL, ALL_TIP);
    /** Rechts von „Alles auswählen“: das Bestandszeichen über alle Quellen, in der Spalte der Zeilen. */
    private final JLabel allBadge = new JLabel();
    /** Rückfrage vor „Alles indexieren“ (Ja/Nein); Tests setzen eine eigene Antwort. */
    private java.util.function.Predicate<java.awt.Component> confirmIndexAll =
            parent -> Dialogs.confirm(parent, INDEX_ALL_TITLE, INDEX_ALL_QUESTION);
    /** Rückfrage vor dem Aktualisieren aller Quellen (Ja/Nein); Tests setzen eine eigene Antwort. */
    private java.util.function.Predicate<java.awt.Component> confirmRefreshAll =
            parent -> Dialogs.confirm(parent, REFRESH_ALL_TITLE, REFRESH_ALL_QUESTION);
    /** Ob die Maus über dem Zustandspunkt der Kopfzeile steht: dann zeigt er die Kreispfeile. */
    private boolean allBadgeHover;
    /**
     * Die Überschrift „Wissensquellen“ ist gewählt (Angelo, 16.11.2026): die globale Auswahl über alle Quellen; Chat,
     * Export und Analysen beziehen sich dann auf alles, im Baum ist nichts gewählt.
     */
    private boolean global;
    private List<Scope> externalScopes = List.of();
    private final DefaultTreeModel model = new DefaultTreeModel(top);
    private final JTree tree = new JTree(model);
    /** Die Bestandszeichen je Zeile (Angelo, 13.11.2026): aus dem Core gemeldet, nie abgefragt. */
    private final TreeStates states;
    private final DefaultListModel<Match> matchModel = new DefaultListModel<>();
    private final JList<Match> matches = new JList<>(matchModel);
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel note = Comic.muted(" ");
    private final IconButton collapseAll = new IconButton(Glyph.COLLAPSE_ALL, "Alles zuklappen");
    private final IconButton showCurrent = new IconButton(Glyph.CROSSHAIR, "Aktuelles Dokument im Baum zeigen");
    /** Unten rechts (Angelo, 13.11.2026): Umriss = alle Knoten, gefüllt = nur die Favoriten als Liste mit Pfad. */
    private final IconButton favoritesOnly = new IconButton(Glyph.STAR, ONLY_FAVORITES);
    private boolean onlyFavorites;
    /** Die Favoritenansicht an Stelle des Baums (ADR 0034, 2a); im Konstruktor angelegt, sie fragt den Baum. */
    private final FavoritesView favorites;
    /** Die zuletzt in der Leseansicht gezeigte Seite; Ziel von „Aktuelles Dokument im Baum zeigen“. */
    private PageNavigation.Position current;
    private final Timer debounce = new Timer(300, e -> runFilter());
    private List<String> shownSources = List.of();
    private Consumer<List<Scope>> scopeListener = scopes -> { };
    private Consumer<com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection> selectionListener =
            selection -> { };
    private int filterRequest;
    /** Während {@link #revealPage} wählt der Baum selbst; der Suchumfang bleibt dann, wie er ist. */
    private boolean revealing;
    /** Zähler der Reveal-Anfragen: Suchtreffer A, dann B – A darf B nicht mehr überschreiben. */
    private int revealRequest;
    /** Zeile, über deren Stern die Maus steht; -1 sonst (Angelo, 16.11.2026: Hervorhebung nur auf dem Stern). */
    private int hoverStarRow = -1;
    /** Zeile, über deren Zustandspunkt die Maus steht; dort stehen dann die Kreispfeile zum Nachholen. */
    private int hoverBadgeRow = -1;
    /** Maus am Baum: Klick, Loslassen nach kleiner Bewegung, Hover (für Tests erreichbar). */
    private MouseAdapter treeMouse;
    /** „Analysieren“ über den Core ({@code Workspace}); Tests setzen eine eigene Umsetzung. */
    private Analyses analyses = new WorkspaceAnalyses();
    /** Bestätigung großer Analyseumfänge; Tests antworten ohne Dialog. */
    private AnalysisConfirmation confirmation = (parent, title, text, action) -> {
        Dialogs.Choice chosen = Dialogs.confirm(parent, title, choice -> analysisText(text,
                        AnalyzerNames.of(choice)), ANALYZER,
                java.util.List.of(AnalyzerNames.PARSER, AnalyzerNames.MODEL),
                AnalyzerNames.name(analyzerSetting()), java.util.List.of(REANALYZE, SIMPLIFY));
        return chosen == null ? null : new AnalysisChoice(chosen.checked()[0], chosen.checked()[1],
                AnalyzerNames.of(chosen.choice()));
    };
    /** Zeile oben im Bestätigungsdialog „Analysieren“ (Fahrplan A3). */
    static final String ANALYZER = "Analysator";

    /** Rückfrage vor „Aussagen prüfen“ über mehrere Seiten: Ja oder Nein; Tests antworten ohne Dialog. */
    private CheckConfirmation checkConfirmation = Dialogs::confirm;

    /** Fragt vor einem großen Prüfauftrag; {@code true} nur nach „Ja“. */
    interface CheckConfirmation {
        boolean ask(java.awt.Component parent, String title, String question);
    }

    /** Einstellungen des Prüfschritts für den Start; ohne geladene Datei die Vorgabe des Core. */
    private com.aresstack.spaceharvester.application.configuration.CheckSettings checkSettings() {
        return context.settings() == null
                ? com.aresstack.spaceharvester.application.configuration.CheckSettings.DEFAULT
                : context.settings().check();
    }

    /** Einstellungen der Vorstufe „Sätze vereinfachen“ für den Start; ohne geladene Datei die Vorgabe des Core. */
    private com.aresstack.spaceharvester.application.configuration.SimplificationSettings simplification() {
        return context.settings() == null
                ? com.aresstack.spaceharvester.application.configuration.SimplificationSettings.DEFAULT
                : context.settings().simplification();
    }

    /** Was „Analysieren“ vom Core braucht. */
    interface Analyses {
        /** {@code null}, wenn Analysieren möglich ist, sonst der Grund für den Hinweis am Menüeintrag. */
        String unavailable();

        StatementAnalysisJobs.Estimate estimate(StatementAnalysisJobs.Scope scope, int limit);

        JobStatus start(StatementAnalysisJobs.Scope scope, StatementAnalysisJobs.Options options);

        /** {@code null}, wenn „Aussagen prüfen“ möglich ist, sonst der Grund für den Hinweis am Menüeintrag. */
        default String checkUnavailable() {
            return unavailable();
        }

        /** Startet den Auftrag „Aussagen prüfen“ (ADR 0032, FL4). */
        default JobStatus startCheck(StatementAnalysisJobs.Scope scope, StatementAnalysisJobs.CheckOptions options) {
            throw new UnsupportedOperationException("Aussagen prüfen");
        }
    }

    /** Fragt vor einem großen Analyseauftrag; {@code null} heißt abgebrochen, sonst die angekreuzten Felder. */
    interface AnalysisConfirmation {
        AnalysisChoice ask(java.awt.Component parent, String title, String text, String action);
    }

    /**
     * Was im Bestätigungsdialog angekreuzt war.
     *
     * @param reanalyze „erneut analysieren“
     * @param simplify  mit der Vorstufe „Sätze vereinfachen“ (ADR 0032, FL3)
     */
    record AnalysisChoice(boolean reanalyze, boolean simplify, AnalyzerChoice analyzer) {
        /** Ohne eigene Wahl gilt die Einstellung „Aussagenanalyse“. */
        AnalysisChoice(boolean reanalyze, boolean simplify) {
            this(reanalyze, simplify, null);
        }
    }

    /** Der vorgewählte Analysator (Einstellungen › Modelle, „Aussagenanalyse“); ohne Datei der MaltParser. */
    private AnalyzerChoice analyzerSetting() {
        return context.settings() == null || context.settings().analyzer().analyzer() == null ? AnalyzerChoice.PARSER
                : context.settings().analyzer().analyzer();
    }

    private final class WorkspaceAnalyses implements Analyses {
        @Override
        public String unavailable() {
            AppRuntime runtime = context.runtime();
            if (runtime == null) {
                return ANALYZE_NOT_STARTED;
            }
            try {
                return runtime.workspace().statementAnalysisJobsAvailable() ? null : ANALYZE_UNAVAILABLE;
            } catch (RuntimeException e) {
                return ANALYZE_UNAVAILABLE;
            }
        }

        @Override
        public String checkUnavailable() {
            AppRuntime runtime = context.runtime();
            if (runtime == null) {
                return ANALYZE_NOT_STARTED;
            }
            try {
                return runtime.workspace().statementCheckAvailable() ? null : CHECK_NO_MODEL;
            } catch (RuntimeException e) {
                return CHECK_NO_MODEL;
            }
        }

        @Override
        public JobStatus startCheck(StatementAnalysisJobs.Scope scope, StatementAnalysisJobs.CheckOptions options) {
            return runtime().workspace().startStatementCheck(scope, options);
        }

        @Override
        public StatementAnalysisJobs.Estimate estimate(StatementAnalysisJobs.Scope scope, int limit) {
            return runtime().workspace().estimateStatementAnalysis(scope, limit);
        }

        @Override
        public JobStatus start(StatementAnalysisJobs.Scope scope, StatementAnalysisJobs.Options options) {
            return runtime().workspace().startStatementAnalysis(scope, options);
        }

        private AppRuntime runtime() {
            AppRuntime runtime = context.runtime();
            if (runtime == null) {
                throw new IllegalStateException("Die Laufzeit ist nicht gestartet");
            }
            return runtime;
        }
    }

    public Explorer(AppContext context) {
        this.context = context;
        this.favorites = new FavoritesView(context, this::selectedItem, sourceId -> reveal(sourceId, null));
        this.states = new TreeStates(context, this::statesChanged);
        states.onSpaceChanged(this::spaceStructureChanged);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setToggleClickCount(0);
        tree.setOpaque(false);
        tree.setRowHeight(0);
        tree.setFont(Comic.regular(13f));
        tree.getAccessibleContext().setAccessibleName("Wissensquellen");
        tree.setCellRenderer(new Renderer());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                if (event.getPath().getLastPathComponent() instanceof Node node) {
                    load(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        tree.getSelectionModel().setSelectionMode(
                javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> {
            // Während eines Abgleichs stellt der Baum die Auswahl selbst wieder her; die Mitte sieht nichts davon.
            if (!merging) {
                selectionChanged();
            }
            // Die Favoritenliste zeigt, welcher Favorit gerade geöffnet bzw. im Baum gewählt ist.
            favorites.markCurrent();
        });
        MouseAdapter mouse = new MouseAdapter() {
            /** Die Zeile unter dem Drücken der linken Taste, bis zum Loslassen. */
            private TreePath pressed;
            /** Ob die Maus zwischen Drücken und Loslassen gewandert ist: Swing meldet dann keinen Klick. */
            private boolean moved;
            /** Klickzahl beim Drücken; beim Loslassen nach Bewegung setzt AWT sie (etwa unter X11) auf 0. */
            private int pressedCount;

            @Override
            public void mouseClicked(MouseEvent event) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    clicked(tree.getPathForLocation(event.getX(), event.getY()), event.getPoint(),
                            event.getClickCount());
                }
            }

            /**
             * Ein Klick öffnet (Angelo, 17.11.2026: einheitlich für Quelle, Space und Seite, kein Doppelklick zum
             * Lesen); der Doppelklick klappt auf und zu, der Griff davor ebenso.
             */
            private void clicked(TreePath path, java.awt.Point point, int count) {
                if (path == null) {
                    return;
                }
                boolean single = count == 1;
                if (onStar(path, point)) {
                    if (single) {
                        toggleFavorite(((Node) path.getLastPathComponent()).item());
                    }
                } else if (onBadge(path, point)) {
                    if (single) {
                        refresh((KnowledgeNode) ((Node) path.getLastPathComponent()).item());
                    }
                } else if (single) {
                    activate(path);
                } else if (count == 2) {
                    toggle(path);
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                pressed = javax.swing.SwingUtilities.isLeftMouseButton(event)
                        ? tree.getPathForLocation(event.getX(), event.getY()) : null;
                pressedCount = Math.max(1, event.getClickCount());
                moved = false;
                if (event.isPopupTrigger()) {
                    popup(event);
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (pressed != null) {
                    moved = true;
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    popup(event);
                }
                // Angelo (20.11.2026): oft erst der zweite Klick öffnete. Wandert die Maus beim Klick auch nur einen
                // Punkt, markiert Swing die Zeile schon beim Drücken, meldet aber keinen Klick; so blieb die alte Seite
                // stehen. Losgelassen auf derselben Zeile gilt das als Klick.
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (moved && pressed != null && pressed.equals(path)
                        && javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    clicked(path, event.getPoint(), pressedCount);
                }
                pressed = null;
                moved = false;
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                int starRow = onStar(path, event.getPoint()) ? tree.getRowForPath(path) : -1;
                int badgeRow = onBadge(path, event.getPoint()) ? tree.getRowForPath(path) : -1;
                // Über dem Titel: ein rechts abgeschnittener Eintrag zeigt seinen vollständigen Text (Angelo, 27.11.2026).
                tree.setToolTipText(starRow >= 0 ? STAR_TIP : badgeRow >= 0 ? refreshTip(badgeTip(path))
                        : TreeTextTip.fullText(tree, path));
                if (starRow != hoverStarRow || badgeRow != hoverBadgeRow) {
                    hoverStarRow = starRow;
                    hoverBadgeRow = badgeRow;
                    tree.repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                if (hoverStarRow >= 0 || hoverBadgeRow >= 0) {
                    hoverStarRow = -1;
                    hoverBadgeRow = -1;
                    tree.repaint();
                }
            }
        };
        treeMouse = mouse;
        tree.addMouseListener(mouse);
        tree.addMouseMotionListener(mouse);
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
        bindEnter(tree, () -> {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                activate(path);
            }
        });
        matches.setOpaque(false);
        matches.setFont(Comic.regular(13f));
        matches.setSelectionBackground(Comic.YELLOW);
        matches.setSelectionForeground(Comic.INK);
        matches.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Match match) {
                    setText("<html>" + escape(match.title()) + "<br><font color='#7a7a7a'>" + escape(match.where())
                            + "</font></html>");
                    setIcon(SidebarIcons.page());
                }
                setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
                setOpaque(selected);
                return this;
            }
        });
        matches.getAccessibleContext().setAccessibleName("Gefundene Seiten");
        matches.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                openMatch();
            }
        });
        bindEnter(matches, this::openMatch);
        debounce.setRepeats(false);
        filter.setToolTipText("Seitentitel in den geladenen Knoten und im Bestand finden; dient nur der "
                + "Navigation, gesucht wird im Bereich Suche.");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                debounce.restart();
            }
        });
        bindKey(filter, KeyEvent.VK_ESCAPE, () -> filter.setText(""));

        JScrollPane treeScroll = Comic.scroll(tree);
        treeScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        // Jede Zeile ist genau so breit wie der sichtbare Bereich: Bestandszeichen und Stern stehen
        // in festen Spalten ganz rechts, ein zu langer Titel wird davor mit „…“ gekürzt. Bei neuer Breite neu
        // vermessen (auch beim Verschieben des Teilers).
        treeScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                remeasure();
            }
        });
        JScrollPane matchScroll = Comic.scroll(matches);
        deck.setOpaque(false);
        deck.add(treeScroll, TREE);
        deck.add(matchScroll, MATCHES);
        deck.add(favorites.component(), FAVORITES);
        context.favorites().onChange(() -> javax.swing.SwingUtilities.invokeLater(this::favoritesChanged));
        root.setOpaque(false);
        // Kopf wie in der Quellenverwaltung: Comic-Überschrift, darunter das Filterfeld.
        // Die Überschrift ist anklickbar: sie wählt alle Wissensquellen; dann ist der ganze Kopf samt Filterfeld
        // gelb hinterlegt, als ein Feld (Angelo, 16.11.2026).
        JPanel title = head.title();
        // Rechtsbündig wie die Symbole der Zeilen (Angelo, 16.11.2026): „Alles auswählen“, dann das Bestandszeichen
        // über der Spalte der Bestandszeichen; rechts davon bleibt die Spalte der Sterne frei.
        allBadge.setHorizontalAlignment(JLabel.CENTER);
        allBadge.setPreferredSize(new java.awt.Dimension(BADGE_WIDTH, RoundHover.SIZE));
        // Beim Darüberfahren Kreispfeile; der Klick aktualisiert nach Rückfrage alle Quellen (Angelo, 16.11.2026).
        Cursors.set(allBadge, java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        allBadge.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                allBadgeHover = true;
                statesChanged();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                allBadgeHover = false;
                statesChanged();
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    refreshAll();
                }
            }
        });
        head.actions(selectAll, allBadge,
                javax.swing.Box.createRigidArea(new java.awt.Dimension(ACTION_WIDTH, RoundHover.SIZE)));
        selectAll.addActionListener(e -> selectAll());
        heading.setToolTipText(ALL_TIP);
        Cursors.set(heading, java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        MouseAdapter chooseAll = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    selectAll();
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                headPopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                headPopup(event);
            }
        };
        heading.addMouseListener(chooseAll);
        title.addMouseListener(chooseAll);
        head.addMouseListener(chooseAll);
        root.add(head, BorderLayout.NORTH);
        root.add(deck, BorderLayout.CENTER);
        collapseAll.addActionListener(e -> collapseAll());
        showCurrent.setEnabled(false);
        showCurrent.addActionListener(e -> showCurrent());
        // Hinweise nur beim Titelfilter und bei Fehlern; die Anzahl der Wissensquellen sieht man im Baum.
        note.setBorder(BorderFactory.createEmptyBorder(2, 6, 0, 0));
        favoritesOnly.addActionListener(e -> showOnlyFavorites(!onlyFavorites));
        // „Neuer Ordner“ nur, solange die Favoriten gezeigt werden (Angelo 24.11.2026: unten in der Fußleiste).
        favorites.newFolderButton().setVisible(false);
        // Die gemeinsame Fußleiste aller Seitenleisten, so hoch wie die Leiste mit der Lupe daneben.
        root.add(new com.aresstack.spaceharvester.app.ui.controls.SideFoot(
                com.aresstack.spaceharvester.app.ui.controls.SideFoot.buttons(collapseAll, showCurrent,
                        favorites.newFolderButton()), note,
                favoritesOnly), BorderLayout.SOUTH);
        context.onChange(ignored -> refreshSources());
        refreshSources();
    }

    public JComponent component() {
        return root;
    }

    /** Meldet jede Änderung der Auswahl als Umfang (für die Suche); leer heißt alle. */
    public void onScope(Consumer<List<Scope>> listener) {
        this.scopeListener = Objects.requireNonNull(listener, "listener");
    }

    /** Merkt sich die in der Leseansicht gezeigte Seite (ohne den Baum zu ändern). */
    public void currentPage(PageNavigation.Position position) {
        this.current = position;
        showCurrent.setEnabled(position != null);
    }

    /** Klappt alle Knoten zu; die Wissensquellen bleiben sichtbar. */
    void collapseAll() {
        for (int row = tree.getRowCount() - 1; row >= 0; row--) {
            tree.collapseRow(row);
        }
    }

    /** Wie das Fadenkreuz in IntelliJ: nur den Pfad zur gezeigten Seite aufklappen und sie wählen, ohne zu öffnen. */
    void showCurrent() {
        PageNavigation.Position target = current;
        if (target == null) {
            return;
        }
        cards.show(deck, TREE);
        // Fehler bis 23.11.2026: Beim Zuklappen wanderte die Auswahl auf den zugeklappten Vorfahren; der
        // Auswahl-Listener (nur der Umfang war stumm) zeigte dessen Ansicht in der Mitte, die meldete keine Seite mehr,
        // current wurde null, das Fadenkreuz grau und revealPage(current) tat nichts. Jetzt schweigen beim Zuklappen
        // beide Listener, und das Ziel ist vorher gemerkt.
        quiet = true;
        revealing = true;
        try {
            tree.clearSelection();
            collapseAll();
        } finally {
            revealing = false;
            quiet = false;
        }
        // Wie ein Klick des Benutzers: normale Auswahl, die Hervorhebung eines Suchtreffers verschwindet.
        reveal(target, ++revealRequest);
        userRequest = revealRequest;
    }

    /** Während {@link #showCurrent} zuklappt, meldet die Auswahl nichts. */
    private boolean quiet;
    /** Die Anfrage, deren Ziel wie ein Klick gewählt wird (Fadenkreuz). */
    private int userRequest = -1;

    /** Hebt die Auswahl auf, auch die globale; der Umfang ist dann wieder alles. */
    public void clearSelection() {
        boolean was = global || !externalScopes.isEmpty();
        externalScopes = List.of();
        global = false;
        if (tree.getSelectionCount() > 0) {
            tree.clearSelection();
        } else if (was) {
            selectionListener.accept(null);
        }
        paintHead();
    }

    /**
     * Wählt die Überschrift „Wissensquellen“: die globale Auswahl über alle Quellen. Die Auswahl im Baum verschwindet,
     * der Umfang der Suche ist alles, Chat und Export erhalten {@link com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection#ALL}.
     */
    public void selectAll() {
        if (global) {
            return;
        }
        externalScopes = List.of();
        global = true;
        paintHead();
        if (tree.getSelectionCount() > 0) {
            // Der Auswahl-Listener des Baums meldet Umfang und Auswahl.
            tree.clearSelection();
        } else {
            scopeListener.accept(List.of());
            selectionListener.accept(selection());
        }
    }

    /** Für Tests: ersetzt die Rückfrage vor „Alles indexieren“. */
    void confirmIndexAll(java.util.function.Predicate<java.awt.Component> confirmer) {
        this.confirmIndexAll = Objects.requireNonNull(confirmer, "confirmer");
    }

    /** Je Quelle die Spaces, die der Baum schon kennt (aufgeklappte Quellen), für den Gesamtstand der Kopfzeile. */
    private java.util.Map<String, java.util.Set<String>> treeSpaces() {
        java.util.Map<String, java.util.Set<String>> spaces = new java.util.HashMap<>();
        for (int i = 0; i < top.getChildCount(); i++) {
            if (!(top.getChildAt(i) instanceof Node source)) {
                continue;
            }
            for (int j = 0; j < source.getChildCount(); j++) {
                if (source.getChildAt(j) instanceof Node child && child.item() instanceof SpaceItem space) {
                    spaces.computeIfAbsent(space.sourceId(), ignored -> new java.util.HashSet<>()).add(space.spaceKey());
                }
            }
        }
        return spaces;
    }

    /** Der Bestand hat sich geändert: Baum und das Zeichen der Kopfzeile neu. */
    private void statesChanged() {
        tree.repaint();
        TreeStates.Badge badge = states.all(shownSources, treeSpaces());
        allBadge.setIcon(allBadgeHover ? Glyph.SYNC : badge.kind() == TreeStates.Kind.NONE ? null : badge.glyph());
        allBadge.setForeground(allBadgeHover ? Comic.INK : badge.color());
        allBadge.setToolTipText(refreshTip(badge.tooltip()));
    }

    /** Der Tooltip am Zustandspunkt: der Stand, dahinter der Hinweis auf das Nachholen. */
    static String refreshTip(String stateTip) {
        return stateTip == null || stateTip.isBlank() ? REFRESH_TIP : stateTip + " · " + REFRESH_TIP;
    }

    /** Für Tests: eigene Antwort auf die Rückfrage vor dem Aktualisieren aller Quellen. */
    void confirmRefreshAll(java.util.function.Predicate<java.awt.Component> confirmer) {
        this.confirmRefreshAll = Objects.requireNonNull(confirmer, "confirmer");
    }

    /** Holt nach Rückfrage die Änderungen aller Wissensquellen des Baums nach (Angelo, 16.11.2026). */
    void refreshAll() {
        if (shownSources.isEmpty() || !confirmRefreshAll.test(root)) {
            return;
        }
        for (String sourceId : List.copyOf(shownSources)) {
            context.navigator().refresh(sourceId, null, null);
        }
    }

    /** Holt den Knoten samt Teilbaum aus der Quelle nach ({@link AppContext.Navigator#refresh}). */
    private void refresh(KnowledgeNode node) {
        context.navigator().refresh(node.sourceId(), node.spaceKey(), node.pageId());
    }

    /**
     * Indexiert alle Wissensquellen des Baums nach Rückfrage (Kontextmenü der Überschrift): je Space ein eigener
     * Auftrag, wie einzeln angestoßen.
     */
    void indexAll() {
        if (shownSources.isEmpty() || !confirmIndexAll.test(root)) {
            return;
        }
        for (String sourceId : List.copyOf(shownSources)) {
            context.navigator().index(sourceId, null, null);
        }
    }

    /** Kontextmenü der Überschrift „Wissensquellen“: „Alles indexieren“. */
    private void headPopup(MouseEvent event) {
        if (event.isPopupTrigger()) {
            headMenu().show(event.getComponent(), event.getX(), event.getY());
        }
    }

    /** Das Kontextmenü der Überschrift (für Tests ohne Mausereignis). */
    javax.swing.JPopupMenu headMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem all = new javax.swing.JMenuItem(INDEX_ALL);
        all.setToolTipText(INDEX_TIP);
        all.setEnabled(!shownSources.isEmpty());
        all.addActionListener(e -> indexAll());
        menu.add(all);
        return menu;
    }

    /** Ob die Überschrift „Wissensquellen“ gewählt ist (alle Quellen). */
    public boolean allSelected() {
        return global;
    }

    private void paintHead() {
        head.setOpaque(global);
        head.setBackground(Comic.YELLOW);
        heading.setForeground(global ? Comic.INK : Comic.MUTED);
        head.repaint();
    }

    /** Zeigt einen Space im Baum: Quelle aufklappen, Space wählen und aufklappen; unbekannte bleiben ohne Wirkung. */
    public void reveal(String sourceId, String spaceKey) {
        Node source = child(top, item -> item instanceof SourceItem s && s.sourceId().equals(sourceId));
        if (source == null) {
            return;
        }
        Runnable select = () -> {
            Node space = child(source, item -> item instanceof SpaceItem s && s.spaceKey().equals(spaceKey));
            TreePath path = new TreePath((space == null ? source : space).getPath());
            tree.setSelectionPath(path);
            tree.expandPath(path);
            tree.scrollPathToVisible(path);
        };
        if (source.loaded) {
            tree.expandPath(new TreePath(source.getPath()));
            select.run();
        } else {
            source.then = select;
            tree.expandPath(new TreePath(source.getPath()));
        }
    }

    /**
     * Zeigt eine geöffnete Seite im Baum: Quelle, Space und Vorfahren aufklappen (Kinder werden bei Bedarf geladen) und
     * die Seite wählen. Findet der Baum die Seite nicht (etwa weil die Quelle nach einem Neustart ihren Pfad nicht mehr
     * kennt), bleibt er bis zum tiefsten gefundenen Knoten aufgeklappt, wählt aber keinen Ersatz: Die Auswahl bestimmt
     * die Mitte, ein gewählter Ordner oder Space würde die geöffnete Seite durch seine Ansicht ersetzen. Der Suchumfang
     * ändert sich dadurch nicht.
     */
    public void revealPage(PageNavigation.Position position) {
        reveal(position, ++revealRequest);
    }

    /** {@code ticket}: nur die jüngste Anfrage wählt; eine ältere, deren Kinder später laden, verfällt. */
    private void reveal(PageNavigation.Position position, int ticket) {
        if (position == null || position.page() == null) {
            return;
        }
        Node source = child(top, item -> item instanceof SourceItem s && s.sourceId().equals(position.sourceId()));
        if (source == null) {
            return;
        }
        String pageId = position.page().pageId();
        List<java.util.function.Predicate<Item>> steps = new ArrayList<>();
        steps.add(item -> item instanceof SpaceItem s && s.spaceKey().equals(position.spaceKey()));
        for (String ancestorId : ancestorIds(position)) {
            steps.add(item -> item instanceof PageItem p && p.pageId().equals(ancestorId));
        }
        steps.add(item -> item instanceof PageItem p && p.pageId().equals(pageId));
        descend(source, steps, 0, ticket);
    }

    /**
     * Die Vorfahren für den Abstieg: der Pfad des Core, oder der beim Favorisieren gemerkte Pfad, wenn er länger ist.
     * Quellen ohne dauerhaften Seitenbaum (BSCW über WebDAV) kennen nach einem Neustart den Pfad einer nur gestreamten
     * Datei nicht mehr; der Favorit hat ihn sich gemerkt.
     */
    private List<String> ancestorIds(PageNavigation.Position position) {
        List<String> fromCore = position.ancestors().stream().map(HarvestSource.PageNode::pageId).toList();
        for (Favorites.Favorite favorite : context.favorites().all()) {
            if (favorite.sourceId().equals(position.sourceId())
                    && position.page().pageId().equals(favorite.pageId())
                    && favorite.ancestorIds().size() > fromCore.size()) {
                return favorite.ancestorIds();
            }
        }
        return fromCore;
    }

    /** Wie {@link #revealPage(PageNavigation.Position)}; den Pfad der Seite holt der Core im Hintergrund. */
    public void revealPage(String sourceId, String pageId) {
        revealPage(sourceId, null, pageId);
    }

    /**
     * Wie {@link #revealPage(String, String)}. Kennt die Quelle keinen Seitenbaum ({@code position} meldet
     * {@code UNSUPPORTED}, etwa Jira: der Adapter ist keine {@code PageNavigationSource}), sucht der Baum die Seite
     * direkt unter {@code spaceKey} (bei Jira das Projekt), lädt dabei weitere Stapel nach und wählt sie.
     */
    public void revealPage(String sourceId, String spaceKey, String pageId) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || sourceId == null || pageId == null) {
            return;
        }
        int ticket = ++revealRequest;
        Background.run(() -> runtime.workspace().position(sourceId, pageId), position -> {
            if (ticket == revealRequest) {
                reveal(position, ticket);
            }
        }, error -> {
            if (ticket == revealRequest && spaceKey != null && !spaceKey.isBlank()) {
                revealFlat(sourceId, spaceKey, pageId, ticket);
            }
        });
    }

    /** Seite direkt unter ihrem Space suchen und wählen, für Quellen ohne Pfad im Core. */
    private void revealFlat(String sourceId, String spaceKey, String pageId, int ticket) {
        Node source = child(top, item -> item instanceof SourceItem s && s.sourceId().equals(sourceId));
        if (source == null) {
            return;
        }
        descend(source, List.of(item -> item instanceof SpaceItem s && s.spaceKey().equals(spaceKey),
                item -> item instanceof PageItem p && p.pageId().equals(pageId)), 0, ticket);
    }

    private void descend(Node node, List<java.util.function.Predicate<Item>> steps, int index, int ticket) {
        Runnable next = () -> step(node, steps, index, ticket);
        TreePath path = new TreePath(node.getPath());
        if (node.loaded) {
            tree.expandPath(path);
            next.run();
        } else {
            node.then = next;
            tree.expandPath(path);
        }
    }

    /** Ein Schritt des Abstiegs; liegt das Kind in einem noch nicht geladenen Stapel, lädt er ihn nach und sucht erneut. */
    private void step(Node node, List<java.util.function.Predicate<Item>> steps, int index, int ticket) {
        {
            if (ticket != revealRequest) {
                return;
            }
            Node child = index < steps.size() ? child(node, steps.get(index)) : null;
            if (child == null) {
                Node moreNode = child(node, item -> item instanceof MoreItem);
                if (moreNode != null && moreNode.item() instanceof MoreItem more) {
                    loadMore(node, moreNode, more, () -> step(node, steps, index, ticket));
                    return;
                }
            }
            if (child == null && index < steps.size() - 1 && node.item() instanceof SpaceItem) {
                // Vorfahren, die der Baum nicht als Wurzel zeigt: die Seite direkt unter dem Space suchen.
                child = child(node, steps.get(steps.size() - 1));
                if (child != null) {
                    finish(child, ticket);
                    return;
                }
            }
            if (child == null) {
                // Seite nicht gefunden: aufgeklappt zeigen, wie weit der Pfad reicht, aber nichts anderes wählen.
                tree.scrollPathToVisible(new TreePath(node.getPath()));
            } else if (index == steps.size() - 1) {
                finish(child, ticket);
            } else {
                descend(child, steps, index + 1, ticket);
            }
        }
    }

    /** Der hervorgehobene Suchtreffer; kein gewählter Knoten, die Auswahl (der Filter der Suche) bleibt. */
    private Node hint;
    /** Die Anfrage, deren Ziel nur hervorgehoben statt gewählt wird. */
    private int hintRequest = -1;

    /** Ende des Abstiegs: wählen, oder bei {@link #hintPage} nur hervorheben und hinscrollen. */
    private void finish(Node node, int ticket) {
        if (ticket == userRequest) {
            TreePath path = new TreePath(node.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
            return;
        }
        if (ticket != hintRequest) {
            select(node);
            return;
        }
        Node before = hint;
        hint = node;
        TreePath path = new TreePath(node.getPath());
        if (before != null) {
            ((javax.swing.tree.DefaultTreeModel) tree.getModel()).nodeChanged(before);
        }
        ((javax.swing.tree.DefaultTreeModel) tree.getModel()).nodeChanged(node);
        tree.scrollPathToVisible(path);
    }

    /**
     * Zeigt einen Suchtreffer im Baum (Angelo, 23.11.2026): Pfad aufklappen, hinscrollen und die Seite mit einem
     * Rahmen hervorheben, ohne die Auswahl zu ändern, denn die Auswahl ist der Filter der Suche.
     */
    /** Wie {@link #hintPage(String, String, String)} für einen bekannten Pfad (aus der Suche geöffnete Seite). */
    public void hintPage(PageNavigation.Position position) {
        reveal(position, ++revealRequest);
        hintRequest = revealRequest;
    }

    /** Nimmt die Hervorhebung eines Suchtreffers weg. */
    void clearHint() {
        Node before = hint;
        hint = null;
        if (before != null) {
            ((javax.swing.tree.DefaultTreeModel) tree.getModel()).nodeChanged(before);
        }
    }

    public void hintPage(String sourceId, String spaceKey, String pageId) {
        revealPage(sourceId, spaceKey, pageId);
        hintRequest = revealRequest;
    }

    /** Für Tests: die Seiten-ID des hervorgehobenen Treffers. */
    String hintedPageId() {
        return hint != null && hint.item() instanceof PageItem page ? page.pageId() : null;
    }

    private void select(Node node) {
        TreePath path = new TreePath(node.getPath());
        revealing = true;
        try {
            tree.setSelectionPath(path);
        } finally {
            revealing = false;
        }
        tree.scrollPathToVisible(path);
    }

    private static Node child(Node parent, java.util.function.Predicate<Item> match) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof Node node && match.test(node.item())) {
                return node;
            }
        }
        return null;
    }

    /** Umfang der ersten gewählten Zeile: Quelle, Space oder der Space einer Seite; ohne Auswahl alle. */
    public Scope scope() {
        List<Scope> scopes = scopes();
        return scopes.isEmpty() ? Scope.ALL : scopes.get(0);
    }

    /** Umfang aller gewählten Zeilen (Strg-Klick), ohne Doppelte; leer ohne Auswahl. */
    /** Eine gemerkte Auswahl links: Knoten im Baum oder „Wissensquellen“ (alle). */
    public record Remembered(TreePath[] paths, boolean global) {
    }

    /** Merkt sich die Auswahl links, etwa beim Verlassen der Suche (Angelo, 21.11.2026). */
    public Remembered remember() {
        TreePath[] paths = tree.getSelectionPaths();
        return new Remembered(paths == null ? new TreePath[0] : paths.clone(), global);
    }

    /**
     * Stellt eine gemerkte Auswahl wieder her; Knoten, die der Baum inzwischen nicht mehr enthält, fallen weg. Gleicht
     * sie der jetzigen, meldet der Baum nichts, und die Suche bleibt, wie sie war.
     */
    public void restore(Remembered remembered) {
        if (remembered == null) {
            return;
        }
        if (remembered.global()) {
            selectAll();
            return;
        }
        TreePath[] alive = java.util.Arrays.stream(remembered.paths())
                .filter(path -> path.getLastPathComponent() instanceof Node node && node.getRoot() == model.getRoot())
                .toArray(TreePath[]::new);
        if (alive.length == 0) {
            clearSelection();
        } else {
            tree.setSelectionPaths(alive);
        }
    }

    /**
     * Wählt links genau diese Bereiche (Quelle oder Space), etwa wenn in der Suche Wissensquellen an- oder abgehakt
     * werden (Angelo, 22.11.2026: beides ist dasselbe); leer wählt „Wissensquellen“ (alle). Ein Space, den der Baum
     * noch nicht geladen hat, wird als seine Quelle gewählt.
     */
    public void select(List<Scope> scopes) {
        if (scopes.isEmpty()) {
            selectAll();
            return;
        }
        boolean external = context.runtime() != null && scopes.stream().anyMatch(scope ->
                !shownSources.contains(scope.sourceId()) && context.runtime().workspace().sources().stream()
                        .anyMatch(source -> source.id().equals(scope.sourceId())));
        // Eine sichtbare Seite als Bereich: im Baum aufklappen und wählen wie ein Suchtreffer.
        if (!external && scopes.size() == 1 && scopes.get(0).pageId() != null) {
            revealPage(scopes.get(0).sourceId(), scopes.get(0).spaceKey(), scopes.get(0).pageId());
            return;
        }
        List<TreePath> paths = new ArrayList<>();
        for (Scope scope : scopes) {
            for (int i = 0; i < top.getChildCount(); i++) {
                if (top.getChildAt(i) instanceof Node source && source.item() instanceof SourceItem item
                        && item.sourceId().equals(scope.sourceId())) {
                    Node target = source;
                    if (scope.spaceKey() != null) {
                        for (int j = 0; j < source.getChildCount(); j++) {
                            if (source.getChildAt(j) instanceof Node space && space.item() instanceof SpaceItem s
                                    && s.spaceKey().equals(scope.spaceKey())) {
                                target = space;
                            }
                        }
                    }
                    TreePath path = new TreePath(target.getPath());
                    if (!paths.contains(path)) {
                        paths.add(path);
                    }
                }
            }
        }
        if (external) {
            // Preserve the explicit search scope even when no matching tree node exists.
            global = false;
            externalScopes = List.copyOf(scopes);
            quiet = true;
            try {
                tree.setSelectionPaths(paths.isEmpty() ? null : paths.toArray(TreePath[]::new));
            } finally {
                quiet = false;
            }
            clearHint();
            paintHead();
            scopeListener.accept(externalScopes);
            selectionListener.accept(selection());
            return;
        }
        if (paths.isEmpty()) {
            return;
        }
        boolean hadExternal = !externalScopes.isEmpty();
        externalScopes = List.of();
        tree.setSelectionPaths(paths.toArray(TreePath[]::new));
        if (hadExternal) {
            scopeListener.accept(scopes());
            selectionListener.accept(selection());
        }
    }

    public List<Scope> scopes() {
        if (!externalScopes.isEmpty()) {
            return externalScopes;
        }
        TreePath[] paths = tree.getSelectionPaths();
        List<Scope> scopes = new ArrayList<>();
        for (TreePath path : paths == null ? new TreePath[0] : paths) {
            if (path.getLastPathComponent() instanceof Node node) {
                Scope scope = node.item() instanceof KnowledgeNode known ? known.scope() : null;
                if (scope != null && !scopes.contains(scope)) {
                    scopes.add(scope);
                }
            }
        }
        return scopes;
    }

    // ------------------------------------------------------------------ Baum

    /** Die Wurzelknoten: alle Quellen der Laufzeit, in deren Reihenfolge; unverändert, solange dieselben bleiben. */
    private void refreshSources() {
        AppRuntime runtime = context.runtime();
        List<HarvestSource> sources = runtime == null ? List.of() : runtime.workspace().sources().stream()
                .filter(source -> runtime.settings().sources().stream()
                        .filter(entry -> entry.id().equals(source.id()))
                        .allMatch(entry -> entry.config().showInExplorer()))
                .toList();
        List<String> ids = sources.stream().map(HarvestSource::id).toList();
        // Vor dem Vergleich: eine neue Laufzeit mit denselben Quellen meldet die Zeichen neu an.
        states.watch(ids);
        if (ids.equals(shownSources) && top.getChildCount() > 0) {
            retitle(sources);
            return;
        }
        shownSources = ids;
        statesChanged();
        top.removeAllChildren();
        for (HarvestSource source : sources) {
            top.add(expandable(new SourceItem(source.id(), source.title())));
        }
        if (sources.isEmpty()) {
            top.add(new Node(new Note(runtime == null ? "Startet …" : "Keine Wissensquelle")));
        }
        model.reload();
        note.setText(" ");
    }

    /**
     * Übernimmt geänderte Anzeigenamen (Rechtsklick „Umbenennen“ oder Quellenverwaltung) in die gezeigten
     * Quellenknoten, ohne den Baum neu aufzubauen: Aufgeklappte Zweige und Auswahl bleiben.
     */
    private void retitle(List<HarvestSource> sources) {
        java.util.Map<String, String> titles = new java.util.HashMap<>();
        sources.forEach(source -> titles.put(source.id(), source.title()));
        for (int i = 0; i < top.getChildCount(); i++) {
            if (top.getChildAt(i) instanceof Node node && node.item() instanceof SourceItem source) {
                String title = titles.get(source.sourceId());
                if (title != null && !title.equals(source.title())) {
                    node.setUserObject(new SourceItem(source.sourceId(), title));
                    model.nodeChanged(node);
                }
            }
        }
    }

    private static Node expandable(Item item) {
        Node node = new Node(item);
        node.add(new Node(new Note(LOADING)));
        return node;
    }

    /** Ob gerade ein Abgleich läuft ({@link #keepingView}); Auswahl-Ereignisse gehen dann nicht an die Mitte. */
    private boolean merging;

    private void selectionChanged() {
        if (quiet) {
            return;
        }
        if (!revealing) {
            externalScopes = List.of();
            // Der Benutzer wählt selbst: die Hervorhebung eines Suchtreffers verschwindet.
            clearHint();
        }
        // Ein gewählter Knoten löst die globale Auswahl ab.
        if (global && tree.getSelectionCount() > 0) {
            global = false;
            paintHead();
        }
        if (!revealing) {
            scopeListener.accept(scopes());
        }
        // Der Export folgt auch einer Auswahl, die ein geöffneter Treffer oder Auftrag gesetzt hat.
        selectionListener.accept(selection());
    }

    /**
     * Lädt die Kinder eines Knotens beim ersten Aufklappen; nach einem Fehler beim nächsten erneut. Seiten erscheinen
     * zuerst aus dem Cache (sofort, nach Titel geordnet), dann fragt der Baum die Quelle und gleicht nach Seiten-ID ab,
     * ohne Aufklappung und Auswahl darunter zu verlieren; danach lädt der Core die Leseansichten der Kinder vor
     * (Angelo, 17.11.2026). Ist im Cache nichts, bleibt „Lädt …“ bis zur Antwort der Quelle.
     */
    private void load(Node node) {
        if (node.loaded || node.loading) {
            return;
        }
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        node.loading = true;
        Item item = node.item();
        if (item instanceof SpaceItem space) {
            states.open(space.sourceId(), space.spaceKey());
        } else if (item instanceof PageItem page) {
            states.open(page.sourceId(), page.spaceKey());
        }
        if (node.fromCache || !(item instanceof SpaceItem || item instanceof PageItem)) {
            loadLive(runtime, node);
            return;
        }
        Background.run(() -> cachedChildren(runtime, item, null), cached -> {
            if (!cached.nodes().isEmpty()) {
                // Noch steht nur „Lädt …“ darunter: Neuaufbau dieses Knotens verliert nichts.
                node.removeAllChildren();
                appendCached(node, cached);
                node.fromCache = true;
                model.nodeStructureChanged(node);
            }
            loadLive(runtime, node);
        }, error -> loadLive(runtime, node));
    }

    /** Fragt die Kinder bei der Quelle ab; über einem Stand aus dem Cache per Abgleich, sonst wie bisher. */
    private void loadLive(AppRuntime runtime, Node node) {
        Item item = node.item();
        Background.run(() -> children(runtime, item, null), batch -> {
            node.loading = false;
            node.loaded = true;
            node.fromCache = false;
            merge(node, batch, true);
            // Prefetch is optional and must never postpone the visible navigation results.
            Background.run(() -> {
                prefetch(runtime, item, batch);
                return null;
            }, ignored -> { }, ignored -> { });
            // Neue Spaces im Baum zählen für den Gesamtstand der Kopfzeile.
            if (item instanceof SourceItem) {
                statesChanged();
            }
            Runnable then = node.then;
            node.then = null;
            if (then != null) {
                then.run();
            }
        }, error -> {
            node.loading = false;
            node.then = null;
            if (node.fromCache) {
                // Der Stand aus dem Cache bleibt stehen; beim nächsten Aufklappen fragt der Baum die Quelle erneut.
                note.setText("Nicht geladen: " + Problems.describe(error).title());
                return;
            }
            node.removeAllChildren();
            node.add(new Node(new Note("Nicht geladen: " + Problems.describe(error).title())));
            model.nodeStructureChanged(node);
        });
    }

    /** Höchstens so viele Leseansichten lädt der Core je aufgeklapptem Knoten vor. */
    static final int PREFETCH_LIMIT = 25;

    /** Lässt den Core die Leseansichten der eben geholten Kindseiten vorladen; still, wenn das nicht geht. */
    private static void prefetch(AppRuntime runtime, Item item, Batch batch) {
        String sourceId = switch (item) {
            case SpaceItem space -> space.sourceId();
            case PageItem page -> page.sourceId();
            default -> null;
        };
        if (sourceId == null) {
            return;
        }
        List<String> pageIds = batch.nodes().stream().map(Node::item)
                .filter(PageItem.class::isInstance).map(child -> (PageItem) child)
                .filter(page -> page.kind() == HarvestSource.PageNode.Kind.PAGE)
                .map(PageItem::pageId).limit(PREFETCH_LIMIT).toList();
        if (pageIds.isEmpty()) {
            return;
        }
        try {
            runtime.workspace().prefetchReadingViews(sourceId, pageIds);
        } catch (RuntimeException ignored) {
            // Vorladen ist nur eine Beschleunigung.
        }
    }

    /**
     * Ein Stapel Kindseiten aus dem Cache, ohne die Quelle zu fragen; leer, wenn dort nichts liegt oder es nicht geht.
     * Weitere Stapel folgen über „Weitere laden“ ({@link MoreItem#cached()}), auch wenn die Quelle nicht antwortet.
     *
     * @param cursor {@code null} für den ersten Stapel, sonst {@link Batch#nextCursor()} des vorigen
     */
    static Batch cachedChildren(AppRuntime runtime, Item item, String cursor) {
        try {
            return switch (item) {
                case SpaceItem space -> nodes(space.sourceId(), runtime.workspace()
                        .cachedChildren(space.sourceId(), space.spaceKey(), null, cursor, BATCH));
                case PageItem page -> nodes(page.sourceId(), runtime.workspace()
                        .cachedChildren(page.sourceId(), page.spaceKey(), page.pageId(), cursor, BATCH));
                default -> new Batch(List.of(), null);
            };
        } catch (RuntimeException notCached) {
            return new Batch(List.of(), null);
        }
    }

    /**
     * Ein Klick oder Enter: Seite bzw. Ordner öffnen oder den nächsten Stapel holen. Quelle und Space zeigt schon die Auswahl in
     * der Mitte (die Quellenseite mit den Updates), ein Klick wechselt dort keinen Reiter. In der Suche wechselt kein
     * Klick die Ansicht, die Auswahl ist dort der Filter (Angelo, 21.11.2026).
     */
    void activate(TreePath path) {
        if (!(path.getLastPathComponent() instanceof Node node)) {
            return;
        }
        switch (node.item()) {
            case PageItem page -> open(page);
            case MoreItem more -> loadMore((Node) node.getParent(), node, more, null);
            default -> {
            }
        }
    }

    /**
     * Öffnet einen Knoten unterhalb des Space (Klick und „Öffnen“ im Kontextmenü gleich): mit eigenem Inhalt als
     * Seite; ein Ordner oder eine Gruppierung hat keinen eigenen Inhalt, die Mitte zeigt ihn als leere Ebene statt der
     * vorher geöffneten Seite (Angelo 26.11.2026), für jede Quelle und in jeder Tiefe. Auf- und zuklappen bleibt beim
     * Doppelklick (Angelo 17.11.2026).
     */
    private void open(PageItem page) {
        if (page.kind().hasOwnContent()) {
            context.navigator().openFromTree(page.sourceId(), page.pageId());
        } else {
            context.navigator().openContainerFromTree(page.sourceId(), page.spaceKey(), page.pageId(), page.title());
        }
    }

    /** Doppelklick: den Knoten auf- oder zuklappen (Angelo, 17.11.2026). */
    private void toggle(TreePath path) {
        if (tree.isExpanded(path)) {
            tree.collapsePath(path);
        } else {
            tree.expandPath(path);
        }
    }

    /** Lädt den nächsten Stapel Kinder von {@code parent}; {@code then} läuft danach auf dem EDT (etwa der Abstieg). */
    private void loadMore(Node parent, Node moreNode, MoreItem more, Runnable then) {
        AppRuntime runtime = context.runtime();
        if (runtime != null && parent != null && more.cached()) {
            // Die Vorschau blättert auch, während der Abgleich mit der Quelle noch läuft oder sie nicht antwortet.
            loadCachedMore(runtime, parent, moreNode, more, then);
            return;
        }
        if (runtime == null || parent == null || parent.loading) {
            return;
        }
        parent.loading = true;
        moreNode.setUserObject(new Note(LOADING));
        model.nodeChanged(moreNode);
        Background.run(() -> children(runtime, parent.item(), more.cursor()), batch -> {
            parent.loading = false;
            merge(parent, batch, more.cursor() == null);
            if (more.cursor() == null) {
                note.setText("Ordner neu geladen.");
            }
            if (then != null) {
                then.run();
            }
        }, error -> {
            parent.loading = false;
            MoreItem retry = more;
            if (error instanceof InvalidNavigationCursorException && more.cursor() != null) {
                retry = new MoreItem(null);
                moreNode.setUserObject(retry);
                model.nodeChanged(moreNode);
                loadMore(parent, moreNode, retry, then);
                return;
            }
            moreNode.setUserObject(retry);
            model.nodeChanged(moreNode);
            note.setText("Nicht geladen: " + Problems.describe(error).title());
        });
    }


    /**
     * Der nächste Stapel der Vorschau aus dem Cache: hängt die noch nicht gezeigten Kinder an, ohne die Quelle zu fragen.
     * Ein inzwischen eingetroffener Abgleich mit der Quelle hat „Weitere laden“ schon ersetzt; dann geschieht nichts.
     */
    private void loadCachedMore(AppRuntime runtime, Node parent, Node moreNode, MoreItem more, Runnable then) {
        moreNode.setUserObject(new Note(LOADING));
        model.nodeChanged(moreNode);
        Background.run(() -> cachedChildren(runtime, parent.item(), more.cursor()), batch -> {
            if (moreNode.getParent() != parent || !parent.fromCache) {
                return;
            }
            keepingView(parent, () -> {
                model.removeNodeFromParent(moreNode);
                Set<String> shown = new HashSet<>();
                for (int i = 0; i < parent.getChildCount(); i++) {
                    shown.add(key(((Node) parent.getChildAt(i)).item()));
                }
                for (Node child : batch.nodes()) {
                    if (shown.add(key(child.item()))) {
                        model.insertNodeInto(child, parent, parent.getChildCount());
                    }
                }
                if (batch.nextCursor() != null) {
                    model.insertNodeInto(new Node(new MoreItem(batch.nextCursor(), true)), parent,
                            parent.getChildCount());
                }
            });
            if (then != null) {
                then.run();
            }
        }, error -> {
            moreNode.setUserObject(more);
            model.nodeChanged(moreNode);
            note.setText("Nicht geladen: " + Problems.describe(error).title());
        });
    }

    /** Hängt den ersten Stapel der Vorschau aus dem Cache an; weitere Stapel kommen ebenfalls aus dem Cache. */
    private static void appendCached(Node parent, Batch batch) {
        append(parent, new Batch(batch.nodes(), null));
        if (batch.nextCursor() != null) {
            parent.add(new Node(new MoreItem(batch.nextCursor(), true)));
        }
    }

    private static void append(Node parent, Batch batch) {
        for (Node child : batch.nodes()) {
            parent.add(child);
        }
        if (batch.nextCursor() != null) {
            parent.add(new Node(new MoreItem(batch.nextCursor())));
        }
        if (parent.getChildCount() == 0) {
            Node empty = new Node(new Note(parent.item() instanceof SourceItem ? "Keine Spaces" : "Keine Seiten"));
            empty.loaded = true;
            parent.add(empty);
        }
    }

    /** Ein Stapel Kinder; im Hintergrund. */
    record Batch(List<Node> nodes, String nextCursor) {
    }

    /**
     * Ein Space hat laut Core einen neuen Stand (etwa nach dem Nachholen über die Kreispfeile, Codex zu #802): die
     * geladenen Knoten dieses Space (oder aller Spaces der Quelle, {@code spaceKey} null) lesen ihre Kinder nach.
     * Vorhandene Knoten bleiben samt Aufklappung und Auswahl, neue kommen hinzu, verschwundene gehen, geänderte Titel
     * werden übernommen; die Ansicht springt nicht.
     */
    private void spaceStructureChanged(String sourceId, String spaceKey) {
        Node source = child(top, item -> item instanceof SourceItem s && s.sourceId().equals(sourceId));
        if (source == null) {
            return;
        }
        for (int i = 0; i < source.getChildCount(); i++) {
            if (source.getChildAt(i) instanceof Node space && space.item() instanceof SpaceItem item
                    && (spaceKey == null || item.spaceKey().equals(spaceKey))) {
                reread(space);
            }
        }
    }

    /** Liest die Kinder eines geladenen Knotens nach und gleicht sie ab ({@link #merge}); danach seine geladenen Kinder. */
    private void reread(Node node) {
        AppRuntime runtime = context.runtime();
        Item item = node.item();
        if (runtime == null || !node.loaded || node.loading || !(item instanceof SpaceItem || item instanceof PageItem)) {
            return;
        }
        node.loading = true;
        Background.run(() -> children(runtime, item, null), batch -> {
            node.loading = false;
            merge(node, batch, true);
        }, error -> node.loading = false);
    }

    /** Schlüssel eines Knotens für den Abgleich; Hinweise und „Weitere laden“ haben keinen. */
    private static String key(Item item) {
        return switch (item) {
            case SpaceItem space -> "s" + space.spaceKey();
            case PageItem page -> "p" + page.pageId();
            default -> null;
        };
    }

    /**
     * Gleicht die Kinder von {@code parent} mit einem frischen Stapel der Quelle ab, mit einzelnen Modell-Ereignissen
     * statt Neuaufbau. {@code first}: der erste Stapel einer Abfrage. Bestätigte Kinder stehen in der Reihenfolge der
     * Quelle vorn; solange ein Cursor offen ist, bleiben unbestätigte (etwa aus dem Cache) dahinter stehen, erst der
     * letzte Stapel entfernt, was die Quelle nicht mehr kennt (AT zu #861). Auswahl, aufgeklappte Pfade und
     * Rollposition bleiben; die Mitte sieht keinen Auswahlwechsel, solange die Auswahl erhalten bleibt.
     */
    void merge(Node parent, Batch batch, boolean first) {
        keepingView(parent, () -> mergeNodes(parent, batch, first));
    }

    private void mergeNodes(Node parent, Batch batch, boolean first) {
        if (first || parent.confirmed == null) {
            parent.confirmed = new java.util.LinkedHashSet<>();
        }
        java.util.Map<String, Node> existing = new java.util.LinkedHashMap<>();
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            Node child = (Node) parent.getChildAt(i);
            String key = key(child.item());
            if (key == null) {
                model.removeNodeFromParent(child);
            }
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            Node child = (Node) parent.getChildAt(i);
            String key = key(child.item());
            // Schon bestätigte Kinder früherer Stapel stehen vorn und bleiben.
            if (!parent.confirmed.contains(key)) {
                existing.put(key, child);
            }
        }
        List<Node> kept = new ArrayList<>();
        int index = parent.confirmed.size();
        for (Node fresh : batch.nodes()) {
            String key = key(fresh.item());
            if (key == null || !parent.confirmed.add(key)) {
                continue;
            }
            Node current = existing.remove(key);
            if (current == null) {
                model.insertNodeInto(fresh, parent, Math.min(index, parent.getChildCount()));
            } else {
                if (!current.item().equals(fresh.item())) {
                    current.setUserObject(fresh.item());
                    model.nodeChanged(current);
                }
                if (fresh.getChildCount() == 0) {
                    // Mark a confirmed leaf as loaded so stale cache placeholders cannot reappear.
                    if (!current.loaded && current.getChildCount() > 0) {
                        model.removeNodeFromParent((Node) current.getFirstChild());
                    }
                    current.loaded = true;
                } else if (current.getChildCount() == 0) {
                    // A previous leaf can become a directory in a newer source listing.
                    current.loaded = false;
                    model.insertNodeInto((Node) fresh.getFirstChild(), current, 0);
                    if (tree.isExpanded(new TreePath(current.getPath()))) {
                        // Schon aufgeklappt: Ohne erneutes Aufklappen käme kein Laden, „Lädt …“ bliebe stehen.
                        load(current);
                    }
                }
                if (parent.getIndex(current) != index) {
                    model.removeNodeFromParent(current);
                    model.insertNodeInto(current, parent, index);
                }
                kept.add(current);
            }
            index++;
        }
        if (batch.nextCursor() == null) {
            // Letzter Stapel: Was die Quelle nicht bestätigt hat, gibt es nicht mehr.
            for (Node gone : existing.values()) {
                model.removeNodeFromParent(gone);
            }
            parent.confirmed = null;
        } else {
            model.insertNodeInto(new Node(new MoreItem(batch.nextCursor())), parent, parent.getChildCount());
        }
        if (parent.getChildCount() == 0) {
            Node empty = new Node(new Note(parent.item() instanceof SourceItem ? "Keine Spaces" : "Keine Seiten"));
            empty.loaded = true;
            model.insertNodeInto(empty, parent, 0);
        }
        for (Node child : kept) {
            reread(child);
        }
    }

    /**
     * Führt eine Änderung unter {@code parent} aus und stellt danach Auswahl, aufgeklappte Pfade und Rollposition
     * wieder her. Auswahl-Ereignisse währenddessen erreichen die Mitte nicht; fehlt danach ein gewählter Knoten, meldet
     * der Baum die neue Auswahl einmal.
     */
    private void keepingView(Node parent, Runnable change) {
        TreePath[] selected = tree.getSelectionPaths();
        List<TreePath> expanded = new ArrayList<>();
        Enumeration<TreePath> open = tree.getExpandedDescendants(new TreePath(parent.getPath()));
        if (open != null) {
            expanded.addAll(java.util.Collections.list(open));
        }
        java.awt.Point position = viewPosition();
        merging = true;
        try {
            change.run();
            for (TreePath path : expanded) {
                if (attached(path)) {
                    tree.expandPath(path);
                }
            }
            List<TreePath> still = new ArrayList<>();
            if (selected != null) {
                for (TreePath path : selected) {
                    if (attached(path)) {
                        still.add(path);
                    }
                }
            }
            tree.setSelectionPaths(still.toArray(TreePath[]::new));
            if (position != null && tree.getParent() instanceof javax.swing.JViewport viewport) {
                viewport.setViewPosition(position);
            }
        } finally {
            merging = false;
        }
        if (selected != null && tree.getSelectionCount() != selected.length) {
            selectionChanged();
        }
    }

    private java.awt.Point viewPosition() {
        return tree.getParent() instanceof javax.swing.JViewport viewport ? viewport.getViewPosition() : null;
    }

    /** Ob alle Knoten des Pfads noch im Baum hängen. */
    private boolean attached(TreePath path) {
        Object last = path.getLastPathComponent();
        return last instanceof Node node && node.getRoot() == top;
    }

    static Batch children(AppRuntime runtime, Item item, String cursor) {
        return switch (item) {
            case SourceItem source -> new Batch(runtime.workspace().spaces(source.sourceId()).stream()
                    .sorted(Comparator.comparing((Partition p) -> p.name().toLowerCase(Locale.ROOT)))
                    .map(p -> expandable(new SpaceItem(source.sourceId(), p.key(), p.name()))).toList(), null);
            case SpaceItem space -> pages(runtime, space.sourceId(), space.spaceKey(), null, cursor);
            case PageItem page -> pages(runtime, page.sourceId(), page.spaceKey(), page.pageId(), cursor);
            default -> new Batch(List.of(), null);
        };
    }

    private static Batch pages(AppRuntime runtime, String sourceId, String spaceKey, String parentPageId,
                               String cursor) {
        PageNavigation.Children children;
        try {
            children = runtime.workspace().children(sourceId, spaceKey, parentPageId, cursor);
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.UNSUPPORTED && parentPageId == null) {
                return wholeSpace(runtime, sourceId, spaceKey);
            }
            throw e;
        }
        return nodes(sourceId, children);
    }

    private static Batch nodes(String sourceId, PageNavigation.Children children) {
        List<Node> nodes = new ArrayList<>();
        for (HarvestSource.PageNode page : children.pages()) {
            PageItem item = PageItem.of(sourceId, page.partitionKey() == null ? children.spaceKey()
                    : page.partitionKey(), page.pageId(), page.title(), page.hasChildren(), page.kind());
            nodes.add(Boolean.FALSE.equals(page.hasChildren()) ? leaf(item) : expandable(item));
        }
        return new Batch(nodes, children.nextCursor());
    }

    private static Node leaf(Item item) {
        Node node = new Node(item);
        node.loaded = true;
        return node;
    }

    /**
     * Quellen ohne Live-Seitenbaum (etwa Ordner): der Seitenbaum des Space aus den Metadaten der Quelle, auf einmal;
     * nur wenn die Quelle nicht antwortet, aus dem Bestand. Der Bestand kann unvollständig sein (die
     * Hintergrundbefüllung legt Seite für Seite ab), darum ist er nur der Ausweich und nie die erste Wahl: sonst
     * zeigte der Baum zeitweise nur die schon abgelegten Seiten.
     */
    private static Batch wholeSpace(AppRuntime runtime, String sourceId, String spaceKey) {
        Map<String, String> titles = new HashMap<>();
        Set<String> folders = new HashSet<>();
        PageTree pageTree;
        try {
            Map<String, TreeEntry> entries = new LinkedHashMap<>();
            String next = null;
            do {
                PageMetadataListing listing = runtime.workspace().liveMetadata(sourceId, spaceKey, next, BATCH);
                for (PageMetadataListing.Entry entry : listing.entries()) {
                    PageMetadata page = entry.page();
                    entries.putIfAbsent(page.pageId(), new TreeEntry(page.pageId(), page.parentPageId()));
                    titles.putIfAbsent(page.pageId(), page.title());
                    if (folder(page)) {
                        folders.add(page.pageId());
                    }
                }
                next = listing.nextCursor();
            } while (next != null);
            pageTree = PageTreeBuilder.build(entries.values());
        } catch (RuntimeException unreachable) {
            SpaceTree stock;
            try {
                stock = runtime.workspace().tree(sourceId, spaceKey);
            } catch (RuntimeException notStocked) {
                unreachable.addSuppressed(notStocked);
                throw unreachable;
            }
            titles.clear();
            folders.clear();
            stock.pages().forEach((id, page) -> {
                titles.put(id, page.header().metadata().title());
                if (folder(page.header().metadata())) {
                    folders.add(id);
                }
            });
            pageTree = stock.tree();
        }
        List<Node> nodes = new ArrayList<>();
        for (PageTree.Node child : pageTree.roots()) {
            nodes.add(built(sourceId, spaceKey, child, titles, folders));
        }
        return new Batch(nodes, null);
    }

    /** Ein Ordner der Quelle (Ordner-Quelle, BSCW): im Baum mit Ordnersymbol. */
    private static boolean folder(PageMetadata page) {
        return PageMetadata.FOLDER_KIND.equals(page.attributes().get(PageMetadata.RESOURCE_KIND));
    }

    private static Node built(String sourceId, String spaceKey, PageTree.Node node, Map<String, String> titles,
                              Set<String> folders) {
        String title = titles.get(node.pageId());
        Node built = leaf(PageItem.of(sourceId, spaceKey, node.pageId(),
                title == null || title.isBlank() ? node.pageId() : title, !node.children().isEmpty(),
                folders.contains(node.pageId()) ? HarvestSource.PageNode.Kind.FOLDER
                        : HarvestSource.PageNode.Kind.PAGE));
        for (PageTree.Node child : node.children()) {
            built.add(built(sourceId, spaceKey, child, titles, folders));
        }
        return built;
    }

    // ------------------------------------------------------------------ Titelfilter

    private void runFilter() {
        String text = filter.getText().trim();
        int id = ++filterRequest;
        if (text.isEmpty()) {
            cards.show(deck, onlyFavorites ? FAVORITES : TREE);
            note.setText(" ");
            return;
        }
        cards.show(deck, MATCHES);
        String needle = text.toLowerCase(Locale.ROOT);
        Map<String, Match> found = new LinkedHashMap<>();
        Enumeration<?> all = top.breadthFirstEnumeration();
        while (all.hasMoreElements() && found.size() < MATCH_LIMIT) {
            if (all.nextElement() instanceof Node node && node.item() instanceof PageItem page && page.findable()
                    && page.title().toLowerCase(Locale.ROOT).contains(needle)) {
                found.putIfAbsent(page.sourceId() + "/" + page.pageId(), new Match(page.sourceId(), page.spaceKey(),
                        page.pageId(), page.title(), where(page.sourceId(), page.spaceKey())));
            }
        }
        show(found, true);
        AppRuntime runtime = context.runtime();
        PageSearchQuery query;
        try {
            query = PageSearchQuery.of(null, List.of(), List.of(), text, null, null, null, null, null,
                    SearchSort.TITLE);
        } catch (InvalidQueryException invalid) {
            note.setText(invalid.getMessage());
            return;
        }
        if (runtime == null) {
            return;
        }
        List<String> sources = List.copyOf(shownSources);
        // Erst der Bestand, dann jede Quelle selbst (CQL „Titel enthält“), damit auch ungeladene Seiten erscheinen.
        Background.run(() -> runtime.workspace().search(query, null, MATCH_LIMIT), results -> {
            if (id != filterRequest) {
                return;
            }
            for (SearchHit hit : results.hits()) {
                PageMetadata page = hit.page().header().metadata();
                String sourceId = hit.page().header().key().sourceId();
                found.putIfAbsent(sourceId + "/" + page.pageId(), new Match(sourceId, page.partitionKey(),
                        page.pageId(), page.title(), where(sourceId, page.partitionKey())));
            }
            show(found, true);
            live(runtime, id, text, sources, found);
        }, error -> {
            if (id == filterRequest) {
                show(found, true);
                live(runtime, id, text, sources, found);
            }
        });
    }

    /** Die Titelsuche jeder Quelle ({@code PageNavigation.findByTitle}); eine Quelle, die scheitert, fehlt nur. */
    private void live(AppRuntime runtime, int id, String text, List<String> sources, Map<String, Match> found) {
        Background.run(() -> {
            List<Match> matches = new ArrayList<>();
            boolean truncated = false;
            for (String sourceId : sources) {
                try {
                    PageNavigation.TitleMatches result = runtime.workspace().findByTitle(sourceId, Set.of(), text,
                            MATCH_LIMIT);
                    truncated |= result.truncated();
                    for (HarvestSource.PageNode page : result.pages()) {
                        matches.add(new Match(sourceId, page.partitionKey(), page.pageId(), page.title(), null));
                    }
                } catch (RuntimeException unavailable) {
                    // Nicht erreichbar oder ohne Titelsuche: Bestand und geladene Knoten bleiben.
                }
            }
            return new LiveMatches(matches, truncated);
        }, live -> {
            if (id != filterRequest) {
                return;
            }
            for (Match match : live.matches()) {
                found.putIfAbsent(match.sourceId() + "/" + match.pageId(), new Match(match.sourceId(),
                        match.spaceKey(), match.pageId(), match.title(), where(match.sourceId(), match.spaceKey())));
            }
            show(found, false);
            if (live.truncated() || found.size() > MATCH_LIMIT) {
                note.setText(note.getText() + " · weitere vorhanden, Titel genauer eingeben");
            }
        }, error -> {
            if (id == filterRequest) {
                show(found, false);
            }
        });
    }

    private record LiveMatches(List<Match> matches, boolean truncated) {
    }

    private void show(Map<String, Match> found, boolean searching) {
        matchModel.clear();
        found.values().stream().limit(MATCH_LIMIT).forEach(matchModel::addElement);
        note.setText(searching ? "Wissensquellen werden durchsucht …"
                : found.isEmpty() ? "Kein Seitentitel passt" : Texts.count(Math.min(found.size(), MATCH_LIMIT),
                "Seite", "Seiten"));
    }

    private String where(String sourceId, String spaceKey) {
        String source = sourceId;
        for (int i = 0; i < top.getChildCount(); i++) {
            if (((Node) top.getChildAt(i)).item() instanceof SourceItem item && item.sourceId().equals(sourceId)) {
                source = item.title();
            }
        }
        return spaceKey == null ? source : source + " › " + spaceKey;
    }

    private void openMatch() {
        Match match = matches.getSelectedValue();
        if (match != null) {
            context.navigator().openPage(match.sourceId(), match.pageId());
        }
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Eine Zeile über die volle Breite: links Symbol und Titel je Ebene (Datenbank für Quellen, Ordner für Spaces, Seite
     * für Seiten; Hinweise gedämpft), rechtsbündig Bestandszeichen und Stern im Stil der Space-Aktionen
     * ({@link RoundHover}).
     */
    private final class Renderer extends javax.swing.tree.DefaultTreeCellRenderer {

        /** Ob die Zeile der hervorgehobene Suchtreffer ist ({@link #hint}). */
        private boolean hinted;

        /** Breite von Symbol und Titel, ohne die Spalten rechts. */
        private int getPreferredSizeOfText() {
            java.awt.FontMetrics metrics = getFontMetrics(getFont());
            int icon = getIcon() == null ? 0 : getIcon().getIconWidth() + getIconTextGap();
            return icon + metrics.stringWidth(getText() == null ? "" : getText()) + 8;
        }

        /** Ob die Zeile einen Stern trägt (Space oder Seite): gefüllt als Favorit, sonst nur beim Darüberfahren. */
        private boolean columned;
        private boolean starred;
        private TreeStates.Badge badge = TreeStates.Badge.NONE;
        /** Trägt die Farbe des Zeichens; {@link Glyph} malt in der Vordergrundfarbe seines Wirts. */
        private final JLabel badgeHost = new JLabel();
        private boolean favorite;
        private int row = -1;
        private int width;

        Renderer() {
            // Auswahl wie die gewählte Quellenkarte: Comic-Gelb mit Tinte.
            setBackgroundNonSelectionColor(new java.awt.Color(0, 0, 0, 0));
            setBackgroundSelectionColor(Comic.YELLOW);
            setTextSelectionColor(Comic.INK);
            setBorderSelectionColor(null);
            setFont(Comic.regular(13f));
        }

        @Override
        public java.awt.Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                               boolean expanded, boolean leaf, int row,
                                                               boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            Item item = value instanceof Node node ? node.item() : null;
            setForeground(selected ? getTextSelectionColor() : Comic.INK);
            switch (item) {
                case SourceItem ignored -> setIcon(MainWindow.database());
                case SpaceItem ignored -> setIcon(SidebarIcons.folder());
                case PageItem page -> setIcon(page.kind() == HarvestSource.PageNode.Kind.FOLDER
                        || page.kind() == HarvestSource.PageNode.Kind.GROUP
                        ? SidebarIcons.folder() : SidebarIcons.page());
                case MoreItem ignored -> {
                    setIcon(null);
                    setForeground(Comic.BLUE);
                }
                case null, default -> {
                    setIcon(null);
                    setForeground(Comic.MUTED);
                }
            }
            // Bestandszeichen und Stern hängen am Knoten, nicht an Ebene oder Quellart (Angelo 26.11.2026): jeder
            // Knoten der Wissensquellen hat die Spalten rechts; den Stern zeichnet nur, wer Favorit werden kann.
            columned = item instanceof KnowledgeNode;
            starred = item instanceof KnowledgeNode known && known.starrable();
            favorite = starred && isFavorite(item);
            badge = badge(item);
            this.row = row;
            hinted = value != null && value == hint;
            int depth = value instanceof Node node ? node.getLevel() : 1;
            // Die Spalten rechts liegen im rechten Rand des Labels: der Titel endet davor und wird nötigenfalls
            // mit „…“ gekürzt, statt die Symbole aus dem sichtbaren Bereich zu schieben.
            int columns = columned ? ACTION_WIDTH + BADGE_WIDTH : 0;
            setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, columns));
            int visible = tree.getParent() == null ? 0 : tree.getParent().getWidth() - indent(depth) - 4;
            width = Math.max(visible, columns + MIN_TITLE_WIDTH);
            return this;
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            java.awt.Dimension size = super.getPreferredSize();
            return new java.awt.Dimension(width, Math.max(size.height, RoundHover.SIZE));
        }

        @Override
        public void paint(java.awt.Graphics graphics) {
            super.paint(graphics);
            if (hinted) {
                // Treffer der Suche: blauer Rahmen um den Titel, keine Auswahl (der Filter bleibt, Angelo 23.11.2026).
                java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Comic.BLUE);
                g.setStroke(new java.awt.BasicStroke(2f));
                int textEnd = Math.min(getWidth() - 1, getPreferredSizeOfText());
                g.drawRoundRect(1, 1, textEnd - 2, getHeight() - 3, 8, 8);
                g.dispose();
            }
            if (!columned) {
                return;
            }
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                // Der Stern ganz rechts (Angelo, 16.11.2026): immer sichtbar, damit man weiß, wo man klickt – als
                // Favorit gefüllt in Tinte, sonst als gedämpfter Umriss, beim Darüberfahren in Tinte.
                int sx = getWidth() - ACTION_WIDTH;
                if (starred) {
                    Glyph star = favorite ? Glyph.STAR_FILLED : Glyph.STAR;
                    badgeHost.setForeground(favorite || row == hoverStarRow ? Comic.INK : Comic.MUTED);
                    star.paintIcon(badgeHost, g, sx + (ACTION_WIDTH - star.getIconWidth()) / 2,
                            (getHeight() - star.getIconHeight()) / 2);
                }
                // Das Bestandszeichen links vom Stern, in seiner Farbe (Haken grün, Wolke gedämpft, Pfeile blau …);
                // unter der Maus stattdessen die Kreispfeile zum Nachholen (Angelo, 16.11.2026).
                int bx = sx - BADGE_WIDTH;
                javax.swing.Icon glyph = row == hoverBadgeRow ? Glyph.SYNC
                        : badge.kind() == TreeStates.Kind.NONE ? null : badge.glyph();
                if (row == hoverBadgeRow) {
                    RoundHover.paint(g, bx + (BADGE_WIDTH - RoundHover.SIZE) / 2, 0, RoundHover.SIZE, getHeight(),
                            false);
                }
                if (glyph != null) {
                    badgeHost.setForeground(row == hoverBadgeRow ? Comic.INK : badge.color());
                    glyph.paintIcon(badgeHost, g, bx + (BADGE_WIDTH - glyph.getIconWidth()) / 2,
                            (getHeight() - glyph.getIconHeight()) / 2);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** Vermisst alle Zeilen neu, etwa nach einer neuen Breite des sichtbaren Bereichs. */
    void remeasure() {
        tree.setCellRenderer(new Renderer());
    }

    /** Linker Rand einer Zeile dieser Tiefe, wie {@code BasicTreeUI} ihn ohne sichtbare Wurzel legt. */
    private int indent(int depth) {
        if (tree.getUI() instanceof javax.swing.plaf.basic.BasicTreeUI basic) {
            return (basic.getLeftChildIndent() + basic.getRightChildIndent()) * depth;
        }
        return 20 * depth;
    }

    /**
     * Ob genau dieser Knoten zum Indexieren markiert ist; eine Quelle, wenn jeder Space, den der Baum von ihr kennt,
     * ganz markiert ist. Ohne Laufzeit nichts.
     */
    boolean indexed(Item item) {
        com.aresstack.spaceharvester.app.runtime.AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return false;
        }
        return switch (item) {
            case SourceItem source -> {
                java.util.Set<String> spaces = treeSpaces().getOrDefault(source.sourceId(), java.util.Set.of());
                yield !spaces.isEmpty() && spaces.stream()
                        .allMatch(space -> runtime.workspace().indexed(source.sourceId(), space, null));
            }
            case KnowledgeNode node -> runtime.workspace().indexed(node.sourceId(), node.spaceKey(), node.pageId());
            default -> false;
        };
    }

    /** Markiert den Knoten zum Indexieren und startet den Abgleich; der Auftrag erscheint unter Aufträge. */
    private void index(KnowledgeNode node) {
        context.navigator().index(node.sourceId(), node.spaceKey(), node.pageId());
    }

    /** Schließt den Knoten aus und nimmt ihn aus dem Baum; die Meldung nennt, wo man ihn wieder einblendet. */
    private void exclude(KnowledgeNode item) {
        com.aresstack.spaceharvester.app.runtime.AppRuntime runtime = context.runtime();
        if (runtime == null) {
            notice("Ausschließen nicht möglich: Die Laufzeit ist nicht gestartet", Severity.ERROR);
            return;
        }
        if (!item.excludable()) {
            return;
        }
        String title = item.toString();
        Background.run(() -> {
            runtime.workspace().exclude(item.sourceId(), item.spaceKey(), item.pageId());
            return Boolean.TRUE;
        }, done -> {
            removeShown(item);
            notice(String.format(EXCLUDED_NOTICE, title), Severity.INFO);
        }, error -> notice("Ausschließen ließ sich nicht speichern: " + Problems.describe(error).title(),
                Severity.ERROR));
    }

    /** Fragt den neuen Anzeigenamen ab und speichert ihn wie die Quellenverwaltung ({@link AppContext#renameSource}). */
    private void rename(SourceItem source) {
        String typed = Dialogs.askText(tree, RENAME, String.format(RENAME_TEXT, source.title(), source.sourceId()),
                source.title());
        if (typed == null || typed.equals(source.title())) {
            return;
        }
        context.renameSource(source.sourceId(), typed,
                () -> notice("Quelle umbenannt in „" + typed + "“", Severity.INFO),
                error -> notice("Umbenennen ließ sich nicht speichern: " + Problems.describe(error).title(),
                        Severity.ERROR));
    }

    /**
     * Lädt die Knoten der Quelle neu, ohne die Quelle abzugleichen, etwa nachdem in den Einstellungen etwas
     * ausgeschlossen oder wieder eingeblendet wurde; eine aufgeklappte Quelle klappt wieder auf.
     */
    void reloadSource(String sourceId) {
        for (int i = 0; i < top.getChildCount(); i++) {
            if (top.getChildAt(i) instanceof Node node && node.item() instanceof SourceItem source
                    && source.sourceId().equals(sourceId) && !node.loading) {
                TreePath path = new TreePath(node.getPath());
                boolean expanded = tree.isExpanded(path);
                node.loaded = false;
                node.removeAllChildren();
                node.add(new Node(new Note(LOADING)));
                model.nodeStructureChanged(node);
                if (expanded) {
                    tree.expandPath(path);
                }
            }
        }
    }

    /** Nimmt jeden Knoten mit diesem Eintrag aus dem Baum, samt seinen Kindern. */
    private void removeShown(Item item) {
        List<Node> shown = new ArrayList<>();
        java.util.Enumeration<javax.swing.tree.TreeNode> all = top.depthFirstEnumeration();
        while (all.hasMoreElements()) {
            if (all.nextElement() instanceof Node node && item.equals(node.item()) && node.getParent() != null) {
                shown.add(node);
            }
        }
        shown.forEach(model::removeNodeFromParent);
    }

    /** Hebt die Markierung auf; an einer Quelle für jeden Space, den der Baum von ihr kennt. */
    private void unindex(Item item) {
        switch (item) {
            case SourceItem source -> treeSpaces().getOrDefault(source.sourceId(), java.util.Set.of())
                    .forEach(space -> context.navigator().unindex(source.sourceId(), space, null));
            case KnowledgeNode node -> context.navigator().unindex(node.sourceId(), node.spaceKey(), node.pageId());
            default -> {
            }
        }
    }

    /** Ob der Punkt auf dem Bestandszeichen der Zeile liegt (links vom Stern). */
    private boolean onBadge(TreePath path, java.awt.Point point) {
        java.awt.Rectangle bounds = path == null ? null : tree.getPathBounds(path);
        return bounds != null && path.getLastPathComponent() instanceof Node node
                && node.item() instanceof KnowledgeNode known && known.refreshable()
                && point.x >= bounds.x + bounds.width - ACTION_WIDTH - BADGE_WIDTH
                && point.x < bounds.x + bounds.width - ACTION_WIDTH;
    }

    private String badgeTip(TreePath path) {
        TreeStates.Badge badge = path.getLastPathComponent() instanceof Node node ? badge(node.item())
                : TreeStates.Badge.NONE;
        return badge.tooltip();
    }

    /** Das Bestandszeichen einer Zeile: Quellen über alle ihre Spaces, Spaces nach ihrem Stand, Seiten nach Zustand bzw. Teilbaum. */
    TreeStates.Badge badge(Item item) {
        return item instanceof KnowledgeNode node ? node.badge(states, treeSpaces()) : TreeStates.Badge.NONE;
    }

    /** Für Tests: der Bestand. */
    TreeStates states() {
        return states;
    }

    /** Ob der Punkt auf dem Stern der Zeile liegt (ganz rechts). */
    private boolean onStar(TreePath path, java.awt.Point point) {
        java.awt.Rectangle bounds = path == null ? null : tree.getPathBounds(path);
        return bounds != null && path.getLastPathComponent() instanceof Node node
                && node.item() instanceof KnowledgeNode known && known.starrable()
                && point.x >= bounds.x + bounds.width - ACTION_WIDTH;
    }

    // ------------------------------------------------------------------ Favoriten

    boolean isFavorite(Item item) {
        return item instanceof KnowledgeNode node
                && context.favorites().contains(node.sourceId(), node.spaceKey(), node.pageId());
    }

    /**
     * Setzt den Favoriten des Knotens oder nimmt ihn zurück. Eine Seite merkt sich dabei ihren Pfad (Space und
     * Vorfahren aus {@code PageNavigation.position}, im Hintergrund); ein Space nur seine Quelle.
     */
    void toggleFavorite(Item item) {
        Favorites store = context.favorites();
        switch (item) {
            case SourceItem source -> store.toggle(Favorites.Favorite.source(source.sourceId(),
                    sourceTitle(source.sourceId())));
            case SpaceItem space -> store.toggle(Favorites.Favorite.space(space.sourceId(), space.spaceKey(),
                    space.name(), sourceTitle(space.sourceId())));
            case PageItem page -> {
                if (store.contains(page.sourceId(), page.spaceKey(), page.pageId())) {
                    store.toggle(new Favorites.Favorite(page.sourceId(), page.spaceKey(), page.pageId(), page.title(),
                            List.of(), List.of()));
                    return;
                }
                AppRuntime runtime = context.runtime();
                String source = sourceTitle(page.sourceId());
                if (runtime == null) {
                    store.toggle(favorite(null, source, page));
                    return;
                }
                Background.run(() -> runtime.workspace().position(page.sourceId(), page.pageId()), position ->
                        store.toggle(favorite(position, source, page)), error ->
                        store.toggle(favorite(null, source, page)));
            }
            default -> {
            }
        }
    }

    /** Ein Seiten-Favorit aus dem Pfad der Seite: Quelle › Space › Vorfahren, dazu die IDs der Vorfahren. */
    static Favorites.Favorite favorite(PageNavigation.Position position, String sourceTitle, PageItem page) {
        return Favorites.Favorite.page(page.sourceId(), page.spaceKey(), page.pageId(), page.title(), position,
                sourceTitle);
    }

    private String sourceTitle(String sourceId) {
        return where(sourceId, null);
    }

    /** Umschalter unten rechts: nur die Favoriten als Liste (Stern gefüllt) oder der ganze Baum (Umriss). */
    void showOnlyFavorites(boolean only) {
        onlyFavorites = only;
        favoritesOnly.setIcon(only ? Glyph.STAR_FILLED : Glyph.STAR);
        favoritesOnly.setToolTipText(only ? ALL_NODES : ONLY_FAVORITES);
        favoritesOnly.getAccessibleContext().setAccessibleName(only ? ALL_NODES : ONLY_FAVORITES);
        favorites.newFolderButton().setVisible(only);
        favoritesChanged();
        if (filter.getText().trim().isEmpty()) {
            cards.show(deck, only ? FAVORITES : TREE);
            note.setText(only && favorites.isEmpty() ? "Noch keine Favoriten: Stern an Quelle, Space oder Seite" : " ");
        }
    }

    boolean onlyFavorites() {
        return onlyFavorites;
    }

    private void favoritesChanged() {
        boolean empty = favorites.reload();
        if (onlyFavorites && filter.getText().trim().isEmpty()) {
            note.setText(empty ? "Noch keine Favoriten: Stern an Quelle, Space oder Seite" : " ");
        }
        tree.repaint();
    }

    /** Der im Baum gewählte Knoten, null ohne Auswahl. */
    private Item selectedItem() {
        TreePath path = tree.getSelectionPath();
        return path != null && path.getLastPathComponent() instanceof Node node ? node.item() : null;
    }

    /**
     * Ein Ordner der Favoriten wurde gewählt (ADR 0034): bekommt ID und Namen; der Empfänger wählt die Sammlung
     * ({@code ContentView.folder}), damit es genau eine Auflösung Ordner → Wurzeln gibt.
     */
    public void onFolder(java.util.function.BiConsumer<String, String> listener) {
        favorites.onFolder(listener);
    }

    /** Für Tests: die Favoritenansicht und ihr Umschalter. */
    FavoritesView favoriteView() {
        return favorites;
    }

    IconButton favoritesButton() {
        return favoritesOnly;
    }

    private void popup(MouseEvent event) {
        TreePath path = tree.getPathForLocation(event.getX(), event.getY());
        if (path == null || !(path.getLastPathComponent() instanceof Node node)) {
            return;
        }
        if (!tree.isPathSelected(path)) {
            tree.setSelectionPath(path);
        }
        javax.swing.JPopupMenu menu = menu(node.item());
        if (menu.getComponentCount() > 0) {
            menu.show(tree, event.getX(), event.getY());
        }
    }

    /** Das Kontextmenü eines Knotens (für Tests ohne Mausereignis). */
    javax.swing.JPopupMenu menu(Item item) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        if (!(item instanceof KnowledgeNode node)) {
            return menu;
        }
        if (node instanceof PageItem page) {
            javax.swing.JMenuItem open = new javax.swing.JMenuItem("Öffnen");
            open.addActionListener(e -> {
                if (page.kind().hasOwnContent()) {
                    context.navigator().openPage(page.sourceId(), page.pageId());
                } else {
                    open(page);
                }
            });
            menu.add(open);
        }
        if (node.indexable()) {
            boolean marked = indexed(item);
            javax.swing.JMenuItem index = new javax.swing.JMenuItem(marked ? UNINDEX : INDEX);
            index.setToolTipText(marked ? UNINDEX_TIP : INDEX_TIP);
            index.addActionListener(e -> {
                if (marked) {
                    unindex(node);
                } else {
                    index(node);
                }
            });
            menu.add(index);
            if (node instanceof PageItem page && !page.leaf()) {
                javax.swing.JMenuItem only = new javax.swing.JMenuItem(PAGE_ONLY);
                only.setToolTipText(PAGE_ONLY_TIP);
                only.addActionListener(e -> context.navigator().downloadPage(page.sourceId(), page.spaceKey(),
                        page.pageId(), false));
                menu.add(only);
            }
        }
        if (node.excludable()) {
            javax.swing.JMenuItem exclude = new javax.swing.JMenuItem(EXCLUDE);
            exclude.setToolTipText(EXCLUDE_TIP);
            exclude.addActionListener(e -> exclude(node));
            menu.add(exclude);
        }
        if (node.starrable()) {
            javax.swing.JMenuItem star = new javax.swing.JMenuItem(isFavorite(item) ? UNFAVORITE : FAVORITE);
            star.setIcon(isFavorite(item) ? Glyph.STAR_FILLED : Glyph.STAR);
            star.addActionListener(e -> toggleFavorite(item));
            menu.add(star);
        }
        if (node.refreshable()) {
            javax.swing.JMenuItem refresh = new javax.swing.JMenuItem(REFRESH);
            refresh.setIcon(Glyph.SYNC);
            refresh.setToolTipText(REFRESH_TIP);
            refresh.addActionListener(e -> refresh(node));
            menu.add(refresh);
        }
        if (node instanceof SourceItem source) {
            javax.swing.JMenuItem rename = new javax.swing.JMenuItem(RENAME);
            rename.setToolTipText(RENAME_TIP);
            rename.addActionListener(e -> rename(source));
            menu.add(rename);
        }
        if (node.analysisScope(false) != null) {
            String unavailable = analyses.unavailable();
            javax.swing.JMenuItem analyze = new javax.swing.JMenuItem(ANALYZE);
            analyze.setEnabled(unavailable == null);
            analyze.setToolTipText(unavailable == null ? ANALYZE_TIP : unavailable);
            analyze.addActionListener(e -> analyze(item, false));
            menu.add(analyze);
            if (node instanceof PageItem page && !page.leaf()) {
                javax.swing.JMenuItem only = new javax.swing.JMenuItem(ANALYZE_PAGE);
                only.setEnabled(unavailable == null);
                only.setToolTipText(unavailable == null ? ANALYZE_PAGE_TIP : unavailable);
                only.addActionListener(e -> analyze(item, true));
                menu.add(only);
            }
            // ADR 0032, FL4: Der Prüfschritt steht direkt unter der Analyse, deren Aussagen er beurteilt.
            String noCheck = analyses.checkUnavailable();
            javax.swing.JMenuItem check = new javax.swing.JMenuItem(CHECK);
            check.setEnabled(noCheck == null);
            check.setToolTipText(noCheck == null ? CHECK_TIP : noCheck);
            check.addActionListener(e -> check(item, false));
            menu.add(check);
            if (node instanceof PageItem page && !page.leaf()) {
                javax.swing.JMenuItem only = new javax.swing.JMenuItem(CHECK_PAGE);
                only.setEnabled(noCheck == null);
                only.setToolTipText(noCheck == null ? CHECK_PAGE_TIP : noCheck);
                only.addActionListener(e -> check(item, true));
                menu.add(only);
            }
        }
        return menu;
    }

    /** Umfang von „Analysieren“ ({@link KnowledgeNode#analysisScope}); {@code null} für Hilfseinträge. */
    static StatementAnalysisJobs.Scope analysisScope(Item item, boolean pageOnly) {
        return item instanceof KnowledgeNode node ? node.analysisScope(pageOnly) : null;
    }

    /**
     * „Analysieren“: eine einzelne Seite startet sofort; größere Umfänge zählt der Core erst im Hintergrund, dann
     * fragt der Dialog mit der Zahl (exakt, „mehr als“ oder Schätzung) und „erneut analysieren“.
     */
    private void analyze(Item item, boolean pageOnly) {
        StatementAnalysisJobs.Scope scope = analysisScope(item, pageOnly);
        if (scope == null) {
            return;
        }
        if (scope instanceof StatementAnalysisJobs.Scope.SinglePage) {
            startAnalysis(scope, new StatementAnalysisJobs.Options(false, StatementAnalysisJobs.Options.DEFAULT
                    .maxPages(), null, analyzerSetting()));
            return;
        }
        notice("Umfang der Analyse wird ermittelt …", Severity.INFO);
        String where = analysisWhere(item);
        Background.run(() -> analyses.estimate(scope, ANALYSIS_LIMIT), estimate -> {
            if (estimate.exact() && estimate.pages() == 0) {
                notice("Keine Seiten zum Analysieren", Severity.INFO);
                return;
            }
            AnalysisChoice choice = confirmation.ask(tree, ANALYZE, analysisQuestion(estimate, where),
                    estimate.exceeded() ? "Alle analysieren" : ANALYZE);
            if (choice == null) {
                return;
            }
            // Über der Grenze hat der Benutzer ausdrücklich alle bestätigt; sonst schützt die Grenze davor, dass
            // der Umfang bis zum Start unbemerkt wächst (der Auftrag scheitert dann vor der ersten Analyse).
            startAnalysis(scope, new StatementAnalysisJobs.Options(choice.reanalyze(),
                    estimate.exceeded() ? 0 : ANALYSIS_LIMIT, choice.simplify()
                    ? com.aresstack.spaceharvester.application.knowledge.StatementAnalysis.SimplificationRequest
                            .of(simplification()) : null,
                    choice.analyzer() == null ? analyzerSetting() : choice.analyzer()));
        }, error -> notice("Analyse ließ sich nicht starten: " + Problems.describe(error).title(), Severity.ERROR));
    }

    /**
     * „Aussagen prüfen“: eine einzelne Seite startet sofort; größere Umfänge zählt der Core erst im Hintergrund, dann
     * fragt der Dialog mit der Zahl und „wirklich“, Antwort Ja oder Nein. Umfang und Modell kommen aus den
     * Einstellungen (Einstellungen › Modelle, „Prüfumfang“).
     */
    private void check(Item item, boolean pageOnly) {
        StatementAnalysisJobs.Scope scope = analysisScope(item, pageOnly);
        if (scope == null) {
            return;
        }
        if (scope instanceof StatementAnalysisJobs.Scope.SinglePage) {
            startCheck(scope, StatementAnalysisJobs.CheckOptions.of(ANALYSIS_LIMIT, checkSettings()));
            return;
        }
        notice("Umfang der Prüfung wird ermittelt …", Severity.INFO);
        String where = analysisWhere(item);
        Background.run(() -> analyses.estimate(scope, ANALYSIS_LIMIT), estimate -> {
            if (estimate.exact() && estimate.pages() == 0) {
                notice("Keine Seiten zum Prüfen", Severity.INFO);
                return;
            }
            if (!checkConfirmation.ask(tree, CHECK, checkQuestion(estimate, where))) {
                return;
            }
            // Wie bei der Analyse: Über der Grenze hat der Benutzer ausdrücklich alle bestätigt.
            startCheck(scope, StatementAnalysisJobs.CheckOptions.of(estimate.exceeded() ? 0 : ANALYSIS_LIMIT,
                    checkSettings()));
        }, error -> notice("Prüfung ließ sich nicht starten: " + Problems.describe(error).title(), Severity.ERROR));
    }

    private void startCheck(StatementAnalysisJobs.Scope scope, StatementAnalysisJobs.CheckOptions options) {
        notice("Prüfung wird gestartet …", Severity.INFO);
        Background.run(() -> analyses.startCheck(scope, options), started -> {
            notice(scope instanceof StatementAnalysisJobs.Scope.SinglePage ? "Prüfung der Seite gestartet"
                    : "Prüfung gestartet", Severity.INFO);
            if (context.navigator() != null) {
                context.navigator().showJob(started.id());
            }
        }, error -> notice(error
                instanceof com.aresstack.spaceharvester.application.check.CheckUnavailableException
                ? com.aresstack.spaceharvester.app.ui.content.CheckTexts.NO_MODEL
                : "Prüfung ließ sich nicht starten: " + Problems.describe(error).title(), Severity.ERROR));
    }

    /** Text der Rückfrage vor „Aussagen prüfen“: mit „wirklich“, Zahl exakt, „mehr als“ oder geschätzt. */
    static String checkQuestion(StatementAnalysisJobs.Estimate estimate, String where) {
        String pages = Texts.count(estimate.pages(), "Seite", "Seiten");
        StringBuilder text = new StringBuilder("Die Aussagen von ");
        if (estimate.exceeded()) {
            text.append("mehr als ").append(pages);
        } else if (estimate.incomplete()) {
            text.append(estimate.pages() == 0 ? "noch unbekannt vielen Seiten" : "etwa " + pages);
        } else {
            text.append(pages);
        }
        text.append(" wirklich prüfen?\n\nUmfang: ").append(where).append('.');
        if (estimate.exceeded()) {
            text.append("\n\nDas ist mehr als die Sicherheitsgrenze von ").append(ANALYSIS_LIMIT)
                    .append(" Seiten. Nach „Ja“ werden alle Seiten des Umfangs geprüft.");
        } else if (estimate.incomplete()) {
            text.append("\n\nDer Umfang ließ sich nicht vollständig ermitteln; der Auftrag meldet fehlende Teile.");
        }
        text.append("\n\nDas Chat-Modell bekommt nur Seiten mit KI-Freigabe und aktueller Analyse; die übrigen "
                + "zählt der Auftrag. Fortschritt und Abbruch unter Aufträge.");
        return text.toString();
    }

    private void startAnalysis(StatementAnalysisJobs.Scope scope, StatementAnalysisJobs.Options options) {
        notice("Analyse wird gestartet …", Severity.INFO);
        Background.run(() -> analyses.start(scope, options), started -> {
            notice(scope instanceof StatementAnalysisJobs.Scope.SinglePage ? "Analyse der Seite gestartet"
                    : "Analyse gestartet", Severity.INFO);
            if (context.navigator() != null) {
                context.navigator().showJob(started.id());
            }
        }, error -> notice("Analyse ließ sich nicht starten: " + Problems.describe(error).title(), Severity.ERROR));
    }

    /** Wo analysiert wird, für den Bestätigungsdialog. */
    static String analysisWhere(Item item) {
        return switch (item) {
            case SourceItem source -> "Quelle „" + source.title() + "“ mit allen Spaces und Seiten";
            case SpaceItem space -> "Space „" + space + "“ der Quelle " + space.sourceId() + " mit allen Seiten";
            case PageItem page -> "Seite „" + page.title() + "“ samt allen Unterseiten (Quelle " + page.sourceId()
                    + (page.spaceKey() == null ? "" : " · Space " + page.spaceKey()) + ")";
            default -> "";
        };
    }

    /** Text des Bestätigungsdialogs: exakte Zahl, „mehr als“ über der Grenze oder Schätzung bei Lücken. */
    /** Letzter Absatz des Dialogs „Analysieren“ mit dem MaltParser. */
    static final String PARSER_NOTE = "Fehlende Inhalte werden dafür heruntergeladen. Die Analyse läuft lokal und "
            + "braucht Speicher und Rechenzeit; Fortschritt und Abbruch unter Aufträge.";
    /** Derselbe Absatz mit dem KI-Analysator (Fahrplan A3): nichts läuft lokal, nur Seiten mit KI-Freigabe. */
    static final String MODEL_NOTE = "Fehlende Inhalte werden dafür heruntergeladen. Die Sätze gehen an den "
            + "eingestellten KI-Dienst. Analysiert werden nur Seiten mit KI-Freigabe, die übrigen werden ausgelassen. "
            + "Fortschritt und Abbruch unter Aufträge.";

    /** Der Text des Dialogs passend zum gewählten Analysator; er wechselt beim Umschalten des Auswahlfelds. */
    /**
     * Die Rückfrage vor „Analysieren“ eines größeren Umfangs mit dem Absatz des Analysators, wie im Baum; auch für den
     * Reiter „Aussagen“ mit „mit Unterseiten“ (Fahrplan D3).
     */
    public static String analysisQuestion(StatementAnalysisJobs.Estimate estimate, String where,
                                          AnalyzerChoice analyzer) {
        return analysisText(analysisQuestion(estimate, where), analyzer);
    }

    static String analysisText(String question, AnalyzerChoice analyzer) {
        return analyzer == AnalyzerChoice.MODEL ? question.replace(PARSER_NOTE, MODEL_NOTE) : question;
    }

    static String analysisQuestion(StatementAnalysisJobs.Estimate estimate, String where) {
        String pages = Texts.count(estimate.pages(), "Seite", "Seiten");
        StringBuilder text = new StringBuilder();
        if (estimate.exceeded()) {
            text.append("Wirklich mehr als ").append(pages).append(" analysieren?");
        } else if (estimate.incomplete()) {
            text.append(estimate.pages() == 0 ? "Umfang noch unbekannt (Schätzung). Wirklich analysieren?"
                    : "Etwa " + pages + " wirklich analysieren? (Schätzung)");
        } else {
            text.append(pages).append(" wirklich analysieren?");
        }
        text.append("\n\nUmfang: ").append(where).append('.');
        if (estimate.exceeded()) {
            text.append("\n\nDas ist mehr als die Sicherheitsgrenze von ").append(ANALYSIS_LIMIT)
                    .append(" Seiten. Nach Bestätigung werden alle Seiten des Umfangs analysiert.");
        } else if (estimate.incomplete()) {
            text.append("\n\nDer Umfang ließ sich nicht vollständig ermitteln; der Auftrag meldet fehlende Teile.");
        }
        text.append("\n\n").append(PARSER_NOTE);
        return text.toString();
    }

    /** Eine kurze Meldung in der Statuszeile (wie beim Herunterladen). */
    private void notice(String text, Severity severity) {
        context.events().publish(new StatusMessageEvent(text, NOTICE_MS, severity));
    }

    /** Für Tests. */
    void checkConfirmation(CheckConfirmation confirmation) {
        this.checkConfirmation = confirmation;
    }

    /** Für Tests. */
    void analyses(Analyses analyses, AnalysisConfirmation confirmation) {
        this.analyses = analyses;
        this.confirmation = confirmation;
    }

    private static void bindEnter(JComponent component, Runnable action) {
        bindKey(component, KeyEvent.VK_ENTER, action);
    }

    private static void bindKey(JComponent component, int key, Runnable action) {
        String name = "explorer-" + key;
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
        component.getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    /** Für Tests. */
    JTree tree() {
        return tree;
    }

    /** Für Tests: Zeile, über deren Stern die Maus steht. */
    int hoverStarRow() {
        return hoverStarRow;
    }

    /** Für Tests: Zeile, über deren Zustandspunkt die Maus steht. */
    int hoverBadgeRow() {
        return hoverBadgeRow;
    }

    /** Für Tests: der Maus-Listener des Baums, direkt aufrufbar ohne Fenster. */
    MouseAdapter treeMouse() {
        return treeMouse;
    }


    /** Für Tests: das Bestandszeichen der Kopfzeile. */
    JLabel allBadge() {
        return allBadge;
    }


    /** Für Tests: der Knopf neben der Überschrift. */
    IconButton selectAllButton() {
        return selectAll;
    }

    /** Für Tests: die anklickbare Überschrift. */
    JLabel heading() {
        return heading;
    }

    /** Für Tests: der Kopf (Überschrift und Filterfeld), gelb bei globaler Auswahl. */
    JPanel head() {
        return head;
    }

    /** Für Tests. */
    IconButton collapseAllButton() {
        return collapseAll;
    }

    /** Für Tests. */
    IconButton showCurrentButton() {
        return showCurrent;
    }

    /** Für Tests. */
    String note() {
        return note.getText();
    }

    /** Für Tests. */
    PlaceholderField filter() {
        return filter;
    }

    /** Für Tests. */
    JList<Match> matches() {
        return matches;
    }
}
