package com.aresstack.spaceharvester.application.enrichment;

/** Minimaler JSON-Text für Prozessorergebnisse, ohne Abhängigkeit auf eine JSON-Bibliothek. */
final class JsonText {

    private JsonText() {
    }

    /** Eine JSON-Zeichenkette in Anführungszeichen. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
