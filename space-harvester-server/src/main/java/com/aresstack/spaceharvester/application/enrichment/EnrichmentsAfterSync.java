package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.application.sync.PageChange;
import com.aresstack.spaceharvester.application.sync.RemovalCheck;
import com.aresstack.spaceharvester.application.sync.SpaceSync;
import com.aresstack.spaceharvester.application.sync.SyncCommitted;
import java.util.Objects;

/**
 * Hält die Anreicherungen nach jedem bestätigten Abgleich nach: Für jede Seite der Inventur mit Inhalt im Cache werden
 * fehlende oder veraltete Ergebnisse berechnet ({@link Enrichments#refresh}; aktuelle kosten nur die Projektion, so
 * erreichen auch neue Prozessorversionen jede Seite), für entfernte Seiten werden sie gelöscht. Seiten, deren Abgleich
 * scheiterte oder die die Inventur nicht sah, bleiben unberührt. Der Abgleich hängt nie davon ab.
 */
public class EnrichmentsAfterSync implements SyncCommitted {

    private static final System.Logger LOG = System.getLogger(EnrichmentsAfterSync.class.getName());

    private final Enrichments enrichments;
    private final SnapshotRepository snapshots;

    /** @param snapshots Cache-Speicher oder {@code null}, solange keiner angebunden ist; dann geschieht nichts */
    public EnrichmentsAfterSync(Enrichments enrichments, SnapshotRepository snapshots) {
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.snapshots = snapshots;
    }

    @Override
    public void committed(SpaceSync.SyncRun run) {
        if (snapshots == null) {
            return;
        }
        try {
            refresh(run);
        } finally {
            enrichments.flush();
        }
    }

    private void refresh(SpaceSync.SyncRun run) {
        String sourceId = run.plan().inventory().sourceId();
        int computed = 0;
        int failed = 0;
        int removed = 0;
        int skipped = 0;
        for (PageChange change : run.plan().changes()) {
            PageKey key = new PageKey(sourceId, change.pageId());
            if (change.kind() == PageChange.Kind.NOT_SEEN || run.failedPageIds().contains(change.pageId())) {
                continue;
            }
            if (change.kind() == PageChange.Kind.MISSING) {
                RemovalCheck.Result removal = run.plan().removals().get(change.pageId());
                if (removal != null && removal.verdict() == RemovalCheck.Verdict.GONE) {
                    enrichments.remove(key);
                    removed++;
                }
                continue;
            }
            try {
                PageSnapshot snapshot = snapshots.find(key).orElse(null);
                if (snapshot == null) {
                    continue;
                }
                Enrichments.Run result = enrichments.refresh(snapshot);
                computed += result.computed();
                failed += result.failed();
            } catch (RuntimeException e) {
                // Eine Seite, die sich nicht lesen oder ablegen lässt, hält die übrigen nicht auf.
                skipped++;
                LOG.log(System.Logger.Level.WARNING, "Anreicherung von {0}/{1} übersprungen ({2})", sourceId,
                        change.pageId(), e.getClass().getName());
            }
        }
        LOG.log(System.Logger.Level.INFO, "Anreicherungen nach Abgleich {0}/{1}: {2} berechnet, davon {3} "
                        + "fehlgeschlagen, {4} Seiten entfernt, {5} übersprungen", sourceId,
                run.plan().inventory().spaceKey(), computed, failed, removed, skipped);
    }
}
