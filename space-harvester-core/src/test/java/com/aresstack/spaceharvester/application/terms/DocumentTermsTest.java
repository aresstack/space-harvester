package com.aresstack.spaceharvester.application.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.assets.PartId;
import com.aresstack.spaceharvester.application.enrichment.DocumentTexts;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Begriffe und Akronyme je Anhang und enthaltener Datei (ADR 0026, AP9b), in beiden Ablagen: Seiten zählen wie vorher,
 * Dokumente nur auf Verlangen und getrennt, Ausschnitte nur gegen die vermerkte Extraktion, Rechte über die Seite,
 * Begriffe fallen mit Abschnitt, Teil und Anhang weg.
 */
class DocumentTermsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final Instant T0 = Instant.parse("2026-11-13T12:00:00Z");
    private static final PageTextProjector PLAIN = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));
    private static final PageKey PAGE = new PageKey("wiki", "1");
    private static final DocumentKey ATTACHMENT = DocumentKey.attachment(PAGE, "att-1");
    private static final DocumentKey PART = ATTACHMENT.child(new PartId.Content("a".repeat(64), 1));
    private static final String ATTACHMENT_TEXT = "Der RSL steht im Anhang. Der RSL zählt hier dreimal: RSL.";
    private static final String PART_TEXT = "Die Datei nennt den RSL einmal.";

    @TempDir
    Path directory;

    private final TermProcessor processor = new TermProcessor();
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final Set<PageKey> hidden = new HashSet<>();
    private final Map<String, PageText> texts = new HashMap<>();
    private EnrichmentStore store;

    @AfterEach
    void close() throws Exception {
        if (store instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private EnrichmentStore store(String kind) {
        store = "lucene".equals(kind) ? LuceneEnrichmentStore.open(directory.resolve("enrichments"))
                : new InMemoryEnrichmentStore();
        return store;
    }

    private TermQueries queries() {
        SourceCatalog catalog = new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source());
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return "wiki".equals(sourceId) ? Optional.of(source()) : Optional.empty();
            }
        };
        AccessPolicy policy = (context, scope, kind) -> kind == AccessKind.CONTENT && scope.pageId() != null
                && hidden.contains(new PageKey(scope.sourceId(), scope.pageId()))
                ? AccessDecision.DENY : AccessDecision.ALLOW;
        DocumentTexts documentTexts = (document, section, inputSha256, extraction) ->
                Optional.ofNullable(texts.get(document.value() + '|' + section + '|' + extraction))
                        .filter(text -> text.inputSha256().equals(inputSha256));
        return new TermQueries(catalog, (TermIndex) store, new AccessGuard(policy),
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)), PLAIN, processor.version(), documentTexts);
    }

    private static HarvestSource source() {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn("wiki");
        return source;
    }

    private void page(String text) {
        PageMetadata metadata = new PageMetadata("1", "confluence:wiki/page/1", "Seite 1", "DEV", null, "1", T0, null,
                PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of("wiki", metadata, new NativePayload(
                text.getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0);
        cache.put(snapshot.key(), snapshot);
        new Enrichments(PLAIN, List.of(processor), store, Clock.fixed(T0, ZoneOffset.UTC)).refresh(snapshot);
    }

    /** Ein Stand eines Dokumentabschnitts wie aus DocumentEnrichments, mit vermerkter Extraktion. */
    private void document(DocumentKey document, int section, String text, String extraction) {
        PageText input = new PageText("document-text/v1", text, List.of());
        texts.put(document.value() + '|' + section + '|' + extraction, input);
        store.save(new Enrichment(PAGE, TermPayload.PROCESSOR, processor.version(), input.inputSha256(),
                "sha256:doc", Enrichment.Status.OK, processor.process(input).payloadJson(), List.of(), null, T0,
                document, section, extraction));
    }

    private void filled(String kind) {
        store(kind);
        page("Der RSL steht auf der Seite.");
        document(ATTACHMENT, 0, ATTACHMENT_TEXT, "x1");
        document(PART, 0, PART_TEXT, "x1");
    }

    private TermQueries.TermSummary rsl(TermOrigin origin) {
        return queries().terms(CALLER, new TermQueries.TermSearch(null, null, TermKind.ACRONYM, "rsl", true, 50,
                null, null, origin)).terms().stream().filter(term -> term.normalized().equals("rsl")).findFirst()
                .orElseThrow();
    }

    private List<TermQueries.Occurrence> occurrences(TermOrigin origin, int snippetChars) {
        return queries().occurrences(CALLER, new TermQueries.OccurrenceSearch("RSL", TermKind.ACRONYM, null, null,
                50, null, snippetChars, origin)).occurrences();
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "lucene"})
    void pagesCountAsBeforeAndDocumentsOnlyOnRequestAndSeparately(String kind) {
        filled(kind);

        TermQueries.TermSummary pages = rsl(TermOrigin.PAGES);
        assertThat(pages.occurrences()).isEqualTo(1);
        assertThat(pages.pages()).isEqualTo(1);
        assertThat(pages.documentOccurrences()).isZero();
        assertThat(pages.documents()).isZero();
        assertThat(occurrences(TermOrigin.PAGES, 0)).singleElement()
                .satisfies(found -> assertThat(found.attachmentId()).isNull());

        TermQueries.TermSummary all = rsl(TermOrigin.ALL);
        assertThat(all.occurrences()).as("die Zahl der Seiten bleibt").isEqualTo(1);
        assertThat(all.pages()).isEqualTo(1);
        assertThat(all.documentOccurrences()).isEqualTo(4);
        assertThat(all.documents()).isEqualTo(2);

        TermQueries.TermSummary documents = rsl(TermOrigin.DOCUMENTS);
        assertThat(documents.pages()).isZero();
        assertThat(documents.documentOccurrences()).isEqualTo(4);
        assertThat(documents.term()).isEqualTo("RSL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "lucene"})
    void documentOccurrencesNameAttachmentPartAndSectionInSeparateFields(String kind) {
        filled(kind);

        List<TermQueries.Occurrence> found = occurrences(TermOrigin.DOCUMENTS, 0);

        assertThat(found).hasSize(4).allSatisfy(occurrence -> {
            assertThat(occurrence.pageId()).isEqualTo("1");
            assertThat(occurrence.attachmentId()).isEqualTo("att-1");
            assertThat(occurrence.documentSection()).isZero();
        });
        assertThat(found).filteredOn(occurrence -> occurrence.partId() != null).singleElement()
                .satisfies(occurrence -> assertThat(occurrence.partId()).isEqualTo(PART.part().orElseThrow().value()));
        assertThat(found).filteredOn(occurrence -> occurrence.partId() == null).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "lucene"})
    void occurrencesPageThroughDocumentsWithTheCursor(String kind) {
        filled(kind);
        TermQueries terms = queries();

        TermQueries.OccurrenceList first = terms.occurrences(CALLER, new TermQueries.OccurrenceSearch("RSL",
                TermKind.ACRONYM, null, null, 2, null, 0, TermOrigin.ALL));
        TermQueries.OccurrenceList second = terms.occurrences(CALLER, new TermQueries.OccurrenceSearch("RSL",
                TermKind.ACRONYM, null, null, 2, first.nextCursor(), 0, TermOrigin.ALL));
        TermQueries.OccurrenceList third = terms.occurrences(CALLER, new TermQueries.OccurrenceSearch("RSL",
                TermKind.ACRONYM, null, null, 2, second.nextCursor(), 0, TermOrigin.ALL));

        assertThat(first.occurrences().size() + second.occurrences().size() + third.occurrences().size())
                .isEqualTo(5);
        assertThat(third.nextCursor()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "lucene"})
    void snippetsComeOnlyFromTheRecordedExtraction(String kind) {
        filled(kind);

        assertThat(occurrences(TermOrigin.DOCUMENTS, 20)).allSatisfy(occurrence -> {
            assertThat(occurrence.snippet()).isNotNull();
            assertThat(occurrence.snippet().match()).isEqualTo("RSL");
        });

        // Ein neueres Lesen unter derselben Kennung: der vermerkte Text ist nicht mehr da, also kein Ausschnitt.
        texts.clear();
        assertThat(occurrences(TermOrigin.DOCUMENTS, 20)).allSatisfy(occurrence ->
                assertThat(occurrence.snippet()).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "lucene"})
    void aRunThatIsNotThroughYetCountsNothing(String kind) {
        store(kind);
        page("Der RSL steht auf der Seite.");
        document(ATTACHMENT, 0, ATTACHMENT_TEXT, null);

        assertThat(rsl(TermOrigin.ALL).documents()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "lucene"})
    void documentsHaveTheRightsOfTheirPage(String kind) {
        filled(kind);
        hidden.add(PAGE);

        assertThat(queries().terms(CALLER, new TermQueries.TermSearch(null, null, TermKind.ACRONYM, "rsl", true, 50,
                null, null, TermOrigin.ALL)).terms()).isEmpty();
        assertThat(occurrences(TermOrigin.DOCUMENTS, 20)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "lucene"})
    void termsGoWithTheirSectionPartAttachmentAndProcessor(String kind) {
        filled(kind);
        document(ATTACHMENT, 1, "Zweiter Abschnitt mit RSL.", "x1");
        assertThat(rsl(TermOrigin.DOCUMENTS).documentOccurrences()).isEqualTo(5);

        store.removeSection(ATTACHMENT, 1);
        assertThat(rsl(TermOrigin.DOCUMENTS).documentOccurrences()).isEqualTo(4);

        store.removeDocument(PART);
        assertThat(rsl(TermOrigin.DOCUMENTS).documentOccurrences()).isEqualTo(3);

        document(PART, 0, PART_TEXT, "x1");
        store.removeDocument(ATTACHMENT);
        assertThat(queries().terms(CALLER, new TermQueries.TermSearch(null, null, TermKind.ACRONYM, "rsl", true, 50,
                null, null, TermOrigin.DOCUMENTS)).terms()).as("der Anhang samt Teilen").isEmpty();

        document(ATTACHMENT, 0, ATTACHMENT_TEXT, "x1");
        store.remove(PAGE, TermPayload.PROCESSOR);
        assertThat(queries().terms(CALLER, new TermQueries.TermSearch(null, null, TermKind.ACRONYM, "rsl", true, 50,
                null, null, TermOrigin.ALL)).terms()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "lucene"})
    void documentTermsAreNotMistakenForResults(String kind) {
        filled(kind);

        assertThat(store.find(ATTACHMENT)).extracting(Enrichment::processorId).containsExactly("terms");
        assertThat(store.find(PART)).extracting(Enrichment::processorId).containsExactly("terms");
    }
}
