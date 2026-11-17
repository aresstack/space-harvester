package com.aresstack.spaceharvester.application.standing;

/** Fester Grund eines Stands (ADR 0032, FL6), für Anzeige und Tooltip; nie Freitext. */
public enum StandingReason {
    /** Ein Mensch hat den Stand gesetzt. */
    DECISION,
    /** Zeitbezug „gilt jetzt“ (auch, wenn der Satz nichts über die Zeit sagt). */
    TEMPORALITY_GILT,
    /** Zeitbezug „geplant“ (Futur, soll mit Zeitangabe …). */
    TEMPORALITY_GEPLANT,
    /** Zeitbezug „früher“ (Präteritum, Plusquamperfekt, „bisher“ …). */
    TEMPORALITY_FRUEHER,
    /** Zeitbezug „verworfen“ („nicht mehr“, „entfällt“ …). */
    TEMPORALITY_VERWORFEN,
    /** Vorstufe (KI): Tatsache. */
    STAGE_FACT,
    /** Vorstufe (KI): Bedingung. */
    STAGE_CONDITION,
    /** Vorstufe (KI): Planung oder Absicht. */
    STAGE_PLANNING,
    /** Vorstufe (KI): verworfen oder früher. */
    STAGE_DISCARDED,
    /** Die Seite ist überholt (Label, Titel, Lage unter einer überholten Seite oder Entscheidung zur Seite). */
    PAGE_OUTDATED,
    /**
     * Geplant auf einer überholten Seite: ob umgesetzt oder fallengelassen, weiß niemand; deshalb unklar.
     */
    PAGE_OUTDATED_PLAN,
    /** Die Seite ist ein Entwurf (Label, Titel, Lage unter einer Entwurfsseite). */
    PAGE_DRAFT,
    /** Ein Mensch hat die Seite als geplant oder unklar eingestuft. */
    PAGE_DECISION,
    /** Die Aussage hat keine Merkmale (ältere Analyse oder anderer Analysator). */
    NO_FEATURES,
    /** Seite: die meisten sicheren Aussagen haben diesen Stand. */
    MAJORITY,
    /** Seite: Gleichstand an der Spitze oder keine sichere Aussage. */
    NO_MAJORITY
}
