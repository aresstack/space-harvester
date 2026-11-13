package com.aresstack.spaceharvester.application.assets;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Fester Grund zu einem {@link ExtractionStatus} (ADR 0026, Punkt 7), je Ergebnis einer: der zuerst erreichte. Die
 * Liste der ADR ist nicht abschließend; jeder Code steht in {@code docs/SOURCE_ADAPTERS.md}. Der {@link #code()} geht unverändert in Ablage,
 * Export und REST; Meldungen und Protokoll nennen nur ihn, nie Dateinamen oder Inhalte.
 */
public enum StatusReason {
    /** Verschlüsselt; nur, wenn die Ausnahme der Extraktion es eindeutig sagt. */
    ENCRYPTED,
    /** Defekt. */
    CORRUPT,
    /** Ohne Inhalt (0 Bytes). */
    EMPTY,
    /** Laufzeitgrenze erreicht (ohne Fortschritt oder insgesamt). */
    TIMEOUT,
    /** Verschachtelungstiefe überschritten. */
    TOO_DEEP,
    /** Zu viele enthaltene Dateien im äußeren Behälter. */
    TOO_MANY_FILES,
    /** Ausgepackt zu gepackt über dem erlaubten Verhältnis. */
    UNPACKED_TOO_LARGE,
    /** Format braucht mehr Speicher, als für das Lesen im Dienst erlaubt ist. */
    MEMORY_LIMIT,
    /** Medientyp nicht zugelassen. */
    MEDIA_TYPE_EXCLUDED,
    /** Über einer vom Betreiber gesetzten Größe. */
    TOO_LARGE,
    /** Mehr Zeichen je Byte der Datei als erlaubt (Textbombe). */
    TEXT_RATIO_EXCEEDED,
    /** Freier Platz für temporäre Dateien unter der Reserve. */
    TEMP_SPACE_LOW,
    /**
     * Der Parser des Behälters übergab enthaltene Dateien nicht als Datei (etwa ein verschlüsselter ZIP-Eintrag); sie
     * haben keinen Eintrag, der Behälter ist {@link ExtractionStatus#PARTIAL}.
     */
    ENTRIES_NOT_READABLE,
    /** Kein Leser für dieses Format. */
    UNSUPPORTED_FORMAT,
    /**
     * Bytes vorhanden, aber kein Text (etwa ein gescanntes PDF ohne Texterkennung); nie stillschweigend als vollständig
     * gelesen.
     */
    NO_TEXT;

    /** Fester Code, z. B. {@code unpacked-too-large}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Der Grund zu einem Code; leer bei unbekanntem Code. */
    public static Optional<StatusReason> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(reason -> reason.code().equals(code)).findFirst();
    }
}
