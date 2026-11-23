package com.aresstack.spaceharvester.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.ndv.FakeNdvServer;
import com.aresstack.spaceharvester.deck.ndv.NdvProfile;
import com.aresstack.spaceharvester.deck.ndv.NdvSession;
import com.aresstack.spaceharvester.deck.ndv.NdvSessions;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Echttest des Chats im Command Deck › NDV gegen einen Ollama-Server (Streaming) mit dem erfundenen NDV-Schein-Server:
 * Das Modell soll ein Programm erklären, das nicht offen ist, also suchen und lesen. Läuft nur mit
 * {@code -PchatLiveOllama=http://localhost:11434} [{@code -PchatLiveModel=gemma4:12b}]; in der CI übersprungen.
 */
@EnabledIfSystemProperty(named = "chat.live.ollama", matches = "https?://.+")
class DeckNdvLiveTest {

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
    void aRealModelFindsAndExplainsANaturalProgram() throws Exception {
        String model = System.getProperty("chat.live.model", "gemma4:12b");
        context = TerminalMaskFixture.runtime(home, """
                  ai:
                    provider: ollama
                    base-url: %s
                    chat-model: %s
                """.formatted(System.getProperty("chat.live.ollama"), model));
        Workspace workspace = TerminalMaskFixture.workspace(context);
        NdvSessions sessions = new NdvSessions(FakeNdvServer.sample(), Runnable::run);
        NdvSession session = sessions.open(new NdvProfile("10.0.0.7", 2700), "Testhost NDV",
                () -> new ServerCredentials(new ServerAccount("Testhost NDV", "10.0.0.7", "PILOT"),
                        "geheim".toCharArray()));
        DeckChatTools tools = new DeckChatTools(() -> null, () -> true, new NdvChatTools(session, () -> session));
        String area = "Command Deck › NDV";
        String system = DeckChatTools.systemMessage(area, "Dort sieht er Bibliotheken und Objekte eines Natural "
                + "Development Servers.", NdvChatTools.rules(), "Bibliothek LAGER ist geöffnet, kein Objekt offen.");

        TerminalMaskFixture.Recording port = new TerminalMaskFixture.Recording(workspace.aiChat());
        TerminalMaskFixture.Events events = new TerminalMaskFixture.Events();
        new ChatTurn(workspace, port, model, null, List.of(), "Was macht das Programm LAGER002 und welches "
                + "Subprogramm ruft es auf?", null, area, events, Runnable::run).withToolset(tools, system).start();
        boolean finished = events.done.await(10, TimeUnit.MINUTES);

        System.out.println("[live] Modell " + model + ", Anfragen " + port.requests.size() + ", Werkzeuge "
                + events.using);
        System.out.println("[live] Antwort: " + events.completed + (events.failed == null ? ""
                : " / Fehler: " + events.failed));
        assertThat(finished).isTrue();
        assertThat(events.failed).isNull();
        assertThat(events.using).contains("Liest ein NDV-Objekt");
        assertThat(events.completed.toUpperCase(Locale.ROOT)).contains("LAGERN01");
    }
}
