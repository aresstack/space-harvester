package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.run.Pieces;
import com.aresstack.spaceharvester.application.jobs.ChildJobs;
import com.aresstack.spaceharvester.application.jobs.JobContext;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Die Teile einer Stufe von Writing4j (etwa ein Quellensteckbrief je Quelle) als Unteraufträge unter dem laufenden
 * Buch-Auftrag, die Klammer mit Unteraufträgen wie bei „Zusammenfassen“ ({@link ChildJobs}, Angelo 27.11.2026): In der
 * Auftragsliste steht unter „Buch schreiben“ je Quelle eine Zeile mit ihrem Titel. Läuft gerade kein Auftrag der
 * Sitzung ({@link #bind}) oder fehlt die Auftragsverwaltung, laufen die Teile wie bisher im Auftrag selbst.
 */
final class BookPieces implements Pieces {

    private final int capacity;
    private final Function<JobContext, ChildJobs> children;
    private volatile JobContext context;

    /**
     * @param capacity Anfragen zugleich ({@code ai.parallel})
     * @param children die Unteraufträge unter dem Auftrag; {@code null}: alles im Auftrag selbst
     */
    BookPieces(int capacity, Function<JobContext, ChildJobs> children) {
        this.capacity = Math.max(1, capacity);
        this.children = children;
    }

    /** Der Auftrag, unter dem die Teile laufen, bis {@code null} gebunden wird. */
    void bind(JobContext context) {
        this.context = context;
    }

    @Override
    public <T, R> List<R> run(String stage, List<T> items, Function<T, String> title, Function<T, R> work) {
        JobContext parent = context;
        ChildJobs under = parent == null || children == null ? null : children.apply(parent);
        if (under == null) {
            return Pieces.parallel(capacity).run(stage, items, title, work);
        }
        Objects.requireNonNull(work, "work");
        return under.run(items, index -> title.apply(items.get(index)), (item, child) -> {
            // Ein abgebrochener Unterauftrag liefert nichts mehr: wie bei „Zusammenfassen“ endet damit die Stufe.
            child.checkpoint();
            R result = work.apply(item);
            child.checkpoint();
            if (child != parent) {
                // Der Unterauftrag zählt sein Stück; der Oberauftrag zählt weiter über die Schritte von Writing4j.
                child.processed(1);
            }
            return result;
        }, parent);
    }
}
