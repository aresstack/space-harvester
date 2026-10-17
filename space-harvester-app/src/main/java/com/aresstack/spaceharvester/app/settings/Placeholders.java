package com.aresstack.spaceharvester.app.settings;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Löst Platzhalter {@code ${NAME}} und {@code ${NAME:Standard}} wie Spring auf: zuerst Systemeigenschaften, dann
 * Umgebungsvariablen. So bleibt eine Server-Konfiguration ({@code application.yml}) für die App lesbar, und
 * Zugangsdaten stehen wie dort nicht in der Datei, sondern in Umgebungsvariablen.
 */
public final class Placeholders {

    private final Function<String, String> lookup;

    public Placeholders(Function<String, String> lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /** Systemeigenschaften vor Umgebungsvariablen. */
    public static Placeholders system() {
        Map<String, String> env = System.getenv();
        return new Placeholders(name -> {
            String property = System.getProperty(name);
            return property != null ? property : env.get(name);
        });
    }

    /**
     * @return der Wert mit allen aufgelösten Platzhaltern
     * @throws UnresolvedPlaceholderException wenn ein Platzhalter ohne Standard keinen Wert hat
     */
    public String resolve(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            int start = value.indexOf("${", index);
            if (start < 0) {
                result.append(value, index, value.length());
                break;
            }
            int end = closing(value, start + 2);
            if (end < 0) {
                result.append(value, index, value.length());
                break;
            }
            result.append(value, index, start);
            result.append(expression(value.substring(start + 2, end)));
            index = end + 1;
        }
        return result.toString();
    }

    private String expression(String expression) {
        int colon = topLevelColon(expression);
        String name = (colon < 0 ? expression : expression.substring(0, colon)).trim();
        String found = name.isEmpty() ? null : lookup.apply(name);
        if (found != null) {
            return found;
        }
        if (colon >= 0) {
            return resolve(expression.substring(colon + 1));
        }
        throw new UnresolvedPlaceholderException(name);
    }

    /** Ende des Platzhalters ab {@code from}, verschachtelte {@code ${…}} im Standardwert eingeschlossen. */
    private static int closing(String value, int from) {
        int depth = 1;
        for (int i = from; i < value.length(); i++) {
            if (value.startsWith("${", i)) {
                depth++;
                i++;
            } else if (value.charAt(i) == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int topLevelColon(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            if (expression.startsWith("${", i)) {
                depth++;
                i++;
            } else if (expression.charAt(i) == '}') {
                depth--;
            } else if (expression.charAt(i) == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Ein Platzhalter ohne Wert und ohne Standard. Die Meldung nennt nur den Namen, nie einen Wert. */
    public static final class UnresolvedPlaceholderException extends RuntimeException {

        private final String name;

        public UnresolvedPlaceholderException(String name) {
            super("Die Umgebungsvariable " + name + " ist nicht gesetzt");
            this.name = name;
        }

        public String name() {
            return name;
        }
    }
}
