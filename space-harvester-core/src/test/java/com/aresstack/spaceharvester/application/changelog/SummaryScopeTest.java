package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.holdings.Selection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryScopeTest {

    private static final AccessContext ANNA = AccessContext.technicalClient("anna");
    private static final AccessContext BERND = AccessContext.technicalClient("bernd");
    private static final Selection.Root PAGE = Selection.Root.subtree("wiki", "OPS", "4711");
    private static final Selection.Root SPACE = Selection.Root.space("wiki", "DEV");

    @Test
    void theSelectionKeyIsCanonicalAndBelongsToItsOwner() {
        SummaryScope.Selected one = SummaryScope.selection(ANNA, Selection.of(PAGE, SPACE));

        // Reihenfolge und Doppelte ändern den Platz nicht (ADR 0034 §7: kanonische Auswahl).
        assertThat(SummaryScope.selection(ANNA, new Selection(List.of(SPACE, PAGE, SPACE)))).isEqualTo(one);
        // Andere Wurzeln, anderer Besitzer: anderer Platz; so sieht niemand eine fremde Zusammenfassung.
        assertThat(SummaryScope.selection(ANNA, Selection.of(PAGE))).isNotEqualTo(one);
        assertThat(SummaryScope.selection(BERND, Selection.of(PAGE, SPACE))).isNotEqualTo(one);
        assertThat(one.ownedBy(ANNA)).isTrue();
        assertThat(one.ownedBy(BERND)).isFalse();
        assertThat(one.key()).matches("selection-[0-9a-f]{64}");
    }

    @Test
    void anUpdateKeepsItsLevelAndHasAFileNameKey() {
        SummaryScope.Update source = SummaryScope.update("wiki", "u1", null);
        SummaryScope.Update space = SummaryScope.update("wiki", "u1", "OPS");

        assertThat(source.key()).isNotEqualTo(space.key()).matches("update-[0-9a-f]{64}");
        assertThat(new StoredSummary("wiki", "u1", "OPS", "Text", List.of(), "modell",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, 0, "sha", List.of()).scope()).isEqualTo(space);
    }

    @Test
    void aSelectionHasNoStorageYet() {
        assertThat(ChangeLogStore.NONE.summary(SummaryScope.selection(ANNA, Selection.of(PAGE)))).isEmpty();
    }
}
