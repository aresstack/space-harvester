package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * „Neu berechnen“ einer Seite (Fundstelle 21, Architekt 21.11.2026): ein gewöhnlicher Auftrag
 * {@link JobType#ENRICHMENT} mit einer Seite, in der Auftragsliste sichtbar und abbrechbar. Ein Klick wartet nie hinter
 * Hintergrund ({@link Jobs#submitInteractive}). Gerechnet wird wie bisher {@link Enrichments#recompute}, also auch die
 * Prozessoren, die über das Netz rechnen, im Auftrag selbst; vor jedem Prozessor hält ein Abbruch die Arbeit an.
 */
public final class PageRecomputation {

    /** Angabe am Auftrag ({@link EnrichmentsAfterSync#TASK_PARAMETER}): welche Arbeit er tut. */
    public static final String TASK = "recompute-page";
    /** Angabe am Auftrag: die Seite. */
    public static final String ROOT = "root";

    private final Jobs jobs;
    private final Enrichments enrichments;
    private volatile DocumentEnrichments localDocuments;

    public void localDocuments(DocumentEnrichments localDocuments) {
        this.localDocuments = Objects.requireNonNull(localDocuments, "localDocuments");
    }

    public PageRecomputation(Jobs jobs, Enrichments enrichments) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
    }

    /**
     * Rechnet die Analysen der Seite als Auftrag neu und kehrt zurück, wenn er endet. Die Rechte prüft der Aufrufer.
     *
     * @param owner       Auftraggeber, damit die Auftragsliste ihn zeigt
     * @param processorId ein Prozessor oder {@code null} für alle
     * @throws JobCancelledException wenn der Auftrag abgebrochen oder angehalten wurde, vor oder während der Berechnung
     * @throws IllegalStateException  wenn der Auftrag ohne Ergebnis scheiterte, mit seinem Grund
     */
    public Enrichments.Run recompute(String owner, PageSnapshot snapshot, String processorId) {
        return recompute(owner, snapshot, processorId, state -> {
        });
    }

    /**
     * Wie {@link #recompute(String, PageSnapshot, String)}; {@code state} erfährt, ob der Auftrag auf einen freien Platz
     * wartet ({@link JobState#QUEUED}, Abnahme #1078: etwa hinter laufenden Folgeaufträgen) und wann er anläuft
     * ({@link JobState#RUNNING}), damit der Knopf nicht still hängt. Gerufen auf beliebigem Thread.
     */
    public Enrichments.Run recompute(String owner, PageSnapshot snapshot, String processorId,
                                     Consumer<JobState> state) {
        return execute(owner, snapshot, TASK, state, context -> recompute(snapshot, processorId, context));
    }

    /**
     * Die Prozessoren aus {@code processorIds}, die diese Seite ohne Netz rechnen können, in Ausführungsreihenfolge;
     * unbekannte entfallen. Grundlage der selbsttätigen Neuberechnung beim Öffnen: Sie stößt nie einen Netzaufruf an,
     * Netz-Prozessoren rechnet nur der ausdrückliche Klick.
     */
    public List<String> localProcessors(PageKey key, java.util.Collection<String> processorIds) {
        Objects.requireNonNull(key, "key");
        return enrichments.processorIds().stream().filter(processorIds::contains)
                .filter(id -> !enrichments.network(key, id)).toList();
    }

    /**
     * Rechnet veraltete Analysen der Seite, die die Leseansicht braucht, als derselbe Auftrag wie der Knopf
     * ({@link #recompute(String, PageSnapshot, String, Consumer)}), nur für die Prozessoren aus
     * {@link #localProcessors}: also ohne Netz. Sichtbar und abbrechbar in der Auftragsliste.
     *
     * @return {@code null}, wenn keiner davon ohne Netz rechnet; dann entsteht kein Auftrag
     * @throws JobCancelledException wie {@link #recompute(String, PageSnapshot, String, Consumer)}
     */
    public Enrichments.Run recomputeLocal(String owner, PageSnapshot snapshot, java.util.Collection<String> processorIds,
                                          Consumer<JobState> state) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<String> ids = localProcessors(snapshot.key(), processorIds);
        if (ids.isEmpty()) {
            return null;
        }
        return execute(owner, snapshot, TASK, state, context -> {
            int computed = 0;
            int current = 0;
            int failed = 0;
            for (String id : ids) {
                Enrichments.Run part = recompute(snapshot, id, context);
                if (!part.supported()) {
                    continue;
                }
                computed += part.computed();
                current += part.current();
                failed += part.failed();
            }
            return new Enrichments.Run(true, computed, current, failed);
        });
    }

    /** Execute an explicitly requested page analysis in the same interactive job lifecycle. */
    public Enrichments.Run execute(String owner, PageSnapshot snapshot, String task, Consumer<JobState> state,
                                    java.util.function.Function<JobContext, Enrichments.Run> operation) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(operation, "operation");
        AtomicReference<JobState> reported = new AtomicReference<>();
        CompletableFuture<Enrichments.Run> done = new CompletableFuture<>();
        JobStatus status = jobs.submitInteractive(JobType.ENRICHMENT, snapshot.key().sourceId(), snapshot.spaceKey(),
                null, owner, Map.of(EnrichmentsAfterSync.TASK_PARAMETER, task, ROOT, snapshot.key().pageId()),
                JobTask.blocking(context -> {
                    try {
                        report(reported, state, JobState.RUNNING);
                        context.discovered(1);
                        Enrichments.Run run = operation.apply(context);
                        enrichments.flush();
                        // Abgebrochen während des letzten Prozessors: Der Wartende erfährt den Abbruch, nicht Erfolg.
                        context.checkpoint();
                        context.processed(1);
                        if (run.failed() > 0) {
                            context.failed(1);
                        } else if (run.computed() > 0) {
                            context.written(1);
                        }
                        done.complete(run);
                    } catch (RuntimeException e) {
                        done.completeExceptionally(e);
                        throw e;
                    }
                }));
        // Abgebrochen, bevor er anlief: Die Arbeit läuft nie, der Wartende erfährt es über die Auftragsmeldungen.
        Subscription watching = jobs.subscribe(owner, new JobListener() {
            @Override
            public void changed(JobStatus changed) {
                if (!changed.id().equals(status.id())) {
                    return;
                }
                if (changed.state().terminal()) {
                    endedWithoutResult(done, changed);
                } else {
                    report(reported, state, changed.state());
                }
            }

            @Override
            public void removed(JobStatus last) {
                if (last.id().equals(status.id())) {
                    done.completeExceptionally(new JobCancelledException(status.id()));
                }
            }
        });
        try {
            JobStatus now = jobs.status(status.id(), owner);
            if (now.state().terminal()) {
                endedWithoutResult(done, now);
            } else {
                report(reported, state, now.state());
            }
            return done.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        } finally {
            watching.close();
        }
    }

    /**
     * Ein Endzustand, bevor die Arbeit ein Ergebnis lieferte (Abnahme #1078): abgebrochen oder angehalten als
     * {@link JobCancelledException}, gescheitert mit seinem Grund. Hat die Arbeit schon ein Ergebnis oder ihren eigenen
     * Fehler gemeldet, bleibt es dabei.
     */
    private static void endedWithoutResult(CompletableFuture<Enrichments.Run> done, JobStatus ended) {
        if (ended.state() == JobState.FAILED) {
            String reason = ended.error() == null ? "unbekannter Grund"
                    : ended.error().message() != null ? ended.error().message() : ended.error().code();
            done.completeExceptionally(new IllegalStateException("Neuberechnung gescheitert: " + reason));
        } else {
            done.completeExceptionally(new JobCancelledException(ended.id()));
        }
    }

    /** Meldet jeden Zustand höchstens einmal und nie rückwärts von „läuft“ auf „wartet“. */
    private static void report(AtomicReference<JobState> reported, Consumer<JobState> state, JobState now) {
        if (now != JobState.QUEUED && now != JobState.RUNNING) {
            return;
        }
        JobState before = reported.getAndUpdate(previous -> previous == JobState.RUNNING ? previous : now);
        if (before != now && before != JobState.RUNNING) {
            try {
                state.accept(now);
            } catch (RuntimeException e) {
                // Die Rückmeldung am Knopf darf die Berechnung nie aufhalten.
            }
        }
    }

    /**
     * Prozessor für Prozessor wie {@link Enrichments#recompute}, mit einem Halt vor jedem (Codex-Befund #1078): Ein
     * Abbruch während eines langsamen Embeddings startet keinen weiteren Netz-Prozessor mehr.
     */
    private Enrichments.Run recompute(PageSnapshot snapshot, String processorId, JobContext context) {
        List<String> ids = processorId != null ? List.of(processorId) : enrichments.processorIds();
        int computed = 0;
        int current = 0;
        int failed = 0;
        boolean local = com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference
                .recognizes(snapshot.payload()) && localDocuments != null;
        if (local && (processorId == null || enrichments.documentProcessorIds().contains(processorId))) {
            Enrichments.Run sections = localDocuments.recomputeLocal(snapshot, processorId, context);
            if (!sections.supported() || processorId != null) {
                return sections;
            }
            computed += sections.computed();
            current += sections.current();
            failed += sections.failed();
        }
        for (String id : ids) {
            context.checkpoint();
            if (local && enrichments.documentProcessorIds().contains(id)) {
                continue;
            }
            Enrichments.Run part = enrichments.recompute(snapshot, id);
            if (!part.supported()) {
                if (local) {
                    continue;
                }
                return part;
            }
            computed += part.computed();
            current += part.current();
            failed += part.failed();
        }
        return new Enrichments.Run(true, computed, current, failed);
    }
}
