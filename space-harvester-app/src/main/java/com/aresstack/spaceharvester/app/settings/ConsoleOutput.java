package com.aresstack.spaceharvester.app.settings;

import java.util.Locale;

/**
 * Welche Meldungen die App im Terminal ausgibt, aus dem sie gestartet wurde (Angelo 12.11.2026): in der Datei
 * {@value #KEY}, derselbe Schlüssel wie beim Dienst (Spring Boot; dort ohne Angabe alles). In der App ist ohne Angabe
 * {@link #OFF} der Standard, denn bei vielen parallelen Verbindungen liefen die Zeilen im Terminal durcheinander. Jede
 * Stufe zeigt ihre Meldungen und die schwereren ({@link #WARN} also auch Fehler). Die Protokolldatei unter
 * {@code <Benutzerordner>/.space-harvester/logs} bekommt unabhängig davon immer alle Meldungen.
 *
 * <p>Die Namen sind die Logback-Stufen, die auch Spring Boot unter {@value #KEY} erwartet.
 */
public enum ConsoleOutput {

    OFF("Aus"),
    ERROR("Fehler"),
    WARN("Warnungen"),
    INFO("Informationen"),
    /** Ohne Filter: dieselben Meldungen wie in der Protokolldatei. */
    ALL("Alles");

    /** Ohne Angabe in der Datei. */
    public static final ConsoleOutput DEFAULT = OFF;
    /** Der Schlüssel in der Datei, wie beim Dienst. */
    public static final String KEY = "logging.threshold.console";

    private final String label;

    ConsoleOutput(String label) {
        this.label = label;
    }

    /** Die Bezeichnung in der Oberfläche. */
    public String label() {
        return label;
    }

    /** Der Wert in der Datei: {@code off}, {@code error}, {@code warn}, {@code info} oder {@code all}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Die Stufe zu einem Wert aus der Datei, ohne Rücksicht auf Groß- und Kleinschreibung; auch die übrigen
     * Schreibweisen, die Spring Boot dort annimmt ({@code false} für ein unquotiertes {@code off} in YAML,
     * {@code warning}, {@code debug} und {@code trace}, die beiden letzten wie {@link #ALL}, denn die App protokolliert
     * nichts darunter).
     *
     * @return {@code null}, wenn der Wert keine Stufe ist
     */
    public static ConsoleOutput parse(String text) {
        if (text == null) {
            return null;
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "off", "false" -> OFF;
            case "error" -> ERROR;
            case "warn", "warning" -> WARN;
            case "info" -> INFO;
            case "all", "debug", "trace" -> ALL;
            default -> null;
        };
    }
}
