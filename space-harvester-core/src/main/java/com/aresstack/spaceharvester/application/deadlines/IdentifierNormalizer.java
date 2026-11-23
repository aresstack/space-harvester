package com.aresstack.spaceharvester.application.deadlines;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemeinsame Normalisierung der Kennzeichen (ADR 0035, Abschnitt 3): Regex- und KI-Stufe legen denselben Wert ab, damit
 * „031 40 420“ und „03140420“ kein falscher CONFLICT werden. Die Rohschreibweise bleibt im Feld erhalten. Nichts wird
 * ergänzt: Was nicht eindeutig lesbar ist, bleibt leer.
 */
public final class IdentifierNormalizer {

    private static final Pattern GERMAN_DATE = Pattern.compile("(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4})");

    private IdentifierNormalizer() {
    }

    /**
     * Ziffernkennzeichen (Bewirtschafternummer, Objektkonto, Buchungsstelle): nur die Ziffern, Trenner wie Leerzeichen,
     * Punkt, Bindestrich und Schrägstrich fallen weg; führende Nullen bleiben.
     *
     * @return leer, wenn andere Zeichen als Ziffern und Trenner darin stehen oder keine Ziffer
     */
    public static Optional<String> digits(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String stripped = raw.strip();
        if (stripped.isEmpty() || !stripped.matches("[0-9 .\\-/\\u00a0]+")) {
            return Optional.empty();
        }
        String digits = stripped.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? Optional.empty() : Optional.of(digits);
    }

    /** Geschäftszeichen: Leerraum vereinheitlicht, Groß-/Kleinschreibung bleibt (sie kann bedeutsam sein). */
    public static String fileReference(String raw) {
        return raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
    }

    /**
     * Deutsches Datum „27.07.2026“ oder „27. 7. 2026“ als ISO-Datum.
     *
     * @return leer, wenn es kein gültiges Datum ist
     */
    public static Optional<LocalDate> germanDate(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = GERMAN_DATE.matcher(raw.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(1))));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }
}
