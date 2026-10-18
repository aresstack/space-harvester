package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.query.PageCacheFill;
import com.aresstack.spaceharvester.application.query.PageTextIndexing;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Objects;

/**
 * Legt eine nachgeladene Seite ab und berechnet danach ihre Anreicherungen, wie es ein Abgleich nach dem Bestätigen
 * täte ({@link EnrichmentsAfterSync}), samt Volltextindex (#180). Scheitert die Anreicherung, bleibt die Seite
 * trotzdem abgelegt.
 */
public final class EnrichingCacheFill implements PageCacheFill {

    private static final System.Logger LOG = System.getLogger(EnrichingCacheFill.class.getName());

    private final PageCacheFill store;
    private final Enrichments enrichments;
    private final PageTextIndexing texts;

    public EnrichingCacheFill(PageCacheFill store, Enrichments enrichments) {
        this(store, enrichments, null);
    }

    /** @param texts Volltextindex oder {@code null} */
    public EnrichingCacheFill(PageCacheFill store, Enrichments enrichments, PageTextIndexing texts) {
        this.store = Objects.requireNonNull(store, "store");
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.texts = texts;
    }

    @Override
    public String state(PageKey key) {
        return store.state(key);
    }

    @Override
    public boolean store(PageSnapshot snapshot, String expectedState) {
        if (!store.store(snapshot, expectedState)) {
            return false;
        }
        if (texts != null && texts.index(snapshot)) {
            texts.flush();
        }
        try {
            enrichments.refresh(snapshot);
            enrichments.flush();
        } catch (RuntimeException e) {
            // Anreicherungen sind neu berechenbar; der nächste Abgleich holt sie nach.
            LOG.log(System.Logger.Level.WARNING, "Anreicherung der nachgeladenen Seite scheiterte ({0})",
                    e.getClass().getName());
        }
        return true;
    }
}
