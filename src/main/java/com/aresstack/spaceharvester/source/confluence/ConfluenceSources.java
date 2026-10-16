package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceSourceProvider;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Baut den Confluence-Adapter aus {@code enterprise-ai-sources} für den headless Betrieb im Space Harvester:
 * direkte HTTP-Route und je Quelle eigenes TLS. Server-Vertrauen (JVM oder ein übergebener Trust-Manager) und
 * Client-Zertifikat (mTLS) sind getrennt: Das Zertifikat bekommt nur der Confluence-Host der Quelle, fremde Hosts
 * sieht der Adapter nur mit dem Vertrauen ohne Zertifikat. Zugangsdaten liefert ein {@link SecretProvider}; ohne
 * Secrets arbeitet der Adapter anonym.
 */
public final class ConfluenceSources {

    private ConfluenceSources() {
    }

    /** Confluence-Provider ohne Secrets: Quellen ohne {@code credentialRef} werden anonym gelesen. */
    public static KnowledgeSourceProvider anonymousProvider(String userAgent) {
        return provider(noSecrets(), userAgent);
    }

    /** Confluence-Provider mit dem TLS-Vertrauen der JVM. */
    public static KnowledgeSourceProvider provider(SecretProvider secrets, String userAgent) {
        return provider(secrets, userAgent, null, null);
    }

    /**
     * @param trust      Server-/Proxy-CAs der Quelle; {@code null} = Vertrauen der JVM
     * @param thumbprint erwarteter Fingerabdruck des Client-Zertifikats oder {@code null}
     */
    public static KnowledgeSourceProvider provider(SecretProvider secrets, String userAgent, X509TrustManager trust,
                                                   String thumbprint) {
        return new ConfluenceSourceProvider(secrets, HttpRoutePort.direct(), trustOnly(trust), userAgent,
                new ConfiguredClientCertificates(secrets, trust, thumbprint));
    }

    /** {@code true}, wenn der Fingerabdruck 40 (SHA-1) oder 64 (SHA-256) Hex-Zeichen hat; Trenner sind erlaubt. */
    public static boolean validThumbprint(String thumbprint) {
        return ConfiguredClientCertificates.validThumbprint(thumbprint);
    }

    private static SSLSocketFactory trustOnly(X509TrustManager trust) {
        if (trust == null) {
            return null;
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trust}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS-Kontext für die Vertrauensquellen nicht erstellbar", e);
        }
    }

    private static SecretProvider noSecrets() {
        return ref -> {
            throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_AVAILABLE, ref,
                    "im Space Harvester ist noch kein Secret-Speicher konfiguriert");
        };
    }
}
