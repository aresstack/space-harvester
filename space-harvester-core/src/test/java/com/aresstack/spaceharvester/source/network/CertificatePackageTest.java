package com.aresstack.spaceharvester.source.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Zertifikatspaket einer Quelle (Strang ZX) mit erfundenen Zertifikaten aus dem keytool des Test-JDK. */
class CertificatePackageTest {

    private static final String PASSWORD = "changeit";
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 21, 18, 30, 0, 0,
            ZoneId.of("Europe/Berlin"));

    @TempDir
    static Path dir;

    static X509Certificate root;
    static X509Certificate intermediate;
    static X509Certificate server;
    static X509Certificate foreignRoot;
    static X509Certificate foreignServer;
    static X509Certificate selfSignedServer;

    @BeforeAll
    static void certificates() throws Exception {
        Path ca = dir.resolve("ca.p12");
        genkey(ca, "root", "CN=Erfundene Wurzel,O=Beispiel", "bc:c");
        genkey(ca, "inter", "CN=Erfundene Zwischenstelle,O=Beispiel", null);
        intermediate = sign(ca, "inter", "root", "bc:c");
        genkey(ca, "server", "CN=wiki.beispiel.test", null);
        server = sign(ca, "server", "inter", "SAN=dns:wiki.beispiel.test");
        root = export(ca, "root");

        Path foreign = dir.resolve("foreign.p12");
        genkey(foreign, "root", "CN=Fremde Wurzel", "bc:c");
        genkey(foreign, "server", "CN=wiki.beispiel.test", null);
        foreignServer = sign(foreign, "server", "root", null);
        foreignRoot = export(foreign, "root");
        genkey(foreign, "selbst", "CN=selbst.beispiel.test", null);
        selfSignedServer = export(foreign, "selbst");
    }

    @Test
    void serverSendsOnlyLeafAndThePackageFillsTheChainFromTheTrustStore() throws IOException {
        CertificatePackage bundle = CertificatePackage.of("Wiki", "wiki.beispiel.test", List.of(server), true,
                List.of(root, intermediate), List.of(), List.of(), List.of(), NOW);

        assertThat(bundle.serverTrusted()).isTrue();
        assertThat(bundle.count(CertificatePackage.Role.ROOT)).isEqualTo(1);
        assertThat(bundle.count(CertificatePackage.Role.INTERMEDIATE)).isEqualTo(1);
        assertThat(bundle.entries()).filteredOn(e -> e.role() == CertificatePackage.Role.INTERMEDIATE)
                .allMatch(e -> !e.sentByServer() && e.inTrustStore());

        Map<String, String> zip = unzip(bundle);
        assertThat(zip).containsKeys("1-Stammzertifizierungsstellen/Erfundene Wurzel.cer",
                "2-Zwischenzertifizierungsstellen/Erfundene Zwischenstelle.cer", "3-Server/wiki.beispiel.test.cer",
                "kette.pem", "importieren.cmd", "LIESMICH.txt");
        assertThat(zip.get("importieren.cmd"))
                .contains("certutil -user -addstore Root \"1-Stammzertifizierungsstellen\\Erfundene Wurzel.cer\"")
                .contains("certutil -user -addstore CA \"2-Zwischenzertifizierungsstellen\\Erfundene "
                        + "Zwischenstelle.cer\"")
                .doesNotContain("3-Server");
        assertThat(zip.get("LIESMICH.txt")).contains("fehlt beim Server").contains("SHA-256")
                .contains("Vertrauenswürdige Stammzertifizierungsstellen");
        assertThat(count(zip.get("kette.pem"), "BEGIN CERTIFICATE")).isEqualTo(2);
    }

    @Test
    void certificatesSentByTheServerAndVerifiedUpToAnAnchorAreImportable() {
        CertificatePackage bundle = CertificatePackage.of("Wiki", "wiki.beispiel.test",
                List.of(server, intermediate), true, List.of(root), List.of(), List.of(), List.of(), NOW);

        assertThat(bundle.serverTrusted()).isTrue();
        assertThat(bundle.entries()).filteredOn(e -> e.role() == CertificatePackage.Role.INTERMEDIATE)
                .singleElement().matches(CertificatePackage.Entry::sentByServer);
    }

    @Test
    void aChainEndingAtNoAnchorIsNeverImportable() throws IOException {
        CertificatePackage bundle = CertificatePackage.of("Wiki", "wiki.beispiel.test",
                List.of(foreignServer, foreignRoot), false, List.of(root, intermediate), List.of(), List.of(), List.of(),
                NOW);

        assertThat(bundle.serverTrusted()).isFalse();
        assertThat(bundle.count(CertificatePackage.Role.ROOT)).isZero();
        assertThat(bundle.count(CertificatePackage.Role.INTERMEDIATE)).isZero();
        Map<String, String> zip = unzip(bundle);
        assertThat(zip).doesNotContainKey("kette.pem").containsKey("3-Server/Fremde Wurzel.cer");
        assertThat(zip.keySet()).noneMatch(name -> name.startsWith("1-") || name.startsWith("2-"));
        assertThat(zip.get("importieren.cmd")).doesNotContain("certutil").contains("Keine vertrauten Zertifikate");
        assertThat(zip.get("LIESMICH.txt")).contains("ACHTUNG").contains("NICHT an");
    }

    @Test
    void aSelfSignedCertificateAboveAnIntermediateFromTheTrustStoreIsNeverImportedAsRoot() throws IOException {
        // Windows nennt auch Zwischenzertifikate (Speicher „Zwischenzertifizierungsstellen“) als akzeptierte
        // Aussteller; das ist keine Vertrauensaussage für ein selbstsigniertes Zertifikat, das nur der Server schickt.
        CertificatePackage bundle = CertificatePackage.of("Wiki", "wiki.beispiel.test",
                List.of(server, intermediate, root), true, List.of(intermediate), List.of(), List.of(), List.of(),
                NOW);

        assertThat(bundle.count(CertificatePackage.Role.ROOT)).isZero();
        assertThat(bundle.count(CertificatePackage.Role.INTERMEDIATE)).isEqualTo(1);
        Map<String, String> zip = unzip(bundle);
        assertThat(zip.get("importieren.cmd")).doesNotContain("addstore Root").contains("addstore CA");
        assertThat(zip).containsKey("3-Server/Erfundene Wurzel.cer");
        assertThat(count(zip.get("kette.pem"), "BEGIN CERTIFICATE")).isEqualTo(1);
    }

    @Test
    void theServerVerdictComesFromTheSourceTrustNotFromThePath() {
        CertificatePackage bundle = CertificatePackage.of("Wiki", "wiki.beispiel.test", List.of(server), false,
                List.of(root, intermediate), List.of(), List.of(), List.of(), NOW);

        assertThat(bundle.serverTrusted()).isFalse();
    }

    @Test
    void theQrArchiveHasOneReadablePngPerTrustedCertificate() throws IOException {
        CertificatePackage bundle = CertificatePackage.of("Wiki", "wiki.beispiel.test", List.of(server), true,
                List.of(root, intermediate), List.of(), List.of(), List.of(), NOW);
        Path file = Files.createTempFile(dir, "qr", ".zip");

        assertThat(bundle.writeQrZip(file)).isEqualTo(2);
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
                if (entry.getName().endsWith(".png")) {
                    java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(
                            new java.io.ByteArrayInputStream(zip.readAllBytes()));
                    assertThat(CertificateQrCodes.read(image)).hasSize(1).first()
                            .isIn(root, intermediate);
                }
            }
        }
        assertThat(names).containsExactly("01 Stamm Erfundene Wurzel.png",
                "02 Zwischen Erfundene Zwischenstelle.png", "LIESMICH.txt");
    }

    @Test
    void aDirectlyTrustedSelfSignedServerCertificateIsImportedAsRoot() throws IOException {
        CertificatePackage bundle = CertificatePackage.of("Wiki & calc", "wiki.beispiel.test",
                List.of(selfSignedServer), true, List.of(selfSignedServer), List.of(), List.of(), List.of(), NOW);

        assertThat(bundle.count(CertificatePackage.Role.ROOT)).isEqualTo(1);
        String script = unzip(bundle).get("importieren.cmd");
        assertThat(script).contains("addstore Root").doesNotContain("calc").doesNotContain("&");
        assertThat(unzip(bundle).get("LIESMICH.txt")).contains("KEINE Zertifizierungsstelle");
    }

    @Test
    void withoutHandshakeThePackageStillHasTheClientChainAndTheReason() throws IOException {
        CertificatePackage bundle = CertificatePackage.of("Wiki", "wiki.beispiel.test", List.of(), false,
                List.of(root, intermediate), List.of(server, intermediate), List.of("Keine Kette vom Server: TCP"),
                List.of(), NOW);

        Map<String, String> zip = unzip(bundle);
        assertThat(zip).containsKeys("4-Client/wiki.beispiel.test.cer",
                "1-Stammzertifizierungsstellen/Erfundene Wurzel.cer");
        assertThat(zip.get("LIESMICH.txt")).contains("Keine Kette vom Server: TCP")
                .contains("nicht bis zum TLS-Handshake");
    }

    @Test
    void fileNamesAreSafeForWindows() {
        assertThat(CertificatePackage.safe("a/b\\c:d*e?f\"g<h>i|j")).isEqualTo("a_b_c_d_e_f_g_h_i_j");
        assertThat(CertificatePackage.safe("CON")).isEqualTo("_CON");
        assertThat(CertificatePackage.safe("lpt1.txt")).isEqualTo("_lpt1.txt");
        assertThat(CertificatePackage.safe("Name. ")).isEqualTo("Name");
        assertThat(CertificatePackage.safe("..")).isEqualTo("Zertifikat");
        assertThat(CertificatePackage.safe("x".repeat(200))).hasSize(60);
    }

    @Test
    void equalNamesAreSeparatedByTheirFingerprint() {
        CertificatePackage bundle = CertificatePackage.of("Wiki", "wiki.beispiel.test", List.of(server), true,
                List.of(root, intermediate), List.of(foreignServer), List.of(), List.of(), NOW);

        assertThat(bundle.entries()).extracting(CertificatePackage.Entry::path)
                .contains("3-Server/wiki.beispiel.test.cer").doesNotHaveDuplicates()
                .anyMatch(path -> path.matches("4-Client/wiki\\.beispiel\\.test\\.cer"));
        CertificatePackage twice = CertificatePackage.of("Wiki", "wiki.beispiel.test",
                List.of(server, foreignServer), true, List.of(root, intermediate), List.of(), List.of(), List.of(), NOW);
        assertThat(twice.entries()).extracting(CertificatePackage.Entry::path)
                .anyMatch(path -> path.matches("3-Server/wiki\\.beispiel\\.test \\([0-9a-f]{8}\\)\\.cer"));
    }

    @Test
    void recorderKeepsTheChainAndTheVerdictWithoutChangingIt() {
        X509TrustManager rejecting = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("nicht vertraut");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[] {root};
            }
        };
        ChainRecordingTrustManager recorder = new ChainRecordingTrustManager(rejecting);

        assertThatThrownBy(() -> recorder.checkServerTrusted(new X509Certificate[] {server}, "EC"))
                .isInstanceOf(CertificateException.class);
        assertThat(recorder.checked()).isTrue();
        assertThat(recorder.chain()).containsExactly(server);
        assertThat(recorder.rejection()).hasMessage("nicht vertraut");
        assertThat(recorder.anchors()).containsExactly(root);
    }

    private static Map<String, String> unzip(CertificatePackage bundle) throws IOException {
        Path file = Files.createTempFile(dir, "paket", ".zip");
        bundle.writeZip(file);
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static int count(String text, String part) {
        return text.split(part, -1).length - 1;
    }

    private static void genkey(Path store, String alias, String dname, String ext) throws Exception {
        List<String> args = new ArrayList<>(List.of("-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname",
                "secp256r1", "-dname", dname, "-validity", "30", "-storetype", "PKCS12", "-keystore",
                store.toString(), "-storepass", PASSWORD));
        if (ext != null) {
            args.addAll(List.of("-ext", ext));
        }
        keytool(args);
    }

    private static X509Certificate sign(Path store, String alias, String issuer, String ext) throws Exception {
        Path request = dir.resolve(store.getFileName() + "-" + alias + ".csr");
        Path signed = dir.resolve(store.getFileName() + "-" + alias + ".pem");
        keytool(List.of("-certreq", "-alias", alias, "-keystore", store.toString(), "-storepass", PASSWORD,
                "-file", request.toString()));
        List<String> args = new ArrayList<>(List.of("-gencert", "-alias", issuer, "-keystore", store.toString(),
                "-storepass", PASSWORD, "-infile", request.toString(), "-outfile", signed.toString(), "-rfc",
                "-validity", "30"));
        if (ext != null) {
            args.addAll(List.of("-ext", ext));
        }
        keytool(args);
        return read(signed);
    }

    private static X509Certificate export(Path store, String alias) throws Exception {
        Path file = dir.resolve(store.getFileName() + "-" + alias + "-export.pem");
        keytool(List.of("-exportcert", "-alias", alias, "-keystore", store.toString(), "-storepass", PASSWORD,
                "-rfc", "-file", file.toString()));
        return read(file);
    }

    private static X509Certificate read(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static void keytool(List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("keytool " + args.get(0) + " fehlgeschlagen: " + output);
        }
    }
}
