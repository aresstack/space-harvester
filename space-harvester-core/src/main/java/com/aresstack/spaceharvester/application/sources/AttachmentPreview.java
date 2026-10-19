package com.aresstack.spaceharvester.application.sources;

import java.util.List;

/**
 * Vorschauarten, die eine Quelle für einen Anhang selbst erzeugen kann (#183), wie {@code PageView.Preview} im
 * Enterprise AI Client; bei Confluence die Dokumentkonvertierung ({@code /rest/documentConversion}). Was tatsächlich
 * kommt (PDF oder Bild), erkennt der Aufrufer an den Bytes.
 */
public enum AttachmentPreview {
    /** Das umgewandelte Dokument, etwa ein Office-Anhang als PDF ({@code convert}). */
    DOCUMENT,
    /** Ein großes Bild der ersten Seite ({@code poster}). */
    POSTER,
    /** Ein kleines Bild der ersten Seite ({@code thumbnail}). */
    THUMBNAIL;

    /**
     * Die Reihenfolge, in der eine Vorschau gesucht wird, wenn der Aufrufer keine Art nennt: zuerst das umgewandelte
     * Dokument, dann ein großes, dann ein kleines Bild (wie {@code KnowledgeReadingController.previewOrder()}).
     */
    public static List<AttachmentPreview> order() {
        return List.of(DOCUMENT, POSTER, THUMBNAIL);
    }
}
