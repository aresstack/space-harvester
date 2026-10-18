package com.aresstack.spaceharvester.api.confluence;

import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import org.springframework.context.annotation.Primary;
import com.aresstack.spaceharvester.EmptyConfluence;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Vertragstest der Confluence-Fassade (#31) gegen die OpenAPI-Referenz von Confluence Data Center. Die Referenz liegt
 * als Auszug je Version unter {@code src/test/resources/confluence-contract/<version>/}; welche gilt, steht in
 * {@code version.txt}. Der Test hängt nicht von der Atlassian-Seite ab; ob der Auszug noch stimmt, prüft der Workflow
 * {@code confluence-contract.yml}.
 *
 * <p>Geprüft wird: Jeder dokumentierte Parameter ist hier eingeordnet (unterstützt, abgelehnt, ignoriert) und verhält
 * sich so; Antworten, Listenhüllen und Fehlerkörper passen zu den dokumentierten Schemas; Pagination folgt
 * {@code start}, {@code limit} und den {@code _links}. Was abweicht, steht in {@link #KNOWN_DEVIATIONS} und in
 * {@code docs/CONFLUENCE_COMPAT.md}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ConfluenceCompatContractTest {

    private static final String HARVEST_FORMAT = "confluence-rest-content+json;expand=ancestors,body.storage,history,"
            + "history.lastUpdated,metadata.labels,space,version";
    private static final String COMPAT = "/api/compat/confluence/wiki";
    private static final String LIST = "/rest/api/content";
    private static final String PAGE = "/rest/api/content/{id}";
    private static final String CHILDREN = "/rest/api/content/{id}/child/{type}";
    private static final int PAGES = 7;

    /**
     * Abweichungen von der Referenz, Schemapfad zu Begründung. Atlassian weist selbst darauf hin, dass die Referenz
     * nicht in allem den echten Antworten entspricht; den Ausschlag gibt der Live-Vergleich.
     */
    private static final Map<String, String> KNOWN_DEVIATIONS = Map.of(
            "ValidationResult.errors", "Die Referenz nennt ein Objekt, Confluence DC liefert eine (leere) Liste");

    /** Erwartetes Verhalten je dokumentiertem Query-Parameter; ein neuer Parameter der Referenz lässt den Test scheitern. */
    private static final Map<String, Map<String, List<Sample>>> PARAMETERS = Map.of(
            LIST, Map.ofEntries(
                    Map.entry("spaceKey", List.of(Sample.ok("OPS"))),
                    Map.entry("type", List.of(Sample.ok("page"), Sample.rejected("blogpost"))),
                    Map.entry("title", List.of(Sample.ok("Seite 2"))),
                    Map.entry("status", List.of(Sample.ok("current"), Sample.rejected("draft"))),
                    Map.entry("expand", List.of(Sample.ok("body.storage,version"), Sample.rejected("body.view"))),
                    Map.entry("start", List.of(Sample.ok("2"), Sample.rejected("-1"))),
                    Map.entry("limit", List.of(Sample.ok("3"), Sample.rejected("-1"))),
                    Map.entry("ids", List.of(Sample.rejected("500"))),
                    Map.entry("postingDay", List.of(Sample.rejected("2026-10-01"))),
                    Map.entry("xoauth_requestor_id", List.of(Sample.ignored("someone")))),
            PAGE, Map.of(
                    "expand", List.of(Sample.ok("body.storage,version,space"), Sample.rejected("body.view")),
                    "status", List.of(Sample.ok("current"), Sample.rejected("historical")),
                    "version", List.of(Sample.rejected("2")),
                    "xoauth_requestor_id", List.of(Sample.ignored("someone"))),
            CHILDREN, Map.of(
                    "expand", List.of(Sample.ok("body.storage"), Sample.rejected("body.view")),
                    "start", List.of(Sample.ok("1"), Sample.rejected("-1")),
                    "limit", List.of(Sample.ok("2"), Sample.rejected("-1")),
                    "parentVersion", List.of(Sample.ok("0"), Sample.rejected("3"))));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", EmptyConfluence::baseUrl);
        TestClient.register(registry, "wiki");
    }

    @TestConfiguration
    static class Cache {

        @Bean
        @Primary
        PageFreshness cacheOnlyFreshness(SourceQueries queries) {
            return EmptyConfluence.cacheOnly(queries);
        }


        @Bean
        InMemoryPageCache pageCache() {
            return new InMemoryPageCache();
        }

        @Bean
        InMemorySearchIndex searchIndex() {
            return new InMemorySearchIndex();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERSION = resource("confluence-contract/version.txt").trim();
    private static final JsonNode REFERENCE = JSON.readTree(resource("confluence-contract/" + VERSION
            + "/openapi-extract.json"));

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Autowired
    private InMemoryPageCache cache;

    @Autowired
    private InMemorySearchIndex index;

    @BeforeEach
    void fillCache() {
        cache.clear();
        index.clear();
        // Seite 500 und ihre Kinder 501…506, jede mit dem Beispiel der Referenz als gespeicherter Antwort.
        List<PageSnapshot> pages = IntStream.range(0, PAGES).mapToObj(i -> snapshot(String.valueOf(500 + i),
                i == 0 ? null : "500", "Seite " + i)).toList();
        cache.publish("wiki", "OPS", confirmed("g1"), pages);
        index.publish("wiki", new SearchIndexState("s1", Instant.parse("2026-10-16T20:10:00Z")),
                pages.stream().map(page -> InMemorySearchIndex.entry(page, confirmed("g1"))).toList());
    }

    @Test
    void theReferenceVersionIsPinned() {
        assertThat(VERSION).isEqualTo("10.2.0");
        assertThat(REFERENCE.get("info").get("version").asString()).isEqualTo(VERSION);
        for (String path : PARAMETERS.keySet()) {
            assertThat(REFERENCE.get("paths").get(path)).as(path).isNotNull();
            assertThat(REFERENCE.get("paths").get(path).isNull()).as(path).isFalse();
        }
    }

    @Test
    void everyDocumentedParameterIsAccountedFor() {
        for (Map.Entry<String, Map<String, List<Sample>>> endpoint : PARAMETERS.entrySet()) {
            assertThat(documentedQueryParameters(endpoint.getKey())).as(endpoint.getKey())
                    .isEqualTo(new TreeSet<>(endpoint.getValue().keySet()));
        }
    }

    @Test
    void everyDocumentedParameterBehavesAsClassified() throws Exception {
        for (Map.Entry<String, Map<String, List<Sample>>> endpoint : PARAMETERS.entrySet()) {
            for (Map.Entry<String, List<Sample>> parameter : endpoint.getValue().entrySet()) {
                for (Sample sample : parameter.getValue()) {
                    String query = parameter.getKey() + "=" + URLEncoder.encode(sample.value(), StandardCharsets.UTF_8);
                    String label = endpoint.getKey() + "?" + query;
                    HttpResponse<String> response = get(request(endpoint.getKey(), parameter.getKey(), query));
                    assertThat(response.statusCode()).as(label).isEqualTo(sample.status());
                    if (sample.status() == 200) {
                        assertConforms(label, JSON.readTree(response.body()), responseSchema(endpoint.getKey(), "200"));
                    } else {
                        assertConforms(label, JSON.readTree(response.body()), responseSchema(endpoint.getKey(), "404"));
                    }
                }
            }
        }
    }

    @Test
    void documentedStatusCodesAndBodies() throws Exception {
        HttpResponse<String> page = get(COMPAT + "/rest/api/content/500");
        assertThat(page.statusCode()).isEqualTo(200);
        assertConforms("Seite", JSON.readTree(page.body()), responseSchema(PAGE, "200"));

        for (String missing : List.of("/rest/api/content/999", "/rest/api/content/999/child/page")) {
            HttpResponse<String> response = get(COMPAT + missing);
            assertThat(response.statusCode()).as(missing).isEqualTo(404);
            JsonNode body = JSON.readTree(response.body());
            assertConforms(missing, body, responseSchema(PAGE, "404"));
            assertThat(body.get("statusCode").asInt()).isEqualTo(404);
            assertThat(body.get("reason").asString()).isEqualTo("Not Found");
        }
        assertThat(documentedStatusCodes(LIST)).containsExactly("200", "404");
        assertThat(documentedStatusCodes(PAGE)).containsExactly("200", "404");
        assertThat(documentedStatusCodes(CHILDREN)).containsExactly("200", "404");
    }

    @Test
    void paginationFollowsStartLimitAndLinks() throws Exception {
        int documentedDefault = documentedParameter(LIST, "limit").get("schema").get("default").asInt();
        JsonNode first = JSON.readTree(get(COMPAT + "/rest/api/content?spaceKey=OPS").body());
        assertThat(first.get("limit").asInt()).isEqualTo(documentedDefault);
        assertThat(first.get("start").asInt()).isZero();
        assertThat(first.get("size").asInt()).isEqualTo(PAGES);
        assertThat(first.get("_links").has("next")).isFalse();
        assertThat(first.get("_links").has("prev")).isFalse();

        for (String path : List.of("/rest/api/content?spaceKey=OPS", "/rest/api/content/500/child/page")) {
            int expected = path.contains("child") ? PAGES - 1 : PAGES;
            List<String> seen = new ArrayList<>();
            String next = path + (path.contains("?") ? "&" : "?") + "limit=3";
            int start = 0;
            while (next != null) {
                JsonNode batch = JSON.readTree(get(COMPAT + next).body());
                assertConforms(next, batch, responseSchema(path.contains("child") ? CHILDREN : LIST, "200"));
                assertThat(batch.get("start").asInt()).as(next).isEqualTo(start);
                assertThat(batch.get("limit").asInt()).as(next).isEqualTo(3);
                assertThat(batch.get("size").asInt()).as(next).isEqualTo(batch.get("results").size());
                JsonNode links = batch.get("_links");
                assertThat(links.get("base").asString()).endsWith(COMPAT);
                assertThat(links.get("self").asString()).endsWith(path.replaceAll("\\?.*", ""));
                if (start > 0) {
                    assertThat(links.get("prev").asString()).contains("start=" + Math.max(0, start - 3), "limit=3");
                } else {
                    assertThat(links.has("prev")).isFalse();
                }
                batch.get("results").forEach(result -> seen.add(result.get("id").asString()));
                start += batch.get("size").asInt();
                next = links.has("next") ? links.get("next").asString() : null;
                if (next != null) {
                    assertThat(next).startsWith(path.replaceAll("\\?.*", "")).contains("start=" + start, "limit=3");
                }
            }
            assertThat(seen).as(path).hasSize(expected).doesNotHaveDuplicates();
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // Referenz

    private static Set<String> documentedQueryParameters(String path) {
        Set<String> names = new TreeSet<>();
        REFERENCE.get("paths").get(path).get("parameters").forEach(parameter -> {
            if ("query".equals(parameter.get("in").asString())) {
                names.add(parameter.get("name").asString());
            }
        });
        return names;
    }

    private static JsonNode documentedParameter(String path, String name) {
        for (JsonNode parameter : REFERENCE.get("paths").get(path).get("parameters")) {
            if (name.equals(parameter.get("name").asString())) {
                return parameter;
            }
        }
        throw new AssertionError(path + ": kein Parameter " + name);
    }

    private static List<String> documentedStatusCodes(String path) {
        List<String> codes = new ArrayList<>(REFERENCE.get("paths").get(path).get("responses").propertyNames());
        codes.sort(null);
        return codes;
    }

    private static JsonNode responseSchema(String path, String status) {
        return REFERENCE.get("paths").get(path).get("responses").get(status).get("content").get("application/json")
                .get("schema");
    }

    private static void assertConforms(String label, JsonNode value, JsonNode schema) {
        List<String> errors = new ArrayList<>();
        conforms(value, schema, null, "", errors, 0);
        assertThat(errors).as(label).isEmpty();
    }

    /**
     * Die Teilmenge von JSON Schema, die die Referenz nutzt: {@code $ref}, {@code type}, {@code properties} und
     * {@code items}. Felder ohne Schema sind erlaubt, wie bei Confluence üblich.
     */
    private static void conforms(JsonNode value, JsonNode schema, String named, String path, List<String> errors,
                                 int depth) {
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asString();
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            JsonNode target = REFERENCE.get("components").get(ref);
            if (target == null) {
                errors.add(path + ": unbekanntes Schema " + ref);
            } else if (depth < 8) {
                conforms(value, target, name, name, errors, depth + 1);
            }
            return;
        }
        String type = schema.has("type") ? schema.get("type").asString() : schema.has("properties") ? "object" : null;
        if (type != null && !hasType(value, type)) {
            if (!KNOWN_DEVIATIONS.containsKey(path)) {
                errors.add(path + ": " + type + " erwartet, " + value.getNodeType() + " geliefert");
            }
            return;
        }
        if (value.isObject() && schema.has("properties")) {
            for (Map.Entry<String, JsonNode> property : schema.get("properties").properties()) {
                if (value.has(property.getKey()) && !value.get(property.getKey()).isNull()) {
                    conforms(value.get(property.getKey()), property.getValue(), named,
                            path.isEmpty() ? property.getKey() : path + "." + property.getKey(), errors, depth);
                }
            }
        }
        if (value.isArray() && schema.has("items")) {
            for (JsonNode item : value) {
                conforms(item, schema.get("items"), named, path, errors, depth);
            }
        }
    }

    private static boolean hasType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            default -> true;
        };
    }

    /** Ein Objekt aus den {@code example}-Werten eines Schemas, als gespeicherte Antwort einer Seite. */
    private static JsonNode example(JsonNode schema, int depth) {
        if (schema.has("$ref")) {
            JsonNode target = REFERENCE.get("components").get(schema.get("$ref").asString());
            return depth > 2 || target == null ? null : example(target, depth + 1);
        }
        if (schema.has("example")) {
            return schema.get("example");
        }
        if (schema.has("properties")) {
            ObjectNode node = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> property : schema.get("properties").properties()) {
                JsonNode child = example(property.getValue(), depth);
                if (child != null) {
                    node.set(property.getKey(), child);
                }
            }
            return node;
        }
        if ("array".equals(schema.path("type").asString()) && schema.has("items")) {
            JsonNode item = example(schema.get("items"), depth);
            return item == null ? JSON.createArrayNode() : JSON.createArrayNode().add(item);
        }
        return null;
    }

    private static PageSnapshot snapshot(String pageId, String parentPageId, String title) {
        ObjectNode payload = (ObjectNode) example(responseSchema(PAGE, "200"), 0);
        payload.put("id", pageId);
        payload.put("title", title);
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId, title, "OPS",
                parentPageId, "1", Instant.parse("2026-10-15T10:30:00Z"), null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new NativePayload(JSON.writeValueAsBytes(payload),
                "application/json", HARVEST_FORMAT), Instant.parse("2026-10-16T20:00:00Z"));
    }

    // -----------------------------------------------------------------------------------------------------------
    // HTTP

    private static String request(String endpoint, String parameter, String query) {
        return switch (endpoint) {
            case LIST -> COMPAT + LIST + "?" + (parameter.equals("spaceKey") ? "" : "spaceKey=OPS&") + query;
            case PAGE -> COMPAT + "/rest/api/content/500?" + query;
            case CHILDREN -> COMPAT + "/rest/api/content/500/child/page?" + query;
            default -> throw new IllegalArgumentException(endpoint);
        };
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(TestClient.authenticated(HttpRequest.newBuilder(URI.create("http://localhost:"
                + environment.getProperty("local.server.port") + path))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String resource(String name) {
        try (InputStream in = ConfluenceCompatContractTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Testressource fehlt: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ein Beispielwert und der erwartete Status. */
    private record Sample(String value, int status) {

        static Sample ok(String value) {
            return new Sample(value, 200);
        }

        static Sample ignored(String value) {
            return new Sample(value, 200);
        }

        static Sample rejected(String value) {
            return new Sample(value, 400);
        }
    }
}
