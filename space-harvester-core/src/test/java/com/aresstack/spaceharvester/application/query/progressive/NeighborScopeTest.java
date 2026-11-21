package com.aresstack.spaceharvester.application.query.progressive;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.query.DocumentKind;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.structure.PageRef;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Der Umfang der Suche gilt auch für Nachbarn: Quelle, Spaces (auch ausgeschlossene), Arten; nur erfundene Daten. */
class NeighborScopeTest {

    private static final PageKey WIKI = new PageKey("wiki", "1");
    private static final PageKey OTHER = new PageKey("akten", "1");

    private static KeywordSearch.Query query(String sourceId, Set<String> spaces, Set<String> excluded,
                                             Set<DocumentKind> kinds) {
        return new KeywordSearch.Query("Nachtlauf", sourceId, Set.of(), spaces, excluded, null, kinds);
    }

    @Test
    void anotherSourceIsOutsideBothForOneSourceAndForChosenSources() {
        assertThat(NeighborScope.allows(query("wiki", Set.of(), Set.of(), Set.of()), OTHER, null)).isFalse();
        KeywordSearch.Query chosen = new KeywordSearch.Query("Nachtlauf", null, Set.of("wiki"), Set.of(), Set.of(),
                null, Set.of());
        assertThat(NeighborScope.allows(chosen, OTHER, null)).isFalse();
        assertThat(NeighborScope.allows(chosen, WIKI, null)).isTrue();
    }

    @Test
    void spacesDecideByTheKnownSpaceAndAnUnknownSpaceIsOutsideWhenSpacesAreLimited() {
        KeywordSearch.Query dev = query(null, Set.of("DEV"), Set.of(), Set.of());
        assertThat(NeighborScope.allows(dev, WIKI, new PageRef(WIKI, "Seite", "DEV"))).isTrue();
        assertThat(NeighborScope.allows(dev, WIKI, new PageRef(WIKI, "Seite", "OPS"))).isFalse();
        assertThat(NeighborScope.allows(dev, WIKI, null)).isFalse();

        KeywordSearch.Query notOps = query(null, Set.of(), Set.of("OPS"), Set.of());
        assertThat(NeighborScope.allows(notOps, WIKI, new PageRef(WIKI, "Seite", "OPS"))).isFalse();
        assertThat(NeighborScope.allows(notOps, WIKI, new PageRef(WIKI, "Seite", "DEV"))).isTrue();

        assertThat(NeighborScope.allows(query(null, Set.of(), Set.of(), Set.of()), WIKI, null)).isTrue();
    }

    @Test
    void kindsDecideByTheTitleAndAnUnknownTitleCountsAsAPage() {
        KeywordSearch.Query pdf = query(null, Set.of(), Set.of(), Set.of(DocumentKind.PDF));
        assertThat(NeighborScope.allows(pdf, WIKI, new PageRef(WIKI, "Plan.pdf", "DEV"))).isTrue();
        assertThat(NeighborScope.allows(pdf, WIKI, new PageRef(WIKI, "Betrieb", "DEV"))).isFalse();
        assertThat(NeighborScope.allows(pdf, WIKI, null)).isFalse();
        assertThat(NeighborScope.allows(query(null, Set.of(), Set.of(), Set.of(DocumentKind.PAGE)), WIKI, null))
                .isTrue();
    }
}
