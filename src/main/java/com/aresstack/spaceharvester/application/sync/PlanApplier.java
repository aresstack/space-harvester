package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wendet einen {@link SyncPlan} auf das {@link SnapshotRepository} an.
 *
 * <ul>
 *   <li>{@link PageChange.Kind#NEW}, {@link PageChange.Kind#CONTENT_CHANGED}: Snapshot laden und speichern.</li>
 *   <li>{@link PageChange.Kind#METADATA_CHANGED}, {@link PageChange.Kind#ATTRIBUTES_CHANGED}: nur die aktuellen
 *       Kopfdaten ersetzen, kein Inhaltsabruf.</li>
 *   <li>{@link PageChange.Kind#MISSING}: bei {@link RemovalCheck.Verdict#GONE} entfernen, bei
 *       {@link RemovalCheck.Verdict#MOVED_OUT} nur die Kopfdaten aus der Einzelabfrage übernehmen (der Schlüssel
 *       enthält keinen Space, die Seite bleibt), sonst nichts.</li>
 * </ul>
 *
 * <p>Ein Fehler der Quelle beim Laden macht nur die betroffene Seite fehlgeschlagen; ein Fehler des Speichers bricht
 * den Lauf ab, ohne dass eine Generation bestätigt wird. Mehrfaches Anwenden desselben Plans ist unschädlich.
 */
public class PlanApplier {

    private final SnapshotRepository repository;
    private final PageContentFetcher fetcher;

    public PlanApplier(SnapshotRepository repository, PageContentFetcher fetcher) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /** @return die Seiten, deren Abgleich fehlgeschlagen ist, für {@link SyncPlan#nextBaseline} */
    public Set<String> apply(SyncPlan plan) {
        String sourceId = plan.inventory().sourceId();
        Instant observedAt = plan.inventory().finishedAt();
        Set<String> failed = new TreeSet<>();
        for (PageChange change : plan.changes()) {
            PageKey key = new PageKey(sourceId, change.pageId());
            switch (change.kind()) {
                case NEW, CONTENT_CHANGED -> {
                    if (!fetch(key)) {
                        failed.add(change.pageId());
                    }
                }
                case METADATA_CHANGED, ATTRIBUTES_CHANGED ->
                        repository.saveHeader(header(key, change.current(), observedAt));
                case MISSING -> {
                    RemovalCheck.Result removal = plan.removals().get(change.pageId());
                    if (removal != null && removal.verdict() == RemovalCheck.Verdict.GONE) {
                        repository.remove(key);
                    } else if (removal != null && removal.verdict() == RemovalCheck.Verdict.MOVED_OUT) {
                        repository.saveHeader(header(key, removal.current(), observedAt));
                    }
                }
                case UNCHANGED, NOT_SEEN -> {
                }
            }
        }
        return failed;
    }

    private boolean fetch(PageKey key) {
        PageSnapshot snapshot;
        try {
            snapshot = fetcher.fetch(key.sourceId(), key.pageId());
        } catch (SourceAccessException e) {
            return false;
        }
        if (!snapshot.key().equals(key)) {
            throw new IllegalStateException("Quelle lieferte Seite " + snapshot.key() + " statt " + key);
        }
        repository.save(snapshot);
        return true;
    }

    private static PageHeader header(PageKey key, PageMetadataListing.Entry entry, Instant observedAt) {
        return new PageHeader(key, entry.page(), entry.metadataHash(), entry.attributesHash(), observedAt);
    }
}
