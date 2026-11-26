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
        return new EakteProfile(fake.webdav(), withStart ? fake.start() : null, FakeEakteServer.USER);
    }

    @Test
    void logsInLikeTheTestProgramAndListsTheRootWithOnePasswordPost() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            List<EakteEntry> root = connection.list(connection.rootHref());
            assertThat(root).extracting(EakteEntry::name)
                    .containsExactly("Ablage", "Arbeitsvorrat", "Gesperrt", "Pinnwand", "Übersicht Akten.txt");
            assertThat(root.get(0).folder()).isTrue();
            assertThat(root.get(4).size()).isPositive();
            assertThat(root.get(4).modified()).isNotNull();
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
                    .containsExactly("Vorgang 4711", "Antrag Frachtraum 7.txt", "Vermerk Bergung.txt");
            assertThat(files.get(0).folder()).as("nur DAV:iscollection").isTrue();
            assertThat(new String(connection.read(files.get(1).href()), StandardCharsets.UTF_8))
                    .startsWith("Antrag auf Zuweisung");
        }
    }

    @Test
    void canStartWithWebDavItselfWithoutStartPage() throws Exception {
        try (EakteConnection connection = server.connect(profile(false), FakeEakteServer.PASSWORD.toCharArray())) {
            assertThat(connection.list(connection.rootHref())).hasSize(5);
        }
        assertThat(fake.passwordPosts()).isEqualTo(1);
    }

    @Test
    void completesASecondSamlRoundForWebDavWithoutSendingThePasswordAgain() throws Exception {
        fake.separateWebDavSession(true);
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            assertThat(connection.list(connection.rootHref())).hasSize(5);
        }
        assertThat(fake.passwordPosts()).isEqualTo(1);
        assertThat(trace).anyMatch(line -> line.contains("zur Anmeldung um"));
        // v2.0 (completeWebDavSamlChallenge): nach der SAML-Antwort kein GET auf den WebDAV-Endpunkt, sondern PROPFIND
        assertThat(fake.requests()).noneMatch(line -> line.startsWith("eakte GET /fsc/webdav"));
        assertThat(trace).anyMatch(line -> line.contains("wiederhole die Anfrage statt der Weiterleitung"));
    }

    @Test
    void renewsAnExpiredEakteSessionThroughTheOpenAmSession() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            fake.expireEakteSessions();
            assertThat(connection.list(connection.rootHref())).hasSize(5);
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
            assertThat(connection.list(connection.rootHref())).hasSize(5);
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

    /** Wie v2.0: Zugangsdaten nur an ein Formularziel auf demselben Server wie die Formularseite. */
    @Test
    void neverSendsCredentialsToAFormTargetOnAnotherServer() {
        fake.foreignFormAction(true);
        assertThatThrownBy(() -> server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray()))
                .hasMessageContaining("Zugangsdaten nicht gesendet");
        assertThat(fake.passwordPosts()).isZero();
    }

    /** Anmeldedienste stehen nirgends fest: erlaubt wird nur, wohin die Weiterleitungen ab der E-Akte führten. */
    @Test
    void learnsTheLoginServicesFromTheRedirects() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            assertThat(connection.list(connection.rootHref())).isNotEmpty();
        }
        assertThat(trace).anyMatch(line -> line.startsWith("Weiterleitung zu ") && line.contains("(von "));
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

    /** Abnahmekriterium 3 von GPT, hier nur gegen den Nachbau: GET der .msg, byte-identisch, nichts im Protokoll. */
    @Test
    void downloadsTheOutlookMessageByteIdenticalWithTheSameSession() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            EakteEntry inbox = connection.list(connection.rootHref()).get(1);
            EakteEntry vorgang = connection.list(inbox.href()).get(0);
            EakteEntry message = connection.list(vorgang.href()).get(0);
            assertThat(message.name()).isEqualTo("Anfrage Frachtraum #7 (Kopie).msg");
            assertThat(message.contentType()).isEqualTo(FakeEakteServer.MESSAGE_TYPE);
            assertThat(message.size()).isEqualTo(FakeEakteServer.MESSAGE.length);
            assertThat(message.objectAddress()).isEqualTo(FakeEakteServer.MESSAGE_ADDRESS);
            assertThat(message.etag()).isEqualTo("\"4711-1\"");
            assertThat(message.created()).isNotNull();

            java.io.ByteArrayOutputStream target = new java.io.ByteArrayOutputStream();
            long copied = connection.download(message.href(), target);
            assertThat(copied).isEqualTo(FakeEakteServer.MESSAGE.length);
            assertThat(target.toByteArray()).isEqualTo(FakeEakteServer.MESSAGE);
        }
        assertThat(fake.passwordPosts()).isEqualTo(1);
        assertThat(trace).anyMatch(line -> line.startsWith("GET ") && line.contains("HTTP 200")
                && line.contains(FakeEakteServer.MESSAGE_TYPE));
    }

    /** Angelo 26.11.2026 (WLAN): Ein abgerissenes Dokument geht ab dem letzten Byte weiter, mit derselben Sitzung. */
    @Test
    void aBrokenDocumentContinuesFromTheLastByte() throws Exception {
        HttpEakteServer resuming = new HttpEakteServer(HttpClientFactory.direct(), Duration.ofSeconds(10), trace::add,
                () -> 2);
        try (EakteConnection connection = resuming.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            EakteEntry inbox = connection.list(connection.rootHref()).get(1);
            EakteEntry vorgang = connection.list(inbox.href()).get(0);
            EakteEntry message = connection.list(vorgang.href()).get(0);
            fake.breakMessageOnce();

            java.io.ByteArrayOutputStream target = new java.io.ByteArrayOutputStream();
            long copied = connection.download(message.href(), target);

            assertThat(copied).isEqualTo(FakeEakteServer.MESSAGE.length);
            assertThat(target.toByteArray()).isEqualTo(FakeEakteServer.MESSAGE);
            assertThat(fake.messageRanges()).containsExactly("-",
                    "bytes=" + (FakeEakteServer.MESSAGE.length / 4) + "-");
        }
        assertThat(fake.passwordPosts()).isEqualTo(1);
    }

    @Test
    void withoutResumesABrokenDocumentFails() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            EakteEntry inbox = connection.list(connection.rootHref()).get(1);
            EakteEntry vorgang = connection.list(inbox.href()).get(0);
            EakteEntry message = connection.list(vorgang.href()).get(0);
            fake.breakMessageOnce();
            assertThatThrownBy(() -> connection.download(message.href(), java.io.OutputStream.nullOutputStream()))
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    void separatesNoPermissionServerErrorGoneAndEmptyFolder() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            List<EakteEntry> root = connection.list(connection.rootHref());
            assertThat(connection.list(root.get(3).href())).as("Pinnwand ist leer, kein Fehler").isEmpty();
            assertThatThrownBy(() -> connection.list(root.get(2).href()))
                    .hasMessageContaining("keine Berechtigung").hasMessageContaining("403");
            String broken = connection.list(root.get(0).href()).stream()
                    .filter(entry -> entry.name().equals("Defekt.txt")).findFirst().orElseThrow().href();
            assertThatThrownBy(() -> connection.download(broken, java.io.OutputStream.nullOutputStream()))
                    .hasMessageContaining("Serverfehler");
            assertThatThrownBy(() -> connection.read(connection.rootHref() + "Weg.txt"))
                    .hasMessageContaining("gibt es nicht mehr");
        }
    }

    @Test
    void expiredSessionOnDownloadAsksForLoginInsteadOfSendingThePassword() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            EakteEntry file = connection.list(connection.rootHref()).get(4);
            fake.expireAllSessions();
            assertThatThrownBy(() -> connection.download(file.href(), java.io.OutputStream.nullOutputStream()))
                    .isInstanceOf(SessionExpiredException.class);
        }
        assertThat(fake.passwordPosts()).isEqualTo(1);
    }

    /** Wie im Protokoll von v2.0: /openam/ (Startseite mit Skript) → UI/Login → XUI/, dann REST-Fortsetzung. */
    @Test
    void followsTheOpenAmLandingPageLikeTheTestProgram() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            assertThat(connection.list(connection.rootHref())).hasSize(5);
        }
        List<String> openam = fake.requests().stream().filter(line -> line.startsWith("openam ")).toList();
        int landing = openam.indexOf("openam GET /openam/");
        assertThat(landing).isNotNegative();
        assertThat(openam.subList(landing, landing + 4)).containsExactly("openam GET /openam/",
                "openam GET /openam/UI/Login", "openam GET /openam/XUI/", "openam POST /openam/json/authenticate");
        assertThat(trace).anyMatch(line -> line.contains("JavaScript-Weiterleitung erkannt"));
        assertThat(String.join("\n", trace)).doesNotContain("code=");
    }

    /** Wie v2.0: auf der Zwischenseite erst loginUrl (→ XUI/), dann REST; das Formular mit Referer der Seite. */
    @Test
    void visitsLoginUrlBeforeRestAndPostsTheFormWithReferer() throws Exception {
        try (EakteConnection connection = server.connect(profile(true), FakeEakteServer.PASSWORD.toCharArray())) {
            connection.list(connection.rootHref());
        }
        List<String> openam = fake.requests().stream().filter(line -> line.startsWith("openam ")).toList();
        int rest = openam.indexOf("openam POST /openam/json/authenticate");
        assertThat(openam.subList(rest - 2, rest + 1)).containsExactly("openam GET /openam/UI/Login",
                "openam GET /openam/XUI/", "openam POST /openam/json/authenticate");
        assertThat(fake.loginReferers()).hasSize(1);
        assertThat(fake.loginReferers().get(0)).contains("/eai/mapz/login");
    }

    /** Live mit v2.0 belegt: Einstieg = WebDAV-Adresse; die Anmeldung läuft über den WebDAV-Endpunkt selbst. */
    @Test
    void entryAndWebDavAreTheSameAddressLikeTheLiveTest() throws Exception {
        EakteProfile same = EakteProfile.fromAddress(fake.webdav().resolve("/").toString(), "", FakeEakteServer.USER);
        assertThat(same.start()).isEqualTo(same.webdav());
        try (EakteConnection connection = server.connect(same, FakeEakteServer.PASSWORD.toCharArray())) {
            assertThat(connection.list(connection.rootHref())).hasSize(5);
        }
        assertThat(fake.passwordPosts()).isEqualTo(1);
        assertThat(fake.requests()).contains("openam GET /openam/", "openam GET /openam/XUI/");
    }
}
