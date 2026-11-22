package com.aresstack.spaceharvester.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.AppContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Echttest des Maskenassistenten (#1151) gegen einen Ollama-Server (nur HTTP-API, Streaming), mit dem Schein-Host
 * und einer erfundenen Dataset-Maske. Läuft nur mit {@code -PchatLiveOllama=http://localhost:11434}
 * [{@code -PchatLiveModel=gemma4:12b}]; in der CI übersprungen. Geprüft: Das Modell liest die Maske, füllt die
 * Felder lokal, schickt nichts ab und sagt dem Benutzer, dass er selbst abschickt.
 */
@EnabledIfSystemProperty(named = "chat.live.ollama", matches = "https?://.+")
class TerminalMaskLiveTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

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
    void aRealModelFillsTheDatasetMaskWithoutSubmitting() throws Exception {
        String model = System.getProperty("chat.live.model", "gemma4:12b");
        context = TerminalMaskFixture.runtime(home, """
                  ai:
                    provider: ollama
                    base-url: %s
                    chat-model: %s
                """.formatted(System.getProperty("chat.live.ollama"), model));
        Workspace workspace = TerminalMaskFixture.workspace(context);
        try (TerminalMaskFixture deck = new TerminalMaskFixture()) {
            TerminalMaskFixture.Recording port = new TerminalMaskFixture.Recording(workspace.aiChat());
            TerminalMaskFixture.Events events = new TerminalMaskFixture.Events();
            String area = "Command Deck › Terminal 127.0.0.1";
            int sentBefore = deck.host.received().length;

            new ChatTurn(workspace, port, model, null, List.of(),
                    "Ich möchte ein neues Dataset anlegen. Füll mir die Maske aus: Name USER.TEST.DATA, "
                            + "Space units TRACKS, Primary quantity 10, Record format FB, Record length 80. "
                            + "Was muss ich danach tun?", null, area, events, Runnable::run)
                    .withToolset(new TerminalChatTools(deck.session, () -> deck.session),
                            TerminalChatTools.systemMessage(area)).start();
            boolean finished = events.done.await(10, TimeUnit.MINUTES);
            Thread.sleep(300);

            System.out.println("[live] Modell " + model + ", Anfragen " + port.requests.size() + ", Werkzeuge "
                    + events.using);
            System.out.println("[live] Bildschirm:\n" + deck.view.text());
            System.out.println("[live] Antwort: " + events.completed + (events.failed == null ? ""
                    : " / Fehler: " + events.failed));
            assertThat(finished).isTrue();
            assertThat(events.failed).isNull();
            assertThat(events.using).contains("Liest die Terminal-Maske", "Füllt Felder im Terminal aus");
            assertThat(deck.line(2)).contains("USER.TEST.DATA");
            assertThat(deck.line(4)).contains("TRACKS");
            assertThat(deck.line(5)).contains("10");
            assertThat(deck.line(6)).contains("FB");
            assertThat(deck.line(7)).contains("80");
            assertThat(deck.host.received().length).as("kein Byte an den Host").isEqualTo(sentBefore);
            assertThat(deck.host.inputs()).isZero();
            assertThat(events.completed.toLowerCase(Locale.ROOT)).contains("enter");
        }
    }
}
