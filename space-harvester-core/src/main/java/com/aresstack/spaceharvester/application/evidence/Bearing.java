package com.aresstack.spaceharvester.application.evidence;

/**
 * Ist oder Soll (ADR 0041, Punkt 7). Auch eine von einem Menschen bestätigte Aussage über Programmcode bleibt Ist: Sie
 * beschreibt, was das Programm tut, nicht was gelten soll. Das Soll kommt aus Vorschriften und Fachtexten; der
 * Soll-Ist-Vergleich (ADR 0032, FL8) liefert nur Hinweise, keine Feststellungen.
 */
public enum Bearing {
    /** Belegter technischer Ist-Zustand: was ein Programm tatsächlich umsetzt. */
    IST,
    /** Fachlicher Soll-Zustand: was eine Vorschrift oder ein Fachtext verlangt. */
    SOLL
}
