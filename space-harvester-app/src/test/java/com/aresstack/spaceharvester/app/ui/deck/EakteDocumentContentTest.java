package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.application.extraction.DocumentContent;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.deck.eakte.EakteEntry;
import com.aresstack.spaceharvester.deck.eakte.EakteProfile;
import com.aresstack.spaceharvester.deck.eakte.EakteSession;
import com.aresstack.spaceharvester.deck.eakte.EakteSessions;
import com.aresstack.spaceharvester.deck.eakte.EakteState;
import com.aresstack.spaceharvester.deck.eakte.http.FakeEakteServer;
import com.aresstack.spaceharvester.deck.eakte.http.HttpEakteServer;
import com.aresstack.spaceharvester.deck.http.HttpClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Die E-Akte als Ablage der Dokumentanalyse, gegen den Nachbau {@link FakeEakteServer}; alle Daten erfunden. */
class EakteDocumentContentTest {

    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private final EakteSessions sessions = new EakteSessions(
            new HttpEakteServer(HttpClientFactory.direct(), Duration.ofSeconds(10), null), background);
    private FakeEakteServer fake;

    @AfterEach
    void stop() {
        sessions.closeAll();
        if (fake != null) {
            fake.close();
        }
        background.shutdownNow();
    }

    @Test
    void readsTheShownStateOverAConnectedSession() throws Exception {
        DocumentContent content = new EakteDocumentContent(sessions);
        DocumentRef unconnected = new DocumentRef(EakteBrowser.ORIGIN, "/irgendwo.txt", "");
        assertThat(content.supports(unconnected)).as("ohne Anmeldung").isFalse();

        EakteSession session = connect();
        EakteEntry folder = session.list(session.rootHref().orElseThrow()).get(10, TimeUnit.SECONDS).stream()
                .filter(e -> e.name().equals("Arbeitsvorrat")).findFirst().orElseThrow();
        EakteEntry entry = session.list(folder.href()).get(10, TimeUnit.SECONDS).stream()
                .filter(e -> e.name().equals("Vermerk Bergung.txt")).findFirst().orElseThrow();
        DocumentRef shown = EakteBrowser.documentRef(entry);

        assertThat(content.supports(shown)).isTrue();
        assertThat(content.supports(new DocumentRef("wiki", entry.href(), ""))).as("fremde Ablage").isFalse();
        DocumentContent.Content read = content.read(shown);
        assertThat(read.title()).isEqualTo("Vermerk Bergung.txt");
        assertThat(new String(read.bytes(), StandardCharsets.UTF_8)).contains("Bergung abgeschlossen");

        DocumentRef older = new DocumentRef(EakteBrowser.ORIGIN, entry.href(), "\"alt\"");
        assertThatThrownBy(() -> content.read(older)).isInstanceOf(IOException.class).hasMessageContaining("ETag");
    }

    @Test
    void theFolderOfADocumentEndsWithASlash() {
        assertThat(EakteDocumentContent.parent("/fsc/webdav/Ablage/Logbuch%202026.txt")).isEqualTo("/fsc/webdav/Ablage/");
        assertThat(EakteDocumentContent.parent("/datei.txt")).isEqualTo("/");
    }

    private EakteSession connect() throws Exception {
        fake = new FakeEakteServer();
        EakteSession session = sessions.open(new EakteProfile(fake.webdav(), fake.start(), FakeEakteServer.USER,
                fake.loginHosts()), FakeEakteServer.PASSWORD.toCharArray());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (session.state() != EakteState.CONNECTED && System.nanoTime() < until) {
            if (session.state() == EakteState.FAILED) {
                throw new AssertionError(session.failure().orElse("Anmeldung gescheitert"));
            }
            Thread.onSpinWait();
            Thread.sleep(20);
        }
        assertThat(session.state()).isEqualTo(EakteState.CONNECTED);
        return session;
    }
}
