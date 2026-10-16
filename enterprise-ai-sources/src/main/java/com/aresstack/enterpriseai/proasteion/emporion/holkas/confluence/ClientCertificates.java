package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * TLS mit Client-Zertifikat (mTLS) für den Confluence-Transport, aus MainframeMate {@code ConfluenceRestClient}
 * übernommen: Das Zertifikat wird per Alias aus einem KeyStore gewählt und erzwungen, damit Java nicht
 * versehentlich ein abgelaufenes oder falsches Zertifikat anbietet.
 *
 * <p>Der private Schlüssel bleibt im KeyStore (bei {@code Windows-MY} im Windows-Zertifikatsspeicher); der
 * Adapter sieht ihn nie. Der Alias ist kein Secret und darf konfiguriert und geloggt werden.
 *
 * <p>Die Server-Vertrauensquellen gibt die Composition Root als {@link X509TrustManager} mit (JVM-Truststore,
 * Windows-Zertifikatspeicher, CA-Datei); ohne ihn prüft der JVM-Standard.
 */
public final class ClientCertificates {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ClientCertificates.class.getName());

    private ClientCertificates() {
    }

    /** Client-Zertifikat aus dem Windows-Zertifikatsspeicher des angemeldeten Benutzers (nur unter Windows). */
    public static SSLSocketFactory windowsMy(String alias) throws GeneralSecurityException {
        return windowsMy(alias, null);
    }

    /**
     * Wie {@link #windowsMy(String)}, Serverzertifikate prüft der übergebene Trust-Manager
     * ({@code null} = JVM-Standard).
     */
    public static SSLSocketFactory windowsMy(String alias, X509TrustManager trust) throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("Windows-MY");
        try {
            store.load(null, null);
        } catch (IOException e) {
            throw new GeneralSecurityException("Windows-Zertifikatsspeicher nicht lesbar", e);
        }
        return fromKeyStore(store, null, alias, trust);
    }

    /**
     * Client-Zertifikat aus einem beliebigen KeyStore (z. B. PKCS12-Datei).
     *
     * @param keyPassword Passwort des privaten Schlüssels oder {@code null} (Windows-MY)
     */
    public static SSLSocketFactory fromKeyStore(KeyStore store, char[] keyPassword, String alias)
            throws GeneralSecurityException {
        return fromKeyStore(store, keyPassword, alias, null);
    }

    /**
     * Wie {@link #fromKeyStore(KeyStore, char[], String)}, Serverzertifikate prüft der übergebene Trust-Manager
     * ({@code null} = JVM-Standard).
     */
    public static SSLSocketFactory fromKeyStore(KeyStore store, char[] keyPassword, String alias,
                                                X509TrustManager trust) throws GeneralSecurityException {
        if (alias == null || alias.trim().isEmpty()) {
            throw new IllegalArgumentException("Zertifikat-Alias fehlt");
        }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, keyPassword);
        X509KeyManager forced = forceAlias(findX509(factory.getKeyManagers()), alias.trim());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {forced}, trust == null ? null : new TrustManager[] {trust}, null);
        return context.getSocketFactory();
    }

    static X509KeyManager forceAlias(X509KeyManager delegate, String alias) throws GeneralSecurityException {
        X509Certificate[] chain = delegate.getCertificateChain(alias);
        boolean chainFound = chain != null && chain.length > 0;
        boolean keyFound = delegate.getPrivateKey(alias) != null;
        // Diagnose wie gewünscht: nur Alias und Booleans, nie Schlüsselmaterial.
        LOG.info("[Confluence] Zertifikat '" + alias + "': windowsMyChainFound=" + chainFound
                + " windowsMyPrivateKeyFound=" + keyFound);
        if (!chainFound) {
            throw new GeneralSecurityException("keine Zertifikatskette für Alias '" + alias + "'");
        }
        if (!keyFound) {
            throw new GeneralSecurityException("kein privater Schlüssel für Alias '" + alias + "'");
        }
        return new AliasForcingKeyManager(delegate, alias);
    }

    private static X509KeyManager findX509(KeyManager[] managers) throws GeneralSecurityException {
        for (KeyManager manager : managers) {
            if (manager instanceof X509KeyManager) {
                return (X509KeyManager) manager;
            }
        }
        throw new GeneralSecurityException("kein X509KeyManager verfügbar");
    }

    /** Bietet für Client-Authentifizierung ausschließlich den konfigurierten Alias an. */
    static final class AliasForcingKeyManager implements X509KeyManager {

        private final X509KeyManager delegate;
        private final String alias;

        AliasForcingKeyManager(X509KeyManager delegate, String alias) {
            this.delegate = delegate;
            this.alias = alias;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[] {alias};
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return alias;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return delegate.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return delegate.chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public X509Certificate[] getCertificateChain(String requested) {
            return delegate.getCertificateChain(requested);
        }

        @Override
        public PrivateKey getPrivateKey(String requested) {
            return delegate.getPrivateKey(requested);
        }
    }
}
