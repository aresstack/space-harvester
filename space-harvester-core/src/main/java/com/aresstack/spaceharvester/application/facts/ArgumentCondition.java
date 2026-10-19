package com.aresstack.spaceharvester.application.facts;

import java.util.Locale;
import java.util.Objects;

/**
 * Eine Bedingung der Argumentsuche (wie {@code ArgumentCondition.value} im Enterprise AI Client): Der Fakt hat in
 * {@code role} ein Argument mit genau diesem normalisierten Wert. Mehrere Bedingungen gelten zugleich.
 *
 * @param role  Rolle, etwa {@code SHORT_FORM}
 * @param value Wert; verglichen wird mit {@link FactTypes#normalize}
 */
public record ArgumentCondition(String role, String value) {

    public ArgumentCondition {
        MetadataFact.requireName("argument role", role);
        Objects.requireNonNull(value, "value");
        value = FactTypes.normalize(value);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("value darf nicht leer sein");
        }
    }

    /** {@code ROLLE:Wert}, etwa {@code SHORT_FORM:API}. */
    public static ArgumentCondition parse(String text) {
        Objects.requireNonNull(text, "text");
        int colon = text.indexOf(':');
        if (colon < 1) {
            throw new IllegalArgumentException("Bedingung hat die Form ROLLE:Wert");
        }
        return new ArgumentCondition(text.substring(0, colon).strip().toUpperCase(Locale.ROOT),
                text.substring(colon + 1));
    }
}
