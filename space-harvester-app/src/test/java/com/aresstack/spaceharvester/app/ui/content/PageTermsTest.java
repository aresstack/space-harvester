package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.PageAcronyms;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Reiter „Begriffe“ der Leseansicht (#339) mit den Akronymen der Core-Analyse. */
class PageTermsTest {

    private static final Enrichment RESULT = new Enrichment(new PageKey("wiki", "100"), PageAcronyms.PROCESSOR, "1",
            "sha", "3", Enrichment.Status.OK, "{}", List.of(), null, Instant.parse("2026-10-18T20:00:00Z"));

    @Test
    void theTabCountsTheAcronymsOfTheCurrentAnalysisAndJumpsOnClick() throws Exception {
        PageAcronyms.Acronym rsl = new PageAcronyms.Acronym("RSL", 2, "Rückmeldeschlüssel", "Einleitung",
                List.of(new PageAcronyms.Occurrence(4, 7), new PageAcronyms.Occurrence(20, 23)));
        PageAcronyms.Acronym iar = new PageAcronyms.Acronym("IAR", 1, null, null,
                List.of(new PageAcronyms.Occurrence(30, 33)));
        List<String> seen = new ArrayList<>();
        List<String> jumped = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            PageTerms terms = new PageTerms();
            terms.onJump(acronym -> jumped.add(acronym.acronym()));
            assertThat(terms.stateLabel()).isEqualTo("Nicht durchgeführt");
            terms.show(new PageAcronyms(PageAcronyms.State.CURRENT, RESULT, List.of(rsl, iar), List.of()));
            seen.add(terms.stateLabel());
            seen.add(String.join(",", terms.counts()));
            terms.chips().forEach(chip -> seen.add(chip.getText()));
            terms.chips().get(0).doClick();
            terms.filter().setText("rück");
            seen.add(String.valueOf(terms.chips().size()));
            terms.filter().setText("zzz");
            seen.add(terms.hint());
        });
        assertThat(seen).containsExactly("Aktuell", "2,–,–", "RSL · Rückmeldeschlüssel", "IAR", "1",
                "Kein Begriff passt zum Filter.");
        assertThat(jumped).containsExactly("RSL");
    }

    @Test
    void aStaleAnalysisCountsNothingAndSaysHowToRecompute() throws Exception {
        List<String> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            PageTerms terms = new PageTerms();
            terms.show(new PageAcronyms(PageAcronyms.State.STALE, RESULT, List.of(), List.of()));
            seen.add(terms.stateLabel());
            seen.add(terms.counts().get(0));
            seen.add(terms.hint());
            seen.add(String.valueOf(terms.analysis().startsWith("Analyse vom letzten Seitenabgleich · ")));
        });
        assertThat(seen.get(0)).isEqualTo("Veraltet");
        assertThat(seen.get(1)).isEqualTo(PageTerms.NOT_YET);
        assertThat(seen.get(2)).contains("Analyse neu berechnen");
        assertThat(seen.get(3)).isEqualTo("true");
    }
}
