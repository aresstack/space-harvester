package com.aresstack.spaceharvester.application.assets;

import java.util.Objects;
import java.util.Optional;

/**
 * Ergebnis einer Extraktion als Daten (ADR 0026, Punkt 7): Status, fester Grund, ob gekürzt wurde, und wie weit gelesen
 * wurde. Ein gekürztes Ergebnis nennt immer seinen Umfang; {@link ExtractionStatus#OK} ist nie gekürzt und hat keinen
 * Grund, jeder andere Status hat einen.
 */
public record Outcome(ExtractionStatus status, Optional<StatusReason> reason, boolean truncated,
                      Optional<ReadExtent> extent) {

    public Outcome {
        Objects.requireNonNull(status, "status");
        reason = reason == null ? Optional.empty() : reason;
        extent = extent == null ? Optional.empty() : extent;
        if (status == ExtractionStatus.OK && (reason.isPresent() || truncated)) {
            throw new IllegalArgumentException("OK hat weder Grund noch Kürzung");
        }
        if (status != ExtractionStatus.OK && reason.isEmpty()) {
            throw new IllegalArgumentException("Status " + status + " braucht einen Grund");
        }
        if (truncated && extent.isEmpty()) {
            throw new IllegalArgumentException("Gekürzt ohne gelesenen Umfang");
        }
    }

    /** Vollständig gelesen. */
    public static Outcome ok(ReadExtent extent) {
        return new Outcome(ExtractionStatus.OK, Optional.empty(), false, Optional.ofNullable(extent));
    }

    /** Ohne Text, mit Grund (nicht gekürzt). */
    public static Outcome of(ExtractionStatus status, StatusReason reason) {
        return new Outcome(status, Optional.of(reason), false, Optional.empty());
    }

    /** An einer Grenze gekürzt: Was bis dahin gelesen ist, bleibt. */
    public static Outcome truncated(StatusReason reason, ReadExtent extent) {
        return new Outcome(ExtractionStatus.PARTIAL, Optional.of(reason), true, Optional.of(extent));
    }
}
