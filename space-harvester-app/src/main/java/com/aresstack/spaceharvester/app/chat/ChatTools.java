package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.holdings.HoldingState;
import com.aresstack.spaceharvester.application.holdings.PageHolding;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
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
 * nur aus dem Cache, nie bei der Quelle.
 *
 * <ul>
 *   <li>{@value #LIST}: die Kinder eines Knotens (ohne Angabe die Startseiten des Bereichs), nach Titel, blätternd,
 *   mit Kennzeichen „hat Unterseiten“, wie der Baum links; Titel aus den Kopfdaten, nie aus dem Lesetext.</li>
 *   <li>{@value #READ}: der Lesetext einer Seite in Stücken von {@value #READ_CHARS} Zeichen mit Versatz.</li>
 *   <li>{@value #SEARCH}: dieselbe Suche wie die Suche mit Auswahl links, nie über den Bereich hinaus.</li>
 *   <li>{@value #TERM}: was der Wissensgraph im Bereich über einen bestätigten Fachbegriff weiß (Paket W7, auf
 *   {@link TermKnowledge#answer}): Aussagen mit Stufe und Belegseiten, Synonyme, Nachbarbegriffe, und was gekappt
 *   wurde. Bereich und Obergrenzen erzwingt der Kern; jede Belegseite, deren Aussagen geliefert werden, geht vor der
 *   Antwort an {@link Sources#delivered} (ADR 0033 Punkt 8).</li>
 * </ul>
 *
 * <p>Seiten werden über eine Kennung {@code Quelle/Seite} angesprochen. Was das Modell gelesen hat, wird als
 * nummerierte Quelle gemeldet, damit es mit [n] zitieren kann.
 */
final class ChatTools {

    static final String LIST = "seiten_auflisten";
    static final String READ = "seite_lesen";
    static final String SEARCH = "im_bereich_suchen";
    static final String TERM = "begriff_nachschlagen";

    /** So viele Einträge je Blatt der Liste. */
    static final int PAGE_SIZE = 100;
    /** So viele Zeichen liefert ein Lesen höchstens; der Rest folgt mit Versatz. */
    static final int READ_CHARS = 12_000;
    /** So viele Treffer liefert die Suche höchstens. */
    static final int SEARCH_HITS = 10;
    /** Längstens so lange wartet ein Suchaufruf; danach zählen die bis dahin bewerteten Treffer. */
    static final long SEARCH_SECONDS = 60;

    /** Die eine Antwort für fremd, unbekannt, ohne Recht. */
    static final String UNAVAILABLE = "Diese Seite ist im gewählten Bereich nicht verfügbar.";
    static final String NOT_STORED = "Diese Seite liegt im gewählten Bereich, ist aber nicht lokal gespeichert.";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final System.Logger LOG = System.getLogger(ChatTools.class.getName());

    /** Was das Modell gelesen hat, als nummerierte Quelle, und was an das Modell ging (KI-Freigabe, ADR 0033). */
    interface Sources {
        /** Nimmt die Seite auf (oder findet sie wieder) und gibt ihre Nummer. */
        int cite(ChatKnowledge.Excerpt excerpt, PageKey key);

        /** Text oder Auszug der Seite ging eben an das Modell: Sie kommt auf die Liste freigegebener Seiten. */
        void delivered(String spaceKey, PageKey key);
    }

    private final Workspace workspace;
    private final PageSelection scope;
    /** Seiten außerhalb des Bereichs, die der Benutzer ausdrücklich erlaubt hat (vorbereitet, heute leer). */
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
        this.granted = Set.copyOf(granted);
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

    /** Die Definitionen für die Anfrage. */
    static List<AiChat.Tool> definitions() {
        return List.of(
                new AiChat.Tool(SEARCH, "Sucht im gewählten Bereich nach Stichworten und liefert die besten Treffer "
                        + "mit Kennung, Titel und kurzem Auszug. Den ganzen Text danach mit " + READ + " holen.",
                        "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\","
                                + "\"description\":\"Suchbegriffe\"}},\"required\":[\"text\"]}"),
                new AiChat.Tool(LIST, "Listet die Seiten des gewählten Bereichs wie einen Baum: ohne knoten die "
                        + "Startseiten, mit knoten dessen Unterseiten. Je Aufruf höchstens " + PAGE_SIZE
                        + " Einträge; weitere mit blatt.",
                        "{\"type\":\"object\",\"properties\":{\"knoten\":{\"type\":\"string\","
                                + "\"description\":\"Kennung einer Seite; leer für die Startseiten\"},"
                                + "\"blatt\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"Blattnummer, "
                                + "ab 1\"}}}"),
                new AiChat.Tool(READ, "Liest den Text einer Seite des gewählten Bereichs, höchstens " + READ_CHARS
                        + " Zeichen je Aufruf; lange Seiten mit versatz weiterlesen. Liefert die Quellennummer, "
                        + "mit der du die Seite zitierst.",
                        "{\"type\":\"object\",\"properties\":{\"kennung\":{\"type\":\"string\"},"
                                + "\"versatz\":{\"type\":\"integer\",\"minimum\":0}},\"required\":[\"kennung\"]}"),
                new AiChat.Tool(TERM, "Schlägt einen Fachbegriff im gewählten Bereich nach: was der Wissensgraph "
                        + "über ihn weiß (Aussagen mit Verb, Vertrauensstufe und Belegseiten), seine Synonyme und "
                        + "verwandte Begriffe. Ist das Wort kein bestätigter Fachbegriff, nennt es ähnliche. Die "
                        + "Belegseiten vor dem Zitieren mit " + READ + " lesen.",
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
                case LIST -> list(arguments.path("knoten").asString(""), Math.max(1, arguments.path("blatt").asInt(1)));
                case READ -> read(arguments.path("kennung").asString(""), Math.max(0, arguments.path("versatz")
                        .asInt(0)));
                case TERM -> term(arguments.path("begriff").asString(""));
                default -> "Unbekanntes Werkzeug.";
            };
        } catch (CancellationException e) {
            return "Abgebrochen.";
        } catch (RuntimeException e) {
            // Grund in die Diagnose, ohne Argumente oder Inhalte (ADR 0033, Folgen).
            LOG.log(System.Logger.Level.WARNING, "Chat-Werkzeug " + call.name() + " gescheitert: "
                    + e.getClass().getSimpleName());
            return "Das Werkzeug ist gescheitert (" + e.getClass().getSimpleName() + ").";
        }
    }

    // ------------------------------------------------------------------ Bereich

    static String ref(PageKey key) {
        return key.sourceId() + "/" + key.pageId();
    }

    static PageKey parse(String ref) {
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
        return key != null && (scope != null && scope.contains(key) || granted.contains(key));
    }

    // ------------------------------------------------------------------ Lesen

    String read(String ref, int offset) {
        PageKey key = parse(ref);
        if (!allows(key)) {
            return UNAVAILABLE;
        }
        Page page = page(key);
        if (page == null) {
            return NOT_STORED;
        }
        String text = page.text();
        int from = Math.min(offset, text.length());
        int to = Math.min(text.length(), from + READ_CHARS);
        int number = sources.cite(new ChatKnowledge.Excerpt(page.title(), key.sourceId(), page.location(),
                ChatKnowledge.excerpt(text, ChatKnowledge.MAX_HIT_CHARS), key.pageId()), key);
        String spaceKey = spaceOf(key);
        if (spaceKey == null) {
            spaceKey = page.spaceKey();
        }
        if (spaceKey != null) {
            sources.delivered(spaceKey, key);
        }
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
    record Page(String title, String location, String text, String spaceKey) {
    }

    // ------------------------------------------------------------------ Liste

    String list(String node, int sheet) {
        if (scope == null) {
            return "Es ist kein Bereich gewählt.";
        }
        List<Entry> entries = new ArrayList<>();
        boolean unreadable = false;
        PageKey parent = null;
        if (!node.isBlank()) {
            parent = parse(node);
            if (!allows(parent)) {
                return UNAVAILABLE;
            }
        }
        for (var space : scope.spaces()) {
            if (parent != null && !space.sourceId().equals(parent.sourceId())) {
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
                boolean match = parent == null
                        ? page.parentPageId() == null || !scope.contains(space.sourceId(), page.parentPageId())
                        : parent.pageId().equals(page.parentPageId());
                if (match) {
                    String title = titles(space.sourceId(), space.spaceKey()).get(page.pageId());
                    entries.add(new Entry(new PageKey(space.sourceId(), page.pageId()), title,
                            page.subtree().pages()));
                }
            }
            if (parent != null) {
                break;
            }
        }
        // Feste Reihenfolge fürs Blättern: nach Titel wie im Baum, dann nach Kennung.
        entries.sort(Comparator.comparing((Entry entry) -> entry.title() == null ? "\uffff"
                : entry.title().toLowerCase(java.util.Locale.ROOT)).thenComparing(entry -> ref(entry.key())));
        int sheets = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (entries.isEmpty()) {
            return unreadable ? TOO_LARGE
                    : parent == null ? "Der Bereich enthält keine Seiten." : "Diese Seite hat keine Unterseiten.";
        }
        if (sheet > sheets) {
            return "Blatt " + sheet + " gibt es nicht; es gibt " + sheets + ".";
        }
        StringBuilder b = new StringBuilder();
        b.append(parent == null ? "Startseiten des Bereichs" : "Unterseiten").append(", Blatt ").append(sheet)
                .append(" von ").append(sheets).append(" (").append(entries.size()).append(" Einträge)\n");
        for (Entry entry : entries.subList((sheet - 1) * PAGE_SIZE, Math.min(entries.size(), sheet * PAGE_SIZE))) {
            b.append("- ").append(ref(entry.key())).append(" | ")
                    .append(entry.title() == null ? "(nicht lokal gespeichert)" : oneLine(entry.title()));
            if (entry.size() > 1) {
                b.append(" | hat Unterseiten (").append(entry.size() - 1).append(')');
            }
            b.append('\n');
        }
        if (sheet < sheets) {
            b.append("(weiter mit blatt ").append(sheet + 1).append(")\n");
        }
        if (unreadable) {
            b.append("Hinweis: Nicht alle Spaces ließen sich auflisten (zu groß); nutze ").append(SEARCH).append('.');
        }
        return b.toString().stripTrailing();
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

    private record Entry(PageKey key, String title, int size) {
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
        if (scope == null || scope.pages().isEmpty()) {
            return "Es ist kein Bereich gewählt.";
        }
        String text = query.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return "Bitte einen Fachbegriff angeben.";
        }
        if (text.length() > MAX_TERM_CHARS) {
            text = text.substring(0, MAX_TERM_CHARS);
        }
        TermKnowledge.Answer answer = terms.answer(text, scope.pages(), (spaceKey, key) -> {
            String space = spaceKey != null ? spaceKey : spaceOf(key);
            if (space == null) {
                // Ohne Space lässt sich die Seite nicht freigeben; dann darf ihr Inhalt nicht hinaus.
                throw new IllegalStateException("Belegseite ohne Space");
            }
            sources.delivered(space, key);
        }, () -> cancelled);
        return render(text, answer);
    }

    /** Die Antwort als Text für das Modell: knapp, je Aussage eine Zeile, Belegseiten mit Kennung. */
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
                b.append("„").append(oneLine(query)).append("“ ist kein bestätigter Fachbegriff.");
                if (answer.candidates().isEmpty()) {
                    b.append(" Ähnliche Fachbegriffe gibt es keine; nutze ").append(SEARCH).append('.');
                } else {
                    b.append(" Ähnliche Fachbegriffe: ").append(String.join(", ", answer.candidates().stream()
                            .map(ChatTools::oneLine).toList())).append('.');
                }
                return b.toString();
            }
            case FOUND -> {
                // weiter unten
            }
        }
        List<TermKnowledge.Term> terms = answer.terms();
        TermKnowledge.Term main = terms.getFirst();
        b.append("Fachbegriff: ").append(oneLine(main.term())).append('\n');
        if (terms.size() > 1) {
            b.append("Synonyme: ").append(String.join(", ", terms.subList(1, terms.size()).stream()
                    .map(term -> oneLine(term.term())).toList())).append('\n');
        }
        int statements = terms.stream().mapToInt(term -> term.statements().size()).sum();
        if (statements == 0) {
            b.append("Im gewählten Bereich gibt es keine Aussagen zu diesem Begriff; nutze ").append(SEARCH)
                    .append(".\n");
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
        String cut = limits(answer.limits());
        if (!cut.isEmpty()) {
            b.append("Unvollständig: ").append(cut).append('\n');
        }
        if (statements > 0) {
            b.append("Lies eine Belegseite mit ").append(READ).append(", bevor du dich auf sie berufst.");
        }
        return b.toString().stripTrailing();
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
            default -> { }
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
            parts.add(limits.omittedTerms() + " weitere Synonyme");
        }
        if (limits.omittedStatements() > 0) {
            parts.add(limits.omittedStatements() + " weitere Aussagen");
        }
        if (limits.omittedNeighbours() > 0) {
            parts.add(limits.omittedNeighbours() + " weitere verwandte Begriffe");
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

    String search(String text) {
        if (scope == null || scope.pages().isEmpty()) {
            return "Es ist kein Bereich gewählt.";
        }
        String query = text.strip().replaceAll("\\s+", " ");
        if (query.isEmpty()) {
            return "Bitte Suchbegriffe angeben.";
        }
        if (query.length() > ChatTurn.MAX_QUERY_CHARS) {
            query = query.substring(0, ChatTurn.MAX_QUERY_CHARS);
        }
        List<KeywordSearch.Hit> hits = hits(query);
        StringBuilder b = new StringBuilder();
        Set<PageKey> seen = new LinkedHashSet<>();
        for (KeywordSearch.Hit hit : hits) {
            PageKey key = new PageKey(hit.sourceId(), hit.pageId());
            if (!allows(key) || !seen.add(key)) {
                continue;
            }
            b.append("- ").append(ref(key)).append(" | ").append(oneLine(hit.title() == null ? hit.pageId()
                    : hit.title()));
            if (hit.snippet() != null && !hit.snippet().isBlank()) {
                b.append(" | ").append(oneLine(ChatKnowledge.excerpt(hit.snippet(), 300)));
                String spaceKey = hit.spaceKey() != null ? hit.spaceKey() : spaceOf(key);
                if (spaceKey != null) {
                    sources.delivered(spaceKey, key);
                }
            }
            b.append('\n');
            if (seen.size() >= SEARCH_HITS) {
                break;
            }
        }
        return seen.isEmpty() ? "Keine Treffer im gewählten Bereich." : b.toString().stripTrailing();
    }

    /** Die progressive Suche nur in den Seiten des Bereichs, wie die Suche mit Auswahl links. */
    List<KeywordSearch.Hit> hits(String query) {
        KeywordSearch.Query search = new KeywordSearch.Query(query, null, Set.of(), Set.of()).within(scope.pages())
                .withGlossarySynonyms(glossarySynonyms);
        AtomicReference<List<ProgressiveResult.RankedHit>> latest = new AtomicReference<>(List.of());
        ProgressiveSearch running = workspace.progressiveSearch(search, new ProgressiveSearchListener() {
            @Override
            public void ranked(List<ProgressiveResult.RankedHit> ranked) {
                latest.set(ranked);
            }
        }, neighbors);
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
