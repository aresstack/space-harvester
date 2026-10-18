package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.application.acquisition.AcquisitionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Der Stand des Beschaffungsplaners (ADR 0016), wie die Statusansicht ihn zeigt; dieselben Felder wie
 * {@link AcquisitionStatus} im Core, damit die Ansicht ohne laufenden Core testbar bleibt.
 */
public record Acquisition(boolean enabled, Pause pause, int queued, int inFlight, int foregroundQueued,
                          int foregroundRunning, int backgroundRunning, long loaded, long failed, long promoted,
                          int parallelism, Instant lastScan, List<SourceCounts> sources) {

    /** Stufe des Planers, wie der Core sie nennt. */
    public enum Pause { NONE, FOREGROUND, USER_ACTION, COOLDOWN, DISABLED, IDLE, CLOSED }

    /** Stand je Quelle. */
    public record SourceCounts(String sourceId, int queued, long loaded, long failed) {
    }

    public Acquisition {
        Objects.requireNonNull(pause, "pause");
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** Der Stand des Core, Feld für Feld. */
    static Acquisition of(AcquisitionStatus status) {
        return new Acquisition(status.enabled(), Pause.valueOf(status.pause().name()), status.queued(),
                status.inFlight(), status.foregroundQueued(), status.foregroundRunning(), status.backgroundRunning(),
                status.loaded(), status.failed(), status.promoted(), status.parallelism(), status.lastScan(),
                status.sources().stream().map(counts -> new SourceCounts(counts.sourceId(), counts.queued(),
                        counts.loaded(), counts.failed())).toList());
    }
}
