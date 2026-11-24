package com.aresstack.spaceharvester.deck.ftp;

/**
 * Datenumwandlung zwischen Großrechner und Editor wie in MainframeMate (Einstellungen → „Datenumwandlung“,
 * {@code Settings.lineEnding}, {@code fileEndMarker}, {@code padding}, {@code removeFinalNewline}) mit denselben
 * Standardwerten. Hex-Werte als Text; leer heißt aus.
 *
 * @param recordMarker       „Zeilenumbruch des Servers“: Satzende bei Satzstruktur, Standard {@code FF01}
 * @param fileEndMarker      „Datei-Ende-Kennung“, Standard {@code FF02}
 * @param padding            „Padding Byte“: wird beim Lesen komplett entfernt, Standard {@code 00}
 * @param removeFinalNewline „Letzten Zeilenumbruch ausblenden“, Standard an
 */
public record TextTransform(String recordMarker, String fileEndMarker, String padding, boolean removeFinalNewline) {

    /** Die Standardwerte von MainframeMate ({@code de.bund.zrb.model.Settings}). */
    public static final String DEFAULT_RECORD_MARKER = "FF01";
    public static final String DEFAULT_FILE_END_MARKER = "FF02";
    public static final String DEFAULT_PADDING = "00";
    public static final boolean DEFAULT_REMOVE_FINAL_NEWLINE = true;

    public static final TextTransform DEFAULT = new TextTransform(DEFAULT_RECORD_MARKER, DEFAULT_FILE_END_MARKER,
            DEFAULT_PADDING, DEFAULT_REMOVE_FINAL_NEWLINE);

    /** Vorschläge wie {@code LineEndingOption.PRESETS} in MainframeMate. */
    public static final java.util.List<String> RECORD_MARKER_PRESETS = java.util.List.of("0A", "0D0A", "FF01", "");
    /** Vorschläge wie {@code FileEndingOption.PRESETS}. */
    public static final java.util.List<String> FILE_END_PRESETS = java.util.List.of("", "0C", "FF02", "FFFF", "1A");
    /** Vorschläge wie {@code PaddingOption.PRESETS}. */
    public static final java.util.List<String> PADDING_PRESETS = java.util.List.of("", "00");

    public TextTransform {
        recordMarker = hex(recordMarker, Integer.MAX_VALUE);
        fileEndMarker = hex(fileEndMarker, Integer.MAX_VALUE);
        padding = hex(padding, 2);
    }

    /**
     * Normalisiert eine Eingabe wie {@code LineEndingOption.normalizeInput} (nur Hex-Zeichen, groß); das Füllbyte wie
     * {@code PaddingOption.normalizeInput} auf ein Byte gekürzt.
     */
    public static String hex(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        String hex = raw.trim().replaceAll("[^0-9A-Fa-f]", "").toUpperCase(java.util.Locale.ROOT);
        return hex.length() > maxLength ? hex.substring(0, maxLength) : hex;
    }

    public byte[] recordMarkerBytes() {
        return parseHex(recordMarker);
    }

    public byte[] fileEndMarkerBytes() {
        return parseHex(fileEndMarker);
    }

    /** Das Füllbyte oder {@code null}, wenn aus. */
    public Byte paddingByte() {
        byte[] bytes = parseHex(padding);
        return bytes == null || bytes.length == 0 ? null : bytes[0];
    }

    /**
     * Parse a hex string like "FF01" into bytes. Returns null if the input is null or empty. Unverändert aus
     * MainframeMate ({@code RecordStructureCodec.parseHex}).
     */
    public static byte[] parseHex(String hex) {
        if (hex == null || hex.trim().isEmpty()) {
            return null;
        }

        String clean = hex.trim().replaceAll("\\s+", "");
        if (clean.length() % 2 != 0) {
            return null;
        }

        byte[] result = new byte[clean.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(clean.charAt(i * 2), 16);
            int low = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
