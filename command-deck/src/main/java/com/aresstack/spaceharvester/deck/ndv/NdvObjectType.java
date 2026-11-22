package com.aresstack.spaceharvester.deck.ndv;

import java.util.Objects;

/**
 * Art eines Natural-Objekts, wie der Server sie meldet (Programm, Subprogramm, Map, DDM …).
 *
 * @param id        Kennzahl des Servers, nur für den Adapter von Bedeutung
 * @param name      lesbarer Name, etwa {@code Program}
 * @param extension Dateiendung wie in NaturalONE, etwa {@code NSP}; leer, wenn die Art keine hat
 */
public record NdvObjectType(int id, String name, String extension) {

    public NdvObjectType {
        name = name == null || name.isBlank() ? "Unbekannt" : name.trim();
        extension = extension == null ? "" : extension.trim();
    }

    /** Für Listen und Filter: Name und Endung, etwa {@code Program (NSP)}. */
    public String label() {
        return extension.isEmpty() ? name : name + " (" + extension + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NdvObjectType type && type.id == id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
