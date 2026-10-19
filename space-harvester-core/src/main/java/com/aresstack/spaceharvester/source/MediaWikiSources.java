package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.proasteion.application.security.SecretBackedMediaWikiCredentialsProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.MediaWikiSourceProvider;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Quellentyp {@code mediawiki} (#187): {@link MediaWikiSourceProvider} aus {@code enterprise-ai-sources} über
 * {@link KnowledgeSourceProviderAdapter#connected}, verdrahtet wie {@code AdapterAssembly.sourceProviders} im
 * Enterprise AI Client. Seiten ab den Startpunkten entlang der Wiki-Links ({@code discover}), gelesen als gerendertes
 * HTML in Klartext ({@code load}), Revision aus {@code touched} und {@code lastrevid}.
 *
 * <p>Einstellungen unter {@code options} mit den Schlüsseln des Upstream-Typs: {@code apiUrl} (Pflicht),
 * {@code startPoints} (Pflicht), {@code maxDepth}, {@code maxResources}, {@code requiresLogin},
 * {@code linkNamespaces}, {@code connectTimeoutMillis}, {@code readTimeoutMillis}, {@code userAgent}; {@code title}
 * wird {@code displayName}. Anmeldung mit {@code auth.type basic} (Benutzer und Passwort, etwa ein Bot-Passwort):
 * der Adapter fordert sie erst beim Login über {@link SecretBackedMediaWikiCredentialsProvider} unter
 * {@code source:<id>} an, fehlt das Passwort, vom Anbieter des Programms (#188).
 *
 * <p>HTTP macht der Upstream-Adapter selbst, synchron über {@code HttpURLConnection} wie im Enterprise AI Client: je
 * Verbindung die Route des Katalogs ({@code DIRECT}, Proxy oder Abbruch, nie still direkt) und die Socket-Fabrik der
 * Quelle (Vertrauen, mTLS). Einen asynchronen Transport (WebClient) kann der Adapter nicht aufnehmen, seine
 * Transport-Naht ist paketintern.
 */
public final class MediaWikiSources {

    public static final String TYPE = MediaWikiSourceProvider.TYPE_ID;

    /**
     * Der Crawler der Kopie meldet übersprungene Seiten und Linkfehler mit Seitentitel (FINE und WARNING); Titel
     * gehören nicht ins Protokoll des Space Harvester. Die Referenz hält die Einstellung fest, sonst könnte
     * {@code java.util.logging} den Logger samt Stufe verwerfen.
     */
    private static final Logger CRAWLER =
            Logger.getLogger("com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.WikiCrawler");

    static {
        CRAWLER.setLevel(Level.SEVERE);
    }

    private MediaWikiSources() {
    }

    /** Der Quellentyp {@code mediawiki}. */
    public static KnowledgeSourceProviderAdapter adapter() {
        return KnowledgeSourceProviderAdapter.connected(connection -> new MediaWikiSourceProvider(
                ref -> new SecretBackedMediaWikiCredentialsProvider(connection.secrets(), ref), connection.routes(),
                connection.tls(), connection.userAgent()), "credentialRef", "displayName");
    }
}
