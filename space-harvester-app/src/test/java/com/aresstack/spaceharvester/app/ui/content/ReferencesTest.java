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

    /**
     * Fahrplan P3: die Verweise der Unterseiten stehen abgetrennt darunter, je Zeile mit der Unterseite als Herkunft;
     * was oben schon steht, steht unten nicht noch einmal. Ein Klick auf die Herkunft öffnet die Unterseite.
     */
    @Test
    void theReferencesOfTheSubpagesFollowBelowWithTheirOrigin() {
        References view = new References();
        List<String> opened = new java.util.ArrayList<>();
        List<String> status = new java.util.ArrayList<>();
        view.onOpen(opened::add);
        view.onStatus(status::add);
        view.show(new PageNavigation.References("wiki", "1", List.of(PLAN), List.of(), new PageLinks.Coverage(1, 1)));
        assertThat(view.showsBelow()).isFalse();
        PageNode child = new PageNode("11", "Zahlungen", "DEV", null);

        view.belowLoading(2);
        assertThat(view.showsBelow()).isTrue();
        view.below(List.of(new PageNavigation.SubpageReference(child, true, PLAN),
                new PageNavigation.SubpageReference(child, true, new PageNode("8", "Kassenbuch", "DEV", null)),
                new PageNavigation.SubpageReference(child, false, ZIEL)));

        assertThat(view.belowCount()).as("„verweist auf Plan“ steht schon oben").isEqualTo(2);
        assertThat(view.belowTable().getValueAt(0, 1)).isEqualTo("Kassenbuch");
        assertThat(view.belowTable().getValueAt(0, 3)).isEqualTo("Zahlungen");
        assertThat(view.belowTable().getValueAt(1, 0)).isEqualTo("verwiesen von");
        assertThat(status).as("kein Zusatztext, die Zahl steht im Chip (UX)").last().asString()
                .doesNotContain("Unterseite");
        assertThat(view.copyText()).contains(TermsTab.BELOW + "\nverweist auf\tKassenbuch\tDEV\tZahlungen");

        view.openBelow(0, 3);
        view.openBelow(0, 1);
        assertThat(opened).containsExactly("11", "8");

        view.belowLoading(-1);
        assertThat(view.showsBelow()).isFalse();
    }
}
