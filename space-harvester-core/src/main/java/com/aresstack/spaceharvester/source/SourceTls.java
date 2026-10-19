package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ClientCertificates;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.CertificateStoreType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.ClientCertificate;
import com.aresstack.spaceharvester.source.confluence.ConfluenceSources;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS einer Quelle mit HTTP über einen Upstream-Provider (#187): Server-Vertrauen aus {@code trust-bundle} oder dem
 * Vertrauen ohne Bundle, dazu auf Wunsch das Client-Zertifikat (mTLS) aus einer PKCS#12-Datei oder {@code Windows-MY},
 * mit erzwungenem Alias über {@link ClientCertificates} aus der Kopie. Dieselben Felder und Prüfungen wie beim
 * Confluence-Typ; Fehler nennen Feld und Alias, nie ein Passwort.
 */
final class SourceTls {

    private SourceTls() {
    }

    /** Probleme von {@code client-certificate} ohne etwas zu laden; ein abgeschaltetes Zertifikat hat keine. */
    static List<String> problems(ClientCertificate certificate, boolean externalSecrets) {
        List<String> problems = new ArrayList<>();
        if (!certificate.enabled()) {
            return problems;
        }
        boolean path = certificate.path() != null;
        boolean password = present(certificate.password());
        if (certificate.type() == null) {
            problems.add("client-certificate.type fehlt (pkcs12 oder windows-my)");
        }
        if (!present(certificate.alias())) {
            problems.add("client-certificate.alias fehlt");
        }
        if (certificate.type() == CertificateStoreType.PKCS12) {
            if (!path) {
                problems.add("client-certificate.type pkcs12 braucht client-certificate.path");
            }
            if (!password && !externalSecrets) {
                problems.add("client-certificate.type pkcs12 braucht client-certificate.password");
            }
        }
        if (certificate.type() == CertificateStoreType.WINDOWS_MY && (path || password)) {
            problems.add("client-certificate.path/client-certificate.password passen nicht zu client-certificate.type "
                    + "windows-my");
        }
        if (present(certificate.thumbprint()) && !ConfluenceSources.validThumbprint(certificate.thumbprint())) {
            problems.add("client-certificate.thumbprint ist kein SHA-1- (40) oder SHA-256-Fingerabdruck (64 "
                    + "Hex-Zeichen)");
        }
        return problems;
    }

    /**
     * Die Socket-Fabrik der Quelle; {@code null} heißt JVM-Standard (kein Bundle, kein eigenes Vertrauen, kein
     * Zertifikat). Ein unbrauchbares Zertifikat scheitert hier, beim Öffnen, statt die Quelle ohne mTLS zu betreiben.
     *
     * @throws InvalidSourceConfigurationException mit {@code trust-bundle:} oder {@code client-certificate:}
     */
    static SSLSocketFactory socketFactory(SourceSettingsConfig config, SourceAdapter.Context context) {
        X509TrustManager trust;
        try {
            trust = config.trustBundle() == null ? context.trustBundles().defaultTrust()
                    : context.trustBundles().trustManager(config.trustBundle());
        } catch (IllegalArgumentException e) {
            throw new InvalidSourceConfigurationException(List.of("trust-bundle: " + e.getMessage()));
        }
        ClientCertificate certificate = config.clientCertificate();
        try {
            if (!certificate.enabled()) {
                return trust == null ? null : trustOnly(trust).getSocketFactory();
            }
            String alias = certificate.alias().trim();
            char[] password = null;
            try {
                KeyStore store;
                String where;
                if (certificate.type() == CertificateStoreType.PKCS12) {
                    password = password(config, context);
                    store = pkcs12(certificate.path(), password);
                    where = certificate.path().toString();
                } else {
                    store = KeyStore.getInstance("Windows-MY");
                    store.load(null, null);
                    where = "Windows-MY";
                }
                check(store, alias, where, certificate.thumbprint());
                return ClientCertificates.contextFromKeyStore(store, password, alias, trust).getSocketFactory();
            } finally {
                if (password != null) {
                    Arrays.fill(password, '\0');
                }
            }
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new InvalidSourceConfigurationException(List.of("client-certificate: Zertifikat '"
                    + certificate.alias() + "' nicht verwendbar (" + e.getClass().getSimpleName() + ")"));
        }
    }

    /** Das Passwort der PKCS#12-Datei aus der Konfiguration, sonst vom Anbieter des Programms (#188). */
    private static char[] password(SourceSettingsConfig config, SourceAdapter.Context context) {
        if (present(config.clientCertificate().password())) {
            return config.clientCertificate().password().toCharArray();
        }
        try (SecretMaterial material = context.secrets().resolve(ConfiguredSecrets.certificateRefFor(config.id()))) {
            return material.copySecret();
        } catch (SecretUnavailableException e) {
            throw new InvalidSourceConfigurationException(List.of("client-certificate: Passwort der PKCS12-Datei "
                    + "nicht verfügbar"));
        }
    }

    private static KeyStore pkcs12(Path file, char[] password) throws IOException, GeneralSecurityException {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new InvalidSourceConfigurationException(List.of("client-certificate: PKCS12-Datei " + file
                    + " fehlt oder ist nicht lesbar"));
        }
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, password);
            return store;
        }
    }

    private static void check(KeyStore store, String alias, String where, String thumbprint)
            throws GeneralSecurityException {
        if (!store.containsAlias(alias) || !store.isKeyEntry(alias)) {
            throw new InvalidSourceConfigurationException(List.of("client-certificate: Alias '" + alias + "' in "
                    + where + " fehlt oder hat keinen privaten Schlüssel"));
        }
        Certificate certificate = store.getCertificate(alias);
        if (!(certificate instanceof X509Certificate x509)) {
            throw new InvalidSourceConfigurationException(List.of("client-certificate: Alias '" + alias + "' in "
                    + where + " ist kein X.509-Zertifikat"));
        }
        try {
            x509.checkValidity();
        } catch (CertificateExpiredException | CertificateNotYetValidException e) {
            throw new InvalidSourceConfigurationException(List.of("client-certificate: Zertifikat '" + alias
                    + "' in " + where + " ist nicht gültig (" + x509.getNotBefore().toInstant() + " bis "
                    + x509.getNotAfter().toInstant() + ")"));
        }
        if (present(thumbprint)) {
            String expected = thumbprint.replaceAll("[\\s:]", "").toLowerCase(Locale.ROOT);
            String algorithm = expected.length() == 40 ? "SHA-1" : "SHA-256";
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(x509.getEncoded()));
            if (!actual.equals(expected)) {
                throw new InvalidSourceConfigurationException(List.of("client-certificate: Zertifikat '" + alias
                        + "' in " + where + " hat nicht den konfigurierten Fingerabdruck"));
            }
        }
    }

    private static SSLContext trustOnly(X509TrustManager trust) throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trust}, null);
        return context;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
