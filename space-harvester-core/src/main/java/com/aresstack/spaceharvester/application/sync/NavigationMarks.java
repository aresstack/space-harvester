package com.aresstack.spaceharvester.application.sync;

import java.util.Collection;
import java.util.Set;

/**
 * Der Erwerbsgrund „Navigation“ (ADR 0037, Punkt 3; Angelo 26.11.2026): Ressourcen, die die Navigationsbefüllung
 * beim Besuch eines Ordners erworben hat, je Quelle und Bereich. Nur dieser Grund wird gespeichert; den Grund
 * „Bestand“ leitet der Abgleich aus der Konfiguration ab, den Grund „ausdrücklich“ liefern die {@link IndexMarks}.
 *
 * <p>Eine Ressource mit mehreren Gründen bleibt ein Eintrag im Bestand. Abschalten der Navigationsbefüllung hebt keine
 * Markierung auf; nur eine nachgewiesene Löschung in der Quelle oder ein ausdrückliches „Bereinigen“ entfernt etwas.
 */
public interface NavigationMarks {

    /** Keine Ablage: nichts gemerkt; für Läufe ohne Datenverzeichnis. */
    NavigationMarks NONE = new NavigationMarks() {
        @Override
        public Set<String> pages(String sourceId, String spaceKey) {
            return Set.of();
        }

        @Override
        public void mark(String sourceId, String spaceKey, Collection<String> pageIds) {
        }

        @Override
        public Set<String> unknown(String sourceId, String spaceKey, Set<String> withoutReason) {
            return withoutReason;
        }

        @Override
        public void forgetUnknown(String sourceId, String spaceKey, Collection<String> pageIds) {
        }
    };

    /** Die über die Navigation erworbenen Seiten des Bereichs. */
    Set<String> pages(String sourceId, String spaceKey);

    /** Merkt den Erwerbsgrund „Navigation“ für diese Seiten. */
    void mark(String sourceId, String spaceKey, Collection<String> pageIds);

    /**
     * Welche der Seiten {@code withoutReason} ihren Grund nur deshalb nicht zeigen, weil sie vor dem Merken der Gründe
     * erworben wurden (Angelo 26.11.2026: Gruppe „Grund unbekannt“, nur auf ausdrückliches Ankreuzen bereinigt). Beim
     * ersten Aufruf je Bereich wird festgehalten: dann gilt alles in {@code withoutReason} als unbekannt; später nur, was
     * damals festgehalten wurde.
     */
    Set<String> unknown(String sourceId, String spaceKey, Set<String> withoutReason);

    /** Vergisst bereinigte Seiten; kommen sie wieder, haben sie einen gemerkten Grund oder keinen. */
    void forgetUnknown(String sourceId, String spaceKey, Collection<String> pageIds);
}
