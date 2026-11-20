package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sources.SourceSearchMatch;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Die Arten einer Anfrage: Vorgabe alle; mit Arten passt ein Fund der Quelle nur nach seinem Titel. */
class KeywordSearchQueryKindsTest {

    @Test
    void withoutKindsEveryFindingIsAdmittedAndWithKindsOnlyThoseOfAChosenKind() {
        KeywordSearch.Query all = KeywordSearch.Query.of("nachtlauf");
        assertThat(all.kinds()).isEmpty();
        assertThat(all.admits("wiki", match("Betrieb"))).isTrue();

        KeywordSearch.Query pdf = all.ofKinds(Set.of(DocumentKind.PDF));
        assertThat(pdf.kinds()).containsExactly(DocumentKind.PDF);
        assertThat(pdf.text()).isEqualTo("nachtlauf");
        // Ohne bekannte Endung bleibt der Fund Kandidat: erst die Kopfdaten kennen den Medientyp (#964).
        assertThat(pdf.admits("wiki", match("Betrieb"))).isTrue();
        assertThat(pdf.admits(metadata("Betrieb", null))).as("Kopfdaten einer Seite").isFalse();
        assertThat(pdf.admits(metadata("Protokoll", "application/pdf"))).isTrue();
        assertThat(pdf.admits("files", match("Protokoll.pdf"))).isTrue();
        assertThat(pdf.admits("files", match("Regeltermin.pptx"))).isFalse();
        // Leer hebt die Grenze wieder auf; Varianten behalten die Arten.
        assertThat(pdf.ofKinds(Set.of()).kinds()).isEmpty();
        assertThat(pdf.within(null).kinds()).containsExactly(DocumentKind.PDF);
    }

    private static com.aresstack.spaceharvester.application.sources.PageMetadata metadata(String title,
                                                                                            String mediaType) {
        java.util.SortedMap<String, String> attributes = new java.util.TreeMap<>();
        if (mediaType != null) {
            attributes.put(com.aresstack.spaceharvester.application.sources.PageMetadata.REMOTE_MEDIA_TYPE, mediaType);
        }
        return new com.aresstack.spaceharvester.application.sources.PageMetadata("1", "r:1", title, "DEV", null,
                null, null, null, attributes);
    }

    private static SourceSearchMatch match(String title) {
        return new SourceSearchMatch("1", title, "", null, null, null);
    }
}
