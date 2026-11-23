package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.holdings.Selection.Root;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Bestand je Wurzel einer Auswahl (ADR 0034 §3): wo sich Graph und Begriffe eines Favoriten-Ordners anmelden. */
class SelectionHoldingsTest {

    @Test
    void eachFavouriteIsWatchedOnceAndAWholeSourceCoversItsSpaces() {
        List<Root> areas = SelectionHoldings.areas(List.of(
                new Root("wiki", "DH", "42"),
                new Root("wiki", "DH", "43"),
                Root.space("wiki", "OPS"),
                new Root("jira", "PRJ", "7"),
                Root.source("jira")));
        assertThat(areas).containsExactly(Root.space("wiki", "DH"), Root.space("wiki", "OPS"), Root.source("jira"));
    }

    @Test
    void aNodeIsWatchedLikeBefore() {
        assertThat(SelectionHoldings.areas(new ExportPanel.Selection.Node("wiki", "DH", "42", "Seite").roots(null)))
                .containsExactly(Root.space("wiki", "DH"));
        assertThat(SelectionHoldings.areas(new ExportPanel.Selection.Node("wiki", null, null, "Wiki").roots(null)))
                .containsExactly(Root.source("wiki"));
        assertThat(SelectionHoldings.areas(List.of())).isEmpty();
    }
}
