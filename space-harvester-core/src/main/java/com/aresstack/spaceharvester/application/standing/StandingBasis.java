package com.aresstack.spaceharvester.application.standing;

/** Woraus ein Stand stammt (ADR 0032, FL6), in der Reihenfolge des Vorrangs. */
public enum StandingBasis {
    /** Entscheidung eines Menschen. */
    DECISION,
    /** Die Seite (Label, Titel, Lage unter einem Vorfahren oder Entscheidung zur Seite) zieht den Stand mit. */
    PAGE,
    /** Zeitbezug der Aussage (FL2). */
    FEATURES,
    /** Vorstufe (KI): Kennzeichen der Vorstufe „Sätze vereinfachen“ (FL3). */
    STAGE_FLAG,
    /** Nur beim Stand einer Seite: Mehrheit der sicheren Aussagen. */
    STATEMENTS,
    /** Nichts davon: Analysen ohne Merkmale. */
    NONE
}
