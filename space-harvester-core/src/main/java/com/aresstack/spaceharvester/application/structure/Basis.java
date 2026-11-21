package com.aresstack.spaceharvester.application.structure;

/**
 * Worauf eine Kante beruht. Die drei Gruppen werden nie vermischt: Wer Kanten anzeigt oder weiterverwendet, kann an
 * der Grundlage immer erkennen, ob die Quelle die Verbindung ausdrücklich enthält, ob ein Mensch eine Beziehung
 * bestätigt hat oder ob sie nur berechnet ist.
 */
public enum Basis {
    /** Ausdrücklich in der Quelle: Seitenhierarchie oder Verweis. */
    EXPLICIT,
    /** Bestätigte Begriffsbeziehung: von einem Bearbeiter angelegt oder bestätigt. */
    CONFIRMED,
    /** Berechnet aus gespeicherten Daten, etwa Ähnlichkeit von Abschnitten; eine Vermutung, keine Aussage der Quelle. */
    COMPUTED
}
