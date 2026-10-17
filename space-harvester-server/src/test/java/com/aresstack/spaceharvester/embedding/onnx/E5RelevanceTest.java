package com.aresstack.spaceharvester.embedding.onnx;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Relevanzsuche mit dem echten multilingual-e5-small (#89 Punkt 4): Deutsch und Englisch finden einander, Fremdes
 * steht dahinter, und eine Seite ohne {@code content}-Freigabe kommt nie aus den Vektoren, auch wenn sie am besten
 * passt. Läuft nur mit {@code SPACE_HARVESTER_E5_DIR} (Workflow {@code embedding-model.yml}); Qualität auf echten Daten
 * misst Angelo.
 */
@EnabledIfEnvironmentVariable(named = "SPACE_HARVESTER_E5_DIR", matches = ".+")
class E5RelevanceTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final AccessPolicy ALLOW_ALL = (context, scope, kind) -> AccessDecision.ALLOW;
    /** Space SEC: metadata ja, content nein. */
    private static final AccessPolicy NO_SEC_CONTENT = (context, scope, kind) -> kind == AccessKind.CONTENT
            && "SEC".equals(scope.spaceKey()) ? AccessDecision.DENY : AccessDecision.ALLOW;

    private static final HarvestSource SOURCE = (HarvestSource) Proxy.newProxyInstance(
            HarvestSource.class.getClassLoader(), new Class<?>[] {HarvestSource.class}, (proxy, method, args) -> null);
    private static final SourceCatalog CATALOG = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(SOURCE);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(SOURCE) : Optional.empty();
        }
    };

    private static final Map<String, String[]> PAGES = Map.of(
            "1", new String[] {"OPS", "Nachtbetrieb", "Jede Nacht um zwei Uhr startet der Batchlauf. Am Morgen prüft "
                    + "die Bereitschaft die Protokolle und meldet Abbrüche an den Betrieb."},
            "2", new String[] {"OPS", "Night operations", "Every night at two a.m. the batch run starts. In the "
                    + "morning the on-call engineer checks the logs and reports failed jobs to operations."},
            "3", new String[] {"HR", "Urlaubsantrag", "Urlaub wird im Personalportal beantragt und von der "
                    + "Führungskraft genehmigt. Resturlaub verfällt Ende März."},
            "4", new String[] {"HR", "Cafeteria menu", "On Fridays the cafeteria serves fish and vegetarian pasta. "
                    + "Desserts are free for interns."},
            "5", new String[] {"SEC", "Nächtlicher Betrieb im Rechenzentrum", "Der nächtliche Betrieb: Batchläufe "
                    + "starten nachts, die Bereitschaft überwacht den Nachtbetrieb und prüft morgens die Protokolle."});

    private static OnnxEmbeddingProvider provider;
    private static EmbeddingProcessor processor;
    private static final InMemorySearchIndex INDEX = new InMemorySearchIndex();
    private static final InMemoryEnrichmentStore STORE = new InMemoryEnrichmentStore();

    @BeforeAll
    static void embedPages() {
        Path dir = Path.of(System.getenv("SPACE_HARVESTER_E5_DIR"));
        provider = new OnnxEmbeddingProvider(new OnnxEmbeddingSettings(dir.resolve(E5Defaults.MODEL_FILE),
                dir.resolve(E5Defaults.TOKENIZER_FILE), E5Defaults.MODEL_SHA256, E5Defaults.TOKENIZER_SHA256,
                E5Defaults.MODEL_ID, E5Defaults.DIMENSIONS, E5Defaults.MAX_TOKENS, 4, 2, 1, Duration.ofMinutes(1),
                Duration.ofMinutes(5), E5Defaults.PASSAGE_PREFIX, E5Defaults.QUERY_PREFIX, E5Defaults.PAD_TOKEN_ID));
        processor = new EmbeddingProcessor(provider, 1500);
        List<InMemorySearchIndex.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, String[]> page : PAGES.entrySet()) {
            String[] fields = page.getValue();
            PageMetadata metadata = new PageMetadata(page.getKey(), "confluence:wiki/page/" + page.getKey(),
                    fields[1], fields[0], null, "1", Instant.parse("2026-10-01T00:00:00Z"), null,
                    PageMetadata.sorted(Map.of()));
            entries.add(entry(PageSnapshot.of("wiki", metadata, new NativePayload("{}".getBytes(
                    StandardCharsets.UTF_8), "application/json", "test"), Instant.parse("2026-10-17T08:00:00Z")),
                    confirmed("g1")));
            String text = fields[1] + "\n" + fields[2];
            PageText projected = new PageText("test/v1", text, List.of(new PageText.Section(fields[1], 1, 0,
                    text.length())));
            EnrichmentOutput output = processor.process(projected);
            STORE.save(new Enrichment(new PageKey("wiki", page.getKey()), "embedding", processor.version(),
                    projected.inputSha256(), "1", Enrichment.Status.OK, output.payloadJson(), output.warnings(),
                    null, Instant.parse("2026-10-17T08:00:00Z")));
        }
        INDEX.publish("wiki", new SearchIndexState("i1", Instant.parse("2026-10-17T08:00:00Z")), entries);
    }

    @AfterAll
    static void close() {
        if (provider != null) {
            provider.close();
        }
    }

    @Test
    void aGermanQuestionFindsTheGermanAndTheEnglishPageFirst() {
        List<RelevanceSearch.RankedHit> hits = search(ALLOW_ALL, "Wie läuft der nächtliche Betrieb?");

        assertThat(ids(hits).subList(0, 3)).containsExactlyInAnyOrder("1", "2", "5");
        assertRelevantAboveUnrelated(hits);
    }

    @Test
    void anEnglishQuestionFindsTheGermanAndTheEnglishPageFirst() {
        List<RelevanceSearch.RankedHit> hits = search(ALLOW_ALL, "How do the nightly batch operations work?");

        assertThat(ids(hits).subList(0, 3)).containsExactlyInAnyOrder("1", "2", "5");
        assertRelevantAboveUnrelated(hits);
    }

    @Test
    void theBestMatchWithoutContentAccessNeverAppears() {
        List<RelevanceSearch.RankedHit> hits = search(NO_SEC_CONTENT, "Wie läuft der nächtliche Betrieb im "
                + "Rechenzentrum?");

        assertThat(ids(hits)).doesNotContain("5");
        assertThat(ids(hits).subList(0, 2)).containsExactlyInAnyOrder("1", "2");
    }

    private static List<RelevanceSearch.RankedHit> search(AccessPolicy policy, String q) {
        RelevanceSearch search = new RelevanceSearch(CATALOG, INDEX, new AccessGuard(policy), STORE, STORE,
                processor);
        List<RelevanceSearch.RankedHit> hits = search.search(CALLER, PageSearchQuery.of("wiki", List.of(), null,
                null, null, null, null, null, SearchSort.TITLE), q, 10).hits();
        for (RelevanceSearch.RankedHit hit : hits) {
            System.out.printf("%s %.4f %s%n", hit.hit().page().pageId(), hit.relevance().similarity(),
                    hit.hit().page().header().metadata().title());
        }
        return hits;
    }

    private static void assertRelevantAboveUnrelated(List<RelevanceSearch.RankedHit> hits) {
        double worstRelevant = similarity(hits, "1") < similarity(hits, "2") ? similarity(hits, "1")
                : similarity(hits, "2");
        assertThat(worstRelevant).isGreaterThan(similarity(hits, "3")).isGreaterThan(similarity(hits, "4"));
    }

    private static double similarity(List<RelevanceSearch.RankedHit> hits, String pageId) {
        return hits.stream().filter(hit -> hit.hit().page().pageId().equals(pageId)).findFirst().orElseThrow()
                .relevance().similarity();
    }

    private static List<String> ids(List<RelevanceSearch.RankedHit> hits) {
        return hits.stream().map(hit -> hit.hit().page().pageId()).toList();
    }
}
