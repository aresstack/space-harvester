package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Echttest von ADR 0033 gegen einen Ollama-Server (nur HTTP-API), mit erfundenen Seiten. Läuft nur mit
 * {@code -PchatLiveOllama=http://localhost:11434}; in der CI übersprungen. Prüft beide Wege: ein Modell mit
 * Werkzeugaufrufen (Runden, Zitat, Abbruch) und eines, dessen Dienst Werkzeuge ablehnt (stiller Rückfall).
 */
@EnabledIfSystemProperty(named = "chat.live.ollama", matches = "https?://.+")
class ChatToolsLiveTest {

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        ChatTurn.WITHOUT_TOOLS.clear();
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void aModelWithToolsReadsTheOtherPageAndCitesIt() throws Exception {
        Workspace workspace = start(System.getProperty("chat.live.model", "gemma4:12b"));
        PageSelection space = space(workspace);
        Recording port = new Recording(workspace.aiChat());
        Events events = new Events();

        new ChatTurn(workspace, port, model(), null, List.of(),
                "Warum bricht der Nachtlauf ab? Antworte mit Quellenangabe.", space, "Handbuch · 3 Seiten", events,
                Runnable::run).knowledgeByTools(page(workspace, space, "datenhaus")).start();
        assertThat(events.done.await(10, TimeUnit.MINUTES)).isTrue();

        System.out.println("[live] Anfragen: " + port.requests.size() + ", Werkzeuge: " + events.using
                + ", Quellen: " + (events.context == null ? 0 : events.context.accepted().size()));
        System.out.println("[live] Antwort: " + events.completed);
        assertThat(events.failed).isNull();
        assertThat(port.requests.size()).as("mindestens eine Werkzeugrunde").isGreaterThan(1);
        assertThat(events.using).isNotEmpty();
        assertThat(events.completed.toLowerCase(Locale.ROOT)).contains("spool");
    }

    @Test
    void cancellingDuringTheToolLoopEndsTheTurn() throws Exception {
        Workspace workspace = start(System.getProperty("chat.live.model", "gemma4:12b"));
        PageSelection space = space(workspace);
        Events events = new Events();
        ChatTurn[] turn = new ChatTurn[1];
        Recording port = new Recording(workspace.aiChat()) {
            @Override
            public Task stream(Request request, Listener listener) {
                if (requests.size() == 1) {
                    turn[0].cancel(); // nach der ersten Runde abbrechen
                }
                return super.stream(request, listener);
            }
        };
        turn[0] = new ChatTurn(workspace, port, model(), null, List.of(), "Welche Seiten gibt es hier? Lies sie.",
                space, "Handbuch", events, Runnable::run).knowledgeByTools(null);
        turn[0].start();
        assertThat(events.done.await(10, TimeUnit.MINUTES)).isTrue();

        System.out.println("[live] Abbruch nach " + port.requests.size() + " Anfragen, completed=" + events.completed
                + ", cancelled=" + (events.cancelled != null));
        assertThat(events.cancelled).isNotNull();
    }

    @Test
    void aModelWithoutToolsFallsBackSilently() throws Exception {
        String noTools = System.getProperty("chat.live.noToolsModel", "PHI:latest");
        Workspace workspace = start(noTools);
        PageSelection space = space(workspace);
        Recording port = new Recording(workspace.aiChat());
        Events events = new Events();

        new ChatTurn(workspace, port, noTools, null, List.of(), "Warum bricht der Nachtlauf ab?", space, "Handbuch",
                events, Runnable::run).knowledgeByTools(null).start();
        assertThat(events.done.await(10, TimeUnit.MINUTES)).isTrue();

        System.out.println("[live] Rückfall: Anfragen " + port.requests.size() + ", gemerkt "
                + ChatTurn.WITHOUT_TOOLS + ", Antwort: " + events.completed);
        assertThat(events.failed).isNull();
        assertThat(port.requests.get(0).tools()).isNotEmpty();
        assertThat(port.requests.get(port.requests.size() - 1).tools()).isEmpty();
        assertThat(ChatTurn.WITHOUT_TOOLS).contains(noTools);
    }

    private String model() {
        return System.getProperty("chat.live.model", "gemma4:12b");
    }

    private Workspace start(String chatModel) throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("datenhaus.md"), "# Datenhaus\n\nJedes Datenprodukt hat ein "
                + "Berechtigungsmanagement.\n");
        Files.writeString(files.resolve("nachtlauf.md"), "# Nachtlauf\n\nDer Nachtlauf bricht ab, sobald der "
                + "Spool voll ist. Dann hilft nur, den Spool zu leeren.\n");
        Files.writeString(files.resolve("glossar.md"), "# Glossar\n\nSpool: Ablage der Ausgaben.\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  ai:
                    provider: ollama
                    base-url: %s
                    chat-model: %s
                  sources:
                    - id: handbuch
                      type: files
                      title: Handbuch
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), System.getProperty("chat.live.ollama"), chatModel, files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(60, TimeUnit.SECONDS)).isTrue();
        Workspace workspace = context.runtime().workspace();
        assertThat(workspace.aiChat()).as("Chat-Port").isNotNull();
        return workspace;
    }

    private static PageSelection space(Workspace workspace) throws InterruptedException {
        String key = workspace.spaces("handbuch").get(0).key();
        workspace.startSync("handbuch", key, SyncMode.FULL);
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            PageSelection resolved = workspace.resolveSelection(Selection.of(Selection.Root.space("handbuch", key)));
            if (resolved.counts().stored() >= 3) {
                return resolved;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Seiten nicht geladen");
    }

    private static PageKey page(Workspace workspace, PageSelection space, String name) {
        for (Map.Entry<String, java.util.Set<String>> source : space.pages().asMap().entrySet()) {
            for (String pageId : source.getValue()) {
                String title = workspace.cachedPage(source.getKey(), pageId)
                        .map(page -> page.header().metadata().title()).orElse("");
                if (pageId.contains(name) || title.toLowerCase(Locale.ROOT).contains(name)) {
                    return new PageKey(source.getKey(), pageId);
                }
            }
        }
        throw new AssertionError("keine Seite " + name);
    }

    /** Leitet an den echten Port weiter und merkt sich die Anfragen. */
    private static class Recording implements AiChat {

        private final AiChat delegate;
        final List<Request> requests = new CopyOnWriteArrayList<>();

        Recording(AiChat delegate) {
            this.delegate = delegate;
        }

        @Override
        public Task stream(Request request, Listener listener) {
            requests.add(request);
            return delegate.stream(request, listener);
        }
    }

    private static final class Events implements ChatTurn.Listener {

        final CountDownLatch done = new CountDownLatch(1);
        final List<String> using = new CopyOnWriteArrayList<>();
        volatile ChatKnowledge.Context context;
        volatile String completed;
        volatile String failed;
        volatile String cancelled;

        @Override
        public void context(ChatKnowledge.Context context) {
            this.context = context;
        }

        @Override
        public void using(String what) {
            using.add(what);
        }

        @Override
        public void delta(String text) {
        }

        @Override
        public void completed(String text) {
            completed = text;
            done.countDown();
        }

        @Override
        public void failed(String message) {
            failed = message;
            System.out.println("[live] Fehler: " + message);
            done.countDown();
        }

        @Override
        public void cancelled(String partial) {
            cancelled = partial;
            done.countDown();
        }
    }
}
