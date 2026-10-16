package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;

/**
 * Einhängestelle der Zugriffskontrolle (#14) für jede Ausgabe der Query-API: Seitenliste, Baumknoten und Einzelseite.
 * Entschieden wird an den aktuellen Kopfdaten. Eine nicht sichtbare Seite erscheint nirgends, auch nicht als ID oder Titel; ihre sichtbaren Kinder erscheinen im
 * Baum als abgelöste Wurzeln, ohne die verborgene Elternseite zu nennen.
 *
 * <p>Den Aufrufer ermittelt die Implementierung selbst (etwa aus dem Sicherheitskontext der Anfrage).
 */
@FunctionalInterface
public interface PageVisibility {

    boolean canRead(PageHeader page);

    /**
     * Alles sichtbar. Nur solange die API insgesamt keinen Zugriffsschutz hat; Strang F (#14) ersetzt das durch eine
     * fail-closed Implementierung.
     */
    static PageVisibility unrestricted() {
        return page -> true;
    }
}
