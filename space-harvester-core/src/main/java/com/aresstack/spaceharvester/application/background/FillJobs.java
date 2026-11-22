package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.acquisition.BackgroundFill;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Die Hintergrundfüllung als Hintergrundauftrag „{@value #TITLE}“ (Fundstelle 12, Angelo 21.11.2026: nichts läuft
 * unsichtbar): je Quelle und Space ein Teil, solange dort Lücken warten oder laden. Die Füllung selbst bleibt, wie sie
 * ist ({@link BackgroundFill}, über den Planer); der Teil rechnet nichts, er zählt ihre Abrufe (geladen, gescheitert,
 * wartend) und wartet über ihre Rückrufe, ohne Thread (ADR 0027). Darum läuft er auf eigener Spur
 * ({@link BackgroundKind#ownLane()}) und belegt keinen Platz unter „Vorbereitung“.
 *
 * <ul>
 *   <li>Anhalten: Der Space ruht, bis der Teil fortgesetzt wird.</li>
 *   <li>Abbrechen: Der Space ruht bis zum nächsten abgeschlossenen Abgleich. Abgeleitet aus den Aufträgen, nicht im
 *       Speicher gehalten, übersteht es den Neustart: Ein abgebrochener Teil, jünger als der letzte abgeschlossene
 *       Abgleich des Space oder der ganzen Quelle, hält ihn ruhig.</li>
 *   <li>Gescheiterte Abrufe stehen rot mit Grund im Teil; endet er mit Fehlern, scheitert er (die Füllung versucht die
 *       Seiten nach ihrer Wartezeit erneut).</li>
 * </ul>
 *
 * Bei nur einem Platz ({@link BackgroundJobs#canRun()}) bleibt die Füllung unsichtbar wie bisher.
 */
public final class FillJobs implements BackgroundFill.Observer, AutoCloseable {

    /** Kennung der Art. */
    public static final String KIND = "fill";
    /** Titel der Gruppe. */
    public static final String TITLE = "Hintergrundfüllung";
    /** Fehlercode: Seiten ließen sich nicht laden. */
    public static final String PAGES_MISSING = PageLoads.PAGES_MISSING;
    private static final String COMPONENT = "background-fill";

    private final BackgroundJobs background;
    private final Object monitor = new Object();
    /** Laufende Teile je Space, mit ihrem Abschluss; unter {@link #monitor}. */
    private final Map<BackgroundFill.Where, Live> live = new HashMap<>();
    /** Spaces mit wartendem oder laufendem Teil; unter {@link #monitor}. */
    private final Set<BackgroundFill.Where> open = new HashSet<>();
    /** Spaces, deren Teil Anhalten oder Abbrechen angefordert hat; unter {@link #monitor}. */
    private final Set<BackgroundFill.Where> stopping = new HashSet<>();
    /** Zählungen von Abrufen, die endeten, bevor ihr Teil lief; unter {@link #monitor}. */
    private final Map<BackgroundFill.Where, Early> early = new HashMap<>();
    /** Spaces mit angehaltenem Teil; unter {@link #monitor}. */
    private final Set<BackgroundFill.Where> paused = new HashSet<>();
    /** Ende des jüngsten abgebrochenen Teils je Space; unter {@link #monitor}. */
    private final Map<BackgroundFill.Where, Instant> cancelled = new HashMap<>();
    /** Ende des jüngsten abgeschlossenen Abgleichs je Quelle und Space ({@code null}: ganze Quelle). */
    private final Map<BackgroundFill.Where, Instant> synced = new HashMap<>();
    private final Subscription parts;
    private final Subscription syncs;
    private volatile BackgroundFill fill;

    /** Was vor dem Lauf des Teils endete: wird beim Start nachgemeldet, nichts verschwindet still. */
    private static final class Early {

        long written;
        long failed;
        final List<Throwable> failures = new java.util.ArrayList<>();
    }

    /** Ein laufender Teil. */
    private static final class Live {

        final JobContext context;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        long failed;
        long processed;
        long discovered;

        Live(JobContext context) {
            this.context = context;
        }
    }

    /**
     * Meldet die Art an und liest einmal, welche Spaces ruhen (angehaltene Teile, abgebrochene jünger als der letzte
     * Abgleich); danach folgt sie den Auftragsmeldungen, ohne Abfragen.
     */
    public FillJobs(BackgroundJobs background, Jobs jobs) {
        this.background = Objects.requireNonNull(background, "background");
        Objects.requireNonNull(jobs, "jobs");
        background.register(new BackgroundKind(KIND, this::work, () -> true, false, true));
        synchronized (monitor) {
            for (JobStatus status : jobs.listAll(JobType.SYNC)) {
                if (status.state() == JobState.COMPLETED) {
                    noteSync(status);
                }
            }
            for (JobStatus part : background.parts(KIND)) {
                track(part);
            }
        }
        this.parts = background.subscribe(KIND, status -> {
            boolean woke;
            synchronized (monitor) {
                woke = track(status);
            }
            if (woke) {
                wakeFill();
            }
        }, last -> {
            synchronized (monitor) {
                open.remove(where(last));
            }
        });
        this.syncs = jobs.subscribeAll(new JobListener() {
            @Override
            public void changed(JobStatus status) {
                if (status.type() == JobType.SYNC && status.state() == JobState.COMPLETED) {
                    boolean woke;
                    synchronized (monitor) {
                        woke = noteSync(status);
                    }
                    if (woke) {
                        // Abbrechen gilt bis zum nächsten Abgleich: jetzt wieder suchen.
                        wakeFill();
                    }
                }
            }
        });
    }

    /** Hängt die Füllung an: Sie meldet ihre Abrufe hierher und lässt ruhende Spaces aus. */
    public void attach(BackgroundFill fill, Function<PageKey, Optional<String>> spaceOf) {
        this.fill = Objects.requireNonNull(fill, "fill");
        fill.observe(spaceOf, this, this::resting);
    }

    private void wakeFill() {
        BackgroundFill current = fill;
        if (current != null) {
            current.wake();
        }
    }

    private static BackgroundFill.Where where(JobStatus status) {
        return new BackgroundFill.Where(status.sourceId() == null ? "" : status.sourceId(), status.partitionKey());
    }

    /** Unter {@link #monitor}: der Zustand eines Teils; {@code true}, wenn sein Space nicht mehr ruht. */
    private boolean track(JobStatus part) {
        BackgroundFill.Where where = where(part);
        boolean wasResting = restingLocked(where);
        switch (part.state()) {
            case QUEUED, RUNNING -> {
                open.add(where);
                paused.remove(where);
            }
            case PAUSED -> {
                open.remove(where);
                stopping.remove(where);
                paused.add(where);
            }
            case CANCELLED -> {
                open.remove(where);
                stopping.remove(where);
                paused.remove(where);
                Instant at = part.finishedAt();
                if (at != null) {
                    cancelled.merge(where, at, (a, b) -> a.isAfter(b) ? a : b);
                }
            }
            default -> {
                open.remove(where);
                stopping.remove(where);
                paused.remove(where);
            }
        }
        return wasResting && !restingLocked(where);
    }

    /** Unter {@link #monitor}: merkt einen abgeschlossenen Abgleich; {@code true}, wenn dadurch ein Space aufwacht. */
    private boolean noteSync(JobStatus sync) {
        if (sync.sourceId() == null || sync.finishedAt() == null) {
            return false;
        }
        Set<BackgroundFill.Where> before = new HashSet<>();
        cancelled.keySet().stream().filter(this::restingLocked).forEach(before::add);
        synced.merge(new BackgroundFill.Where(sync.sourceId(), sync.partitionKey()), sync.finishedAt(),
                (a, b) -> a.isAfter(b) ? a : b);
        return before.stream().anyMatch(where -> !restingLocked(where));
    }

    /** Ob ein Space ruht (angehalten, im Anhalten oder abgebrochen seit dem letzten Abgleich). */
    public boolean resting(BackgroundFill.Where where) {
        synchronized (monitor) {
            return restingLocked(where);
        }
    }

    private boolean restingLocked(BackgroundFill.Where where) {
        if (stopping.contains(where) || paused.contains(where)) {
            return true;
        }
        Instant stopped = cancelled.get(where);
        if (stopped == null) {
            return false;
        }
        Instant space = synced.get(where);
        Instant source = synced.get(new BackgroundFill.Where(where.sourceId(), null));
        Instant last = space == null ? source : source == null ? space : space.isAfter(source) ? space : source;
        return last == null || stopped.isAfter(last);
    }

    @Override
    public void handed(BackgroundFill.Where where) {
        Live part;
        boolean start;
        synchronized (monitor) {
            part = live.get(where);
            start = part == null && !open.contains(where) && background.canRun();
            if (start) {
                // Vor dem Start vermerkt, damit die nächste Seite keinen zweiten Teil anlegt.
                open.add(where);
            }
        }
        if (part != null) {
            count(part, where, 0);
            return;
        }
        if (start) {
            try {
                // Liegt schon ein offener Teil (etwa nach dem Neustart fortgesetzt), entsteht kein zweiter.
                background.startUnlessRunning(KIND, TITLE, new BackgroundPart.Draft(
                        where.sourceId(), where.spaceKey(), Map.of()));
            } catch (RuntimeException e) {
                synchronized (monitor) {
                    open.remove(where);
                }
            }
        }
    }

    @Override
    public void completed(BackgroundFill.Where where, boolean stored, Throwable failure) {
        Live part;
        synchronized (monitor) {
            part = live.get(where);
            if (part != null) {
                part.processed++;
                if (!stored) {
                    part.failed++;
                }
            }
        }
        if (part == null) {
            synchronized (monitor) {
                if (open.contains(where)) {
                    // Der Teil ist angelegt, läuft aber noch nicht: beim Start nachmelden.
                    Early tally = early.computeIfAbsent(where, ignored -> new Early());
                    if (stored) {
                        tally.written++;
                    } else {
                        tally.failed++;
                        if (tally.failures.size() < 20) {
                            tally.failures.add(failure);
                        }
                    }
                }
            }
            return;
        }
        count(part, where, 0);
        part.context.processed(1);
        if (stored) {
            part.context.written(1);
            return;
        }
        part.context.failed(1);
        // Rot mit Grund: nichts verschwindet still.
        part.context.record(new JobEvent.Draft(JobEvent.Severity.ERROR, JobEvent.Category.SOURCE, COMPONENT,
                PAGES_MISSING, "Eine Seite ließ sich nicht laden: " + reason(failure), null, null, failure));
    }

    /**
     * Hält „gefunden“ bei erledigt plus wartend, damit der Teil zeigt, wie viele Seiten noch warten; nie unter
     * {@link #monitor} gerufen, weil die Füllung ihre eigene Sperre nimmt.
     */
    private void count(Live part, BackgroundFill.Where where, int adjust) {
        BackgroundFill current = fill;
        int waiting = current == null ? 0 : Math.max(0, current.waiting(where) + adjust);
        long more;
        synchronized (monitor) {
            long target = part.processed + waiting;
            more = target - part.discovered;
            if (more > 0) {
                part.discovered = target;
            }
        }
        if (more > 0) {
            part.context.discovered(more);
        }
    }

    private static String reason(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SourceAccessException access) {
                return access.reason().name();
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return failure == null ? "unbekannt" : failure.getClass().getSimpleName();
    }

    @Override
    public void idle(BackgroundFill.Where where) {
        Live part;
        synchronized (monitor) {
            part = live.remove(where);
        }
        if (part != null) {
            finish(part);
        }
    }

    private static void finish(Live part) {
        if (part.failed > 0) {
            part.done.completeExceptionally(new JobFailedException(PAGES_MISSING, part.failed
                    + (part.failed == 1 ? " Seite ließ" : " Seiten ließen") + " sich nicht laden; die Füllung versucht "
                    + "sie nach ihrer Wartezeit erneut"));
        } else {
            part.done.complete(null);
        }
    }

    private JobTask work(BackgroundPart part) {
        BackgroundFill.Where where = new BackgroundFill.Where(part.sourceId() == null ? "" : part.sourceId(),
                part.partitionKey());
        return context -> {
            Live mine = new Live(context);
            boolean woke;
            Early tally;
            synchronized (monitor) {
                Live before = live.put(where, mine);
                if (before != null) {
                    before.done.complete(null);
                }
                open.add(where);
                stopping.remove(where);
                woke = paused.remove(where);
                tally = early.remove(where);
                if (tally != null) {
                    mine.processed = tally.written + tally.failed;
                    mine.failed = tally.failed;
                }
            }
            if (tally != null) {
                context.processed(tally.written + tally.failed);
                context.written(tally.written);
                context.failed(tally.failed);
                tally.failures.forEach(failure -> context.record(new JobEvent.Draft(JobEvent.Severity.ERROR,
                        JobEvent.Category.SOURCE, COMPONENT, PAGES_MISSING, "Eine Seite ließ sich nicht laden: "
                        + reason(failure), null, null, failure)));
            }
            context.whenStopRequested(() -> {
                synchronized (monitor) {
                    stopping.add(where);
                    live.remove(where, mine);
                }
                BackgroundFill current = fill;
                if (current != null) {
                    current.rest(where);
                }
                try {
                    context.checkpoint();
                    mine.done.complete(null);
                } catch (RuntimeException e) {
                    mine.done.completeExceptionally(e);
                }
            });
            BackgroundFill current = fill;
            int waiting = current == null || !current.enabled() ? 0 : current.waiting(where);
            if (waiting == 0) {
                // Fortgesetzt, aber dort wartet nichts (mehr): fertig; die nächste Suche im Katalog legt neu an.
                synchronized (monitor) {
                    live.remove(where, mine);
                }
                count(mine, where, 0);
                finish(mine);
            } else {
                count(mine, where, 0);
            }
            if (woke) {
                wakeFill();
            }
            return mine.done;
        };
    }

    @Override
    public void close() {
        parts.close();
        syncs.close();
    }
}
