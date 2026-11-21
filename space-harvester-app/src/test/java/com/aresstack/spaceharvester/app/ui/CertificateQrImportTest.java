package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.CertificateCode;
import com.aresstack.spaceharvester.source.network.CertificateQrCodes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JTextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Zertifikat aus QR-Bild ins Vertrauen der App und die Anzeige der Codes (Strang ZX), mit erfundenen Daten. */
class CertificateQrImportTest {

    @TempDir
    static Path dir;

    static X509Certificate ca;
    static X509Certificate other;
    static X509Certificate leaf;

    @BeforeAll
    static void certificates() throws Exception {
        ca = selfSigned("ca", "CN=Erfundene Wurzel,O=Beispiel", "bc:c");
        other = selfSigned("other", "CN=Zweite Wurzel,O=Beispiel", "bc:c");
        leaf = selfSigned("leaf", "CN=wiki.beispiel.test", null);
    }

    @Test
    void confirmedCertificatesJoinTheExistingCaFileInTheDataFolder() throws Exception {
        Path existing = dir.resolve("bisher.pem");
        Files.writeString(existing, CertificateQrCodes.text(other));
        JTextField field = new JTextField(existing.toString());
        Path data = dir.resolve("daten");
        List<String> asked = new ArrayList<>();

        String message = CertificateQrImport.apply(List.of(ca, other), field, data, (title, question) -> {
            asked.add(question);
            return true;
        });

        Path target = Path.of(field.getText());
        assertThat(target.getParent()).isEqualTo(data.resolve("zertifikate").toAbsolutePath());
        assertThat(target.getFileName().toString()).startsWith(CertificateQrImport.FILE_PREFIX).endsWith(".pem");
        assertThat(read(target)).containsExactly(other, ca);
        assertThat(Files.readString(existing)).isEqualTo(CertificateQrCodes.text(other));
        assertThat(asked).hasSize(2);
        assertThat(asked.get(0)).contains("wirklich als vertrauenswürdig eintragen?").contains("SHA-1: ")
                .contains("Aussteller: CN=Erfundene Wurzel").contains("nicht für Windows");
        assertThat(message).contains("2 Zertifikate eingetragen");

        // Ein zweiter Import schreibt eine neue Datei; die geltende bleibt bis „Speichern“ unverändert.
        String before = Files.readString(target);
        CertificateQrImport.apply(List.of(leaf), field, data, (title, question) -> true);
        assertThat(Path.of(field.getText())).isNotEqualTo(target);
        assertThat(Files.readString(target)).isEqualTo(before);
        assertThat(read(Path.of(field.getText()))).containsExactly(other, ca, leaf);
    }

    @Test
    void declinedCertificatesChangeNothing() throws Exception {
        JTextField field = new JTextField("");
        Path data = dir.resolve("abgelehnt");

        assertThat(CertificateQrImport.apply(List.of(ca), field, data, (title, question) -> false)).isNull();
        assertThat(field.getText()).isEmpty();
        assertThat(data).doesNotExist();
    }

    @Test
    void aServerCertificateIsMarkedAsNoCertificateAuthority() {
        assertThat(CertificateQrImport.question(leaf)).contains("kein Zertifikat einer Zertifizierungsstelle");
        assertThat(CertificateQrImport.question(ca)).doesNotContain("kein Zertifikat einer Zertifizierungsstelle");
    }

    @Test
    void aPictureWithoutCertificateIsReported() {
        assertThatThrownBy(() -> CertificateQrImport.apply(List.of(), new JTextField(), dir, (t, q) -> true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kein QR-Code");
    }

    @Test
    void theCodesViewPagesThroughAllCodes() {
        List<CertificateCode> codes = List.of(
                new CertificateCode("Erfundene Wurzel", "Stammzertifizierungsstelle", "AA:BB",
                        CertificateQrCodes.image(ca, 1).orElseThrow()),
                new CertificateCode("Zweite Wurzel", "Stammzertifizierungsstelle", "CC:DD",
                        CertificateQrCodes.image(other, 1).orElseThrow()));
        CertificateCodesView view = new CertificateCodesView(codes);

        assertThat(view.positionText()).isEqualTo("1 von 2");
        view.step(1);
        assertThat(view.positionText()).isEqualTo("2 von 2");
        view.step(1);
        assertThat(view.index()).isZero();
        view.step(-1);
        assertThat(view.index()).isEqualTo(1);
    }

    @Test
    void theCodeIsDrawnWithWholePixelsPerModuleAndWithoutSmoothing() {
        java.awt.image.BufferedImage code = CertificateQrCodes.image(ca, 1).orElseThrow();
        assertThat(CertificateCodesView.scale(420, 420, code.getWidth(), code.getHeight()))
                .isEqualTo(420 / code.getWidth());
        assertThat(CertificateCodesView.scale(10, 10, code.getWidth(), code.getHeight())).isEqualTo(1);

        java.awt.image.BufferedImage drawn = CertificateCodesView.paint(code, 420);
        // Nur reines Schwarz und Weiß: keine Zwischentöne einer geglätteten Skalierung.
        for (int y = 0; y < drawn.getHeight(); y++) {
            for (int x = 0; x < drawn.getWidth(); x++) {
                assertThat(drawn.getRGB(x, y) & 0xFFFFFF).isIn(0x000000, 0xFFFFFF);
            }
        }
        // Und das Gezeichnete liest sich wieder als dasselbe Zertifikat.
        assertThat(CertificateQrCodes.read(drawn)).containsExactly(ca);
    }

    @Test
    void aCertificateWithoutCodeIsNamedInTheView() {
        CertificateCodesView view = new CertificateCodesView(List.of(
                new CertificateCode("Riesige Wurzel", "Stammzertifizierungsstelle", "EE:FF", null)));

        assertThat(view.tooLargeShown()).isTrue();
    }

    private static List<X509Certificate> read(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            Collection<? extends Certificate> all = CertificateFactory.getInstance("X.509").generateCertificates(in);
            return all.stream().map(X509Certificate.class::cast).toList();
        }
    }

    private static X509Certificate selfSigned(String name, String dname, String ext) throws Exception {
        Path store = dir.resolve(name + ".p12");
        List<String> args = new ArrayList<>(List.of("-genkeypair", "-alias", name, "-dname", dname, "-validity",
                "30", "-keyalg", "EC", "-groupname", "secp256r1", "-storetype", "PKCS12", "-keystore",
                store.toString(), "-storepass", "changeit"));
        if (ext != null) {
            args.addAll(List.of("-ext", ext));
        }
        keytool(args);
        Path pem = dir.resolve(name + ".pem");
        keytool(List.of("-exportcert", "-alias", name, "-keystore", store.toString(), "-storepass", "changeit",
                "-rfc", "-file", pem.toString()));
        try (InputStream in = Files.newInputStream(pem)) {
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
