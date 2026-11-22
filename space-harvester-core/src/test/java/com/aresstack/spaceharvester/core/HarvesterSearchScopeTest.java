package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.Holdings;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.holdings.SpaceSummary;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Bereich der Suchmethode „Fachbegriffe“ (W8): ausgeschlossene Spaces fehlen schon im Bereich, damit sie die Grenze
 * des Begriffswissens nicht verbrauchen; eine Quelle ohne Leserecht fällt weg. Quellen und Spaces sind erfunden.
 */
class HarvesterSearchScopeTest {

    private static final AccessContext USER = AccessContext.technicalClient("pruefer");

    private final Holdings holdings = mock(Holdings.class);

    private static PageSelection selection(String sourceId, List<String> pageIds, String... spaces) {
        return new PageSelection(PageSet.of(Map.of(sourceId, pageIds)), HoldingCounts.ZERO,
                java.util.Arrays.stream(spaces).map(space -> new SpaceSummary(sourceId, space, null,
                        HoldingCounts.ZERO)).toList(), true);
    }

    @Test
    void excludedSpacesAreLeftOutBeforeTheKnowledgeReadsAnyPage() {
        when(holdings.resolve(any(), eq(Selection.of(Selection.Root.source("wiki")))))
                .thenReturn(selection("wiki", List.of("a1", "a2", "b1"), "ARCHIV", "BETRIEB"));
        when(holdings.resolve(any(), eq(Selection.of(Selection.Root.space("wiki", "BETRIEB")))))
                .thenReturn(selection("wiki", List.of("b1"), "BETRIEB"));
        KeywordSearch.Query query = new KeywordSearch.Query("Leistungsakte", "wiki", Set.of(), Set.of(),
                Set.of("ARCHIV"));

        PageSet scope = Harvester.searchScope(holdings, List.of("wiki"), USER, query);

        assertThat(scope.asMap()).isEqualTo(Map.of("wiki", Set.of("b1")));
    }

    @Test
    void chosenSpacesWithoutTheExcludedOnesAndAnUnreadableSourceFallsAway() {
        when(holdings.resolve(any(), eq(Selection.of(Selection.Root.space("wiki", "BETRIEB")))))
                .thenReturn(selection("wiki", List.of("b1"), "BETRIEB"));
        when(holdings.resolve(any(), eq(Selection.of(Selection.Root.space("akten", "BETRIEB")))))
                .thenThrow(new IllegalStateException("kein Leserecht"));
        KeywordSearch.Query query = new KeywordSearch.Query("Leistungsakte", null, Set.of(),
                Set.of("BETRIEB", "ARCHIV"), Set.of("ARCHIV"));

        PageSet scope = Harvester.searchScope(holdings, List.of("wiki", "akten"), USER, query);

        assertThat(scope.asMap()).isEqualTo(Map.of("wiki", Set.of("b1")));
    }

    @Test
    void aPageLimitIsTheScopeItself() {
        PageSet pages = PageSet.of(Map.of("wiki", List.of("x")));

        assertThat(Harvester.searchScope(holdings, List.of("wiki"), USER,
                KeywordSearch.Query.of("Leistungsakte").within(pages))).isSameAs(pages);
    }
}
