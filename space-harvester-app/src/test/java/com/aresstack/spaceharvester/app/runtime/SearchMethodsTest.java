package com.aresstack.spaceharvester.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Zuschaltbare Suchmethoden: Standard aus, die Stellung bleibt erhalten. */
class SearchMethodsTest {

    private final Preferences node = Preferences.userRoot().node("space-harvester-test/search-methods-"
            + System.nanoTime());

    @AfterEach
    void remove() throws Exception {
        node.removeNode();
    }

    @Test
    void neighborsAreOffByDefaultAndKeepTheirSetting() {
        assertThat(new SearchMethods(node).neighbors()).isFalse();

        new SearchMethods(node).neighbors(true);

        assertThat(new SearchMethods(node).neighbors()).isTrue();
    }

    @Test
    void termPagesAreOffByDefaultAndKeepTheirSetting() {
        assertThat(new SearchMethods(node).termPages()).isFalse();

        new SearchMethods(node).termPages(true);

        assertThat(new SearchMethods(node).termPages()).isTrue();
        assertThat(new SearchMethods(node).neighbors()).as("eigene Stellung").isFalse();
    }

    @Test
    void glossarySynonymsAreOffByDefaultAndHoldOnlyForTheSession() {
        // K5a: Standard aus, gemerkt für die Sitzung, nicht über den Neustart.
        SearchMethods methods = new SearchMethods(node);
        assertThat(methods.glossarySynonyms()).isFalse();

        methods.glossarySynonyms(true);

        assertThat(methods.glossarySynonyms()).isTrue();
        assertThat(new SearchMethods(node).glossarySynonyms()).isFalse();
    }
}
