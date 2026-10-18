package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * Port der Textprojektion: macht aus der nativen Payload einer Quelle eine {@link PageText}. Umsetzungen führen keine
 * Makros aus, laden nichts nach und expandieren keine eingebetteten oder geschützten Inhalte.
 */
public interface PageTextProjector {

    /**
     * @return die Projektion, oder leer, wenn das Format der Payload nicht unterstützt wird
     * @throws IllegalArgumentException wenn die Payload das unterstützte Format verletzt
     */
    Optional<PageText> project(PageSnapshot snapshot);

    /**
     * Der erste Projektor, der das Format kennt, wie im Volltextindex: So bekommen auch Seiten lokaler Dateien
     * Anreicherungen und Embeddings, nicht nur Confluence-Seiten.
     */
    static PageTextProjector firstOf(List<PageTextProjector> projectors) {
        List<PageTextProjector> all = List.copyOf(projectors);
        return snapshot -> {
            for (PageTextProjector projector : all) {
                Optional<PageText> text = projector.project(snapshot);
                if (text.isPresent()) {
                    return text;
                }
            }
            return Optional.empty();
        };
    }
}
