package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.HitScorer;
import com.aresstack.spaceharvester.application.query.progressive.RuleBasedExpansion;
import com.aresstack.spaceharvester.application.query.progressive.SearchOrchestrator;
import com.aresstack.spaceharvester.application.query.progressive.VariantSearch;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Progressive Suche über echtes HTTP (ADR 0017): starten und abfragen, auf das Ergebnis warten, abbrechen, fremde
 * Aufrufer und ungültige Angaben. Die Suchanfragen beantwortet eine feste Suche: je Wort ein Treffer, bewertet nach
 * dem Titel; Texte mit „langsam“ antworten erst nach dem Test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ProgressiveSearchApiTest {

    private static final String OTHER_TOKEN = "other-token-81d0";
    private static final CountDownLatch SLOW = new CountDownLatch(1);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki");
        registry.add("space-harvester.security.clients[1].id", () -> "other");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(OTHER_TOKEN));
        registry.add("space-harvester.security.clients[1].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[1].grants[0].spaces[0]", () -> "*");
        registry.add("space-harvester.security.clients[1].grants[0].access", () -> "metadata,content");
    }

    @TestConfiguration
    static class FixedSearch {

        @Bean
        @Primary
        SearchOrchestrator fixedOrchestrator() {
            VariantSearch search = (AccessContext caller, KeywordSearch.Query query, int limit) -> {
                if (query.text().contains("langsam")) {
                    try {
                        SLOW.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                List<KeywordSearch.Hit> hits = Arrays.stream(query.text().split(" "))
                        .map(word -> new KeywordSearch.Hit("wiki", word.toLowerCase(Locale.ROOT), "DEV", word,
                                "Auszug " + word, URI.create("https://wiki.invalid/" + word), "1", null, null))
                        .toList();
                return new KeywordSearch.Results("wiki", hits, List.of(), null, true, 0);
            };
            HitScorer scorer = (original, hits) -> hits.stream()
                    .mapToDouble(hit -> hit.title().equals("Nachtlauf") ? 0.9 : 0.2).toArray();
            return new SearchOrchestrator(search, new RuleBasedExpansion(), scorer);
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void aStartedSearchRunsInTheBackgroundAndIsPolledUntilDone() throws Exception {
        HttpResponse<String> started = post(TestClient.TOKEN,
                "{\"q\":\"Nachtlauf Betrieb\",\"mode\":\"all-variants\",\"expansion\":\"rules\"}");

        assertThat(started.statusCode()).isEqualTo(202);
        JsonNode body = json.readTree(started.body());
        String id = body.get("id").asString();
        assertThat(started.headers().firstValue("Location")).contains("/api/v1/search/progressive/" + id);
        assertThat(body.get("mode").asString()).isEqualTo("all-variants");
        assertThat(body.get("expansion").asString()).isEqualTo("rules");

        JsonNode done = awaitDone(id);
        assertThat(done.get("status").asString()).isEqualTo("done");
        assertThat(done.get("reason").asString()).isEqualTo("all-variants-done");
        JsonNode first = done.get("hits").get(0);
        assertThat(first.get("sourceId").asString()).isEqualTo("wiki");
        assertThat(first.get("pageId").asString()).isEqualTo("nachtlauf");
        assertThat(first.get("spaceKey").asString()).isEqualTo("DEV");
        assertThat(first.get("title").asString()).isEqualTo("Nachtlauf");
        assertThat(first.get("snippet").asString()).isEqualTo("Auszug Nachtlauf");
        assertThat(first.get("url").asString()).isEqualTo("https://wiki.invalid/Nachtlauf");
        assertThat(first.get("score").asDouble()).isEqualTo(0.9);
        assertThat(first.get("foundBy").asInt()).isZero();
        assertThat(first.get("confirmed").asBoolean()).isTrue();
        assertThat(done.get("hits")).extracting(hit -> hit.get("pageId").asString())
                .containsExactly("nachtlauf", "betrieb");
        JsonNode original = done.get("variants").get(0);
        assertThat(original.get("text").asString()).isEqualTo("Nachtlauf Betrieb");
        assertThat(original.get("priority").asInt()).isZero();
        assertThat(original.get("status").asString()).isEqualTo("done");
        assertThat(original.get("newHits").asInt()).isEqualTo(2);
        assertThat(done.get("variants").size()).isGreaterThan(1);
        assertThat(done.get("elapsedMs").asLong()).isNotNegative();
        assertThat(done.get("loads")).isEmpty();
    }

    @Test
    void anAgentWaitsForTheFinalListWithItsReason() throws Exception {
        HttpResponse<String> response = post(TestClient.TOKEN,
                "{\"q\":\"Nachtlauf Betrieb\",\"wait\":true,\"minRelevantHits\":1,\"minScore\":0.5}");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("done").asBoolean()).isTrue();
        assertThat(body.get("reason").asString()).isEqualTo("quality-reached");
        assertThat(body.get("mode").asString()).isEqualTo("quality");
        assertThat(body.get("hits").get(0).get("pageId").asString()).isEqualTo("nachtlauf");
        // Ohne Chat-Modell formulieren die Regeln die Varianten.
        assertThat(body.get("expansion").asString()).isEqualTo("rules");
    }

    @Test
    void aCancelledSearchEndsWithWhatItFound() throws Exception {
        try {
            JsonNode started = json.readTree(post(TestClient.TOKEN, "{\"q\":\"langsam suchen\"}").body());
            String id = started.get("id").asString();
            assertThat(started.get("done").asBoolean()).isFalse();
            assertThat(started.get("reason").isNull()).isTrue();

            assertThat(delete(TestClient.TOKEN, id).statusCode()).isEqualTo(204);
            JsonNode done = awaitDone(id);
            assertThat(done.get("reason").asString()).isEqualTo("cancelled");
            assertThat(done.get("variants").get(0).get("status").asString()).isEqualTo("skipped");
            // Eine beendete Suche bleibt abrufbar und unverändert.
            assertThat(delete(TestClient.TOKEN, id).statusCode()).isEqualTo(204);
            assertThat(json.readTree(get(TestClient.TOKEN, id).body()).get("reason").asString())
                    .isEqualTo("cancelled");
        } finally {
            SLOW.countDown();
        }
        assertThat(delete(TestClient.TOKEN, "unbekannt").statusCode()).isEqualTo(404);
    }

    @Test
    void anotherCallerNeverSeesTheSearch() throws Exception {
        String id = json.readTree(post(TestClient.TOKEN, "{\"q\":\"Nachtlauf\",\"wait\":true}").body()).get("id")
                .asString();

        assertThat(get(OTHER_TOKEN, id).statusCode()).isEqualTo(404);
        assertThat(delete(OTHER_TOKEN, id).statusCode()).isEqualTo(404);
        assertThat(get(TestClient.TOKEN, id).statusCode()).isEqualTo(200);
        assertThat(get(TestClient.TOKEN, "unbekannt").statusCode()).isEqualTo(404);
    }

    @Test
    void invalidRequestsAre400() throws Exception {
        assertProblem(post(TestClient.TOKEN, "{}"), 400);
        assertProblem(post(TestClient.TOKEN, "{\"q\":\" \"}"), 400);
        assertProblem(post(TestClient.TOKEN, "{\"q\":\"" + "x".repeat(KeywordSearch.MAX_TEXT_LENGTH + 1) + "\"}"),
                400);
        assertProblem(post(TestClient.TOKEN, "{\"q\":\"Nachtlauf\",\"mode\":\"schnell\"}"), 400);
        assertProblem(post(TestClient.TOKEN, "{\"q\":\"Nachtlauf\",\"expansion\":\"llm\"}"), 400);
        assertProblem(post(TestClient.TOKEN, "{\"q\":\"Nachtlauf\",\"maxVariants\":33}"), 400);
        assertProblem(post(TestClient.TOKEN, "{\"q\":\"Nachtlauf\",\"minRelevantHits\":0}"), 400);
        assertProblem(post(TestClient.TOKEN, "{\"q\":\"Nachtlauf\",\"timeBudgetMs\":0}"), 400);
        assertProblem(post(TestClient.TOKEN, "{\"q\":\"Nachtlauf\",\"timeBudgetMs\":120001}"), 400);
        assertProblem(post(TestClient.TOKEN, "{\"q\":\"Nachtlauf\",\"limitPerSearch\":101}"), 400);
        assertProblem(post(TestClient.TOKEN, "{\"q\":\"Nachtlauf\",\"sourceId\":\"wiki\",\"sourceIds\":[\"wiki\"]}"),
                400);
    }

    private JsonNode awaitDone(String id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            HttpResponse<String> response = get(TestClient.TOKEN, id);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = json.readTree(response.body());
            if (body.get("done").asBoolean()) {
                return body;
            }
            assertThat(body.get("status").asString()).isEqualTo("running");
            assertThat(body.get("reason").isNull()).isTrue();
            assertThat(System.nanoTime()).as("Suche endet").isLessThan(until);
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private void assertProblem(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(json.readTree(response.body()).get("type").asString())
                .isEqualTo("https://github.com/aresstack/space-harvester/problems/invalid-request");
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/search/progressive"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String token, String id) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/search/progressive/" + id))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String token, String id) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/search/progressive/" + id))
                .header("Authorization", "Bearer " + token).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
