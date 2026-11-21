package com.aresstack.spaceharvester.application.structure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.links.PageLinks;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link PageLinksDirectory}: unbekannt statt leer, Vollständigkeit aus dem Verzeichnis. */
class PageLinksDirectoryTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final PageKey SEED = new PageKey("wiki", "1");
    private static final PageKey OTHER = new PageKey("wiki", "2");

    @Test
    void aCapturedPageGivesBothDirectionsAndTheDirectorysCompleteness() {
        LinkDirectory complete = PageLinksDirectory.from((caller, page) -> new PageLinks.Links(page, List.of(OTHER),
                List.of(new PageKey("wiki", "3")), true, new PageLinks.Coverage(5, 5)));
        LinkDirectory reconciling = PageLinksDirectory.from((caller, page) -> new PageLinks.Links(page,
                List.of(OTHER), List.of(), true, new PageLinks.Coverage(5, 5, true)));

        LinkDirectory.Links links = complete.links(CALLER, SEED).orElseThrow();

        assertThat(links.outgoing()).containsExactly(OTHER);
        assertThat(links.incoming()).containsExactly(new PageKey("wiki", "3"));
        assertThat(links.complete()).isTrue();
        assertThat(reconciling.links(CALLER, SEED).orElseThrow().complete()).isFalse();
    }

    @Test
    void aPageTheDirectoryHasNotCapturedHasUnknownLinksNotNone() {
        LinkDirectory notCaptured = PageLinksDirectory.from((caller, page) -> new PageLinks.Links(page, List.of(),
                List.of(OTHER), false, new PageLinks.Coverage(0, 4)));

        assertThat(notCaptured.links(CALLER, SEED)).isEmpty();
    }
}
