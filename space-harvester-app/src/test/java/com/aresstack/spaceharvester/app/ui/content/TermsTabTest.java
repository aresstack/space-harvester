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
    void aSelectionShowsTheSameViewSummedUpOverItsPagesAndAsksTheCoreWhenFiltering() throws Exception {
        List<String> queries = new ArrayList<>();
        List<com.aresstack.spaceharvester.application.terms.TermQueries.Occurrence> opened = new ArrayList<>();
        TermsTab.Totals[] known = new TermsTab.Totals[1];
        TermsTab.Selection selection = new TermsTab.Selection() {
            @Override
            public String title() {
                return "Handbuch";
            }

            @Override
            public com.aresstack.spaceharvester.app.ui.Pager<
                    com.aresstack.spaceharvester.application.terms.TermQueries.TermSummary> acronyms(String q) {
                queries.add("acronyms:" + q);
                return pager(q.isEmpty() ? List.of(summary("RSL", TermKind.ACRONYM, "Rückmeldeschlüssel"))
                        : List.of());
            }

            @Override
            public com.aresstack.spaceharvester.app.ui.Pager<
                    com.aresstack.spaceharvester.application.terms.TermQueries.TermSummary> identifiers(String q) {
                return pager(q.isEmpty() ? List.of(summary("Antrag", TermKind.NOUN, null),
                        summary("ISO27001", TermKind.IDENTIFIER, null)) : List.of());
            }

            @Override
            public com.aresstack.spaceharvester.app.ui.Pager<com.aresstack.spaceharvester.app.ui.Relation> relations(
                    String q) {
                return pager(q.isEmpty() ? List.of(new com.aresstack.spaceharvester.app.ui.Relation("r1", "RSL",
                        "ABBREVIATION", "Rückmeldeschlüssel", "PROPOSED", "RULE", 1)) : List.of());
            }

            @Override
            public com.aresstack.spaceharvester.application.terms.TermQueries.OccurrenceList occurrences(
                    com.aresstack.spaceharvester.application.terms.TermQueries.TermSummary term) {
                return new com.aresstack.spaceharvester.application.terms.TermQueries.OccurrenceList(term.normalized(),
                        List.of(occurrence("1", "Seite 1"), occurrence("1", "Seite 1"), occurrence("2", "Seite 2")),
                        null);
            }

            @Override
            public TermsTab.Totals totals() {
                return known[0];
            }
        };
        TermsTab[] tab = new TermsTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new TermsTab();
            tab[0].runner(new TermsTab.Runner() {
                @Override
                public <T> void run(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> onSuccess,
                                    java.util.function.Consumer<Throwable> onFailure) {
                    try {
                        onSuccess.accept(work.call());
                    } catch (Exception e) {
                        onFailure.accept(e);
                    }
                }
            });
            tab[0].onOpen(opened::add);
            tab[0].selection(selection);
        });
        flush();
        SwingUtilities.invokeAndWait(() -> {
            TermsTab terms = tab[0];
            assertThat(terms.title()).isEqualTo("Begriffe in Handbuch");
            assertThat(terms.analysisShown()).as("Seitenabgleich und Plaketten nur auf der Seite").isFalse();
            assertThat(terms.stateLabel()).isBlank();
            assertThat(terms.counts()).as("echte Gesamtzahlen folgen vom Core").containsExactly("…", "…", "…");
            assertThat(terms.chips()).extracting(chip -> chip.getText()).containsExactly("RSL · Rückmeldeschlüssel");
            assertThat(terms.identifierChips()).extracting(chip -> chip.getText()).containsExactly("Antrag",
                    "ISO27001");
            assertThat(terms.relationLines()).containsExactly("RSL · Abkürzung für · Rückmeldeschlüssel "
                    + "(Vorgeschlagen, Regel)");
            assertThat(terms.chips().get(0).getToolTipText()).isEqualTo("Rückmeldeschlüssel · 3 Fundstellen auf 2 "
                    + "Seiten · Klick zeigt die Seiten");

            // Klick: die Seiten mit Fundstellen als Overlay, ein Klick öffnet die Seite.
            terms.identifierChips().get(0).doClick();
            assertThat(terms.occurrencePopup().titleText()).isEqualTo("Fundstellen von „Antrag“");
            assertThat(terms.occurrencePopup().linkTexts()).containsExactly("Seite 1 · 2 Fundstellen",
                    "Seite 2 · 1 Fundstelle");
            terms.occurrencePopup().clickLink(1);
            assertThat(opened).extracting(hit -> hit.pageId()).containsExactly("2");

            // Der Filter fragt den Core neu.
            terms.filter().setText("xyz");
        });
        flush();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(queries).containsExactly("acronyms:", "acronyms:xyz");
            assertThat(tab[0].chips()).isEmpty();
            assertThat(tab[0].hint()).isEqualTo("Kein Begriff passt zum Filter.");
            // Die Gesamtzahlen kommen getrennt im Hintergrund vom Core (BG1b), nie die Zahl der geladenen Chips.
            List<String> status = new ArrayList<>();
            tab[0].onStatus(status::add);
            known[0] = new TermsTab.Totals(4, 120, 7, 2, 3);
            tab[0].selection(selection);
            assertThat(tab[0].counts()).containsExactly("4", "120", "7");
            assertThat(status).last().isEqualTo("Begriffe aus 2 von 3 Seiten · übrige nicht lokal gespeichert");
            // Der Bestand ändert sich (Beobachter): nur neu zählen, die Listen bleiben.
            known[0] = new TermsTab.Totals(5, 121, 7, 3, 3);
            tab[0].recount();
            assertThat(tab[0].counts()).containsExactly("5", "121", "7");
            assertThat(status).last().isEqualTo("Begriffe aus 3 Seiten");
            // Zurück auf eine Seite: wieder die Darstellung der Seite.
            tab[0].show(null);
            assertThat(tab[0].title()).isEqualTo("Begriffe dieser Seite");
            assertThat(tab[0].analysisShown()).isTrue();
        });
    }

    /**
     * Fahrplan P2: „mit Unterseiten“ zeigt unter den eigenen Begriffen abgetrennt die der Unterseiten; ein Begriff, der
     * oben schon steht, bekommt dort „+N“ und steht unten nicht noch einmal. Abgehakt verschwindet der Abschnitt, die
     * eigenen Begriffe bleiben stehen.
     */
    @Test
    void withSubpagesTheTermsOfTheSubpagesFollowSeparatelyAndDuplicatesStayOnTop() throws Exception {
        PageAcronyms.Acronym rsl = new PageAcronyms.Acronym("RSL", 1, "Rückmeldeschlüssel", null,
                List.of(new PageAcronyms.Occurrence(0, 3)));
        TermsTab.Selection subpageTerms = new TermsTab.Selection() {
            @Override
            public String title() {
                return "den Unterseiten";
            }

            @Override
            public com.aresstack.spaceharvester.app.ui.Pager<
                    com.aresstack.spaceharvester.application.terms.TermQueries.TermSummary> acronyms(String q) {
                return pager(List.of(summary("RSL", TermKind.ACRONYM, "Rückmeldeschlüssel"),
                        summary("ZV", TermKind.ACRONYM, "Zahlungsverkehr")));
            }

            @Override
            public com.aresstack.spaceharvester.app.ui.Pager<
                    com.aresstack.spaceharvester.application.terms.TermQueries.TermSummary> identifiers(String q) {
                return pager(List.of(summary("Antrag", TermKind.NOUN, null)));
            }

            @Override
            public com.aresstack.spaceharvester.app.ui.Pager<com.aresstack.spaceharvester.app.ui.Relation> relations(
                    String q) {
                return pager(List.of());
            }

            @Override
            public com.aresstack.spaceharvester.application.terms.TermQueries.OccurrenceList occurrences(
                    com.aresstack.spaceharvester.application.terms.TermQueries.TermSummary term) {
                return new com.aresstack.spaceharvester.application.terms.TermQueries.OccurrenceList(term.normalized(),
                        List.of(occurrence("7", "Unterseite A")), null);
            }
        };
        TermsTab.Below below = new TermsTab.Below() {
            @Override
            public TermsTab.Selection terms() {
                return subpageTerms;
            }

            @Override
            public java.util.Map<com.aresstack.spaceharvester.application.terms.TermQueries.TermRef, Integer>
                    pageCounts() {
                return java.util.Map.of(
                        new com.aresstack.spaceharvester.application.terms.TermQueries.TermRef("rsl",
                                TermKind.ACRONYM), 2,
                        new com.aresstack.spaceharvester.application.terms.TermQueries.TermRef("zv",
                                TermKind.ACRONYM), 1,
                        new com.aresstack.spaceharvester.application.terms.TermQueries.TermRef("antrag",
                                TermKind.NOUN), 1);
            }

            @Override
            public int pages() {
                return 2;
            }
        };
        List<com.aresstack.spaceharvester.application.terms.TermQueries.Occurrence> opened = new ArrayList<>();
        TermsTab[] tab = new TermsTab[1];
        SwingUtilities.invokeAndWait(() -> {
            tab[0] = new TermsTab();
            tab[0].runner(new TermsTab.Runner() {
                @Override
                public <T> void run(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> onSuccess,
                                    java.util.function.Consumer<Throwable> onFailure) {
                    try {
                        onSuccess.accept(work.call());
                    } catch (Exception e) {
                        onFailure.accept(e);
                    }
                }
            });
            tab[0].onOpen(opened::add);
            tab[0].show(new PageAcronyms(PageAcronyms.State.CURRENT, RESULT, List.of(rsl), List.of()));
            assertThat(tab[0].showsBelow()).as("Standard aus").isFalse();
            tab[0].below(below);
        });
        flush();
        SwingUtilities.invokeAndWait(() -> {
            TermsTab terms = tab[0];
            assertThat(terms.chips()).extracting(chip -> chip.getText()).containsExactly("RSL · Rückmeldeschlüssel");
            assertThat(terms.alsoBelowTexts()).containsExactly("+2");
            assertThat(terms.showsBelow()).isTrue();
            assertThat(terms.belowChips()).extracting(chip -> chip.getText())
                    .containsExactly("ZV · Zahlungsverkehr", "Antrag");
            assertThat(terms.belowCount()).as("ohne den Begriff, der oben steht").isEqualTo(2);
            assertThat(terms.copyText()).contains(TermsTab.BELOW + "\nZV · Zahlungsverkehr\nAntrag");

            // Herkunft: Klick zeigt die Unterseiten mit Fundstellen, ein Klick öffnet die Seite.
            terms.belowChips().get(1).doClick();
            assertThat(terms.occurrencePopup().linkTexts()).containsExactly("Unterseite A · 1 Fundstelle");
            terms.occurrencePopup().clickLink(0);
            assertThat(opened).extracting(hit -> hit.pageId()).containsExactly("7");

            terms.below(null);
            assertThat(terms.showsBelow()).isFalse();
            assertThat(terms.alsoBelowTexts()).isEmpty();
            assertThat(terms.chips()).extracting(chip -> chip.getText()).containsExactly("RSL · Rückmeldeschlüssel");
        });
        assertThat(PageDetailPanel.subpagesStatus("Begriffe", 3)).isEqualTo("Begriffe aus dieser Seite und 3 "
                + "Unterseiten");
        assertThat(PageDetailPanel.subpagesStatus("Begriffe", 1)).isEqualTo("Begriffe aus dieser Seite und 1 "
                + "Unterseite");
    }

    @Test
    void theStatusLineSaysFromHowManyPagesTheTermsCome() throws Exception {
        assertThat(TermsTab.coverage(new TermsTab.Totals(1, 2, 3, 1240, 1300)))
                .isEqualTo("Begriffe aus 1.240 von 1.300 Seiten · übrige nicht lokal gespeichert");
        assertThat(TermsTab.coverage(new TermsTab.Totals(1, 2, 3, 1300, 1300))).isEqualTo("Begriffe aus 1.300 Seiten");
        assertThat(TermsTab.coverage(new TermsTab.Totals(1, 2, 3))).as("Bestand unbekannt").isNull();
    }

    private static <T> com.aresstack.spaceharvester.app.ui.Pager<T> pager(List<T> items) {
        return new com.aresstack.spaceharvester.app.ui.Pager<>((one, other) -> 0,
                List.of(List.of(cursor -> new com.aresstack.spaceharvester.app.ui.Pager.Page<>(items, null))));
    }

    private static com.aresstack.spaceharvester.application.terms.TermQueries.TermSummary summary(String term,
            TermKind kind, String expansion) {
        return new com.aresstack.spaceharvester.application.terms.TermQueries.TermSummary(term,
                term.toLowerCase(java.util.Locale.ROOT), kind, 3, 2, 1, List.of(term),
                expansion == null ? List.of() : List.of(expansion));
    }

    private static com.aresstack.spaceharvester.application.terms.TermQueries.Occurrence occurrence(String pageId,
                                                                                                  String title) {
        return new com.aresstack.spaceharvester.application.terms.TermQueries.Occurrence("wiki", "DEV", pageId,
                title, TermKind.NOUN, "Antrag", null, null, 0, 6, "1", "sha", "1", null);
    }

    /** Lässt nachgeschobene Schritte (Nachladen nach dem Auslegen) auf dem EDT laufen. */
    private static void flush() throws Exception {
        for (int i = 0; i < 10; i++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
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

    @Test
    void theRelationsOfThePageAreCountedAndListed() throws Exception {
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermsTab tab = new TermsTab();
            tab.show(new PageAcronyms(PageAcronyms.State.CURRENT, RESULT, List.of(), List.of()));
            seen.add(tab.counts().get(2));
            tab.showRelations(List.of(new com.aresstack.spaceharvester.app.ui.Relation("r1", "API",
                    "ABBREVIATION", "Application Programming Interface", "CONFIRMED", "RULE", 1)));
            seen.add(tab.counts().get(2));
            seen.addAll(tab.relationLines());
        });
        assertThat(seen).containsExactly(TermsTab.NOT_YET, "1",
                "API · Abkürzung für · Application Programming Interface (Bestätigt, Regel)");
    }
}
