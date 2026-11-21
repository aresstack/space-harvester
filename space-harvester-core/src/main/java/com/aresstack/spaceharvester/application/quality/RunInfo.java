package com.aresstack.spaceharvester.application.quality;

import java.time.Instant;
import java.util.Objects;

/**
 * Kopf eines Ergebnisses: womit gemessen wurde. Enthält keine Inhalte.
 *
 * @param version       App-Version
 * @param profile       Suchprofil in Kurzform (Varianten, Ranking, Budgets)
 * @param started       Start des Laufs
 * @param suite         Dateiname des Testbestands
 * @param contextSize   Treffer im Chat-Kontext
 * @param relevantScore Schwelle „brauchbar“
 * @param total         Fragen im Testbestand
 * @param cancelled     abgebrochen
 */
public record RunInfo(String version, String profile, Instant started, String suite, int contextSize,
                      double relevantScore, int total, boolean cancelled) {

    public RunInfo {
        version = clean(version);
        profile = clean(profile);
        suite = clean(suite);
        Objects.requireNonNull(started, "started");
    }

    static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\t\\r\\n]+", " ").strip();
    }
}
