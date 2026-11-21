package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiChatException;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.Selection;
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
                .containsExactlyInAnyOrder(ChatTools.SEARCH, ChatTools.LIST, ChatTools.READ);
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
        assertThat(messages.get(messages.size() - 1).text()).startsWith("Startseiten des Bereichs, Blatt 1 von 1")
                .contains(ChatTools.ref(page("datenhaus"))).contains(ChatTools.ref(page("glossar")));
    }

    @Test
    void theLastRoundGoesWithoutToolsSoThatAnAnswerComes() throws Exception {
        List<BiConsumer<AiChat.Request, AiChat.Listener>> script = new ArrayList<>();
        for (int i = 0; i < ChatTurn.MAX_TOOL_ROUNDS; i++) {
            script.add((request, listener) -> listener.toolCalls("", List.of(
                    new AiChat.ToolCall("c", ChatTools.LIST, "{}"))));
        }
        script.add((request, listener) -> listener.completed("Fertig."));
        ScriptedChat port = new ScriptedChat(script);
        Events events = new Events();

        new ChatTurn(workspace, port, "modell", null, List.of(), "Frage", space, "Handbuch", events, Runnable::run)
                .knowledgeByTools(null).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(port.requests).hasSize(ChatTurn.MAX_TOOL_ROUNDS + 1);
        assertThat(port.requests.get(ChatTurn.MAX_TOOL_ROUNDS).tools()).isEmpty();
        assertThat(events.completed).isEqualTo("Fertig.");
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
    void theOpenPageAndTheSearchHitsWithExcerptAreReleasedButNotTheRest() throws Exception {
        PageKey open = page("datenhaus");
        // Der Suchindex entsteht nach dem Sync im Hintergrund: erst warten, bis die Suche Auszüge liefert.
        ChatTools probe = new ChatTools(workspace, space, java.util.Set.of(), new ChatTools.Sources() {
            @Override
            public int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
                return 1;
            }

            @Override
            public void delivered(String spaceKey, PageKey key) {
            }
        });
        await(() -> probe.search("Spool").lines().anyMatch(line -> line.split(" \\| ").length >= 3) ? true : null);
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
        java.util.Set<PageKey> expected = new java.util.HashSet<>();
        expected.add(open);
        for (String line : hits.split("\n")) {
            if (line.startsWith("- ") && line.split(" \\| ").length >= 3) {
                expected.add(ChatTools.parse(line.substring(2, line.indexOf(" | "))));
            }
        }
        assertThat(expected).as("Suche lieferte Auszüge").hasSizeGreaterThan(1);
        assertThat(released()).containsExactlyInAnyOrderElementsOf(expected);
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

        assertThat(tools.list("", 1)).isEqualTo(ChatTools.TOO_LARGE);
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

    private ChatTurn.Releases releases() {
        return new ChatTurn.Releases() {
            @Override
            public int page(String spaceKey, PageKey key) {
                return workspace.releasePageForChat(spaceKey, key).size();
            }

            @Override
            public int selection() {
                throw new AssertionError("mit Werkzeugen nie die ganze Auswahl");
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
        final List<String> using = new ArrayList<>();
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
