package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.configuration.EnrichmentSettings;
import com.aresstack.spaceharvester.application.enrichment.DocumentEnrichments;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingPayload;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.passages.DocumentNavigationBuilder;
import com.aresstack.spaceharvester.application.passages.NavigationProcessor;
import com.aresstack.spaceharvester.application.passages.PassageChunks;
import com.aresstack.spaceharvester.application.passages.PassageProcessor;
import com.aresstack.spaceharvester.application.passages.PassageSegmentation;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore;
import com.aresstack.spaceharvester.testcorpus.Archives;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Absätze, Embeddings und Navigation je Anhang und enthaltener Datei (ADR 0026, AP9a): gerechnet aus dem Lesetext des
 * Dokuments, an das Embedding-Modell nur für freigegebene Quellen und höchstens bis zur Grenze je Dokument; Absätze und
 * Navigation ohne Grenze.
 */
class DocumentPassagesTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final PageKey PAGE = new PageKey("wiki", "101");
    private static final String HTML = "<html><body><h1>Betrieb</h1><p>Der Antrag wird geprüft. Die Frist beträgt "
            + "zwei Wochen.</p><table><tr><td>Frist</td><td>14 Tage</td></tr><tr><td>Stelle</td><td>Amt</td></tr>"
            + "</table><p>Danach ergeht der Bescheid. Er wird zugestellt.</p></body></html>";

    @TempDir
    Path directory;

    private FileSystemAssetStore store;
    private DocumentExtractions extractions;
    private InMemoryEnrichmentStore results;
    private final List<String> embedded = new ArrayList<>();
    private boolean released;

    @BeforeEach
    void setUp() {
        store = new FileSystemAssetStore(directory.resolve("assets"));
        extractions = new DocumentExtractions(new TikaDocumentStructure(),
                new FileSystemExtractionStore(directory.resolve("extractions")), ExtractionLimits.DEFAULTS);
        results = new InMemoryEnrichmentStore();
        released = true;
    }

    private DocumentEnrichments documents(long embeddingMaxChars) {
        EmbeddingProvider provider = new EmbeddingProvider() {
            @Override
            public String modelId() {
                return "test";
            }

            @Override
            public int dimensions() {
                return 2;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                embedded.addAll(texts);
                return texts.stream().map(text -> new float[] {1, text.length()}).toList();
            }
        };
        SentenceSegmenter sentences = SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults());
        EmbeddingProcessor embedding = new EmbeddingProcessor(provider, 1500, KnowledgeTextSegmentation.defaults(),
                sourceId -> released).withPassages(new PassageChunks(new PassageSegmentation(sentences,
                KnowledgeTextSegmentation.defaults())));
        Enrichments enrichments = new Enrichments(snapshot -> Optional.empty(), List.of(embedding,
                new PassageProcessor(sentences, KnowledgeTextSegmentation.defaults(), embedding),
                new NavigationProcessor(new DocumentNavigationBuilder(), embedding, results::find, results::find,
                        null)), results, Clock.fixed(NOW, ZoneOffset.UTC));
        return new DocumentEnrichments(enrichments, null, () -> new EnrichmentSettings(true, embeddingMaxChars));
    }

    @Test
    void aReleasedDocumentGetsPassagesEmbeddingsAndNavigationFromItsOwnText() {
        Asset asset = stored(Map.of("bericht.html", HTML));

        documents(0).indexed(list(), asset, extractions);

        List<Enrichment> found = results.findDocuments(PAGE);
        assertThat(found).extracting(Enrichment::processorId)
                .containsExactlyInAnyOrder("embedding", "passages", "navigation");
        assertThat(found).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(Enrichment.Status.OK);
            assertThat(result.document().isEmbedded()).isTrue();
        });
        Enrichment vectors = processor(found, "embedding");
        assertThat(EmbeddingPayload.parse(vectors.payloadJson()).chunks()).isNotEmpty();
        assertThat(vectors.payloadJson()).doesNotContain("\"cut\"");
        assertThat(processor(found, "passages").payloadJson()).contains("\"mode\":\"semantic\"")
                .as("Tabellenzeilen eigene Absätze, keiner reicht über die Tabelle").contains("\"kind\":\"table-row\"");
        assertThat(embedded).isNotEmpty().noneMatch(text -> text.contains("bericht.html"));
    }

    @Test
    void anUnreleasedSourceSendsNothingToTheModelAndKeepsPassagesByStructure() {
        released = false;
        Asset asset = stored(Map.of("bericht.html", HTML));

        documents(0).indexed(list(), asset, extractions);

        assertThat(embedded).as("kein Aufruf des Embedding-Modells").isEmpty();
        List<Enrichment> found = results.findDocuments(PAGE);
        assertThat(found).extracting(Enrichment::processorId).containsExactlyInAnyOrder("passages", "navigation");
        assertThat(processor(found, "passages").payloadJson()).contains("\"mode\":\"structure\"")
                .contains("source-not-approved");
    }

    @Test
    void theEmbeddingLimitPerDocumentCutsWhatGoesToTheModelAndIsRecordedAsData() {
        Asset asset = stored(Map.of("bericht.html", HTML));

        documents(20).indexed(list(), asset, extractions);

        List<Enrichment> found = results.findDocuments(PAGE);
        Enrichment vectors = processor(found, "embedding");
        assertThat(vectors.payloadJson()).contains("\"cut\":{\"maxChars\":20,\"offset\":0,\"embeddedChars\":20");
        assertThat(vectors.warnings()).anySatisfy(warning -> assertThat(warning).contains("Grenze"));
        assertThat(EmbeddingPayload.parse(vectors.payloadJson()).chunks())
                .allSatisfy(chunk -> assertThat(chunk.charEnd()).isLessThanOrEqualTo(20));
        assertThat(embedded).allSatisfy(text -> assertThat(text.length()).isLessThanOrEqualTo(20 + 40));
        java.util.regex.Matcher ends = java.util.regex.Pattern.compile("\"end\":(\\d+)")
                .matcher(processor(found, "passages").payloadJson());
        int last = 0;
        while (ends.find()) {
            last = Math.max(last, Integer.parseInt(ends.group(1)));
        }
        assertThat(last).as("Absätze ohne Grenze").isGreaterThan(60);
    }

    @Test
    void anotherLimitRecomputesOnlyTheEmbeddings() {
        Asset asset = stored(Map.of("bericht.html", HTML));
        documents(20).indexed(list(), asset, extractions);
        Enrichment passages = processor(results.findDocuments(PAGE), "passages");

        documents(0).indexed(list(), asset, extractions);

        List<Enrichment> found = results.findDocuments(PAGE);
        assertThat(processor(found, "embedding").payloadJson()).doesNotContain("\"cut\"");
        assertThat(processor(found, "passages").computedAt()).isEqualTo(passages.computedAt());
        assertThat(processor(found, "passages").payloadJson()).isEqualTo(passages.payloadJson());
        int calls = embedded.size();
        documents(0).indexed(list(), asset, extractions);
        assertThat(embedded).as("danach aktuell").hasSize(calls);
    }

    @Test
    void aLaterApprovalReplacesPassagesByStructure() {
        released = false;
        Asset asset = stored(Map.of("bericht.html", HTML));
        documents(0).indexed(list(), asset, extractions);

        released = true;
        documents(0).indexed(list(), asset, extractions);

        List<Enrichment> found = results.findDocuments(PAGE);
        assertThat(found).extracting(Enrichment::processorId)
                .containsExactlyInAnyOrder("embedding", "passages", "navigation");
        assertThat(processor(found, "passages").payloadJson()).contains("\"mode\":\"semantic\"");
        assertThat(processor(found, "navigation").payloadJson()).doesNotContain("source-not-approved");
    }

    private static Enrichment processor(List<Enrichment> found, String id) {
        return found.stream().filter(result -> result.processorId().equals(id)).findFirst().orElseThrow();
    }

    private Asset stored(Map<String, String> files) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        files.forEach((name, content) -> entries.put(name, content.getBytes(StandardCharsets.UTF_8)));
        byte[] zip = Archives.zip(entries);
        Asset asset = new Asset("wiki", "101", "a1", "1", "teile.zip", Archives.ZIP, AssetKind.OTHER, zip.length,
                Assets.sha256(zip), NOW);
        store.put(asset, zip);
        extractions.text(asset, store, NOW);
        return asset;
    }

    private static PageAttachmentList list() {
        return new PageAttachmentList("wiki", "DEV", new PageAttachments("101", List.of(), List.of(),
                "application/json", "test", List.of(), NOW), NOW);
    }
}
