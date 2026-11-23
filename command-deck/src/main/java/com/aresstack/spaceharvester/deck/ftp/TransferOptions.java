package com.aresstack.spaceharvester.deck.ftp;

import java.util.Objects;

/**
 * Wie Text übertragen wird, die Einstellungen „FTP Datei-Typ (TYPE)“, „FTP Text-Format“, „FTP Dateistruktur“ und „FTP
 * Übertragungsmodus“ aus MainframeMate. Die Vorgaben entsprechen dem bisherigen Verhalten: ASCII ohne Textformat,
 * Dateistruktur automatisch (auf MVS Satzstruktur, sonst Datei), Stream.
 *
 * @param fileType     TYPE
 * @param textFormat   Textformat zu TYPE A/E
 * @param structure    STRU
 * @param transferMode MODE
 */
public record TransferOptions(FileType fileType, TextFormat textFormat, Structure structure, TransferMode transferMode) {

    /** {@code TYPE}. */
    public enum FileType {
        ASCII("ASCII"), EBCDIC("EBCDIC"), BINARY("Binär"), LOCAL("Lokal");

        private final String label;

        FileType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Textformat zu {@code TYPE A} bzw. {@code TYPE E}. */
    public enum TextFormat {
        DEFAULT("Ohne Angabe"), NON_PRINT("Non-Print"), TELNET("Telnet"), CARRIAGE_CONTROL("ASA-Vorschub");

        private final String label;

        TextFormat(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** {@code STRU}. */
    public enum Structure {
        AUTO("Automatisch"), FILE("Datei"), RECORD("Satz"), PAGE("Seite");

        private final String label;

        Structure(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** {@code MODE}. */
    public enum TransferMode {
        STREAM("Stream"), BLOCK("Block"), COMPRESSED("Komprimiert");

        private final String label;

        TransferMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /**
         * Ob Commons Net diesen Modus wirklich überträgt: nur Stream. Block und Komprimiert verlangen eine eigene
         * Rahmung bzw. Dekompression beim Lesen und Schreiben, die Commons Net nicht hat; der Host würde sie annehmen
         * und die Daten kämen verfälscht an.
         */
        public boolean supported() {
            return this == STREAM;
        }
    }

    public static final TransferOptions DEFAULT = new TransferOptions(FileType.ASCII, TextFormat.DEFAULT,
            Structure.AUTO, TransferMode.STREAM);

    public TransferOptions {
        fileType = Objects.requireNonNullElse(fileType, FileType.ASCII);
        textFormat = Objects.requireNonNullElse(textFormat, TextFormat.DEFAULT);
        structure = Objects.requireNonNullElse(structure, Structure.AUTO);
        transferMode = Objects.requireNonNullElse(transferMode, TransferMode.STREAM);
        if (!transferMode.supported()) {
            throw new IllegalArgumentException("Übertragungsmodus „" + transferMode.label()
                    + "“ wird nicht unterstützt – nur Stream (Block und Komprimiert würden die Daten verfälschen)");
        }
    }

    /** Ob Text Satz für Satz übertragen wird: ausdrücklich {@code RECORD} oder automatisch auf MVS. */
    public boolean recordStructure(boolean mvs) {
        return structure == Structure.RECORD || (structure == Structure.AUTO && mvs);
    }
}
