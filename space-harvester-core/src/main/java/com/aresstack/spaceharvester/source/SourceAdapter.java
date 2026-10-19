package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.spaceharvester.source.confluence.AsyncSnapshotTransports;
import com.aresstack.spaceharvester.source.confluence.DelegatedConfluenceHttp;
import com.aresstack.spaceharvester.source.network.ProxyCredentials;
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

    /** Anzeigename des Typs für Auswahllisten, z. B. {@code Git-Repository}; ohne eigenen Namen der Typ. */
    default String displayName() {
        return type();
    }

    /**
     * Ob der Typ einen Eintrag {@code options.credentialRef} verlangt, den nur der {@code SecretProvider} des
     * einbettenden Programms auflöst (#188).
     */
    default boolean needsCredentials() {
        return false;
    }

    /**
     * Typeigene Probleme der Einstellungen, ohne etwas zu öffnen; ID und Typ prüft der Katalog.
     *
     * @return leer, wenn die Einstellungen passen; sonst je Problem ein Satz mit dem Feldnamen
     */
    List<String> check(SourceSettingsConfig config);

    /**
     * Wie {@link #check(SourceSettingsConfig)}; mit {@code externalSecrets} kommen fehlende Passwörter und Tokens vom
     * {@code SecretProvider} des einbettenden Programms (#188) und sind kein Problem. Typen ohne Zugangsdaten
     * überschreiben das nicht.
     */
    default List<String> check(SourceSettingsConfig config, boolean externalSecrets) {
        return check(config);
    }

    /**
     * Öffnet die Quelle; Verbindungen entstehen erst bei der ersten Anfrage.
     *
     * @throws InvalidSourceConfigurationException mit den Problemen, wenn die Quelle so nicht betrieben werden kann
     */
    OpenedSource open(SourceSettingsConfig config, Context context);

    /**
     * Was der Katalog allen Typen mitgibt.
     *
     * @param secrets      Zugangsdaten der Quellen, je Quelle unter {@link ConfiguredSecrets#refFor}; was dort fehlt,
     *                     liefert der Anbieter des einbettenden Programms, sofern es einen gibt
     * @param routes       Proxy-Route je Ziel-URL
     * @param userAgent    User-Agent für HTTP-Aufrufe
     * @param trustBundles Vertrauensquellen für {@code trust-bundle}
     * @param async        asynchrone Transporte für Snapshots oder {@code null}
     * @param proxyCredentials Anmeldung am Proxy für WebClient-Transporte (#189); {@code null} ohne
     */
    record Context(ConfiguredSecrets secrets, ProxyRoutes routes, String userAgent, TrustBundles trustBundles,
                   AsyncSnapshotTransports async, ProxyCredentials proxyCredentials) {

        public Context {
            proxyCredentials = proxyCredentials == null ? ProxyCredentials.none() : proxyCredentials;
        }
    }

    /**
     * Eine geöffnete Quelle.
     *
     * @param port      der Port aus {@code enterprise-ai-sources}
     * @param delegated delegierte Aufrufe mit Nutzertoken (#24) oder {@code null}, wenn der Typ keine hat
     * @param scope     Umfang für Quellen ohne {@code PartitionListingSource} (#187): Startpunkte, Tiefe und
     *                  Höchstzahl, mit denen der Core {@code discover} aufruft, wie die Indexierung im Enterprise AI
     *                  Client ({@code KnowledgeSourceProvider.scope}); {@code null} ohne diesen Weg
     */
    record OpenedSource(KnowledgeSourcePort port, DelegatedConfluenceHttp delegated, SourceScope scope) {
        public OpenedSource {
            if (port == null) {
                throw new IllegalArgumentException("port must not be null");
            }
        }

        /** Eine Quelle ohne Umfang für {@code discover}, etwa mit eigener Seitenliste je Partition. */
        public OpenedSource(KnowledgeSourcePort port, DelegatedConfluenceHttp delegated) {
            this(port, delegated, null);
        }

        /**
         * Eine Quelle, deren Seiten der Core über {@code discover} im Umfang {@code scope} findet und über
         * {@code load} liest, als eine Partition je Quelle (#187); hat der Port eine eigene Seitenliste je Partition,
         * gilt diese.
         */
        public static OpenedSource discovering(KnowledgeSourcePort port, SourceScope scope) {
            if (scope == null) {
                throw new IllegalArgumentException("scope must not be null");
            }
            return new OpenedSource(port, null, scope);
        }
    }
}
