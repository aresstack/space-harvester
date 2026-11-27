package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.access.ContentFetchPort;
import com.aresstack.knowledge4j.access.FetchedContent;
import com.aresstack.knowledge4j.access.SourceAccessException;
import com.aresstack.knowledge4j.run.CancellationSignal;
import com.aresstack.knowledge4j.source.Classification;
import com.aresstack.knowledge4j.source.Provenance;
import com.aresstack.knowledge4j.source.SourceRef;
import com.aresstack.spaceharvester.application.snapshot.PageKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Liest Seiten von Space Harvester für Research4j, wie das Chat-Werkzeug {@code seite_lesen}: aus dem Cache oder von der
 * Quelle geholt, mit den Unterseiten als Verweise, damit die Recherche einem Seitenbaum folgen kann. Research4j prüft
 * jede gelesene Seite mit dem Modell am Weidezaun; deshalb kommt sie vorher auf die Liste freigegebener Seiten
 * (ADR 0028/0033: nur was wirklich an das Modell geht). Lässt sie sich nicht freigeben (etwa ohne Space), gibt der
 * Adapter ihren Text nicht heraus.
 */
public final class PageFetchPort implements ContentFetchPort {

    private final PageStock stock;

    public PageFetchPort(PageStock stock) {
        this.stock = Objects.requireNonNull(stock, "stock");
    }

    @Override
    public boolean supports(SourceRef ref) {
        return PageRefs.key(ref) != null;
    }

    @Override
    public FetchedContent fetch(SourceRef ref, CancellationSignal cancel) throws SourceAccessException {
        PageKey key = PageRefs.key(ref);
        if (key == null) {
            throw new SourceAccessException(SourceAccessException.Reason.NOT_FOUND, "keine Seite von Space Harvester");
        }
        PageStock.Page page;
        try {
            page = stock.page(key).orElse(null);
        } catch (RuntimeException failed) {
            throw new SourceAccessException(SourceAccessException.Reason.UNREACHABLE,
                    "Abruf gescheitert (" + failed.getClass().getSimpleName() + ")", failed);
        }
        if (page == null) {
            throw new SourceAccessException(SourceAccessException.Reason.NOT_FOUND, "Seite liegt nicht lokal");
        }
        if (page.spaceKey() == null || page.spaceKey().isBlank()) {
            throw new SourceAccessException(SourceAccessException.Reason.NO_PERMISSION,
                    "Seite ohne Space lässt sich nicht freigeben");
        }
        try {
            stock.release(page.spaceKey(), key);
        } catch (RuntimeException failed) {
            throw new SourceAccessException(SourceAccessException.Reason.NO_PERMISSION,
                    "KI-Freigabe gescheitert (" + failed.getClass().getSimpleName() + ")", failed);
        }
        List<SourceRef> children = new ArrayList<>();
        for (PageStock.Child child : stock.children(key, page.spaceKey())) {
            children.add(PageRefs.of(child.key(), child.title()));
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("Space", page.spaceKey());
        SourceRef read = PageRefs.of(key, page.title());
        return new FetchedContent(read, page.text(), metadata, children, Provenance.of(Classification.INTERNAL, read));
    }
}
