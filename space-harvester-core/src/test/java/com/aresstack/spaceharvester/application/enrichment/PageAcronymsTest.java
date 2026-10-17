package com.aresstack.spaceharvester.application.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Lesen des Akronym-Ergebnisses für den angezeigten Text (#115): nur aktuelle Ergebnisse dieser Seite. */
class PageAcronymsTest {

    static final PageKey KEY = new PageKey("wiki", "101");

    private static final String TEXT = "Die Application Programming Interface (API) nutzt REST.\nDie API und SSO "
            + "(Single Sign-On).";
    private final AcronymProcessor processor = new AcronymProcessor();

    @Test
    void readsKnownAndUnknownExpansionsWithEveryOccurrence() {
        PageText text = text(TEXT);

        PageAcronyms acronyms = PageAcronyms.read(KEY, List.of(current(text, processor)), text);

        assertThat(acronyms.state()).isEqualTo(PageAcronyms.State.CURRENT);
        assertThat(acronyms.acronyms()).extracting(PageAcronyms.Acronym::acronym, PageAcronyms.Acronym::count,
                PageAcronyms.Acronym::expansion).containsExactly(
                org.assertj.core.groups.Tuple.tuple("API", 2, "Application Programming Interface"),
                org.assertj.core.groups.Tuple.tuple("REST", 1, null),
                org.assertj.core.groups.Tuple.tuple("SSO", 1, "Single Sign-On"));
        assertThat(acronyms.marks()).extracting(mark -> TEXT.substring(mark.occurrence().start(),
                mark.occurrence().end())).containsExactly("API", "REST", "API", "SSO");
        assertThat(acronyms.marks().get(0).occurrence().start()).isEqualTo(TEXT.indexOf("API"));
        assertThat(acronyms.marks().get(2).occurrence().start()).isEqualTo(TEXT.lastIndexOf("API"));
    }

    @Test
    void neverProjectsAResultOfAnotherTextOntoTheShownText() {
        PageText old = text("Die API (Application Programming Interface) ist alt.");
        PageText changed = text("Neu: erst REST, dann die API.");
        Enrichments.View view = current(old, processor);

        PageAcronyms acronyms = PageAcronyms.read(KEY, List.of(view), changed);

        assertThat(acronyms.state()).isEqualTo(PageAcronyms.State.STALE);
        assertThat(acronyms.acronyms()).isEmpty();
        assertThat(acronyms.marks()).isEmpty();
    }

    @Test
    void staleFailedAndMissingResultsCarryNoMarks() {
        PageText text = text(TEXT);
        Enrichment ok = current(text, processor).enrichment();
        Enrichment failed = new Enrichment(KEY, "acronyms", "2", text.inputSha256(), "1", Enrichment.Status.FAILED,
                "{}", List.of(), Enrichments.PROCESSOR_ERROR, Instant.EPOCH);

        assertThat(PageAcronyms.read(KEY, List.of(new Enrichments.View(ok, Enrichment.Freshness.STALE)), text).state())
                .isEqualTo(PageAcronyms.State.STALE);
        assertThat(PageAcronyms.read(KEY, List.of(new Enrichments.View(failed, Enrichment.Freshness.CURRENT)), text)
                .state()).isEqualTo(PageAcronyms.State.FAILED);
        assertThat(PageAcronyms.read(KEY, List.of(), text).state()).isEqualTo(PageAcronyms.State.MISSING);
        assertThat(PageAcronyms.read(KEY, List.of(current(text, processor)), null).state())
                .isEqualTo(PageAcronyms.State.MISSING);
    }

    @Test
    void aVersionOneResultWithoutOccurrencesIsStaleAfterTheUpgrade() {
        PageText text = text(TEXT);
        Enrichment v1 = new Enrichment(KEY, "acronyms", "1", text.inputSha256(), "1", Enrichment.Status.OK,
                "{\"acronyms\":[{\"acronym\":\"API\",\"count\":2,\"expansion\":null,\"section\":null,\"charStart\":39}]}",
                List.of(), null, Instant.EPOCH);
        Enrichments.View view = new Enrichments.View(v1, v1.freshness(text.inputSha256(), processor.version()));

        assertThat(PageAcronyms.read(KEY, List.of(view), text).state()).isEqualTo(PageAcronyms.State.STALE);
    }

    @Test
    void usesOnlyTheResultOfTheShownSourceAndPage() {
        PageText text = text(TEXT);
        Enrichment other = current(text, processor).enrichment();
        Enrichment foreign = new Enrichment(new PageKey("zweites-wiki", "101"), other.processorId(),
                other.processorVersion(), other.inputSha256(), other.revision(), other.status(), other.payloadJson(),
                other.warnings(), null, other.computedAt());

        PageAcronyms acronyms = PageAcronyms.read(KEY,
                List.of(new Enrichments.View(foreign, Enrichment.Freshness.CURRENT)), text);

        assertThat(acronyms.state()).isEqualTo(PageAcronyms.State.MISSING);
    }

    @Test
    void ignoresOccurrencesThatDoNotPointAtTheAcronym() {
        PageText text = text("Die API.");
        Enrichment broken = new Enrichment(KEY, "acronyms", "2", text.inputSha256(), "1", Enrichment.Status.OK,
                "{\"acronyms\":[{\"acronym\":\"API\",\"count\":2,\"expansion\":null,\"section\":null,\"charStart\":4,"
                        + "\"occurrences\":[{\"start\":4,\"end\":7},{\"start\":0,\"end\":3},{\"start\":6,\"end\":99}]}]}",
                List.of(), null, Instant.EPOCH);

        PageAcronyms acronyms = PageAcronyms.read(KEY,
                List.of(new Enrichments.View(broken, Enrichment.Freshness.CURRENT)), text);

        assertThat(acronyms.marks()).extracting(mark -> mark.occurrence().start()).containsExactly(4);
    }

    /** Ein frisch berechnetes, aktuelles Ergebnis wie nach einem Abgleich. */
    static Enrichments.View current(PageText text, AcronymProcessor processor) {
        EnrichmentOutput output = processor.process(text);
        Enrichment enrichment = new Enrichment(KEY, processor.id(), processor.version(), text.inputSha256(), "1",
                Enrichment.Status.OK, output.payloadJson(), output.warnings(), null, Instant.EPOCH);
        return new Enrichments.View(enrichment, Enrichment.Freshness.CURRENT);
    }

    private static PageText text(String text) {
        return new PageText("t/v1", text, List.of());
    }
}
