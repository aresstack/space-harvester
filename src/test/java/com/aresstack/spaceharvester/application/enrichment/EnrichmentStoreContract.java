package com.aresstack.spaceharvester.application.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static Enrichment ok(PageKey key, String processor, String version, String payload) {
        return new Enrichment(key, processor, version, "sha256:" + "0".repeat(64), "1", Enrichment.Status.OK,
                payload, List.of(), null, Instant.parse("2026-10-16T21:00:00Z"));
    }
}
