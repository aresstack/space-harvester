package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.DelegatedSourceAccess;
import com.aresstack.spaceharvester.application.access.DelegationUnavailableException;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.access.UserCredential;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Prüfung mit dem Token des Endnutzers gegen eine nachgebildete Confluence (#24, Teil C). */
@ExtendWith(OutputCaptureExtension.class)
class ConfluenceDelegatedAccessTest {

    private static final String TOKEN = "alice-pat-5c0e91";
    private static final UserCredential ALICE = new UserCredential("wiki", TOKEN);

    private HttpServer server;
    private ConfiguredSourceCatalog catalog;
    private DelegatedSourceAccess access;
    /** Antwort je Pfad mit Query: Status, Content-Type, Körper. */
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();

    private record Answer(int status, String contentType, String body, String location) {

        static Answer json(String body) {
            return new Answer(200, "application/json;charset=UTF-8", body, null);
        }

        static Answer status(int status) {
            return new Answer(status, "application/json", "{}", null);
        }
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestURI().getRawPath().replaceFirst("^/confluence", "")
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            requested.add(key);
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            Answer answer = answers.getOrDefault(key, Answer.status(404));
            if (answer.location() != null) {
                exchange.getResponseHeaders().add("Location", answer.location());
            }
            exchange.getResponseHeaders().add("Content-Type", answer.contentType());
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/confluence");
        catalog = ConfiguredSourceCatalog.of(List.of(new SourceSettingsConfig("wiki", "confluence", null, base,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null, "harvest-pat-should-not-be-sent"), Map.of("allowInsecureHttp", "true"))),
                "space-harvester/test");
        access = ConfluenceDelegatedAccess.factory(catalog).forSource("wiki").orElseThrow();
    }

    @AfterEach
    void stop() {
        catalog.close();
        server.stop(0);
    }

    @Test
    void identifiesTheUserBehindTheTokenWithTheTokenOnly() {
        answers.put("/rest/api/user/current",
                Answer.json("{\"type\":\"known\",\"username\":\"alice\",\"userKey\":\"8a7f0001\"}"));

        assertThat(access.identify(ALICE)).hasValue("8a7f0001");
        assertThat(authorizations).containsExactly("Bearer " + TOKEN);
    }

    @Test
    void rejectedTokensAreNotUsers() {
        answers.put("/rest/api/user/current", Answer.status(401));
        assertThat(access.identify(ALICE)).isEmpty();

        answers.put("/rest/api/user/current", new Answer(302, "text/html", "", "/login.action"));
        assertThat(access.identify(ALICE)).isEmpty();

        answers.put("/rest/api/user/current", Answer.json("{\"type\":\"anonymous\"}"));
        assertThat(access.identify(ALICE)).isEmpty();
    }

    @Test
    void unclearUserAnswersAreUnavailable() {
        answers.put("/rest/api/user/current", Answer.status(500));
        assertThatThrownBy(() -> access.identify(ALICE)).isInstanceOf(DelegationUnavailableException.class);

        answers.put("/rest/api/user/current", new Answer(200, "text/html", "<html>Login</html>", null));
        assertThatThrownBy(() -> access.identify(ALICE)).isInstanceOf(DelegationUnavailableException.class);
    }

    @Test
    void pageIsVisibleOnlyWithItsIdInTheExpectedSpace() {
        answers.put("/rest/api/content/100?expand=space",
                Answer.json("{\"id\":\"100\",\"type\":\"page\",\"space\":{\"key\":\"DEV\"}}"));

        assertThat(access.canSee(ALICE, ResourceScope.page("wiki", "DEV", "100"))).isEqualTo(AccessDecision.ALLOW);
        assertThat(access.canSee(ALICE, ResourceScope.page("wiki", null, "100"))).isEqualTo(AccessDecision.ALLOW);
        // In einen anderen Space verschoben: nicht als Seite des erwarteten Space freigeben.
        assertThat(access.canSee(ALICE, ResourceScope.page("wiki", "HR", "100"))).isEqualTo(AccessDecision.DENY);
    }

    @Test
    void restrictedMissingOrRedirectedPagesAreHidden() {
        assertThat(access.canSee(ALICE, ResourceScope.page("wiki", "DEV", "101"))).isEqualTo(AccessDecision.DENY);
        answers.put("/rest/api/content/102?expand=space", Answer.status(403));
        assertThat(access.canSee(ALICE, ResourceScope.page("wiki", "DEV", "102"))).isEqualTo(AccessDecision.DENY);
        answers.put("/rest/api/content/103?expand=space", new Answer(302, "text/html", "", "/login.action"));
        assertThat(access.canSee(ALICE, ResourceScope.page("wiki", "DEV", "103"))).isEqualTo(AccessDecision.DENY);
    }

    @Test
    void unclearPageAnswersFailClosed() {
        answers.put("/rest/api/content/100?expand=space", Answer.status(503));
        assertThat(access.canSee(ALICE, ResourceScope.page("wiki", "DEV", "100")))
                .isEqualTo(AccessDecision.UNDETERMINED);

        answers.put("/rest/api/content/100?expand=space", new Answer(200, "text/html", "<html/>", null));
        assertThat(access.canSee(ALICE, ResourceScope.page("wiki", "DEV", "100")))
                .isEqualTo(AccessDecision.UNDETERMINED);
    }

    @Test
    void spaceIsVisibleOnlyWithItsKey() {
        answers.put("/rest/api/space/DEV", Answer.json("{\"key\":\"DEV\",\"name\":\"Entwicklung\"}"));
        answers.put("/rest/api/space/~alice", Answer.json("{\"key\":\"~alice\"}"));

        assertThat(access.canSee(ALICE, ResourceScope.space("wiki", "DEV"))).isEqualTo(AccessDecision.ALLOW);
        assertThat(access.canSee(ALICE, ResourceScope.space("wiki", "~alice"))).isEqualTo(AccessDecision.ALLOW);
        assertThat(access.canSee(ALICE, ResourceScope.space("wiki", "HR"))).isEqualTo(AccessDecision.DENY);
    }

    @Test
    void unusualIdsAreNeverSentToConfluence() {
        assertThat(access.canSee(ALICE, ResourceScope.page("wiki", "DEV", "../space/HR")))
                .isEqualTo(AccessDecision.DENY);
        assertThat(access.canSee(ALICE, ResourceScope.space("wiki", "DEV?x=1"))).isEqualTo(AccessDecision.DENY);
        assertThat(requested).isEmpty();
    }

    @Test
    void unreachableConfluenceFailsClosedWithoutLeakingTheToken(CapturedOutput output) {
        server.stop(0);

        assertThat(access.canSee(ALICE, ResourceScope.space("wiki", "DEV"))).isEqualTo(AccessDecision.UNDETERMINED);
        assertThatThrownBy(() -> access.identify(ALICE)).isInstanceOf(DelegationUnavailableException.class)
                .message().doesNotContain(TOKEN);
        assertThat(output).doesNotContain(TOKEN);
    }

    @Test
    void tokenOfAnotherSourceIsRefused() {
        assertThatThrownBy(() -> access.identify(new UserCredential("other", TOKEN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requested).isEmpty();
    }
}
