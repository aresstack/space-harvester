package com.aresstack.spaceharvester.app.chat;

import com.aresstack.enterpriseai.astu.approval.ApprovalDecision;
import com.aresstack.enterpriseai.astu.approval.ApprovalRequest;
import com.aresstack.enterpriseai.astu.approval.ApprovalTarget;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolContribution;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolParameter;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolResult;
import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.chat.browser.BrowserChatTools;
import com.aresstack.spaceharvester.app.chat.browser.ChatApprovalPrompt;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Websuche im Turn: Browser-Werkzeuge zusätzlich, Regeln im Systemhinweis, Budget und Ende. */
class ChatTurnWebSearchTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path home;

    private com.aresstack.spaceharvester.app.ui.AppContext context;

    @org.junit.jupiter.api.AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    private com.aresstack.spaceharvester.app.runtime.Workspace workspace() throws Exception {
        context = TerminalMaskFixture.runtime(home, "");
        return TerminalMaskFixture.workspace(context);
    }

    @Test
    void withWebSearchTheBrowserToolsComeAlongAreRunAndTheTurnEndsTheBrowser() throws Exception {
        List<String> navigated = new CopyOnWriteArrayList<>();
        BrowserChatTools browser = new BrowserChatTools(List.of(McpToolContribution.of("browser_navigate",
                "Öffnet eine Adresse", call -> {
                    navigated.add(call.getString("url"));
                    return McpToolResult.ok("Seite „Wetter“ geöffnet: Sonne");
                }, McpToolParameter.string("url", true, "Adresse"))));
        CountDownLatch ended = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        ChatToolset base = new ChatToolset() {
            @Override
            public List<AiChat.Tool> definitions() {
                return List.of(new AiChat.Tool("maske_lesen", "liest", "{\"type\":\"object\",\"properties\":{}}"));
            }

            @Override
            public String run(AiChat.ToolCall call) {
                return "Maske";
            }

            @Override
            public String describe(AiChat.ToolCall call) {
                return "Liest die Maske";
            }
        };
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(new AiChat.ToolCall("c1", "browser_navigate",
                        "{\"url\":\"https://wetter.example\"}"))),
                (request, listener) -> listener.completed("Die Sonne scheint.")));
        TerminalMaskFixture.Events events = new TerminalMaskFixture.Events();

        ChatTurn turn = new ChatTurn(workspace(), port, "modell", null, List.of(), "Wie ist das Wetter?", null, "Test",
                events, Runnable::run).withToolset(base, "System").webSearch(new ChatTurn.WebSearch(browser,
                "[Browserprofil] Extern", () -> order.add("begin"), ended::countDown));
        turn.start();
        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(ended.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(events.failed).isNull();
        assertThat(navigated).containsExactly("https://wetter.example");
        assertThat(order).containsExactly("begin");
        assertThat(TerminalMaskFixture.toolNames(port.requests.get(0))).containsExactly("maske_lesen",
                "browser_navigate");
        assertThat(port.requests.get(0).messages().get(0).text()).startsWith("System")
                .contains("[Browserprofil] Extern");
        List<AiChat.Message> second = port.requests.get(1).messages();
        assertThat(second.get(second.size() - 1).text()).contains("Sonne");
        assertThat(events.using).as("Browser-Schritte gehören in die Browserkarte, nicht in die Wissenskarte")
                .isEmpty();
    }

    @Test
    void stopEndsAnOpenApprovalAsCancelledAndTheToolDoesNotRun() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);
        List<ApprovalDecision.Outcome> outcomes = new CopyOnWriteArrayList<>();
        List<String> executed = new CopyOnWriteArrayList<>();
        // Die Fläche zeigt die Frage, aber niemand antwortet.
        ChatApprovalPrompt prompt = new ChatApprovalPrompt((title, description, always, answer) -> {
            asked.countDown();
            return (approved, outcome) -> { };
        }, Runnable::run);
        BrowserChatTools browser = new BrowserChatTools(List.of(McpToolContribution.of("browser_act",
                "Klickt", call -> {
                    ApprovalDecision decision = prompt.ask(new ApprovalRequest("browser_act",
                            ApprovalTarget.invoke()), 60_000L);
                    outcomes.add(decision.outcome());
                    if (decision.outcome() == ApprovalDecision.Outcome.APPROVED) {
                        executed.add("Speichern");
                    }
                    return McpToolResult.ok("nicht ausgeführt");
                }, McpToolParameter.string("control", true, "Steuerelement"))));
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.toolCalls("", List.of(new AiChat.ToolCall("c1", "browser_act",
                        "{\"control\":\"Speichern\"}"))),
                (request, listener) -> listener.completed("Fertig.")));
        TerminalMaskFixture.Events events = new TerminalMaskFixture.Events();
        ChatTurn turn = new ChatTurn(workspace(), port, "modell", null, List.of(), "Speichere das", null, "Test",
                events, Runnable::run).withToolset(new ChatToolset() {
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
                }, "System").webSearch(new ChatTurn.WebSearch(browser, null, null, null));

        turn.start();
        assertThat(asked.await(30, TimeUnit.SECONDS)).isTrue();
        turn.cancel();

        assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(outcomes).containsExactly(ApprovalDecision.Outcome.CANCELLED);
        assertThat(executed).isEmpty();
        assertThat(events.cancelled).isNotNull();
    }

    @Test
    void aWebPageIsEvaluatedByASubAgentForTheGivenQuestionOrTheUsersQuestion() throws Exception {
        // Angelo 24.11.2026: Webtreffer wertet je Seite ein eigener Agent aus (browser_open_many mit question).
        ScriptedChat port = new ScriptedChat(List.of(
                (request, listener) -> listener.completed(" Regen am Nachmittag. "),
                (request, listener) -> listener.completed("Trägt nichts bei.")));
        ChatTurn turn = new ChatTurn(workspace(), port, "modell", null, List.of(), "Wie ist das Wetter?", null,
                "Test", new TerminalMaskFixture.Events(), Runnable::run);

        assertThat(turn.evaluatePage("Regnet es?", "Wetter", "https://wetter.example/", "Regen ab 14 Uhr"))
                .isEqualTo("Regen am Nachmittag.");
        assertThat(turn.evaluatePage("", "", "https://leer.example/", "Impressum")).isEqualTo("Trägt nichts bei.");

        assertThat(port.requests.get(0).messages().get(0).text()).isEqualTo(ChatKnowledge.SUB_AGENT_PROMPT);
        assertThat(port.requests.get(0).messages().get(1).text()).contains("Frage: Regnet es?")
                .contains("Wetter (https://wetter.example/)").contains("Regen ab 14 Uhr");
        assertThat(port.requests.get(1).messages().get(1).text()).contains("Frage: Wie ist das Wetter?")
                .contains("(ohne Titel)");
    }
}
