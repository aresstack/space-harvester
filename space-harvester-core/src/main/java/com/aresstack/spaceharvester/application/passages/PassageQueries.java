package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageQueries;
import java.util.Objects;

/**
 * Die semantischen Absätze einer Seite (#181, ADR 0020) für einen Aufrufer. Rechte und Aktualität wie beim Abruf der
 * Seite ({@link PageQueries#page}); keine eigene Suche, kein zweiter Index. Fehlt das Ergebnis oder ist es veraltet,
 * wird es wie nach einem Abgleich nachgezogen: ohne Modell sofort, mit Modell im Hintergrund, dann zeigt die Antwort
 * bis dahin den bisherigen Stand.
 */
public final class PassageQueries {

    /** Nachbarn je Seite eines gefundenen Absatzes, wie {@code KnowledgeResourceReader.NEIGHBOURS} im Client. */
    static final int NEIGHBOURS = 1;

    private final PageQueries pages;
    private final Enrichments enrichments;
    private final PageTextProjector projector;

    public PassageQueries(PageQueries pages, Enrichments enrichments, PageTextProjector projector) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    /**
     * Die Absätze einer Seite.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wie {@link PageQueries#page}
     * @throws com.aresstack.spaceharvester.application.query.CacheMissException     wie {@link PageQueries#page}
     */
    public PagePassages page(AccessContext caller, String sourceId, String pageId) {
        DeliveredPage page = pages.page(caller, sourceId, pageId, false);
        PageText text = text(page);
        PagePassages passages = PagePassages.read(page.snapshot().key(), enrichments.view(page.snapshot()), text);
        if (text == null || passages.state() == PagePassages.State.CURRENT
                || !enrichments.processorIds().contains(PassageProcessor.ID)) {
            return passages;
        }
        Enrichments.Run run = enrichments.refresh(page.snapshot(), PassageProcessor.ID);
        if (run.computed() == 0) {
            return passages;
        }
        enrichments.flush();
        return PagePassages.read(page.snapshot().key(), enrichments.view(page.snapshot()), text);
    }

    /**
     * Löst eine Passage-ID auf ({@link PassageId}), wie {@code KnowledgeResourceReader} im Enterprise AI Client: Gibt
     * es den Absatz im aktuellen Ergebnis, kommt er mit je {@value #NEIGHBOURS} Nachbarn; sonst kommen alle aktuellen
     * Absätze und {@link PassageLookup.Match} sagt warum. Rechte und Nachziehen wie {@link #page}.
     *
     * @throws IllegalArgumentException wenn {@code passageId} keine Passage-ID ist
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wie {@link PageQueries#page}
     * @throws com.aresstack.spaceharvester.application.query.CacheMissException     wie {@link PageQueries#page}
     */
    public PassageLookup resolve(AccessContext caller, String passageId) {
        PassageId requested = PassageId.parse(passageId);
        return PassageLookup.of(requested, page(caller, requested.sourceId(), requested.pageId()), NEIGHBOURS);
    }

    private PageText text(DeliveredPage page) {
        try {
            return projector.project(page.snapshot()).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
