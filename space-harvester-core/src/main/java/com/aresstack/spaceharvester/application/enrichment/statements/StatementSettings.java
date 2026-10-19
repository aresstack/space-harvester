package com.aresstack.spaceharvester.application.enrichment.statements;

import java.util.Objects;
import java.util.Properties;

/**
 * Einstellungen der Satzanalyse (#108, Standards wie {@code StatementSettings} im Enterprise AI Client): ob Seiten
 * überhaupt analysiert werden (Standard aus: Seitentext geht an das Chat-Modell), ob jede Aussage die Ja/Nein-Prüfung
 * bekommt, und wie viel einer Seite analysiert wird. Der Core liest keine Umgebung; Server und App übergeben die Werte,
 * etwa als {@link Properties} über {@link #fromProperties}.
 *
 * @param enabled           Seiten analysieren; nur mit Chat-Modell und nur für freigegebene Quellen
 * @param verify            jede Handlung und Relation mit einer zweiten Anfrage prüfen
 * @param maxParagraphs     so viele Absätze je Seite (Modellanfragen); spätere fallen weg
 * @param maxParagraphChars längere Absätze werden in Folgen ganzer Sätze bis zu dieser Länge analysiert
 */
public record StatementSettings(boolean enabled, boolean verify, int maxParagraphs, int maxParagraphChars) {

    public static final boolean DEFAULT_ENABLED = false;
    public static final boolean DEFAULT_VERIFY = true;
    public static final int DEFAULT_MAX_PARAGRAPHS = 40;
    public static final int DEFAULT_MAX_PARAGRAPH_CHARS = 1500;

    /** Präfix der Properties in Server und App. */
    public static final String PREFIX = "space-harvester.enrichment.statements.";
    public static final String ENABLED = "enabled";
    public static final String VERIFY = "verify";
    public static final String MAX_PARAGRAPHS = "max-paragraphs";
    public static final String MAX_PARAGRAPH_CHARS = "max-paragraph-chars";

    /** Aus, mit den Standardgrenzen. */
    public static final StatementSettings DEFAULT = new StatementSettings(DEFAULT_ENABLED, DEFAULT_VERIFY,
            DEFAULT_MAX_PARAGRAPHS, DEFAULT_MAX_PARAGRAPH_CHARS);

    public StatementSettings {
        if (maxParagraphs < 1 || maxParagraphChars < 1) {
            throw new IllegalArgumentException(PREFIX + MAX_PARAGRAPHS + " und " + PREFIX + MAX_PARAGRAPH_CHARS
                    + " müssen positiv sein");
        }
    }

    public static StatementSettings defaults() {
        return DEFAULT;
    }

    /**
     * Liest die Einstellungen aus Properties mit oder ohne {@link #PREFIX}; fehlende Werte gelten als Standard.
     *
     * @throws IllegalArgumentException bei einem ungültigen Wert, ohne den Wert zu nennen
     */
    public static StatementSettings fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        return new StatementSettings(bool(properties, ENABLED, DEFAULT_ENABLED),
                bool(properties, VERIFY, DEFAULT_VERIFY), number(properties, MAX_PARAGRAPHS, DEFAULT_MAX_PARAGRAPHS),
                number(properties, MAX_PARAGRAPH_CHARS, DEFAULT_MAX_PARAGRAPH_CHARS));
    }

    private static String value(Properties properties, String name) {
        String value = properties.getProperty(PREFIX + name, properties.getProperty(name));
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean bool(Properties properties, String name, boolean fallback) {
        String value = value(properties, name);
        if (value == null) {
            return fallback;
        }
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(PREFIX + name + " muss true oder false sein");
        }
        return Boolean.parseBoolean(value);
    }

    private static int number(Properties properties, String name, int fallback) {
        String value = value(properties, name);
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + name + " ist keine Zahl");
        }
    }
}
