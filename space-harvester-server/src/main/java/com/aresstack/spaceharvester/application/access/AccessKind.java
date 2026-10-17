package com.aresstack.spaceharvester.application.access;

/**
 * Was gelesen oder ausgelöst werden soll. Die Arten sind unabhängig: {@link #CONTENT} schließt {@link #METADATA} nicht
 * ein und umgekehrt.
 */
public enum AccessKind {
    /**
     * Existenz und Verwaltungsdaten: Quellen, Spaces, Seiten-IDs, Titel, Elternbeziehungen, Baumknoten, Versionen,
     * Hashes und Suchtreffer. Schon Titel und Existenz einer Seite können vertraulich sein.
     */
    METADATA,
    /**
     * Inhalte: die native Payload (z. B. Confluence-JSON mit {@code body.storage}) sowie alles daraus Abgeleitete,
     * also Enrichments, Bildbeschreibungen und Converter-Ergebnisse.
     */
    CONTENT,
    /** Exportjobs starten, abbrechen, ihren Status lesen und ihre Artefakte herunterladen. */
    EXPORT,
    /** Abgleich eines Space mit der Quelle anstoßen, abbrechen und seinen Status lesen (#16). */
    SYNC
}
