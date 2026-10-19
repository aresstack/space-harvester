package com.aresstack.spaceharvester.application.searchrun;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stabile Kennung eines Suchlaufs (wie {@code SearchRunId} im Enterprise AI Client); überdauert Neustarts.
 *
 * @param value höchstens {@value #MAX_LENGTH} Zeichen aus Buchstaben, Ziffern, {@code -}, {@code _} und {@code .}
 */
public record SearchRunId(String value) {

    /** Längste Kennung. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._-]{1," + MAX_LENGTH + "}");

    public SearchRunId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Ungültige Kennung eines Suchlaufs");
        }
    }

    /** Eine neue, zufällige Kennung. */
    public static SearchRunId random() {
        return new SearchRunId(UUID.randomUUID().toString());
    }

    /** Die Kennung oder {@code null}, wenn {@code value} keine gültige ist. */
    public static SearchRunId parse(String value) {
        return value != null && FORMAT.matcher(value).matches() ? new SearchRunId(value) : null;
    }

    @Override
    public String toString() {
        return value;
    }
}
