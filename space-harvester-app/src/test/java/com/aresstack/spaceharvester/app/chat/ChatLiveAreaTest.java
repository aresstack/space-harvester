package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ein gewählter Ordner, den der Katalog nicht kennt, ist für den Chat trotzdem ein Bereich (Angelo 26.11.2026: bei
 * SharePoint sagte der Chat trotz Auswahl, ihm lägen keine Wissensquellen vor, und erfand dann Inhalte). Gegen eine
 * echte Ordner-Quelle ohne Abgleich: Liste und Lesen über die gemeinsame Navigation, Lesen holt die Datei wie die
 * Leseansicht und gibt sie vor dem Senden frei; was neben dem Ordner liegt, bleibt draußen.
 */
class ChatLiveAreaTest {

    @TempDir
    Path home;

    private AppContext context;
    private Workspace workspace;
    private String spaceKey;
    private PageNode team;

    @BeforeEach
    void start() throws Exception {
        Path files = Files.createDirectories(home.resolve("ablage"));
        Files.createDirectories(files.resolve("team"));
        Files.writeString(files.resolve("team").resolve("protokoll.md"), "# Protokoll\n\nDie Rufbereitschaft "
                + "übernimmt im Dezember das Team Nord.\n");
        Files.writeString(files.resolve("sonstiges.md"), "# Sonstiges\n\nDie Kantine schließt freitags früher.\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: ablage
                      type: files
                      title: Ablage
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        workspace = context.runtime().workspace();
        spaceKey = workspace.spaces("ablage").get(0).key();
        // Kein Abgleich: Der Ordner steht nur in der Navigation, wie ein SharePoint-Ordner im Baum.
        PageNavigation.Children top = workspace.children("ablage", spaceKey, null, null);
        team = top.pages().stream().filter(node -> node.title().contains("team")).findFirst().orElseThrow();
    }

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void theToolsListAndReadAFolderTheCatalogDoesNotKnow() {
        Selection.Root root = Selection.Root.subtree("ablage", spaceKey, team.pageId());
        PageSelection scope = workspace.resolveSelection(Selection.of(root));
        assertThat(scope.pages().isEmpty()).as("der Katalog kennt den Ordner nicht").isTrue();
        List<String> delivered = new ArrayList<>();
        ChatTools tools = new ChatTools(workspace, scope, java.util.Set.of(), new ChatTools.Sources() {
            @Override
            public int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
                return 2;
            }

            @Override
            public void delivered(String space, PageKey key) {
                delivered.add(ChatTools.ref(key));
            }
        }).liveArea(new LiveArea(LiveArea.of(workspace), LiveArea.complementing(scope, List.of(root))));

        String listed = tools.run(new AiChat.ToolCall("l", ChatTools.LIST, "{}"));
        assertThat(listed).contains("protokoll").doesNotContain("sonstiges").doesNotContain("keine Seiten");
        String ref = listed.lines().filter(line -> line.contains("protokoll")).findFirst().orElseThrow()
                .substring(2).split(" \\| ")[0];

        String read = tools.run(new AiChat.ToolCall("r", ChatTools.READ, "{\"kennung\":\"" + ref + "\"}"));
        assertThat(read).contains("Quelle [2]").contains("Team Nord");
        assertThat(delivered).containsExactly(ref);

        // Neben dem Ordner: nicht verfügbar, ohne zu verraten, ob es die Datei gibt.
        PageNode outside = workspace.children("ablage", spaceKey, null, null).pages().stream()
                .filter(node -> node.title().contains("sonstiges")).findFirst().orElseThrow();
        assertThat(tools.run(new AiChat.ToolCall("o", ChatTools.READ, "{\"kennung\":\""
                + ChatTools.ref(new PageKey("ablage", outside.pageId())) + "\"}"))).isEqualTo(ChatTools.UNAVAILABLE);
        // Die Suche meldet nie „kein Bereich gewählt“, wenn einer gewählt ist.
        assertThat(tools.run(new AiChat.ToolCall("s", ChatTools.SEARCH, "{\"text\":\"Rufbereitschaft\"}")))
                .doesNotContain("kein Bereich gewählt");
    }

    @Test
    void localAndLiveComplementEachOtherInAPartlyLoadedArea() {
        // Angelo 26.11.2026: Ein bekannter Bereich ist nicht zwangsläufig ganz erschlossen. Eine Datei liegt lokal
        // (geöffnet), die übrigen kennt nur die Quelle; der Chat erreicht alle.
        PageNode outside = workspace.children("ablage", spaceKey, null, null).pages().stream()
                .filter(node -> node.title().contains("sonstiges")).findFirst().orElseThrow();
        workspace.page("ablage", outside.pageId(), false);
        Selection.Root root = Selection.Root.space("ablage", spaceKey);
        PageSelection scope = workspace.resolveSelection(Selection.of(root));
        assertThat(scope.pages().contains("ablage", outside.pageId())).as("lokal und im Katalog").isTrue();
        assertThat(scope.complete()).as("ohne Inventur unvollständig").isFalse();
        List<Selection.Root> live = LiveArea.complementing(scope, List.of(root));
        assertThat(live).containsExactly(root);
        ChatTools tools = new ChatTools(workspace, scope, java.util.Set.of(), new ChatTools.Sources() {
            @Override
            public int cite(ChatKnowledge.Excerpt excerpt, PageKey key) {
                return 3;
            }

            @Override
            public void delivered(String space, PageKey key) {
            }
        }).liveArea(new LiveArea(LiveArea.of(workspace), live));

        String listed = tools.run(new AiChat.ToolCall("l", ChatTools.LIST, "{}"));
        assertThat(listed).contains("sonstiges").contains("team");
        String folder = ChatTools.ref(new PageKey("ablage", team.pageId()));
        assertThat(tools.run(new AiChat.ToolCall("u", ChatTools.LIST, "{\"knoten\":\"" + folder + "\"}")))
                .contains("protokoll");
        assertThat(tools.run(new AiChat.ToolCall("f", ChatTools.READ, "{\"kennung\":\"" + folder + "\"}")))
                .isEqualTo(ChatTools.FOLDER_ONLY);
    }

    @Test
    void aTurnOnSuchAFolderGetsTheAreaToolsAndTheNoInventionRule() throws Exception {
        Selection.Root root = Selection.Root.subtree("ablage", spaceKey, team.pageId());
        PageSelection scope = workspace.resolveSelection(Selection.of(root));
        List<AiChat.Request> requests = new CopyOnWriteArrayList<>();
        List<String> results = new CopyOnWriteArrayList<>();
        AiChat port = (request, listener) -> {
            requests.add(request);
            AiChat.Message last = request.messages().get(request.messages().size() - 1);
            results.add(last.text());
            switch (requests.size()) {
                case 1 -> listener.toolCalls("", List.of(new AiChat.ToolCall("c1", ChatTools.LIST, "{}")));
                default -> listener.completed("Das Team Nord [2].");
            }
            return () -> { };
        };
        CountDownLatch done = new CountDownLatch(1);
        List<String> failures = new CopyOnWriteArrayList<>();
        ChatTurn.Listener listener = new ChatTurn.Listener() {
            @Override
            public void context(ChatKnowledge.Context context) {
            }

            @Override
            public void delta(String text) {
            }

            @Override
            public void completed(String text) {
                done.countDown();
            }

            @Override
            public void failed(String message) {
                failures.add(message);
                done.countDown();
            }

            @Override
            public void cancelled(String partial) {
                done.countDown();
            }
        };
        new ChatTurn(workspace, port, "modell", null, List.of(), "Wer hat im Dezember Rufbereitschaft?", scope,
                "team samt Unterseiten", listener, Runnable::run).knowledgeByTools(null).area(List.of(root)).start();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();

        AiChat.Request first = requests.get(0);
        String system = first.messages().get(0).text();
        assertThat(system).startsWith(ChatKnowledge.TOOL_PROMPT).contains(ChatKnowledge.NO_INVENTION)
                .doesNotContain(ChatKnowledge.OWN_KNOWLEDGE_PROMPT);
        assertThat(first.tools()).extracting(AiChat.Tool::name)
                .contains(ChatTools.SEARCH, ChatTools.LIST, ChatTools.READ);
        assertThat(results.get(1)).contains("protokoll");
    }

    @Test
    void withoutAnyRootTheTurnStaysWithoutArea() throws Exception {
        List<AiChat.Request> requests = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiChat port = (request, listener) -> {
            requests.add(request);
            listener.completed("Antwort");
            return () -> { };
        };
        new ChatTurn(workspace, port, "modell", null, List.of(), "Hallo?", null, null, new ChatTurn.Listener() {
            @Override
            public void context(ChatKnowledge.Context context) {
            }

            @Override
            public void delta(String text) {
            }

            @Override
            public void completed(String text) {
                done.countDown();
            }

            @Override
            public void failed(String message) {
                done.countDown();
            }

            @Override
            public void cancelled(String partial) {
                done.countDown();
            }
        }, Runnable::run).knowledgeByTools(null).area(List.of()).start();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(requests.get(0).tools()).isEmpty();
    }
}
