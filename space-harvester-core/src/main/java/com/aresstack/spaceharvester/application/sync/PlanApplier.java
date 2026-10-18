package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Merkt die Änderungen eines {@link SyncPlan} in einer {@link StagedGeneration} vor.
 *
 * <ul>
 *   <li>{@link PageChange.Kind#NEW}, {@link PageChange.Kind#CONTENT_CHANGED}: Snapshot laden und speichern, wenn er
 *       zur Inventur passt (siehe unten).</li>
 *   <li>{@link PageChange.Kind#METADATA_CHANGED}, {@link PageChange.Kind#ATTRIBUTES_CHANGED}: nur die aktuellen
 *       Kopfdaten ersetzen, kein Inhaltsabruf.</li>
 *   <li>{@link PageChange.Kind#MISSING}: bei {@link RemovalCheck.Verdict#DELETED} entfernen, bei
 *       {@link RemovalCheck.Verdict#INACCESSIBLE} sperren ({@link StagedGeneration#withhold}), bei
 *       {@link RemovalCheck.Verdict#MOVED_OUT} nur die Kopfdaten aus der Einzelabfrage übernehmen (der Schlüssel
 *       enthält keinen Space, die Seite bleibt), sonst nichts.</li>
 *   <li>{@link SyncPlan#purges()}: gesperrte Seiten nach Ablauf der Frist endgültig entfernen.</li>
 * </ul>
 *
 * <p>Ein geladener Snapshot muss Seite, Space und (wenn die Inventur sie kennt) Revision der Inventur tragen. Weicht
 * er ab, weil sich die Seite zwischen Listung und Abruf geändert hat (ältere, neuere oder verschobene Antwort), wird
 * er nicht gespeichert und die Seite gilt als fehlgeschlagen: Die Generation behält den bisherigen Stand als veraltet,
 * und der nächste Lauf gleicht sie erneut ab. So bestätigt eine Generation nie eine Revision, deren Payload nicht
 * gespeichert ist. Ein Quellfehler beim Laden macht ebenfalls nur die betroffene Seite fehlgeschlagen.
 */
public class PlanApplier {

    private final PageContentFetcher fetcher;

    public PlanApplier(PageContentFetcher fetcher) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /** @return die Seiten, deren Abgleich fehlgeschlagen ist, für {@link SyncPlan#nextBaseline} */
    public Set<String> apply(SyncPlan plan, StagedGeneration target) {
        return apply(plan, target, SyncListener.NONE);
    }

    /**
     * Wie {@link #apply(SyncPlan, StagedGeneration)}, mit Rückmeldung je Seite und {@link SyncListener#checkpoint()}
     * vor jedem Inhaltsabruf.
     */
    public Set<String> apply(SyncPlan plan, StagedGeneration target, SyncListener listener) {
        String sourceId = plan.inventory().sourceId();
        Instant observedAt = plan.inventory().finishedAt();
        Set<String> failed = new TreeSet<>();
        for (PageChange change : plan.changes()) {
            PageKey key = new PageKey(sourceId, change.pageId());
            boolean written = true;
            switch (change.kind()) {
                case NEW, CONTENT_CHANGED -> {
                    listener.checkpoint();
                    if (!fetch(key, change.current().page(), target)) {
                        failed.add(change.pageId());
                        written = false;
                    }
                }
                case METADATA_CHANGED, ATTRIBUTES_CHANGED ->
                        target.saveHeader(header(key, change.current(), observedAt));
                case MISSING -> {
                    RemovalCheck.Result removal = plan.removals().get(change.pageId());
                    if (removal != null && removal.verdict() == RemovalCheck.Verdict.DELETED) {
                        target.remove(key);
                    } else if (removal != null && removal.verdict() == RemovalCheck.Verdict.INACCESSIBLE) {
                        target.withhold(key);
                    } else if (removal != null && removal.verdict() == RemovalCheck.Verdict.MOVED_OUT) {
                        target.saveHeader(header(key, removal.current(), observedAt));
                    } else {
                        written = false;
                    }
                }
                case UNCHANGED, NOT_SEEN -> written = false;
            }
            listener.pageDone(change, written, failed.contains(change.pageId()));
        }
        for (String pageId : plan.purges()) {
            target.remove(new PageKey(sourceId, pageId));
        }
        return failed;
    }

    private boolean fetch(PageKey key, PageMetadata expected, StagedGeneration target) {
        PageSnapshot snapshot;
        try {
            snapshot = fetcher.fetch(key.sourceId(), key.pageId());
        } catch (SourceAccessException e) {
            return false;
        }
        boolean matches = snapshot.key().equals(key)
                && expected.partitionKey().equals(snapshot.spaceKey())
                && (expected.version() == null || expected.version().equals(snapshot.revision()));
        if (matches) {
            target.save(snapshot);
        }
        return matches;
    }

    private static PageHeader header(PageKey key, PageMetadataListing.Entry entry, Instant observedAt) {
        return new PageHeader(key, entry.page(), entry.metadataHash(), entry.attributesHash(), observedAt);
    }
}
