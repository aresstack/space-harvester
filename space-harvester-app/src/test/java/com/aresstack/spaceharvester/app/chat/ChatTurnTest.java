package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiChatException;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.Selection;
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
        workspace.startSync("handbuch", space);
        PageSelection scope = await(() -> {
            PageSelection resolved = workspace.resolveSelection(Selection.of(Selection.Root.space("handbuch", space)));
            return resolved.counts().stored() > 0 ? resolved : null;
        });

        FakeAiChat port = new FakeAiChat(List.of("Ein ", "Berechtigungs", "management."), null);
        Events events = new Events();
        List<ChatRecord.Message> history = List.of(ChatRecord.Message.user("Hallo", 1),
                ChatRecord.Message.assistant("abgebrochen", true, 2), ChatRecord.Message.assistant("Hi", false, 3));
        ChatTurn turn = new ChatTurn(workspace, port, "modell", "low", history, "Was hat jedes Datenprodukt?", scope,
                events, Runnable::run);
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
                .contains("[1] ").contains("Berechtigungsmanagement");
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
        new ChatTurn(workspace, port, "modell", null, List.of(), "Frage", null, events, Runnable::run).start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(events.context.isEmpty()).isTrue();
        assertThat(port.lastRequest.messages().get(0).text()).isEqualTo(ChatKnowledge.SYSTEM_PROMPT);
        assertThat(port.lastRequest.reasoningEffort()).isNull();
        assertThat(events.failed).startsWith("Der KI-Dienst ist nicht erreichbar. (Status 503)")
                .contains("Technische Ursache: HTTP 503");
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
