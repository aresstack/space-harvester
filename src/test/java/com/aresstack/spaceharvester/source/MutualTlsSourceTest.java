package com.aresstack.spaceharvester.source;

import static com.aresstack.spaceharvester.source.tls.TestCertificates.CLIENT_ALIAS;
import static com.aresstack.spaceharvester.source.tls.TestCertificates.CLIENT_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.Auth;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.AuthType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.CertificateStoreType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.ClientCertificate;
import com.aresstack.spaceharvester.source.tls.HttpsFakeConfluenceServer;
import com.aresstack.spaceharvester.source.tls.TestCertificates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.KeyStore;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * mTLS gegen einen lokalen HTTPS-Server, der ein Client-Zertifikat verlangt: Das richtige Zertifikat kommt durch,
 * ohne oder mit fremdem Zertifikat gibt es keine Verbindung, und Konfigurationsfehler verhindern den Start mit
 * verständlicher Meldung ohne Passwort.
 */
class MutualTlsSourceTest {

    @TempDir
    static Path dir;

    private static TestCertificates certificates;
    private static FakeConfluence confluence;
    private static HttpsFakeConfluenceServer mtlsServer;
    private static HttpsFakeConfluenceServer tlsServer;
    private static TrustBundles bundles;

    @BeforeAll
    static void startServers() throws Exception {
        certificates = new TestCertificates(dir);
        confluence = new FakeConfluence();
        confluence.space("DEV", "Entwicklung");
        mtlsServer = new HttpsFakeConfluenceServer(confluence, certificates.serverContext(), true);
        tlsServer = new HttpsFakeConfluenceServer(confluence, certificates.serverContext(), false);
        X509TrustManager trust = certificates.harvesterTrustManager();
        bundles = name -> {
            if (!name.equals("confluence-ca")) {
                throw new IllegalArgumentException("SSL-Bundle '" + name + "' ist nicht konfiguriert");
            }
            return trust;
        };
    }

    @AfterAll
    static void stopServers() {
        mtlsServer.close();
        tlsServer.close();
    }

    private static ClientCertificate pkcs12(Path file, String password) {
        return new ClientCertificate(true, CertificateStoreType.PKCS12, file, CLIENT_ALIAS, password, null);
    }

    private static HarvestSource source(HttpsFakeConfluenceServer server, ClientCertificate certificate) {
        return catalog(new SourceSettingsConfig("wiki", "confluence", null, server.baseUrl(),
                new Auth(AuthType.PAT, null, null, "pat-token"), Map.of(), certificate, "confluence-ca"))
                .find("wiki").orElseThrow();
    }

    private static ConfiguredSourceCatalog catalog(SourceSettingsConfig config) {
        return ConfiguredSourceCatalog.of(List.of(config), "test", bundles);
    }

    @Test
    void rightCertificateTogetherWithPatReachesConfluence() {
        List<Partition> spaces = source(mtlsServer, pkcs12(certificates.client, CLIENT_PASSWORD)).partitions();

        assertThat(spaces).extracting(Partition::key).contains("DEV");
        // mTLS ersetzt die Anmeldung nicht: das PAT geht weiterhin als Bearer mit.
        assertThat(confluence.requestHeaders()).last().satisfies(headers -> assertThat(headers)
                .containsEntry("Authorization", "Bearer pat-token"));
    }

    @Test
    void withoutCertificateTheServerRefusesTheConnection() {
        HarvestSource source = source(mtlsServer, null);

        assertThatThrownBy(source::partitions).isInstanceOf(SourceAccessException.class)
                .satisfies(e -> assertThat(((SourceAccessException) e).reason())
                        .isEqualTo(SourceAccessException.Reason.UNAVAILABLE));
    }

    @Test
    void unknownCertificateIsRefusedByTheServer() {
        HarvestSource source = source(mtlsServer, pkcs12(certificates.stranger, CLIENT_PASSWORD));

        assertThatThrownBy(source::partitions).isInstanceOf(SourceAccessException.class);
    }

    @Test
    void sourceWithoutMtlsStillWorksAgainstPlainTls() {
        assertThat(source(tlsServer, null).partitions()).extracting(Partition::key).contains("DEV");
    }

    @Test
    void matchingThumbprintIsAccepted() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(certificates.client)) {
            store.load(in, CLIENT_PASSWORD.toCharArray());
        }
        byte[] encoded = store.getCertificate(CLIENT_ALIAS).getEncoded();
        String sha1 = HexFormat.ofDelimiter(":").withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-1").digest(encoded));

        HarvestSource source = source(mtlsServer, new ClientCertificate(true, CertificateStoreType.PKCS12,
                certificates.client, CLIENT_ALIAS, CLIENT_PASSWORD, sha1));

        assertThat(source.partitions()).extracting(Partition::key).contains("DEV");
    }

    @Test
    void certificateProblemsPreventStartWithoutRevealingThePassword() {
        assertProblem(pkcs12(certificates.client, "xyz-wrong"), "Passwort falsch");
        assertProblem(new ClientCertificate(true, CertificateStoreType.PKCS12, certificates.client, "unbekannt",
                CLIENT_PASSWORD, null), "Alias 'unbekannt' nicht in");
        assertProblem(pkcs12(certificates.certificateOnly, CLIENT_PASSWORD), "hat keinen privaten Schlüssel");
        assertProblem(pkcs12(certificates.expired, CLIENT_PASSWORD), "ist abgelaufen");
        assertProblem(pkcs12(dir.resolve("fehlt.p12"), CLIENT_PASSWORD), "fehlt oder ist nicht lesbar");
        assertProblem(new ClientCertificate(true, CertificateStoreType.PKCS12, certificates.client, CLIENT_ALIAS,
                CLIENT_PASSWORD, "00".repeat(20)), "nicht den konfigurierten Fingerabdruck");
    }

    @Test
    void windowsStoreOutsideWindowsIsReportedInsteadOfFallingBackToPlainTls() {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        assertProblem(new ClientCertificate(true, CertificateStoreType.WINDOWS_MY, null, CLIENT_ALIAS, null, null),
                "Windows-MY");
    }

    @Test
    void incompleteCertificateSettingsAreReportedTogether() {
        assertThatThrownBy(() -> ConfiguredSourceCatalog.of(List.of(
                config(new ClientCertificate(true, null, null, null, null, null)),
                config(new ClientCertificate(true, CertificateStoreType.PKCS12, null, CLIENT_ALIAS, null, "abc")),
                config(new ClientCertificate(true, CertificateStoreType.WINDOWS_MY, certificates.client, CLIENT_ALIAS,
                        "x", null))), "test", bundles))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .satisfies(e -> assertThat(((InvalidSourceConfigurationException) e).problems()).contains(
                        "sources[0] (id=wiki): client-certificate.type fehlt (pkcs12 oder windows-my)",
                        "sources[0] (id=wiki): client-certificate.alias fehlt",
                        "sources[1] (id=wiki): client-certificate.type pkcs12 braucht client-certificate.path",
                        "sources[1] (id=wiki): client-certificate.type pkcs12 braucht client-certificate.password",
                        "sources[1] (id=wiki): client-certificate.thumbprint ist kein SHA-1- (40) oder "
                                + "SHA-256-Fingerabdruck (64 Hex-Zeichen)",
                        "sources[2] (id=wiki): client-certificate.path/client-certificate.password passen nicht zu "
                                + "client-certificate.type windows-my"));
    }

    @Test
    void disabledCertificateKeepsItsSettingsAndUsesPlainTls() {
        HarvestSource source = source(tlsServer, new ClientCertificate(false, CertificateStoreType.PKCS12,
                dir.resolve("fehlt.p12"), CLIENT_ALIAS, "egal", null));

        assertThat(source.partitions()).extracting(Partition::key).contains("DEV");
    }

    @Test
    void unknownTrustBundleIsReported() {
        assertThatThrownBy(() -> catalog(new SourceSettingsConfig("wiki", "confluence", null, tlsServer.baseUrl(),
                null, Map.of(), null, "unbekannt")))
                .hasMessageContaining("sources[0] (id=wiki): trust-bundle: SSL-Bundle 'unbekannt' ist nicht "
                        + "konfiguriert");
    }

    @Test
    void certificateSettingsNeverShowThePassword() {
        assertThat(pkcs12(certificates.client, CLIENT_PASSWORD).toString()).contains(CLIENT_ALIAS)
                .doesNotContain(CLIENT_PASSWORD);
    }

    private static SourceSettingsConfig config(ClientCertificate certificate) {
        return new SourceSettingsConfig("wiki", "confluence", null, mtlsServer.baseUrl(), null, Map.of(),
                certificate, "confluence-ca");
    }

    private static void assertProblem(ClientCertificate certificate, String expected) {
        assertThatThrownBy(() -> catalog(config(certificate)))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("sources[0] (id=wiki): client-certificate: ")
                .hasMessageContaining(expected)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(CLIENT_PASSWORD));
    }
}
