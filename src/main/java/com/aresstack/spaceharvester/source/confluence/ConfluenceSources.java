package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceSourceProvider;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;

/**
 * Baut den Confluence-Adapter aus {@code enterprise-ai-sources} für den headless Betrieb im Space Harvester:
 * direkte HTTP-Route, TLS-Vertrauen der JVM und keine Client-Zertifikate (mTLS ist im Webdienst noch nicht
 * vorgesehen). Zugangsdaten liefert ein {@link SecretProvider}; ohne Secrets arbeitet der Adapter anonym.
 */
public final class ConfluenceSources {

    private ConfluenceSources() {
    }

    /** Confluence-Provider ohne Secrets: Quellen ohne {@code credentialRef} werden anonym gelesen. */
    public static KnowledgeSourceProvider anonymousProvider(String userAgent) {
        return provider(noSecrets(), userAgent);
    }

    public static KnowledgeSourceProvider provider(SecretProvider secrets, String userAgent) {
        return new ConfluenceSourceProvider(secrets, HttpRoutePort.direct(), null, userAgent,
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
