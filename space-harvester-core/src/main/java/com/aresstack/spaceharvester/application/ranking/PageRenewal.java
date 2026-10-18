package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@link RelevanceSearch.Renewal} über den Beschaffungs-Use-Case: liest die Seite im Hintergrund wie ein normaler
 * Abruf ({@link PageQueries#page}, mit Rechten des Aufrufers), der eine neuere Version holt, im gemeinsamen Cache
 * ablegt und damit die Anreicherungen samt Embedding neu anstößt. Dieselbe Seite wird nicht doppelt eingeplant; ist
 * der Hintergrund voll, holt der nächste Abruf oder Abgleich sie nach.
 */
public final class PageRenewal implements RelevanceSearch.Renewal {

    private static final System.Logger LOG = System.getLogger(PageRenewal.class.getName());

    private final PageQueries pages;
    private final Executor background;
    private final Set<PageKey> queued = ConcurrentHashMap.newKeySet();

    /** @param background begrenzter Hintergrund, etwa {@code Enrichments.background(100)} */
    public PageRenewal(PageQueries pages, Executor background) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.background = Objects.requireNonNull(background, "background");
    }

    @Override
    public void renew(AccessContext caller, PageKey key) {
        if (!queued.add(key)) {
            return;
        }
        try {
            background.execute(() -> {
                try {
                    pages.page(caller, key.sourceId(), key.pageId(), false);
                } catch (RuntimeException e) {
                    // Ohne Seiten-ID; die Suche hat den Treffer schon als veraltet markiert.
                    LOG.log(System.Logger.Level.INFO, "Nachladen in {0} gescheitert ({1})", key.sourceId(),
                            e.getClass().getName());
                } finally {
                    queued.remove(key);
                }
            });
        } catch (RejectedExecutionException e) {
            queued.remove(key);
        }
    }
}
