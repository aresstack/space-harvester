package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.SearchView;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.holdings.HoldingState;
import com.aresstack.spaceharvester.application.holdings.PageHolding;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.TermKnowledge;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearchListener;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Die Werkzeuge des Chats (ADR 0033): Was nicht offen ist, holt sich das Modell selbst, und zwar nur aus dem Bereich,
 * der beim Senden links gewählt war. Jeder Aufruf wird gegen diesen Bereich geprüft; eine Seite außerhalb, ohne
 * Recht oder unbekannt bekommt dieselbe Antwort {@link #UNAVAILABLE}, ohne zu verraten, ob es sie gibt. Gelesen wird
 * aus dem Cache; eine Seite des Bereichs, die nicht lokal liegt, holt {@value #READ} wie die Leseansicht von der Quelle.
 * Wurzeln, die der Katalog nicht kennt, erschließt {@link LiveArea} über die gemeinsame Navigation aller Quellen.
 *
 * <ul>
 *   <li>{@value #LIST}: die Kinder eines Knotens (ohne Angabe die Startseiten des Bereichs), nach Titel, blätternd,
 *   mit Kennzeichen „hat Unterseiten“, wie der Baum links; Titel aus den Kopfdaten, nie aus dem Lesetext.</li>
 *   <li>{@value #READ}: der Lesetext einer Seite in Stücken mit Versatz, je Stück so viel, wie in die Anfrage passt
 *   ({@link ChatRoom}).</li>
 *   <li>{@value #SEARCH}: dieselbe Suche wie die Suche mit Auswahl links, nie über den Bereich hinaus.</li>
 *   <li>{@value #TERM}: was der Wissensgraph im Bereich über einen bestätigten Fachbegriff weiß (Paket W7, auf
 *   {@link TermKnowledge#answer}): Aussagen mit Stufe und Belegseiten, Synonyme, Nachbarbegriffe und was gekappt
 *   wurde. Bereich und Obergrenzen erzwingt der Kern; jede Belegseite, deren Aussagen geliefert werden, geht vor der
 *   Antwort an {@link Sources#delivered} (ADR 0033 Punkt 8).</li>
 * </ul>
 *
 * <p>Seiten werden über eine Kennung {@code Quelle/Seite} angesprochen. Was das Modell gelesen hat, wird als
 * nummerierte Quelle gemeldet, damit es mit [n] zitieren kann.
 */
public final class ChatTools {

    static final String LIST = "seiten_auflisten";
    static final String READ = "seite_lesen";
    static final String SEARCH = "im_bereich_suchen";
    static final String TERM = "begriff_nachschlagen";
    /** Nur mit dem Schalter „Interne Suche“ im Composer: die globale Suche der App über alle Wissensquellen. */
    static final String APP_SEARCH = "wissensquellen_durchsuchen";
    /**
     * Nur für den Suchagenten mit Wissen: mehrere Treffer gleichzeitig von Unteragenten im Volltext auswerten lassen
     * (Angelo 24.11.2026, „Parallele Agenten (intern)“ im ▾-Menü des Chats).
     */
    static final String EVALUATE = "treffer_auswerten";
    /** Präfix der virtuellen Ordnerknoten einer Sammlung in {@value #LIST} (ADR 0034 §5). */
    static final String FOLDER = "ordner/";

    /**
     * So viele Treffer nennt die Suche höchstens über die Liste der Suchleiste hinaus (die Funde der progressiven Suche:
     * Varianten, Bewertung, Nachbarn); die Liste der Suchleiste nennt sie immer ganz ({@link #barResults}).
     */
    static final int SEARCH_HITS = 10;

    /** Der Menüeintrag zum Indexieren einer Quelle im Baum, für den Hinweis bei Quellen ohne Online-Suche. */
    static final String INDEX_ACTION = com.aresstack.spaceharvester.app.ui.Explorer.INDEX;
    /** Längstens so lange wartet ein Suchaufruf; danach zählen die bis dahin bewerteten Treffer. */
    static final long SEARCH_SECONDS = 60;

    /** Die eine Antwort für fremd, unbekannt, ohne Recht. */
    static final String UNAVAILABLE = "Diese Seite ist im gewählten Bereich nicht verfügbar.";
    static final String NOT_STORED = "Diese Seite liegt im gewählten Bereich, ist aber nicht lokal gespeichert.";
    /** Eine Seite des Bereichs ließ sich nicht von der Quelle holen; das Modell darf ihren Inhalt nicht erraten. */
    static final String NOT_FETCHED = "Diese Seite liegt im gewählten Bereich, ließ sich aber nicht von der Quelle "
            + "laden (%s). Ihr Inhalt ist dir unbekannt; erfinde ihn nicht.";
    /** Ordner haben keinen eigenen Text. */
    static final String FOLDER_ONLY = "Das ist ein Ordner ohne eigenen Text; seinen Inhalt zeigt " + LIST
            + " mit dieser Kennung als knoten.";
    /** Der Bereich ist gewählt, aber weder Katalog noch Navigation kennen eine Seite darin. */
    static final String EMPTY_AREA = "Im gewählten Bereich ist keine Seite bekannt. Sage das dem Benutzer und "
            + "erfinde keine Inhalte.";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final System.Logger LOG = System.getLogger(ChatTools.class.getName());
    /** Wohin Fehlergründe gehen (nur Werkzeug und Klasse, nie Argumente oder Inhalte); für Tests austauschbar. */
    static volatile java.util.function.Consumer<String> diagnosis = reason -> LOG.log(System.Logger.Level.WARNING,
            reason);

    /** Was das Modell gelesen hat, als nummerierte Quelle, und was an das Modell ging (KI-Freigabe, ADR 0033). */
    interface Sources {
        /** Nimmt die Seite auf (oder findet sie wieder) und gibt ihre Nummer. */
        int cite(ChatKnowledge.Excerpt excerpt, PageKey key);

        /**
         * Text oder Auszug der Seite geht gleich an das Modell: Sie kommt vorher auf die Liste freigegebener Seiten.
         * Scheitert der Eintrag, wirft es, und die Seite geht nicht hinaus (ADR 0033 Punkt 8, für alle Werkzeuge).
         */
        void delivered(String spaceKey, PageKey key);

        /** Eine Suche nannte die Seite als Treffer (nur Kennung und Titel, keine Freigabe); für den Fortschritt. */
        default void found(PageKey key) {
        }
    }

    /** Der Eintrag in die Liste freigegebener Seiten ist gescheitert; die Seite geht nicht an das Modell. */
    static final class ReleaseFailed extends RuntimeException {

        ReleaseFailed(RuntimeException cause) {
            super(cause.getClass().getSimpleName(), cause);
        }

        String reason() {
            return getCause().getClass().getSimpleName();
        }
    }

    static final String RELEASE_FAILED = "Die Seite ließ sich nicht für die KI freigeben (%s); ihr Inhalt geht "
            + "nicht an das Modell.";

    private final Workspace workspace;
    private final PageSelection scope;
    /**
     * Seiten außerhalb des Bereichs, die gelesen werden dürfen: die offene Seite und die Treffer von
     * {@value #APP_SEARCH} in diesem Turn.
     */
    private final Set<PageKey> granted;
    private final Sources sources;
    private volatile ProgressiveSearch search;
    private volatile boolean cancelled;
    /** Je Turn einmal geladen: Bestand und Titel je Space (aus den Kopfdaten, ohne Lesetext). */
    private final Map<String, Map<String, PageHolding>> holdings = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> titles = new java.util.concurrent.ConcurrentHashMap<>();

    /** Liest den Bestand eines Space; für Tests austauschbar. */
    interface HoldingsReader {
        Map<String, PageHolding> pages(String sourceId, String spaceKey);
    }

    private HoldingsReader reader;

    /** Fragt das Begriffswissen des Core ({@link Workspace#termKnowledge}); für Tests austauschbar. */
    interface TermLookup {
        TermKnowledge.Answer answer(String query, PageSet scope, TermKnowledge.Delivery delivery,
                                    BooleanSupplier cancelled);
    }

    private TermLookup terms;

    ChatTools(Workspace workspace, PageSelection scope, Set<PageKey> granted, Sources sources) {
        this.reader = (sourceId, spaceKey) -> workspace.holdings(sourceId, spaceKey).pages();
        this.terms = workspace::termKnowledge;
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.scope = scope;
        this.granted = java.util.concurrent.ConcurrentHashMap.newKeySet();
        this.granted.addAll(granted);
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /**
     * Suchmethode „Nachbarseiten“ (R2, C1) für {@value #SEARCH}, wie der Benutzer sie in den Suchoptionen gesetzt hat;
     * beim Senden festgehalten. Nachbarn kommen nur aus dem Bereich, und nur gelieferte Seiten werden freigegeben.
     */
    ChatTools neighbors(boolean on) {
        this.neighbors = on;
        return this;
    }

    private boolean neighbors;

    /** Suchmethode „Synonyme der Fachbegriffe“ (K5a) für die Suche im Bereich. */
    ChatTools glossarySynonyms(boolean on) {
        this.glossarySynonyms = on;
        return this;
    }

    private boolean glossarySynonyms;

    /**
     * Schalter „Interne Suche“ (Angelo 23.11.2026): {@value #APP_SEARCH} sucht wie die globale Suche der Suchleiste in
     * allen Wissensquellen, die {@code sources} nennt (die für die KI freigegebenen), und macht die Treffer für
     * {@value #READ} lesbar; {@code null} schaltet das Werkzeug ab.
     */
    ChatTools appSearch(java.util.function.Supplier<Set<String>> sources) {
        return appSearch(sources, sources);
    }

    ChatTools appSearch(java.util.function.Supplier<Set<String>> sources,
                        java.util.function.Supplier<Set<String>> explicitSources) {
        this.appSources = sources;
        this.explicitAppSources = explicitSources;
        return this;
    }

    private java.util.function.Supplier<Set<String>> appSources;
    private java.util.function.Supplier<Set<String>> explicitAppSources;

    /**
     * Ist eine Sammlung der Bereich (ADR 0034 §5), liefert {@code nodes} ihre Ordner und Favoriten für {@value #LIST};
     * {@code null} für jeden anderen Bereich.
     */
    ChatTools collection(CollectionNodes nodes) {
        this.nodes = nodes;
        return this;
    }

    private CollectionNodes nodes;

    /**
     * Die Wurzeln des Bereichs, die der Katalog nicht kennt (etwa ein SharePoint-Ordner, ein nie abgeglichener Space),
     * über die gemeinsame Navigation ({@link LiveArea}); {@link LiveArea#NONE}, wenn der Katalog alles abdeckt.
     */
    ChatTools liveArea(LiveArea area) {
        this.live = Objects.requireNonNull(area, "area");
        return this;
    }

    private LiveArea live = LiveArea.NONE;

    /** Die Werkzeuge dieses Turns: die vier des Bereichs, mit „Interne Suche“ dazu {@value #APP_SEARCH}. */
    List<AiChat.Tool> toolDefinitions() {
        if (scope == null) {
            // Ohne Bereich (Angelo 24.11.2026): nur die interne Suche samt Lesen ihrer Treffer, wenn sie an ist.
            if (appSources == null) return List.of();
            List<AiChat.Tool> all = new ArrayList<>(List.of(readDefinition(), appSearchDefinition()));
            if (explicitAppSources != null) all.addAll(storedProgramTools().definitions());
            return List.copyOf(all);
        }
        if (appSources == null) {
            return definitions(nodes != null);
        }
        List<AiChat.Tool> all = new ArrayList<>(definitions(nodes != null));
        all.add(appSearchDefinition());
        if (explicitAppSources != null) all.addAll(storedProgramTools().definitions());
        return List.copyOf(all);
    }

    /** {@value #READ}: liest eine Seite des Bereichs oder einen Treffer der internen Suche im Volltext. */
    static AiChat.Tool readDefinition() {
        return new AiChat.Tool(READ, "Liest den Text einer Seite des gewählten Bereichs oder eines Suchtreffers, "
                + "so viel, wie in die Anfrage passt; lange Seiten mit versatz weiterlesen. Liefert die "
                + "Quellennummer, mit der du die Seite zitierst.",
                "{\"type\":\"object\",\"properties\":{\"kennung\":{\"type\":\"string\"},"
                        + "\"versatz\":{\"type\":\"integer\",\"minimum\":0}},\"required\":[\"kennung\"]}");
    }

    /** {@value #EVALUATE}: je Kennung ein Unteragent, der die Seite liest und nur ihren Beitrag zur Frage liefert. */
    static AiChat.Tool evaluateDefinition() {
        return new AiChat.Tool(EVALUATE, "Lässt mehrere Treffer gleichzeitig von Unteragenten auswerten: Jeder liest "
                + "eine Seite im Volltext und liefert, was sie zur Frage beiträgt, samt Quellennummer. Schneller als "
                + "jede Seite einzeln mit " + READ + " zu lesen; für Einzelheiten danach " + READ + " verwenden.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"frage\":{\"type\":\"string\",\"description\":\"Worauf die Seiten geprüft werden; leer "
                        + "für die Frage des Nutzers\"},"
                        + "\"kennungen\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":"
                        + "\"Kennungen der Treffer aus den Suchwerkzeugen\"}},\"required\":[\"kennungen\"]}");
    }

    static AiChat.Tool appSearchDefinition() {
        StringBuilder kinds = new StringBuilder();
        for (com.aresstack.spaceharvester.application.query.DocumentKind kind
                : com.aresstack.spaceharvester.application.query.DocumentKind.values()) {
            kinds.append(kinds.isEmpty() ? "" : ",").append('"').append(kind.name()).append('"');
        }
        return new AiChat.Tool(APP_SEARCH, "Durchsucht alle Wissensquellen der App wie die globale Suche der "
                + "Suchleiste (semantisch, wenn verfügbar), unabhängig vom gewählten Bereich, und liefert die besten "
                + "Treffer mit Kennung, Titel und Quelle, ohne Text. Findet die erste Suche nichts Passendes, suche "
                + "mit anderen Begriffen oder Synonymen erneut. Den Text eines Treffers mit " + READ + " holen. Alle "
                + "Optionen sind freiwillig.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"text\":{\"type\":\"string\",\"description\":\"Suchbegriffe oder Frage\"},"
                        + "\"nachbarseiten\":{\"type\":\"boolean\",\"description\":\"Auch Nachbarseiten der "
                        + "Treffer (Eltern, Geschwister, Kinder) einbeziehen\"},"
                        + "\"fachbegriffe\":{\"type\":\"boolean\",\"description\":\"Auch Seiten zu erkannten "
                        + "Fachbegriffen der Anfrage einbeziehen (experimentell)\"},"
                        + "\"synonyme\":{\"type\":\"boolean\",\"description\":\"Auch vorgeschlagene Synonyme "
                        + "der Fachbegriffe suchen (experimentell)\"},"
                        + "\"arten\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[" + kinds
                        + "]},\"description\":\"Nur diese Dokumentarten; leer für alle\"},"
                        + "\"quellen\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                        + "\"description\":\"Nur diese Wissensquellen (Kennungen); leer für alle\"}},"
                        + "\"required\":[\"text\"]}");
    }

    /** Für Tests: ein anderer Zugriff auf den Bestand. */
    ChatTools holdingsReader(HoldingsReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
        return this;
    }

    /** Für Tests: ein anderes Begriffswissen. */
    ChatTools termLookup(TermLookup terms) {
        this.terms = Objects.requireNonNull(terms, "terms");
        return this;
    }

    /** Die Definitionen für die Anfrage; mit {@code collection} nennt {@value #LIST} die Ordnerknoten einer Sammlung. */
    static List<AiChat.Tool> definitions(boolean collection) {
        return List.of(
                new AiChat.Tool(SEARCH, "Sucht im gewählten Bereich nach Stichworten und liefert die besten Treffer "
                        + "mit Kennung und Titel, ohne Text. Den Text einer Seite danach mit " + READ + " holen.",
                        "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\","
                                + "\"description\":\"Suchbegriffe\"}},\"required\":[\"text\"]}"),
                new AiChat.Tool(LIST, collection
                        ? "Listet den gewählten Favoriten-Ordner wie einen Baum: ohne knoten seine Unterordner "
                        + "(Kennung " + FOLDER + "…) und Favoriten, mit knoten den Inhalt eines Unterordners oder "
                        + "die Unterseiten einer Seite. Je Aufruf so viele Einträge, wie in die Anfrage passen; "
                        + "weitere mit ab."
                        : "Listet die Seiten des gewählten Bereichs wie einen Baum: ohne knoten die "
                        + "Startseiten, mit knoten dessen Unterseiten. Je Aufruf so viele Einträge, wie in die Anfrage "
                        + "passen; weitere mit ab.",
                        "{\"type\":\"object\",\"properties\":{\"knoten\":{\"type\":\"string\","
                                + "\"description\":\"" + (collection ? "Kennung einer Seite oder eines Ordners"
                                : "Kennung einer Seite") + "; leer für die Startseiten\"},"
                                + "\"ab\":{\"type\":\"integer\",\"minimum\":0,\"description\":\"ab diesem "
                                + "Eintrag, Vorgabe 0\"}}}"),
                readDefinition(),
                new AiChat.Tool(TERM, "Schlägt einen Fachbegriff im gewählten Bereich nach: was der Wissensgraph "
                        + "dort über ihn weiß, als Aussagen mit Verb, Vertrauensstufe und Belegseiten, dazu seine "
                        + "Synonyme und verwandte Begriffe. Ist das Wort dort kein bestätigter Fachbegriff, nennt es "
                        + "ähnliche. Die Belegseiten vor dem Zitieren mit " + READ + " lesen.",
                        "{\"type\":\"object\",\"properties\":{\"begriff\":{\"type\":\"string\","
                                + "\"description\":\"Der Fachbegriff, etwa ein Wort aus der Frage\"}},"
                                + "\"required\":[\"begriff\"]}"));
    }

    /** Kurz, was der Aufruf tut, für die Statuszeile; ohne Argumente des Modells. */
    static String describe(AiChat.ToolCall call) {
        return switch (call.name()) {
            case SEARCH -> "Sucht im Bereich";
            case LIST -> "Listet Seiten";
            case READ -> "Liest eine Seite";
            case TERM -> "Schlägt einen Fachbegriff nach";
            case APP_SEARCH -> "Durchsucht die Wissensquellen";
            case StoredProgramChatTools.SOURCES, StoredProgramChatTools.SEARCH, StoredProgramChatTools.ANALYSIS ->
                    StoredProgramChatTools.describe(call.name());
            default -> "Werkzeug";
        };
    }

    void cancel() {
        cancelled = true;
        ProgressiveSearch running = search;
        if (running != null) {
            running.cancel();
        }
    }

    /** Führt einen Aufruf aus; das Ergebnis ist immer Text, nie eine Ausnahme. */
    String run(AiChat.ToolCall call) {
        JsonNode arguments;
        try {
            arguments = JSON.readTree(call.argumentsJson());
        } catch (JacksonException e) {
            return "Die Argumente sind kein gültiges JSON.";
        }
        if (arguments == null || !arguments.isObject()) {
            arguments = JSON.createObjectNode();
        }
        try {
            return switch (call.name()) {
                case SEARCH -> search(arguments.path("text").asString(""));
                case LIST -> list(arguments.path("knoten").asString(""), Math.max(0, arguments.path("ab").asInt(0)));
                case READ -> read(arguments.path("kennung").asString(""), Math.max(0, arguments.path("versatz")
                        .asInt(0)));
                case TERM -> term(arguments.path("begriff").asString(""));
                case APP_SEARCH -> appSearch(arguments);
                case StoredProgramChatTools.SOURCES, StoredProgramChatTools.SEARCH, StoredProgramChatTools.ANALYSIS ->
                        explicitAppSources == null || appSources == null ? "Unbekanntes Werkzeug."
                                : storedProgramTools().run(call.name(), arguments);
                default -> "Unbekanntes Werkzeug.";
            };
        } catch (CancellationException e) {
            return "Abgebrochen.";
        } catch (ReleaseFailed e) {
            diagnosis.accept("Chat-Werkzeug " + call.name() + ": Freigabe gescheitert: " + e.reason());
            return RELEASE_FAILED.formatted(e.reason());
        } catch (RuntimeException e) {
            // Grund in die Diagnose, ohne Argumente oder Inhalte (ADR 0033, Folgen).
            diagnosis.accept("Chat-Werkzeug " + call.name() + " gescheitert: " + e.getClass().getSimpleName());
            return "Das Werkzeug ist gescheitert (" + e.getClass().getSimpleName() + ").";
        }
    }

    // ------------------------------------------------------------------ Bereich

    public static String ref(PageKey key) {
        return key.sourceId() + "/" + key.pageId();
    }

    public static PageKey parse(String ref) {
        if (ref == null) {
            return null;
        }
        String trimmed = ref.strip();
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash == trimmed.length() - 1) {
            return null;
        }
        return new PageKey(trimmed.substring(0, slash), trimmed.substring(slash + 1));
    }

    /** Ob die Seite im Bereich liegt oder ausdrücklich erlaubt ist. */
    boolean allows(PageKey key) {
        return key != null && (scope != null && scope.contains(key) || granted.contains(key)
                || scope != null && live.contains(key));
    }

    /** Trägt die Seite ein, bevor ihr Inhalt an das Modell geht; scheitert es, {@link ReleaseFailed}. */
    private void release(String spaceKey, PageKey key) {
        try {
            sources.delivered(spaceKey, key);
        } catch (RuntimeException e) {
            throw new ReleaseFailed(e);
        }
    }

    // ------------------------------------------------------------------ Lesen

    String read(String ref, int offset) {
        PageKey key = parse(ref);
        if (!allows(key)) {
            return UNAVAILABLE;
        }
        if (live.isFolder(key)) {
            return FOLDER_ONLY;
        }
        Page page;
        try {
            page = fetchedPage(workspace, key);
        } catch (RuntimeException failed) {
            diagnosis.accept("Chat-Werkzeug " + READ + ": Abruf gescheitert: " + failed.getClass().getSimpleName());
            return NOT_FETCHED.formatted(failed.getClass().getSimpleName());
        }
        if (page == null) {
            return NOT_STORED;
        }
        String text = page.text();
        int from = Math.min(offset, text.length());
        int room = ChatRoom.chars();
        if (room == 0) {
            return ChatRoom.FULL;
        }
        int to = (int) Math.min(text.length(), (long) from + room);
        String spaceKey = spaceOf(key);
        if (spaceKey == null) {
            spaceKey = page.spaceKey();
        }
        if (spaceKey == null) {
            spaceKey = live.spaceOf(key);
        }
        if (spaceKey == null) {
            // Ohne Space lässt sich die Seite nicht freigeben; dann geht ihr Text nicht hinaus.
            throw new ReleaseFailed(new IllegalStateException("Seite ohne Space"));
        }
        release(spaceKey, key);
        int number = sources.cite(new ChatKnowledge.Excerpt(page.title(), key.sourceId(), page.location(),
                ChatKnowledge.excerpt(text, ChatKnowledge.MAX_HIT_CHARS), key.pageId()), key);
        StringBuilder b = new StringBuilder();
        b.append("Quelle [").append(number).append("] ").append(oneLine(page.title())).append('\n');
        b.append("Zeichen ").append(from).append(" bis ").append(to).append(" von ").append(text.length())
                .append('\n');
        if (page.location() != null) {
            b.append("Ort: ").append(page.location()).append('\n');
        }
        b.append('\n').append(text, from, to);
        if (to < text.length()) {
            b.append("\n\n(Fortsetzung mit ").append(READ).append(", versatz ").append(to).append(')');
        }
        return b.toString();
    }

    /** Titel, Ort und Lesetext aus dem Cache; {@code null}, wenn die Seite nicht lokal liegt. */
    Page page(PageKey key) {
        return localPage(workspace, key);
    }

    /**
     * Die Seite zum Lesen: aus dem Cache oder, im Bereich, aber nicht lokal (Angelo 26.11.2026: Auf den gewählten
     * Bereich hat der Chat Zugriff), wie beim Öffnen in der Leseansicht von der Quelle geholt; sie liegt danach lokal
     * und wird wie jede andere Seite vor dem Senden freigegeben. {@code null}, wenn sie danach nicht lokal liegt; wirft,
     * wenn der Abruf scheitert. Auch die Recherche der Buchfunktion liest so (app.research).
     */
    public static Page fetchedPage(Workspace workspace, PageKey key) {
        Page page = localPage(workspace, key);
        if (page != null) {
            return page;
        }
        workspace.page(key.sourceId(), key.pageId(), false);
        return localPage(workspace, key);
    }

    /**
     * Titel, Ort und Lesetext aus dem Cache, wie der Chat sie liest; {@code null}, wenn die Seite nicht lokal liegt.
     * Auch die Recherche der Buchfunktion liest so (app.research).
     */
    public static Page localPage(Workspace workspace, PageKey key) {
        try {
            DeliveredPage page = workspace.cachedPage(key.sourceId(), key.pageId()).orElse(null);
            if (page == null) {
                return null;
            }
            PageText text = workspace.pageText(page.snapshot());
            PageMetadata metadata = page.header().metadata();
            return new Page(metadata.title() == null ? key.pageId() : metadata.title(),
                    metadata.url() == null ? null : metadata.url().toString(),
                    text == null ? "" : text.text().strip(), metadata.partitionKey());
        } catch (RuntimeException notLocal) {
            return null;
        }
    }

    /** @param spaceKey Space laut Kopfdaten (Partition), {@code null}, wenn die Quelle keine nennt */
    public record Page(String title, String location, String text, String spaceKey) {
    }

    // ------------------------------------------------------------------ Liste

    String list(String node, int from) {
        if (scope == null) {
            return "Es ist kein Bereich gewählt.";
        }
        if (nodes != null && (node.isBlank() || node.strip().startsWith(FOLDER))) {
            return listFolder(node.isBlank() ? null : node.strip().substring(FOLDER.length()), from);
        }
        PageKey parent = null;
        if (!node.isBlank()) {
            parent = parse(node);
            if (!allows(parent)) {
                return UNAVAILABLE;
            }
        }
        Listing listing = entries(parent, null);
        if (!live.isEmpty() && (parent == null || live.contains(parent))) {
            listing = withLive(listing, parent);
        }
        return sheet(listing, parent == null ? "Startseiten des Bereichs" : "Unterseiten",
                parent == null ? "Der Bereich enthält keine Seiten." : "Diese Seite hat keine Unterseiten.", from);
    }

    /**
     * Die Liste aus dem Katalog, ergänzt um das, was die Navigation unter den Wurzeln außerhalb des Katalogs zeigt
     * ({@link LiveArea}), wie der Baum links. Lehnt die Quelle ab, steht das in der Liste, statt den Bereich als leer
     * auszugeben.
     */
    private Listing withLive(Listing listing, PageKey parent) {
        Map<String, Entry> entries = new java.util.LinkedHashMap<>();
        listing.entries().forEach(entry -> entries.put(entry.ref(), entry));
        boolean failed = listing.failed();
        try {
            for (LiveArea.Node node : live.children(parent, () -> cancelled)) {
                String ref = ref(node.key());
                Entry known = entries.get(ref);
                if (known == null) {
                    entries.put(ref, new Entry(ref, node.title(), Boolean.TRUE.equals(node.children()) ? -1 : 1,
                            node.folder()));
                } else if (known.title() == null) {
                    // Der Katalog kennt die Seite ohne Titel in der Seitenliste; die Quelle nennt ihn.
                    entries.put(ref, new Entry(ref, node.title(), known.size(), known.folder() || node.folder()));
                }
            }
        } catch (RuntimeException e) {
            diagnosis.accept("Chat-Werkzeug " + LIST + ": Navigation gescheitert: " + e.getClass().getSimpleName());
            failed = true;
        }
        return new Listing(new ArrayList<>(entries.values()), listing.unreadable(), failed);
    }

    /**
     * Ein Ordner der Sammlung (ADR 0034 §5): seine Unterordner als virtuelle Knoten, danach je direkter Favorit die Seite
     * samt Unterseiten bzw. die Startseiten seines Space oder seiner Quelle; nur Seiten des Bereichs.
     */
    private String listFolder(String folderId, int from) {
        CollectionNodes.Content content = nodes.content(folderId);
        if (content == null) {
            return UNAVAILABLE;
        }
        List<Entry> entries = new ArrayList<>();
        for (CollectionNodes.Folder folder : content.folders()) {
            entries.add(new Entry(FOLDER + folder.id(), folder.name(), 0, true));
        }
        boolean unreadable = false;
        Set<String> seen = new java.util.HashSet<>();
        for (Selection.Root root : content.roots()) {
            Listing listing = entries(null, root);
            unreadable |= listing.unreadable();
            for (Entry entry : listing.entries()) {
                if (seen.add(entry.ref())) {
                    entries.add(entry);
                }
            }
        }
        return sheet(new Listing(entries, unreadable), folderId == null ? "Favoriten des Bereichs"
                : "Inhalt des Ordners", "Dieser Ordner enthält keine Favoriten.", from);
    }

    /**
     * Die Einträge unter {@code parent} bzw. ohne ihn die Startseiten des Bereichs; mit {@code within} nur die eines
     * Favoriten: eine Seite selbst, sonst die Startseiten seines Space oder seiner Quelle.
     */
    private Listing entries(PageKey parent, Selection.Root within) {
        List<Entry> entries = new ArrayList<>();
        boolean unreadable = false;
        for (var space : scope.spaces()) {
            if (parent != null && !space.sourceId().equals(parent.sourceId())
                    || within != null && !covers(within, space.sourceId(), space.spaceKey())) {
                continue;
            }
            Map<String, PageHolding> pages = holdings(space.sourceId(), space.spaceKey());
            if (pages == null) {
                unreadable = true;
                continue;
            }
            if (parent != null && !pages.containsKey(parent.pageId())) {
                continue; // der Knoten liegt in einem anderen Space dieser Quelle
            }
            for (PageHolding page : pages.values()) {
                if (page.state() == HoldingState.REMOVED || !scope.contains(space.sourceId(), page.pageId())) {
                    continue;
                }
                boolean match = within != null && within.pageId() != null ? within.pageId().equals(page.pageId())
                        : parent == null
                        ? page.parentPageId() == null || !scope.contains(space.sourceId(), page.parentPageId())
                        : parent.pageId().equals(page.parentPageId());
                if (match) {
                    String title = titles(space.sourceId(), space.spaceKey()).get(page.pageId());
                    entries.add(new Entry(ref(new PageKey(space.sourceId(), page.pageId())), title,
                            page.subtree().pages(), false));
                }
            }
            if (parent != null) {
                break;
            }
        }
        return new Listing(entries, unreadable);
    }

    /** Ob der Space unter der Wurzel liegt: eine Quelle deckt alle ihre Spaces ab. */
    private static boolean covers(Selection.Root root, String sourceId, String spaceKey) {
        return root.sourceId().equals(sourceId) && (root.spaceKey() == null || root.spaceKey().equals(spaceKey));
    }

    /**
     * Ein Abschnitt der Liste in fester Reihenfolge (Ordner zuerst, dann nach Titel wie im Baum, dann nach Kennung): ab
     * Eintrag {@code from} so viele Einträge, wie in die Anfrage passen ({@link ChatRoom}), mindestens einer.
     */
    private static String sheet(Listing listing, String heading, String empty, int from) {
        List<Entry> entries = new ArrayList<>(listing.entries());
        entries.sort(Comparator.comparing((Entry entry) -> !entry.folder())
                .thenComparing(entry -> entry.title() == null ? "\uffff"
                        : entry.title().toLowerCase(java.util.Locale.ROOT)).thenComparing(Entry::ref));
        if (entries.isEmpty()) {
            return listing.failed() ? NAVIGATION_FAILED : listing.unreadable() ? TOO_LARGE : empty;
        }
        if (from >= entries.size()) {
            return "Ab Eintrag " + from + " gibt es nichts; es gibt " + entries.size() + " Einträge (ab 0 gezählt).";
        }
        int room = ChatRoom.chars();
        if (room == 0) {
            return ChatRoom.FULL;
        }
        List<String> lines = new ArrayList<>();
        long used = heading.length() + 64L;
        int to = from;
        while (to < entries.size()) {
            Entry entry = entries.get(to);
            StringBuilder line = new StringBuilder("- ").append(entry.ref()).append(" | ")
                    .append(entry.title() == null ? "(nicht lokal gespeichert)" : oneLine(entry.title()));
            if (entry.folder()) {
                line.append(" | Ordner");
            } else if (entry.size() > 1) {
                line.append(" | hat Unterseiten (").append(entry.size() - 1).append(')');
            } else if (entry.size() < 0) {
                line.append(" | hat Unterseiten");
            }
            line.append('\n');
            if (!lines.isEmpty() && used + line.length() > room) {
                break;
            }
            used += line.length();
            lines.add(line.toString());
            to++;
        }
        StringBuilder b = new StringBuilder();
        b.append(heading).append(", Einträge ").append(from + 1).append(" bis ").append(to).append(" von ")
                .append(entries.size()).append('\n');
        lines.forEach(b::append);
        if (to < entries.size()) {
            b.append("(weiter mit ab ").append(to).append(")\n");
        }
        if (listing.failed()) {
            b.append("Hinweis: Die Quelle lieferte nicht alle Einträge; nutze auch ").append(SEARCH).append(".\n");
        }
        if (listing.unreadable()) {
            b.append("Hinweis: Nicht alle Spaces ließen sich auflisten (zu groß); nutze ").append(SEARCH).append('.');
        }
        return b.toString().stripTrailing();
    }

    /** @param failed die Quelle lehnte die Liste eines Knotens ab (nur Navigation) */
    private record Listing(List<Entry> entries, boolean unreadable, boolean failed) {

        Listing(List<Entry> entries, boolean unreadable) {
            this(entries, unreadable, false);
        }
    }

    /** Der Space einer Seite des Bereichs aus dem Bestand; {@code null}, wenn kein Space der Auswahl sie kennt. */
    String spaceOf(PageKey key) {
        if (scope == null) {
            return null;
        }
        for (var space : scope.spaces()) {
            Map<String, PageHolding> pages = space.sourceId().equals(key.sourceId())
                    ? holdings(space.sourceId(), space.spaceKey()) : null;
            if (pages != null && pages.containsKey(key.pageId())) {
                return space.spaceKey();
            }
        }
        return null;
    }

    /**
     * Der Bestand eines Space, je Turn einmal; {@code null}, wenn er sich nicht laden ließ (etwa zu groß für die
     * Bestandsübersicht): Dann sagt die Liste das, statt den Space als leer zu melden (Codex-Befund zu #1003).
     */
    private Map<String, PageHolding> holdings(String sourceId, String spaceKey) {
        Map<String, PageHolding> loaded = holdings.computeIfAbsent(sourceId + "\u0000" + spaceKey, key -> {
            try {
                return reader.pages(sourceId, spaceKey);
            } catch (RuntimeException gone) {
                return UNREADABLE;
            }
        });
        return loaded == UNREADABLE ? null : loaded;
    }

    private static final Map<String, PageHolding> UNREADABLE = java.util.Collections.unmodifiableMap(
            new java.util.HashMap<>());
    static final String NAVIGATION_FAILED = "Die Quelle lieferte die Einträge dieses Bereichs gerade nicht; nutze "
            + SEARCH + " oder sage dem Benutzer, dass die Quelle nicht erreichbar war. Erfinde keine Inhalte.";
    static final String NO_TERMS = "Zu diesem Bereich ist noch nichts lokal ausgewertet, der Wissensgraph kennt dort "
            + "keine Fachbegriffe; suche mit " + SEARCH + " und lies die Treffer mit " + READ + ".";
    static final String TOO_LARGE = "Der Bestand dieses Bereichs lässt sich nicht als Liste zeigen (zu groß); nutze "
            + SEARCH + ".";

    /** Die Titel eines Space aus den Kopfdaten der Seitenliste des Cache, je Turn einmal, ohne Lesetext. */
    private Map<String, String> titles(String sourceId, String spaceKey) {
        return titles.computeIfAbsent(sourceId + "\u0000" + spaceKey, key -> {
            Map<String, String> found = new java.util.HashMap<>();
            try {
                String cursor = null;
                do {
                    var listing = workspace.cachedPages(sourceId, spaceKey, cursor, 1000);
                    for (var listed : listing.pages()) {
                        PageMetadata metadata = listed.header().metadata();
                        found.put(listed.header().key().pageId(), metadata.title() == null
                                ? listed.header().key().pageId() : metadata.title());
                    }
                    cursor = listing.nextCursor();
                } while (cursor != null && !cancelled);
            } catch (RuntimeException gone) {
                // ohne Liste bleiben die Einträge ohne Titel
            }
            return found;
        });
    }

    /** Ein Eintrag der Liste: eine Seite ({@code ref} wie {@link #ref}) oder ein Ordner ({@code ordner/<id>}). */
    private record Entry(String ref, String title, int size, boolean folder) {
    }

    // ------------------------------------------------------------------ Fachbegriff (W7)

    /** Obergrenze für die Eingabe; längeres ist kein Fachbegriff. */
    static final int MAX_TERM_CHARS = 200;

    /**
     * Was der Wissensgraph im Bereich über {@code query} weiß. Der Bereich ist die Auswahl beim Senden, je Aufruf;
     * die Belegseiten meldet der Kern vor der Antwort an {@link Sources#delivered}. Wirft diese Meldung, gibt es keine
     * Antwort (ADR 0033 Punkt 8).
     */
    String term(String query) {
        if (scope == null) {
            return "Es ist kein Bereich gewählt.";
        }
        if (scope.pages().isEmpty()) {
            // Der Wissensgraph kennt nur lokal ausgewertete Seiten; ein Bereich nur aus der Navigation hat dort keine.
            return NO_TERMS;
        }
        String text = query.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return "Bitte einen Fachbegriff angeben.";
        }
        text = cut(text, MAX_TERM_CHARS);
        TermKnowledge.Answer answer = terms.answer(text, scope.pages(), (spaceKey, key) -> {
            String space = spaceKey != null ? spaceKey : spaceOf(key);
            if (space == null) {
                // Ohne Space lässt sich die Seite nicht freigeben; dann darf ihr Inhalt nicht hinaus.
                throw new ReleaseFailed(new IllegalStateException("Belegseite ohne Space"));
            }
            release(space, key);
        }, () -> cancelled);
        return render(text, answer);
    }

    /** Höchstens {@code max} Zeichen, ohne ein Surrogatpaar zu zerteilen. */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    /**
     * Die Antwort als Text für das Modell: knapp, je Aussage eine Zeile, Belegseiten mit Kennung. Fachbegriff, Synonyme
     * und ähnliche Fachbegriffe entscheidet der Core nur nach dem Bereich und der KI-Freigabe (ADR 0032, Nachtrag
     * Abnahme W7); hier wird nichts nachgefiltert.
     */
    static String render(String query, TermKnowledge.Answer answer) {
        StringBuilder b = new StringBuilder();
        switch (answer.outcome()) {
            case EMPTY_QUERY -> {
                return "Bitte einen Fachbegriff angeben.";
            }
            case EMPTY_SCOPE -> {
                return "Der Bereich enthält keine Seiten.";
            }
            case NOT_A_TERM -> {
                return noTerm(query, answer.candidates(), answer.limits());
            }
            case FOUND -> {
                // weiter unten
            }
        }
        List<TermKnowledge.Term> terms = answer.terms();
        b.append("Fachbegriff: ").append(oneLine(terms.getFirst().term())).append('\n');
        if (terms.size() > 1) {
            b.append("Synonyme: ").append(String.join(", ", terms.subList(1, terms.size()).stream()
                    .map(term -> oneLine(term.term())).toList())).append('\n');
        }
        int statements = terms.stream().mapToInt(term -> term.statements().size()).sum();
        TermKnowledge.Limits limits = answer.limits();
        if (statements == 0) {
            b.append(read(limits) == null
                    ? "Im gewählten Bereich gibt es keine Aussagen zu diesem Begriff; nutze " + SEARCH + ".\n"
                    : "In den gelesenen Seiten gibt es keine Aussagen zu diesem Begriff (" + read(limits)
                    + "); nutze " + SEARCH + ".\n");
        } else {
            b.append("Aussagen (Stufe: geprüft, einig, ungeprüft, abweichend):\n");
            for (TermKnowledge.Term term : terms) {
                for (TermKnowledge.Statement statement : term.statements()) {
                    b.append("- ").append(statement(statement)).append('\n');
                }
            }
        }
        if (!answer.neighbours().isEmpty()) {
            b.append("Verwandte Begriffe: ").append(String.join(", ", answer.neighbours().stream()
                    .map(ChatTools::oneLine).toList())).append('\n');
        }
        String cut = limits(limits);
        if (!cut.isEmpty()) {
            b.append("Unvollständig: ").append(cut).append('\n');
        }
        if (statements > 0) {
            b.append("Lies eine Belegseite mit ").append(READ).append(", bevor du dich auf sie berufst.");
        }
        return b.toString().stripTrailing();
    }

    /** Was bei gekapptem Bereich gelesen wurde; {@code null}, wenn alle Seiten des Bereichs gelesen wurden. */
    private static String read(TermKnowledge.Limits limits) {
        return limits.pagesRead() < limits.pagesInScope() ? "gelesen wurden " + limits.pagesRead() + " von "
                + limits.pagesInScope() + " Seiten des Bereichs, die übrigen sind ungelesen" : null;
    }

    /** Kein Fachbegriff im Bereich, mit ähnlichen aus dem Bereich und dem Hinweis, was ungelesen blieb. */
    private static String noTerm(String query, List<String> candidates, TermKnowledge.Limits limits) {
        StringBuilder b = new StringBuilder("„").append(oneLine(query))
                .append("“ ist im gewählten Bereich kein bestätigter Fachbegriff");
        if (read(limits) != null) {
            b.append(" (").append(read(limits)).append(')');
        }
        b.append('.');
        if (!candidates.isEmpty()) {
            b.append(" Ähnliche Fachbegriffe: ").append(String.join(", ", candidates.stream()
                    .map(ChatTools::oneLine).toList())).append('.');
        }
        return b.append(" Sonst nutze ").append(SEARCH).append('.').toString();
    }

    private static String statement(TermKnowledge.Statement statement) {
        StringBuilder b = new StringBuilder();
        b.append(oneLine(statement.subject())).append(" —");
        switch (statement.modality()) {
            case MUSS -> b.append("muss ");
            case SOLL -> b.append("soll ");
            case KANN -> b.append("kann ");
            case DARF -> b.append("darf ");
            default -> { }
        }
        if (statement.negation() == ParsedStatements.Negation.NEGATED) {
            b.append("nicht ");
        }
        b.append(oneLine(statement.verb())).append("→ ").append(oneLine(statement.object()));
        b.append(" [").append(trust(statement.trust()));
        switch (statement.temporality()) {
            case GEPLANT -> b.append(", geplant");
            case FRUEHER -> b.append(", früher");
            case VERWORFEN -> b.append(", verworfen");
            case UNKNOWN -> b.append(", Zeitbezug offen");
            case GILT -> { }
        }
        // Nicht bestimmte Merkmale ausdrücklich nennen, sonst läse das Modell eine sichere, bejahte Aussage.
        if (statement.negation() == ParsedStatements.Negation.UNKNOWN) {
            b.append(", Verneinung offen");
        }
        if (statement.modality() == ParsedStatements.Modality.UNKNOWN) {
            b.append(", Verbindlichkeit offen");
        }
        b.append(", ").append(statement.evidence()).append(statement.evidence() == 1 ? " Beleg" : " Belege")
                .append(']');
        if (!statement.pages().isEmpty()) {
            b.append(" Seiten: ").append(String.join("; ", statement.pages().stream()
                    .map(page -> ref(page.key()) + " (" + oneLine(page.title() == null ? page.key().pageId()
                            : page.title()) + ")").toList()));
            if (statement.omittedPages() > 0) {
                b.append(" und ").append(statement.omittedPages()).append(" weitere");
            }
        }
        return b.toString();
    }

    private static String trust(DomainTermGraph.Trust trust) {
        return switch (trust) {
            case GEPRUEFT -> "geprüft";
            case EINIG -> "einig";
            case UNGEPRUEFT -> "ungeprüft";
            case ABWEICHEND -> "abweichend";
        };
    }

    /** Was gekappt wurde, als kurze Liste; leer, wenn die Antwort vollständig ist. */
    static String limits(TermKnowledge.Limits limits) {
        if (!limits.cut()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (limits.pagesRead() < limits.pagesInScope()) {
            parts.add(limits.pagesRead() + " von " + limits.pagesInScope() + " Seiten des Bereichs gelesen");
        }
        if (limits.omittedTerms() > 0) {
            parts.add(limits.omittedTerms() + (limits.omittedTerms() == 1 ? " weiteres Synonym" : " weitere Synonyme"));
        }
        if (limits.omittedStatements() > 0) {
            parts.add(limits.omittedStatements() + " weitere Aussagen");
        }
        if (limits.omittedNeighbours() > 0) {
            parts.add(limits.omittedNeighbours() + " weitere verwandte Begriffe");
        }
        if (limits.omittedEvidencePages() > 0) {
            parts.add(limits.omittedEvidencePages() + (limits.omittedEvidencePages() == 1 ? " weitere Belegseite"
                    : " weitere Belegseiten"));
        }
        if (limits.graphTruncated()) {
            parts.add("der Wissensgraph ist an seiner Obergrenze");
        }
        if (limits.skippedStale() > 0) {
            parts.add(limits.skippedStale() + " Aussagen veralteter Seiten ausgelassen");
        }
        return String.join("; ", parts);
    }

    // ------------------------------------------------------------------ Suche

    /**
     * Die Trefferliste: nur Kennung und Titel, nie ein Auszug aus dem Seitentext (Angelo 22.11.2026: „Die Suche zählt
     * dafür ja nicht“). Eine Suche trägt darum nichts auf die Liste freigegebener Seiten ein; den Text einer Seite holt
     * das Modell mit {@link #READ}, und erst dann kommt sie auf die Liste.
     */
    String search(String text) {
        if (scope == null) {
            return "Es ist kein Bereich gewählt.";
        }
        if (scope.pages().isEmpty() && live.isEmpty()) {
            return EMPTY_AREA;
        }
        String query = text.strip().replaceAll("\\s+", " ");
        if (query.isEmpty()) {
            return "Bitte Suchbegriffe angeben.";
        }
        query = clampQuery(query);
        KeywordSearch.Query search = areaQuery(query, scope, live).withGlossarySynonyms(glossarySynonyms);
        KeywordSearch.Results barResults = barResults(search);
        List<KeywordSearch.Hit> bar = barResults == null ? List.of() : barResults.ranked();
        List<KeywordSearch.Hit> hits = listed(bar, ranked(search, neighbors, false));
        int most = bar.size() + SEARCH_HITS;
        StringBuilder b = new StringBuilder();
        Set<PageKey> seen = new LinkedHashSet<>();
        for (KeywordSearch.Hit hit : hits) {
            PageKey key = new PageKey(hit.sourceId(), hit.pageId());
            if (!allows(key) || !seen.add(key)) {
                continue;
            }
            sources.found(key);
            b.append("- ").append(ref(key)).append(" | ").append(oneLine(hit.title() == null ? hit.pageId()
                    : hit.title()));
            b.append('\n');
            if (seen.size() >= most) {
                break;
            }
        }
        String note = localOnlyNote(barResults);
        String listing = seen.isEmpty() ? "Keine Treffer im gewählten Bereich." : b.toString().stripTrailing();
        return note == null ? listing : listing + "\n" + note;
    }

    /**
     * Welche Quellen nur in ihren indexierten Inhalten durchsucht wurden, weil sie keine Online-Suche haben
     * ({@code SourceState.Status.INDEXED}). Ohne diese Zeile hielt das Modell „Keine Treffer“ für „gibt es nicht“,
     * obwohl nicht indexierte Dokumente dieser Quellen gar nicht durchsucht waren. Der Benutzer muss erfahren, dass er
     * sie zuerst indexieren muss (Angelo 27.11.2026).
     */
    String localOnlyNote(KeywordSearch.Results results) {
        if (results == null) {
            return null;
        }
        List<String> titles = new ArrayList<>();
        for (KeywordSearch.SourceState state : results.sources()) {
            if (state.status() == KeywordSearch.SourceState.Status.INDEXED) {
                titles.add(sourceTitle(state.sourceId()));
            }
        }
        return titles.isEmpty() ? null : localOnlyNote(titles);
    }

    /** Die Zeile für {@link #localOnlyNote(KeywordSearch.Results)} aus den Titeln der Quellen. */
    static String localOnlyNote(List<String> titles) {
        // Derselbe Menüeintrag wie im Baum (Explorer.INDEX).
        return "Hinweis: " + String.join(", ", titles) + (titles.size() == 1 ? " kann" : " können")
                + " nicht online durchsucht werden; durchsucht wurden dort nur indexierte Inhalte. Was nicht indexiert "
                + "ist, findet die Suche nicht: Sage dem Benutzer, dass er die Quelle zuerst indexieren muss "
                + "(Rechtsklick auf die Quelle › " + INDEX_ACTION + "), statt zu behaupten, es gebe nichts dazu.";
    }

    private String sourceTitle(String sourceId) {
        try {
            for (var source : workspace.sources()) {
                if (source.id().equals(sourceId) && source.title() != null && !source.title().isBlank()) {
                    return source.title();
                }
            }
        } catch (RuntimeException e) {
            // Ohne Katalog bleibt die Kennung.
        }
        return sourceId;
    }

    /**
     * Dieselbe Schlagwortsuche wie die Suchleiste ({@code SearchView.run}: {@code keywordSearchIn} mit
     * {@link SearchView#LIMIT}) und dieselbe Liste ({@code results.ranked()}: Treffer aus dem Cache, dann die Funde der
     * Quelle selbst). Bis 24.11.2026 fragte der Chat nur die progressive Suche mit {@code Harvester.searchProfile()},
     * die die Suchleiste nur mit dem Haken „semantisch“ nimmt. Mit eingerichtetem Embedding-Dienst ist dieses Profil
     * semantisch ({@code SemanticVariantSearch.profile}): Treffer kommen dann nur aus der Vektorsuche, nie aus der
     * Schlagwortsuche über Titel und Volltext; ohne ist es die Schlagwortsuche, von der der Chat die ersten
     * {@value #SEARCH_HITS} nahm (unbewertet je Anfrage erst alle Cache-Treffer, dann die Funde der Quelle). Ein
     * Seitentitel, den die Suchleiste zeigte, kam so nicht in die Liste (Angelo 24.11.2026, „Team 10“). Scheitert die
     * Schlagwortsuche, bleibt die progressive Suche.
     */
    private KeywordSearch.Results barResults(KeywordSearch.Query search) {
        if (cancelled) {
            return null;
        }
        try {
            return workspace.keywordSearchIn(java.util.UUID.randomUUID().toString(), search, null, SearchView.LIMIT,
                    null);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Schlagwortsuche nicht möglich ({0})", e.getClass().getName());
            return null;
        }
    }

    /** Erst die Liste der Suchleiste in ihrer Reihenfolge, dann die übrigen Treffer der progressiven Suche. */
    static List<KeywordSearch.Hit> listed(List<KeywordSearch.Hit> bar, List<KeywordSearch.Hit> progressive) {
        Set<PageKey> seen = new java.util.HashSet<>();
        List<KeywordSearch.Hit> all = new ArrayList<>();
        for (List<KeywordSearch.Hit> part : List.of(bar, progressive)) {
            for (KeywordSearch.Hit hit : part) {
                if (seen.add(new PageKey(hit.sourceId(), hit.pageId()))) {
                    all.add(hit);
                }
            }
        }
        return all;
    }

    /**
     * Die Anfrage für einen Bereich im selben Umfang wie die Suchleiste mit derselben Auswahl: Quelle(n) und Spaces des
     * Bereichs, dazu seine Seiten. Ohne die Spaces fragte eine Quelle mit eigener Suche (Confluence) das ganze Wiki,
     * lieferte nur ihre ersten Treffer, und alle außerhalb des Bereichs fielen danach weg; eine Seite im Bereich, die
     * die Suchleiste im Space als Erstes findet, kam so nie an (Angelo 24.11.2026, „Team 10“). Kennt die Auswahl den
     * Space einer ihrer Quellen nicht, bleibt es ohne Space-Filter, damit keine Seite verloren geht.
     */
    static KeywordSearch.Query areaQuery(String text, PageSelection scope) {
        return areaQuery(text, scope, LiveArea.NONE);
    }

    /**
     * Wie oben, wo der Katalog den Bereich nicht vollständig kennt ({@link LiveArea}): Die Anfrage sucht in den Quellen
     * und Spaces der Wurzeln über die bekannten Seiten und die Teilbaum-Grenze ({@code KeywordSearch.Query#bounded}).
     * Die Grenze greift im Core vor Katalogisieren, Laden, Volltextindex und Nachbarn, nicht erst an der Liste.
     */
    static KeywordSearch.Query areaQuery(String text, PageSelection scope, LiveArea area) {
        if (!area.isEmpty()) {
            List<Selection.Root> live = area.roots();
            Set<String> sources = new java.util.TreeSet<>(scope.pages().sourceIds());
            Set<String> spaces = new java.util.TreeSet<>();
            boolean wholeSource = false;
            for (com.aresstack.spaceharvester.application.holdings.SpaceSummary space : scope.spaces()) {
                if (sources.contains(space.sourceId())) {
                    spaces.add(space.spaceKey());
                }
            }
            for (Selection.Root root : live) {
                sources.add(root.sourceId());
                if (root.spaceKey() == null) {
                    wholeSource = true;
                } else {
                    spaces.add(root.spaceKey());
                }
            }
            if (wholeSource) {
                spaces.clear();
            }
            String single = sources.size() == 1 ? sources.iterator().next() : null;
            return new KeywordSearch.Query(text, single, single == null ? sources : Set.of(), spaces)
                    .bounded(scope.pages(), area);
        }
        Set<String> sources = scope.pages().sourceIds();
        Set<String> spaces = new java.util.TreeSet<>();
        Set<String> withSpace = new java.util.HashSet<>();
        for (com.aresstack.spaceharvester.application.holdings.SpaceSummary space : scope.spaces()) {
            if (sources.contains(space.sourceId())) {
                spaces.add(space.spaceKey());
                withSpace.add(space.sourceId());
            }
        }
        if (!withSpace.containsAll(sources)) {
            spaces.clear();
        }
        String single = sources.size() == 1 ? sources.iterator().next() : null;
        return new KeywordSearch.Query(text, single, single == null ? sources : Set.of(), spaces)
                .within(scope.pages());
    }

    // ------------------------------------------------------------------ Interne Suche

    /**
     * Kürzt einen Suchtext auf das, was die Suche annimmt ({@value KeywordSearch#MAX_TEXT_LENGTH} Zeichen), an einer
     * Wortgrenze; sonst lehnte sie die ganze Frage ab (Codex-Befund zu #1260).
     */
    static String clampQuery(String query) {
        if (query.length() <= KeywordSearch.MAX_TEXT_LENGTH) {
            return query;
        }
        String cut = query.substring(0, KeywordSearch.MAX_TEXT_LENGTH);
        int space = cut.lastIndexOf(' ');
        return (space > KeywordSearch.MAX_TEXT_LENGTH / 2 ? cut.substring(0, space) : cut).strip();
    }

    private StoredProgramChatTools storedProgramTools() {
        return new StoredProgramChatTools(workspace, explicitAppSources, () -> cancelled,
                (spaceKey, key) -> {
                    release(spaceKey, key);
                    granted.add(key);
                });
    }

    /**
     * {@value #APP_SEARCH}: dieselbe globale Suche wie die Suchleiste, über die freigegebenen Wissensquellen, ohne
     * Vorab-Entscheidung, ob gesucht wird. Treffer werden lesbar ({@link #granted}); Text geht erst mit {@value #READ}
     * an das Modell und wird dort freigegeben.
     */
    String appSearch(JsonNode arguments) {
        if (appSources == null) {
            return "Unbekanntes Werkzeug.";
        }
        String query = arguments.path("text").asString("").strip().replaceAll("\\s+", " ");
        if (query.isEmpty()) {
            return "Bitte Suchbegriffe angeben.";
        }
        query = clampQuery(query);
        Set<String> wanted = strings(arguments.path("quellen"));
        Set<String> allowed = wanted.isEmpty() ? appSources.get() : explicitAppSources.get();
        Set<String> searched = new java.util.TreeSet<>(allowed);
        if (!wanted.isEmpty()) {
            searched.retainAll(wanted);
        }
        if (searched.isEmpty()) {
            return allowed.isEmpty() ? "Keine Wissensquelle ist für die KI freigegeben."
                    : "Keine der genannten Wissensquellen ist für die KI freigegeben; ohne quellen sucht es in allen.";
        }
        Set<com.aresstack.spaceharvester.application.query.DocumentKind> kinds = new LinkedHashSet<>();
        for (String name : strings(arguments.path("arten"))) {
            try {
                kinds.add(com.aresstack.spaceharvester.application.query.DocumentKind.valueOf(
                        name.strip().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                // Unbekannte Arten fallen weg; die Suche läuft mit den übrigen.
            }
        }
        String single = searched.size() == 1 ? searched.iterator().next() : null;
        KeywordSearch.Query search = new KeywordSearch.Query(query, single, single == null ? searched : Set.of(),
                Set.of()).ofKinds(kinds).withGlossarySynonyms(arguments.path("synonyme").asBoolean(false));
        KeywordSearch.Results barResults = barResults(search);
        List<KeywordSearch.Hit> bar = barResults == null ? List.of() : barResults.ranked();
        List<KeywordSearch.Hit> hits = listed(bar, ranked(search, arguments.path("nachbarseiten").asBoolean(false),
                arguments.path("fachbegriffe").asBoolean(false)));
        int most = bar.size() + SEARCH_HITS;
        StringBuilder b = new StringBuilder();
        Set<PageKey> seen = new LinkedHashSet<>();
        for (KeywordSearch.Hit hit : hits) {
            PageKey key = new PageKey(hit.sourceId(), hit.pageId());
            if (!searched.contains(hit.sourceId()) || !seen.add(key)) {
                continue;
            }
            granted.add(key);
            sources.found(key);
            b.append("- ").append(ref(key)).append(" | ").append(oneLine(hit.title() == null ? hit.pageId()
                    : hit.title())).append(" | Quelle ").append(hit.sourceId()).append('\n');
            if (seen.size() >= most) {
                break;
            }
        }
        String note = localOnlyNote(barResults);
        String listing = seen.isEmpty() ? "Keine Treffer in den Wissensquellen. Versuche andere Begriffe."
                : b.toString().stripTrailing();
        return note == null ? listing : listing + "\n" + note;
    }

    private static Set<String> strings(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asString("").strip();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        } else if (node != null && node.isString() && !node.asString("").isBlank()) {
            values.add(node.asString("").strip());
        }
        return values;
    }

    /** Läuft eine progressive Suche bis zum Ende oder zur Frist und gibt die Treffer nach Rang. */
    private List<KeywordSearch.Hit> ranked(KeywordSearch.Query search, boolean neighbors, boolean technicalTerms) {
        AtomicReference<List<ProgressiveResult.RankedHit>> latest = new AtomicReference<>(List.of());
        ProgressiveSearch running = workspace.progressiveSearch(search, new ProgressiveSearchListener() {
            @Override
            public void ranked(List<ProgressiveResult.RankedHit> ranked) {
                latest.set(ranked);
            }
        }, neighbors, technicalTerms);
        this.search = running;
        if (cancelled) {
            running.cancel();
        }
        List<ProgressiveResult.RankedHit> ranked;
        try {
            ranked = running.result().get(SEARCH_SECONDS, TimeUnit.SECONDS).hits();
        } catch (TimeoutException slow) {
            running.cancel();
            ranked = latest.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.cancel();
            ranked = latest.get();
        } catch (ExecutionException | CancellationException failed) {
            ranked = latest.get();
        } finally {
            this.search = null;
        }
        return ranked.stream().map(ProgressiveResult.RankedHit::hit).toList();
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }
}
