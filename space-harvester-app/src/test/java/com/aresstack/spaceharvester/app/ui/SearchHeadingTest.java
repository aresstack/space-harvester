package com.aresstack.spaceharvester.app.ui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Titeltreffer und abgesetzter Bereich über den Suchtreffern (Angelo, 23.11.2026). */
class SearchHeadingTest {

    @Test
    void titlesMatchExactlyOrAlmost() {
        assertThat(SearchView.similarTitle("Kurzbeschreibung DATENH V1.0", "kurzbeschreibung datenh v1.0")).isTrue();
        assertThat(SearchView.similarTitle("Ausgangsvalidierung IAR", "Ausgangsvalidierung  IAR!")).isTrue();
        assertThat(SearchView.similarTitle("Ausgangsvalidierung IAR", "Ausgangsvalidirung IAR")).as("Tippfehler")
                .isTrue();
        assertThat(SearchView.similarTitle("Betriebshandbuch", "Betriebshandbuch")).isTrue();
        assertThat(SearchView.similarTitle("Betriebshandbuch V2", "Betriebshandbuch")).isTrue();
        assertThat(SearchView.similarTitle("Protokoll der Sitzung zum Betriebshandbuch 2026", "Betriebshandbuch"))
                .isFalse();
        assertThat(SearchView.similarTitle("Release-Plan", "Betrieb")).isFalse();
        assertThat(SearchView.similarTitle("", "x")).isFalse();
    }

    @Test
    void titleTermsAddTheLongestWordAndAreaKeepsSpaces() {
        assertThat(SearchView.titleTerms("Ausgangsvalidirung IAR")).containsExactly("Ausgangsvalidirung IAR",
                "ausgangsvalidirung");
        assertThat(SearchView.titleTerms("Betrieb")).containsExactly("Betrieb");
        SearchView.Hit inX = new SearchView.Hit("b", "1", "T", "X", "", null);
        SearchView.Hit inY = new SearchView.Hit("b", "2", "T", "Y", "", null);
        List<Explorer.Scope> area = List.of(new Explorer.Scope("a", null), new Explorer.Scope("b", "X"));
        assertThat(SearchView.inArea(area, inX)).isTrue();
        assertThat(SearchView.inArea(area, inY)).isFalse();
        assertThat(SearchView.inArea(List.of(), inY)).isTrue();
    }

    @Test
    void pinnedHitsShowSelectAndOpen() throws Exception {
        List<String> selected = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        PinnedHits[] pinned = new PinnedHits[1];
        SwingUtilities.invokeAndWait(() -> {
            pinned[0] = new PinnedHits();
            pinned[0].onSelect(hit -> selected.add(hit.pageId()));
            pinned[0].onOpen(hit -> opened.add(hit.pageId()));
        });
        assertThat(pinned[0].isVisible()).as("ohne Seiten ausgeblendet").isFalse();
        SwingUtilities.invokeAndWait(() -> pinned[0].show(PinnedHits.ADDRESS,
                List.of(new SearchView.Hit("s", "42", "Seite 42", "DEV", "", null))));
        assertThat(pinned[0].isVisible()).isTrue();
        assertThat(pinned[0].caption()).isEqualTo("Seite zur Adresse");
        SwingUtilities.invokeAndWait(() -> pinned[0].select(0));
        assertThat(selected).containsExactly("42");
        assertThat(pinned[0].selectedIndex()).isZero();
        assertThat(opened).isEmpty();
        SwingUtilities.invokeAndWait(pinned[0]::clear);
        assertThat(pinned[0].isVisible()).isFalse();
    }
}
