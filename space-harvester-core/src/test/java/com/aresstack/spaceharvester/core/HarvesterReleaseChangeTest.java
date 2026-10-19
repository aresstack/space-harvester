package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.passages.NavigationPayload;
import com.aresstack.spaceharvester.application.passages.NavigationProcessor;
import com.aresstack.spaceharvester.application.passages.PassagePayload;
import com.aresstack.spaceharvester.application.passages.PassageProcessor;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wechsel der Freigabe {@code ai.external-processing-sources} zur Laufzeit (ADR 0015): Die Seiten der Quelle im Cache
 * werden ohne neuen Abruf und ohne neue Version neu angereichert, mit Modell nach der Freigabe, ohne nach dem Entzug.
 */
class HarvesterReleaseChangeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;
    private static final List<String> PAGES = List.of("101", "102", "103");

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>").storage("<p>Start</p>");
        CONFLUENCE.homepage("DEV", "100");
        for (String id : PAGES) {
            // Jeder Satz nennt seine Seite, damit der Test jeden Modellaufruf einer Seite zuordnen kann.
            String storage = "<h1>Betrieb " + id + "</h1><p>Der Nachtlauf " + id + " startet um zwei Uhr. Lauf " + id
                    + " sichert alle Datenbanken. Danach prüft Lauf " + id + " die Protokolle. Fehler von " + id
                    + " gehen an die Bereitschaft.</p><h1>Urlaub " + id + "</h1><p>Urlaub " + id + " beantragt man im "
                    + "Portal. Die Leitung genehmigt Urlaub " + id + ". Danach steht Urlaub " + id + " im Kalender. "
                    + "Vertretungen für " + id + " trägt man selbst ein.</p>";
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
    /** Je Modellaufruf die Seite, aus der die Texte stammen. */
    private final List<String> calledPages = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Hält jeden Aufruf des Modells an, bis der Test ihn freigibt; {@code null}: sofort antworten. */
    private volatile CountDownLatch gate;

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @BeforeEach
    void startModel() throws IOException {
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/embeddings", this::embed);
        model.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        model.start();
    }

    @AfterEach
    void stopModel() {
        CountDownLatch open = gate;
        if (open != null) {
            open.countDown();
        }
        model.stop(0);
    }

    @Test
    void afterAReleaseTheSamePagesAreEnrichedWithTheModelWithoutANewVersion() throws InterruptedException {
        ConfigurationManager manager = new ConfigurationManager(configuration(Set.of()));
        try (Harvester harvester = harvester(manager)) {
            load(harvester);
            awaitTrue(() -> PAGES.stream().allMatch(id -> notApproved(harvester, id)), "Absätze nach Struktur");
            assertThat(calls).hasValue(0);
            long version = manager.current().version();
            String passagesVersion = harvester.enrichments().processorVersion(PassageProcessor.ID).orElseThrow();

            assertThat(manager.apply(configuration(Set.of("wiki"))).activated()).isTrue();

            awaitTrue(() -> PAGES.stream().allMatch(id -> withModel(harvester, id)), "Anreicherung mit Modell");
            assertThat(calls.get()).isGreaterThanOrEqualTo(PAGES.size());
            // Gleiches Modell, gleicher Text: dieselbe Version, nur das Ergebnis ist neu.
            assertThat(harvester.enrichments().processorVersion(PassageProcessor.ID)).hasValue(passagesVersion);
            assertThat(manager.current().version()).isEqualTo(version + 1);
        }
    }

    @Test
    void afterARevocationNoQueuedCalculationCallsTheModelAndTheResultsComeWithoutIt() throws Exception {
        ConfigurationManager manager = new ConfigurationManager(configuration(Set.of()));
        try (Harvester harvester = harvester(manager)) {
            load(harvester);
            awaitTrue(() -> PAGES.stream().allMatch(id -> notApproved(harvester, id)), "Absätze nach Struktur");
            CountDownLatch held = new CountDownLatch(1);
            gate = held;

            // Freigabe: Die Seiten warten im Hintergrund, die erste Berechnung hängt im Modell.
            assertThat(manager.apply(configuration(Set.of("wiki"))).activated()).isTrue();
            awaitTrue(() -> calls.get() == 1, "erster Modellaufruf");
            assertThat(manager.apply(configuration(Set.of())).activated()).isTrue();
            held.countDown();

            awaitTrue(() -> PAGES.stream().allMatch(id -> notApproved(harvester, id) && withoutEmbedding(harvester, id)
                    && navigationNotApproved(harvester, id)), "Rückfall ohne Modell");
            // Nur die Berechnung, die vor dem Entzug schon lief, fragt das Modell (auch mehrmals) zu Ende; keine
            // eingereihte Seite fragt es danach.
            assertThat(calledPages.getFirst()).isIn(PAGES);
            assertThat(calledPages).hasSize(calls.get()).containsOnly(calledPages.getFirst());
        }
    }

    @Test
    void aRevocationWhileStoppedTakesEffectAtTheNextStartWithoutTheModel() throws InterruptedException {
        try (Harvester harvester = harvester(new ConfigurationManager(configuration(Set.of("wiki"))))) {
            load(harvester);
            awaitTrue(() -> PAGES.stream().allMatch(id -> withModel(harvester, id)), "Anreicherung mit Modell");
        }
        calls.set(0);

        // Entzug, während der Dienst nicht läuft: Der Start gleicht aus den gespeicherten Ergebnissen ab.
        try (Harvester harvester = harvester(new ConfigurationManager(configuration(Set.of())))) {
            awaitTrue(() -> PAGES.stream().allMatch(id -> notApproved(harvester, id)
                    && withoutEmbedding(harvester, id) && navigationNotApproved(harvester, id)), "Rückfall beim Start");
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void aReleaseWhileStoppedTakesEffectAtTheNextStartWithTheModel() throws InterruptedException {
        try (Harvester harvester = harvester(new ConfigurationManager(configuration(Set.of())))) {
            load(harvester);
            awaitTrue(() -> PAGES.stream().allMatch(id -> notApproved(harvester, id)), "Absätze nach Struktur");
        }
        assertThat(calls).hasValue(0);

        try (Harvester harvester = harvester(new ConfigurationManager(configuration(Set.of("wiki"))))) {
            awaitTrue(() -> PAGES.stream().allMatch(id -> withModel(harvester, id)), "Anreicherung beim Start");
            assertThat(calls.get()).isGreaterThanOrEqualTo(PAGES.size());
        }
    }

    @Test
    void atTheStartOnlyPagesThatDoNotFitTheReleaseCallTheModel() throws InterruptedException {
        try (Harvester harvester = harvester(new ConfigurationManager(configuration(Set.of("wiki"))))) {
            load(harvester);
            awaitTrue(() -> PAGES.stream().allMatch(id -> withModel(harvester, id)), "Anreicherung mit Modell");
            // Wie ein abgebrochener Lauf: einer Seite fehlt das Embedding.
            harvester.enrichments().store().remove(new PageKey("wiki", PAGES.getLast()), "embedding");
        }
        calls.set(0);
        calledPages.clear();

        try (Harvester harvester = harvester(new ConfigurationManager(configuration(Set.of("wiki"))))) {
            // Der Abgleich geht die Seiten in ihrer Reihenfolge durch; ist die letzte neu gerechnet, ist über die
            // anderen entschieden.
            awaitTrue(() -> withModel(harvester, PAGES.getLast()), "Neuberechnung der unvollständigen Seite");
            assertThat(calledPages).isNotEmpty().containsOnly(PAGES.getLast());
            assertThat(PAGES).allMatch(id -> withModel(harvester, id));
        }
    }

    private Harvester harvester(ConfigurationManager manager) {
        return Harvester.builder().source(wiki()).dataDirectory(data).configuration(manager).build();
    }

    private static SourceSettingsConfig wiki() {
        return new SourceSettingsConfig("wiki", "confluence", "Wiki", SERVER.baseUrl(),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null, "token"),
                Map.of("connectTimeoutMillis", "2000", "allowInsecureHttp", "true"));
    }

    private HarvesterConfiguration configuration(Set<String> released) {
        return HarvesterConfiguration.DEFAULT.withAi(new AiServiceSettings(
                URI.create("http://127.0.0.1:" + model.getAddress().getPort() + "/v1"), null, null, "modell-a", 3,
                null, null, released));
    }

    private static void load(Harvester harvester) {
        for (String id : PAGES) {
            harvester.pages().page(harvester.caller(), "wiki", id, false);
        }
    }

    private static Optional<JsonNode> result(Harvester harvester, String pageId, String processorId) {
        return harvester.enrichments().store().find(new PageKey("wiki", pageId)).stream()
                .filter(enrichment -> enrichment.processorId().equals(processorId)
                        && enrichment.status() == Enrichment.Status.OK)
                .findFirst().map(enrichment -> JSON.readTree(enrichment.payloadJson()));
    }

    private static boolean notApproved(Harvester harvester, String pageId) {
        return result(harvester, pageId, PassageProcessor.ID).map(passages -> "structure".equals(
                passages.path("mode").asText()) && PassagePayload.REASON_NOT_APPROVED.equals(
                passages.path("reason").asText())).orElse(false);
    }

    private static boolean navigationNotApproved(Harvester harvester, String pageId) {
        return result(harvester, pageId, NavigationProcessor.ID).map(navigation ->
                NavigationPayload.REASON_NOT_APPROVED.equals(navigation.path("reason").asText())).orElse(false);
    }

    private static boolean withoutEmbedding(Harvester harvester, String pageId) {
        return harvester.enrichments().store().find(new PageKey("wiki", pageId)).stream()
                .noneMatch(enrichment -> enrichment.processorId().equals("embedding"));
    }

    private static boolean withModel(Harvester harvester, String pageId) {
        boolean passages = result(harvester, pageId, PassageProcessor.ID)
                .map(payload -> "semantic".equals(payload.path("mode").asText())).orElse(false);
        boolean embedding = result(harvester, pageId, "embedding")
                .map(payload -> payload.path("chunks").size() > 0).orElse(false);
        boolean navigation = result(harvester, pageId, NavigationProcessor.ID).map(payload -> {
            String reason = payload.path("reason").asText();
            return !NavigationPayload.REASON_NOT_APPROVED.equals(reason)
                    && !NavigationPayload.REASON_NOT_EMBEDDED.equals(reason);
        }).orElse(false);
        return passages && embedding && navigation;
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

    private void embed(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        String texts = request.path("input").toString();
        calledPages.add(PAGES.stream().filter(texts::contains).findFirst().orElse("?"));
        CountDownLatch held = gate;
        if (held != null) {
            try {
                held.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        JsonNode input = request.path("input");
        int count = input.isArray() ? input.size() : 1;
        StringBuilder body = new StringBuilder("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < count; i++) {
            // Zwei Themen je Seite: Betrieb und Urlaub, damit die Absätze an der Themengrenze wechseln.
            String vector = i < count / 2 ? "[0.6,0.8,0.0]" : "[0.0,0.6,0.8]";
            body.append(i == 0 ? "" : ",").append("{\"object\":\"embedding\",\"index\":").append(i)
                    .append(",\"embedding\":").append(vector).append('}');
        }
        byte[] bytes = body.append("]}").toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
