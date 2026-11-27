package com.aresstack.spaceharvester.app.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Angelo 27.11.2026: „Buch schreiben“ zeigte „306/88“ in der Phase „Quellen beschreiben“. Das Sieb meldet erst den
 * ersten fertigen Aufruf, nie 0; seine Menge kam nie dazu und die Phase blieb stehen.
 */
class StepProgressTest {

    @Test
    void einSchrittOhneNullZaehltSeineMengeUndNenntSeinePhase() {
        BasketResearchJobTest.Counting context = new BasketResearchJobTest.Counting();
        BookRunProgress progress = BookJob.progress(context)::step;

        progress.step("quellensteckbriefe", 0, 88);
        for (int done = 1; done <= 88; done++) {
            progress.step("quellensteckbriefe", done, 88);
        }
        progress.step("quellensteckbriefe", 88, 88);
        progress.step("sieb", 1, 600);
        progress.step("sieb", 3, 600);
        progress.step("sieb", 2, 600);

        assertThat(context.discovered).hasValue(688);
        assertThat(context.processed).hasValue(91);
        assertThat(context.phases).containsExactly("Quellen beschreiben", "Begriffe sieben");
    }

    @Test
    void erledigtNieUeberDerMengeUndEineWachsendeMengeKommtDazu() {
        BasketResearchJobTest.Counting context = new BasketResearchJobTest.Counting();
        BookRunProgress progress = BookJob.progress(context)::step;

        progress.step("belegpruefung", 5, 3);
        progress.step("belegpruefung", 4, 6);

        assertThat(context.discovered).hasValue(6);
        assertThat(context.processed).hasValue(4);
    }

    interface BookRunProgress {
        void step(String step, int done, int total);
    }
}
