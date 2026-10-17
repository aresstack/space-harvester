package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Legt eine nachgeladene Seite im gemeinsamen Cache ab (ADR 0010): native Payload, Revision, Hashes und Kopfdaten, wie
 * sie die Quelle mit der Quellenidentität geliefert hat, nie etwas Benutzerbezogenes. Danach ist sie über Cache und
 * Suche sichtbar; die Generation ihres Space ändert sich dadurch nicht.
 *
 * <p>Blockiert nie: Schreibt gerade ein Abgleich, wird nicht abgelegt und {@code false} gemeldet; die Seite wird dann
 * trotzdem ausgeliefert, und der Abgleich bzw. der nächste Abruf legt sie ab. Abgelegt wird nur, solange der Cache
 * noch den Stand von vor dem Abruf hat ({@link #state}: Kopfdaten und Payload); ein inzwischen abgelegter neuerer
 * Stand wird nie überschrieben, auch keine neueren Kopfdaten eines Abgleichs.
 */
public interface PageCacheFill {

    /** Ohne Cache-Speicher: nichts wird abgelegt. */
    PageCacheFill NONE = of(key -> null, (snapshot, expectedState) -> false);

    /**
     * Der Stand der Seite im Cache als Vergleichswert für {@link #store}: Kopfdaten samt Beobachtungszeit und Payload,
     * auch wenn nur Kopfdaten bekannt sind; {@code null}, wenn der Cache die Seite nicht kennt.
     */
    String state(PageKey key);

    /**
     * Legt ab, wenn die Seite im Cache noch {@code expectedState} hat.
     *
     * @param expectedState {@link #state} vor dem Abruf
     * @return ob die Seite abgelegt wurde
     */
    boolean store(PageSnapshot snapshot, String expectedState);

    static PageCacheFill of(Function<PageKey, String> state, BiPredicate<PageSnapshot, String> store) {
        return new PageCacheFill() {
            @Override
            public String state(PageKey key) {
                return state.apply(key);
            }

            @Override
            public boolean store(PageSnapshot snapshot, String expectedState) {
                return store.test(snapshot, expectedState);
            }
        };
    }
}
