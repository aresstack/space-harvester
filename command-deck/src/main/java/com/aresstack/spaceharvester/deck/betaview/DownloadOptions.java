package com.aresstack.spaceharvester.deck.betaview;

/**
 * „Herunterladen mit Optionen“ wie in MainframeMate: Seitenbereich, Format (Original oder CSV mit Trenner und
 * Trennstelle) und was von den Notizen mitkommt.
 *
 * @param pages     Seitenauswahl
 * @param from      erste Seite bei {@link Pages#RANGE}
 * @param to        letzte Seite bei {@link Pages#RANGE}
 * @param format    Format
 * @param delimiter CSV-Trenner
 * @param split     CSV-Trennstelle
 * @param csvValue  Wert zur Trennstelle (Zeichenzahl, Ersatzzeichen bzw. Muster)
 * @param csvTrim   Leerzeichen trimmen
 * @param notes     was von den Notizen mitkommt
 * @param noteKind  welche Notizen
 */
public record DownloadOptions(Pages pages, int from, int to, Format format, Delimiter delimiter, Split split,
                              String csvValue, boolean csvTrim, Notes notes, NoteKind noteKind) {

    public DownloadOptions {
        pages = pages == null ? Pages.ALL : pages;
        format = format == null ? Format.ORIGINAL : format;
        delimiter = delimiter == null ? Delimiter.COMMA : delimiter;
        split = split == null ? Split.AFTER_CHARACTERS : split;
        csvValue = csvValue == null ? "" : csvValue.trim();
        notes = notes == null ? Notes.TEXT_ONLY : notes;
        noteKind = noteKind == null ? NoteKind.ALL : noteKind;
        if (pages == Pages.RANGE && (from < 1 || to < from)) {
            throw new IllegalArgumentException("Seitenbereich ungültig: von " + from + " bis " + to);
        }
    }

    /** Schnell: alle Seiten im Original, nur Text (der Knopf ⬇ in MainframeMate). */
    public static DownloadOptions quick() {
        return new DownloadOptions(Pages.ALL, 1, 1, Format.ORIGINAL, Delimiter.COMMA, Split.AFTER_CHARACTERS, "10",
                false, Notes.TEXT_ONLY, NoteKind.ALL);
    }

    public enum Pages {
        ALL("printAllPages", "Alle Seiten"),
        CURRENT("printCurrentPage", "Aktuelle Seite"),
        RANGE("printRange", "Seitenbereich");

        private final String value;
        private final String label;

        Pages(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Format {
        ORIGINAL("ORIG", "Original"),
        CSV("CSV", "CSV");

        private final String value;
        private final String label;

        Format(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Delimiter {
        COMMA("comma", ", (Komma)"),
        SEMICOLON("semicolon", "; (Semikolon)"),
        TAB("tab", "Tabulator");

        private final String value;
        private final String label;

        Delimiter(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Split {
        AFTER_CHARACTERS("csv_int", "Nach x Zeichen"),
        REPLACE_CHARACTER("csv_char", "Als Ersatz für"),
        VARIABLE("csv_flex", "Variabel");

        private final String value;
        private final String label;

        Split(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Notes {
        TEXT_ONLY("printTextOnly", "Nur Text"),
        NOTES_ONLY("printAntsOnly", "Nur Notizen"),
        BOTH("printWithAnts", "Beides");

        private final String value;
        private final String label;

        Notes(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum NoteKind {
        ALL("all", "Alle Notizen"),
        PUBLIC("public", "Nur öffentliche"),
        PRIVATE("private", "Nur private");

        private final String value;
        private final String label;

        NoteKind(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
