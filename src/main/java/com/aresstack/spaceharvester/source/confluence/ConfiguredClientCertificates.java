package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ClientCertificates;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceSourceProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Lädt das Client-Zertifikat einer Quelle für den Confluence-Adapter: PKCS#12-Datei (Passwort über den
 * {@link SecretProvider}) oder {@code Windows-MY}. Den TLS-Kontext mit erzwungenem Alias baut die übernommene
 * {@link ClientCertificates}; hier kommen nur die Prüfungen davor hinzu (Alias vorhanden, privater Schlüssel,
 * Gültigkeit, optional Fingerabdruck), damit Fehler beim Start verständlich gemeldet werden statt erst beim
 * TLS-Handshake. Die Server-Vertrauensquellen bleiben dabei die der Quelle.
 */
final class ConfiguredClientCertificates implements ConfluenceSourceProvider.ClientCertificateSockets {

    private final SecretProvider secrets;
    private final X509TrustManager trust;
    private final String thumbprint;

    /**
     * @param trust      Server-Vertrauen der Quelle; {@code null} = JVM-Standard
     * @param thumbprint erwarteter SHA-1- oder SHA-256-Fingerabdruck (hex, Trenner erlaubt) oder {@code null}
     */
    ConfiguredClientCertificates(SecretProvider secrets, X509TrustManager trust, String thumbprint) {
        this.secrets = secrets;
        this.trust = trust;
        this.thumbprint = thumbprint == null || thumbprint.isBlank() ? null : normalize(thumbprint);
    }

    @Override
    public SSLSocketFactory socketFactory(String alias, Path keyStoreFile, SecretRef passwordRef) {
        char[] password = null;
        try {
            KeyStore store;
            if (keyStoreFile == null) {
                store = windowsMy();
            } else {
                password = password(passwordRef, keyStoreFile);
                store = pkcs12(keyStoreFile, password);
            }
            String where = keyStoreFile == null ? "Windows-MY" : keyStoreFile.toString();
            check(store, alias, where);
            return ClientCertificates.fromKeyStore(store, password, alias, trust);
        } catch (GeneralSecurityException e) {
            throw new ClientCertificateException("Client-Zertifikat '" + alias + "' nicht verwendbar: "
                    + e.getMessage(), e);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private static KeyStore windowsMy() {
        try {
            KeyStore store = KeyStore.getInstance("Windows-MY");
            store.load(null, null);
            return store;
        } catch (GeneralSecurityException | IOException e) {
            throw new ClientCertificateException("Windows-Zertifikatsspeicher (Windows-MY) nicht verfügbar; er "
                    + "existiert nur unter Windows mit dem Windows-JDK, sonst type pkcs12 verwenden", e);
        }
    }

    private char[] password(SecretRef ref, Path file) {
        if (ref == null) {
            throw new ClientCertificateException("Passwort für die PKCS12-Datei " + file + " fehlt");
        }
        try (SecretMaterial material = secrets.resolve(ref)) {
            return material.copySecret();
        } catch (SecretUnavailableException e) {
            throw new ClientCertificateException("Passwort für die PKCS12-Datei " + file + " nicht verfügbar", e);
        }
    }

    private static KeyStore pkcs12(Path file, char[] password) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new ClientCertificateException("PKCS12-Datei " + file + " fehlt oder ist nicht lesbar");
        }
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, password);
            return store;
        } catch (IOException e) {
            if (e.getCause() instanceof UnrecoverableKeyException) {
                throw new ClientCertificateException("PKCS12-Datei " + file + ": Passwort falsch", e);
            }
            throw new ClientCertificateException("PKCS12-Datei " + file + " nicht lesbar (falsches Passwort oder "
                    + "keine PKCS12-Datei)", e);
        } catch (GeneralSecurityException e) {
            throw new ClientCertificateException("PKCS12-Datei " + file + " nicht lesbar", e);
        }
    }

    private void check(KeyStore store, String alias, String where) throws GeneralSecurityException {
        if (!store.containsAlias(alias)) {
            throw new ClientCertificateException("Alias '" + alias + "' nicht in " + where + " gefunden");
        }
        if (!store.isKeyEntry(alias)) {
            throw new ClientCertificateException("Alias '" + alias + "' in " + where
                    + " hat keinen privaten Schlüssel");
        }
        Certificate certificate = store.getCertificate(alias);
        if (!(certificate instanceof X509Certificate x509)) {
            throw new ClientCertificateException("Alias '" + alias + "' in " + where + " ist kein X.509-Zertifikat");
        }
        try {
            x509.checkValidity();
        } catch (CertificateExpiredException e) {
            throw new ClientCertificateException("Client-Zertifikat '" + alias + "' in " + where
                    + " ist abgelaufen (gültig bis " + x509.getNotAfter().toInstant() + ")", e);
        } catch (CertificateNotYetValidException e) {
            throw new ClientCertificateException("Client-Zertifikat '" + alias + "' in " + where
                    + " ist noch nicht gültig (ab " + x509.getNotBefore().toInstant() + ")", e);
        }
        if (thumbprint != null) {
            String algorithm = thumbprint.length() == 40 ? "SHA-1" : "SHA-256";
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(x509.getEncoded()));
            if (!actual.equals(thumbprint)) {
                throw new ClientCertificateException("Client-Zertifikat '" + alias + "' in " + where
                        + " hat nicht den konfigurierten Fingerabdruck");
            }
        }
    }

    /** Kleinbuchstaben ohne Trenner (Leerzeichen, Doppelpunkt), wie Windows und keytool ihn anzeigen. */
    static String normalize(String thumbprint) {
        return thumbprint.replaceAll("[\\s:]", "").toLowerCase(Locale.ROOT);
    }

    /** {@code true} bei 40 (SHA-1) oder 64 (SHA-256) Hex-Zeichen nach {@link #normalize(String)}. */
    static boolean validThumbprint(String thumbprint) {
        return normalize(thumbprint).matches("[0-9a-f]{40}|[0-9a-f]{64}");
    }
}
