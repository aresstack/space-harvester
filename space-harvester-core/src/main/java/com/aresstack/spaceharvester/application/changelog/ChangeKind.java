package com.aresstack.spaceharvester.application.changelog;

/**
 * Art einer Zeile im Änderungsprotokoll (ADR 0030, Abschnitt 1). Die erste Gruppe heißt: die Quelle hat sich
 * geändert. {@link #LOADED} und {@link #RELOADED} heißen nur: der Bestand hat sich geändert.
 */
public enum ChangeKind {
    /** Erstmals im Cache, mit oder ohne Inhalt. */
    NEW,
    /** Neue Version in der Quelle; ohne Inhalt der neuen Version mit {@link ChangeEntry#contentPending()}. */
    CONTENT_CHANGED,
    /** Neuer Titel. */
    RENAMED,
    /** Neue Elternseite im selben Space. */
    MOVED,
    /** Andere Labels oder übrige Attribute ({@code attributesHash}). */
    LABELS_CHANGED,
    /** Aus dem Cache entfernt. */
    REMOVED,
    /** Gesperrt (ADR 0014, {@code INACCESSIBLE}). */
    WITHHELD,
    /** In einen anderen Space gewandert. */
    MOVED_OUT,
    /** Inhalt erstmals lokal, ohne neue Version. */
    LOADED,
    /** Inhalt einer schon als geändert bekannten Version jetzt lokal; keine zweite Änderung. */
    RELOADED;

    /** Ob die Quelle sich geändert hat; {@code false} für {@link #LOADED} und {@link #RELOADED}. */
    public boolean sourceChange() {
        return this != LOADED && this != RELOADED;
    }
}
