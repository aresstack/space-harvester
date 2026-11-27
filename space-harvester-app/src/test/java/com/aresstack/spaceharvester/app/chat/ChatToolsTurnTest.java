package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiChatException;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.TermKnowledge;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0033: Die offene Seite geht im Volltext mit, alles andere im Bereich holt sich das Modell per Werkzeug; jeder
 * Aufruf wird gegen den Bereich geprüft, und lehnt der Dienst Werkzeuge ab, fällt der Chat still auf die Suche zurück.
 */
class ChatToolsTurnTest {

    @TempDir
    Path home;

    private AppContext context;
    private Workspace workspace;
    private PageSelection space;

    @BeforeEach
    void start() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("datenhaus.md"), "# Datenhaus\n\nJedes Datenprodukt hat ein "
                + "Berechtigungsmanagement.\n");
        Files.writeString(files.resolve("nachtlauf.md"), "# Nachtlauf\n\nDer Nachtlauf bricht bei vollem Spool ab.\n");
        Files.writeString(files.resolve("glossar.md"), "# Glossar\n\nSpool: Ablage der Ausgaben.\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: handbuch
                      type: files
                      title: Handbuch
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        workspace = context.runtime().workspace();
        String key = workspace.spaces("handbuch").get(0).key();
        workspace.startSync("handbuch", key, SyncMode.FULL);
        space = await(() -> {
            PageSelection resolved = workspace.resolveSelection(Selection.of(Selection.Root.space("handbuch", key)));
            return resolved.counts().stored() >= 3 ? resolved : null;
        });
        ChatTurn.WITHOUT_TOOLS.clear();
    }

    @AfterEach
    void shutdown() {
        ChatTurn.WITHOUT_TOOLS.clear();
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void theInternalSearchFindsAndReadsPagesOutsideTheScopeOnlyInReleasedSources() throws Exception {
        PageKey open = page("datenhaus");
        PageKey other = page("nachtlauf");
        com.aresstack.spaceharvester.application.holdings.PageSelection onlyOpen =
                new com.aresstack.spaceharvester.application.holdings.PageSelection(
                        com.aresstack.spaceharvester.application.snapshot.PageSet.of(
                                java.util.Map.of(open.sourceId(), List.of(open.pageId()))),
                        space.counts(), space.spaces(), space.complete());
        // Der Suchindex entsteht nach dem Sync im Hintergrund: erst warten, bis die globale Suche trifft.
        ChatTools probe = new ChatTools(workspace, onlyOpen, java.util.Set.of(), new ChatTools.Sources() {
            @Override
            public int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
                return 1;
            }

            @Override
            public void delivered(String spaceKey, PageKey key) {
            }
        }).appSearch(() -> java.util.Set.of("handbuch"));
        await(() -> probe.run(new AiChat.ToolCall("p", ChatTools.APP_SEARCH, "{\"text\":\"Spool\"}"))
                .contains(ChatTools.ref(other)) ? true : null);
        assertThat(probe.run(new AiChat.ToolCall("q", ChatTools.APP_SEARCH, "{\"text\":\"Spool\","
                + "\"quellen\":[\"fremd\"]}"))).startsWith("Keine der genannten Wissensquellen");
        // Eine lange Frage wird gekürzt statt abgelehnt (Codex-Befund zu #1260).
        assertThat(probe.run(new AiChat.ToolCall("l", ChatTools.APP_SEARCH, "{\"text\":\"Spool "
                + "Nachtlauf ".repeat(40) + "\"}"))).contains(ChatTools.ref(other));
        assertThat(ChatTools.clampQuery("a ".repeat(300)).length())
                .isLessThanOrEqualTo(com.aresstack.spaceharvester.application.query.KeywordSearch.MAX_TEXT_LENGTH);
        // Markdown-Dateien sind Text, keine Wiki-Seiten: Der Filter nach Arten greift.
        assertThat(probe.run(new AiChat.ToolCall("r", ChatTools.APP_SEARCH, "{\"text\":\"Spool\","
                + "\"arten\":[\"PAGE\"]}"))).startsWith("Keine Treffer");

        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(new AiChat.ToolCall("c1", ChatTools.APP_SEARCH,
                        "{\"text\":\"Spool\",\"arten\":[\"TEXT\"]}"))),
                (request, listener) -> listener.toolCalls("", List.of(new AiChat.ToolCall("c2", ChatTools.READ,
                        "{\"kennung\":\"" + ChatTools.ref(other) + "\"}"))),
                (request, listener) -> listener.completed("Bei vollem Spool [2].")));
        Events events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Warum bricht der Nachtlauf ab?", onlyOpen,
                "Datenhaus", events, Runnable::run).knowledgeByTools(open)
                .appSearch(() -> java.util.Set.of("handbuch")).releasing(releases()).start();
        assertThat(events.done.await(60, TimeUnit.SECONDS)).isTrue();

        AiChat.Request first = port.requests.get(0);
        assertThat(first.tools()).extracting(AiChat.Tool::name).contains(ChatTools.APP_SEARCH);
        assertThat(first.messages().get(0).text()).contains(ChatKnowledge.APP_SEARCH_PROMPT);
        List<AiChat.Message> afterSearch = port.requests.get(1).messages();
        assertThat(afterSearch.get(afterSearch.size() - 1).text()).contains(ChatTools.ref(other))
                .contains("Quelle handbuch");
        List<AiChat.Message> afterRead = port.requests.get(2).messages();
        assertThat(afterRead.get(afterRead.size() - 1).text()).startsWith("Quelle [2]").contains("vollem Spool");
        assertThat(released()).contains(other);
    }

    @Test
    void withoutTheSwitchThereIsNoInternalSearchAndItSearchesNoBlockedSource() throws Exception {
        ScriptedChat port = new ScriptedChat(List.of((request, listener) -> listener.completed("Gut.")));
        Events events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Hallo", space, "Handbuch", events, Runnable::run)
                .knowledgeByTools(null).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(port.requests.get(0).tools()).extracting(AiChat.Tool::name).doesNotContain(ChatTools.APP_SEARCH);
        assertThat(port.requests.get(0).messages().get(0).text()).doesNotContain(ChatKnowledge.APP_SEARCH_PROMPT);

        ChatTools blocked = new ChatTools(workspace, space, java.util.Set.of(), new ChatTools.Sources() {
            @Override
            public int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
                return 1;
            }

            @Override
            public void delivered(String spaceKey, PageKey key) {
            }
        }).appSearch(java.util.Set::of);
        assertThat(blocked.run(new AiChat.ToolCall("x", ChatTools.APP_SEARCH, "{\"text\":\"Spool\"}")))
                .isEqualTo("Keine Wissensquelle ist für die KI freigegeben.");
    }

    @Test
    void theOpenPageGoesAlongEvenWhenTheResolvedScopeDoesNotKnowIt() throws Exception {
        // Ein Ticket aus der Suche, das die Auflösung des Bereichs nicht kennt: es ist trotzdem das, was man sieht.
        PageKey open = page("datenhaus");
        PageKey other = page("nachtlauf");
        com.aresstack.spaceharvester.application.holdings.PageSelection elsewhere =
                new com.aresstack.spaceharvester.application.holdings.PageSelection(
                        com.aresstack.spaceharvester.application.snapshot.PageSet.of(
                                java.util.Map.of(other.sourceId(), List.of(other.pageId()))),
                        space.counts(), space.spaces(), space.complete());
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(new AiChat.ToolCall("c1", ChatTools.READ,
                        "{\"kennung\":\"" + ChatTools.ref(open) + "\",\"versatz\":10}"))),
                (request, listener) -> listener.completed("Steht oben [1].")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Was steht auf der aktuellen Seite?", elsewhere,
                "Ticket", events, Runnable::run).knowledgeByTools(open).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(port.requests.get(0).messages().get(0).text()).contains("[1] ").contains("Berechtigungsmanagement")
                .doesNotContain(ChatKnowledge.NO_OPEN_PAGE);
        List<AiChat.Message> messages = port.requests.get(1).messages();
        assertThat(messages.get(messages.size() - 1).text()).as("weiterlesen in der offenen Seite")
                .doesNotContain(ChatTools.UNAVAILABLE);
    }

    @Test
    void theOpenPageGoesAlongAndOtherPagesAreReadByToolWithTheirNumber() throws Exception {
        PageKey open = page("datenhaus");
        PageKey other = page("nachtlauf");
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c1", ChatTools.READ, "{\"kennung\":\"" + ChatTools.ref(other) + "\"}"))),
                (request, listener) -> {
                    listener.delta("Bei vollem Spool [2].");
                    listener.completed("Bei vollem Spool [2].");
                }));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Warum bricht der Nachtlauf ab?", space,
                "Handbuch · 3 Seiten", events, Runnable::run).knowledgeByTools(open).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(events.completed).isEqualTo("Bei vollem Spool [2].");
        AiChat.Request first = port.requests.get(0);
        assertThat(first.tools()).extracting(AiChat.Tool::name)
                .containsExactlyInAnyOrder(ChatTools.SEARCH, ChatTools.LIST, ChatTools.READ, ChatTools.TERM,
                        ChatTools.EVALUATE);
        assertThat(first.messages().get(0).text()).startsWith(ChatKnowledge.TOOL_PROMPT)
                .contains("[1] ").contains("Berechtigungsmanagement").contains(ChatTools.ref(open))
                .doesNotContain("vollem Spool");
        AiChat.Request second = port.requests.get(1);
        AiChat.Message result = second.messages().get(second.messages().size() - 1);
        assertThat(result.role()).isEqualTo(AiChat.Role.TOOL);
        assertThat(result.toolCallId()).isEqualTo("c1");
        assertThat(result.text()).startsWith("Quelle [2]").contains("vollem Spool");
        assertThat(events.context.accepted()).extracting(ChatKnowledge.Excerpt::text)
                .satisfiesExactly(a -> assertThat(a).contains("Berechtigungsmanagement"),
                        b -> assertThat(b).contains("vollem Spool"));
        assertThat(events.context.accepted()).extracting(ChatKnowledge.Excerpt::pageId)
                .as("die Quellen kennen ihre Seite, für den Verlauf").containsExactly(open.pageId(), other.pageId());
        assertThat(events.using).contains("Liest eine Seite");
    }

    @Test
    void aPageOutsideTheAreaAndAnUnknownPageGetTheSameAnswer() throws Exception {
        PageKey open = page("datenhaus");
        PageSelection onlyOpen = new PageSelection(PageSet.of(Map.of(open.sourceId(), List.of(open.pageId()))),
                space.counts(), space.spaces(), space.complete());
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c1", ChatTools.READ,
                                "{\"kennung\":\"" + ChatTools.ref(page("nachtlauf")) + "\"}"),
                        new AiChat.ToolCall("c2", ChatTools.READ, "{\"kennung\":\"handbuch/gibt-es-nicht.md\"}"),
                        new AiChat.ToolCall("c3", ChatTools.SEARCH, "{\"text\":\"Spool\"}"),
                        new AiChat.ToolCall("c4", ChatTools.LIST, "{}"))),
                (request, listener) -> listener.completed("Steht hier nicht.")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Was ist ein Spool?", onlyOpen, "Datenhaus",
                events, Runnable::run).knowledgeByTools(open).start();
        assertThat(events.done.await(60, TimeUnit.SECONDS)).isTrue();

        List<AiChat.Message> messages = port.requests.get(1).messages();
        List<String> results = messages.subList(messages.size() - 4, messages.size()).stream()
                .map(AiChat.Message::text).toList();
        assertThat(results.get(0)).isEqualTo(ChatTools.UNAVAILABLE);
        assertThat(results.get(1)).isEqualTo(ChatTools.UNAVAILABLE);
        assertThat(results.get(2)).doesNotContain("nachtlauf").doesNotContain("glossar");
        assertThat(results.get(3)).contains(ChatTools.ref(open)).doesNotContain("nachtlauf");
    }

    @Test
    void theListGoesNodeByNodeAndAWholeSpaceSendsNoPageText() throws Exception {
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c1", ChatTools.LIST, "{}"))),
                (request, listener) -> listener.completed("Drei Seiten.")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Was gibt es hier?", space, "Handbuch",
                events, Runnable::run).knowledgeByTools(null).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(port.requests.get(0).messages().get(0).text()).contains(ChatKnowledge.NO_OPEN_PAGE)
                .doesNotContain("Berechtigungsmanagement");
        List<AiChat.Message> messages = port.requests.get(1).messages();
        assertThat(messages.get(messages.size() - 1).text()).startsWith("Startseiten des Bereichs, Einträge 1 bis 3 von 3")
                .contains(ChatTools.ref(page("datenhaus"))).contains(ChatTools.ref(page("glossar")));
    }

    // ------------------------------------------------------------------ Sammlung (ADR 0034 §5)

    @Test
    void aFolderListsItsSubfoldersAsVirtualNodesAndOnlyItsFavoritesPages() {
        String key = workspace.spaces("handbuch").get(0).key();
        PageKey datenhaus = page("datenhaus");
        PageKey nachtlauf = page("nachtlauf");
        Selection.Root top = Selection.Root.subtree("handbuch", key, datenhaus.pageId());
        Selection.Root below = Selection.Root.subtree("handbuch", key, nachtlauf.pageId());
        PageSelection scope = workspace.resolveSelection(new Selection(List.of(top, below)));
        CollectionNodes nodes = folderId -> folderId == null
                ? new CollectionNodes.Content(List.of(new CollectionNodes.Folder("f-2", "Betrieb")), List.of(top))
                : folderId.equals("f-2") ? new CollectionNodes.Content(List.of(), List.of(below)) : null;
        ChatTools tools = new ChatTools(workspace, scope, java.util.Set.of(), sources(new ArrayList<>()))
                .collection(nodes);

        String first = tools.list("", 0);
        String sub = tools.list(ChatTools.FOLDER + "f-2", 0);

        assertThat(first).startsWith("Favoriten des Bereichs, Einträge 1 bis 2 von 2")
                .contains("- " + ChatTools.FOLDER + "f-2 | Betrieb | Ordner")
                .contains("- " + ChatTools.ref(datenhaus) + " | ")
                .doesNotContain(ChatTools.ref(nachtlauf)).doesNotContain("glossar");
        assertThat(first.indexOf(ChatTools.FOLDER)).as("Ordner zuerst").isLessThan(first.indexOf(datenhaus.pageId()));
        assertThat(sub).startsWith("Inhalt des Ordners").contains(ChatTools.ref(nachtlauf))
                .doesNotContain(ChatTools.ref(datenhaus));
        assertThat(tools.list(ChatTools.FOLDER + "fremd", 0)).as("geprüft wie allows()")
                .isEqualTo(ChatTools.UNAVAILABLE);
        assertThat(tools.toolDefinitions().stream().filter(tool -> tool.name().equals(ChatTools.LIST))
                .findFirst().orElseThrow().description()).contains(ChatTools.FOLDER);
    }

    @Test
    void aRemovedFavoriteIsRejectedOnTheNextQuestion() throws Exception {
        // ADR 0034 §5: Der Ordner ändert sich während des Chats; ab der nächsten Frage gilt der neue Stand.
        String key = workspace.spaces("handbuch").get(0).key();
        PageKey datenhaus = page("datenhaus");
        PageKey nachtlauf = page("nachtlauf");
        com.aresstack.spaceharvester.app.ui.Favorites favorites =
                new com.aresstack.spaceharvester.app.ui.Favorites(() -> home.resolve("favorites.json"));
        String folder = favorites.createFolder("Betrieb", null);
        for (PageKey page : List.of(datenhaus, nachtlauf)) {
            favorites.toggle(new com.aresstack.spaceharvester.app.ui.Favorites.Favorite("handbuch", key,
                    page.pageId(), page.pageId(), List.of(), List.of()), folder);
        }
        String read = "{\"kennung\":\"" + ChatTools.ref(nachtlauf) + "\"}";

        String before = ask(favorites, folder, read);
        favorites.toggle(new com.aresstack.spaceharvester.app.ui.Favorites.Favorite("handbuch", key,
                nachtlauf.pageId(), null, List.of(), List.of()), folder);
        String after = ask(favorites, folder, read);

        assertThat(before).contains("vollem Spool");
        assertThat(after).isEqualTo(ChatTools.UNAVAILABLE);
    }

    /** Eine Frage im Ordner: seine Favoriten wie beim Senden aufgelöst, das Modell liest eine Seite. */
    private String ask(com.aresstack.spaceharvester.app.ui.Favorites favorites, String folder, String read)
            throws Exception {
        List<Selection.Root> roots = favorites.favoritesIn(folder, true).stream()
                .map(com.aresstack.spaceharvester.app.ui.Favorites.Favorite::root).toList();
        PageSelection scope = workspace.resolveSelection(new Selection(roots));
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c1", ChatTools.READ, read))),
                (request, listener) -> listener.completed("Fertig.")));
        Events events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Warum bricht der Nachtlauf ab?", scope,
                "Ordner Betrieb", events, Runnable::run).knowledgeByTools(null).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();
        List<AiChat.Message> messages = port.requests.get(1).messages();
        return messages.get(messages.size() - 1).text();
    }

    @Test
    void eachToolCallStartsAndFinishesOnTheEventThreadSoTheChatCanShowACloud() throws Exception {
        // Wie die Werkzeugwolken im EAC: Beginn und Ende jedes Aufrufs, in dieser Reihenfolge, mit derselben Kennung.
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(new AiChat.ToolCall("c1", ChatTools.LIST, "{}"),
                        new AiChat.ToolCall("c2", ChatTools.LIST, "{}"))),
                (request, listener) -> listener.completed("Fertig.")));
        Events events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Frage", space, "Handbuch", events, Runnable::run)
                .knowledgeByTools(null).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(events.tools).hasSize(4);
        String first = events.tools.get(0).substring(("start " + ChatTools.LIST + " ").length());
        String second = events.tools.get(2).substring(("start " + ChatTools.LIST + " ").length());
        assertThat(events.tools).containsExactly("start " + ChatTools.LIST + " " + first, "end " + first,
                "start " + ChatTools.LIST + " " + second, "end " + second);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void everyRoundOffersTheToolsWithoutAFixedRoundLimit() throws Exception {
        // Angelo 26.11.2026: keine feste Rundengrenze; früher ging nach 8 Runden eine Anfrage still ohne Werkzeuge
        // hinaus, und das Modell schrieb den nächsten Aufruf als Text in die Antwort.
        int rounds = 25;
        List<BiConsumer<AiChat.Request, AiChat.Listener>> script = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            script.add((request, listener) -> listener.toolCalls("", List.of(
                    new AiChat.ToolCall("c", ChatTools.LIST, "{}"))));
        }
        script.add((request, listener) -> listener.completed("Fertig."));
        ScriptedChat port = new ScriptedChat(script);
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Frage", space, "Handbuch", events, Runnable::run)
                .knowledgeByTools(null).start();
        assertThat(events.done.await(60, TimeUnit.SECONDS)).isTrue();

        assertThat(port.requests).hasSize(rounds + 1);
        assertThat(port.requests).allSatisfy(request -> assertThat(request.tools()).isNotEmpty());
        assertThat(events.completed).isEqualTo("Fertig.");
    }

    @Test
    void afterTheSetRoundsWithoutAnythingNewTheModelIsAskedOpenlyToAnswerAndKeepsItsTools() throws Exception {
        // Stillstand wie in askai (ResearchLead.endRule): dieselbe Liste dreimal hintereinander ist nichts Neues.
        List<BiConsumer<AiChat.Request, AiChat.Listener>> script = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            script.add((request, listener) -> listener.toolCalls("", List.of(
                    new AiChat.ToolCall("c", ChatTools.LIST, "{}"))));
        }
        script.add((request, listener) -> listener.completed("Fertig."));
        ScriptedChat port = new ScriptedChat(script);
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Frage", space, "Handbuch", events, Runnable::run)
                .knowledgeByTools(null).quietRounds(2).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(port.requests).hasSize(4);
        assertThat(port.requests).allSatisfy(request -> assertThat(request.tools()).isNotEmpty());
        assertThat(last(port.requests.get(1)).role()).isEqualTo(AiChat.Role.TOOL);
        assertThat(last(port.requests.get(2)).role()).isEqualTo(AiChat.Role.TOOL);
        assertThat(last(port.requests.get(3)).text()).isEqualTo(String.format(ChatTurn.STANDSTILL_INSTRUCTION, 2));
        assertThat(events.completed).isEqualTo("Fertig.");
    }

    private static AiChat.Message last(AiChat.Request request) {
        return request.messages().get(request.messages().size() - 1);
    }

    @Test
    void aToolCallWrittenAsTextRunsAsACallAndNeverShowsInTheBubble() throws Exception {
        // Angelo 26.11.2026: „We will call the search. {"cmd":["im_bereich_suchen","High Level"]}“ stand als Antwort
        // da, gesucht wurde nicht. Erfundene Daten: Suche nach „Nachtlauf“.
        String leaked = "We will call the search.\n{\"cmd\":[\"" + ChatTools.SEARCH + "\",\"Nachtlauf\"]}";
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> {
                    listener.delta("We will call the search.\n");
                    listener.delta(leaked.substring("We will call the search.\n".length()));
                    listener.completed(leaked);
                },
                (request, listener) -> {
                    listener.delta("Der Nachtlauf bricht bei vollem Spool ab [1].");
                    listener.completed("Der Nachtlauf bricht bei vollem Spool ab [1].");
                }));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Warum bricht der Nachtlauf ab?", space, "Handbuch",
                events, Runnable::run).knowledgeByTools(null).releasing(releases()).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(port.requests).hasSize(2);
        List<AiChat.Message> second = port.requests.get(1).messages();
        AiChat.Message call = second.get(second.size() - 2);
        assertThat(call.toolCalls()).singleElement().satisfies(toolCall -> {
            assertThat(toolCall.name()).isEqualTo(ChatTools.SEARCH);
            assertThat(toolCall.argumentsJson()).isEqualTo("{\"text\":\"Nachtlauf\"}");
        });
        assertThat(call.text()).isEmpty();
        assertThat(second.get(second.size() - 1).role()).isEqualTo(AiChat.Role.TOOL);
        assertThat(second.get(second.size() - 1).text()).isNotBlank().isNotEqualTo("Das Werkzeug ist gescheitert.");
        assertThat(String.join("", events.deltas)).isEqualTo("Der Nachtlauf bricht bei vollem Spool ab [1].");
        assertThat(events.completed).isEqualTo("Der Nachtlauf bricht bei vollem Spool ab [1].");
    }

    @Test
    void aCallThatIsOnlyAnnouncedGetsOneReminderAndStaysOutOfTheBubble() throws Exception {
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> {
                    listener.delta("We will perform a silent tool call.");
                    listener.completed("We will perform a silent tool call.");
                },
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c1", ChatTools.SEARCH, "{\"text\":\"Nachtlauf\"}"))),
                (request, listener) -> listener.completed("Bei vollem Spool [1].")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Warum bricht der Nachtlauf ab?", space, "Handbuch",
                events, Runnable::run).knowledgeByTools(null).releasing(releases()).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(port.requests).hasSize(3);
        List<AiChat.Message> reminded = port.requests.get(1).messages();
        assertThat(reminded.get(reminded.size() - 1).text()).isEqualTo(ChatTurn.TOOL_CHANNEL_REMINDER);
        assertThat(reminded).noneMatch(message -> message.text().contains("silent tool call"));
        assertThat(port.requests.get(1).tools()).isNotEmpty();
        assertThat(events.deltas).noneMatch(delta -> delta.contains("tool call"));
        assertThat(events.completed).isEqualTo("Bei vollem Spool [1].");
    }

    @Test
    void anAnswerThatOnlyKeepsAnnouncingEndsWithAReadableMessage() throws Exception {
        BiConsumer<AiChat.Request, AiChat.Listener> announcing = (request, listener) -> {
            listener.delta("We need to call the tool correctly.");
            listener.completed("We need to call the tool correctly.");
        };
        ScriptedChat port = new ScriptedChat(List.of(announcing, announcing));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Frage", space, "Handbuch", events, Runnable::run)
                .knowledgeByTools(null).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(port.requests).hasSize(2);
        assertThat(events.failed).isEqualTo(ChatTurn.TOOL_TEXT_FAILED);
        assertThat(events.deltas).isEmpty();
    }

    @Test
    void earlierAnswersThatWereOnlyToolCallsAsTextDoNotGoBackToTheModel() throws Exception {
        // Sonst ahmt das Modell sie in jeder folgenden Frage nach.
        ScriptedChat port = new ScriptedChat(List.of((request, listener) -> listener.completed("Antwort.")));
        Events events = new Events();
        List<ChatRecord.Message> history = List.of(
                ChatRecord.Message.user("Was ist der Plan?", 1),
                ChatRecord.Message.assistant("Der Plan steht auf der Startseite [1].", false, 2),
                ChatRecord.Message.user("Und das Ziel?", 3),
                ChatRecord.Message.assistant("We will call the search.\n{\"cmd\":[\"" + ChatTools.SEARCH
                        + "\",\"Ziel\"]}", false, 4),
                ChatRecord.Message.user("Das war kein Aufruf", 5),
                ChatRecord.Message.assistant("We will perform a silent tool call.", false, 6));

        new ChatTurn(workspace, port, "modell", null, history, "Und jetzt?", space, "Handbuch", events, Runnable::run)
                .knowledgeByTools(null).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        List<AiChat.Message> sent = port.requests.get(0).messages();
        assertThat(sent).extracting(AiChat.Message::text).contains("Der Plan steht auf der Startseite [1].")
                .noneMatch(text -> text.contains("cmd") || text.contains("silent tool call"));
        assertThat(sent).filteredOn(message -> message.role() == AiChat.Role.USER).extracting(AiChat.Message::text)
                .containsExactly("Was ist der Plan?", "Und das Ziel?", "Das war kein Aufruf", "Und jetzt?");
    }

    @Test
    void withoutAreaAndWithoutAnySearchTheAgentAnswersFromItsOwnKnowledge() throws Exception {
        // Angelo 24.11.2026: Ohne Auswahl und ohne zugeschaltete Suche antwortet der Chat aus eigenem Wissen.
        ScriptedChat port = new ScriptedChat(List.of((request, listener) -> listener.completed("Aus dem Kopf.")));
        Events events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Was ist JCL?", null, null, events, Runnable::run)
                .knowledgeByTools(null).searchAgent().releasing(releases()).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(port.requests).singleElement().satisfies(request -> {
            assertThat(request.tools()).isEmpty();
            assertThat(request.messages().get(0).text()).isEqualTo(ChatKnowledge.OWN_KNOWLEDGE_PROMPT);
        });
        assertThat(events.completed).isEqualTo("Aus dem Kopf.");
    }

    @Test
    void withoutAreaTheInternalSearchAgentOnlySearchesAndReadsAndKeepsGoingUntilItIsDone() throws Exception {
        // Ohne Bereich keine Bereichswerkzeuge (keine langsame Suche im Bereich); die interne Suche recherchiert, bis
        // sie fertig ist.
        int budget = 12;
        List<BiConsumer<AiChat.Request, AiChat.Listener>> script = new ArrayList<>();
        for (int i = 0; i < budget; i++) {
            script.add((request, listener) -> listener.toolCalls("", List.of(new AiChat.ToolCall("c",
                    ChatTools.APP_SEARCH, "{\"text\":\"Spool\",\"quellen\":[\"fremd\"]}"))));
        }
        script.add((request, listener) -> listener.completed("Gefunden."));
        ScriptedChat port = new ScriptedChat(script);
        Events events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Warum bricht der Nachtlauf ab?", null, null,
                events, Runnable::run).knowledgeByTools(null).appSearch(() -> java.util.Set.of("handbuch"))
                .searchAgent().releasing(releases()).start();
        assertThat(events.done.await(60, TimeUnit.SECONDS)).isTrue();

        AiChat.Request first = port.requests.get(0);
        assertThat(first.tools()).extracting(AiChat.Tool::name)
                .containsExactly(ChatTools.READ, ChatTools.APP_SEARCH, ChatTools.EVALUATE);
        assertThat(first.messages().get(0).text()).startsWith(ChatKnowledge.AGENT_PROMPT)
                .contains(ChatKnowledge.AGENT_APP_SEARCH_PROMPT).contains(ChatKnowledge.ATTACHMENTS_PROMPT)
                .doesNotContain(ChatKnowledge.AGENT_AREA_PROMPT);
        assertThat(port.requests).hasSize(budget + 1);
        assertThat(port.requests).allSatisfy(request -> assertThat(request.tools()).isNotEmpty());
        assertThat(events.completed).isEqualTo("Gefunden.");
    }

    @Test
    void theSearchAgentLetsSubAgentsEvaluateSeveralHitsAtOnceEachReadingItsReleasedPage() throws Exception {
        // Angelo 24.11.2026: Jeder Treffer wird von einem eigenen Agenten im Hintergrund ausgewertet, gleichzeitig bis
        // „Parallele Agenten (intern)“.
        PageKey night = page("nachtlauf");
        PageKey house = page("datenhaus");
        CountDownLatch bothRunning = new CountDownLatch(2);
        List<AiChat.Request> requests = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger mainRound = new java.util.concurrent.atomic.AtomicInteger();
        AiChat port = (request, listener) -> {
            requests.add(request);
            if (ChatKnowledge.SUB_AGENT_PROMPT.equals(request.messages().get(0).text())) {
                bothRunning.countDown();
                try {
                    // Erst antworten, wenn beide Unteragenten gleichzeitig laufen.
                    assertThat(bothRunning.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String page = request.messages().get(1).text();
                listener.completed(page.contains("vollem Spool") ? "Bricht bei vollem Spool ab." : "Trägt nichts bei.");
                return () -> { };
            }
            if (mainRound.getAndIncrement() == 0) {
                listener.toolCalls("", List.of(new AiChat.ToolCall("c1", ChatTools.EVALUATE,
                        "{\"kennungen\":[\"" + ChatTools.ref(night) + "\",\"" + ChatTools.ref(house) + "\"]}")));
            } else {
                listener.completed("Fertig [1].");
            }
            return () -> { };
        };
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Warum bricht der Nachtlauf ab?", space, "Handbuch",
                events, Runnable::run).knowledgeByTools(null).searchAgent().parallelAgents(4)
                .releasing(releases()).start();
        assertThat(events.done.await(60, TimeUnit.SECONDS)).isTrue();

        assertThat(events.completed).isEqualTo("Fertig [1].");
        AiChat.Request first = requests.get(0);
        assertThat(first.tools()).extracting(AiChat.Tool::name).contains(ChatTools.EVALUATE);
        assertThat(first.messages().get(0).text()).contains(ChatKnowledge.AGENT_EVALUATE_PROMPT);
        AiChat.Request last = requests.get(requests.size() - 1);
        AiChat.Message result = last.messages().get(last.messages().size() - 1);
        assertThat(result.toolCallId()).isEqualTo("c1");
        assertThat(result.text()).contains("Bricht bei vollem Spool ab.").contains("Trägt nichts bei.")
                .contains(ChatTools.ref(night)).contains(ChatTools.ref(house)).contains("Quelle [");
        assertThat(result.text().indexOf(ChatTools.ref(night)))
                .as("die Auswertungen in der Reihenfolge der Kennungen")
                .isLessThan(result.text().indexOf(ChatTools.ref(house)));
        assertThat(released()).as("was ans Modell ging, ist freigegeben").contains(night, house);
        assertThat(events.context.accepted()).hasSize(2);
    }

    @Test
    void theAreaSearchOutsideTheSearchTabAlsoHasSubAgentsAndSeveralCallsOfARoundKeepTheirOrder() throws Exception {
        // Angelo 24.11.2026: Bereichssuche und interne Suche verhalten sich gleich, beide mit Hintergrund-Agenten.
        PageKey night = page("nachtlauf");
        PageKey house = page("datenhaus");
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c1", ChatTools.READ, "{\"kennung\":\"" + ChatTools.ref(night) + "\"}"),
                        new AiChat.ToolCall("c2", ChatTools.READ, "{\"kennung\":\"" + ChatTools.ref(house) + "\"}"))),
                (request, listener) -> listener.completed("Fertig.")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Frage", space, "Handbuch", events, Runnable::run)
                .knowledgeByTools(null).parallelAgents(4).releasing(releases()).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(port.requests.get(0).tools()).extracting(AiChat.Tool::name).contains(ChatTools.EVALUATE);
        assertThat(port.requests.get(0).messages().get(0).text()).contains(ChatKnowledge.AGENT_EVALUATE_PROMPT);
        List<AiChat.Message> messages = port.requests.get(1).messages();
        assertThat(messages.subList(messages.size() - 2, messages.size()))
                .satisfiesExactly(a -> assertThat(a.toolCallId()).isEqualTo("c1"),
                        b -> assertThat(b.toolCallId()).isEqualTo("c2"));
        assertThat(messages.get(messages.size() - 2).text()).contains("vollem Spool");
        assertThat(messages.get(messages.size() - 1).text()).contains("Berechtigungsmanagement");
    }

    /** Browser-Werkzeuge für Tests: browser_navigate meldet eine erfundene Seite. */
    private static ChatTurn.WebSearch web() {
        return new ChatTurn.WebSearch(new com.aresstack.spaceharvester.app.chat.browser.BrowserChatTools(List.of(
                com.aresstack.enterpriseai.proasteion.katagogion.McpToolContribution.of("browser_navigate",
                        "Öffnet eine Adresse",
                        call -> com.aresstack.enterpriseai.proasteion.katagogion.McpToolResult.ok("Seite geöffnet"),
                        com.aresstack.enterpriseai.proasteion.katagogion.McpToolParameter.string("url", true,
                                "Adresse")))), null, null, null);
    }

    @Test
    void withWebSearchTheRoleNoLongerLimitsTheModelToTheArea() throws Exception {
        // Angelo 27.11.2026: Mit eingeschalteter Websuche lehnte der Chat eine genannte Adresse ab, weil die Rolle
        // „Du erreichst nur den gewählten Bereich“ sagte.
        PageKey open = page("datenhaus");
        ScriptedChat port = new ScriptedChat(List.of((request, listener) -> listener.completed("Gesucht.")));
        Events events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Suche auf https://intranet.example/ nach Urlaub",
                space, "Handbuch", events, Runnable::run).knowledgeByTools(open).webSearch(web()).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        AiChat.Request first = port.requests.get(0);
        assertThat(first.tools()).extracting(AiChat.Tool::name).contains("browser_navigate", ChatTools.SEARCH);
        assertThat(first.messages().get(0).text()).contains(ChatKnowledge.WEB_SEARCH_PROMPT)
                .doesNotContain(ChatKnowledge.AREA_ONLY).contains(ChatKnowledge.NO_INVENTION);

        // Ohne Websuche bleibt die Grenze.
        ScriptedChat without = new ScriptedChat(List.of((request, listener) -> listener.completed("Bereich.")));
        Events plain = new Events();
        new ChatTurn(workspace, without, "modell", null, List.of(), "Frage", space, "Handbuch", plain,
                Runnable::run).knowledgeByTools(open).start();
        assertThat(plain.done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(without.requests.get(0).messages().get(0).text()).contains(ChatKnowledge.AREA_ONLY)
                .doesNotContain(ChatKnowledge.WEB_SEARCH_PROMPT);
    }

    @Test
    void withWebSearchARejectionOfToolsIsReportedAndNeverAnsweredSilentlyWithoutThem() throws Exception {
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.failed(new AiChatException(AiChatException.Kind.INVALID_REQUEST, 400,
                        "HTTP 400", null))));
        Events events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Suche im Web", space, "Handbuch", events,
                Runnable::run).knowledgeByTools(null).webSearch(web()).start();
        assertThat(events.done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(events.failed).startsWith(ChatTurn.NO_WEB_TOOLS);
        assertThat(port.requests).hasSize(1);

        // Auch wenn das Modell als werkzeuglos gemerkt ist: Mit Websuche geht die Frage mit Werkzeugen hinaus.
        assertThat(ChatTurn.WITHOUT_TOOLS).contains("modell");
        ScriptedChat again = new ScriptedChat(List.of((request, listener) -> listener.completed("Mit Browser.")));
        Events next = new Events();
        new ChatTurn(workspace, again, "modell", null, List.of(), "Noch einmal", space, "Handbuch", next,
                Runnable::run).knowledgeByTools(null).webSearch(web()).start();
        assertThat(next.done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(again.requests.get(0).tools()).extracting(AiChat.Tool::name).contains("browser_navigate");
    }

    @Test
    void aServiceThatRejectsToolsFallsBackToTheSearchAndIsRemembered() throws Exception {
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.failed(new AiChatException(AiChatException.Kind.INVALID_REQUEST, 400,
                        "HTTP 400", null)),
                (request, listener) -> listener.completed("Ohne Werkzeuge.")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Was hat jedes Datenprodukt?", space, "Handbuch",
                events, Runnable::run).knowledgeByTools(null).start();
        assertThat(events.done.await(60, TimeUnit.SECONDS)).isTrue();

        assertThat(events.completed).isEqualTo("Ohne Werkzeuge.");
        assertThat(events.failed).isNull();
        assertThat(port.requests.get(1).tools()).isEmpty();
        assertThat(port.requests.get(1).messages().get(0).text()).startsWith(ChatKnowledge.SYSTEM_PROMPT);
        assertThat(ChatTurn.WITHOUT_TOOLS).contains("modell");

        // Die nächste Frage an dasselbe Modell geht gleich ohne Werkzeuge.
        ScriptedChat again = new ScriptedChat(List.of((request, listener) -> listener.completed("Gleich so.")));
        Events next = new Events();
        new ChatTurn(workspace, again, "modell", null, List.of(), "Noch eine Frage", space, "Handbuch", next,
                Runnable::run).knowledgeByTools(null).start();
        assertThat(next.done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(again.requests).singleElement().satisfies(request -> assertThat(request.tools()).isEmpty());
    }

    @Test
    void toolsRunOffTheStreamThreadOfTheAdapterAndOffTheEventThread() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean returned = new java.util.concurrent.atomic.AtomicBoolean();
        Thread[] stream = new Thread[1];
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> {
                    stream[0] = Thread.currentThread();
                    listener.toolCalls("", List.of(new AiChat.ToolCall("c1", ChatTools.LIST, "{}")));
                    returned.set(true);
                },
                (request, listener) -> {
                    // Liefen die Werkzeuge im Rückruf, wäre der erste Rückruf hier noch nicht zurück.
                    long end = System.currentTimeMillis() + 5_000;
                    while (!returned.get() && System.currentTimeMillis() < end) {
                        Thread.onSpinWait();
                    }
                    listener.completed(returned.get() ? "frei" : "blockiert");
                }));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Frage", space, "Handbuch", events, Runnable::run)
                .knowledgeByTools(null).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(events.completed).isEqualTo("frei");
        assertThat(events.usingThreads).singleElement().satisfies(thread -> {
            assertThat(thread).isNotSameAs(stream[0]);
            assertThat(thread.getName()).isEqualTo("chat-turn-tools");
        });
    }

    @Test
    void cancellingBetweenRoundsStopsTheLoop() throws Exception {
        Events events = new Events();
        ChatTurn[] turn = new ChatTurn[1];
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> {
                    turn[0].cancel();
                    listener.toolCalls("", List.of(new AiChat.ToolCall("c1", ChatTools.LIST, "{}")));
                },
                (request, listener) -> listener.completed("darf nicht kommen")));
        turn[0] = new ChatTurn(workspace, port, "modell", null, List.of(), "Frage", space, "Handbuch", events,
                Runnable::run).knowledgeByTools(null);
        turn[0].start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(events.cancelled).isNotNull();
        assertThat(events.completed).isNull();
        assertThat(port.requests).hasSize(1);
    }

    @Test
    void withToolsOnlyWhatReachesTheModelIsReleased() throws Exception {
        PageSelection root = workspace.resolveSelection(Selection.of(Selection.Root.source("handbuch")));
        PageKey nachtlauf = page("nachtlauf");
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c1", ChatTools.LIST, "{}"))),
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c2", ChatTools.READ, "{\"kennung\":\"" + ChatTools.ref(nachtlauf) + "\"}"))),
                (request, listener) -> listener.completed("Fertig.")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Frage", root, "Wissensquellen", events,
                Runnable::run).knowledgeByTools(null).releasing(releases()).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        // Wurzel gewählt, die Liste gelesen, eine Seite gelesen: genau diese eine steht auf der Liste.
        assertThat(released()).containsExactly(nachtlauf);
        assertThat(events.released).isEqualTo(1);
    }

    @Test
    void theChatListsEveryHitTheSearchBarListsForTheSameArea() throws Exception {
        ChatTools probe = new ChatTools(workspace, space, java.util.Set.of(), new ChatTools.Sources() {
            @Override
            public int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
                return 1;
            }

            @Override
            public void delivered(String spaceKey, PageKey key) {
            }
        });
        // Der Suchindex entsteht nach dem Sync im Hintergrund: warten, bis die Suchleiste alle Seiten mit dem Wort kennt.
        // Dieselbe Anfrage wie die Suchleiste mit derselben Auswahl: keywordSearchIn, ein Stapel der Suchleiste.
        List<com.aresstack.spaceharvester.application.query.KeywordSearch.Hit> bar = await(() -> {
            var ranked = workspace.keywordSearchIn(java.util.UUID.randomUUID().toString(),
                    ChatTools.areaQuery("Spool", space), null, com.aresstack.spaceharvester.app.ui.SearchView.LIMIT,
                    null).ranked();
            return ranked.size() > 1 ? ranked : null;
        });

        String listed = probe.search("Spool");

        for (var hit : bar) {
            assertThat(listed).contains(ChatTools.ref(new PageKey(hit.sourceId(), hit.pageId())));
        }
    }

    @Test
    void onlyTheOpenPageIsReleasedASearchReleasesNothingAndSendsNoPageText() throws Exception {
        PageKey open = page("datenhaus");
        // Der Suchindex entsteht nach dem Sync im Hintergrund: erst warten, bis die Suche Treffer liefert.
        ChatTools probe = new ChatTools(workspace, space, java.util.Set.of(), new ChatTools.Sources() {
            @Override
            public int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
                return 1;
            }

            @Override
            public void delivered(String spaceKey, PageKey key) {
            }
        });
        await(() -> probe.search("Spool").lines().anyMatch(line -> line.startsWith("- ")) ? true : null);
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c1", ChatTools.SEARCH, "{\"text\":\"Spool\"}"))),
                (request, listener) -> listener.completed("Fertig.")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Was ist ein Spool?", space, "Handbuch", events,
                Runnable::run).knowledgeByTools(open).releasing(releases()).start();
        assertThat(events.done.await(60, TimeUnit.SECONDS)).isTrue();

        List<AiChat.Message> messages = port.requests.get(1).messages();
        String hits = messages.get(messages.size() - 1).text();
        // Angelo 22.11.2026: „Die Suche zählt dafür ja nicht.“ Nur Kennung und Titel, kein Seitentext, keine Freigabe.
        assertThat(hits.lines().filter(line -> line.startsWith("- ")).toList()).isNotEmpty()
                .allSatisfy(line -> assertThat(line.split(" \\| ")).hasSize(2));
        assertThat(hits).doesNotContain("vollem Spool").doesNotContain("Ablage der Ausgaben");
        assertThat(released()).containsExactly(open);
        // Angelo 24.11.2026: Der Balken zählt Seiten, keine Runden mit fester Höchstzahl: gelesen ist die offene
        // Seite, gefunden sind die Treffer der Suche samt ihr.
        java.util.Set<String> found = new java.util.HashSet<>();
        hits.lines().filter(line -> line.startsWith("- ")).forEach(line -> found.add(line.substring(2).split(" \\| ")[0]));
        found.add(ChatTools.ref(open));
        assertThat(events.progress).isNotEmpty();
        assertThat(events.progress.get(events.progress.size() - 1)).containsExactly(1, found.size());
    }

    @Test
    void theFirstPageReadWithoutAnOpenPageIsReportedAsSourceOne() throws Exception {
        PageKey read = page("nachtlauf");
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(new AiChat.ToolCall("c1", ChatTools.READ,
                        "{\"kennung\":\"" + ChatTools.ref(read) + "\"}"))),
                (request, listener) -> listener.completed("Bei vollem Spool [1].")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Warum?", space, "Handbuch", events, Runnable::run)
                .knowledgeByTools(null).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(events.context.accepted()).extracting(ChatKnowledge.Excerpt::pageId)
                .containsExactly(read.pageId());
    }

    @Test
    void aSpaceTooLargeForTheOverviewIsNamedInsteadOfListedAsEmpty() {
        ChatTools tools = new ChatTools(workspace, space, java.util.Set.of(), new ChatTools.Sources() {
            @Override
            public int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
                return 1;
            }

            @Override
            public void delivered(String spaceKey, PageKey key) {
            }
        }).holdingsReader((sourceId, spaceKey) -> {
            throw new IllegalStateException("zu groß");
        });

        assertThat(tools.list("", 0)).isEqualTo(ChatTools.TOO_LARGE);
        assertThat(tools.list(ChatTools.ref(page("datenhaus")), 1)).isEqualTo(ChatTools.TOO_LARGE);
    }

    @Test
    void anAnswerThatOnlyArrivesAsCompletionFollowsTheNarrationOfAnEarlierRound() throws Exception {
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> {
                    listener.delta("Ich schaue nach. ");
                    listener.toolCalls("Ich schaue nach. ", List.of(new AiChat.ToolCall("c1", ChatTools.LIST, "{}")));
                },
                (request, listener) -> listener.completed("Drei Seiten.")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Frage", space, "Handbuch", events, Runnable::run)
                .knowledgeByTools(null).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(events.completed).isEqualTo("Ich schaue nach. Drei Seiten.");
        assertThat(events.deltas).containsExactly("Ich schaue nach. ", "Drei Seiten.");
    }

    // ------------------------------------------------------------------ begriff_nachschlagen (W7)

    @Test
    void theTermToolAsksOnlyTheAreaAndReleasesTheEvidencePagesBeforeAnswering() throws Exception {
        PageKey evidence = page("nachtlauf");
        String spaceKey = workspace.spaces("handbuch").get(0).key();
        List<String> order = new CopyOnWriteArrayList<>();
        List<PageSet> asked = new CopyOnWriteArrayList<>();
        ChatTools tools = tools(order).termLookup((query, scope, delivery, cancelled) -> {
            asked.add(scope);
            TermKnowledge.Answer answer = found(evidence, spaceKey);
            answer.pages().forEach(page -> delivery.delivered(page.spaceKey(), page.key()));
            order.add("antwort");
            return answer;
        });

        String text = tools.run(new AiChat.ToolCall("c1", ChatTools.TERM, "{\"begriff\":\"  Spool \"}"));

        assertThat(asked).containsExactly(space.pages());
        assertThat(order).containsExactly("geliefert " + ChatTools.ref(evidence), "antwort");
        assertThat(text).contains("Fachbegriff: Spool").contains("Synonyme: Ausgabeablage")
                .contains("Nachtlauf —muss nicht füllen→ Spool [geprüft, 2 Belege]")
                .contains(ChatTools.ref(evidence) + " (Nachtlauf) und 1 weitere")
                .contains("Verwandte Begriffe: Nachtlauf")
                .contains("Unvollständig: 3 weitere Aussagen; 1 weitere Belegseite")
                .contains(ChatTools.READ);
    }

    @Test
    void whenTheReleaseFailsTheTermToolGivesNoAnswer() {
        PageKey evidence = page("nachtlauf");
        String spaceKey = workspace.spaces("handbuch").get(0).key();
        ChatTools tools = new ChatTools(workspace, space, java.util.Set.of(), new ChatTools.Sources() {
            @Override
            public int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
                return 1;
            }

            @Override
            public void delivered(String spaceKey, PageKey key) {
                throw new IllegalStateException("Freigabe gescheitert");
            }
        }).termLookup((query, scope, delivery, cancelled) -> {
            TermKnowledge.Answer answer = found(evidence, spaceKey);
            answer.pages().forEach(page -> delivery.delivered(page.spaceKey(), page.key()));
            return answer;
        });

        String text = tools.run(new AiChat.ToolCall("c1", ChatTools.TERM, "{\"begriff\":\"Spool\"}"));

        assertThat(text).isEqualTo(ChatTools.RELEASE_FAILED.formatted("IllegalStateException"))
                .doesNotContain("Nachtlauf");
    }

    @Test
    void aWordThatIsNoTermNamesSimilarTermsOfTheAreaAndWithoutAreaNothingIsAsked() {
        ChatTools tools = tools(new ArrayList<>()).termLookup((query, scope, delivery, cancelled) ->
                new TermKnowledge.Answer(TermKnowledge.Outcome.NOT_A_TERM, List.of(), List.of(),
                        List.of("Spool", "Spoolbereich"), List.of(), limitsNone()));
        // Die Vorschläge filtert der Core nach Bereich und KI-Freigabe (#1118); das Werkzeug gibt sie weiter.
        assertThat(tools.term("Spol")).isEqualTo("„Spol“ ist im gewählten Bereich kein bestätigter Fachbegriff. "
                + "Ähnliche Fachbegriffe: Spool, Spoolbereich. Sonst nutze " + ChatTools.SEARCH + ".");
        assertThat(tools.term("   ")).isEqualTo("Bitte einen Fachbegriff angeben.");

        ChatTools nowhere = new ChatTools(workspace, null, java.util.Set.of(), sources(new ArrayList<>()))
                .termLookup((query, scope, delivery, cancelled) -> {
                    throw new AssertionError("ohne Bereich nie fragen");
                });
        assertThat(nowhere.term("Spool")).isEqualTo("Es ist kein Bereich gewählt.");
    }

    @Test
    void theRealTermKnowledgeAnswersInTheAreaWithoutReleasingAnything() {
        List<String> order = new ArrayList<>();
        // Im erfundenen Handbuch ist nichts als Fachbegriff bestätigt: kein Fachbegriff, nichts geliefert.
        String text = tools(order).run(new AiChat.ToolCall("c1", ChatTools.TERM, "{\"begriff\":\"Spool\"}"));
        assertThat(text).startsWith("„Spool“ ist im gewählten Bereich kein bestätigter Fachbegriff");
        assertThat(order).isEmpty();
    }

    @Test
    void inTheTurnTheTermToolReleasesOnlyTheEvidencePages() throws Exception {
        PageKey evidence = page("nachtlauf");
        String spaceKey = workspace.spaces("handbuch").get(0).key();
        TermKnowledge.Answer answer = found(evidence, spaceKey);
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c1", ChatTools.TERM, "{\"begriff\":\"Spool\"}"))),
                (request, listener) -> listener.completed("Fertig.")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Was ist ein Spool?", space, "Handbuch", events,
                Runnable::run).knowledgeByTools(null).releasing(releases())
                .termLookup((query, scope, delivery, cancelled) -> {
                    answer.pages().forEach(page -> delivery.delivered(page.spaceKey(), page.key()));
                    return answer;
                }).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(events.using).contains("Schlägt einen Fachbegriff nach");
        List<AiChat.Message> messages = port.requests.get(1).messages();
        assertThat(messages.get(messages.size() - 1).text()).contains("Fachbegriff: Spool");
        assertThat(released()).containsExactly(evidence);
    }

    @Test
    void inTheTurnAFailedReleaseKeepsTheTermAnswerFromTheModel() throws Exception {
        PageKey evidence = page("nachtlauf");
        String spaceKey = workspace.spaces("handbuch").get(0).key();
        TermKnowledge.Answer answer = found(evidence, spaceKey);
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c1", ChatTools.TERM, "{\"begriff\":\"Spool\"}"))),
                (request, listener) -> listener.completed("Fertig.")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Was ist ein Spool?", space, "Handbuch", events,
                Runnable::run).knowledgeByTools(null).releasing(new ChatTurn.Releases() {
                    @Override
                    public int page(String spaceKey, PageKey key) {
                        throw new IllegalStateException("Freigabeliste nicht schreibbar");
                    }

                }).termLookup((query, scope, delivery, cancelled) -> {
                    answer.pages().forEach(page -> delivery.delivered(page.spaceKey(), page.key()));
                    return answer;
                }).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        List<AiChat.Message> messages = port.requests.get(1).messages();
        assertThat(messages.get(messages.size() - 1).text())
                .isEqualTo(ChatTools.RELEASE_FAILED.formatted("IllegalStateException"));
        assertThat(released()).isEmpty();
    }

    @Test
    void featuresThatWereNotDeterminedAreNamedAsOpen() {
        PageKey evidence = new PageKey("handbuch", "nachtlauf");
        TermKnowledge.Statement statement = new TermKnowledge.Statement("Nachtlauf", "füllen", "Spool",
                ParsedStatements.Negation.UNKNOWN, ParsedStatements.Modality.UNKNOWN,
                ParsedStatements.Temporality.UNKNOWN, DomainTermGraph.Trust.UNGEPRUEFT, 1,
                List.of(new TermKnowledge.Page(evidence, "HB", "Nachtlauf")), 0);
        TermKnowledge.Answer answer = new TermKnowledge.Answer(TermKnowledge.Outcome.FOUND,
                List.of(new TermKnowledge.Term("Spool", DomainTermGraph.NodeKind.TERM, List.of(statement))),
                List.of(), List.of(), List.of(), limitsNone());

        assertThat(ChatTools.render("Spool", answer)).contains("Nachtlauf —füllen→ Spool [ungeprüft, "
                + "Zeitbezug offen, Verneinung offen, Verbindlichkeit offen, 1 Beleg]");
    }

    // ------------------------------------------------------------------ Freigabe dicht für alle Werkzeuge

    @Test
    void aFailedReleaseKeepsTheReadPageFromTheModelAndALaterLookupReportsItAgain() throws Exception {
        PageKey read = page("nachtlauf");
        String spaceKey = workspace.spaces("handbuch").get(0).key();
        List<PageKey> attempts = new CopyOnWriteArrayList<>();
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c1", ChatTools.READ, "{\"kennung\":\"" + ChatTools.ref(read) + "\"}"))),
                (request, listener) -> listener.toolCalls("", List.of(
                        new AiChat.ToolCall("c2", ChatTools.TERM, "{\"begriff\":\"Spool\"}"))),
                (request, listener) -> listener.completed("Fertig.")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Warum bricht der Nachtlauf ab?", space, "Handbuch",
                events, Runnable::run).knowledgeByTools(null).releasing(new ChatTurn.Releases() {
                    @Override
                    public int page(String spaceKey, PageKey key) {
                        attempts.add(key);
                        if (attempts.size() == 1) {
                            throw new IllegalStateException("Freigabeliste nicht schreibbar");
                        }
                        return workspace.releasePageForChat(spaceKey, key).size();
                    }

                }).termLookup((query, scope, delivery, cancelled) -> {
                    TermKnowledge.Answer answer = found(read, spaceKey);
                    answer.pages().forEach(page -> delivery.delivered(page.spaceKey(), page.key()));
                    return answer;
                }).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        List<AiChat.Message> first = port.requests.get(1).messages();
        assertThat(first.get(first.size() - 1).text())
                .isEqualTo(ChatTools.RELEASE_FAILED.formatted("IllegalStateException"))
                .doesNotContain("vollem Spool");
        assertThat(events.context == null ? List.of() : events.context.accepted()).as("nichts zitiert").isEmpty();
        assertThat(attempts).as("die gescheiterte Seite wird beim nächsten Werkzeug wieder gemeldet")
                .containsExactly(read, read);
        assertThat(released()).containsExactly(read);
    }

    @Test
    void aSearchNeverTouchesTheListOfReleasedPages() throws Exception {
        ChatTools probe = tools(new ArrayList<>());
        await(() -> probe.search("Spool").lines().anyMatch(line -> line.startsWith("- ")) ? true : null);
        ChatTools failing = new ChatTools(workspace, space, java.util.Set.of(), new ChatTools.Sources() {
            @Override
            public int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
                return 1;
            }

            @Override
            public void delivered(String spaceKey, PageKey key) {
                throw new AssertionError("eine Suche trägt nie ein");
            }
        });

        String hits = failing.search("Spool");

        assertThat(hits).contains("- ").doesNotContain("vollem Spool").doesNotContain("Ablage der Ausgaben")
                .doesNotContain("nicht freigegeben");
    }

    @Test
    void anOpenPageWhoseReleaseFailsDoesNotGoAlong() throws Exception {
        PageKey open = page("datenhaus");
        ScriptedChat port = new ScriptedChat(List.of((request, listener) -> listener.completed("Fertig.")));
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Worum geht es?", space, "Handbuch", events,
                Runnable::run).knowledgeByTools(open).releasing(new ChatTurn.Releases() {
                    @Override
                    public int page(String spaceKey, PageKey key) {
                        throw new IllegalStateException("Freigabeliste nicht schreibbar");
                    }

                }).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(port.requests.get(0).messages().get(0).text()).doesNotContain("Berechtigungsmanagement")
                .contains(ChatKnowledge.NO_OPEN_PAGE);
    }

    // ------------------------------------------------------------------ begriff_nachschlagen: Kanten

    @Test
    void theRealTermKnowledgeFindsAConfirmedTermInTheAreaAndReportsOnlyItsEvidenceBeforeAnswering() {
        String spaceKey = workspace.spaces("handbuch").get(0).key();
        PageKey inside = page("nachtlauf");
        PageKey outside = new PageKey("handbuch", "fremd");
        com.aresstack.spaceharvester.application.access.AccessContext caller =
                com.aresstack.spaceharvester.application.access.AccessContext.technicalClient("desktop");
        com.aresstack.spaceharvester.application.knowledge.InMemoryKnowledgeGraphStore store =
                new com.aresstack.spaceharvester.application.knowledge.InMemoryKnowledgeGraphStore();
        com.aresstack.spaceharvester.application.access.AccessGuard access =
                new com.aresstack.spaceharvester.application.access.AccessGuard((context, scope, kind) ->
                        com.aresstack.spaceharvester.application.access.AccessDecision.ALLOW);
        Map<PageKey, com.aresstack.spaceharvester.application.snapshot.PageSnapshot> cache = new java.util.HashMap<>();
        java.util.function.Function<com.aresstack.spaceharvester.application.snapshot.PageSnapshot,
                java.util.Optional<com.aresstack.spaceharvester.application.enrichment.PageText>> texts = snapshot ->
                java.util.Optional.of(new com.aresstack.spaceharvester.application.enrichment.PageText("test/v1",
                        new String(snapshot.payload().bytes(), java.nio.charset.StandardCharsets.UTF_8), List.of()));
        com.aresstack.spaceharvester.application.knowledge.StatementAnalysis analysis =
                new com.aresstack.spaceharvester.application.knowledge.StatementAnalysis(texts, store, access,
                        com.aresstack.spaceharvester.application.knowledge.FakeStatementAnalyzer::new,
                        java.time.Clock.fixed(java.time.Instant.parse("2026-11-22T15:00:00Z"),
                                java.time.ZoneOffset.UTC));
        com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph graph =
                new com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph(store, access,
                        key -> java.util.Optional.ofNullable(cache.get(key)), texts, analysis,
                        com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph.TermExpansion.NONE);
        for (var page : List.of(Map.entry(inside, "Der Nachtlauf prüft den Spool."),
                Map.entry(outside, "Der Nachtlauf sendet den Spool."))) {
            var snapshot = com.aresstack.spaceharvester.application.snapshot.PageSnapshot.of("handbuch",
                    new com.aresstack.spaceharvester.application.sources.PageMetadata(page.getKey().pageId(),
                            "files:" + page.getKey().pageId(), "Seite " + page.getKey().pageId(), spaceKey, null,
                            "1", java.time.Instant.parse("2026-11-22T10:00:00Z"),
                            java.net.URI.create("file:///handbuch/" + page.getKey().pageId()),
                            com.aresstack.spaceharvester.application.sources.PageMetadata.sorted(Map.of())),
                    new com.aresstack.spaceharvester.application.snapshot.NativePayload(page.getValue()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain", "test-text"),
                    java.time.Instant.parse("2026-11-22T10:00:00Z"));
            cache.put(snapshot.key(), snapshot);
            analysis.analyze(caller, snapshot, () -> false);
        }
        DomainTermGraph.Vocabulary vocabulary = new DomainTermGraph.Vocabulary() {
            @Override
            public List<DomainTermGraph.Term> terms(
                    com.aresstack.spaceharvester.application.access.AccessContext who) {
                return List.of(new DomainTermGraph.Term("Nachtlauf",
                                new com.aresstack.spaceharvester.application.terms.RelationScope(null, null)),
                        new DomainTermGraph.Term("Spool",
                                new com.aresstack.spaceharvester.application.terms.RelationScope(null, null)));
            }

            @Override
            public List<DomainTermGraph.Synonym> synonyms(
                    com.aresstack.spaceharvester.application.access.AccessContext who) {
                // Ein global geltendes Synonym und eines, das nur in einer anderen Quelle gilt.
                return List.of(new DomainTermGraph.Synonym("Spool", "Ausgabeablage",
                                new com.aresstack.spaceharvester.application.terms.RelationScope(null, null)),
                        new DomainTermGraph.Synonym("Spool", "Druckpuffer",
                                new com.aresstack.spaceharvester.application.terms.RelationScope("andere", null)));
            }
        };
        // Alle Quellen freigegeben: geprüft wird hier die Reihenfolge Freigabe vor Antwort, nicht die KI-Freigabe (#1118).
        TermKnowledge knowledge = new TermKnowledge(new DomainTermGraph(graph, vocabulary),
                com.aresstack.spaceharvester.application.release.AiRelease.ofSources(sourceId -> true));
        List<String> order = new CopyOnWriteArrayList<>();

        String text = tools(order).termLookup((query, scope, delivery, cancelled) -> {
            TermKnowledge.Answer answer = knowledge.answer(caller, query, scope, delivery, cancelled);
            order.add("antwort");
            return answer;
        }).term("Spool");

        assertThat(text).contains("Fachbegriff: Spool").contains("Synonyme: Ausgabeablage")
                .contains("Nachtlauf —prüft→ Spool").contains(ChatTools.ref(inside))
                .doesNotContain("sendet").doesNotContain("fremd").doesNotContain("Druckpuffer");
        assertThat(order).containsExactly("geliefert " + ChatTools.ref(inside), "antwort");
    }

    @Test
    void anEvidencePageWithoutSpaceGivesNoAnswer() {
        PageKey unknown = new PageKey("handbuch", "ohne-space");
        ChatTools tools = tools(new ArrayList<>()).termLookup((query, scope, delivery, cancelled) -> {
            TermKnowledge.Answer answer = found(unknown, null);
            answer.pages().forEach(page -> delivery.delivered(page.spaceKey(), page.key()));
            return answer;
        });

        assertThat(tools.run(new AiChat.ToolCall("c1", ChatTools.TERM, "{\"begriff\":\"Spool\"}")))
                .isEqualTo(ChatTools.RELEASE_FAILED.formatted("IllegalStateException"));
    }

    @Test
    void cancellingStopsTheLookupWithAShortAnswer() {
        ChatTools tools = tools(new ArrayList<>()).termLookup((query, scope, delivery, cancelled) -> {
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            throw new AssertionError("Abbruch nicht durchgereicht");
        });
        tools.cancel();

        assertThat(tools.run(new AiChat.ToolCall("c1", ChatTools.TERM, "{\"begriff\":\"Spool\"}")))
                .isEqualTo("Abgebrochen.");
    }

    @Test
    void theInputIsCappedWithoutSplittingASurrogatePair() {
        List<String> asked = new CopyOnWriteArrayList<>();
        ChatTools tools = tools(new ArrayList<>()).termLookup((query, scope, delivery, cancelled) -> {
            asked.add(query);
            return new TermKnowledge.Answer(TermKnowledge.Outcome.NOT_A_TERM, List.of(), List.of(), List.of(),
                    List.of(), limitsNone());
        });
        String input = "a".repeat(ChatTools.MAX_TERM_CHARS - 1) + "😀" + "rest";

        tools.term(input);

        assertThat(asked).singleElement().isEqualTo("a".repeat(ChatTools.MAX_TERM_CHARS - 1));
        assertThat(ChatTools.cut("kurz", ChatTools.MAX_TERM_CHARS)).isEqualTo("kurz");
    }

    @Test
    void aFailingLookupNamesItsReasonAndTheDiagnosisOnlyItsClass() {
        List<String> reasons = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<String> before = ChatTools.diagnosis;
        ChatTools.diagnosis = reasons::add;
        try {
            ChatTools tools = tools(new ArrayList<>()).termLookup((query, scope, delivery, cancelled) -> {
                throw new IllegalArgumentException("Inhalt der Seite Nachtlauf");
            });

            String text = tools.run(new AiChat.ToolCall("c1", ChatTools.TERM, "{\"begriff\":\"Spool\"}"));

            assertThat(text).isEqualTo("Das Werkzeug ist gescheitert (IllegalArgumentException).");
            assertThat(reasons).containsExactly("Chat-Werkzeug " + ChatTools.TERM
                    + " gescheitert: IllegalArgumentException");
        } finally {
            ChatTools.diagnosis = before;
        }
    }

    @Test
    void withoutStatementsInAPartlyReadAreaTheAnswerSaysWhatWasRead() {
        TermKnowledge.Answer answer = new TermKnowledge.Answer(TermKnowledge.Outcome.FOUND,
                List.of(new TermKnowledge.Term("Spool", DomainTermGraph.NodeKind.TERM, List.of())), List.of(),
                List.of(), List.of(), new TermKnowledge.Limits(800, 500, 0, 0, 0, 0, false, 0));

        assertThat(ChatTools.render("Spule", answer))
                .startsWith("Fachbegriff: Spool\n")
                .contains("In den gelesenen Seiten gibt es keine Aussagen zu diesem Begriff (gelesen wurden 500 von "
                        + "800 Seiten des Bereichs, die übrigen sind ungelesen)")
                .contains("Unvollständig: 500 von 800 Seiten des Bereichs gelesen")
                .doesNotContain(ChatTools.READ);
    }

    private ChatTools tools(List<String> order) {
        return new ChatTools(workspace, space, java.util.Set.of(), sources(order));
    }

    private static ChatTools.Sources sources(List<String> order) {
        return new ChatTools.Sources() {
            @Override
            public int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
                return 1;
            }

            @Override
            public void delivered(String spaceKey, PageKey key) {
                order.add("geliefert " + ChatTools.ref(key));
            }
        };
    }

    /** Eine erfundene Antwort: Spool mit einem Synonym, eine verneinte Muss-Aussage, drei gekappte Aussagen. */
    private static TermKnowledge.Answer found(PageKey evidence, String spaceKey) {
        TermKnowledge.Page page = new TermKnowledge.Page(evidence, spaceKey, "Nachtlauf");
        TermKnowledge.Statement statement = new TermKnowledge.Statement("Nachtlauf", "füllen", "Spool",
                ParsedStatements.Negation.NEGATED, ParsedStatements.Modality.MUSS, ParsedStatements.Temporality.GILT,
                DomainTermGraph.Trust.GEPRUEFT, 2, List.of(page), 1);
        return new TermKnowledge.Answer(TermKnowledge.Outcome.FOUND, List.of(
                new TermKnowledge.Term("Spool", DomainTermGraph.NodeKind.TERM, List.of(statement)),
                new TermKnowledge.Term("Ausgabeablage", DomainTermGraph.NodeKind.SYNONYM, List.of())),
                List.of("Nachtlauf"), List.of(), List.of(page),
                new TermKnowledge.Limits(3, 3, 0, 3, 0, 1, false, 0));
    }

    private static TermKnowledge.Limits limitsNone() {
        return new TermKnowledge.Limits(0, 0, 0, 0, 0, 0, false, 0);
    }

    private ChatTurn.Releases releases() {
        return new ChatTurn.Releases() {
            @Override
            public int page(String spaceKey, PageKey key) {
                return workspace.releasePageForChat(spaceKey, key).size();
            }

        };
    }

    private List<PageKey> released() {
        List<PageKey> keys = new ArrayList<>();
        for (var group : workspace.pageReleaseGroups()) {
            for (var page : workspace.releasedPages(group.sourceId(), group.spaceKey(), group.node())) {
                keys.add(page.key());
            }
        }
        return keys;
    }

    private PageKey page(String name) {
        for (Map.Entry<String, java.util.Set<String>> source : space.pages().asMap().entrySet()) {
            for (String pageId : source.getValue()) {
                String title = workspace.cachedPage(source.getKey(), pageId)
                        .map(page -> page.header().metadata().title()).orElse("");
                if (pageId.contains(name) || title.toLowerCase(java.util.Locale.ROOT).contains(name)) {
                    return new PageKey(source.getKey(), pageId);
                }
            }
        }
        throw new AssertionError("keine Seite " + name + " in " + space.pages().asMap());
    }

    private static <T> T await(java.util.function.Supplier<T> probe) throws InterruptedException {
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            T value = probe.get();
            if (value != null) {
                return value;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("nicht eingetreten");
    }

    /** Ein Chat-Modell nach Drehbuch: je Anfrage der nächste Schritt. */
    private static final class ScriptedChat implements AiChat {

        private final List<BiConsumer<Request, Listener>> script;
        final List<Request> requests = new CopyOnWriteArrayList<>();

        ScriptedChat(List<BiConsumer<Request, Listener>> script) {
            this.script = script;
        }

        @Override
        public Task stream(Request request, Listener listener) {
            requests.add(request);
            script.get(requests.size() - 1).accept(request, listener);
            return () -> { };
        }
    }

    private static final class Events implements ChatTurn.Listener {

        final CountDownLatch done = new CountDownLatch(1);
        final List<String> using = new CopyOnWriteArrayList<>();
        final List<Thread> usingThreads = new CopyOnWriteArrayList<>();
        int released;
        final List<String> deltas = new CopyOnWriteArrayList<>();
        ChatKnowledge.Context context;
        String completed;
        String failed;
        String cancelled;

        @Override
        public void context(ChatKnowledge.Context context) {
            this.context = context;
        }

        @Override
        public void using(String what) {
            using.add(what);
            usingThreads.add(Thread.currentThread());
        }

        @Override
        public void released(int count) {
            released += count;
        }

        final List<int[]> progress = new CopyOnWriteArrayList<>();

        @Override
        public void progress(int done, int total) {
            progress.add(new int[] {done, total});
        }

        final List<String> tools = new CopyOnWriteArrayList<>();

        @Override
        public void toolStarted(String key, String name, String what) {
            tools.add("start " + name + " " + key);
        }

        @Override
        public void toolFinished(String key) {
            tools.add("end " + key);
        }

        @Override
        public void delta(String text) {
            deltas.add(text);
        }

        @Override
        public void completed(String text) {
            completed = text;
            done.countDown();
        }

        @Override
        public void failed(String message) {
            failed = message;
            done.countDown();
        }

        @Override
        public void cancelled(String partial) {
            cancelled = partial;
            done.countDown();
        }
    }
}
