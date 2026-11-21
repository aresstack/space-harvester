package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageAddresses;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceField;
import com.aresstack.spaceharvester.application.sources.SourceType;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.source.EacSourceAdapters;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Jira Data Center als Quelle im Space Harvester: der Typ {@code jira} aus der Kopie des Enterprise AI Client
 * ({@code JiraSourceProvider}, enterprise-ai-client#228) über {@link EacSourceAdapters#jira()}, gegen einen kleinen
 * HTTP-Server mit den Antworten der REST-API 2. Projekte werden Partitionen, Vorgänge Seiten mit Beschreibung,
 * Metadaten und Kommentaren.
 */
class HarvesterJiraSourceTest {

    @TempDir
    Path data;

    private HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> searches = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/jira/", this::answer);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theTypeDescribesItsFieldsWithoutItsOwnCertificateField() {
        SourceType type = type(EacSourceAdapters.jira());

        assertThat(type.displayName()).isEqualTo("Jira");
        assertThat(type.addressOption()).isEqualTo("baseUrl");
        assertThat(type.tls()).isTrue();
        assertThat(type.authModes()).containsExactly(SourceType.AuthMode.NONE, SourceType.AuthMode.BASIC,
                SourceType.AuthMode.PAT);
        assertThat(type.fields()).extracting(SourceField::key).contains("baseUrl", "credentialRef", "jql")
                .doesNotContain("title", "clientCertificate.alias");
    }

    @Test
    void anOwnCertificateOptionIsRejectedBecauseClientCertificateHoldsIt() {
        SourceSettingsConfig config = new SourceSettingsConfig("jira", "jira", "Jira", null, null,
                Map.of("baseUrl", "https://jira.example.org/jira", "clientCertificate.alias", "ich"));

        assertThat(EacSourceAdapters.jira().check(config))
                .contains("options.clientCertificate.alias ist nicht erlaubt; dafür gibt es client-certificate");
    }

    @Test
    void projectsArePartitionsAndIssuesArePagesWithDescriptionAndComments() throws Exception {
        SourceSettingsConfig.Auth auth = new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "anna",
                "nur-fuer-den-test", null);
        try (Harvester harvester = harvester(auth)) {
            assertThat(harvester.sources().partitions("jira")).extracting(Partition::key).containsExactly("ABC");

            JobStatus sync = harvester.await(harvester.sync("jira", "ABC").id(), Duration.ofSeconds(60));
            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
            // Ohne eigene Startpunkte nur die Liste des Projekts, keine Suche über die ganze Jira davor.
            assertThat(searches).containsExactly("project = \"ABC\" ORDER BY key ASC");
            List<ListedPage> pages = harvester.pages().pages(harvester.caller(), "jira", "ABC", null, 100).pages();
            assertThat(pages).singleElement().satisfies(page ->
                    assertThat(page.header().metadata().title()).contains("ABC-1", "Drucker streikt"));
            PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "jira",
                    pages.get(0).header().key().pageId(), false).snapshot();
            // Abgelegt ist der Lesetext mit allen Kommentaren, auch denen hinter der ersten Seite.
            assertThat(snapshot.payload().nativeFormat()).isEqualTo(KnowledgeDocumentTextProjector.NATIVE_FORMAT);
            String text = new String(snapshot.payload().bytes(), StandardCharsets.UTF_8);
            assertThat(text).contains("Drucker streikt", "Der Drucker im zweiten Stock druckt nicht.",
                    "Toner ist leer.", "Neuer Toner ist bestellt.");
            assertThat(harvester.pageText(snapshot)).map(PageText::text)
                    .hasValueSatisfying(indexed -> assertThat(indexed).contains("zweiten Stock", "bestellt"));
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline) && found(harvester, "Stock").isEmpty()) {
                Thread.sleep(50);
            }
            assertThat(found(harvester, "Stock")).extracting(KeywordSearch.Hit::sourceId).containsExactly("jira");
        }
        assertThat(authorizations).isNotEmpty().allSatisfy(header -> assertThat(header).startsWith("Basic "));
        assertThat(paths).noneMatch(path -> path.contains("nur-fuer-den-test"));
    }

    @Test
    void aPersonalAccessTokenGoesAsBearer() throws Exception {
        SourceSettingsConfig.Auth auth = new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null,
                "token-fuer-den-test");
        try (Harvester harvester = harvester(auth)) {
            assertThat(harvester.sources().partitions("jira")).extracting(Partition::key).containsExactly("ABC");
        }
        assertThat(authorizations).isNotEmpty().allSatisfy(header ->
                assertThat(header).isEqualTo("Bearer token-fuer-den-test"));
    }

    @Test
    void aJiraLinkFromAnotherSourceResolvesToTheIssueInTheJiraSource() throws Exception {
        try (Harvester harvester = harvester(SourceSettingsConfig.Auth.anonymous())) {
            // So kommt ein Jira-Makro oder -Link aus einer Confluence-Seite an: die Adresse des Vorgangs im Browser.
            PageAddresses.Resolution issue = harvester.addresses().resolve(harvester.caller(),
                    baseUrl() + "/browse/ABC-1");
            assertThat(issue.matches()).singleElement().satisfies(match -> {
                assertThat(match.sourceId()).isEqualTo("jira");
                assertThat(match.title()).contains("Drucker streikt");
            });

            PageAddresses.Resolution foreign = harvester.addresses().resolve(harvester.caller(),
                    "https://www.example.org/browse/ABC-1");
            assertThat(foreign.matches()).isEmpty();
        }
    }

    private static List<KeywordSearch.Hit> found(Harvester harvester, String word) {
        return harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of(word), null, 10).hits();
    }

    private Harvester harvester(SourceSettingsConfig.Auth auth) {
        Map<String, String> options = Map.of("baseUrl", baseUrl(), "allowInsecureHttp", "true");
        return Harvester.builder()
                .source(new SourceSettingsConfig("jira", "jira", "Jira", null, auth, options))
                .dataDirectory(data).build();
    }

    private SourceType type(com.aresstack.spaceharvester.source.SourceAdapter adapter) {
        try (Harvester harvester = Harvester.builder().dataDirectory(data).build()) {
            return harvester.sourceTypes().stream().filter(type -> type.id().equals(adapter.type())).findFirst()
                    .orElseThrow();
        }
    }

    private String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/jira";
    }

    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath().substring("/jira".length());
        paths.add(exchange.getRequestURI().toString());
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null) {
            authorizations.add(authorization);
        }
        String body;
        if (path.equals("/rest/api/2/project")) {
            body = "[" + project() + "]";
        } else if (path.equals("/rest/api/2/project/ABC")) {
            body = project();
        } else if (path.equals("/rest/api/2/search")) {
            searches.add(query(exchange, "jql"));
            body = "{\"startAt\":0,\"maxResults\":50,\"total\":1,\"issues\":[" + issue(false) + "]}";
        } else if (path.equals("/rest/api/2/issue/ABC-1/comment")) {
            body = "{\"startAt\":0,\"maxResults\":50,\"total\":2,\"comments\":[" + comment() + ","
                    + secondComment() + "]}";
        } else if (path.equals("/rest/api/2/issue/ABC-1")) {
            body = issue(true);
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String project() {
        return "{\"id\":\"10001\",\"key\":\"ABC\",\"name\":\"Projekt ABC\",\"self\":\"" + baseUrl()
                + "/rest/api/2/project/ABC\"}";
    }

    private static String query(HttpExchange exchange, String name) {
        for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
            if (pair.startsWith(name + "=")) {
                return java.net.URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String secondComment() {
        return "{\"id\":\"2\",\"author\":{\"displayName\":\"Alice\",\"name\":\"alice\"},"
                + "\"body\":\"Neuer Toner ist bestellt.\",\"renderedBody\":\"<p>Neuer Toner ist bestellt.</p>\","
                + "\"created\":\"2026-01-11T12:00:00.000+0100\",\"updated\":\"2026-01-11T12:00:00.000+0100\"}";
    }

    private String comment() {
        return "{\"id\":\"1\",\"author\":{\"displayName\":\"Bob\",\"name\":\"bob\"},\"body\":\"Toner ist leer.\","
                + "\"renderedBody\":\"<p>Toner ist leer.</p>\",\"created\":\"2026-01-10T12:00:00.000+0100\","
                + "\"updated\":\"2026-01-10T12:00:00.000+0100\"}";
    }

    private String issue(boolean full) {
        String user = "{\"displayName\":\"Alice\",\"name\":\"alice\"}";
        String fields = "\"summary\":\"Drucker streikt\",\"issuetype\":{\"name\":\"Bug\"},\"status\":{\"name\":\"Open\"},"
                + "\"priority\":{\"name\":\"Medium\"},\"resolution\":null,\"assignee\":" + user + ",\"reporter\":"
                + user + ",\"created\":\"2026-01-05T10:15:30.000+0100\",\"updated\":\"2026-02-01T08:00:00.000+0100\","
                + "\"project\":{\"key\":\"ABC\",\"name\":\"Projekt ABC\"},\"labels\":[],\"components\":[],"
                + "\"fixVersions\":[],\"description\":\"Der Drucker im zweiten Stock druckt nicht.\"";
        if (full) {
            fields += ",\"subtasks\":[],\"issuelinks\":[],\"attachment\":[],\"comment\":{\"startAt\":0,"
                    + "\"maxResults\":1,\"total\":2,\"comments\":[" + comment() + "]}";
        }
        String rendered = full ? ",\"renderedFields\":{\"description\":\"<p>Der Drucker im zweiten Stock druckt "
                + "nicht.</p>\"}" : "";
        return "{\"id\":\"20001\",\"key\":\"ABC-1\",\"self\":\"" + baseUrl() + "/rest/api/2/issue/ABC-1\",\"fields\":{"
                + fields + "}" + rendered + "}";
    }
}
