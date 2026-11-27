package com.aresstack.spaceharvester.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiChat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Chat nutzt die Spezialmodelle (Angelo 27.11.2026): Schaubilder über die Bildgenerierung. */
class ModelChatToolsTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"></svg>";

    @TempDir
    java.nio.file.Path home;

    private com.aresstack.spaceharvester.app.ui.AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void theDiagramStandsBelowTheAnswerAndNeverPassesThroughTheChatModel() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(new AiChat.ToolCall("c1",
                        ModelChatTools.DIAGRAM, "{\"thema\":\"Ablauf\",\"inhalt\":\"Abgleich, Index, Chat\"}"))),
                (request, listener) -> listener.completed("Das Schaubild zeigt den Ablauf.")));
        TerminalMaskFixture.Events events = new TerminalMaskFixture.Events();
        context = TerminalMaskFixture.runtime(home, "");
        ChatTurn turn = new ChatTurn(TerminalMaskFixture.workspace(context), port, "modell", null, List.of(),
                "Zeichne den Ablauf", null, "Test", events, Runnable::run).withToolset(empty(), "System")
                .diagrams((topic, material) -> {
                    asked.add(topic + " | " + material);
                    return Optional.of(SVG);
                });

        turn.start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(events.failed).isNull();
        assertThat(asked).containsExactly("Ablauf | Abgleich, Index, Chat");
        assertThat(TerminalMaskFixture.toolNames(port.requests.get(0))).contains(ModelChatTools.DIAGRAM);
        List<AiChat.Message> second = port.requests.get(1).messages();
        assertThat(second.get(second.size() - 1).text()).isEqualTo(ModelChatTools.DIAGRAM_DRAWN);
        assertThat(events.completed).isEqualTo("Das Schaubild zeigt den Ablauf." + ModelChatTools.block(SVG));
        assertThat(events.deltas.toString()).endsWith(ModelChatTools.block(SVG));
    }

    @Test
    void withoutDiagramsTheToolIsNotOffered() throws Exception {
        assertThat(ModelChatTools.with(empty(), null, text -> { }).definitions()).isEmpty();
    }

    @Test
    void withoutImageModelTheChatModelIsToldWhy() {
        List<String> below = new CopyOnWriteArrayList<>();
        String result = ModelChatTools.diagram(new AiChat.ToolCall("c1", ModelChatTools.DIAGRAM,
                "{\"thema\":\"x\",\"inhalt\":\"y\"}"), (topic, material) -> Optional.empty(), below::add);

        assertThat(result).isEqualTo(ModelChatTools.DIAGRAM_NONE);
        assertThat(below).isEmpty();
    }

    @Test
    void theHistoryCarriesOnlyANoteInsteadOfTheSvg() {
        String stored = "Hier das Bild." + ModelChatTools.block(SVG) + "\nNoch etwas.";

        assertThat(ModelChatTools.withoutDiagrams(stored)).isEqualTo("Hier das Bild.\n\n[Schaubild]\n\nNoch etwas.");
        assertThat(ModelChatTools.withoutDiagrams("Ohne Bild.")).isEqualTo("Ohne Bild.");
    }

    private static ChatToolset empty() {
        return new ChatToolset() {
            @Override
            public List<AiChat.Tool> definitions() {
                return List.of();
            }

            @Override
            public String run(AiChat.ToolCall call) {
                return "";
            }

            @Override
            public String describe(AiChat.ToolCall call) {
                return "";
            }
        };
    }
}
