package com.aresstack.spaceharvester.application.extraction;

import java.util.Objects;

/**
 * Liest die Bytes eines Dokumentstands für die Analyse (ADR 0035, Abschnitt 2). Der Core kennt keine Ablage: Die App
 * schließt etwa die E-Akte (WebDAV) über diesen Port an; den Text liest danach die vorhandene Extraktion. Liest nur,
 * ändert nie. Keine Methode wird auf dem Swing-Thread gerufen.
 */
public interface DocumentContent {

    /** Ohne angeschlossene Ablage: nichts lesbar. */
    DocumentContent NONE = new DocumentContent() {
        @Override
        public boolean supports(DocumentRef document) {
            return false;
        }

        @Override
        public Content read(DocumentRef document) {
            throw new UnsupportedOperationException("Keine Ablage angeschlossen");
        }
    };

    /** Ob dieser Stand gelesen werden kann (etwa: die Ablage {@link DocumentRef#origin()} ist angeschlossen). */
    boolean supports(DocumentRef document);

    /**
     * Die Bytes genau dieses Stands. Blockiert.
     *
     * @throws Exception wenn die Ablage nicht erreichbar ist, eine Anmeldung fehlt oder der Stand nicht mehr da ist
     *                   (anderes ETag); der Grund geht in die Diagnose
     */
    Content read(DocumentRef document) throws Exception;

    /**
     * Gelesener Inhalt.
     *
     * @param title     Dateiname oder Titel, Hinweis auf das Format und zur Anzeige
     * @param mediaType Medientyp laut Ablage, leer wenn unbekannt
     * @param bytes     der Inhalt
     */
    record Content(String title, String mediaType, byte[] bytes) {

        public Content {
            title = title == null ? "" : title;
            mediaType = mediaType == null ? "" : mediaType;
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override
        public String toString() {
            return "Content[" + title + ", " + mediaType + ", " + bytes.length + " Bytes]";
        }
    }
}
