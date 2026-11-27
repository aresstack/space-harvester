package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;
import com.aresstack.spaceharvester.app.chat.artifact.DocumentChatTools;
import com.aresstack.spaceharvester.app.chat.artifact.SectionDocument;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Echttest der Schreibumgebung (ADR 0045) gegen einen Ollama-Server, mit erfundenem Text: Das Modell legt über den
 * echten Chat-Turn ein Dokument an, füllt es Abschnitt für Abschnitt und ändert danach gezielt einen Abschnitt, ohne
 * die anderen anzufassen. Läuft nur mit {@code -PchatLiveOllama=http://localhost:11434}; in der CI übersprungen.
 * Das Protokoll (Aufrufe, Zeiten, Dokument) steht in der Konsole.
 */
@EnabledIfSystemProperty(named = "chat.live.ollama", matches = "https?://.+")
class WritingLiveTest {

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
    void theModelWritesSectionBySectionAndChangesOnlyTheMeantSection() throws Exception {
        Workspace workspace = start();
        ChatStore chats = new ChatStore(() -> home.resolve("data").resolve(ChatStore.FOLDER));
        ChatNode node = new ChatNode("handbuch", null, null);
        Path folder = chats.folder(node);
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("Start", 1), 1);
        chats.save(folder, chat);
        ChatArtifactStore store = new ChatArtifactStore(chats);

        Turn first = turn(workspace, store, folder, chat.id(), List.of(), "Schreib mir ein kurzes Dokument "
                + "„Gartenfibel“ über Bohnen im Kleingarten mit den Abschnitten Boden, Aussaat und Ernte, je zwei bis "
                + "drei Sätze.");
        List<ChatArtifact> artifacts = store.list(folder, chat.id());
        assertThat(artifacts).as("ein Dokument angelegt").isNotEmpty();
        ChatArtifact written = artifacts.get(0);
        System.out.println("[live] Dokument nach Auftrag 1 (Stand " + written.revision() + "):\n" + written.content());
        assertThat(written.document().sections().size()).isGreaterThanOrEqualTo(3);
        assertThat(written.document().words()).isGreaterThan(20);
        assertThat(first.answer).contains("artefakt:" + written.id());

        SectionDocument before = written.document();
        String harvest = sectionWith(before, "ernte");
        assertThat(harvest).as("Abschnitt Ernte").isNotNull();
        List<ChatRecord.Message> history = new ArrayList<>(List.of(ChatRecord.Message.user(first.question, 1),
                ChatRecord.Message.assistant(first.answer, false, 2)));
        Turn second = turn(workspace, store, folder, chat.id(), history, "Ändere im Dokument nur den Abschnitt "
                + "Ernte: Geerntet wird ab Mitte Juli, alle zwei Tage pflücken.");
        ChatArtifact after = store.read(folder, chat.id(), written.id());
        System.out.println("[live] Dokument nach Auftrag 2 (Stand " + after.revision() + "):\n" + after.content());
        assertThat(after.document().section(harvest).text().toLowerCase(Locale.ROOT)).contains("juli");
        for (SectionDocument.Section section : before.sections()) {
            if (!section.id().equals(harvest)) {
                assertThat(after.document().section(section.id())).as("unberührt: " + section.id())
                        .isEqualTo(section);
            }
        }
        System.out.println("[live] Auftrag 1: " + first.calls + " Aufrufe, " + first.seconds + " s; Auftrag 2: "
                + second.calls + " Aufrufe, " + second.seconds + " s");
    }

    private static String sectionWith(SectionDocument document, String heading) {
        return document.sections().stream()
                .filter(section -> section.heading().toLowerCase(Locale.ROOT).contains(heading))
                .map(SectionDocument.Section::id).findFirst().orElse(null);
    }

    private record Turn(String question, String answer, List<String> calls, long seconds) {
    }

    private Turn turn(Workspace workspace, ChatArtifactStore store, Path folder, String chatId,
                      List<ChatRecord.Message> history, String question) throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        Events events = new Events(calls);
        AiChat port = new AiChat() {
            @Override
            public Task stream(Request request, Listener listener) {
                return workspace.aiChat().stream(request, listener);
            }
        };
        DocumentChatTools tools = new DocumentChatTools(store, folder, chatId, () -> null, artifact -> { });
        long started = System.nanoTime();
        new ChatTurn(workspace, port, model(), null, history, question, null, null, events, Runnable::run)
                .knowledgeByTools(null).writing(tools).start();
        assertThat(events.done.await(15, TimeUnit.MINUTES)).isTrue();
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        System.out.println("[live] Frage: " + question + "\n[live] Aufrufe: " + calls + "\n[live] Antwort: "
                + events.completed);
        assertThat(events.failed).isNull();
        return new Turn(question, events.completed, calls, seconds);
    }

    private String model() {
        return System.getProperty("chat.live.model", "gemma4:12b");
    }

    private Workspace start() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("leer.md"), "# Leer\n\nNichts.\n");
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
                """.formatted(home.resolve("data"), System.getProperty("chat.live.ollama"), model(), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(60, TimeUnit.SECONDS)).isTrue();
        return context.runtime().workspace();
    }

    private static final class Events implements ChatTurn.Listener {
        final CountDownLatch done = new CountDownLatch(1);
        final List<String> calls;

        Events(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void toolStarted(String key, String name, String what) {
            calls.add(name);
        }
        volatile String completed;
        volatile String failed;

        @Override
        public void context(ChatKnowledge.Context context) {
        }

        @Override
        public void using(String what) {
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
            failed = "abgebrochen";
            done.countDown();
        }
    }
}
