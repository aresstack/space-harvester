package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPSClient;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * FTPS für die FTP- und JES-Sitzungen über {@link FTPSClient} aus Commons Net: explizit ({@code AUTH TLS} auf dem
 * Steuerkanal) oder implizit (TLS ab dem ersten Byte, Standardport 990). Nach der Anmeldung {@code PBSZ 0} und
 * {@code PROT P}, damit auch die Datenverbindungen verschlüsselt sind. Vertrauen: die Zertifikate eines PEM- oder
 * DER-Bundles ({@code trustBundle}) oder, ohne Bundle, die Vertrauensquellen der JVM. Der Hostname wird gegen das
 * Zertifikat geprüft. Fehlermeldungen nennen weder Host noch Zertifikatsinhalt, nur die Art des TLS-Fehlers.
 */
final class FtpTls {

    private FtpTls() {
    }

    /** Ein Client für die Einstellung: ohne TLS das bisherige {@link FTPClient}, sonst ein {@link FTPSClient}. */
    static FTPClient client(FtpConnectionSettings settings) throws IOException {
        if (settings.tls == FtpConnectionSettings.Tls.NONE) {
            return new FTPClient();
        }
        FTPSClient client = new FTPSClient("TLS", settings.tls == FtpConnectionSettings.Tls.IMPLICIT);
        // Der Standard von Commons Net prüft nur die Gültigkeitsdauer, nicht die Kette.
        client.setTrustManager(trustManager(settings.trustBundle));
        client.setEndpointCheckingEnabled(true);
        return client;
    }

    /** {@code PBSZ 0} und {@code PROT P} nach der Anmeldung; ohne TLS nichts. */
    static void protect(FTPClient client) throws IOException {
        if (client instanceof FTPSClient) {
            FTPSClient ftps = (FTPSClient) client;
            ftps.execPBSZ(0);
            ftps.execPROT("P");
        }
    }

    /**
     * Ersetzt einen TLS-Fehler durch einen ohne Host- und Zertifikatsangaben (die JDK-Meldungen nennen beides);
     * andere Fehler bleiben, wie sie sind.
     */
    static IOException neutral(IOException e) {
        if (e instanceof SSLException) {
            return new IOException("TLS-Verbindung fehlgeschlagen (" + e.getClass().getSimpleName()
                    + "; Zertifikat, Vertrauen oder Name prüfen)");
        }
        return e;
    }

    /**
     * Prüft Vertrauen gegen die Zertifikate des Bundles; {@code null} = Vertrauensquellen der JVM.
     *
     * @throws IOException wenn das Bundle fehlt, nicht lesbar ist oder kein Zertifikat enthält
     */
    static X509TrustManager trustManager(Path bundle) throws IOException {
        try {
            KeyStore store = null;
            if (bundle != null) {
                store = KeyStore.getInstance(KeyStore.getDefaultType());
                store.load(null, null);
                int count = 0;
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                try (InputStream in = Files.newInputStream(bundle)) {
                    for (Certificate certificate : factory.generateCertificates(in)) {
                        if (certificate instanceof X509Certificate) {
                            store.setCertificateEntry("bundle-" + count++, certificate);
                        }
                    }
                }
                if (count == 0) {
                    throw new IOException("Trust-Bundle enthält kein Zertifikat");
                }
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager) {
                    return (X509TrustManager) manager;
                }
            }
            throw new IOException("Kein X.509-TrustManager verfügbar");
        } catch (GeneralSecurityException e) {
            throw new IOException("Trust-Bundle nicht lesbar (" + e.getClass().getSimpleName() + ")", e);
        }
    }
}
