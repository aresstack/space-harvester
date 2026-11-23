package com.aresstack.spaceharvester.application.extraction;

import java.util.Objects;

/**
 * Eine Verarbeitungsstufe, die Felder geliefert hat, mit ihrer Version, etwa {@code regex}/{@code 1} oder
 * {@code ai}/{@code gemma4:12b/prompt-1}. Liefert dieselbe Stufe in einer anderen Version, ersetzen ihre Felder die der
 * alten Version (wie ADR 0032: alte Version = veraltet).
 *
 * @param id      Kennung der Stufe
 * @param version Version; leer, wenn unbekannt
 */
public record Processor(String id, String version) {

    /** Stufe unbekannt (etwa Beispieldaten); ersetzt nie etwas. */
    public static final Processor UNKNOWN = new Processor("", "");

    public Processor {
        id = id == null ? "" : id.strip();
        version = version == null ? "" : version.strip();
    }

    public boolean isKnown() {
        return !id.isEmpty();
    }

    @Override
    public String toString() {
        return version.isEmpty() ? id : id + "/" + version;
    }
}
