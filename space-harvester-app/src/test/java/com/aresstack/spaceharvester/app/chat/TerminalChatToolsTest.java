package com.aresstack.spaceharvester.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.ui.AppContext;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Maskenassistent im globalen Chat (#1151): Im Command Deck bekommt die Frage genau die drei Terminal-Werkzeuge,
 * keine Wissenswerkzeuge; ausgefüllt wird lokal, an den Host geht kein Byte, und ein Werkzeug zum Abschicken gibt es
 * nicht – auch nicht unter anderem Namen.
 */
class TerminalChatToolsTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path home;

    @Test
    void readFillAndMoveStayLocalAndThereIsNoWayToSubmit() throws Exception {
        try (TerminalMaskFixture deck = new TerminalMaskFixture()) {
            TerminalChatTools tools = new TerminalChatTools(deck.session, () -> deck.session);
            int sentBefore = deck.host.received().length;

            String mask = tools.run(new AiChat.ToolCall("1", TerminalChatTools.READ, "{}"));
            String revision = revision(mask);
            String filled = tools.run(new AiChat.ToolCall("2", TerminalChatTools.FILL, "{\"revision\":\"" + revision
                    + "\",\"felder\":[{\"nr\":1,\"wert\":\"USER.TEST.DATA\"},{\"nr\":3,\"wert\":\"10\"}]}"));
            String moved = tools.run(new AiChat.ToolCall("3", TerminalChatTools.CURSOR, "{\"revision\":\""
                    + revision + "\",\"bewegung\":\"neue_zeile\"}"));
            String password = tools.run(new AiChat.ToolCall("4", TerminalChatTools.FILL, "{\"revision\":\""
                    + revision + "\",\"felder\":[{\"nr\":6,\"wert\":\"geheim\"}]}"));
            String enter = tools.run(new AiChat.ToolCall("5", "enter_druecken", "{}"));
            String pf3 = tools.run(new AiChat.ToolCall("6", "taste", "{\"taste\":\"PF3\"}"));
            Thread.sleep(300);

            assertThat(mask).contains("Revision: ").contains("01|   ALLOCATE NEW DATA SET")
                    .contains("1. Zeile 3, Spalte 26, Länge 44, Beschriftung \"Data Set Name\"")
                    .contains("nur Ziffern").contains("Kennwortfeld").contains("kann nur der Benutzer");
            assertThat(filled).startsWith("Lokal ausgefüllt").contains("Feld 1 = \"USER.TEST.DATA\"");
            assertThat(moved).contains("nichts abgeschickt");
            assertThat(password).startsWith("Abgelehnt");
            assertThat(enter).contains("Unbekanntes Werkzeug");
            assertThat(pf3).contains("Unbekanntes Werkzeug");
            assertThat(deck.line(2)).contains("USER.TEST.DATA");
            assertThat(deck.line(5)).contains("10");
            assertThat(deck.host.received().length).as("kein Byte an den Host").isEqualTo(sentBefore);
            assertThat(deck.host.inputs()).isZero();
            assertThat(tools.definitions()).extracting(AiChat.Tool::name).containsExactly(TerminalChatTools.READ,
                    TerminalChatTools.FILL, TerminalChatTools.CURSOR);
        }
    }

    @Test
    void withoutSessionOrAfterSwitchingSessionsNothingIsReadOrWritten() throws Exception {
        try (TerminalMaskFixture deck = new TerminalMaskFixture()) {
            assertThat(new TerminalChatTools(null, () -> null).run(new AiChat.ToolCall("1",
                    TerminalChatTools.READ, "{}"))).isEqualTo(TerminalChatTools.NO_SESSION);
            assertThat(new TerminalChatTools(deck.session, () -> null).run(new AiChat.ToolCall("1",
                    TerminalChatTools.READ, "{}"))).isEqualTo(TerminalChatTools.SWITCHED);
            deck.session.disconnect();
            assertThat(new TerminalChatTools(deck.session, () -> deck.session).run(new AiChat.ToolCall("1",
                    TerminalChatTools.READ, "{}"))).startsWith("Abgelehnt").contains("nicht verbunden");
        }
    }

    @Test
    void inTheCommandDeckTheTurnOffersOnlyTheTerminalToolsAndFillsTheMask() throws Exception {
        AppContext context = TerminalMaskFixture.runtime(home, "");
        try (TerminalMaskFixture deck = new TerminalMaskFixture()) {
            TerminalChatTools tools = new TerminalChatTools(deck.session, () -> deck.session);
            String system = TerminalChatTools.systemMessage("Command Deck › Terminal 127.0.0.1");
            ScriptedChat port = new ScriptedChat(List.of(
                    (request, listener) -> listener.toolCalls("", List.of(
                            new AiChat.ToolCall("c1", TerminalChatTools.READ, "{}"))),
                    (request, listener) -> {
                        String revision = revision(request.messages().get(request.messages().size() - 1).text());
                        listener.toolCalls("", List.of(new AiChat.ToolCall("c2", TerminalChatTools.FILL,
                                "{\"revision\":\"" + revision + "\",\"felder\":[{\"nr\":1,\"wert\":\"A.B.C\"}]}")));
                    },
                    (request, listener) -> {
                        listener.delta("Feld ausgefüllt. Bitte prüfen und mit Enter abschicken.");
                        listener.completed("Feld ausgefüllt. Bitte prüfen und mit Enter abschicken.");
                    }));
            TerminalMaskFixture.Events events = new TerminalMaskFixture.Events();

            new ChatTurn(TerminalMaskFixture.workspace(context), port, "modell", null, List.of(),
                    "Trag als Dataset A.B.C ein.", null, "Command Deck › Terminal 127.0.0.1", events, Runnable::run)
                    .withToolset(tools, system).start();
            assertThat(events.done.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(events.failed).isNull();
            assertThat(port.requests).hasSize(3);
            assertThat(TerminalMaskFixture.toolNames(port.requests.get(0))).containsExactly(TerminalChatTools.READ,
                    TerminalChatTools.FILL, TerminalChatTools.CURSOR);
            assertThat(port.requests.get(0).messages().get(0).text()).isEqualTo(system);
            assertThat(port.requests.get(1).messages().get(port.requests.get(1).messages().size() - 1).text())
                    .contains("ALLOCATE NEW DATA SET");
            assertThat(events.using).containsExactly("Liest die Terminal-Maske", "Füllt Felder im Terminal aus");
            assertThat(deck.line(2)).contains("A.B.C");
            assertThat(deck.host.inputs()).isZero();
            assertThat(deck.session.assistant().edits()).hasSize(1);
        } finally {
            context.shutdown();
        }
    }

    static String revision(String mask) {
        Matcher matcher = Pattern.compile("Revision: (\\w+)").matcher(mask);
        assertThat(matcher.find()).as("Revision in: " + mask).isTrue();
        return matcher.group(1);
    }

    private static final class ScriptedChat implements AiChat {
        private final List<BiConsumer<Request, Listener>> script;
        final List<Request> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

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
}
