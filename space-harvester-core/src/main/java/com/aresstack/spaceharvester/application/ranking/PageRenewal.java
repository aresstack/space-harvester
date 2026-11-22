package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.background.PageLoads;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.observe.Coalescer;
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
 *
 * <p>Mit {@link PageLoads} ist das Nachladen ein Hintergrundauftrag „{@value #TITLE}“ (Fundstelle 10, Abnahme PR 4):
 * Die veralteten Treffer eines Aufrufers sammeln sich kurz ({@link Coalescer#INTERVAL}) und gehen dann als eine Gruppe
 * an {@link PageLoads#join(AccessContext, String, java.util.Collection, boolean)}, auch für Benutzer mit Anmeldung.
 */
public final class PageRenewal implements RelevanceSearch.Renewal, AutoCloseable {

    /** Titel der Gruppe in der Auftragsliste. */
    public static final String TITLE = "Veraltete Treffer nachladen";

    private static final System.Logger LOG = System.getLogger(PageRenewal.class.getName());

    private final PageQueries pages;
    private final Executor background;
    private final Set<PageKey> queued = ConcurrentHashMap.newKeySet();
    private final PageLoads loads;
    private final Coalescer<AccessContext, PageKey> collected;

    /** @param background begrenzter Hintergrund, etwa {@code Enrichments.background(100)} */
    public PageRenewal(PageQueries pages, Executor background) {
        this(pages, background, null);
    }

    /** @param loads Hintergrundaufträge „Seiten nachladen“, oder {@code null} für den bisherigen Hintergrund */
    public PageRenewal(PageQueries pages, Executor background, PageLoads loads) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.background = Objects.requireNonNull(background, "background");
        this.loads = loads;
        this.collected = loads == null ? null : new Coalescer<>("page-renewal", this::load);
    }

    @Override
    public void renew(AccessContext caller, PageKey key) {
        if (collected != null) {
            collected.mark(caller, java.util.List.of(key));
            return;
        }
        if (!queued.add(key)) {
            return;
        }
        try {
            background.execute(() -> {
                try {
                    // ADR 0030: die Suche hat den veralteten Treffer gemeldet.
                    ChangeTrigger.SEARCH.run(() -> pages.page(caller, key.sourceId(), key.pageId(), false));
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

    /** Die gesammelten veralteten Treffer eines Aufrufers als eine Gruppe; Seiten, die schon nachladen, schließen sich an. */
    private void load(AccessContext caller, Set<PageKey> keys) {
        try {
            loads.join(caller, TITLE, keys, true);
        } catch (RuntimeException e) {
            // Voll oder beendet: Die nächste Suche meldet die Treffer wieder als veraltet.
            LOG.log(System.Logger.Level.INFO, "{0} veraltete Treffer nicht nachgeladen ({1})", keys.size(),
                    e.getClass().getName());
        }
    }

    @Override
    public void close() {
        if (collected != null) {
            collected.close();
        }
    }
}
