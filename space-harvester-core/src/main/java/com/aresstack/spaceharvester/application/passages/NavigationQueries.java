package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageQueries;
import java.util.Objects;

/**
 * Die Dokumentnavigation einer Seite (#181, ADR 0020) für einen Aufrufer, wie {@code BuildDocumentNavigationUseCase}
 * im Enterprise AI Client. Rechte und Aktualität wie beim Abruf der Seite ({@link PageQueries#page}), also wie
 * {@link PassageQueries}. Fehlt das Ergebnis oder ist es veraltet, wird es nachgezogen: zuerst die Embeddings je
 * Absatz (mit Modell im Hintergrund), dann die Navigation; bis dahin zeigt die Antwort die Überschriften.
 */
public final class NavigationQueries {

    private final PageQueries pages;
    private final Enrichments enrichments;
    private final PageTextProjector projector;
    private final DocumentNavigationBuilder builder;

    public NavigationQueries(PageQueries pages, Enrichments enrichments, PageTextProjector projector,
                             DocumentNavigationBuilder builder) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.builder = Objects.requireNonNull(builder, "builder");
    }

    /**
     * Die Navigation einer Seite.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wie {@link PageQueries#page}
     * @throws com.aresstack.spaceharvester.application.query.CacheMissException     wie {@link PageQueries#page}
     */
    public DocumentNavigationView page(AccessContext caller, String sourceId, String pageId) {
        DeliveredPage page = pages.page(caller, sourceId, pageId, false);
        PageText text = text(page);
        DocumentNavigationView navigation = read(page, text);
        if (text == null || navigation.state() == PagePassages.State.CURRENT
                || !enrichments.processorIds().contains(NavigationProcessor.ID)) {
            return navigation;
        }
        if (enrichments.processorIds().contains("embedding")) {
            enrichments.refresh(page.snapshot(), "embedding");
        }
        Enrichments.Run run = enrichments.refresh(page.snapshot(), NavigationProcessor.ID);
        if (run.computed() == 0) {
            return navigation;
        }
        enrichments.flush();
        return read(page, text);
    }

    private DocumentNavigationView read(DeliveredPage page, PageText text) {
        return DocumentNavigationView.read(page.snapshot().key(), page.snapshot().metadata().title(),
                enrichments.view(page.snapshot()), text, builder);
    }

    private PageText text(DeliveredPage page) {
        try {
            return projector.project(page.snapshot()).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
