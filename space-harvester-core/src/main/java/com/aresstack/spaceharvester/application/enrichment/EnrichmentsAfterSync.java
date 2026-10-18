package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.PageTextIndexing;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.application.sync.PageChange;
import com.aresstack.spaceharvester.application.sync.RemovalCheck;
import com.aresstack.spaceharvester.application.sync.SpaceSync;
import com.aresstack.spaceharvester.application.sync.SyncCommitted;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Hält die Anreicherungen nach jedem bestätigten Abgleich nach: Für jede Seite der Inventur mit Inhalt im Cache werden
 * fehlende oder veraltete Ergebnisse berechnet ({@link Enrichments#refresh}; aktuelle kosten nur die Projektion, so
 * erreichen auch neue Prozessorversionen jede Seite), für entfernte Seiten werden sie gelöscht. Seiten, deren Abgleich
 * scheiterte oder die die Inventur nicht sah, bleiben unberührt.
 *
 * <p>Mit {@link Jobs} läuft das als eigener Auftrag {@link JobType#ENRICHMENT} für den Auslöser des Abgleichs oder
 * Exports: Abgleich und Export warten nicht darauf. Der Auftrag rechnet Prozessor für Prozessor, die Phase nennt den
 * aktiven ({@code Anreichern: entities (3/6)}); {@code discovered} zählt Seiten mal Prozessoren, {@code processed} die
 * erledigten Schritte, {@code written} die neu berechneten und {@code failed} die fehlgeschlagenen Ergebnisse. Er
 * lässt sich wie jeder Auftrag abbrechen; bis dahin Berechnetes bleibt, der Rest folgt beim nächsten Abgleich. Nimmt
 * die Auftragsverwaltung nichts mehr an, entfällt die Anreicherung dieses Laufs ebenso bis zum nächsten.
 *
 * <p>Mit {@link PageTextIndexing} hält derselbe Auftrag vor den Prozessoren den Volltextindex nach (#180, Phase
 * {@code Volltextindex}): neue und geänderte Projektionen hinein, entfernte Seiten heraus; er zählt nicht in
 * {@code discovered}.
 */
public class EnrichmentsAfterSync implements SyncCommitted {

    private static final System.Logger LOG = System.getLogger(EnrichmentsAfterSync.class.getName());
    private static final String COMPONENT = "Enrichments";

    private final Enrichments enrichments;
    private final SnapshotRepository snapshots;
    private final Jobs jobs;
    private final PageTextIndexing texts;

    /** Im aufrufenden Thread, ohne eigenen Auftrag. */
    public EnrichmentsAfterSync(Enrichments enrichments, SnapshotRepository snapshots) {
        this(enrichments, snapshots, null);
    }

    /**
     * @param snapshots Cache-Speicher oder {@code null}, solange keiner angebunden ist; dann geschieht nichts
     * @param jobs      Auftragsverwaltung für den eigenen Auftrag oder {@code null}, dann im aufrufenden Thread
     */
    public EnrichmentsAfterSync(Enrichments enrichments, SnapshotRepository snapshots, Jobs jobs) {
        this(enrichments, snapshots, jobs, null);
    }

    /** @param texts Volltextindex oder {@code null} */
    public EnrichmentsAfterSync(Enrichments enrichments, SnapshotRepository snapshots, Jobs jobs,
                                PageTextIndexing texts) {
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.snapshots = snapshots;
        this.jobs = jobs;
        this.texts = texts;
    }

    @Override
    public void committed(SpaceSync.SyncRun run) {
        committed(run, null);
    }

    @Override
    public void committed(SpaceSync.SyncRun run, String owner) {
        if (snapshots == null) {
            return;
        }
        String sourceId = run.plan().inventory().sourceId();
        String spaceKey = run.plan().inventory().spaceKey();
        if (jobs == null) {
            refresh(run, null);
            return;
        }
        try {
            jobs.submit(JobType.ENRICHMENT, sourceId, spaceKey, owner, JobTask.blocking(context -> refresh(run,
                    context)));
        } catch (JobCapacityExceededException | IllegalStateException e) {
            LOG.log(System.Logger.Level.INFO, "Anreicherung nach Abgleich {0}/{1} nicht eingeplant ({2}); der "
                    + "nächste Abgleich holt sie nach", sourceId, spaceKey, e.getClass().getSimpleName());
        }
    }

    private void refresh(SpaceSync.SyncRun run, JobContext context) {
        String sourceId = run.plan().inventory().sourceId();
        List<PageKey> pages = new ArrayList<>();
        int removed = 0;
        for (PageChange change : run.plan().changes()) {
            PageKey key = new PageKey(sourceId, change.pageId());
            if (change.kind() == PageChange.Kind.NOT_SEEN || run.failedPageIds().contains(change.pageId())) {
                continue;
            }
            if (change.kind() == PageChange.Kind.MISSING) {
                RemovalCheck.Result removal = run.plan().removals().get(change.pageId());
                if (removal != null && removal.verdict() == RemovalCheck.Verdict.GONE) {
                    enrichments.remove(key);
                    if (texts != null) {
                        texts.remove(key);
                    }
                    removed++;
                }
                continue;
            }
            pages.add(key);
        }
        if (texts != null) {
            index(pages, context);
        }
        List<String> processors = enrichments.processorIds();
        if (context != null) {
            context.discovered((long) pages.size() * processors.size());
        }
        int computed = 0;
        int failed = 0;
        int skipped = 0;
        try {
            for (int i = 0; i < processors.size(); i++) {
                String processor = processors.get(i);
                if (context != null) {
                    context.phase(COMPONENT, "Anreichern: " + processor + " (" + (i + 1) + "/" + processors.size()
                            + ")");
                }
                for (PageKey key : pages) {
                    if (context != null) {
                        context.checkpoint();
                    }
                    try {
                        PageSnapshot snapshot = snapshots.find(key).orElse(null);
                        if (snapshot != null) {
                            Enrichments.Run result = enrichments.refresh(snapshot, processor);
                            computed += result.computed();
                            failed += result.failed();
                            if (context != null) {
                                context.written(result.computed() - result.failed());
                                context.failed(result.failed());
                            }
                        }
                    } catch (RuntimeException e) {
                        // Eine Seite, die sich nicht lesen oder ablegen lässt, hält die übrigen nicht auf.
                        skipped++;
                        LOG.log(System.Logger.Level.WARNING, "Anreicherung {0} einer Seite in {1} übersprungen ({2})",
                                processor, sourceId, e.getClass().getName());
                    }
                    if (context != null) {
                        context.processed(1);
                    }
                }
                // Was fertig ist, bleibt auch nach einem Abbruch.
                enrichments.flush();
            }
        } finally {
            enrichments.flush();
            LOG.log(System.Logger.Level.INFO, "Anreicherungen nach Abgleich {0}/{1}: {2} berechnet, davon {3} "
                            + "fehlgeschlagen, {4} Seiten entfernt, {5} übersprungen", sourceId,
                    run.plan().inventory().spaceKey(), computed, failed, removed, skipped);
        }
    }

    /** Volltextindex der Seiten; aktuelle Projektionen kosten nur die Projektion. */
    private void index(List<PageKey> pages, JobContext context) {
        if (context != null) {
            context.phase(COMPONENT, "Volltextindex");
        }
        try {
            for (PageKey key : pages) {
                if (context != null) {
                    context.checkpoint();
                }
                try {
                    snapshots.find(key).ifPresent(texts::index);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Volltext einer Seite in {0} übersprungen ({1})",
                            key.sourceId(), e.getClass().getName());
                }
            }
        } finally {
            texts.flush();
        }
    }
}
