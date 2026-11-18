package com.aresstack.spaceharvester.application.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** „Alles löschen“: Urteile des Prüfschritts nach Auswahl und Leserecht entfernen (Angelo 17.11.2026). */
class StatementChecksDeleteTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");
    private static final PageKey A = new PageKey("wiki", "1");
    private static final PageKey B = new PageKey("wiki", "2");
    private static final PageKey SECRET = new PageKey("wiki", "3");
    private static final PageKey OTHER_SOURCE = new PageKey("andere", "1");

    private final StatementCheckStore store = new StatementCheckStore(new InMemoryEnrichmentStore(),
            Clock.fixed(Instant.parse("2026-11-18T19:00:00Z"), ZoneOffset.UTC));
    private final StatementChecks checks = new StatementChecks(StepModel.NONE, store, page -> true,
            (caller, page) -> !page.equals(SECRET));
    private final AtomicInteger notified = new AtomicInteger();

    @BeforeEach
    void fill() {
        for (PageKey page : Set.of(A, B, SECRET, OTHER_SOURCE)) {
            store.put(page, "v1", "sha256:" + "0".repeat(64), Map.of(StatementCheckStore.key("Satz", "Aussage"),
                    new StatementCheckStore.Line(ParsedStatements.Verdict.FALSCH, null)));
        }
        checks.subscribe(notified::incrementAndGet);
    }

    @Test
    void deletesOnlyMatchingReadablePagesCountsThemAndNotifiesOnce() {
        int deleted = checks.delete(CALLER, page -> page.sourceId().equals("wiki"));

        assertThat(deleted).isEqualTo(2);
        assertThat(store.contains(A)).isFalse();
        assertThat(store.contains(B)).isFalse();
        assertThat(store.contains(SECRET)).isTrue();
        assertThat(store.contains(OTHER_SOURCE)).isTrue();
        assertThat(notified).hasValue(1);
    }

    @Test
    void anEmptySelectionChangesNothingAndStaysQuiet() {
        assertThat(checks.delete(CALLER, page -> false)).isZero();

        assertThat(store.pages()).containsExactlyInAnyOrder(A, B, SECRET, OTHER_SOURCE);
        assertThat(notified).hasValue(0);
    }

    @Test
    void withoutReadRightNothingIsDeleted() {
        StatementChecks blind = new StatementChecks(StepModel.NONE, store, page -> true);

        assertThat(blind.delete(CALLER, page -> true)).isZero();
        assertThat(checks.delete(CALLER, SECRET::equals)).isZero();
        assertThat(store.contains(SECRET)).isTrue();
    }
}
