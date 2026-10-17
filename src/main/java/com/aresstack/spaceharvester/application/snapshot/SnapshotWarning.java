package com.aresstack.spaceharvester.application.snapshot;

import java.util.Objects;

/**
 * Hinweis zu einem Snapshot, etwa auf unvollständige Zusatzdaten der Quelle (#8). Ein Snapshot mit Warnungen bleibt
 * gültig; die Warnung sagt nur, welcher Teil nicht vollständig ist. Enthält nie Seiteninhalte oder Zugangsdaten.
 *
 * @param code   maschinenlesbar, z. B. {@value #ATTRIBUTES_INCOMPLETE}
 * @param detail worauf sie sich bezieht, z. B. der Attributname {@code confluence.labels}
 */
public record SnapshotWarning(String code, String detail) {

    /** Ein Attribut der Quelle war gekürzt (bei Confluence {@code metadata.labels} mit {@code _links.next}). */
    public static final String ATTRIBUTES_INCOMPLETE = "attributes-incomplete";

    public SnapshotWarning {
        Objects.requireNonNull(code, "code");
        detail = detail == null ? "" : detail;
    }
}
