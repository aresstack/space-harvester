package com.aresstack.spaceharvester.deck.eakte.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.deck.eakte.EakteConnection;
import com.aresstack.spaceharvester.deck.eakte.EakteEntry;
import com.aresstack.spaceharvester.deck.eakte.EakteProfile;
import com.aresstack.spaceharvester.deck.eakte.LoginRefusedException;
import com.aresstack.spaceharvester.deck.eakte.SessionExpiredException;
import com.aresstack.spaceharvester.deck.http.HttpClientFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Anmeldung und WebDAV gegen den Nachbau {@link FakeEakteServer}; alle Daten erfunden. */
class HttpEakteServerTest {

    private FakeEakteServer fake;
    private final List<String> trace = new CopyOnWriteArrayList<>();
    private HttpEakteServer server;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeEakteServer();
        server = new HttpEakteServer(HttpClientFactory.direct(), Duration.ofSeconds(10), trace::add);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    private EakteProfile profile(boolean withStart) {
        return new EakteProfile(fake.webdav(), withStart ? fake.start() : null, FakeEakteServer.USER,
                fake.loginHosts());
    }

    @Test
    void logsInLikeTheTestProgramAndListsTheRootWithOnePasswordPost() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            List<EakteEntry> root = connection.list(connection.rootHref());
            assertThat(root).extracting(EakteEntry::name)
                    .containsExactly("Ablage", "Arbeitsvorrat", "Übersicht Akten.txt");
            assertThat(root.get(0).folder()).isTrue();
            assertThat(root.get(2).size()).isPositive();
            assertThat(root.get(2).modified()).isNotNull();
        }
        assertThat(fake.passwordPosts()).isEqualTo(1);
        assertThat(String.join("\n", trace)).doesNotContain(FakeEakteServer.PASSWORD).doesNotContain("AQIC");
    }

    @Test
    void opensFoldersAndReadsDocuments() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            EakteEntry inbox = connection.list(connection.rootHref()).get(1);
            List<EakteEntry> files = connection.list(inbox.href());
            assertThat(files).extracting(EakteEntry::name)
                    .containsExactly("Antrag Frachtraum 7.txt", "Vermerk Bergung.txt");
            assertThat(new String(connection.read(files.get(0).href()), StandardCharsets.UTF_8))
                    .startsWith("Antrag auf Zuweisung");
        }
    }

    @Test
    void canStartWithWebDavItselfWithoutStartPage() throws Exception {
        try (EakteConnection connection = server.connect(profile(false), FakeEakteServer.PASSWORD.toCharArray())) {
            assertThat(connection.list(connection.rootHref())).hasSize(3);
        }
        assertThat(fake.passwordPosts()).isEqualTo(1);
    }

    @Test
    void completesASecondSamlRoundForWebDavWithoutSendingThePasswordAgain() throws Exception {
        fake.separateWebDavSession(true);
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            assertThat(connection.list(connection.rootHref())).hasSize(3);
        }
        assertThat(fake.passwordPosts()).isEqualTo(1);
        assertThat(trace).anyMatch(line -> line.contains("zur Anmeldung um"));
    }

    @Test
    void renewsAnExpiredEakteSessionThroughTheOpenAmSession() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            fake.expireEakteSessions();
            assertThat(connection.list(connection.rootHref())).hasSize(3);
        }
        assertThat(fake.passwordPosts()).isEqualTo(1);
    }

    @Test
    void neverSendsThePasswordAgainWhenOpenAmSessionIsGone() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            fake.expireAllSessions();
            assertThatThrownBy(() -> connection.list(connection.rootHref()))
                    .isInstanceOf(SessionExpiredException.class);
        }
        assertThat(fake.passwordPosts()).isEqualTo(1);
    }

    @Test
    void wrongPasswordIsRefusedAfterExactlyOneAttempt() {
        assertThatThrownBy(() -> server.connect(profile(true), "falsch".toCharArray()))
                .isInstanceOf(LoginRefusedException.class);
        assertThat(fake.passwordPosts()).isEqualTo(1);
    }

    @Test
    void answersOpenAmNameAndPasswordCallbacksOnce() throws Exception {
        fake.nameCallbacks(true);
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            assertThat(connection.list(connection.rootHref())).hasSize(3);
        }
        assertThat(fake.restPasswordAnswers()).isEqualTo(1);
        assertThat(fake.passwordPosts()).isZero();
    }

    @Test
    void wrongPasswordOverRestCallbacksIsRefused() {
        fake.nameCallbacks(true);
        assertThatThrownBy(() -> server.connect(profile(true), "falsch".toCharArray()))
                .isInstanceOf(LoginRefusedException.class);
        assertThat(fake.restPasswordAnswers()).isEqualTo(1);
    }

    @Test
    void neverSendsCredentialsToAHostThatIsNoLoginService() {
        EakteProfile untrusted = new EakteProfile(fake.webdav(), fake.start(), FakeEakteServer.USER,
                Set.of("anmeldung.example.org"));
        assertThatThrownBy(() -> server.connect(untrusted, FakeEakteServer.PASSWORD.toCharArray()))
                .hasMessageContaining("fremden Server");
        assertThat(fake.passwordPosts()).isZero();
        assertThat(fake.requests()).noneMatch(line -> line.startsWith("openam") || line.startsWith("provider"));
    }

    @Test
    void sendsAllCookiesInOneLine() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            connection.list(connection.rootHref());
        }
        // Der Nachbau lehnt wie die echte E-Akte mehrere Cookie-Zeilen ab; ohne Umweg über die Anmeldung beim
        // zweiten PROPFIND war die Zeile also eine einzige.
        assertThat(trace).noneMatch(line -> line.contains("PROPFIND") && line.contains("zur Anmeldung um"));
    }
}
