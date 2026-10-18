package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Adressen öffnet das Suchfeld direkt über die Adressauflösung des Core; alles andere bleibt Schlagwortsuche. */
class SearchViewAddressTest {

    @Test
    void urlsBookmarksAndConfluenceBookmarksAreAddresses() {
        assertThat(SearchView.ADDRESS.matcher("https://wiki.firma.de/display/DEV/Start").matches()).isTrue();
        assertThat(SearchView.ADDRESS.matcher("space-harvester://wiki/page/102?version=3").matches()).isTrue();
        assertThat(SearchView.ADDRESS.matcher("confluence://wiki/102").matches()).isTrue();
        assertThat(SearchView.ADDRESS.matcher("Release Notes").matches()).isFalse();
        assertThat(SearchView.ADDRESS.matcher("https://wiki.firma.de mit Text").matches()).isFalse();
    }
}
