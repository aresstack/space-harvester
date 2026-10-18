package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.configuration.ConfigurationSnapshot;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.SearchSettings;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.TreeSet;

/**
 * Die wirksame Konfiguration für {@code GET /api/v1/configuration}, ohne Geheimnisse: Vom API-Key und vom
 * Proxy-Passwort steht nur, ob sie gesetzt sind. Adressen ohne Query und Zugangsdaten, Dauern und Zeitpunkte als
 * ISO-8601-Text.
 *
 * @param version       wirksame Version, 1 nach dem Start
 * @param activatedAt   ihre Aktivierung
 * @param configuration die Werte
 */
public record ConfigurationResponse(long version, String activatedAt, Configuration configuration) {

    static ConfigurationResponse of(ConfigurationSnapshot snapshot, NetworkDescription network) {
        HarvesterConfiguration c = snapshot.configuration();
        return new ConfigurationResponse(snapshot.version(), snapshot.activatedAt().toString(),
                new Configuration(Ai.of(c.ai()), Search.of(c), Sync.of(c.sync()), network.describe(c.network()),
                        new Storage(c.storage().dataDirectory() == null ? null
                                : c.storage().dataDirectory().toString())));
    }

    public record Configuration(Ai ai, Search search, Sync sync, Network network, Storage storage) {
    }

    /**
     * @param apiKeySet ob ein API-Key gesetzt ist; der Key selbst erscheint nie
     */
    public record Ai(String baseUrl, boolean apiKeySet, String chatModel, String embeddingModel,
                     Integer embeddingDimensions, String timeout, String trustBundle,
                     List<String> externalProcessingSources, String rerankModel) {

        static Ai of(AiServiceSettings ai) {
            return new Ai(withoutSecrets(ai.baseUrl()), ai.apiKey() != null, ai.chatModel(), ai.embeddingModel(),
                    ai.embeddingDimensions(), text(ai.timeout()), ai.trustBundle(),
                    List.copyOf(new TreeSet<>(ai.externalProcessingSources())), ai.rerankModel());
        }
    }

    /**
     * @param semanticActive  ob die semantische Suche tatsächlich läuft (Haken und Embedding-Modell)
     * @param rerankingActive ob Treffer umgeordnet werden (Option und Rerank-Modell)
     */
    public record Search(boolean semantic, boolean rerank, boolean prefetchHits, String cacheMaxAge,
                         boolean cacheVersionCheck, boolean semanticActive, boolean rerankingActive) {

        static Search of(HarvesterConfiguration c) {
            SearchSettings s = c.search();
            return new Search(s.semantic(), s.rerank(), s.prefetchHits(), text(s.cacheFreshness().maxAge()),
                    s.cacheFreshness().versionCheck(), c.semanticSearch(), c.reranking());
        }
    }

    public record Sync(boolean events, String interval, List<String> spaces, boolean beforeSearch,
                       String withheldRetention) {

        static Sync of(SyncSettings s) {
            return new Sync(s.events(), text(s.interval()), s.spaces(), s.beforeSearch(), text(s.withheldRetention()));
        }
    }

    public record Network(Proxy proxy, Tls tls) {
    }

    /**
     * @param mode        wie in {@code network.proxy.mode}, etwa {@code manual-proxy}
     * @param username    Benutzername der Anmeldung am Proxy oder {@code null}
     * @param passwordSet ob ein Proxy-Passwort gesetzt ist; das Passwort selbst erscheint nie
     */
    public record Proxy(String mode, String pacUrl, String proxyHost, Integer proxyPort, List<String> nonProxyHosts,
                        String resolveTimeout, String cacheTtl, String failureTtl, String username,
                        boolean passwordSet, boolean customPacDiscoveryScript) {
    }

    public record Tls(boolean useJvmDefault, boolean useWindowsRoot, boolean useWindowsCaStores,
                      String caCertificatesFile) {
    }

    /**
     * @param dataDirectory Speicherort ({@code cache.directory}) oder {@code null}
     */
    public record Storage(String dataDirectory) {
    }

    /** Schema, Host, Port und Pfad; ohne Zugangsdaten, Query und Fragment, die Geheimnisse tragen könnten. */
    public static String withoutSecrets(URI uri) {
        if (uri == null) {
            return null;
        }
        if (uri.getHost() == null) {
            // etwa file:/pfad/proxy.pac
            return uri.getScheme() == null ? null
                    : uri.getScheme() + ":" + (uri.getRawPath() == null ? "" : uri.getRawPath());
        }
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }

    /** Dauer als ISO-8601-Text oder {@code null}. */
    public static String text(Duration duration) {
        return duration == null ? null : duration.toString();
    }
}
