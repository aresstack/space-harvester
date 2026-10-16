package com.aresstack.enterpriseai.astu.knowledge;

/**
 * Die kanonische technische Adresse einer Wissensquelle und damit ihre einzige Identität, als corenth-Bookmark
 * {@code <scheme>://<locator>}: {@code confluence://cf.intern/confluence}, {@code wiki://wiki.intern/w},
 * {@code ftp://host.example:21}, {@code ndv://host.example:2700}, {@code file:///C:/Wissen}.
 *
 * <p>Das Scheme bestimmt der Connector-Typ, den Locator der kanonische Endpoint ({@code SourceLocators}); es gibt
 * keinen zweiten, frei vergebenen Schlüssel. Konfiguration, Häkchen, Bereichsauswahl, Status, Entfernen, Vorladen
 * und Indexzuordnung hängen an dieser Adresse. Titel und KeePass-Eintrag gehören nie dazu. Eine Ressource der
 * Quelle ist Adresse plus native ID ({@link KnowledgeResourceId} {@code confluence:cf.intern/confluence/page/123456},
 * als Bookmark {@code confluence://cf.intern/confluence/page/123456}).
 *
 * <p>Die Adresse enthält keine Zugangsdaten (keine Benutzerangaben, keine Query) und darf geloggt und indexiert
 * werden. Höchstens 2048 Zeichen, kein Leerraum am Rand, keine Steuerzeichen.
 */
public final class SourceAddress {

    private static final String SEPARATOR = "://";
    private static final int MAX_LENGTH = 2048;

    private final String value;

    private SourceAddress(String value) {
        this.value = value;
    }

    public static SourceAddress of(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH || KnowledgeText.hasEdgeSpace(value)) {
            throw new IllegalArgumentException("Ungültige Quellenadresse: " + quote(value)
                    + " (erwartet <scheme>://<adresse>, z. B. confluence://cf.intern/confluence)");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("Ungültige Quellenadresse: " + quote(value)
                        + " (enthält Steuerzeichen)");
            }
        }
        return new SourceAddress(value);
    }

    /** Die Adresse aus Scheme des Connectors und kanonischem Locator, z. B. {@code of("ndv", "host:2700")}. */
    public static SourceAddress of(String scheme, String locator) {
        if (scheme == null || scheme.isEmpty() || locator == null || locator.isEmpty()) {
            throw new IllegalArgumentException("Scheme und Locator der Quellenadresse sind Pflicht");
        }
        return of(scheme + SEPARATOR + locator);
    }

    public String value() {
        return value;
    }

    /** Das Scheme ({@code confluence}); leer, wenn die Adresse keins trägt. */
    public String scheme() {
        int at = value.indexOf(SEPARATOR);
        return at <= 0 ? "" : value.substring(0, at);
    }

    /** Der kanonische Locator ohne Scheme ({@code cf.intern/confluence}); ohne Scheme die ganze Adresse. */
    public String locator() {
        int at = value.indexOf(SEPARATOR);
        return at <= 0 ? value : value.substring(at + SEPARATOR.length());
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SourceAddress && value.equals(((SourceAddress) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

    static String quote(String value) {
        return value == null ? "null" : "'" + value + "'";
    }
}
