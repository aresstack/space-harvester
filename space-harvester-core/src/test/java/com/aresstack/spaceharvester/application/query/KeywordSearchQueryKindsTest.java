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
        assertThat(pdf.admits("wiki", match("Betrieb"))).as("eine Seite ohne Endung").isFalse();
        assertThat(pdf.admits("files", match("Protokoll.pdf"))).isTrue();
        assertThat(pdf.admits("files", match("Regeltermin.pptx"))).isFalse();
        // Leer hebt die Grenze wieder auf; Varianten behalten die Arten.
        assertThat(pdf.ofKinds(Set.of()).kinds()).isEmpty();
        assertThat(pdf.within(null).kinds()).containsExactly(DocumentKind.PDF);
    }

    private static SourceSearchMatch match(String title) {
        return new SourceSearchMatch("1", title, "", null, null, null);
    }
}
