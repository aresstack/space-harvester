package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiChatException;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ein Turn holt das Wissen der Auswahl aus dem Bestand (wenige Seiten: ganz), streamt die Antwort und meldet Deltas,
 * Abschluss und Fehler auf dem UI-Thread; der Verlauf geht ohne abgebrochene Antworten an das Modell.
 */
class ChatTurnTest {

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void theSelectedPagesGoIntoTheSystemPromptAndTheAnswerStreams() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("datenhaus.md"), "# Datenhaus\n\nJedes Datenprodukt hat ein "
                + "Berechtigungsmanagement.\n");
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
        Workspace workspace = context.runtime().workspace();
        assertThat(workspace.aiChat()).as("ohne Chat-Modell kein Port").isNull();
        String space = workspace.spaces("handbuch").get(0).key();
        // Vollständiger Abgleich lädt die Inhalte sofort; SYNC_CACHE katalogisiert nur, der Inhalt käme erst über die
        // Hintergrundfüllung nach ihrer Anlaufzeit (30 s) – ein Wettlauf mit dem Warten unten.
        workspace.startSync("handbuch", space, SyncMode.FULL);
        PageSelection scope = await(() -> {
            PageSelection resolved = workspace.resolveSelection(Selection.of(Selection.Root.space("handbuch", space)));
            return resolved.counts().stored() > 0 ? resolved : null;
        });

        FakeAiChat port = new FakeAiChat(List.of("Ein ", "Berechtigungs", "management."), null);
        Events events = new Events();
        List<ChatRecord.Message> history = List.of(ChatRecord.Message.user("Hallo", 1),
                ChatRecord.Message.assistant("abgebrochen", true, 2), ChatRecord.Message.assistant("Hi", false, 3));
        ChatTurn turn = new ChatTurn(workspace, port, "modell", "low", history, "Was hat jedes Datenprodukt?", scope,
                "Handbuch · alle Spaces · 1 Seite", events, Runnable::run);
        turn.start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(events.context.accepted()).hasSize(1);
        assertThat(events.context.accepted().get(0).text()).contains("Berechtigungsmanagement");
        assertThat(events.deltas).containsExactly("Ein ", "Berechtigungs", "management.");
        assertThat(events.completed).isEqualTo("Ein Berechtigungsmanagement.");
        AiChat.Request request = port.lastRequest;
        assertThat(request.model()).isEqualTo("modell");
        assertThat(request.reasoningEffort()).isEqualTo("low");
        List<AiChat.Message> messages = request.messages();
        assertThat(messages.get(0).text()).startsWith(ChatKnowledge.SYSTEM_PROMPT)
                .contains("Gewählter Bereich: Handbuch · alle Spaces · 1 Seite")
                .contains("[1] ").contains("Berechtigungsmanagement").doesNotContain(ChatKnowledge.NO_CONTEXT);
        assertThat(messages).extracting(AiChat.Message::text)
                .containsSubsequence("Hallo", "Hi", "Was hat jedes Datenprodukt?").doesNotContain("abgebrochen");
    }

    @Test
    void withoutKnowledgeTheModelStillAnswersAndErrorsAreReadable() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: " + home.resolve("data") + "\n");
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        Workspace workspace = context.runtime().workspace();

        FakeAiChat port = new FakeAiChat(List.of(),
                new AiChatException(AiChatException.Kind.TRANSPORT, 503, "HTTP 503", null));
        Events events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Frage", null, null, events, Runnable::run).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(events.context.isEmpty()).isTrue();
        assertThat(port.lastRequest.messages().get(0).text()).startsWith(ChatKnowledge.SYSTEM_PROMPT)
                .endsWith(ChatKnowledge.NO_CONTEXT).doesNotContain("Gewählter Bereich");
        assertThat(port.lastRequest.reasoningEffort()).isNull();
        assertThat(events.failed).startsWith("Der KI-Dienst ist nicht erreichbar. (Status 503)")
                .contains("Technische Ursache: HTTP 503");
    }

    @Test
    void anOverviewQuestionWithoutHitsGetsTheStartPagesOfTheArea() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        for (int i = 1; i <= 5; i++) {
            Files.writeString(files.resolve("kapitel-" + i + ".md"), "# Kapitel " + i + "\n\nInhalt des Kapitels "
                    + i + " über das Datenhaus.\n");
        }
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
        Workspace workspace = context.runtime().workspace();
        String space = workspace.spaces("handbuch").get(0).key();
        workspace.startSync("handbuch", space, SyncMode.FULL);
        PageSelection scope = await(() -> {
            PageSelection resolved = workspace.resolveSelection(Selection.of(Selection.Root.space("handbuch", space)));
            return resolved.counts().stored() >= 5 ? resolved : null;
        });

        FakeAiChat port = new FakeAiChat(List.of("Ein Handbuch."), null);
        Events events = new Events();
        // Mehr als drei Seiten: die Suche läuft, findet zu „xyzzy“ aber nichts; dann kommt der Überblick.
        new ChatTurn(workspace, port, "modell", null, List.of(), "xyzzy?", scope, "Space " + space, events,
                Runnable::run).start();
        assertThat(events.done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(events.context.accepted()).isNotEmpty();
        assertThat(events.context.accepted().get(0).title()).startsWith("Überblick: Startseiten");
        assertThat(events.context.accepted().get(0).text()).contains("kapitel-1.md").contains("kapitel-5.md");
        String system = port.lastRequest.messages().get(0).text();
        assertThat(system).contains("Gewählter Bereich: Space " + space).contains("Inhalt des Kapitels")
                .doesNotContain(ChatKnowledge.NO_CONTEXT);
    }

    @Test
    void whatsNewListsOnlyChangedPagesOfTheSelectionSinceEachSourcesOwnMark() throws Exception {
        Path a = Files.createDirectories(home.resolve("a"));
        Path b = Files.createDirectories(home.resolve("b"));
        Files.writeString(a.resolve("alt-a.md"), "# Alt A\n\nSteht schon lange da.\n");
        Files.writeString(b.resolve("alt-b.md"), "# Alt B\n\nSteht schon lange da.\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: a
                      type: files
                      title: Quelle A
                      options:
                        directory: %s
                    - id: b
                      type: files
                      title: Quelle B
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), a, b));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        Workspace workspace = context.runtime().workspace();
        String spaceA = workspace.spaces("a").get(0).key();
        String spaceB = workspace.spaces("b").get(0).key();

        // Noch kein Abgleich: keine Marke, mit der sich vergleichen ließe; das Modell bleibt ungerufen.
        PageSelection fresh = workspace.resolveSelection(Selection.of(Selection.Root.source("a")));
        FakeAiChat port = new FakeAiChat(List.of("Antwort"), null);
        Events events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Was ist neu?", fresh, "Quelle A",
                ChatTurn.Kind.WHATS_NEW, events, Runnable::run).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(events.completed).isEqualTo(ChatTurn.NO_MARK);
        assertThat(port.lastRequest).isNull();

        // Erster (manueller) Abgleich beider Quellen: Erstladen zählt nicht als Änderung.
        workspace.startSync("a", spaceA, SyncMode.FULL);
        workspace.startSync("b", spaceB, SyncMode.FULL);
        awaitMarks(workspace, "a", 1);
        awaitMarks(workspace, "b", 1);
        PageSelection onlyA = await(() -> {
            PageSelection resolved = workspace.resolveSelection(Selection.of(Selection.Root.source("a")));
            return resolved.counts().stored() > 0 ? resolved : null;
        });
        events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Was ist neu?", onlyA, "Quelle A",
                ChatTurn.Kind.WHATS_NEW, events, Runnable::run).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(events.completed).isEqualTo(ChatTurn.NOTHING_NEW);
        assertThat(port.lastRequest).as("ohne Änderung kein Modellaufruf").isNull();

        // Je Quelle eine neue Seite und ein eigener Abgleich: zwei Quellen, zwei verschiedene Marken.
        Files.writeString(a.resolve("neu-a.md"), "# Neu A\n\nDie neue Seite der Quelle A.\n");
        workspace.startSync("a", spaceA, SyncMode.FULL);
        awaitMarks(workspace, "a", 2);
        Files.writeString(b.resolve("neu-b.md"), "# Neu B\n\nDie neue Seite der Quelle B.\n");
        workspace.startSync("b", spaceB, SyncMode.FULL);
        awaitMarks(workspace, "b", 2);

        // Nur Quelle A gewählt: die Änderung in B kommt nicht vor.
        onlyA = await(() -> {
            PageSelection resolved = workspace.resolveSelection(Selection.of(Selection.Root.source("a")));
            return resolved.counts().stored() >= 2 ? resolved : null;
        });
        events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Was ist neu?", onlyA, "Quelle A",
                ChatTurn.Kind.WHATS_NEW, events, Runnable::run).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(events.completed).isEqualTo("Antwort");
        assertThat(events.context.accepted()).extracting(ChatKnowledge.Excerpt::title)
                .anySatisfy(title -> assertThat(title).startsWith("neu-a.md (geändert am "))
                .noneSatisfy(title -> assertThat(title).contains("neu-b.md"))
                .noneSatisfy(title -> assertThat(title).contains("alt-a.md"));
        String asked = port.lastRequest.messages().get(port.lastRequest.messages().size() - 1).text();
        assertThat(asked).startsWith("Was ist neu?").contains("Grundlage: 1 geänderte Seite seit dem ")
                .contains("Erfinde keine Unterschiede");

        // Beide Quellen: jede seit ihrer eigenen Marke, die jüngste Änderung zuerst.
        PageSelection both = workspace.resolveSelection(Selection.of(Selection.Root.source("a"),
                Selection.Root.source("b")));
        events = new Events();
        new ChatTurn(workspace, port, "modell", null, List.of(), "Was ist neu?", both, "alle Wissensquellen",
                ChatTurn.Kind.WHATS_NEW, events, Runnable::run).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(events.context.accepted()).extracting(ChatKnowledge.Excerpt::title).hasSize(2)
                .satisfies(titles -> {
                    assertThat(titles.get(0)).startsWith("neu-b.md (geändert am ");
                    assertThat(titles.get(1)).startsWith("neu-a.md (geändert am ");
                });
        asked = port.lastRequest.messages().get(port.lastRequest.messages().size() - 1).text();
        assertThat(asked).contains("Grundlage: 2 geänderte Seiten seit dem ");
    }

    /** Wartet, bis die Quelle so viele abgeschlossene Updates (Marken) im Änderungsprotokoll hat. */
    private static void awaitMarks(Workspace workspace, String sourceId, int marks) throws InterruptedException {
        await(() -> workspace.changeUpdates(sourceId).stream().filter(update -> !update.open()).count() >= marks
                ? Boolean.TRUE : null);
    }

    /** Chat-Modell für Tests (wie {@code FakeChatCompletionPort} im Client): Deltas oder ein Fehler, Anfrage gemerkt. */
    private static final class FakeAiChat implements AiChat {

        private final List<String> deltas;
        private final AiChatException error;
        volatile Request lastRequest;

        FakeAiChat(List<String> deltas, AiChatException error) {
            this.deltas = deltas;
            this.error = error;
        }

        @Override
        public Task stream(Request request, Listener listener) {
            lastRequest = request;
            if (error != null) {
                listener.failed(error);
            } else {
                deltas.forEach(listener::delta);
                listener.completed(String.join("", deltas));
            }
            return () -> { };
        }
    }

    private static final class Events implements ChatTurn.Listener {

        final List<String> deltas = new ArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        ChatKnowledge.Context context;
        String completed;
        String failed;
        String cancelled;

        @Override
        public void context(ChatKnowledge.Context context) {
            this.context = context;
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
}
