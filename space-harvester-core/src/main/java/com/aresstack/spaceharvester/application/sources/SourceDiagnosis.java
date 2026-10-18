package com.aresstack.spaceharvester.application.sources;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Ergebnis der Verbindungsdiagnose einer Quelle (#189): je Stufe ein Schritt, in der Reihenfolge der Stufen. */
public record SourceDiagnosis(List<ConnectionStep> steps) {

    public SourceDiagnosis {
        steps = List.copyOf(steps);
    }

    /** Ob kein Schritt gescheitert ist. */
    public boolean successful() {
        return steps.stream().noneMatch(ConnectionStep::failed);
    }

    /** Der erste gescheiterte Schritt. */
    public Optional<ConnectionStep> failure() {
        return steps.stream().filter(ConnectionStep::failed).findFirst();
    }

    /** Eine Zeile für Protokoll und Statuszeile, z. B. {@code Proxy-Route ok, …, TLS failed}. */
    public String summary() {
        StringBuilder text = new StringBuilder();
        for (ConnectionStep step : steps) {
            text.append(text.isEmpty() ? "" : ", ").append(step.stage().label()).append(' ')
                    .append(step.status().name().toLowerCase(Locale.ROOT));
        }
        return text.toString();
    }
}
