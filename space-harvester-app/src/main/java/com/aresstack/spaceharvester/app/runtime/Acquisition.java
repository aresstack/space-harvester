package com.aresstack.spaceharvester.app.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Der Stand des Beschaffungsplaners (ADR 0016), wie die Statusansicht ihn zeigt; dieselben Felder wie
 * {@code AcquisitionStatus} im Core, damit die Ansicht ohne Core-Typen testbar bleibt.
 */
public record Acquisition(boolean enabled, Pause pause, int queued, int inFlight, int foregroundQueued,
                          int foregroundRunning, int backgroundRunning, long loaded, long failed, long promoted,
                          int parallelism, Instant lastScan, List<SourceCounts> sources) {

    /** Stufe des Planers, wie der Core sie nennt. */
    public enum Pause { NONE, FOREGROUND, USER_ACTION, COOLDOWN, DISABLED, IDLE, CLOSED }

    /** Stand je Quelle. */
    public record SourceCounts(String sourceId, int queued, int loaded, int failed) {
    }

    public Acquisition {
        Objects.requireNonNull(pause, "pause");
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
