package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheGenerationChangedException;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.CacheUnavailableException;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Startet Exporte eines Space aus dem Cache als Auftrag und findet ihre Artefakte. Der Export liest die Seitenliste
 * einer Generation stapelweise, lädt je Seite den Snapshot und schreibt ihn sofort weiter; im Speicher bleiben nur IDs
 * für den Baum.
 *
 * <p>Ein Artefakt ist nur {@code complete}, wenn es aus einer bestätigten Generation stammt und keine Seite fehlt. Ein
 * abgebrochener oder gescheiterter Export veröffentlicht nichts.
 *
 * <p>Artefakte leben so lange wie ihr Auftrag ({@code space-harvester.jobs.retention}): {@link #purge()} entfernt
 * beim Start und vor jedem neuen Export Artefakte ohne Auftrag sowie Reste von Läufen, die nie fertig wurden.
 */
public class Exports {

    /** Seiten je Lesestapel. */
    public static final int BATCH_SIZE = 100;

    private static final System.Logger LOG = System.getLogger(Exports.class.getName());

    private final Jobs jobs;
    private final PageCacheReader cache;
    private final ExportArtifactWriter writer;
    private final Clock clock;

    public Exports(Jobs jobs, PageCacheReader cache, ExportArtifactWriter writer, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clock = Objects.requireNonNull(clock, "clock");
        purgeQuietly();
    }

    /**
     * Nimmt einen Export an, ohne auf ihn zu warten; die Job-ID ist zugleich die Export-ID.
     *
     * @param owner auslösender Client; nur er sieht Auftrag und Artefakt
     * @throws com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException wenn zu viele Aufträge offen
     *                                                                                   sind
     */
    public JobStatus start(String sourceId, String spaceKey, String owner) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        purgeQuietly();
        return jobs.submit(JobType.EXPORT, sourceId, spaceKey, owner,
                JobTask.blocking(context -> export(context, sourceId, spaceKey)));
    }

    /**
     * Das veröffentlichte Artefakt des eigenen Exports oder leer, solange es keines gibt.
     *
     * @throws com.aresstack.spaceharvester.application.jobs.UnknownJobException wenn es keinen Export mit dieser ID für
     *                                                                           diesen Client gibt
     */
    public Optional<ExportArtifact> artifact(String exportId, String owner) {
        JobStatus status = jobs.status(exportId, owner);
        if (status.type() != JobType.EXPORT) {
            return Optional.empty();
        }
        return writer.find(exportId);
    }

    /**
     * Eine Datei des veröffentlichten Artefakts des eigenen Exports.
     *
     * @return leer, solange es kein Artefakt oder keine Datei dieses Namens gibt
     * @throws com.aresstack.spaceharvester.application.jobs.UnknownJobException wenn es keinen Export mit dieser ID für
     *                                                                           diesen Client gibt
     */
    public Optional<InputStream> artifactFile(String exportId, String owner, String name) {
        if (jobs.status(exportId, owner).type() != JobType.EXPORT) {
            return Optional.empty();
        }
        return writer.open(exportId, name);
    }

    /**
     * Entfernt veröffentlichte Artefakte, deren Auftrag nicht mehr existiert (nach Ablauf der Aufbewahrung oder
     * Verlust der Job-Ablage), und Artefakte im Entstehen, deren Auftrag nicht mehr läuft (Abbruch des Dienstes). Ein
     * Artefakt eines laufenden oder wartenden Exports bleibt immer.
     *
     * @return Anzahl entfernter Artefakte
     */
    public int purge() {
        jobs.purgeExpired();
        int removed = 0;
        for (String exportId : writer.published()) {
            if (!exportJob(exportId)) {
                writer.delete(exportId);
                removed++;
            }
        }
        for (String exportId : writer.unpublished()) {
            if (!running(exportId)) {
                writer.discard(exportId);
                removed++;
            }
        }
        return removed;
    }

    /** Aufräumen darf keinen Export verhindern; was liegen bleibt, entfernt der nächste Lauf. */
    private void purgeQuietly() {
        try {
            purge();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Alte Exportartefakte nicht vollständig entfernt: {0}",
                    e.getClass().getSimpleName());
        }
    }

    private boolean exportJob(String exportId) {
        try {
            return jobs.status(exportId).type() == JobType.EXPORT;
        } catch (UnknownJobException e) {
            return false;
        }
    }

    private boolean running(String exportId) {
        try {
            JobStatus status = jobs.status(exportId);
            return status.type() == JobType.EXPORT && !status.state().terminal();
        } catch (UnknownJobException e) {
            return false;
        }
    }

    private void export(JobContext context, String sourceId, String spaceKey) {
        try {
            exportGeneration(context, sourceId, spaceKey);
        } catch (CacheUnavailableException e) {
            throw new JobFailedException("CACHE_UNAVAILABLE", "Der Seitencache ist nicht lesbar", e);
        } catch (CacheGenerationChangedException e) {
            throw new JobFailedException("GENERATION_CHANGED",
                    "Die Cache-Generation wurde während des Exports ersetzt; bitte neu starten", e);
        }
    }

    private void exportGeneration(JobContext context, String sourceId, String spaceKey) {
        Instant startedAt = clock.instant();
        CacheGeneration generation = cache.currentGeneration(sourceId, spaceKey).orElseThrow(() ->
                new JobFailedException("NOT_CACHED", "Space " + spaceKey + " der Quelle " + sourceId
                        + " liegt nicht im Cache"));
        ExportScope scope = new ExportScope(sourceId, spaceKey, generation);
        List<TreeEntry> entries = new ArrayList<>();
        List<PageFailure> failures = new ArrayList<>();
        try (ExportArtifactWriter.Session session = writer.begin(context.jobId(), scope)) {
            String after = null;
            List<ListedPage> batch;
            do {
                context.checkpoint();
                batch = cache.pages(sourceId, spaceKey, generation.id(), after, BATCH_SIZE);
                context.discovered(batch.size());
                for (ListedPage listed : batch) {
                    context.checkpoint();
                    if (after != null && listed.pageId().compareTo(after) <= 0) {
                        throw new JobFailedException("INCONSISTENT_READ", "Seitenliste des Cache ist nicht "
                                + "aufsteigend sortiert (Seite " + listed.pageId() + ")");
                    }
                    after = listed.pageId();
                    PageFailure failure = exportPage(session, listed, generation);
                    context.processed(1);
                    if (failure == null) {
                        entries.add(new TreeEntry(listed.pageId(), listed.parentPageId()));
                        context.written(1);
                    } else {
                        failures.add(failure);
                        context.failed(1);
                    }
                }
            } while (batch.size() == BATCH_SIZE);
            context.checkpoint();
            PageTree tree = PageTreeBuilder.build(entries);
            List<String> warnings = new ArrayList<>();
            for (PageTree.Anomaly anomaly : tree.anomalies()) {
                warnings.add("Seite " + anomaly.pageId() + " ist im Baum Wurzel: " + switch (anomaly.kind()) {
                    case MISSING_PARENT -> "Elternseite fehlt im Export";
                    case CYCLE -> "Zyklus in der Elternbeziehung aufgebrochen";
                });
            }
            if (generation.state() != CacheState.CONFIRMED) {
                warnings.add("Cache-Generation " + generation.id() + " ist " + generation.state()
                        + "; das Artefakt ist kein bestätigter vollständiger Stand");
            }
            boolean complete = generation.state() == CacheState.CONFIRMED && failures.isEmpty();
            session.publish(tree, new ExportSummary(context.jobId(), scope, startedAt, clock.instant(),
                    entries.size(), failures, warnings, complete));
        }
    }

    /** @return {@code null}, wenn die Seite geschrieben wurde, sonst warum nicht */
    private PageFailure exportPage(ExportArtifactWriter.Session session, ListedPage listed,
                                          CacheGeneration generation) {
        if (listed.rawSha256() == null) {
            return new PageFailure(listed.pageId(), "PAYLOAD_MISSING",
                    "Für Seite " + listed.pageId() + " liegt noch keine Payload im Cache");
        }
        Optional<CachedPage> cached = cache.page(listed.header().key());
        if (cached.isEmpty()) {
            return new PageFailure(listed.pageId(), "PAYLOAD_MISSING",
                    "Seite " + listed.pageId() + " ist nicht mehr im Cache");
        }
        CachedPage page = cached.get();
        if (!page.generation().id().equals(generation.id())
                || !page.snapshot().payload().rawSha256().equals(listed.rawSha256())) {
            return new PageFailure(listed.pageId(), "CHANGED_DURING_EXPORT",
                    "Seite " + listed.pageId() + " wurde während des Exports im Cache geändert");
        }
        session.page(listed.header(), page.snapshot());
        return null;
    }
}
