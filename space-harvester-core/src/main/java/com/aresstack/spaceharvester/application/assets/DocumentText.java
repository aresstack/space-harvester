package com.aresstack.spaceharvester.application.assets;

import java.util.Optional;

/**
 * Port der Dokumenterkennung und Textextraktion (#183): erkennt den Dateityp am Inhalt (Magic Bytes) und liest Text aus
 * Dokumenten (PDF, Word, Excel, PowerPoint, OpenDocument, RTF …) für Lesetext und Volltextindex. Die Umsetzung liegt
 * im Quellenadapter (Apache Tika über die Kopie aus dem Enterprise AI Client); sie begrenzt Größe, Laufzeit und
 * Textlänge selbst und meldet Fehler mit {@link DocumentTextException}, ohne Dateinamen oder Inhalte in der Meldung.
 */
public interface DocumentText {

    /** Kennung der Extraktion, z. B. {@code tika}. */
    String id();

    /** Version der Extraktion; ein anderer Wert macht abgelegte Texte alt. */
    String version();

    /**
     * Der Medientyp nach dem Inhalt allein, ohne Dateiname und ohne Angabe der Quelle.
     *
     * @return leer, wenn der Inhalt keinen eindeutigen Typ zeigt (etwa {@code application/octet-stream}) oder die
     *         Erkennung scheitert
     */
    Optional<String> detect(byte[] content);

    /**
     * Der Text eines Dokuments.
     *
     * @param title     Dateiname (nur als Hinweis auf das Format)
     * @param mediaType Medientyp, möglichst schon am Inhalt geprüft
     * @return der Text, leer, wenn das Dokument keinen hat
     * @throws DocumentTextException wenn das Format nicht lesbar, die Datei zu groß, die Zeit abgelaufen oder die
     *                               Extraktion gescheitert ist
     */
    Extracted extract(String title, String mediaType, byte[] content);

    /**
     * Ein gelesener Text.
     *
     * @param truncated ob der Text an der Zeichengrenze der Extraktion abgeschnitten wurde
     */
    record Extracted(String text, boolean truncated) {

        public Extracted {
            text = text == null ? "" : text;
        }

        @Override
        public String toString() {
            return "Extracted[chars=" + text.length() + ", truncated=" + truncated + "]";
        }
    }
}
