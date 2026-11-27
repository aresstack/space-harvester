package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Die Anfragen von „Zusammenfassen“ als Unteraufträge unter dem Auftrag (Angelo 22.11.2026): je Teil und je
 * Zusammenführen ein eigener Auftrag, die nach den Auftragsplätzen („Vorbereitung“) gleichzeitig laufen, zusammen
 * höchstens {@code ai.parallel} Anfragen zugleich. Der Oberauftrag teilt auf, wartet und führt das Ergebnis zusammen;
 * er zählt je fertigem Unterauftrag einen Schritt. Ist kein Platz für Unteraufträge frei (eine Vorbereitung) oder
 * startet keiner, übernimmt er wartende selbst, damit nichts hängt. Abbrechen des Oberauftrags bricht alle ab.
 */
final class SummaryChildJobs implements ThematicSummarizer.Runner {

    private final Jobs jobs;
    private final SummaryModel model;
    private final Clock clock;
    private final JobType type;
    private final String parentId;
    private final String sourceId;
    private final String partitionKey;
    private final String owner;
    /** Bereich des Oberauftrags in Worten ({@link com.aresstack.spaceharvester.application.jobs.JobArea}), oder null. */
    private final String area;
    private final Semaphore lanes;

    /**
     * @param type         Auftragsart der Unteraufträge, dieselbe wie die des Oberauftrags
     * @param partitionKey Partition der Unteraufträge, dieselbe wie die des Oberauftrags
     */
    SummaryChildJobs(Jobs jobs, SummaryModel model, Clock clock, JobType type, String parentId, String sourceId,
                     String partitionKey, String owner) {
        this(jobs, model, clock, type, parentId, sourceId, partitionKey, owner, null);
    }

    /** @param area Bereich des Oberauftrags in Worten; die Unteraufträge zeigen denselben in der Spalte „Space“ */
    SummaryChildJobs(Jobs jobs, SummaryModel model, Clock clock, JobType type, String parentId, String sourceId,
                     String partitionKey, String owner, String area) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.model = Objects.requireNonNull(model, "model");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.type = Objects.requireNonNull(type, "type");
        this.parentId = parentId;
        this.sourceId = sourceId;
        this.partitionKey = partitionKey;
        this.owner = owner;
        this.area = area;
        this.lanes = new Semaphore(Math.max(1, model.parallel()));
    }

    @Override
    public <T, R> List<R> run(List<T> items, java.util.function.IntFunction<String> title,
                              java.util.function.BiFunction<T, com.aresstack.spaceharvester.application.jobs
                                      .JobContext, R> work,
                              com.aresstack.spaceharvester.application.jobs.JobContext context) {
        return run(items, title, null, work, context);
    }

    /**
     * @param extent wie viel ein Eintrag umfasst; der Unterauftrag zählt in dieser Einheit (Seiten eines Teils,
     *             Teilergebnisse eines Zusammenführens), sonst eine Anfrage
     */
    @Override
    public <T, R> List<R> run(List<T> items, java.util.function.IntFunction<String> title,
                              java.util.function.ToIntFunction<T> extent,
                              java.util.function.BiFunction<T, com.aresstack.spaceharvester.application.jobs
                                      .JobContext, R> work,
                              com.aresstack.spaceharvester.application.jobs.JobContext context) {
        // Folgeaufträge belegen höchstens alle Plätze bis auf einen; der Oberauftrag hält selbst einen.
        boolean room = Math.max(1, jobs.parallelPreparations() - 1) >= 2;
        int size = items.size();
        if (!room || size <= 1) {
            // Kein Platz für Unteraufträge: im Auftrag selbst, trotzdem bis zu ai.parallel zugleich.
            return ThematicSummarizer.inJob(model).run(items, title, work, context);
        }
        List<java.util.concurrent.CompletableFuture<R>> results = new ArrayList<>(size);
        List<java.util.concurrent.atomic.AtomicBoolean> claimed = new ArrayList<>(size);
        String[] children = new String[size];
        for (int i = 0; i < size; i++) {
            results.add(new java.util.concurrent.CompletableFuture<>());
            claimed.add(new java.util.concurrent.atomic.AtomicBoolean());
        }
        for (int i = 0; i < size; i++) {
            String step = title.apply(i);
            if (!room || size == 1 || step == null || step.isBlank()) {
                continue;
            }
            int index = i;
            T item = items.get(i);
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put(ChangeLog.PARENT_PARAMETER, parentId);
            parameters.put(ChangeLog.STEP_PARAMETER, step);
            if (area != null && !area.isBlank()) {
                parameters.put(com.aresstack.spaceharvester.application.jobs.JobArea.PARAMETER, area);
            }
            try {
                children[i] = jobs.submit(type, sourceId, partitionKey, null, owner, parameters,
                        JobTask.blocking(child -> {
                            if (!claimed.get(index).compareAndSet(false, true)) {
                                return;
                            }
                            if (extent == null) {
                                child.discovered(1);
                                execute(item, work, reporting(child, context, false), results.get(index));
                                return;
                            }
                            // Echter Fortschritt (Angelo 27.11.2026: „bei Unteraufträgen immer nur 1 von 1“): Der
                            // Unterauftrag zählt, was er umfasst, etwa die Seiten seines Teils; verlorene Seiten zählen
                            // als Fehlschläge davon.
                            int units = Math.max(1, extent.applyAsInt(item));
                            child.discovered(units);
                            execute(item, work, reporting(child, context, true), results.get(index));
                            child.processed(units);
                        })).id();
                try {
                    jobs.prioritize(children[i], owner);
                } catch (RuntimeException ignored) {
                    // Schon gestartet.
                }
            } catch (RuntimeException full) {
                // Kein Platz in der Warteschlange: der Oberauftrag macht diesen Teil selbst.
                children[i] = null;
            }
        }
        // Teile, die der Oberauftrag selbst gerechnet hat: Sie zählt er beim Rechnen, nicht noch einmal als Unterauftrag.
        java.util.Set<Integer> byParent = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try (AutoCloseable ignored = context.whenStopRequested(() -> cancelAll(children))) {
            Instant waitingSince = clock.instant();
            java.util.Set<Integer> counted = new java.util.HashSet<>();
            while (true) {
                boolean open = false;
                boolean anyRunning = false;
                Integer takeOver = null;
                for (int i = 0; i < size; i++) {
                    if (results.get(i).isCompletedExceptionally()) {
                        // Ein Teil scheiterte endgültig (etwa Anmeldung): die übrigen sofort beenden.
                        break;
                    }
                    if (results.get(i).isDone()) {
                        if (children[i] != null && !byParent.contains(i) && counted.add(i)) {
                            stepDone(jobs, children[i], context);
                        }
                        continue;
                    }
                    open = true;
                    if (children[i] == null) {
                        takeOver = takeOver == null ? i : takeOver;
                        continue;
                    }
                    JobStatus status = status(jobs, children[i]);
                    if (status == null || status.state().terminal()) {
                        // Abgebrochen oder gelöscht, bevor er lief: der Oberauftrag macht ihn selbst.
                        takeOver = takeOver == null ? i : takeOver;
                    } else if (status.state() == com.aresstack.spaceharvester.application.jobs.JobState.RUNNING) {
                        anyRunning = true;
                    }
                }
                if (!open || results.stream().anyMatch(
                        java.util.concurrent.CompletableFuture::isCompletedExceptionally)) {
                    break;
                }
                context.checkpoint();
                if (takeOver == null && !anyRunning && java.time.Duration.between(waitingSince,
                        clock.instant()).compareTo(ChangeLog.CHILD_START_GRACE) > 0) {
                    // Kein Unterauftrag bekommt einen Platz: den nächsten wartenden selbst übernehmen.
                    for (int i = 0; i < size && takeOver == null; i++) {
                        if (!results.get(i).isDone()) {
                            takeOver = i;
                        }
                    }
                }
                if (takeOver != null) {
                    int index = takeOver;
                    if (claimed.get(index).compareAndSet(false, true)) {
                        byParent.add(index);
                        if (children[index] != null) {
                            try {
                                jobs.cancelIfWaiting(children[index], owner, "vom Oberauftrag übernommen");
                            } catch (RuntimeException ignoredCancel) {
                                // Schon beendet.
                            }
                        }
                        execute(items.get(index), work, context, results.get(index));
                    }
                    waitingSince = clock.instant();
                    continue;
                }
                if (anyRunning) {
                    waitingSince = clock.instant();
                }
                try {
                    java.util.concurrent.CompletableFuture.anyOf(results.stream().filter(f -> !f.isDone())
                            .toArray(java.util.concurrent.CompletableFuture[]::new))
                            .get(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                    // weiter prüfen
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelAll(children);
                    throw new com.aresstack.spaceharvester.application.jobs.JobCancelledException(
                            context.jobId());
                }
            }
        } catch (RuntimeException e) {
            cancelAll(children);
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        // Vor den Fehlern der Teile: Abbrechen, Anhalten und die abgelaufene Laufzeitgrenze gehen vor. Der Zeitgeber
        // der Grenze weckt über whenStopRequested und bricht dabei die Teile ab; der Oberauftrag endet dann TIMEOUT,
        // nicht abgebrochen oder mit dem Fehler eines Teils (Abnahme #1133).
        try {
            context.checkpoint();
        } catch (RuntimeException stopped) {
            cancelAll(children);
            throw stopped;
        }
        for (java.util.concurrent.CompletableFuture<R> result : results) {
            if (result.isCompletedExceptionally()) {
                cancelAll(children);
                try {
                    result.join();
                } catch (java.util.concurrent.CompletionException e) {
                    if (e.getCause() instanceof RuntimeException cause) {
                        throw cause;
                    }
                    throw e;
                }
            }
        }
        List<R> answers = new ArrayList<>(size);
        for (java.util.concurrent.CompletableFuture<R> result : results) {
            try {
                answers.add(result.join());
            } catch (java.util.concurrent.CompletionException e) {
                cancelAll(children);
                if (e.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw e;
            }
        }
        return answers;
    }

    private <T, R> void execute(T item, java.util.function.BiFunction<T, com.aresstack.spaceharvester.application
            .jobs.JobContext, R> work, com.aresstack.spaceharvester.application.jobs.JobContext lane,
                                java.util.concurrent.CompletableFuture<R> result) {
        try {
            lanes.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            com.aresstack.spaceharvester.application.jobs.JobCancelledException cancelled =
                    new com.aresstack.spaceharvester.application.jobs.JobCancelledException(lane.jobId());
            result.completeExceptionally(cancelled);
            throw cancelled;
        }
        try {
            result.complete(work.apply(item, lane));
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
            throw e;
        } finally {
            lanes.release();
        }
    }

    /**
     * Ein Unterauftrag ist fertig: ein Schritt des Oberauftrags erledigt; hat der Teil Seiten verloren, ist der
     * Schritt gescheitert. Der Oberauftrag zählt Schritte (Teile und Zusammenführen), seine Unteraufträge Anfragen
     * und Seiten; vorher kamen deren verlorene Seiten als Fehlschläge beim Oberauftrag an und zogen sich von seinen
     * Schritten ab, aufgeklappt waren die Teile fertig, der Oberauftrag stand bei 23/43 (Angelo 23.11.2026).
     */
    private static void stepDone(Jobs jobs, String childId,
                                 com.aresstack.spaceharvester.application.jobs.JobContext context) {
        context.processed(1);
        JobStatus child = status(jobs, childId);
        if (child != null && child.progress().failed() > 0) {
            context.failed(1);
        }
    }

    /**
     * Der Unterauftrag, der Unvollständiges und seine Meldungen auch dem Oberauftrag meldet: Sonst stünde
     * „Zusammenfassen“ als vollständig da, obwohl ein Teil eine Seite verlor (wie ohne Unteraufträge). Seine
     * Fehlschläge zählen in Seiten und bleiben bei ihm; der Oberauftrag zählt den Schritt ({@link #stepDone}).
     */
    private static com.aresstack.spaceharvester.application.jobs.JobContext reporting(
            com.aresstack.spaceharvester.application.jobs.JobContext child,
            com.aresstack.spaceharvester.application.jobs.JobContext parent, boolean sized) {
        return (com.aresstack.spaceharvester.application.jobs.JobContext) java.lang.reflect.Proxy.newProxyInstance(
                com.aresstack.spaceharvester.application.jobs.JobContext.class.getClassLoader(),
                new Class<?>[] {com.aresstack.spaceharvester.application.jobs.JobContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("incomplete") || method.getName().equals("record")) {
                        method.invoke(parent, args);
                    }
                    if (sized && (method.getName().equals("discovered") || method.getName().equals("processed"))) {
                        // Anfragen und Teilungen zählen nicht; der Unterauftrag zählt seinen Umfang am Ende.
                        return null;
                    }
                    try {
                        return method.invoke(child, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static JobStatus status(Jobs jobs, String id) {
        try {
            return jobs.status(id);
        } catch (RuntimeException gone) {
            return null;
        }
    }

    private void cancelAll(String[] children) {
        for (String child : children) {
            if (child == null) {
                continue;
            }
            try {
                JobStatus status = jobs.status(child);
                if (!status.state().terminal()) {
                    jobs.cancel(child, owner);
                }
            } catch (RuntimeException ignored) {
                // Schon beendet oder gelöscht.
            }
        }
    }
}
