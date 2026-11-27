package com.aresstack.spaceharvester.app.research;

import com.aresstack.spaceharvester.application.jobs.JobContext;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Fortschritt eines Auftrags der Buchfunktion über alle Schritte, nicht nur über das Lesen: Modellschritte brauchen oft
 * am längsten. Zählte nur das Lesen, stand die Anzeige danach auf 99 %, während das Modell noch prüfte (Test des
 * Produktverantwortlichen am 27.11.). Jeder Schritt meldet seine Menge beim Start als gefunden und jedes erledigte Stück
 * als bearbeitet; die Phase nennt, was gerade läuft. Gilt für „Korb füllen“ (Research4j) und „Buch schreiben“
 * (Writing4j), die beide Schritte mit {@code (Schritt, erledigt, gesamt)} melden.
 */
final class StepProgress {

    private final JobContext context;
    private final String title;
    private final Function<String, String> phaseOf;
    private final Map<String, Long> reported = new ConcurrentHashMap<>();
    private final Map<String, Long> totals = new ConcurrentHashMap<>();

    /** @param phaseOf verständlicher Name eines Schritts für die Auftragsliste */
    StepProgress(JobContext context, String title, Function<String, String> phaseOf) {
        this.context = Objects.requireNonNull(context, "context");
        this.title = Objects.requireNonNull(title, "title");
        this.phaseOf = Objects.requireNonNull(phaseOf, "phaseOf");
    }

    /**
     * Ein Stand {@code done} von {@code total} des Schritts {@code step}, aus beliebigem Thread. Der erste Stand eines
     * Schritts meldet seine Menge und die Phase, auch wenn er nicht mit 0 beginnt (das Sieb meldet erst den ersten
     * fertigen Aufruf), ein Stand 0 eine neue Runde; wächst die Menge, kommt nur der Zuwachs dazu. Erledigt zählt nie über die Menge hinaus. Vorher
     * fehlte die Menge des Siebs, und die Anzeige stand bei „306/88“ in der Phase „Quellen beschreiben“ (Angelo
     * 27.11.).
     */
    synchronized void step(String step, long done, long total) {
        long size = Math.max(0, total);
        Long known = totals.get(step);
        if (known == null || done == 0) {
            // Neuer Schritt oder neue Runde desselben Schritts (Research4j sucht in Runden).
            totals.put(step, size);
            reported.put(step, 0L);
            context.discovered(size);
            context.phase(title, phaseOf.apply(step));
        } else if (size > known) {
            totals.put(step, size);
            context.discovered(size - known);
        }
        long capped = Math.min(Math.max(0, done), totals.get(step));
        long before = reported.getOrDefault(step, 0L);
        if (capped > before) {
            reported.put(step, capped);
            context.processed(capped - before);
        }
    }
}
