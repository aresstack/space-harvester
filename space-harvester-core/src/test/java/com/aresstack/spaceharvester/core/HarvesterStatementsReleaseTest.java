package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementProcessor;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementSettings;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wechsel der Freigabe {@code ai.external-processing-sources} mit eingeschalteter Satzanalyse (#108) und nur einem
 * Chat-Modell, ohne Embedding-Modell (ADR 0015): Die Freigabe rechnet die Seiten im Cache mit dem Modell nach, der Entzug
 * entfernt die Ergebnisse wieder.
 */
class HarvesterStatementsReleaseTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;
    private static final List<String> PAGES = List.of("201", "202");
    private static final String EMPTY = "{\\\"mentions\\\": [], \\\"events\\\": [], \\\"relations\\\": []}";

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("200", "Start", "DEV", "<p>Start</p>").storage("<p>Start</p>");
        CONFLUENCE.homepage("DEV", "200");
        for (String id : PAGES) {
            String storage = "<p>Der Nachtlauf " + id + " sichert alle Datenbanken.</p>";
            CONFLUENCE.page(id, "Seite " + id, "DEV", storage).storage(storage);
        }
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TempDir
    Path data;

    private HttpServer model;
    private final AtomicInteger calls = new AtomicInteger();

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @BeforeEach
    void startModel() throws IOException {
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/chat/completions", this::chat);
        model.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        model.start();
    }

    @AfterEach
    void stopModel() {
        model.stop(0);
    }

    @Test
    void aReleaseRecomputesTheStatementsAndARevocationRemovesThem() throws InterruptedException {
        ConfigurationManager manager = new ConfigurationManager(configuration(Set.of()));
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data).configuration(manager)
                .statements(new StatementSettings(true, false, StatementSettings.DEFAULT_MAX_PARAGRAPHS,
                        StatementSettings.DEFAULT_MAX_PARAGRAPH_CHARS)).build()) {
            for (String id : PAGES) {
                harvester.pages().page(harvester.caller(), "wiki", id, false);
            }
            harvester.enrichments().flush();
            assertThat(PAGES).noneMatch(id -> statements(harvester, id));
            assertThat(calls).hasValue(0);

            assertThat(manager.apply(configuration(Set.of("wiki"))).activated()).isTrue();
            awaitTrue(() -> PAGES.stream().allMatch(id -> statements(harvester, id)), "Satzanalyse nach Freigabe");
            assertThat(calls.get()).isGreaterThanOrEqualTo(PAGES.size());

            assertThat(manager.apply(configuration(Set.of())).activated()).isTrue();
            awaitTrue(() -> PAGES.stream().noneMatch(id -> statements(harvester, id)), "keine Satzanalyse nach Entzug");
        }
    }

    @Test
    void aReleaseWhileStoppedIsReconciledOnStartWithOnlyAChatModel() throws InterruptedException {
        try (Harvester harvester = harvester(new ConfigurationManager(configuration(Set.of())))) {
            for (String id : PAGES) {
                harvester.pages().page(harvester.caller(), "wiki", id, false);
            }
            harvester.enrichments().flush();
            assertThat(PAGES).noneMatch(id -> statements(harvester, id));
        }
        try (Harvester harvester = harvester(new ConfigurationManager(configuration(Set.of("wiki"))))) {
            awaitTrue(() -> PAGES.stream().allMatch(id -> statements(harvester, id)), "Satzanalyse nach dem Start");
        }
        try (Harvester harvester = harvester(new ConfigurationManager(configuration(Set.of())))) {
            awaitTrue(() -> PAGES.stream().noneMatch(id -> statements(harvester, id)), "Entzug beim Start");
        }
    }

    private Harvester harvester(ConfigurationManager manager) {
        return Harvester.builder().source(wiki()).dataDirectory(data).configuration(manager)
                .statements(new StatementSettings(true, false, StatementSettings.DEFAULT_MAX_PARAGRAPHS,
                        StatementSettings.DEFAULT_MAX_PARAGRAPH_CHARS)).build();
    }

    private static SourceSettingsConfig wiki() {
        return new SourceSettingsConfig("wiki", "confluence", "Wiki", SERVER.baseUrl(),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null, "token"),
                Map.of("connectTimeoutMillis", "2000", "allowInsecureHttp", "true"));
    }

    private HarvesterConfiguration configuration(Set<String> released) {
        return HarvesterConfiguration.DEFAULT.withAi(new AiServiceSettings(
                URI.create("http://127.0.0.1:" + model.getAddress().getPort() + "/v1"), null, "chat-a", null, null,
                null, null, released, null, null));
    }

    private static boolean statements(Harvester harvester, String pageId) {
        return harvester.enrichments().store().find(new PageKey("wiki", pageId)).stream()
                .anyMatch(enrichment -> enrichment.processorId().equals(StatementProcessor.ID)
                        && enrichment.status() == Enrichment.Status.OK);
    }

    /** Wartet mit Frist, ohne feste Pause: jede Prüfung liest den Stand der Ablage neu. */
    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError(what + " nach 30 s nicht erreicht");
            }
            Thread.sleep(20);
        }
    }

    private void chat(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = ("{\"id\":\"c1\",\"object\":\"chat.completion\",\"model\":\"chat-a\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + EMPTY + "\"},\"finish_reason\":\"stop\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
