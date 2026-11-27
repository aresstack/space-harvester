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

    /** @param phaseOf verständlicher Name eines Schritts für die Auftragsliste */
    StepProgress(JobContext context, String title, Function<String, String> phaseOf) {
        this.context = Objects.requireNonNull(context, "context");
        this.title = Objects.requireNonNull(title, "title");
        this.phaseOf = Objects.requireNonNull(phaseOf, "phaseOf");
    }

    void step(String step, long done, long total) {
        if (done == 0) {
            reported.put(step, 0L);
            context.discovered(total);
            context.phase(title, phaseOf.apply(step));
            return;
        }
        long[] delta = new long[1];
        reported.compute(step, (name, last) -> {
            long before = last == null ? 0 : last;
            delta[0] = Math.max(0, done - before);
            return Math.max(before, done);
        });
        if (delta[0] > 0) {
            context.processed(delta[0]);
        }
    }
}
