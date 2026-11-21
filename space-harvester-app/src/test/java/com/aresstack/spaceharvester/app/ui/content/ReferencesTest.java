package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.links.PageLinks;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Statuszeile des Reiters „Verweise“ (Paket V2): beide Richtungen, Stand des Verzeichnisses. */
class ReferencesTest {

    private static final PageNode PLAN = new PageNode("2", "Plan", "DEV", null);
    private static final PageNode ZIEL = new PageNode("3", null, null, null);

    @Test
    void incomingFromTheDirectoryNamesHowManyStoredPagesItComesFrom() {
        PageNavigation.References references = new PageNavigation.References("wiki", "1", List.of(PLAN),
                List.of(PLAN, ZIEL), new PageLinks.Coverage(120, 340));

        assertThat(References.hint(references))
                .isEqualTo("Diese Seite verweist auf 1 Seite; 2 Seiten verweisen auf sie (geprüft: 120 von 340 Seiten"
                        + " sind lokal gespeichert).");
    }

    @Test
    void whileTheDirectoryIsStillBuiltNoCountersThatLookComplete() {
        PageNavigation.References references = new PageNavigation.References("wiki", "1", List.of(PLAN),
                List.of(PLAN), new PageLinks.Coverage(12, 340, true));

        assertThat(References.hint(references))
                .isEqualTo("Diese Seite verweist auf 1 Seite; Verzeichnis der Verweise wird noch aufgebaut.");
    }

    @Test
    void onlyAChangeOfTheWholeSourceOrOfTheShownPageReloads() {
        assertThat(PageDetailPanel.affects(new PageLinks.LinksChanged("wiki", java.util.Set.of()), "wiki", "1"))
                .isTrue();
        assertThat(PageDetailPanel.affects(new PageLinks.LinksChanged("wiki", java.util.Set.of("1", "7")), "wiki",
                "1")).isTrue();
        assertThat(PageDetailPanel.affects(new PageLinks.LinksChanged("wiki", java.util.Set.of("7")), "wiki", "1"))
                .isFalse();
        assertThat(PageDetailPanel.affects(new PageLinks.LinksChanged("andere", java.util.Set.of()), "wiki", "1"))
                .isFalse();
    }

    @Test
    void hiddenCountersAndIncomingFromTheSourceNameNoCoverage() {
        assertThat(References.hint(new PageNavigation.References("wiki", "1", List.of(), List.of(PLAN),
                PageLinks.Coverage.HIDDEN)))
                .isEqualTo("Diese Seite verweist auf keine andere Seite der Quelle; 1 Seite verweist auf sie.");
        assertThat(References.hint(new PageNavigation.References("wiki", "1", List.of(PLAN), List.of())))
                .isEqualTo("Diese Seite verweist auf 1 Seite; keine Seite verweist auf sie.");
        assertThat(References.hint(new PageNavigation.References("wiki", "1", List.of(PLAN), null)))
                .endsWith("eingehende Verweise kennt die Quelle nicht vollständig.");
    }

    @Test
    void aPageWithoutTitleIsShownByItsIdNotAsAnEmptyRow() {
        References view = new References();
        view.show(new PageNavigation.References("wiki", "1", List.of(), List.of(ZIEL),
                new PageLinks.Coverage(1, 1)));

        assertThat(view.table().getValueAt(0, 0)).isEqualTo("verwiesen von");
        assertThat(view.table().getValueAt(0, 1)).asString().isNotBlank().contains("3");
        assertThat(view.table().getValueAt(0, 2)).isEqualTo("–");
        assertThat(view.showing()).isTrue();
    }
}
