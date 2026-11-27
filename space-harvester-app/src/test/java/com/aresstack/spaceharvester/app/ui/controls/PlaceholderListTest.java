package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlaceholderListTest {

    /** Jedes Zeichen zählt hier zehn Pixel, damit die Erwartung ohne Schrift auskommt. */
    @Test
    void theSentenceWrapsAtSpacesWhenTheAreaIsNarrow() {
        assertThat(PlaceholderList.lines("Keine Treffer", s -> s.length() * 10, 400))
                .containsExactly("Keine Treffer");
        assertThat(PlaceholderList.lines("eins zwei drei vier", s -> s.length() * 10, 100))
                .containsExactly("eins zwei", "drei vier");
        assertThat(PlaceholderList.lines("Donaudampfschiff ahoi", s -> s.length() * 10, 50))
                .as("ein zu breites Wort bleibt ganz").containsExactly("Donaudampfschiff", "ahoi");
    }
}
