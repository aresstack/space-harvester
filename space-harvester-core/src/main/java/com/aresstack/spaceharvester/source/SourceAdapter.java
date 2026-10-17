package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.spaceharvester.source.confluence.AsyncSnapshotTransports;
import com.aresstack.spaceharvester.source.confluence.DelegatedConfluenceHttp;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import java.util.List;

/**
 * Ein Quellentyp für {@link ConfiguredSourceCatalog} (#18): prüft die Einstellungen einer Quelle dieses Typs und
 * öffnet sie als {@link KnowledgeSourcePort} aus {@code enterprise-ai-sources}. Was die Quelle kann (Partitionen,
 * Seitenlisten, Einzelmetadaten, Originale, asynchron), ergibt sich aus den optionalen Fähigkeiten des Ports; die
 * REST-API und die Anwendungsfälle kennen nur {@code HarvestSource}. Neue Typen kommen als weitere Umsetzung dazu,
 * im Dienst als Spring-Bean.
 */
public interface SourceAdapter {

    /** Wert von {@code type} in der Quellenkonfiguration, z. B. {@code confluence}. */
    String type();

    /**
     * Typeigene Probleme der Einstellungen, ohne etwas zu öffnen; ID und Typ prüft der Katalog.
     *
     * @return leer, wenn die Einstellungen passen; sonst je Problem ein Satz mit dem Feldnamen
     */
    List<String> check(SourceSettingsConfig config);

    /**
     * Öffnet die Quelle; Verbindungen entstehen erst bei der ersten Anfrage.
     *
     * @throws InvalidSourceConfigurationException mit den Problemen, wenn die Quelle so nicht betrieben werden kann
     */
    OpenedSource open(SourceSettingsConfig config, Context context);

    /**
     * Was der Katalog allen Typen mitgibt.
     *
     * @param secrets      Zugangsdaten der Quellen, je Quelle unter {@link ConfiguredSecrets#refFor}
     * @param routes       Proxy-Route je Ziel-URL
     * @param userAgent    User-Agent für HTTP-Aufrufe
     * @param trustBundles Vertrauensquellen für {@code trust-bundle}
     * @param async        asynchrone Transporte für Snapshots oder {@code null}
     */
    record Context(ConfiguredSecrets secrets, ProxyRoutes routes, String userAgent, TrustBundles trustBundles,
                   AsyncSnapshotTransports async) {
    }

    /**
     * Eine geöffnete Quelle.
     *
     * @param port      der Port aus {@code enterprise-ai-sources}
     * @param delegated delegierte Aufrufe mit Nutzertoken (#24) oder {@code null}, wenn der Typ keine hat
     */
    record OpenedSource(KnowledgeSourcePort port, DelegatedConfluenceHttp delegated) {
        public OpenedSource {
            if (port == null) {
                throw new IllegalArgumentException("port must not be null");
            }
        }
    }
}
