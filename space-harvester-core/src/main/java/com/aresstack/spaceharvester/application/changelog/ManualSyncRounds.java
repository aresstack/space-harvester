package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.observe.Subscription;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Setzt die Update-Marke einer Quelle (ADR 0030, Abschnitt 3): Eine Runde beginnt mit der ersten manuellen
 * Synchronisierung der Quelle und endet, wenn keine mehr läuft oder angehalten wartet; erst dann wird die Marke gesetzt.
 * Angehaltene Aufträge (auch automatisch angehaltene) halten die Runde offen; abgebrochene und gescheiterte zählen als
 * Ende. Folgt den Aufträgen über ihre Meldungen (ADR 0027), ohne Abfrage in Abständen.
 */
public final class ManualSyncRounds implements JobListener, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ManualSyncRounds.class.getName());
    private static final int REMEMBERED_ENDS = 1000;

    private final UpdateMarks marks;
    private final Clock clock;
    private final Map<String, Round> open = new HashMap<>();
    /** Schon verbuchte Enden je Auftrag (Ende-Zeitpunkt), damit eine wiederholte Meldung nicht zweimal zählt. */
    private final Map<String, Instant> ends = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
            return size() > REMEMBERED_ENDS;
        }
    };
    private volatile Subscription subscription = () -> {
    };

    private static final class Round {
        final Set<String> jobs = new HashSet<>();
        final Set<String> spaces = new TreeSet<>();
        String user;
        int failed;
        Instant end;
    }

    ManualSyncRounds(UpdateMarks marks, Clock clock) {
        this.marks = Objects.requireNonNull(marks, "marks");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Meldet sich bei {@code jobs} an und übernimmt die schon offenen manuellen Synchronisierungen (nach Neustart). */
    public static ManualSyncRounds start(Jobs jobs, UpdateMarks marks, Clock clock) {
        Objects.requireNonNull(jobs, "jobs");
        ManualSyncRounds rounds = new ManualSyncRounds(marks, clock);
        rounds.subscription = jobs.subscribeAll(rounds);
        for (JobStatus status : jobs.listAll(JobType.SYNC)) {
            if (manual(status) && open(status.state())) {
                rounds.joined(status);
            }
        }
        return rounds;
    }

    /** Ob der Auftrag eine manuelle Synchronisierung ist. */
    static boolean manual(JobStatus status) {
        return status.type() == JobType.SYNC && status.parameters() != null
                && ChangeTrigger.parse(status.parameters().get(ChangeTrigger.PARAMETER)) == ChangeTrigger.MANUAL;
    }

    private static boolean open(JobState state) {
        return state == JobState.QUEUED || state == JobState.RUNNING || state == JobState.PAUSED;
    }

    @Override
    public void changed(JobStatus status) {
        if (!manual(status)) {
            return;
        }
        if (open(status.state())) {
            joined(status);
            return;
        }
        synchronized (this) {
            if (Objects.equals(ends.get(status.id()), status.finishedAt()) && !inRound(status.id())) {
                return;
            }
            ends.put(status.id(), status.finishedAt());
            if (!inRound(status.id())) {
                // Gebündelte Meldungen: ein schneller Auftrag meldet vielleicht nur sein Ende.
                joined(status);
            }
            ended(status, status.state() != JobState.COMPLETED);
        }
    }

    @Override
    public void removed(JobStatus last) {
        if (manual(last)) {
            synchronized (this) {
                if (inRound(last.id())) {
                    ended(last, true);
                }
            }
        }
    }

    private boolean inRound(String jobId) {
        return open.values().stream().anyMatch(round -> round.jobs.contains(jobId));
    }

    private synchronized void joined(JobStatus status) {
        Round round = open.computeIfAbsent(status.sourceId(), ignored -> new Round());
        if (round.jobs.isEmpty() && round.user == null) {
            round.user = status.owner();
        }
        round.jobs.add(status.id());
        if (status.partitionKey() != null) {
            round.spaces.add(status.partitionKey());
        }
    }

    private void ended(JobStatus status, boolean failed) {
        Round round = open.get(status.sourceId());
        if (round == null || !round.jobs.remove(status.id())) {
            return;
        }
        if (failed) {
            round.failed++;
        }
        Instant end = status.finishedAt() == null ? clock.instant() : status.finishedAt();
        if (round.end == null || end.isAfter(round.end)) {
            round.end = end;
        }
        if (!round.jobs.isEmpty()) {
            return;
        }
        open.remove(status.sourceId());
        try {
            boolean initial = marks.marks(status.sourceId()).isEmpty();
            marks.mark(new UpdateMark(status.sourceId(), round.end, round.user, round.spaces.stream().toList(),
                    round.failed, initial));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Update-Marke nicht gesetzt ({0})", e.getClass().getName());
        }
    }

    @Override
    public void close() {
        subscription.close();
    }
}
