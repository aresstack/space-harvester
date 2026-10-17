package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;

/**
 * Legt eine nachgeladene Seite im gemeinsamen Cache ab (ADR 0010): native Payload, Revision, Hashes und Kopfdaten, wie
 * sie die Quelle mit der Quellenidentität geliefert hat, nie etwas Benutzerbezogenes. Danach ist sie über Cache und
 * Suche sichtbar; die Generation ihres Space ändert sich dadurch nicht.
 *
 * <p>Blockiert nie: Schreibt gerade ein Abgleich, wird nicht abgelegt und {@code false} gemeldet; die Seite wird dann
 * trotzdem ausgeliefert, und der Abgleich bzw. der nächste Abruf legt sie ab.
 */
@FunctionalInterface
public interface PageCacheFill {

    /** Ohne Cache-Speicher: nichts wird abgelegt. */
    PageCacheFill NONE = snapshot -> false;

    /** @return ob die Seite abgelegt wurde */
    boolean store(PageSnapshot snapshot);
}
