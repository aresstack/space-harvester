package com.aresstack.spaceharvester.application.assets;

import java.util.Objects;

/**
 * Das Original einer enthaltenen Datei ist nicht abrufbar (ADR 0026, Punkt 6, AP7b), mit festem Code; nie mit Datei-
 * oder Anzeigenamen in der Meldung.
 */
public final class PartUnavailableException extends RuntimeException {

    /** Feste Gründe, siehe SOURCE_ADAPTERS.md. */
    public enum Reason {
        /** Der Teil hat keine Kennung nach Inhalt (nicht vollständig gelesen), also kein abrufbares Original. */
        PART_WITHOUT_HASH("part-without-hash"),
        /** Das Original des äußeren Behälters liegt nicht mehr in der Ablage. */
        CONTAINER_MISSING("container-missing"),
        /** Der Behälter enthält keinen Teil mit dieser Kennung (mehr), oder er ist unter den Grenzen nicht lesbar. */
        PART_NOT_FOUND("part-not-found");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Reason reason;

    public PartUnavailableException(Reason reason, Throwable cause) {
        super("Teil nicht abrufbar (" + Objects.requireNonNull(reason, "reason").code() + ")", cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
