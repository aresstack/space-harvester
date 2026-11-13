package com.aresstack.spaceharvester.application.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.assets.PartId;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Vertrag jeder {@link EnrichmentStore}-Umsetzung; Umsetzungen erben und liefern eine leere Ablage. */
public abstract class EnrichmentStoreContract {

    private static final PageKey PAGE = new PageKey("wiki", "101");
    private static final PageKey OTHER = new PageKey("wiki", "102");

    protected abstract EnrichmentStore store();

    @Test
    void anUnknownPageHasNoResults() {
        assertThat(store().find(PAGE)).isEmpty();
    }

    @Test
    void resultsAreReturnedByProcessorIdAndReplacedPerProcessor() {
        EnrichmentStore store = store();
        store.save(ok(PAGE, "outline", "1", "{\"words\":1}"));
        store.save(ok(PAGE, "acronyms", "1", "{\"acronyms\":[]}"));
        Enrichment replaced = ok(PAGE, "outline", "2", "{\"words\":2}");
        store.save(replaced);

        assertThat(store.find(PAGE)).extracting(Enrichment::processorId).containsExactly("acronyms", "outline");
        assertThat(store.find(PAGE).get(1)).isEqualTo(replaced);
    }

    @Test
    void everyFieldSurvivesARoundTrip() {
        EnrichmentStore store = store();
        Enrichment failed = new Enrichment(PAGE, "acronyms", "1", "sha256:" + "a".repeat(64), null,
                Enrichment.Status.FAILED, "{}", List.of(), Enrichments.PROCESSOR_ERROR,
                Instant.parse("2026-10-16T21:00:00Z"));
        Enrichment ok = new Enrichment(PAGE, "outline", "3", "sha256:" + "b".repeat(64), "7",
                Enrichment.Status.OK, "{\"text\":\"Ä \\\" \\n\"}", List.of("eins", "zwei"), null,
                Instant.parse("2026-10-16T21:00:00.123456Z"));
        store.save(failed);
        store.save(ok);

        assertThat(store.find(PAGE)).containsExactly(failed, ok);
    }

    @Test
    void removingAPageLeavesOtherPagesAlone() {
        EnrichmentStore store = store();
        store.save(ok(PAGE, "outline", "1", "{}"));
        store.save(ok(OTHER, "outline", "1", "{}"));
        store.save(ok(new PageKey("other", "101"), "outline", "1", "{}"));

        store.removeAll(PAGE);

        assertThat(store.find(PAGE)).isEmpty();
        assertThat(store.find(OTHER)).hasSize(1);
        assertThat(store.find(new PageKey("other", "101"))).hasSize(1);
    }

    @Test
    void resultsOfDocumentsStayApartFromTheirPage() {
        // ADR 0026, Punkt 10: Ergebnisse hängen am Dokument; find(PageKey) bleibt bei der Seite selbst.
        EnrichmentStore store = store();
        DocumentKey attachment = DocumentKey.attachment(PAGE, "att/1 ~@#");
        DocumentKey part = attachment.child(new PartId.Content("ab".repeat(32), 1));
        Enrichment page = ok(PAGE, "passages", "1", "{\"page\":true}");
        Enrichment second = document(attachment, 1, "passages");
        Enrichment first = document(attachment, 0, "passages");
        Enrichment navigation = document(attachment, 0, "navigation");
        Enrichment inPart = document(part, 0, "passages");
        store.save(page);
        store.save(second);
        store.save(first);
        store.save(navigation);
        store.save(inPart);

        assertThat(store.find(PAGE)).containsExactly(page);
        assertThat(store.find(DocumentKey.page(PAGE))).containsExactly(page);
        assertThat(store.find(attachment)).containsExactly(navigation, first, second);
        assertThat(store.find(part)).singleElement().satisfies(result -> {
            assertThat(result).isEqualTo(inPart);
            assertThat(result.document().part()).isEqualTo(part.part());
            assertThat(result.document().attachmentId()).contains("att/1 ~@#");
        });
        // Ersetzt wird je Dokument, Abschnitt und Prozessor.
        Enrichment replaced = new Enrichment(PAGE, "passages", "2", "sha256:" + "e".repeat(64), null,
                Enrichment.Status.OK, "{\"neu\":true}", List.of(), null, Instant.parse("2026-11-13T13:00:00Z"),
                attachment, 1);
        store.save(replaced);
        assertThat(store.find(attachment)).containsExactly(navigation, first, replaced);
    }

    @Test
    void removingDocumentsRemovesExactlyWhatBelongsToThem() {
        EnrichmentStore store = store();
        DocumentKey attachment = DocumentKey.attachment(PAGE, "a1");
        DocumentKey part = attachment.child(new PartId.Content("ab".repeat(32), 1));
        DocumentKey other = DocumentKey.attachment(PAGE, "a2");
        Enrichment page = ok(PAGE, "passages", "1", "{}");
        store.save(page);
        store.save(document(attachment, 0, "passages"));
        store.save(document(part, 0, "passages"));
        store.save(document(other, 0, "passages"));
        store.save(document(other, 0, "embedding"));

        store.removeDocument(part);
        assertThat(store.find(part)).isEmpty();
        assertThat(store.find(attachment)).hasSize(1);

        store.save(document(part, 0, "passages"));
        store.removeDocument(attachment);
        assertThat(store.find(attachment)).as("der Anhang samt seinen Teilen").isEmpty();
        assertThat(store.find(part)).isEmpty();
        assertThat(store.find(other)).hasSize(2);
        assertThat(store.find(PAGE)).containsExactly(page);

        store.remove(PAGE, "embedding");
        assertThat(store.find(other)).extracting(Enrichment::processorId).containsExactly("passages");

        store.removeDocument(DocumentKey.page(PAGE));
        assertThat(store.find(PAGE)).isEmpty();
        assertThat(store.find(other)).as("nur die Seite selbst").hasSize(1);

        store.save(page);
        store.removeAll(PAGE);
        assertThat(store.find(PAGE)).isEmpty();
        assertThat(store.find(other)).as("die Seite samt ihren Dokumenten").isEmpty();
    }

    @Test
    void documentsOfAPageAreFoundTogetherAndSectionsRemovedOneByOne() {
        EnrichmentStore store = store();
        DocumentKey attachment = DocumentKey.attachment(PAGE, "a1");
        DocumentKey part = attachment.child(new PartId.Content("ab".repeat(32), 1));
        DocumentKey elsewhere = DocumentKey.attachment(OTHER, "a1");
        store.save(ok(PAGE, "outline", "1", "{}"));
        store.save(document(attachment, 0, "outline"));
        store.save(document(attachment, 1, "outline"));
        store.save(document(attachment, 1, "passages"));
        store.save(document(part, 0, "outline"));
        store.save(document(elsewhere, 0, "outline"));

        assertThat(store.findDocuments(PAGE)).as("ohne die Seite selbst und ohne andere Seiten")
                .extracting(Enrichment::document, Enrichment::section, Enrichment::processorId)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(attachment, 0, "outline"),
                        org.assertj.core.groups.Tuple.tuple(attachment, 1, "outline"),
                        org.assertj.core.groups.Tuple.tuple(attachment, 1, "passages"),
                        org.assertj.core.groups.Tuple.tuple(part, 0, "outline"));

        store.removeSection(attachment, 1);
        assertThat(store.find(attachment)).extracting(Enrichment::section).containsExactly(0);
        assertThat(store.find(part)).hasSize(1);
        assertThat(store.find(PAGE)).hasSize(1);
        assertThat(store.findDocuments(OTHER)).hasSize(1);
    }

    private static Enrichment document(DocumentKey document, int section, String processor) {
        return new Enrichment(document.page(), processor, "1", "sha256:" + "d".repeat(64), null, Enrichment.Status.OK,
                "{\"section\":" + section + "}", List.of(), null, Instant.parse("2026-11-13T12:00:00Z"), document,
                section);
    }

    private static Enrichment ok(PageKey key, String processor, String version, String payload) {
        return new Enrichment(key, processor, version, "sha256:" + "0".repeat(64), "1", Enrichment.Status.OK,
                payload, List.of(), null, Instant.parse("2026-10-16T21:00:00Z"));
    }
}
