package com.aresstack.spaceharvester.application.changelog.topics;

import java.util.Objects;

/**
 * Ein Unterschied in den Aussagen einer Änderungseinheit (ADR 0024, ADR 0030 Nachtrag Abschnitt 4), vorverarbeitete
 * Angabe für das Modell. Experimentell; nur wo ein Satzanalysator belegt ist, nur für Einheiten mit KI-Freigabe und
 * nur im Auftrag „Zusammenfassen“.
 *
 * @param kind   neue, entfallene oder geänderte Aussage
 * @param unitId die Änderungseinheit
 * @param before die Aussage vorher als kurzer Wortlaut; {@code null} bei {@link Kind#ADDED}
 * @param after  die Aussage nachher als kurzer Wortlaut; {@code null} bei {@link Kind#REMOVED}
 */
public record StatementDelta(Kind kind, String unitId, String before, String after) {

    public enum Kind { ADDED, REMOVED, CHANGED }

    public StatementDelta {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(unitId, "unitId");
    }
}
