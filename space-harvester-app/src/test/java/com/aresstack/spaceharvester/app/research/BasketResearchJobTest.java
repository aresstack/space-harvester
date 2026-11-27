package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.run.ProgressListener;
import com.aresstack.research4j.basket.BasketResearch;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Fortschritt von „Korb füllen“ zählt alle Schritte einer Runde. Zählte er nur das Lesen, stand die Anzeige auf
 * 99 %, während das Modell noch am Weidezaun prüfte (Test des Produktverantwortlichen am 27.11.).
 */
class BasketResearchJobTest {

    static final class Counting implements JobContext {
        final AtomicLong discovered = new AtomicLong();
        final AtomicLong processed = new AtomicLong();
        final List<String> phases = new ArrayList<>();

        @Override
        public void checkpoint() {
        }

        @Override
        public String jobId() {
            return "korb";
        }

        @Override
        public boolean cancelRequested() {
            return false;
        }

        @Override
        public void discovered(long pages) {
            discovered.addAndGet(pages);
        }

        @Override
        public void processed(long pages) {
            processed.addAndGet(pages);
        }

        @Override
        public void written(long pages) {
        }

        @Override
        public void failed(long pages) {
        }

        @Override
        public void phase(String component, String phase) {
            phases.add(phase);
        }
    }

    @Test
    void nachDemLesenStehtDasPruefenNochAusUndDiePhaseSagtEs() {
        Counting context = new Counting();
        ProgressListener progress = BasketResearchJob.progress(context);

        progress.onProgress(BasketResearch.STEP_PLAN, 0, 2);
        progress.onProgress(BasketResearch.STEP_PLAN, 2, 2);
        progress.onProgress(BasketResearch.STEP_FETCH, 0, 3);
        progress.onProgress(BasketResearch.STEP_FETCH, 1, 3);
        progress.onProgress(BasketResearch.STEP_FETCH, 3, 3);
        progress.onProgress(BasketResearch.STEP_JUDGE, 0, 3);

        assertThat(context.discovered).hasValue(8);
        assertThat(context.processed).as("das Prüfen der drei Seiten steht noch aus").hasValue(5);
        assertThat(context.phases).last().isEqualTo("Am Weidezaun prüfen");

        progress.onProgress(BasketResearch.STEP_JUDGE, 2, 3);
        progress.onProgress(BasketResearch.STEP_JUDGE, 1, 3); // verspätete Meldung zählt nicht doppelt
        progress.onProgress(BasketResearch.STEP_JUDGE, 3, 3);
        assertThat(context.processed).hasValue(8);
    }
}
