package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.chat.TerminalChatTools;
import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.TerminalView;
import com.aresstack.spaceharvester.deck.terminal.openterm.FakeTn3270Host;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Was der Mensch vom Maskenassistenten sieht (#1151): Der Chat bekommt im Command Deck die Terminal-Werkzeuge der
 * gewählten Sitzung; was die KI ausfüllt, steht im Terminal, daneben „KI hat … vorbereitet“ und „KI-Eingaben
 * zurücknehmen“; Zurücknehmen leert die Felder wieder, abgeschickt wird nichts.
 */
class MaskAssistUiTest {

    private CommandDeck deck;

    @AfterEach
    void shutdown() {
        if (deck != null) {
            deck.shutdown();
        }
    }

    @Test
    void theChatScopeFillsTheSelectedSessionAndTheUserCanUndoIt() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(List.of(
                new FakeTn3270Host.Line(0, 2, "NEUES DATASET", false),
                new FakeTn3270Host.Line(2, 2, "Name . . .", false),
                FakeTn3270Host.Line.input(2, 16, 20, false, false)), "Cp273")) {
            SwingUtilities.invokeAndWait(() -> {
                deck = new CommandDeck();
                ConnectForm form = deck.terminal().form();
                form.host.setText("127.0.0.1");
                form.port.setText(String.valueOf(host.port()));
                form.tls.setSelected(false); // der Schein-Host spricht kein TLS
                form.submit();
            });
            TerminalSession session = deck.sessions().all().get(0);
            TerminalView view = session.view(TerminalView.class).orElseThrow();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while ((session.state() != SessionState.CONNECTED || !view.text().contains("NEUES DATASET")
                    || view.keyboardLocked()) && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            var scope = deck.chatScope();
            assertThat(scope.area()).contains("Command Deck › Terminal 127.0.0.1");
            assertThat(scope.system()).contains("NICHTS abschicken");
            assertThat(scope.tools().definitions()).extracting(AiChat.Tool::name)
                    .containsExactly(TerminalChatTools.READ, TerminalChatTools.FILL, TerminalChatTools.CURSOR);

            String mask = scope.tools().run(new AiChat.ToolCall("1", TerminalChatTools.READ, "{}"));
            Matcher revision = Pattern.compile("Revision: (\\w+)").matcher(mask);
            assertThat(revision.find()).isTrue();
            String result = scope.tools().run(new AiChat.ToolCall("2", TerminalChatTools.FILL, "{\"revision\":\""
                    + revision.group(1) + "\",\"felder\":[{\"nr\":1,\"wert\":\"USER.NEU\"}]}"));
            assertThat(result).startsWith("Lokal ausgefüllt");
            waitFor(() -> !deck.terminal().assistHintText().isEmpty(), until);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(deck.terminal().assistHintText()).isEqualTo("KI hat 1 Feld vorbereitet – prüfen, dann "
                        + "selbst abschicken");
                assertThat(deck.terminal().undoAssistButton().isVisible()).isTrue();
                assertThat(view.text().split("\n")[2]).contains("USER.NEU");
                deck.terminal().undoAssistButton().doClick();
                assertThat(view.text().split("\n")[2]).doesNotContain("USER.NEU");
                assertThat(deck.terminal().assistHintText()).isEmpty();
                assertThat(deck.terminal().undoAssistButton().isVisible()).isFalse();
            });
            assertThat(host.inputs()).as("nichts abgeschickt").isZero();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long until) throws Exception {
        while (System.nanoTime() < until) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
