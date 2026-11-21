package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Neue, ersetzte und entfernte Analysen melden sich beim Graphen (D6); Texte erfunden. */
class ObservedKnowledgeGraphStoreTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");

    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicReference<KnowledgeGraph> graphRef = new AtomicReference<>();
    private final ObservedKnowledgeGraphStore store = new ObservedKnowledgeGraphStore(
            new InMemoryKnowledgeGraphStore(), () -> {
                writes.incrementAndGet();
                KnowledgeGraph graph = graphRef.get();
                if (graph != null) {
                    graph.analysesChanged();
                }
            });
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final AccessGuard access = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
    private final StatementAnalysis statements = new StatementAnalysis(
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), store, access,
            FakeStatementAnalyzer::new, StatementAnalysisTest.clock());
    private final KnowledgeGraph graph = new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
            KnowledgeGraph.TermExpansion.NONE);

    @Test
    void aNewAnalysisFromAJobReachesTheGraphObserversWithoutAClick() throws Exception {
        graphRef.set(graph);
        CountDownLatch told = new CountDownLatch(1);
        graph.subscribe(told::countDown);
        PageSnapshot snapshot = EnrichmentTestPages.text("wiki", "1", "1", "Der Sachbearbeiter prüft den Beleg.");
        cache.put(snapshot.key(), snapshot);

        statements.analyze(CALLER, snapshot, () -> false);

        assertThat(writes.get()).as("die Analyse wurde geschrieben").isPositive();
        assertThat(told.await(5, TimeUnit.SECONDS)).as("Beobachter des Graphen erfahren es").isTrue();
        assertThat(graph.analysed(CALLER)).hasSize(1);
    }

    @Test
    void manyWritesInAShortTimeAreDeliveredBundled() throws Exception {
        graphRef.set(graph);
        AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        graph.subscribe(() -> {
            deliveries.incrementAndGet();
            first.countDown();
        });
        for (int i = 1; i <= 20; i++) {
            PageSnapshot snapshot = EnrichmentTestPages.text("wiki", String.valueOf(i), "1",
                    "Der Kunde sendet den Antrag.");
            cache.put(snapshot.key(), snapshot);
            statements.analyze(CALLER, snapshot, () -> false);
        }

        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(writes.get()).isGreaterThanOrEqualTo(20);
        assertThat(deliveries.get()).as("gebündelt statt zwanzigmal").isLessThan(20);
    }

    @Test
    void removalsAreReportedAndAFailingListenerNeverBreaksTheWrite() {
        PageSnapshot snapshot = EnrichmentTestPages.text("wiki", "1", "1", "Der Sachbearbeiter prüft den Beleg.");
        cache.put(snapshot.key(), snapshot);
        statements.analyze(CALLER, snapshot, () -> false);
        int before = writes.get();

        store.remove(snapshot.key());

        assertThat(writes.get()).isEqualTo(before + 1);
        ObservedKnowledgeGraphStore failing = new ObservedKnowledgeGraphStore(new InMemoryKnowledgeGraphStore(),
                () -> {
                    throw new IllegalStateException("Beobachter kaputt");
                });
        failing.remove(new PageKey("wiki", "9"));
        assertThat(failing.all()).isEmpty();
    }
}
