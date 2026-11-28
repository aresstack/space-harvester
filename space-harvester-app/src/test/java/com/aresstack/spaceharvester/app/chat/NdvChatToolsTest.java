package com.aresstack.spaceharvester.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.ndv.FakeNdvServer;
import com.aresstack.spaceharvester.deck.ndv.NdvProfile;
import com.aresstack.spaceharvester.deck.ndv.NdvSession;
import com.aresstack.spaceharvester.deck.ndv.NdvSessions;
import org.junit.jupiter.api.Test;

/** Chat-Werkzeuge im NDV-Reiter: suchen und lesen auf dem Server der gewählten Sitzung, nichts schreiben. */
class NdvChatToolsTest {

    @Test
    void searchesLibrariesAndObjectsAndReadsSourceOfTheChosenSession() {
        FakeNdvServer server = FakeNdvServer.sample().pageSize(2);
        NdvSessions sessions = new NdvSessions(server, Runnable::run);
        NdvSession session = sessions.open(new NdvProfile("10.0.0.7", 2700), "Testhost NDV",
                () -> new ServerCredentials(new com.aresstack.spaceharvester.deck.access.ServerAccount("Testhost NDV",
                        "10.0.0.7", "PILOT"), "geheim".toCharArray()));
        NdvChatTools tools = new NdvChatTools(session, () -> session);

        String libraries = tools.run(new AiChat.ToolCall("1", NdvChatTools.LIBRARIES, "{\"muster\":\"LA*\"}"));
        String objects = tools.run(new AiChat.ToolCall("2", NdvChatTools.SEARCH,
                "{\"bibliothek\":\"lager\",\"muster\":\"LAGER0*\"}"));
        String source = tools.run(new AiChat.ToolCall("3", NdvChatTools.READ,
                "{\"bibliothek\":\"LAGER\",\"name\":\"lager002\"}"));
        String missing = tools.run(new AiChat.ToolCall("4", NdvChatTools.READ,
                "{\"bibliothek\":\"LAGER\",\"name\":\"GIBTSNICHT\"}"));

        assertThat(libraries).contains("LAGER").doesNotContain("KASSE");
        assertThat(objects).contains("LAGER001", "LAGER002").doesNotContain("LAGERN01");
        assertThat(source).contains("CALLNAT 'LAGERN01'");
        assertThat(missing).startsWith("Kein Objekt");
        assertThat(new NdvChatTools(null, () -> null).run(new AiChat.ToolCall("5", NdvChatTools.SEARCH, "{}")))
                .isEqualTo(NdvChatTools.NO_SESSION);
        assertThat(new NdvChatTools(session, () -> null).run(new AiChat.ToolCall("6", NdvChatTools.SEARCH, "{}")))
                .contains("andere NDV-Sitzung");
    }

    @Test
    void searchReportsHowManyObjectsItFoundSoFar() {
        FakeNdvServer server = FakeNdvServer.sample().pageSize(1);
        NdvSessions sessions = new NdvSessions(server, Runnable::run);
        NdvSession session = sessions.open(new NdvProfile("10.0.0.7", 2700), "Testhost NDV",
                () -> new ServerCredentials(new com.aresstack.spaceharvester.deck.access.ServerAccount("Testhost NDV",
                        "10.0.0.7", "PILOT"), "geheim".toCharArray()));
        NdvChatTools tools = new NdvChatTools(session, () -> session);
        java.util.List<String> reports = new java.util.ArrayList<>();

        ToolProgress.within(reports::add, () -> tools.run(new AiChat.ToolCall("1", NdvChatTools.SEARCH,
                "{\"bibliothek\":\"LAGER\",\"muster\":\"LAGER0*\"}")));

        assertThat(reports).first().isEqualTo("Sucht NDV-Objekte in LAGER …");
        assertThat(reports).contains("Sucht NDV-Objekte in LAGER · 1 gefunden", "Sucht NDV-Objekte in LAGER · 2 gefunden");
        // Außerhalb eines Turns geht die Meldung ins Leere.
        ToolProgress.report("ohne Turn");
        assertThat(reports).doesNotContain("ohne Turn");
    }
}
