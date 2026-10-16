package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceSourceProvider;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;

/**
 * Baut den Confluence-Adapter aus {@code enterprise-ai-sources} für den headless Betrieb im Space Harvester:
 * Proxy-Route je Ziel aus dem übergebenen {@link HttpRoutePort} (#34), TLS-Vertrauen der JVM und keine
 * Client-Zertifikate (mTLS ist im Webdienst noch nicht vorgesehen). Zugangsdaten liefert ein {@link SecretProvider};
 * ohne Secrets arbeitet der Adapter anonym.
 */
public final class ConfluenceSources {

    private ConfluenceSources() {
    }

    /** Confluence-Provider ohne Secrets und ohne Proxy: Quellen ohne {@code credentialRef} werden anonym gelesen. */
    public static KnowledgeSourceProvider anonymousProvider(String userAgent) {
        return provider(noSecrets(), HttpRoutePort.direct(), userAgent);
    }

    /**
     * @param routes Proxy-Route je Ziel-URL; eine UNAVAILABLE-Route lässt die Anfrage scheitern, nie still direkt
     */
    public static KnowledgeSourceProvider provider(SecretProvider secrets, HttpRoutePort routes, String userAgent) {
        if (routes == null) {
            throw new IllegalArgumentException("routes must not be null");
        }
        return new ConfluenceSourceProvider(secrets, routes, null, userAgent,
                (alias, keyStoreFile, passwordRef) -> {
                    throw new UnsupportedOperationException(
                            "Client-Zertifikate (mTLS) werden vom Space Harvester noch nicht unterstützt");
                });
    }

    private static SecretProvider noSecrets() {
        return ref -> {
            throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_AVAILABLE, ref,
                    "im Space Harvester ist noch kein Secret-Speicher konfiguriert");
        };
    }
}
