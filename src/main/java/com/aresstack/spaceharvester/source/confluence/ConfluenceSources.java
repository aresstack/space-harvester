package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceSourceProvider;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import java.net.URI;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Baut den Confluence-Adapter aus {@code enterprise-ai-sources} für den headless Betrieb im Space Harvester:
 * Proxy-Route je Ziel aus dem übergebenen {@link HttpRoutePort} (#34) und je Quelle eigenes TLS. Server-Vertrauen
 * (JVM oder ein übergebener Trust-Manager) und Client-Zertifikat (mTLS) sind getrennt: Das Zertifikat bekommt nur der
 * Confluence-Host der Quelle, fremde Hosts sieht der Adapter nur mit dem Vertrauen ohne Zertifikat. Zugangsdaten
 * liefert ein {@link SecretProvider}; ohne Secrets arbeitet der Adapter anonym.
 */
public final class ConfluenceSources {

    private ConfluenceSources() {
    }

    /** Confluence-Provider ohne Secrets und ohne Proxy: Quellen ohne {@code credentialRef} werden anonym gelesen. */
    public static KnowledgeSourceProvider anonymousProvider(String userAgent) {
        return provider(noSecrets(), HttpRoutePort.direct(), userAgent);
    }

    /** Confluence-Provider mit dem TLS-Vertrauen der JVM. */
    public static KnowledgeSourceProvider provider(SecretProvider secrets, HttpRoutePort routes, String userAgent) {
        return provider(secrets, routes, userAgent, null, null);
    }

    /**
     * @param routes     Proxy-Route je Ziel-URL; eine UNAVAILABLE-Route lässt die Anfrage scheitern, nie still direkt
     * @param trust      Server-/Proxy-CAs der Quelle; {@code null} = Vertrauen der JVM
     * @param thumbprint erwarteter Fingerabdruck des Client-Zertifikats oder {@code null}
     */
    public static KnowledgeSourceProvider provider(SecretProvider secrets, HttpRoutePort routes, String userAgent,
                                                   X509TrustManager trust, String thumbprint) {
        return confluenceProvider(secrets, routes, userAgent, trust, thumbprint);
    }

    /**
     * Wie {@link #provider(SecretProvider, HttpRoutePort, String, X509TrustManager, String)}, dazu native
     * Seiten-Snapshots asynchron ({@code AsyncStoredContentSource}, #47) über einen Transport aus {@code async}.
     *
     * @param async Besitzer der asynchronen Transporte oder {@code null}: dann nur synchron
     */
    public static KnowledgeSourceProvider provider(SecretProvider secrets, HttpRoutePort routes, String userAgent,
                                                   X509TrustManager trust, String thumbprint,
                                                   AsyncSnapshotTransports async) {
        ConfluenceSourceProvider provider = confluenceProvider(secrets, routes, userAgent, trust, thumbprint);
        return async == null ? provider : async.attach(provider, trust, thumbprint);
    }

    private static ConfluenceSourceProvider confluenceProvider(SecretProvider secrets, HttpRoutePort routes,
                                                               String userAgent, X509TrustManager trust,
                                                               String thumbprint) {
        if (routes == null) {
            throw new IllegalArgumentException("routes must not be null");
        }
        return new ConfluenceSourceProvider(secrets, routes, trustOnly(trust), userAgent,
                new ConfiguredClientCertificates(secrets, trust, thumbprint));
    }

    /**
     * Delegierte Aufrufe an die Confluence einer Quelle (#24) über den asynchronen Transport (#47): dieselbe Route,
     * dasselbe Server-Vertrauen und dasselbe Client-Zertifikat wie {@link #provider}, aber mit dem Token des
     * Nutzers je Anfrage. Ein unbrauchbares Zertifikat scheitert hier sofort ({@link ClientCertificateException}).
     *
     * @param alias        Alias des Client-Zertifikats oder {@code null} ohne mTLS
     * @param keyStoreFile PKCS12-Datei oder {@code null} für Windows-MY
     * @param passwordRef  Verweis auf das Passwort der PKCS12-Datei oder {@code null}
     */
    public static DelegatedConfluenceHttp delegatedHttp(URI baseUrl, SecretProvider secrets, HttpRoutePort routes,
                                                        String userAgent, X509TrustManager trust, String thumbprint,
                                                        String alias, Path keyStoreFile, SecretRef passwordRef) {
        if (routes == null) {
            throw new IllegalArgumentException("routes must not be null");
        }
        SSLContext trustOnly = trustOnlyContext(trust);
        SSLContext clientTls = alias == null ? trustOnly
                : new ConfiguredClientCertificates(secrets, trust, thumbprint).sslContext(alias, keyStoreFile,
                        passwordRef);
        WebClientConfluenceTransport transport = WebClientConfluenceTransport.builder()
                .routes(routes)
                .clientTls(clientTls)
                .anonymousTls(trustOnly)
                .userAgent(userAgent)
                .build();
        return new DelegatedConfluenceHttp(baseUrl, transport, DelegatedConfluenceHttp.TIMEOUT);
    }

    /** {@code true}, wenn der Fingerabdruck 40 (SHA-1) oder 64 (SHA-256) Hex-Zeichen hat; Trenner sind erlaubt. */
    public static boolean validThumbprint(String thumbprint) {
        return ConfiguredClientCertificates.validThumbprint(thumbprint);
    }

    private static SSLSocketFactory trustOnly(X509TrustManager trust) {
        SSLContext context = trustOnlyContext(trust);
        return context == null ? null : context.getSocketFactory();
    }

    /** Nur die Vertrauensquellen der Quelle, ohne Client-Zertifikat; {@code null} = JVM-Standard. */
    static SSLContext trustOnlyContext(X509TrustManager trust) {
        if (trust == null) {
            return null;
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trust}, null);
            return context;
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
