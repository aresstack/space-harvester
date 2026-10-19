package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.TestClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stabile Passage-IDs über echtes HTTP (EAC-Lücke 5, ADR 0020 Punkt 10): Ein Schlagworttreffer nennt die ID des
 * Absatzes, aus dem sein Auszug stammt, {@code GET /api/v1/passages?id=} findet genau diesen Absatz, und eine nicht
 * sichtbare Seite antwortet wie eine unbekannte mit 404 {@code cache-miss}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PassageIdApiTest {

    private static final String READER_TOKEN = "passage-reader-token-3b8f";
    private static final String METADATA_TOKEN = "passage-metadata-token-9e21";
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final HttpServer SERVER;
    private static final Path STORE;
    private static final Path CACHE;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.space("SEC", "Sicherheit");
        CONFLUENCE.page("100", "Start", "DEV", "<p>S</p>");
        CONFLUENCE.homepage("DEV", "100");
        String operations = "<h1>Betrieb</h1><p>Das Wartungsfenster liegt jeden Samstag.</p>"
                + "<h1>Urlaub</h1><p>Urlaub beantragt man im Portal.</p>";
        CONFLUENCE.page("101", "Betrieb", "DEV", operations).storage(operations);
        CONFLUENCE.child("100", "101");
        String vault = "<p>Die Kombination des Panzerschranks steht im Wartungsbuch.</p>";
        CONFLUENCE.page("103", "Tresorraum", "SEC", vault).storage(vault);
        CONFLUENCE.homepage("SEC", "103");
        // Die Suche der Quelle findet beide; welche Seite ein Treffer wird, entscheidet der Volltextindex des Caches.
        CONFLUENCE.searchResults("101", "103");
        try {
            SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            SERVER.createContext("/", PassageIdApiTest::serve);
            SERVER.start();
            STORE = Files.createTempDirectory("passage-ids");
            // Unter build/, nicht gelöscht: der Spring-Kontext schließt seine Indizes erst am Ende der Testläufe.
            CACHE = Path.of("build", "passage-id-api-test", java.util.UUID.randomUUID().toString());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void serve(HttpExchange exchange) throws IOException {
        URI incoming = exchange.getRequestURI();
        String query = incoming.getRawQuery();
        URI mapped = URI.create(FakeConfluence.BASE.getScheme() + "://" + FakeConfluence.BASE.getHost()
                + incoming.getRawPath() + (query == null ? "" : "?" + query));
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            if (!header.getValue().isEmpty()) {
                headers.put(header.getKey(), header.getValue().getFirst());
            }
        }
        ConfluenceHttpResponse response;
        synchronized (CONFLUENCE) {
            response = CONFLUENCE.get(mapped, headers, Integer.MAX_VALUE);
        }
        byte[] body = response.body();
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        String client = "space-harvester.security.clients[0].";
        registry.add(client + "id", () -> TestClient.ID);
        registry.add(client + "token-sha256", () -> TestClient.TOKEN_SHA256);
        registry.add(client + "grants[0].source", () -> "wiki");
        registry.add(client + "grants[0].spaces[0]", () -> "*");
        registry.add(client + "grants[0].access", () -> "metadata,content,sync");
        String reader = "space-harvester.security.clients[1].";
        registry.add(reader + "id", () -> "nur-lesen-dev");
        registry.add(reader + "token-sha256", () -> "sha256:" + sha256(READER_TOKEN));
        registry.add(reader + "grants[0].source", () -> "wiki");
        registry.add(reader + "grants[0].spaces[0]", () -> "DEV");
        registry.add(reader + "grants[0].access", () -> "metadata,content");
        String metadata = "space-harvester.security.clients[2].";
        registry.add(metadata + "id", () -> "nur-metadaten");
        registry.add(metadata + "token-sha256", () -> "sha256:" + sha256(METADATA_TOKEN));
        registry.add(metadata + "grants[0].source", () -> "wiki");
        registry.add(metadata + "grants[0].spaces[0]", () -> "*");
        registry.add(metadata + "grants[0].access", () -> "metadata");
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + FakeConfluence.BASE.getPath());
        registry.add("space-harvester.sources[0].options.retryBackoffMillis", () -> "1");
        registry.add("space-harvester.assets.directory", STORE::toString);
        registry.add("space-harvester.cache.directory", () -> CACHE.toAbsolutePath().toString());
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.stop(0);
        try (Stream<Path> files = Files.walk(STORE)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void aKeywordHitNamesThePassageThatTheLookupFindsAndHiddenPagesStayHidden() throws Exception {
        String id = passageOfHit("Wartungsfenster", "101");
        assertThat(id).startsWith("wiki:101@");

        HttpResponse<String> lookup = send("/api/v1/passages?id=" + encode(id), READER_TOKEN);

        assertThat(lookup.statusCode()).isEqualTo(200);
        JsonNode found = json.readTree(lookup.body());
        assertThat(found.get("match").asString()).isEqualTo("found");
        assertThat(found.get("passages").findValuesAsString("id")).contains(id);
        String wording = null;
        for (JsonNode entry : found.get("passages")) {
            if (id.equals(entry.get("id").asString())) {
                wording = entry.get("text").asString();
            }
        }
        assertThat(wording).contains("Wartungsfenster");

        // Unsichtbar heißt unbekannt: dieselbe Antwort wie für eine Seite, die es nicht gibt, nie 403.
        String hidden = passageOfHit("Panzerschranks", "103");
        HttpResponse<String> forbidden = send("/api/v1/passages?id=" + encode(hidden), READER_TOKEN);
        HttpResponse<String> unknown = send("/api/v1/passages?id="
                + encode(hidden.replace("wiki:103@", "wiki:999@")), READER_TOKEN);
        HttpResponse<String> metadataOnly = send("/api/v1/passages?id=" + encode(id), METADATA_TOKEN);

        assertProblem(forbidden, 404, "cache-miss");
        assertProblem(unknown, 404, "cache-miss");
        assertProblem(metadataOnly, 404, "cache-miss");
        assertThat(forbidden.body()).doesNotContain("Panzerschrank").doesNotContain("Tresorraum");
        JsonNode reader = json.readTree(send("/api/v1/search?text=Panzerschranks", READER_TOKEN).body());
        assertThat(reader.findValuesAsString("pageId")).doesNotContain("103");
        assertThat(reader.findValues("passageId")).allSatisfy(value -> assertThat(value.isNull()).isTrue());
    }

    /**
     * Sucht {@code text}, bis der Treffer auf {@code pageId} seine Passage-ID trägt: Laden und Absätze der Seite laufen
     * nach der ersten Suche weiter; gewartet wird auf den Zustand, 120 s sind nur die Grenze für einen Hänger.
     */
    private String passageOfHit(String text, String pageId) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        boolean computed = false;
        while (true) {
            JsonNode hits = json.readTree(send("/api/v1/search?sourceId=wiki&text=" + text, TestClient.TOKEN).body())
                    .get("hits");
            for (JsonNode hit : hits) {
                if (pageId.equals(hit.get("pageId").asString()) && hit.hasNonNull("passageId")) {
                    return hit.get("passageId").asString();
                }
                if (pageId.equals(hit.get("pageId").asString()) && !computed) {
                    // Die Absätze nicht dem Hintergrund überlassen: einmal ausdrücklich berechnen.
                    assertThat(send("/api/v1/sources/wiki/pages/" + pageId + "/enrichments?processor=passages",
                            TestClient.TOKEN, true).statusCode()).isEqualTo(200);
                    computed = true;
                }
            }
            assertThat(System.nanoTime()).as("Treffer mit Passage-ID für " + pageId).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private void assertProblem(HttpResponse<String> response, int status, String type) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("type").asString())
                .isEqualTo("https://github.com/aresstack/space-harvester/problems/" + type);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(String path, String token) throws Exception {
        return send(path, token, false);
    }

    private HttpResponse<String> send(String path, String token, boolean post) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);
        return http.send((post ? request.POST(HttpRequest.BodyPublishers.noBody()) : request.GET()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
