package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.application.jobs.Foreground;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiChatException;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.holdings.HoldingState;
import com.aresstack.spaceharvester.application.holdings.PageHolding;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.passages.PagePassages;
import com.aresstack.spaceharvester.application.passages.PassageLookup;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearchListener;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.changelog.ChangeUpdate;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ein Turn des Chats (nach {@code ChatServiceBinding} und {@code RagChatBinding} des Enterprise AI Client, auf das
 * Nötige verkürzt): erst das Wissen aus dem Bestand holen, dann die Frage mit Verlauf und Kontext an das Chat-Modell
 * streamen. Deltas, Abschluss, Abbruch und Fehler kommen vom Adapter-Thread und gehen über {@code ui} (in der App
 * {@code SwingUtilities::invokeLater}) an den {@link Listener}; nach dem Ende kommt nichts mehr.
 *
 * <p>Wissen (ADR 0029): die links gewählten Seiten. Sind es wenige, gehen sie ganz mit; sonst sucht die progressive
 * Suche des Core (ADR 0017) nur in diesen Seiten und die besten Treffer kommen als Auszüge mit. Was nicht lokal
 * gespeichert ist, fehlt; das sagt die Statuszeile des Wissens. Die Suche blockiert und läuft deshalb nie auf dem EDT.
 */
public final class ChatTurn {

    /** Bis so viele Seiten gehen die Seiten ganz mit, statt zu suchen. */
    static final int WHOLE_PAGES = 3;
    /** Längstens so lange wartet der Turn auf die Suche; danach zählen die bis dahin bewerteten Treffer. */
    public static final long SEARCH_SECONDS = 90;
    /** Die Suche nimmt höchstens so viele Zeichen Frage an ({@code KeywordSearch.MAX_TEXT_LENGTH}). */
    static final int MAX_QUERY_CHARS = 500;

    /** Welche Art Turn: eine freie Frage oder „Was ist neu?“ aus dem Änderungsprotokoll (ADR 0030). */
    public enum Kind {
        /** Die Frage des Benutzers mit Wissen aus Suche oder ganzen Seiten. */
        QUESTION,
        /**
         * „Was ist neu?“: die seit der vorigen Update-Marke geänderten Seiten der Auswahl, die jüngsten zuerst; ohne
         * Änderung oder ohne Marke antwortet der Turn mit einem festen Satz, ohne das Modell zu rufen.
         */
        WHATS_NEW
    }

    /** Die feste Antwort, wenn in der Auswahl seit der vorigen Marke nichts geändert wurde. */
    public static final String NOTHING_NEW = "Seit dem letzten Abgleich hat sich in dieser Auswahl nichts geändert.";
    /** Die feste Antwort, wenn keine Quelle der Auswahl eine Update-Marke hat. */
    public static final String NO_MARK = "Für diese Quelle gibt es noch keinen Abgleich, mit dem sich vergleichen ließe.";
    /** Die feste Antwort ohne Auswahl. */
    public static final String NO_AREA = "Links eine Wissensquelle, einen Space oder eine Seite wählen.";
    /** Die Fehlermeldung, wenn das Änderungsprotokoll nicht lesbar war; nie ein „nichts geändert“. */
    public static final String CHANGE_LOG_UNREADABLE = "Das Änderungsprotokoll ließ sich nicht lesen.";
    /** Höchstens so viele geänderte Seiten gehen mit Text an das Modell. */
    static final int CHANGED_PAGES = ChatKnowledge.MAX_SOURCES;
    static final DateTimeFormatter CHANGE_DATE = DateTimeFormatter.ofPattern("d.M.yyyy HH:mm");

    /** So viele Zeichen der offenen Seite gehen im Volltext mit; der Rest per {@code seite_lesen} (ADR 0033). */
    static final int OPEN_PAGE_CHARS = 24_000;
    /**
     * Modelle, deren Dienst eine Anfrage mit Werkzeugen abgelehnt hat: Für sie fällt der Chat bis zum Neustart still
     * auf die Suche mit Auszügen zurück (ADR 0033, kein Schalter).
     */
    static final Set<String> WITHOUT_TOOLS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Was die Oberfläche vom Turn erfährt, immer auf dem UI-Thread. */
    public interface Listener {

        /** Der Kontext steht: so viele Auszüge gehen mit (0 ohne Wissen). */
        void context(ChatKnowledge.Context context);

        void delta(String text);

        /**
         * Die Antwort ist ab jetzt {@code text}: Bisher Gezeigtes war ein Werkzeugaufruf als Text oder seine Ankündigung
         * und fällt weg ({@link TextToolCalls}); weitere Deltas hängen sich an {@code text} an.
         */
        default void replaced(String text) {
        }

        void completed(String text);

        /** Mit lesbarer Meldung; die Blase zeigt sie statt einer Antwort. */
        void failed(String message);

        /** Mit dem bis dahin empfangenen Text. */
        void cancelled(String partial);

        /** Das Modell wird geladen; die Antwort kommt, sobald es läuft. */
        default void loading() {
        }

        /** Das Modell holt gerade etwas per Werkzeug (ADR 0033); kurzer Text ohne Argumente des Modells. */
        default void using(String what) {
        }

        /**
         * Ein Werkzeugaufruf beginnt (wie {@code onToolActivity} im EAC): {@code key} kennzeichnet ihn bis
         * {@link #toolFinished}, {@code name} ist der Werkzeugname, {@code what} der kurze Text dazu.
         */
        default void toolStarted(String key, String name, String what) {
        }

        /** Der Werkzeugaufruf {@code key} ist fertig. */
        default void toolFinished(String key) {
        }

        /** So viele Seiten kamen eben neu auf die Liste freigegebener Seiten (ADR 0028/0033). */
        default void released(int count) {
        }

        /** Die Liste freigegebener Seiten ließ sich nicht schreiben. */
        default void releaseFailed(RuntimeException error) {
        }

        /**
         * Fortschritt der Arbeit vor der Antwort, für den Balken der Gedankenblase: {@code done} gelesene Seiten von
         * {@code total} gefundenen und gelesenen Seiten dieses Turns; beide wachsen, keine feste Höchstzahl.
         */
        default void progress(int done, int total) {
        }
    }

    /**
     * Die KI-Freigabe des Chats (ADR 0028, mit Werkzeugen ADR 0033): Mit Werkzeugen kommt nur auf die Liste, was
     * wirklich an das Modell geht (die offene Seite beim Senden, jede gelesene Seite, jede Seite mit Auszug aus der
     * Suche; die Liste der Titel nicht). Ohne Werkzeuge ebenso: jede Seite, deren Text im Kontextblock mitgeht, nie die
     * ganze Auswahl (Angelo 22.11.2026).
     * Aufgerufen auf dem Thread des Turns, nie auf dem EDT.
     */
    public interface Releases {

        Releases NONE = new Releases() {
            @Override
            public int page(String spaceKey, PageKey key) {
                return 0;
            }

        };

        /** Trägt genau diese Seite ein; die Zahl der neuen Einträge (0 oder 1). */
        int page(String spaceKey, PageKey key);
    }

    /** Der Zugriff auf das Änderungsprotokoll, für Tests austauschbar. */
    interface ChangeLogReader {
        List<ChangeUpdate> updates(String sourceId);

        Map<PageKey, Instant> latestContentChanges(String sourceId, String spaceKey, Instant from);
    }

    private final Workspace workspace;
    private ChangeLogReader changeLog;
    private final AiChat port;
    private final String model;
    private final String effort;
    private final List<ChatRecord.Message> history;
    private final String question;
    private PageSelection scope;
    /** Der gewählte Bereich in Worten für die Systemnachricht; {@code null} ohne Auswahl. */
    private final String area;
    private final Kind kind;
    private final Listener listener;
    private final Executor ui;
    private final StringBuilder received = new StringBuilder();
    private volatile boolean cancelRequested;
    private volatile boolean closed;
    private volatile AiChat.Task task;
    private volatile ProgressiveSearch search;
    /** Mit Werkzeugen (ADR 0033): die offene Seite oder {@code null}; ohne Werkzeuge bleibt es bei der Suche. */
    private boolean byTools;
    private PageKey openPage;
    private volatile ChatTools tools;
    /** Die Werkzeuge der laufenden Frage: die Wissenswerkzeuge oder die der Perspektive ({@link #withToolset}). */
    private volatile ChatToolset toolset;
    /** Websuche (Composer-Knopf „Websuche“): die Browser-Werkzeuge des Chats; sonst {@code null}. */
    private WebSearch webSearch;
    private final java.util.concurrent.atomic.AtomicBoolean webSearchEnded =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final JsonMapper BROWSER_JSON = JsonMapper.builder().build();
    /** Von der Perspektive gesetzte Werkzeuge samt Systemnachricht (Command Deck, #1151); sonst {@code null}. */
    private ChatToolset custom;
    private String customSystem;
    private final List<ChatKnowledge.Excerpt> cited = new ArrayList<>();
    /** Ob die Quellen schon einmal gemeldet wurden; danach meldet jede neue Quelle die Liste erneut. */
    private boolean sourcesAnnounced;
    private final Map<PageKey, Integer> numbers = new HashMap<>();
    private Releases releases = Releases.NONE;
    private final Set<PageKey> releasedHere = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Was die Suchen dieses Turns als Treffer nannten, für den Balken der Gedankenblase. */
    private final Set<PageKey> foundHere = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Der Balken der Gedankenblase (Angelo 24.11.2026, keine feste Höchstzahl): gelesene Seiten von allen gefundenen
     * und gelesenen dieses Turns; beide Zahlen wachsen mit der Arbeit.
     */
    private void reportProgress() {
        Set<PageKey> all = new java.util.HashSet<>(foundHere);
        all.addAll(releasedHere);
        int done = releasedHere.size();
        int total = all.size();
        onUi(() -> listener.progress(done, total));
    }
    /** Bestand je Space für {@link #spaceOf}, je Turn einmal gelesen. */
    private final Map<String, Map<String, PageHolding>> spacePages = new java.util.concurrent.ConcurrentHashMap<>();

    public ChatTurn(Workspace workspace, AiChat port, String model, String effort,
                    List<ChatRecord.Message> history, String question, PageSelection scope, String area,
                    Listener listener, Executor ui) {
        this(workspace, port, model, effort, history, question, scope, area, Kind.QUESTION, listener, ui);
    }

    public ChatTurn(Workspace workspace, AiChat port, String model, String effort,
                    List<ChatRecord.Message> history, String question, PageSelection scope, String area, Kind kind,
                    Listener listener, Executor ui) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.port = Objects.requireNonNull(port, "port");
        this.model = Objects.requireNonNull(model, "model");
        this.effort = effort;
        this.history = List.copyOf(history);
        this.question = Objects.requireNonNull(question, "question");
        this.scope = scope;
        this.area = area;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.changeLog = new ChangeLogReader() {
            @Override
            public List<ChangeUpdate> updates(String sourceId) {
                return workspace.changeUpdates(sourceId);
            }

            @Override
            public Map<PageKey, Instant> latestContentChanges(String sourceId, String spaceKey, Instant from) {
                return workspace.latestContentChanges(sourceId, spaceKey, from);
            }
        };
        this.listener = Objects.requireNonNull(listener, "listener");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    /** Für Tests: ein anderer Zugriff auf das Änderungsprotokoll. */
    ChatTurn changeLog(ChangeLogReader reader) {
        this.changeLog = Objects.requireNonNull(reader, "reader");
        return this;
    }

    /**
     * Die Websuche dieses Turns (Composer-Knopf „Websuche“, wie im Enterprise AI Client): die Browser-Werkzeuge des
     * Chats zusätzlich zu den übrigen Werkzeugen, die Browser-Regeln im Systemhinweis und das Browser-Budget als
     * Zahl der Werkzeugrunden. Gilt nur mit Werkzeugen.
     */
    public ChatTurn webSearch(WebSearch webSearch) {
        this.webSearch = webSearch;
        return this;
    }

    /**
     * Die Browser-Seite eines Turns.
     *
     * @param tools  die an den Chat gebundenen Browser-Werkzeuge
     * @param rules  zusätzlicher Systemhinweis (Lesen mehrerer Seiten, Browserprofil mit Such-URLs)
     * @param begin  wird zu Beginn des Turns auf seinem Thread gerufen (meldet den Turn beim Browser an), oder
     *               {@code null}
     * @param ended  wird nach dem Ende des Turns gerufen, auf eigenem Thread (schließt den Browser)
     */
    public record WebSearch(com.aresstack.spaceharvester.app.chat.browser.BrowserChatTools tools, String rules,
                            Runnable begin, Runnable ended) {
    }

    /**
     * Wissen per Werkzeug (ADR 0033): Die offene Seite geht im Volltext mit, alles andere im Bereich holt sich das
     * Modell über die Werkzeuge. Hat der Dienst Werkzeuge für dieses Modell schon abgelehnt, bleibt es bei der Suche.
     *
     * @param open die Seite, die der Benutzer gerade offen hat, oder {@code null} bei Space, Quelle oder allen Quellen
     */
    public ChatTurn knowledgeByTools(PageKey open) {
        this.byTools = kind == Kind.QUESTION && !WITHOUT_TOOLS.contains(model);
        this.openPage = open;
        return this;
    }

    /**
     * Suchmethode „Nachbarseiten“ (R2, C1): die Stellung der Suchoptionen beim Senden; die Suche im Bereich nimmt dann
     * Nachbarn der besten Treffer dazu, die die Frage auch treffen, nie von außerhalb des Bereichs.
     */
    public ChatTurn searchingNeighbors(boolean on) {
        this.neighbors = on;
        return this;
    }

    private boolean neighbors;

    /**
     * Suchmethode „Synonyme der Fachbegriffe“ (K5a): die Stellung der Suchoptionen beim Senden; die Suche im Bereich
     * sucht dann zu bestätigten Fachbegriffen auch ihre vorgeschlagenen Synonyme.
     */
    public ChatTurn searchingGlossarySynonyms(boolean on) {
        this.glossarySynonyms = on;
        return this;
    }

    private boolean glossarySynonyms;

    /**
     * Die Wurzeln der Auswahl links (Quelle, Space, Seite samt Unterseiten), wie der Baum sie zeigt. Solange der
     * Katalog die Auswahl nicht vollständig kennt (etwa ein SharePoint-Ordner, ADR 0040 Punkt 3, oder ein nur teilweise
     * geladener Space), ergänzen die Werkzeuge ihn über die gemeinsame Navigation ({@link LiveArea}); ein solcher
     * Bereich ist ein Bereich, auch wenn der Katalog dort keine Seite kennt (Angelo 26.11.2026: bei SharePoint antwortete der Chat trotz Auswahl, ihm lägen
     * keine Wissensquellen vor).
     */
    public ChatTurn area(List<com.aresstack.spaceharvester.application.holdings.Selection.Root> roots) {
        this.liveRoots = LiveArea.complementing(scope, roots);
        if (scope == null && !liveRoots.isEmpty()) {
            scope = new PageSelection(com.aresstack.spaceharvester.application.snapshot.PageSet.EMPTY,
                    com.aresstack.spaceharvester.application.holdings.HoldingCounts.ZERO, List.of(), false);
        }
        return this;
    }

    private List<com.aresstack.spaceharvester.application.holdings.Selection.Root> liveRoots = List.of();
    private LiveArea.Navigation navigation;

    /** Für Tests: eine andere Navigation für {@link #area}. */
    ChatTurn navigation(LiveArea.Navigation navigation) {
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        return this;
    }

    /** Die KI-Freigabe dieses Turns; ohne Aufruf trägt er nichts ein. */
    public ChatTurn releasing(Releases releases) {
        this.releases = Objects.requireNonNull(releases, "releases");
        return this;
    }

    /**
     * Werkzeuge der aktiven Perspektive statt der Wissenswerkzeuge (#1151): Der Chat bleibt derselbe, nur was das
     * Modell für diese Frage tun darf, kommt aus der Perspektive – im Command Deck der Maskenassistent. Ohne
     * Wissenssuche und ohne Rückfall: Lehnt der Dienst Werkzeuge ab, endet die Frage mit einer Meldung.
     *
     * @param tools  die Werkzeuge
     * @param system die Systemnachricht dazu
     */
    public ChatTurn withToolset(ChatToolset tools, String system) {
        this.custom = Objects.requireNonNull(tools, "tools");
        this.customSystem = Objects.requireNonNull(system, "system");
        return this;
    }

    /** Die feste Meldung, wenn der Dienst für dieses Modell keine Werkzeuge annimmt. */
    public static final String NO_TOOLS = "Das gewählte Modell nimmt keine Werkzeuge an; ohne Werkzeuge kann der "
            + "Chat hier nichts lesen oder ausfüllen.";

    /** Für Tests: ein anderes Begriffswissen für {@value ChatTools#TERM}. */
    ChatTurn termLookup(ChatTools.TermLookup lookup) {
        this.termLookup = Objects.requireNonNull(lookup, "lookup");
        return this;
    }

    private ChatTools.TermLookup termLookup;

    /**
     * Schalter „Interne Suche“ im Composer (Angelo 23.11.2026): {@code sources} liefert die Wissensquellen, die die
     * globale Suche des Modells durchsuchen darf (die für die KI freigegebenen); {@code null} lässt das Werkzeug weg.
     */
    public ChatTurn appSearch(java.util.function.Supplier<Set<String>> sources) {
        return appSearch(sources, sources);
    }

    public ChatTurn appSearch(java.util.function.Supplier<Set<String>> sources,
                              java.util.function.Supplier<Set<String>> explicitSources) {
        this.appSources = sources;
        this.explicitAppSources = explicitSources;
        return this;
    }

    private java.util.function.Supplier<Set<String>> appSources;
    private java.util.function.Supplier<Set<String>> explicitAppSources;

    /**
     * Suchagent (Angelo 24.11.2026): In der Suche, und ohne gewählten Bereich, recherchiert das Modell mit den
     * zugeschalteten Werkzeugen (Bereich als Filter, interne Suche, Websuche) gründlich, bis es fertig ist, statt nach
     * einer Suche aufzugeben; ohne jedes Werkzeug antwortet es aus eigenem Wissen.
     */
    public ChatTurn searchAgent() {
        this.searchAgent = true;
        return this;
    }

    /** Ob der Turn ein Suchagent ist. */
    private boolean searchAgent;

    /**
     * Wie viele Unteragenten gleichzeitig Treffer der internen Suche auswerten und wie viele Werkzeugaufrufe einer
     * Runde (außer Browser) gleichzeitig laufen (Menü „Parallele Agenten (intern)“, Angelo 24.11.2026).
     */
    public ChatTurn parallelAgents(int agents) {
        this.agents = Math.max(1, agents);
        return this;
    }

    private int agents = 1;
    /** Die laufenden Anfragen der Unteragenten, damit Stopp sie beendet. */
    private final Set<AiChat.Task> subTasks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Ein Favoriten-Ordner ist der Bereich (ADR 0034 §5): {@code nodes} liefert seine Unterordner und Favoriten für
     * {@value ChatTools#LIST}; {@code null} für jeden anderen Bereich.
     */
    public ChatTurn collection(CollectionNodes nodes) {
        this.collection = nodes;
        return this;
    }

    private CollectionNodes collection;

    /** Die offene Ansicht, für die der Turn arbeitet ({@link Foreground}), oder {@code null}. */
    private volatile String view;
    /** Solange der Turn arbeitet, lassen KI-Anfragen gewöhnlicher Aufträge ihm den Vortritt. */
    private volatile Foreground.Hold busy = () -> { };

    /**
     * Der Turn arbeitet für die offene Ansicht (Angelo 24.11.2026): Vom Senden bis zur Antwort warten die KI-Anfragen
     * gewöhnlicher Aufträge, und was er an Aufträgen anlegt (etwa „Suchtreffer vorbereiten“), startet vor allen anderen.
     * Wird die Ansicht verlassen, verliert beides den Vorrang.
     */
    public ChatTurn inView(String view) {
        this.view = view;
        return this;
    }

    /** Startet den Turn auf einem eigenen Thread; kehrt sofort zurück. */
    public void start() {
        String working = view;
        busy = Foreground.busy(working);
        Thread worker = new Thread(() -> Foreground.run(working, () -> {
            try {
                run();
            } catch (RuntimeException | Error e) {
                busy.close();
                throw e;
            }
        }), "chat-turn");
        worker.setDaemon(true);
        worker.start();
    }

    /** Bricht Suche oder Antwort ab; die Abbruchmeldung kommt über den Listener. */
    public void cancel() {
        cancelRequested = true;
        ChatToolset using = toolset;
        if (using != null) {
            using.cancel();
        }
        ProgressiveSearch running = search;
        if (running != null) {
            running.cancel();
        }
        AiChat.Task streaming = task;
        if (streaming != null) {
            streaming.cancel();
        }
        subTasks.forEach(AiChat.Task::cancel);
    }

    private void run() {
        WebSearch web = webSearch;
        if (web != null && web.begin() != null) {
            web.begin().run();
        }
        if (custom != null) {
            toolset = withWebSearch(custom);
            onUi(() -> listener.context(ChatKnowledge.Context.EMPTY));
            round(conversation(withWebSearchRules(customSystem), question), 0);
        } else if (byTools) {
            runWithTools();
        } else {
            runWithSearch();
        }
    }

    /** Ohne Werkzeuge: Wissen aus Suche oder ganzen Seiten im Kontextblock (vor ADR 0033, und als Rückfall). */
    private void runWithSearch() {
        ChatKnowledge.Context context;
        String asked = question;
        if (kind == Kind.WHATS_NEW) {
            Changes changes;
            try {
                changes = changes();
            } catch (RuntimeException e) {
                // Ein Fehler behauptet nie „nichts geändert“: der Turn endet lesbar als Fehler.
                String message = CHANGE_LOG_UNREADABLE + "\nTechnische Ursache: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                finish(() -> listener.failed(message));
                return;
            }
            if (changes.fixed() != null) {
                // Ohne Änderung kein Modellaufruf: der feste Satz ist die Antwort.
                String fixed = changes.fixed();
                onUi(() -> listener.context(ChatKnowledge.Context.EMPTY));
                finish(() -> listener.completed(fixed));
                return;
            }
            context = ChatKnowledge.assemble(changes.excerpts());
            asked = whatsNewQuestion(question, changes.total(), context.accepted().size(), changes.since());
        } else {
            try {
                context = gather();
            } catch (RuntimeException e) {
                // Ohne Wissen antwortet das Modell trotzdem; die Statuszeile sagt, dass die Suche scheiterte.
                context = ChatKnowledge.Context.EMPTY;
            }
        }
        if (cancelRequested) {
            finish(() -> listener.cancelled(""));
            return;
        }
        // ADR 0033 Punkt 8 auch ohne Werkzeuge (Angelo 22.11.2026): nur die Seiten, deren Text mitgeht, nie die ganze
        // Auswahl.
        context = releaseDelivered(context);
        ChatKnowledge.Context chosen = context;
        onUi(() -> listener.context(chosen));
        // Ohne Bereich gibt es kein Wissen aus dem Bestand: dann aus eigenem Wissen (Angelo 24.11.2026).
        String system = scope == null && kind == Kind.QUESTION ? ChatKnowledge.OWN_KNOWLEDGE_PROMPT
                : ChatKnowledge.systemMessage(area, context);
        List<AiChat.Message> messages = conversation(system, asked);
        task = port.stream(new AiChat.Request(messages, model, effort), new AiChat.Listener() {
            @Override
            public void delta(String text) {
                synchronized (received) {
                    received.append(text);
                }
                onUi(() -> listener.delta(text));
            }

            @Override
            public void completed(String text) {
                finish(() -> listener.completed(text));
            }

            @Override
            public void failed(AiChatException error) {
                String message = describe(error);
                finish(() -> listener.failed(message));
            }

            @Override
            public void loading() {
                onUi(listener::loading);
            }

            @Override
            public void cancelled() {
                String partial;
                synchronized (received) {
                    partial = received.toString();
                }
                finish(() -> listener.cancelled(partial));
            }
        });
        if (cancelRequested) {
            task.cancel();
        }
    }

    /** Systemnachricht, Verlauf ohne abgebrochene Teilantworten (wie im EAC) und die Frage. */
    private List<AiChat.Message> conversation(String system, String asked) {
        List<AiChat.Message> messages = new ArrayList<>();
        messages.add(AiChat.Message.system(system));
        for (ChatRecord.Message message : history) {
            if (message.text().isBlank()) {
                continue;
            }
            if (message.user()) {
                messages.add(AiChat.Message.user(message.text()));
            } else if (message.assistant() && !message.cancelled() && !TextToolCalls.leaked(message.text())) {
                // Eine Antwort, die nur ein Werkzeugaufruf als Text war, geht nicht zurück: Sonst ahmt das Modell sie
                // in jeder folgenden Frage nach (Angelo 26.11.2026, „We need to call the tool correctly.“).
                messages.add(AiChat.Message.assistant(message.text()));
            }
        }
        messages.add(AiChat.Message.user(asked));
        return messages;
    }

    // ------------------------------------------------------------------ Werkzeuge (ADR 0033)

    /**
     * Mit Werkzeugen: die offene Seite als Quelle [1] im Volltext (bei sehr langen Seiten der Anfang), danach Runden aus
     * Antwort oder Werkzeugaufrufen. Jede Runde prüft den Abbruch; die letzte Runde geht ohne Werkzeuge.
     */
    private void runWithTools() {
        // Was ich sehe, sieht der Bot (Angelo 23.11.2026): Die offene Seite gehört immer dazu, auch wenn die Auflösung
        // des Bereichs sie nicht kennt (etwa ein Ticket aus der Suche, das im Bestandsbaum fehlt).
        Set<PageKey> granted = openPage == null ? Set.of() : Set.of(openPage);
        ChatTools running = new ChatTools(workspace, scope, granted, new ChatTools.Sources() {
            @Override
            public int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
                return ChatTurn.this.cite(excerpt, key);
            }

            @Override
            public void delivered(String spaceKey, PageKey key) {
                releasePageOrThrow(spaceKey, key);
            }

            @Override
            public void found(PageKey key) {
                if (foundHere.add(key)) {
                    reportProgress();
                }
            }
        }).neighbors(neighbors).glossarySynonyms(glossarySynonyms).collection(collection)
                .liveArea(liveRoots.isEmpty() ? LiveArea.NONE : new LiveArea(navigation != null ? navigation
                        : LiveArea.of(workspace), liveRoots));
        if (termLookup != null) {
            running.termLookup(termLookup);
        }
        if (appSources != null) {
            running.appSearch(appSources, explicitAppSources);
        }
        tools = running;
        toolset = new ChatToolset() {
            @Override
            public List<AiChat.Tool> definitions() {
                return running.toolDefinitions();
            }

            @Override
            public String run(AiChat.ToolCall call) {
                return running.run(call);
            }

            @Override
            public String describe(AiChat.ToolCall call) {
                return ChatTools.describe(call);
            }

            @Override
            public void cancel() {
                running.cancel();
            }
        };
        toolset = withWebSearch(withSubAgents(toolset));
        String openRef = null;
        ChatKnowledge.Excerpt open = null;
        int shown = 0;
        int total = 0;
        if (openPage != null && running.allows(openPage)) {
            ChatTools.Page page = running.page(openPage);
            String spaceKey = page == null ? null : running.spaceOf(openPage);
            if (spaceKey == null && page != null) {
                spaceKey = page.spaceKey();
            }
            // Die offene Seite geht nur mit, wenn sie vorher eingetragen ist (ADR 0033 Punkt 8); scheitert der
            // Eintrag, meldet ihn die Oberfläche, und die Frage geht ohne die Seite hinaus.
            boolean released = false;
            if (page != null && !page.text().isBlank() && spaceKey != null) {
                try {
                    releasePageOrThrow(spaceKey, openPage);
                    released = true;
                } catch (RuntimeException failed) {
                    ChatTools.diagnosis.accept("Offene Seite: Freigabe gescheitert: "
                            + failed.getClass().getSimpleName());
                }
            }
            if (released) {
                total = page.text().length();
                String head = total <= OPEN_PAGE_CHARS ? page.text() : ChatKnowledge.excerpt(page.text(),
                        OPEN_PAGE_CHARS).replaceAll(" …$", "");
                shown = head.length();
                open = new ChatKnowledge.Excerpt(page.title(), openPage.sourceId(), page.location(), head,
                        openPage.pageId());
                openRef = ChatTools.ref(openPage);
                cite(open, openPage);
            }
        }
        if (cancelRequested) {
            finish(() -> listener.cancelled(""));
            return;
        }
        synchronized (cited) {
            sourcesAnnounced = true;
        }
        reportSources();
        String system = searchAgent
                ? ChatKnowledge.agentSystemMessage(area, scope != null, appSources != null,
                        !toolset.definitions().isEmpty(), open, shown, total, openRef)
                : ChatKnowledge.toolSystemMessage(area, open, shown, total, openRef, appSources != null);
        List<AiChat.Message> messages = conversation(withWebSearchRules(system), question);
        round(messages, 0);
    }

    /**
     * Eine Anfrage; Werkzeugaufrufe führen zur nächsten Runde, alles andere beendet den Turn. Die Werkzeuge gehen in
     * jeder Runde mit, ohne feste Rundengrenze: Das Modell sucht, bis es fertig ist; anhalten kann der Benutzer mit
     * Abbrechen (Angelo 26.11.2026). Früher ging nach 8 Runden eine letzte Anfrage still ohne Werkzeuge hinaus, und das
     * Modell schrieb seinen nächsten Aufruf dann als Text in die Antwort.
     * <p>
     * Schreibt das Modell einen Aufruf trotzdem als Text statt im Feld {@code tool_calls} ({@link TextToolCalls}),
     * führt der Turn ihn als Aufruf aus; kündigt es einen Aufruf nur an, erinnert er es einmal an die
     * Werkzeugschnittstelle. Solcher Text erscheint nie in der Blase: Bis klar ist, dass er keiner ist, hält die Runde
     * ihn zurück, und was schon zu sehen war, nimmt {@link Listener#replaced} wieder heraus.
     */
    private void round(List<AiChat.Message> messages, int round) {
        if (cancelRequested) {
            String partial;
            synchronized (received) {
                partial = received.toString();
            }
            finish(() -> listener.cancelled(partial));
            return;
        }
        List<AiChat.Tool> offered = toolset.definitions();
        int roundStart;
        synchronized (received) {
            roundStart = received.length();
        }
        // Noch nicht gezeigter Text dieser Runde und ob die Runde schon in die Blase schreibt; beides unter received.
        StringBuilder held = new StringBuilder();
        boolean[] flowing = {false};
        task = port.stream(new AiChat.Request(messages, model, effort, offered), new AiChat.Listener() {
            @Override
            public void delta(String text) {
                String release = null;
                synchronized (received) {
                    received.append(text);
                    if (flowing[0]) {
                        release = text;
                    } else {
                        held.append(text);
                        if (held.length() > TextToolCalls.ANNOUNCE_MAX_CHARS
                                && !TextToolCalls.suspicious(held.toString(), offered)) {
                            flowing[0] = true;
                            release = held.toString();
                            held.setLength(0);
                        }
                    }
                }
                if (release != null) {
                    String shown = release;
                    onUi(() -> listener.delta(shown));
                }
            }

            @Override
            public void completed(String text) {
                // Kam diese Runde ohne Deltas (nur Abschlusstext), gehört der Text noch an die Antwort, auch wenn
                // frühere Runden schon Text gestreamt haben (Codex-Befund zu #1003).
                String roundText;
                boolean shownBefore;
                synchronized (received) {
                    if (received.length() == roundStart && text != null && !text.isEmpty()) {
                        received.append(text);
                        held.append(text);
                    }
                    roundText = received.substring(roundStart);
                    shownBefore = flowing[0];
                }
                List<AiChat.ToolCall> textCalls = TextToolCalls.parse(roundText, offered);
                if (!textCalls.isEmpty() || TextToolCalls.announces(roundText)) {
                    retract(roundStart, held, shownBefore);
                    ChatTools.diagnosis.accept("Chat: Werkzeugaufruf als Antworttext statt tool_calls (Runde "
                            + (round + 1) + ", " + (textCalls.isEmpty() ? "nur angekündigt" : textCalls.stream()
                            .map(AiChat.ToolCall::name).collect(java.util.stream.Collectors.joining(", "))) + ")");
                    if (!textCalls.isEmpty()) {
                        startTools(messages, "", textCalls, round);
                        return;
                    }
                    if (!channelReminded) {
                        channelReminded = true;
                        List<AiChat.Message> next = new ArrayList<>(messages);
                        next.add(AiChat.Message.user(TOOL_CHANNEL_REMINDER));
                        Thread again = new Thread(() -> round(next, round + 1), "chat-turn-round");
                        again.setDaemon(true);
                        again.start();
                        return;
                    }
                    finish(() -> listener.failed(TOOL_TEXT_FAILED));
                    return;
                }
                String unshown;
                String full;
                synchronized (received) {
                    unshown = held.toString();
                    held.setLength(0);
                    flowing[0] = true;
                    full = received.toString();
                }
                if (!unshown.isEmpty()) {
                    onUi(() -> listener.delta(unshown));
                }
                finish(() -> listener.completed(full));
            }

            @Override
            public void toolCalls(String text, List<AiChat.ToolCall> calls) {
                // Vorspann vor echten Aufrufen („We will call the search.“) bleibt aus der Blase, sonst erscheint
                // zurückgehaltener Text wie bisher.
                String unshown;
                synchronized (received) {
                    unshown = held.toString();
                    held.setLength(0);
                    if (!flowing[0] && TextToolCalls.announces(unshown)) {
                        received.setLength(roundStart);
                        unshown = "";
                    }
                    flowing[0] = true;
                }
                if (!unshown.isEmpty()) {
                    String shown = unshown;
                    onUi(() -> listener.delta(shown));
                }
                startTools(messages, unshown.isEmpty() ? "" : text, calls, round);
            }

            @Override
            public void failed(AiChatException error) {
                boolean nothingYet;
                synchronized (received) {
                    nothingYet = received.length() == 0;
                }
                if (custom != null && round == 0 && nothingYet
                        && error.kind() == AiChatException.Kind.INVALID_REQUEST) {
                    finish(() -> listener.failed(NO_TOOLS + "\nTechnische Ursache: " + describe(error)));
                    return;
                }
                if (round == 0 && nothingYet && error.kind() == AiChatException.Kind.INVALID_REQUEST) {
                    // Der Dienst lehnt Werkzeuge ab: für dieses Modell merken und still auf die Suche zurückfallen.
                    WITHOUT_TOOLS.add(model);
                    runWithSearch();
                    return;
                }
                String message = describe(error);
                finish(() -> listener.failed(message));
            }

            @Override
            public void loading() {
                onUi(listener::loading);
            }

            @Override
            public void cancelled() {
                String partial;
                synchronized (received) {
                    // Zurückgehaltener Text, der ein Werkzeugaufruf sein könnte, bleibt auch beim Abbruch draußen.
                    if (!flowing[0] && TextToolCalls.suspicious(held.toString(), offered)) {
                        received.setLength(roundStart);
                    }
                    partial = received.toString();
                }
                finish(() -> listener.cancelled(partial));
            }
        });
        if (cancelRequested) {
            task.cancel();
        }
    }

    /** Ob der Turn das Modell schon einmal an die Werkzeugschnittstelle erinnert hat. */
    private volatile boolean channelReminded;

    /**
     * Erinnerung, wenn das Modell einen Aufruf nur ankündigt („We will perform a silent tool call.“): Als Text wird
     * nichts ausgeführt. Geht einmal je Turn als Hinweis des Clients an das Modell, nie in die Blase oder den Verlauf.
     */
    static final String TOOL_CHANNEL_REMINDER = "[Hinweis des Clients] Deine letzte Nachricht hat einen "
            + "Werkzeugaufruf nur als Text angekündigt; als Text wird nichts ausgeführt. Rufe Werkzeuge ausschließlich "
            + "über die Werkzeugschnittstelle auf und schreibe nichts über Werkzeuge in die Antwort, oder antworte "
            + "direkt auf Deutsch.";

    /** Meldung, wenn das Modell auch nach der Erinnerung nur Werkzeugaufrufe ankündigt. */
    static final String TOOL_TEXT_FAILED = "Die KI hat einen Werkzeugaufruf nur angekündigt, statt ihn auszuführen. "
            + "Bitte die Frage noch einmal stellen.";

    /** Nimmt den Text der Runde ab {@code roundStart} heraus; war schon etwas zu sehen, erfährt es die Oberfläche. */
    private void retract(int roundStart, StringBuilder held, boolean shown) {
        String now;
        synchronized (received) {
            received.setLength(roundStart);
            held.setLength(0);
            now = received.toString();
        }
        if (shown) {
            onUi(() -> listener.replaced(now));
        }
    }

    /** Führt die Aufrufe auf eigenem Thread aus: nie auf dem Strom-Thread des Adapters und nie auf dem EDT. */
    private void startTools(List<AiChat.Message> messages, String text, List<AiChat.ToolCall> calls, int round) {
        Thread worker = new Thread(() -> runTools(messages, text, calls, round), "chat-turn-tools");
        worker.setDaemon(true);
        worker.start();
    }

    /** Führt die Aufrufe einer Runde aus (auf eigenem Thread) und stellt die nächste Anfrage. */
    private void runTools(List<AiChat.Message> messages, String text, List<AiChat.ToolCall> calls, int round) {
        List<AiChat.Message> next = new ArrayList<>(messages);
        next.add(AiChat.Message.assistant(text == null ? "" : text, calls));
        // Mehrere Aufrufe einer Runde laufen gleichzeitig (höchstens „Parallele Agenten“); Browser-Aufrufe bleiben
        // nacheinander, der Browser eines Chats bedient eine Aktion nach der anderen. Die Antworten bleiben in der
        // Reihenfolge der Aufrufe.
        String[] results = new String[calls.size()];
        List<java.util.concurrent.Future<?>> running = new ArrayList<>();
        java.util.concurrent.ExecutorService pool = agents > 1 && calls.size() > 1
                ? java.util.concurrent.Executors.newFixedThreadPool(Math.min(agents, calls.size()),
                daemon("chat-werkzeug")) : null;
        try {
            for (int i = 0; i < calls.size(); i++) {
                if (cancelRequested) {
                    break;
                }
                AiChat.ToolCall call = calls.get(i);
                int index = i;
                Runnable work = () -> {
                    if (cancelRequested) {
                        return;
                    }
                    String what = toolset.describe(call);
                    String key = round + ":" + index + ":" + call.id();
                    onUi(() -> {
                        listener.using(what);
                        listener.toolStarted(key, call.name(), what);
                    });
                    try {
                        results[index] = toolset.run(call);
                    } finally {
                        onUi(() -> listener.toolFinished(key));
                    }
                };
                if (pool == null || browserCall(call)) {
                    work.run();
                } else {
                    running.add(pool.submit(work));
                }
            }
            for (java.util.concurrent.Future<?> future : running) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    // Ein Werkzeug liefert sonst immer Text; ohne Antwort zählt der Aufruf unten als gescheitert.
                }
            }
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
        boolean anythingNew = false;
        for (int i = 0; i < calls.size(); i++) {
            String result = results[i] != null ? results[i]
                    : cancelRequested ? "Abgebrochen." : "Das Werkzeug ist gescheitert.";
            next.add(AiChat.Message.tool(calls.get(i).id(), result));
            anythingNew |= seenResults.add(result);
        }
        quiet = anythingNew ? 0 : quiet + 1;
        if (quiet > 0 && quiet % quietRounds == 0) {
            // Stillstand wie in askai (ResearchLead.endRule): offen sagen, nie still Werkzeuge entziehen.
            next.add(AiChat.Message.user(String.format(STANDSTILL_INSTRUCTION, quiet)));
        }
        round(next, round + 1);
    }

    /**
     * Stillstand (Angelo 26.11.2026, Verfahren aus askai {@code ResearchLead.endRule}): Nach {@code rounds}
     * Werkzeugrunden, deren Ergebnisse in dieser Frage alle schon da waren, bittet der Turn das Modell offen, mit dem
     * Gefundenen zu antworten. Eine feste Rundengrenze gibt es nicht; die Werkzeuge bleiben angeboten.
     */
    public ChatTurn quietRounds(int rounds) {
        this.quietRounds = Math.max(1, rounds);
        return this;
    }

    /** Runden ohne Neues bis zum Hinweis; im Menü am Dreieck des Chats einstellbar. */
    private int quietRounds = 3;
    /** Runden ohne neues Ergebnis in Folge; nur auf dem Werkzeug-Thread des Turns. */
    private int quiet;
    /** Alle Werkzeugergebnisse dieses Turns: Ein Ergebnis, das schon da war, ist nichts Neues. */
    private final Set<String> seenResults = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Hinweis bei Stillstand, nach {@code FINAL_ROUND_INSTRUCTION} des EAC ({@code ChatCompletionsToolLoop}), aber ohne
     * die Werkzeuge zu entziehen; {@code %d} ist die Zahl der Runden ohne Neues.
     */
    static final String STANDSTILL_INSTRUCTION = "[Hinweis des Clients] Die letzten %d Werkzeugrunden brachten kein "
            + "neues Ergebnis. Verwende die bereits vorliegenden Werkzeugergebnisse und gib jetzt die bestmögliche "
            + "abschließende Antwort auf die ursprüngliche Benutzerfrage. Weise auf fehlende Informationen hin; suche "
            + "nur weiter, wenn ein ganz anderer Weg Neues verspricht.";

    /**
     * Trägt eine Seite ein, bevor ihr Inhalt an das Modell geht, je Turn einmal. Scheitert der Eintrag, wirft es nach
     * der Meldung an die Oberfläche, und die Seite geht nicht an das Modell (ADR 0033 Punkt 8, alle Werkzeuge).
     */
    private void releasePageOrThrow(String spaceKey, PageKey key) {
        if (!releasedHere.add(key)) {
            return;
        }
        try {
            int added = releases.page(spaceKey, key);
            if (added > 0) {
                onUi(() -> listener.released(added));
            }
            reportProgress();
        } catch (RuntimeException e) {
            releasedHere.remove(key);
            onUi(() -> listener.releaseFailed(e));
            throw e;
        }
    }

    /**
     * Ohne Werkzeuge: Jede Seite, deren Text im Kontextblock an das Modell geht, kommt vorher auf die Liste
     * freigegebener Seiten, genau wie bei den Werkzeugen (ADR 0033 Punkt 8). Die übrige Auswahl bleibt draußen: Früher
     * trug das Senden die ganze links gewählte Auswahl ein, und die KI-Anreicherung bettete danach alle ihre Seiten ein
     * (Angelo 22.11.2026). Lässt sich eine Seite nicht eintragen (ohne Space, nicht lokal, Fehler der Ablage), geht ihr
     * Text nicht hinaus; Auszüge ohne Seite (etwa die Titelliste des Überblicks) bleiben, wie sie sind.
     */
    private ChatKnowledge.Context releaseDelivered(ChatKnowledge.Context context) {
        if (context.isEmpty()) {
            return context;
        }
        List<ChatKnowledge.Excerpt> kept = new ArrayList<>();
        for (ChatKnowledge.Excerpt excerpt : context.accepted()) {
            if (excerpt.pageId() == null || excerpt.pageId().isBlank()) {
                kept.add(excerpt);
                continue;
            }
            PageKey key = new PageKey(excerpt.sourceId(), excerpt.pageId());
            String spaceKey = spaceOf(key);
            if (spaceKey == null) {
                RuntimeException missing = new IllegalStateException("Seite ohne Space im gewählten Bereich");
                onUi(() -> listener.releaseFailed(missing));
                continue;
            }
            try {
                releasePageOrThrow(spaceKey, key);
                kept.add(excerpt);
            } catch (RuntimeException e) {
                // Gemeldet hat releasePageOrThrow; der Text der Seite geht nicht an das Modell.
            }
        }
        return kept.size() == context.accepted().size() ? context : ChatKnowledge.assemble(kept);
    }

    /** Der Space der Seite im gewählten Bereich, je Turn einmal je Space gelesen; {@code null}, wenn keiner sie hat. */
    private String spaceOf(PageKey key) {
        if (scope == null) {
            return null;
        }
        for (var space : scope.spaces()) {
            if (!space.sourceId().equals(key.sourceId())) {
                continue;
            }
            Map<String, PageHolding> pages = spacePages.computeIfAbsent(space.sourceId() + "\u0000" + space.spaceKey(),
                    ignored -> {
                        try {
                            return workspace.holdings(space.sourceId(), space.spaceKey()).pages();
                        } catch (RuntimeException gone) {
                            return Map.of();
                        }
                    });
            if (pages.containsKey(key.pageId())) {
                return space.spaceKey();
            }
        }
        return null;
    }

    /** Nimmt eine gelesene Seite als Quelle auf (oder findet sie wieder) und meldet die Liste; gibt ihre Nummer. */
    private int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
        synchronized (cited) {
            Integer known = numbers.get(key);
            if (known != null) {
                return known;
            }
            cited.add(excerpt);
            numbers.put(key, cited.size());
            if (sourcesAnnounced) {
                reportSources();
            }
            return cited.size();
        }
    }

    /** Meldet die bisher nummerierten Quellen als Kontext (die Reihenfolge ist die Nummer). */
    private void reportSources() {
        ChatKnowledge.Context context;
        synchronized (cited) {
            context = cited.isEmpty() ? ChatKnowledge.Context.EMPTY
                    : new ChatKnowledge.Context("", List.copyOf(cited));
        }
        onUi(() -> listener.context(context));
    }

    // ------------------------------------------------------------------ Was ist neu?

    /**
     * Das Ergebnis der Suche nach Änderungen: ein fester Satz statt einer Modellantwort, oder die geänderten Seiten
     * der Auswahl als Auszüge (die jüngsten zuerst), ihre Gesamtzahl und die älteste Marke, seit der gezählt wurde.
     */
    record Changes(String fixed, List<ChatKnowledge.Excerpt> excerpts, int total, Instant since) {
    }

    /**
     * Die geänderten Seiten der Auswahl seit der vorigen Update-Marke jeder Quelle (ADR 0030): „seit“ ist je Quelle
     * der Anfang des jüngsten abgeschlossenen Updates (bei der Erstbefüllung seine Marke), also alles, was der letzte
     * Abgleich brachte und was seither im Hintergrund dazukam. Der Core liefert je Quelle und Space die Seiten mit
     * ihrer jüngsten Inhaltsänderung; hier bleibt nur die Schnittmenge mit den Seiten der Auswahl.
     */
    private Changes changes() {
        if (scope == null) {
            return new Changes(NO_AREA, List.of(), 0, null);
        }
        Set<String> sourceIds = new LinkedHashSet<>(scope.pages().sourceIds());
        scope.spaces().forEach(space -> sourceIds.add(space.sourceId()));
        Map<String, Instant> since = new HashMap<>();
        boolean anyMark = false;
        for (String sourceId : sourceIds) {
            List<ChangeUpdate> updates;
            try {
                updates = changeLog.updates(sourceId);
            } catch (UnknownSourceException | AccessDeniedException gone) {
                // Quelle entfernt oder nicht lesbar: sie zählt nicht; jeder andere Fehler bricht den Turn ab.
                continue;
            }
            // Grundlage ist die vorige Marke: das jüngste abgeschlossene Update, gleich wie viele Seiten es hat; bei
            // der Erstbefüllung (oder ohne Anfang) seine eigene Marke. Weiter zurück nie.
            ChangeUpdate latest = updates.stream().filter(update -> !update.open()).findFirst().orElse(null);
            if (latest == null) {
                continue;
            }
            anyMark = true;
            Instant from = latest.initial() || latest.from() == null ? latest.to() : latest.from();
            if (from != null) {
                since.put(sourceId, from);
            }
        }
        if (!anyMark) {
            return new Changes(NO_MARK, List.of(), 0, null);
        }
        Map<PageKey, Instant> changed = new HashMap<>();
        for (var space : scope.spaces()) {
            Instant from = since.get(space.sourceId());
            if (from == null) {
                continue;
            }
            Map<PageKey, Instant> latest;
            try {
                latest = changeLog.latestContentChanges(space.sourceId(), space.spaceKey(), from);
            } catch (UnknownSourceException | AccessDeniedException gone) {
                continue;
            }
            for (Map.Entry<PageKey, Instant> entry : latest.entrySet()) {
                if (scope.contains(entry.getKey())) {
                    changed.merge(entry.getKey(), entry.getValue(), (a, b) -> a.isAfter(b) ? a : b);
                }
            }
        }
        if (changed.isEmpty()) {
            return new Changes(NOTHING_NEW, List.of(), 0, null);
        }
        List<Map.Entry<PageKey, Instant>> ordered = new ArrayList<>(changed.entrySet());
        ordered.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        List<ChatKnowledge.Excerpt> excerpts = new ArrayList<>();
        for (Map.Entry<PageKey, Instant> entry : ordered) {
            if (excerpts.size() >= CHANGED_PAGES) {
                break;
            }
            ChatKnowledge.Excerpt excerpt = page(entry.getKey().sourceId(), entry.getKey().pageId(), null,
                    ChatKnowledge.MAX_HIT_CHARS);
            if (excerpt != null) {
                excerpts.add(new ChatKnowledge.Excerpt((excerpt.title() == null ? entry.getKey().pageId()
                        : excerpt.title()) + " (geändert am " + date(entry.getValue()) + ")", excerpt.sourceId(),
                        excerpt.location(), excerpt.text(), excerpt.pageId()));
            }
        }
        Instant oldest = since.values().stream().min(Instant::compareTo).orElse(null);
        return new Changes(null, excerpts, ordered.size(), oldest);
    }

    /**
     * Die feste Frage an das Modell für „Was ist neu?“: nennt die Grundlage und verbietet erfundene Unterschiede,
     * weil der Chat nur weiß, welche Seiten sich wann geändert haben, nicht was darin (kein Vergleich im Core).
     */
    static String whatsNewQuestion(String question, int total, int withText, Instant since) {
        return question + "\n\nGrundlage: " + total + (total == 1 ? " geänderte Seite" : " geänderte Seiten")
                + (since == null ? "" : " seit dem " + date(since)) + (withText < total
                ? ", davon die " + withText + " jüngsten mit Text im Kontext" : ", mit Text im Kontext")
                + ". Beginne die Antwort mit dieser Grundlage. Nenne die geänderten Seiten mit Titel und Datum, die "
                + "jüngsten zuerst, und fasse ihren jetzigen Inhalt kurz zusammen. Was sich innerhalb einer Seite "
                + "geändert hat, ist nicht bekannt: Erfinde keine Unterschiede und behaupte keine.";
    }

    static String date(Instant at) {
        return CHANGE_DATE.format(at.atZone(ZoneId.systemDefault()));
    }

    // ------------------------------------------------------------------ Wissen

    private ChatKnowledge.Context gather() {
        if (scope == null || scope.pages().isEmpty()) {
            return ChatKnowledge.Context.EMPTY;
        }
        List<ChatKnowledge.Excerpt> excerpts = scope.size() <= WHOLE_PAGES ? wholePages() : searched();
        if (excerpts.isEmpty()) {
            // Überblicksfragen („Worum geht es hier?“) treffen kein Schlagwort: dann die Startseiten des Bereichs.
            excerpts = overview();
        }
        return ChatKnowledge.assemble(excerpts);
    }

    /** Höchstens so viele Titel im Überblick über den Bereich. */
    static final int OVERVIEW_TITLES = 40;

    /**
     * Der Überblick über den Bereich, wenn die Suche nichts fand: die Startseiten (Seiten der Auswahl, deren Eltern
     * nicht dazugehören), die größten Teilbäume zuerst, als Titelliste und mit dem Text der ersten Startseiten.
     */
    private List<ChatKnowledge.Excerpt> overview() {
        List<Root> roots = new ArrayList<>();
        for (var space : scope.spaces()) {
            Map<String, PageHolding> pages;
            try {
                pages = workspace.holdings(space.sourceId(), space.spaceKey()).pages();
            } catch (RuntimeException gone) {
                continue;
            }
            for (PageHolding page : pages.values()) {
                boolean stored = page.state() == HoldingState.LOCAL || page.state() == HoldingState.OUTDATED;
                boolean root = page.parentPageId() == null || !scope.contains(space.sourceId(), page.parentPageId());
                if (stored && root && scope.contains(space.sourceId(), page.pageId())) {
                    roots.add(new Root(space.sourceId(), page.pageId(), page.subtree().pages()));
                }
            }
        }
        roots.sort((a, b) -> Integer.compare(b.size(), a.size()));
        List<ChatKnowledge.Excerpt> excerpts = new ArrayList<>();
        StringBuilder titles = new StringBuilder();
        int listed = 0;
        for (Root root : roots) {
            ChatKnowledge.Excerpt excerpt = page(root.sourceId(), root.pageId(), null, ChatKnowledge.MAX_HIT_CHARS);
            if (excerpt == null) {
                continue;
            }
            if (listed < OVERVIEW_TITLES) {
                titles.append("- ").append(excerpt.title() == null ? root.pageId() : excerpt.title());
                if (root.size() > 1) {
                    titles.append(" (").append(root.size()).append(" Seiten)");
                }
                titles.append('\n');
                listed++;
            }
            if (excerpts.size() < ChatKnowledge.MAX_SOURCES - 1) {
                excerpts.add(excerpt);
            }
        }
        if (titles.length() > 0) {
            String sourceId = roots.get(0).sourceId();
            excerpts.add(0, new ChatKnowledge.Excerpt("Überblick: Startseiten des gewählten Bereichs ("
                    + scope.size() + " Seiten)", sourceId, null, titles.toString()));
        }
        return excerpts;
    }

    private record Root(String sourceId, String pageId, int size) {
    }

    private List<ChatKnowledge.Excerpt> wholePages() {
        List<ChatKnowledge.Excerpt> excerpts = new ArrayList<>();
        for (Map.Entry<String, Set<String>> source : scope.pages().asMap().entrySet()) {
            for (String pageId : source.getValue()) {
                ChatKnowledge.Excerpt excerpt = page(source.getKey(), pageId, null, ChatKnowledge.MAX_PAGE_CHARS);
                if (excerpt != null) {
                    excerpts.add(excerpt);
                }
            }
        }
        return excerpts;
    }

    private List<ChatKnowledge.Excerpt> searched() {
        String text = question.strip().replaceAll("\\s+", " ");
        if (text.length() > MAX_QUERY_CHARS) {
            text = text.substring(0, MAX_QUERY_CHARS);
        }
        KeywordSearch.Query query = new KeywordSearch.Query(text, null, Set.of(), Set.of()).within(scope.pages())
                .withGlossarySynonyms(glossarySynonyms);
        AtomicReference<List<ProgressiveResult.RankedHit>> latest = new AtomicReference<>(List.of());
        ProgressiveSearch running = workspace.progressiveSearch(query, new ProgressiveSearchListener() {
            @Override
            public void ranked(List<ProgressiveResult.RankedHit> hits) {
                latest.set(hits);
            }
        }, neighbors);
        search = running;
        List<ProgressiveResult.RankedHit> hits;
        try {
            hits = running.result().get(SEARCH_SECONDS, TimeUnit.SECONDS).hits();
        } catch (TimeoutException slow) {
            running.cancel();
            hits = latest.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.cancel();
            hits = latest.get();
        } catch (ExecutionException | CancellationException failed) {
            hits = latest.get();
        } finally {
            search = null;
        }
        List<ChatKnowledge.Excerpt> excerpts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ProgressiveResult.RankedHit ranked : hits) {
            KeywordSearch.Hit hit = ranked.hit();
            if (!seen.add(hit.sourceId() + "\u0000" + hit.pageId())) {
                continue;
            }
            ChatKnowledge.Excerpt excerpt = passage(hit);
            if (excerpt == null) {
                excerpt = page(hit.sourceId(), hit.pageId(), hit.snippet(), ChatKnowledge.MAX_HIT_CHARS);
            }
            if (excerpt != null) {
                excerpts.add(excerpt);
            }
            if (excerpts.size() >= ChatKnowledge.MAX_SOURCES) {
                break;
            }
        }
        return excerpts;
    }

    /** Der Absatz des Treffers samt Nachbarn (ADR 0020), wenn der Treffer einen kennt; sonst {@code null}. */
    private ChatKnowledge.Excerpt passage(KeywordSearch.Hit hit) {
        if (hit.passageId() == null) {
            return null;
        }
        try {
            PassageLookup lookup = workspace.passage(hit.passageId());
            StringBuilder text = new StringBuilder();
            for (PagePassages.Passage passage : lookup.passages()) {
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(passage.text());
            }
            return text.isEmpty() ? null : new ChatKnowledge.Excerpt(hit.title(), hit.sourceId(),
                    hit.url() == null ? null : hit.url().toString(),
                    ChatKnowledge.excerpt(text.toString(), ChatKnowledge.MAX_HIT_CHARS), hit.pageId());
        } catch (RuntimeException gone) {
            return null;
        }
    }

    /**
     * Der Lesetext der Seite nur aus dem Cache, gekürzt; {@code null}, wenn sie nicht lokal liegt. Nie ein Abruf bei
     * der Quelle: An den KI-Dienst geht nur, was lokal gespeichert und damit beim Senden freigegeben ist (ADR 0028).
     */
    private ChatKnowledge.Excerpt page(String sourceId, String pageId, String fallback, int maxChars) {
        try {
            DeliveredPage page = workspace.cachedPage(sourceId, pageId).orElse(null);
            if (page == null) {
                return null;
            }
            PageText text = workspace.pageText(page.snapshot());
            PageMetadata metadata = page.header().metadata();
            String body = text == null || text.text().isBlank() ? fallback : text.text();
            if (body == null || body.isBlank()) {
                return null;
            }
            return new ChatKnowledge.Excerpt(metadata.title(), sourceId,
                    metadata.url() == null ? null : metadata.url().toString(), ChatKnowledge.excerpt(body, maxChars),
                    pageId);
        } catch (RuntimeException notLocal) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Zustellung

    /** Die Werkzeuge {@code base} samt den Browser-Werkzeugen, wenn die Websuche an ist. */
    private ChatToolset withWebSearch(ChatToolset base) {
        WebSearch web = webSearch;
        if (web == null) {
            return base;
        }
        return new ChatToolset() {
            @Override
            public List<AiChat.Tool> definitions() {
                List<AiChat.Tool> all = new ArrayList<>(base.definitions());
                all.addAll(web.tools().definitions());
                return all;
            }

            /** Der Thread, der gerade ein Browser-Werkzeug ausführt (wartet er auf eine Rückfrage, weckt Stopp ihn). */
            private volatile Thread browsing;

            @Override
            public String run(AiChat.ToolCall call) {
                if (!web.tools().handles(call.name())) {
                    return base.run(call);
                }
                if (cancelRequested) {
                    return "Abgebrochen.";
                }
                browsing = Thread.currentThread();
                try {
                    if (cancelRequested) {
                        return "Abgebrochen.";
                    }
                    return web.tools().run(call.name(), BROWSER_JSON.readTree(call.argumentsJson() == null
                            || call.argumentsJson().isBlank() ? "{}" : call.argumentsJson()));
                } catch (JacksonException e) {
                    return "Die Argumente sind kein gültiges JSON.";
                } catch (RuntimeException e) {
                    ChatTools.diagnosis.accept("Browser-Werkzeug " + call.name() + " gescheitert: "
                            + e.getClass().getSimpleName());
                    return "Fehler: Das Browser-Werkzeug ist gescheitert (" + e.getClass().getSimpleName() + ").";
                } finally {
                    browsing = null;
                    // Stopp zählt über cancelRequested; die Unterbrechung galt nur der offenen Rückfrage.
                    Thread.interrupted();
                }
            }

            @Override
            public String describe(AiChat.ToolCall call) {
                return web.tools().handles(call.name())
                        ? com.aresstack.spaceharvester.app.chat.browser.BrowserChatTools.describe(call.name())
                        : base.describe(call);
            }

            @Override
            public void cancel() {
                base.cancel();
                // Eine offene Rückfrage endet sofort als „abgebrochen, nicht ausgeführt“ (ChatApprovalPrompt).
                Thread worker = browsing;
                if (worker != null) {
                    worker.interrupt();
                }
            }
        };
    }

    /** Ob der Aufruf ein Browser-Werkzeug ist (diese laufen nacheinander). */
    private boolean browserCall(AiChat.ToolCall call) {
        WebSearch web = webSearch;
        return web != null && web.tools().handles(call.name());
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return work -> {
            Thread thread = new Thread(work, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    // ------------------------------------------------------------------ Unteragenten (Angelo 24.11.2026)

    /** Höchstens so viele Zeichen einer Seite liest ein Unteragent. */
    static final int SUB_AGENT_CHARS = ChatKnowledge.MAX_PAGE_CHARS;

    /**
     * Die Werkzeuge {@code base} samt {@value ChatTools#EVALUATE}, wenn der Turn Wissen hat (Bereich oder interne
     * Suche, auch außerhalb des Suchreiters): mehrere Treffer gehen gleichzeitig an Unteragenten, jeder liest seine Seite im Volltext und
     * liefert nur, was sie zur Frage beiträgt.
     */
    private ChatToolset withSubAgents(ChatToolset base) {
        // Bereichssuche und interne Suche gleich (Angelo 24.11.2026): beide mit Unteragenten, nicht nur der Suchagent.
        if (scope == null && appSources == null) {
            return base;
        }
        return new ChatToolset() {
            @Override
            public List<AiChat.Tool> definitions() {
                List<AiChat.Tool> all = new ArrayList<>(base.definitions());
                all.add(ChatTools.evaluateDefinition());
                return all;
            }

            @Override
            public String run(AiChat.ToolCall call) {
                return ChatTools.EVALUATE.equals(call.name()) ? evaluate(call) : base.run(call);
            }

            @Override
            public String describe(AiChat.ToolCall call) {
                return base.describe(call);
            }

            @Override
            public void cancel() {
                base.cancel();
                subTasks.forEach(AiChat.Task::cancel);
            }
        };
    }

    /** {@value ChatTools#EVALUATE}: je Kennung ein Unteragent, höchstens „Parallele Agenten“ gleichzeitig. */
    private String evaluate(AiChat.ToolCall call) {
        tools.jackson.databind.JsonNode arguments;
        try {
            arguments = BROWSER_JSON.readTree(call.argumentsJson() == null || call.argumentsJson().isBlank() ? "{}"
                    : call.argumentsJson());
        } catch (JacksonException e) {
            return "Die Argumente sind kein gültiges JSON.";
        }
        String asked = arguments.path("frage").asString("").strip();
        if (asked.isEmpty()) {
            asked = question;
        }
        List<String> refs = new ArrayList<>();
        for (tools.jackson.databind.JsonNode ref : arguments.path("kennungen")) {
            String value = ref.asString("").strip();
            if (!value.isEmpty() && !refs.contains(value)) {
                refs.add(value);
            }
        }
        if (refs.isEmpty()) {
            return "Keine Kennungen angegeben; Kennungen liefern die Suchwerkzeuge.";
        }
        String focus = asked;
        String[] answers = new String[refs.size()];
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(agents, refs.size()), daemon("chat-unteragent"));
        try {
            List<java.util.concurrent.Future<?>> running = new ArrayList<>();
            for (int i = 0; i < refs.size(); i++) {
                int index = i;
                running.add(pool.submit(() -> answers[index] = evaluateOne(focus, refs.get(index))));
            }
            for (java.util.concurrent.Future<?> future : running) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    // Die Auswertung dieser Seite fehlt; die anderen zählen.
                }
            }
        } finally {
            pool.shutdownNow();
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < refs.size(); i++) {
            out.append(out.isEmpty() ? "" : "\n\n").append(answers[i] == null ? refs.get(i)
                    + ": Auswertung gescheitert." : answers[i]);
        }
        return out.toString();
    }

    /** Ein Unteragent: liest die Seite im Volltext (nach der KI-Freigabe) und fragt das Modell nach ihrem Beitrag. */
    private String evaluateOne(String asked, String ref) {
        if (cancelRequested) {
            return ref + ": Abgebrochen.";
        }
        ChatTools reader = tools;
        PageKey key = ChatTools.parse(ref);
        if (reader == null || !reader.allows(key)) {
            return ref + ": " + ChatTools.UNAVAILABLE;
        }
        ChatTools.Page page = reader.page(key);
        if (page == null) {
            return ref + ": " + ChatTools.NOT_STORED;
        }
        String spaceKey = reader.spaceOf(key);
        if (spaceKey == null) {
            spaceKey = page.spaceKey();
        }
        try {
            if (spaceKey == null) {
                throw new IllegalStateException("Seite ohne Space");
            }
            releasePageOrThrow(spaceKey, key);
        } catch (RuntimeException failed) {
            return ref + ": Die Seite ließ sich nicht für die KI freigeben; sie geht nicht an das Modell.";
        }
        String text = ChatKnowledge.excerpt(page.text(), SUB_AGENT_CHARS);
        int number = cite(new ChatKnowledge.Excerpt(page.title(), key.sourceId(), page.location(),
                ChatKnowledge.excerpt(page.text(), ChatKnowledge.MAX_HIT_CHARS), key.pageId()), key);
        List<AiChat.Message> messages = List.of(AiChat.Message.system(ChatKnowledge.SUB_AGENT_PROMPT),
                AiChat.Message.user("Frage: " + asked + "\n\nSeite [" + number + "] " + page.title() + "\n\n" + text));
        return "Quelle [" + number + "] " + page.title() + " (" + ref + "):\n" + askSubAgent(messages);
    }

    /**
     * Ein Unteragent der Websuche: wertet eine von {@code browser_open_many} geladene Seite für {@code asked} aus (leer:
     * die Frage des Nutzers). Läuft auf einem Thread des Browser-Werkzeugs, mehrere gleichzeitig.
     */
    public String evaluatePage(String asked, String title, String url, String text) {
        if (cancelRequested) {
            return "Abgebrochen.";
        }
        String focus = asked == null || asked.isBlank() ? question : asked.strip();
        return askSubAgent(List.of(AiChat.Message.system(ChatKnowledge.SUB_AGENT_PROMPT),
                AiChat.Message.user("Frage: " + focus + "\n\nSeite " + (title == null || title.isBlank()
                        ? "(ohne Titel)" : title) + " (" + url + ")\n\n" + (text == null ? "" : text))));
    }

    /** Eine Anfrage eines Unteragenten, gestreamt wie jede KI-Anfrage; wartet auf ihr Ende. */
    private String askSubAgent(List<AiChat.Message> messages) {
        java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        AiChat.Task running = port.stream(new AiChat.Request(messages, model, effort), new AiChat.Listener() {
            @Override
            public void delta(String text) {
            }

            @Override
            public void completed(String text) {
                result.complete(text == null || text.isBlank() ? "(keine Auswertung)" : text.strip());
            }

            @Override
            public void failed(AiChatException error) {
                result.complete("Auswertung gescheitert: " + describe(error).lines().findFirst().orElse(""));
            }

            @Override
            public void cancelled() {
                result.complete("Abgebrochen.");
            }
        });
        subTasks.add(running);
        if (cancelRequested) {
            running.cancel();
        }
        try {
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.cancel();
            return "Abgebrochen.";
        } catch (ExecutionException e) {
            return "Auswertung gescheitert.";
        } finally {
            subTasks.remove(running);
        }
    }

    /** Der Systemhinweis samt den Browser-Regeln, wenn die Websuche an ist. */
    private String withWebSearchRules(String system) {
        WebSearch web = webSearch;
        if (web == null || web.rules() == null || web.rules().isBlank()) {
            return system;
        }
        return (system == null || system.isBlank() ? "" : system + "\n\n") + web.rules();
    }

    /** Beendet die Websuche des Turns einmal, auf eigenem Thread (der Browser schließt). */
    private void endWebSearch() {
        WebSearch web = webSearch;
        if (web == null || web.ended() == null || !webSearchEnded.compareAndSet(false, true)) {
            return;
        }
        Thread closing = new Thread(web.ended(), "chat-websuche-ende");
        closing.setDaemon(true);
        closing.start();
    }

    private void finish(Runnable terminal) {
        busy.close();
        endWebSearch();
        onUi(() -> {
            if (!closed) {
                closed = true;
                terminal.run();
            }
        });
    }

    private void onUi(Runnable update) {
        ui.execute(() -> {
            if (!closed) {
                update.run();
            }
        });
    }

    /**
     * Für Menschen lesbare Fehlermeldung wie {@code ChatServiceBinding.headline} des Enterprise AI Client: erste Zeile
     * die Fehlerart, dann die technische Ursache des Adapters (laut Port-Vertrag ohne Zugangsdaten).
     */
    public static String describe(AiChatException error) {
        String reason = switch (error.kind()) {
            case AUTHENTICATION -> "Anmeldung am KI-Dienst fehlgeschlagen.";
            case CREDENTIALS_UNAVAILABLE -> "Der API-Key des KI-Dienstes ist nicht verfügbar.";
            case RATE_LIMITED -> "Der KI-Dienst ist ausgelastet. Bitte später erneut versuchen.";
            case INVALID_REQUEST -> "Der KI-Dienst hat die Anfrage abgelehnt.";
            case PROVIDER_ERROR -> "Fehler im KI-Dienst.";
            case TRANSPORT -> "Der KI-Dienst ist nicht erreichbar.";
            default -> "Unerwartete Antwort des KI-Dienstes.";
        };
        if (error.statusCode() > 0) {
            reason = reason + " (Status " + error.statusCode() + ")";
        }
        String detail = error.getMessage();
        return detail == null || detail.isBlank() ? reason : reason + "\nTechnische Ursache: " + detail;
    }
}
