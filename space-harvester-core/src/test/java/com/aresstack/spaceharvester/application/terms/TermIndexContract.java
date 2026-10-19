package com.aresstack.spaceharvester.application.terms;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Vertrag des Begriffsindex für jede Ablage, die ihn mit {@link EnrichmentStore} verbindet. */
public abstract class TermIndexContract {

    protected static final PageKey A1 = new PageKey("wiki-a", "1");
    protected static final PageKey B1 = new PageKey("wiki-b", "1");
    protected static final Set<String> BOTH = Set.of("wiki-a", "wiki-b");

    protected abstract EnrichmentStore store();

    protected abstract TermIndex index();

    protected static Enrichment terms(PageKey key, String revision, String payload) {
        return new Enrichment(key, TermPayload.PROCESSOR, "1", "sha256:" + revision, revision, Enrichment.Status.OK,
                payload, List.of(), null, Instant.parse("2026-10-19T00:00:00Z"));
    }

    protected static String payload(String... entries) {
        return "{\"terms\":[" + String.join(",", entries) + "]}";
    }

    protected static String term(String term, String kind, String expansion, int... occurrences) {
        StringBuilder flat = new StringBuilder();
        for (int i = 0; i < occurrences.length; i++) {
            flat.append(i == 0 ? "" : ",").append(occurrences[i]);
        }
        return "{\"term\":\"" + term + "\",\"normalized\":\"" + TermText.normalize(term) + "\",\"kind\":\"" + kind
                + "\",\"count\":" + occurrences.length / 2 + ",\"expansion\":"
                + (expansion == null ? "null" : "\"" + expansion + "\"") + ",\"section\":null,\"occurrences\":["
                + flat + "]}";
    }

    private List<TermPosting> all() {
        return index().postings(new TermIndex.Filter(BOTH, null, null, null, "1"), null, false, TermIndex.MAX_BATCH);
    }

    @Test
    void savedTermsBecomePostingsWithAllOccurrences() {
        store().save(terms(A1, "3", payload(term("Beleg", "noun", null, 4, 9, 30, 35))));

        assertThat(all()).singleElement().satisfies(posting -> {
            assertThat(posting.page()).isEqualTo(A1);
            assertThat(posting.kind()).isEqualTo(TermKind.NOUN);
            assertThat(posting.occurrences()).containsExactly(new TermOccurrence(4, 9), new TermOccurrence(30, 35));
            assertThat(posting.revision()).isEqualTo("3");
            assertThat(posting.inputSha256()).isEqualTo("sha256:3");
        });
    }

    @Test
    void samePageIdInTwoSourcesStaysApart() {
        store().save(terms(A1, "1", payload(term("RSL", "acronym", "Rückmeldeschlüssel", 0, 3))));
        store().save(terms(B1, "1", payload(term("RSL", "acronym", "Ressourcenstatusliste", 0, 3))));

        assertThat(all()).extracting(TermPosting::page).containsExactly(A1, B1);
        assertThat(all()).extracting(TermPosting::expansion)
                .containsExactly("Rückmeldeschlüssel", "Ressourcenstatusliste");
    }

    @Test
    void recomputingReplacesAndFailingRemovesThePostings() {
        store().save(terms(A1, "1", payload(term("Alt", "noun", null, 0, 3))));
        store().save(terms(A1, "2", payload(term("Neu", "noun", null, 0, 3))));

        assertThat(all()).extracting(TermPosting::normalized).containsExactly("neu");

        store().save(new Enrichment(A1, TermPayload.PROCESSOR, "1", "sha256:3", "3", Enrichment.Status.FAILED, "{}",
                List.of(), "PROCESSOR_ERROR", Instant.parse("2026-10-19T00:00:00Z")));

        assertThat(all()).isEmpty();
    }

    @Test
    void removingThePageRemovesItsPostings() {
        store().save(terms(A1, "1", payload(term("Beleg", "noun", null, 0, 5))));
        store().save(terms(B1, "1", payload(term("Beleg", "noun", null, 0, 5))));

        store().removeAll(A1);

        assertThat(all()).extracting(TermPosting::page).containsExactly(B1);
    }

    @Test
    void termPostingsAreNotResults() {
        store().save(terms(A1, "1", payload(term("Beleg", "noun", null, 0, 5), term("API", "acronym", null, 6,
                9))));

        assertThat(store().find(A1)).extracting(Enrichment::processorId).containsExactly(TermPayload.PROCESSOR);
    }

    @Test
    void filtersBySourceKindExactValuePrefixOfAnyWordAndVersion() {
        store().save(terms(A1, "1", payload(term("Rückmelde-Schlüssel", "noun", null, 0, 19),
                term("RSL", "acronym", null, 20, 23), term("Beleg", "noun", null, 24, 29))));
        store().save(terms(B1, "1", payload(term("Beleg", "noun", null, 0, 5))));

        assertThat(index().postings(new TermIndex.Filter(Set.of("wiki-b"), null, null, null, "1"), null, false, 10))
                .extracting(TermPosting::page).containsExactly(B1);
        assertThat(index().postings(new TermIndex.Filter(BOTH, TermKind.ACRONYM, null, null, "1"), null, false, 10))
                .extracting(TermPosting::term).containsExactly("RSL");
        assertThat(index().postings(new TermIndex.Filter(BOTH, null, "beleg", null, "1"), null, false, 10))
                .hasSize(2);
        assertThat(index().postings(new TermIndex.Filter(BOTH, null, null, "schl", "1"), null, false, 10))
                .extracting(TermPosting::term).containsExactly("Rückmelde-Schlüssel");
        assertThat(index().postings(new TermIndex.Filter(BOTH, null, null, "rück", "1"), null, false, 10))
                .extracting(TermPosting::term).containsExactly("Rückmelde-Schlüssel");
        assertThat(index().postings(new TermIndex.Filter(BOTH, null, null, null, "2"), null, false, 10)).isEmpty();
        assertThat(index().postings(new TermIndex.Filter(Set.of(), null, null, null, "1"), null, false, 10))
                .isEmpty();
    }

    @Test
    void readsInKeyOrderAndContinuesAfterAKey() {
        store().save(terms(B1, "1", payload(term("Beleg", "noun", null, 0, 5), term("API", "acronym", null, 6, 9))));
        store().save(terms(A1, "1", payload(term("Beleg", "noun", null, 0, 5), term("Belege", "noun", null, 6,
                12))));

        List<TermPosting> all = all();
        assertThat(all).extracting(posting -> posting.normalized() + "/" + posting.page().sourceId())
                .containsExactly("api/wiki-b", "beleg/wiki-a", "beleg/wiki-b", "belege/wiki-a");

        assertThat(index().postings(new TermIndex.Filter(BOTH, null, null, null, "1"), all.get(1).sortKey(), false,
                10)).extracting(TermPosting::sortKey).containsExactly(all.get(2).sortKey(), all.get(3).sortKey());
        assertThat(index().postings(new TermIndex.Filter(BOTH, null, null, null, "1"), all.get(1).sortKey(), true,
                1)).extracting(TermPosting::sortKey).containsExactly(all.get(1).sortKey());
        assertThat(index().postings(new TermIndex.Filter(BOTH, null, null, null, "1"),
                TermIndex.afterGroup(all.get(1).groupKey()), false, 10))
                .extracting(TermPosting::normalized).containsExactly("belege");
    }
}
