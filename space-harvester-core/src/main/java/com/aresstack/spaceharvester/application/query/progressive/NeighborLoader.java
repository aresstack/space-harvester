package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Lädt Nachbarn der Suchmethode „Nachbarseiten“ (R2) nach, die lokal fehlen oder nur mit Kopfdaten liegen: im
 * Hintergrund, mit den Rechten des Aufrufers, ohne die Suche aufzuhalten. Was sich laden lässt, prüft die Suche danach
 * mit derselben Anfrage wie jeden Nachbarn; was der Aufrufer nicht lesen darf, kommt nie in den Cache seiner Suche.
 */
@FunctionalInterface
public interface NeighborLoader {

    /** Lädt nichts. */
    NeighborLoader NONE = (caller, pages) -> Optional.empty();

    /**
     * Beginnt das Laden der Seiten, deren Inhalt nicht im Cache liegt; die übrigen lässt es aus.
     *
     * @return der laufende Vorgang, leer, wenn nichts zu laden ist oder Laden nicht möglich
     */
    Optional<Load> load(AccessContext caller, Collection<PageKey> pages);

    /** Ein laufendes Nachladen. */
    interface Load {

        /** Kennung, unter der der Vorgang auch in der Liste der Aufträge steht. */
        String id();

        /** Wie viele Seiten geladen werden. */
        int pages();

        /**
         * Meldet {@code done} das Ende, auch nach Abbruch (Argument: ob es abgebrochen wurde); ist es schon zu Ende,
         * sofort. Observer statt Abfragen (ADR 0027).
         */
        com.aresstack.spaceharvester.application.observe.Subscription whenDone(Consumer<Boolean> done);

        /** Bricht das Laden ab; schon geladene Seiten bleiben. */
        void cancel();
    }
}
