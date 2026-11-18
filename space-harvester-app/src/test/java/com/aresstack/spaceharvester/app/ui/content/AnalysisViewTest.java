package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.RelationTab;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Unterreiter „Analyse“ des Technik-Reiters (BG2b): Schritte und Beziehungen in einer Ansicht. */
class AnalysisViewTest {

    private static final PageKey PAGE = new PageKey("wiki", "100");

    @Test
    void theStepsCarryGermanNamesResultsAndTheTrafficLightAndShowTheTechnicalPartOnClick() throws Exception {
        List<Enrichments.View> views = List.of(
                view("acronyms", "4;sentences=opennlp-de", Enrichment.Status.OK, Enrichment.Freshness.CURRENT,
                        "{\"acronyms\":[]}", List.of("kurz")),
                view("outline", "1", Enrichment.Status.OK, Enrichment.Freshness.STALE, "{}", List.of()),
                view("images", "1", Enrichment.Status.FAILED, Enrichment.Freshness.CURRENT, "{}", List.of()));
        SwingUtilities.invokeAndWait(() -> {
            AnalysisView analysis = new AnalysisView(RelationTab.all(null), true);
            analysis.steps(views, view -> view.enrichment().processorId().equals("acronyms") ? "4 Akronyme" : null);
            assertThat(analysis.showsSteps()).isTrue();
            List<String> rows = new ArrayList<>();
            for (int r = 0; r < analysis.stepTable().getRowCount(); r++) {
                Enrichments.View view = (Enrichments.View) analysis.stepTable().getValueAt(r, 2);
                rows.add(analysis.stepTable().getValueAt(r, 0) + " | " + analysis.stepTable().getValueAt(r, 1)
                        + " | " + AnalysisView.standWord(view));
            }
            // Kein Rohname, keine Version in der Zeile; Ergebnis als Zahl mit Wort, sonst „berechnet“.
            assertThat(rows).containsExactly("Akronyme | 4 Akronyme | aktuell", "Gliederung | berechnet | veraltet",
                    "Bilder | fehlgeschlagen (TIMEOUT) | fehlgeschlagen");
            assertThat(AnalysisView.standTone(views.get(1))).isEqualTo(com.aresstack.spaceharvester.app.ui.Comic.YELLOW);
            assertThat(AnalysisView.standTone(views.get(2))).isEqualTo(com.aresstack.spaceharvester.app.ui.Comic.RED);
        });
    }

    @Test
    void aPageWithoutAnalysesSaysSoAndManyStepsScrollAfterTenRows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AnalysisView analysis = new AnalysisView(RelationTab.all(null), true);
            analysis.steps(List.of(), null);
            assertThat(analysis.showsSteps()).isFalse();
            assertThat(analysis.stepPlaceholder()).isEqualTo(AnalysisView.NO_STEPS);
            List<Enrichments.View> many = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                many.add(view("p" + i, "1", Enrichment.Status.OK, Enrichment.Freshness.CURRENT, "{}", List.of()));
            }
            analysis.steps(many, null);
            assertThat(analysis.showsSteps()).isTrue();
            assertThat(analysis.stepPlaceholder()).isNull();
            assertThat(analysis.visibleStepRows()).isEqualTo(AnalysisView.MAX_STEP_ROWS);
            assertThat(analysis.stepTable().getToolTipText()).isEqualTo("Klick zeigt Kennung, Version und Rohergebnis");
            analysis.steps(many.subList(0, 3), null);
            assertThat(analysis.visibleStepRows()).isEqualTo(3);
        });
    }

    @Test
    void theRelationsFollowTheSelectionAndCarryTheirTotalFromTheCore() throws Exception {
        List<PageSet> asked = new ArrayList<>();
        AtomicInteger counted = new AtomicInteger();
        RelationTab[] relations = new RelationTab[1];
        AnalysisView[] view = new AnalysisView[1];
        SwingUtilities.invokeAndWait(() -> {
            relations[0] = RelationTab.all(null);
            relations[0].fetch((type, status, term, startsWith, cursor) -> new TermRelations.RelationList(List.of(),
                    null));
            view[0] = new AnalysisView(relations[0], false);
            view[0].runner(new AnalysisView.Runner() {
                @Override
                public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                    try {
                        onSuccess.accept(work.call());
                    } catch (Exception e) {
                        onFailure.accept(e);
                    }
                }
            });
            assertThat(view[0].showsSteps()).as("Oberseiten ohne Analyseschritte").isFalse();
            view[0].scope(new AnalysisView.Scope() {
                @Override
                public PageSet pages() {
                    PageSet set = PageSet.ofKeys(List.of(PAGE));
                    asked.add(set);
                    return set;
                }

                @Override
                public int relations(PageSet pages) {
                    counted.incrementAndGet();
                    return 12;
                }
            });
            assertThat(view[0].relationHeading()).isEqualTo("Beziehungen · …");
            assertThat(asked).as("verdeckt wird nichts gelesen").isEmpty();
            view[0].shown(true);
        });
        await(() -> view[0].relationHeading().equals("Beziehungen · 12"));
        assertThat(asked).hasSize(1);
        assertThat(counted).hasValue(1);
    }

    private static void await(Supplier<Boolean> condition) throws Exception {
        for (int i = 0; i < 200; i++) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.get());
            if (done[0]) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Bedingung nicht erfüllt");
    }

    private static Enrichments.View view(String processor, String version, Enrichment.Status status,
                                         Enrichment.Freshness freshness, String json, List<String> warnings) {
        return new Enrichments.View(new Enrichment(PAGE, processor, version, "sha", "3", status, json, warnings,
                status == Enrichment.Status.FAILED ? "TIMEOUT" : null, Instant.parse("2026-11-17T10:00:00Z")),
                freshness);
    }
}
