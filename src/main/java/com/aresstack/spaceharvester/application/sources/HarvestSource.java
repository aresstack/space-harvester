package com.aresstack.spaceharvester.application.sources;

import java.util.List;

/**
 * Eine konfigurierte Wissensquelle aus Sicht der Anwendungsfälle, unabhängig von ihrem Typ (Confluence, später
 * weitere). Umgesetzt im Paket {@code source}.
 */
public interface HarvestSource {

    /** Quellen-ID aus der Konfiguration, z. B. {@code confluence}. */
    String id();

    /** Quellentyp, z. B. {@code confluence}. */
    String type();

    /** Anzeigename. */
    String title();

    /**
     * Alle Partitionen der Quelle (bei Confluence: Spaces), die mit den konfigurierten Zugangsdaten sichtbar sind;
     * ungekürzt.
     *
     * @throws SourceAccessException wenn die Quelle nicht erreichbar ist oder den Zugriff verweigert
     */
    List<Partition> partitions();
}
