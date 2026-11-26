package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class ProgramMcpServerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @TempDir Path home;

    @Test
    void servesStoredFactsOverHttpAndChecksAuthorizationAndExactRevisions() throws Exception {
        var program = ftp("ftp", "APP.COBOL(BOOKING)", """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. BOOKING.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           COPY FIELDS.
                       PROCEDURE DIVISION.
                           MOVE 1 TO BOOKING-ID.
                           DISPLAY BOOKING-ID.
                           GOBACK.
                """);
        var copy = ftp("ftp", "APP.CPY(FIELDS)", "       01 BOOKING-ID PIC 9.\n");
        var hidden = ftp("other", "OTHER.COBOL(SECRET)", """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. SECRET.
                       PROCEDURE DIVISION.
                           GOBACK.
                """);
        var jcl = ftp("ftp", "APP.JCL(JOB)", "//JOB JOB\n//STEP EXEC PGM=BOOKING\n");
        var natural = ndv("ndv", "LIB", "MAIN.NSP", "CALL 'BOOKING'\nEND\n");
        try (var cache = LucenePageCache.open(home.resolve("pages"));
             var store = LuceneEnrichmentStore.open(home.resolve("analyses"))) {
            var enrichments = new Enrichments(new KnowledgeDocumentTextProjector(), List.of(
                    new CobolProgramEnrichment(Map.of("ftp", List.of("APP.CPY"), "other", List.of()), cache::find),
                    new JclProgramEnrichment(Set.of("ftp", "other")),
                    new NaturalProgramEnrichment(Set.of("ndv"))), store, Clock.systemUTC());
            for (var page : List.of(program, copy, hidden, jcl, natural)) cache.save(page);
            for (var page : List.of(program, hidden, jcl, natural)) assertThat(enrichments.refresh(page).computed()).isEqualTo(1);
            var allowed = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
            allowed.addAll(Set.of("ftp", "other", "ndv"));
            var denied = java.util.concurrent.ConcurrentHashMap.<PageKey>newKeySet();
            var released = new java.util.concurrent.CopyOnWriteArrayList<PageKey>();
            var reader = new LucenePageCacheReader(cache);
            var holdings = new ProgramMcpTools.Holdings() {
                @Override public Set<String> sources() { return Set.copyOf(allowed); }
                @Override public StoredProgramSearch.Snapshot search(Set<String> sources, BooleanSupplier cancelled) {
                    var result = new StoredProgramSearch(this::page, this::analyses);
                    for (String source : sources) {
                        if (!source.equals("ndv")) {
                            var cobol = new StoredCobolSearch(this::page, this::analyses);
                            result.addCobol(cobol.build(source, List.of("APP.CPY"),
                                    visit -> reader.sourceHeaders(source, null, 50).forEach(visit), cancelled), cancelled);
                        }
                        result.addSource(source, source.equals("ndv"), visit -> reader.sourceHeaders(source, null, 50).forEach(visit), cancelled);
                    }
                    return result.snapshot();
                }
                @Override public Optional<DeliveredPage> page(PageKey key) {
                    return denied.contains(key) ? Optional.empty() : reader.page(key).map(DeliveredPage::cached);
                }
                @Override public List<Enrichments.View> analyses(DeliveredPage page) {
                    return enrichments.view(page.snapshot());
                }
                @Override public void authorize(DeliveredPage page) { released.add(page.header().key()); }
            };
            try (var server = new ProgramMcpServer(new ProgramMcpTools(holdings, Set.of("ftp", "ndv")))) {
                var uri = URI.create(server.url());
                assertThat(uri.getHost()).isEqualTo("127.0.0.1");
                var init = rpc("initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "test", "version", "1")));
                assertThat(post(server, init, null, null).statusCode()).isEqualTo(401);
                assertThat(post(server, init, "wrong", null).statusCode()).isEqualTo(401);
                assertThat(post(server, init, server.token(), "https://untrusted.example").statusCode()).isEqualTo(403);
                assertThat(result(server, init).path("protocolVersion").asString()).isEqualTo("2025-11-25");
                assertThat(post(server, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"),
                        server.token(), null).statusCode()).isEqualTo(202);
                assertThat(result(server, rpc("tools/list", Map.of())).path("tools").size()).isEqualTo(3);
                assertThat(call(server, "program_sources", Map.of()).path("sources").get(0).asString()).isEqualTo("ftp");
                assertThat(call(server, "program_sources", Map.of()).path("sources").size()).isEqualTo(2);
                var found = call(server, "program_search", Map.of("mode", "CANDIDATES", "language", "JCL", "query", "BOOKING"));
                assertThat(found.path("total").asInt()).isEqualTo(1);
                assertThat(found.path("hits").get(0).path("candidate").path("location").path("key").path("pageId").asString())
                        .isEqualTo(program.key().pageId());
                assertThat(released).contains(program.key(), copy.key(), jcl.key()).doesNotContain(hidden.key());
                var naturalHits = call(server, "program_search", Map.of("mode", "CANDIDATES", "language", "NATURAL", "query", "BOOKING"));
                assertThat(naturalHits.path("total").asInt()).isEqualTo(1);
                assertThat(naturalHits.path("hits").get(0).path("fact").path("location").path("key").path("sourceId").asString()).isEqualTo("ndv");
                var naturalData = call(server, "program_analysis", Map.of("source", "ndv", "pageId", natural.key().pageId(),
                        "rawSha256", natural.payload().rawSha256(), "section", "dependencies"));
                assertThat(naturalData.path("rows").get(0).path("target").asString()).isEqualTo("BOOKING");
                var jclData = call(server, "program_analysis", Map.of("source", "ftp", "pageId", jcl.key().pageId(),
                        "rawSha256", jcl.payload().rawSha256(), "section", "statements"));
                assertThat(jclData.path("total").asInt()).isEqualTo(2);
                var args = Map.<String, Object>of("source", "ftp", "pageId", program.key().pageId(),
                        "rawSha256", program.payload().rawSha256());
                var data = call(server, "program_analysis", args);
                assertThat(data.path("sections").toString()).contains("nodes", "edges", "operations", "readSites");
                var page = new java.util.LinkedHashMap<>(args);
                page.put("section", "nodes");
                page.put("limit", 1);
                var nodes = call(server, "program_analysis", page);
                assertThat(nodes.path("rows").size()).isEqualTo(1);
                assertThat(nodes.path("total").asInt()).isGreaterThan(1);
                page.put("offset", 1);
                var next = call(server, "program_analysis", page);
                assertThat(next.path("rows").get(0)).isNotEqualTo(nodes.path("rows").get(0));
                assertThat(toolResult(server, "program_search", Map.of("source", "other")).path("isError").asBoolean()).isTrue();
                assertThat(toolResult(server, "program_search", Map.of("limit", 51)).path("isError").asBoolean()).isTrue();
                denied.add(copy.key());
                assertThat(toolResult(server, "program_analysis", args).path("isError").asBoolean()).isTrue();
                denied.clear();
                cache.save(ftp("ftp", "APP.CPY(FIELDS)", "       01 BOOKING-ID PIC 99.\n"));
                assertThat(toolResult(server, "program_analysis", args).path("isError").asBoolean()).isTrue();
                allowed.remove("ftp");
                assertThat(call(server, "program_sources", Map.of()).path("sources").size()).isEqualTo(1);
                assertThat(toolResult(server, "program_search", Map.of("source", "ftp")).path("isError").asBoolean()).isTrue();
                allowed.remove("ndv");
                assertThat(call(server, "program_sources", Map.of()).path("sources").size()).isZero();
                assertThat(toolResult(server, "program_search", Map.of()).path("isError").asBoolean()).isTrue();
                assertThat(released).doesNotContain(hidden.key());
            }
        }
    }

    private static Map<String, Object> rpc(String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params);
    }

    private static HttpResponse<String> post(ProgramMcpServer server, Object request, String token, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(server.url())).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request)));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (origin != null) builder.header("Origin", origin);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode result(ProgramMcpServer server, Object request) throws Exception {
        var response = post(server, request, server.token(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        var json = JSON.readTree(response.body());
        assertThat(json.path("error").isMissingNode()).isTrue();
        return json.path("result");
    }

    private static JsonNode toolResult(ProgramMcpServer server, String name, Map<String, Object> arguments) throws Exception {
        return result(server, rpc("tools/call", Map.of("name", name, "arguments", arguments)));
    }

    private static JsonNode call(ProgramMcpServer server, String name, Map<String, Object> arguments) throws Exception {
        var result = toolResult(server, name, arguments);
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return JSON.readTree(result.path("content").get(0).path("text").asString());
    }

    private static PageSnapshot ndv(String source, String library, String path, String code) {
        var payload = new NativePayload(code.getBytes(StandardCharsets.UTF_8), "text/plain",
                com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.NdvSourceProvider.SOURCE_TEXT_FORMAT);
        String resource = "ndv:host:2700/" + library + "/" + path;
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(resource.getBytes(StandardCharsets.UTF_8));
        var metadata = new PageMetadata(id, resource, path, source, null, "date|size=20",
                null, null, PageMetadata.sorted(Map.of("ndv.library", library)));
        return PageSnapshot.of(source, metadata, payload, Instant.now());
    }

    private static PageSnapshot ftp(String source, String path, String code) {
        var payload = new NativePayload(code.getBytes(StandardCharsets.UTF_8), "text/plain",
                com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider.SOURCE_TEXT_FORMAT);
        String resource = "ftp:host:21/" + path;
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(resource.getBytes(StandardCharsets.UTF_8));
        var metadata = new PageMetadata(id, resource, path, source, null, payload.rawSha256().substring(7),
                null, null, PageMetadata.sorted(Map.of("ftp.path", path)));
        return PageSnapshot.of(source, metadata, payload, Instant.now());
    }
}
