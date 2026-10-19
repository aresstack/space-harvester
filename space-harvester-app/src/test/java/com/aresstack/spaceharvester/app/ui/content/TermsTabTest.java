package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.PageAcronyms;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.terms.PageTerms;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermOccurrence;
import com.aresstack.spaceharvester.application.terms.TermPayload;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Reiter „Begriffe“ der Leseansicht (#339) mit den Akronymen der Core-Analyse. */
class TermsTabTest {

    private static final Enrichment RESULT = new Enrichment(new PageKey("wiki", "100"), PageAcronyms.PROCESSOR, "1",
            "sha", "3", Enrichment.Status.OK, "{}", List.of(), null, Instant.parse("2026-10-18T20:00:00Z"));

    @Test
    void theTabCountsTheAcronymsOfTheCurrentAnalysisAndJumpsOnClick() throws Exception {
        PageAcronyms.Acronym rsl = new PageAcronyms.Acronym("RSL", 2, "Rückmeldeschlüssel", "Einleitung",
                List.of(new PageAcronyms.Occurrence(4, 7), new PageAcronyms.Occurrence(20, 23)));
        PageAcronyms.Acronym iar = new PageAcronyms.Acronym("IAR", 1, null, null,
                List.of(new PageAcronyms.Occurrence(30, 33)));
        List<String> seen = new ArrayList<>();
        List<List<TermsTab.Span>> jumped = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermsTab terms = new TermsTab();
            terms.onJump(jumped::add);
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
        assertThat(jumped).containsExactly(List.of(new TermsTab.Span(4, 7), new TermsTab.Span(20, 23)));
    }

    @Test
    void aStaleAnalysisCountsNothingAndSaysHowToRecompute() throws Exception {
        List<String> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermsTab terms = new TermsTab();
            terms.show(new PageAcronyms(PageAcronyms.State.STALE, RESULT, List.of(), List.of()));
            seen.add(terms.stateLabel());
            seen.add(terms.counts().get(0));
            seen.add(terms.hint());
            seen.add(String.valueOf(terms.analysis().startsWith("Analyse vom letzten Seitenabgleich · ")));
        });
        assertThat(seen.get(0)).isEqualTo("Veraltet");
        assertThat(seen.get(1)).isEqualTo(TermsTab.NOT_YET);
        assertThat(seen.get(2)).contains("Analyse neu berechnen");
        assertThat(seen.get(3)).isEqualTo("true");
    }

    @Test
    void identifiersOfTheCurrentTermAnalysisAreCountedAndListedAfterTheAcronyms() throws Exception {
        PageAcronyms.Acronym rsl = new PageAcronyms.Acronym("RSL", 1, "Rückmeldeschlüssel", null,
                List.of(new PageAcronyms.Occurrence(4, 7)));
        PageTerms terms = new PageTerms(PageTerms.State.CURRENT, RESULT, List.of(
                new TermPayload.Entry("RSL", "rsl", TermKind.ACRONYM, 1, "Rückmeldeschlüssel", null,
                        List.of(new TermOccurrence(4, 7))),
                new TermPayload.Entry("Sachbearbeitung", "sachbearbeitung", TermKind.NOUN, 2, null, "Einleitung",
                        List.of(new TermOccurrence(10, 25), new TermOccurrence(40, 55))),
                new TermPayload.Entry("ISO27001", "iso27001", TermKind.IDENTIFIER, 1, null, null,
                        List.of(new TermOccurrence(60, 68)))), List.of());
        List<String> seen = new ArrayList<>();
        List<List<TermsTab.Span>> jumped = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermsTab tab = new TermsTab();
            tab.onJump(jumped::add);
            tab.show(new PageAcronyms(PageAcronyms.State.CURRENT, RESULT, List.of(rsl), List.of()), terms);
            seen.add(String.join(",", tab.counts()));
            tab.identifierChips().forEach(chip -> seen.add(chip.getText()));
            seen.add(tab.identifierChips().get(0).getToolTipText());
            tab.identifierChips().get(0).doClick();
            tab.filter().setText("iso");
            seen.add(tab.chips().size() + "/" + tab.identifierChips().size());
        });
        assertThat(seen).containsExactly("1,2,–", "Sachbearbeitung", "ISO27001",
                "Nomen · 2 Fundstellen · zuerst in „Einleitung“ · Klick springt zur nächsten Fundstelle im Lesetext",
                "0/1");
        assertThat(jumped).containsExactly(List.of(new TermsTab.Span(10, 25), new TermsTab.Span(40, 55)));
    }
}
