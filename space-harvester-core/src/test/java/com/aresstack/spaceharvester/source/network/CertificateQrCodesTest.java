package com.aresstack.spaceharvester.source.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** QR-Codes je Zertifikat (Strang ZX) mit erfundenen Zertifikaten aus dem keytool des Test-JDK. */
class CertificateQrCodesTest {

    @TempDir
    static Path dir;

    static X509Certificate ec;
    static X509Certificate rsa;

    @BeforeAll
    static void certificates() throws Exception {
        ec = selfSigned("ec", "CN=Erfundene Wurzel,O=Beispiel", "-keyalg", "EC", "-groupname", "secp256r1");
        rsa = selfSigned("rsa", "CN=Erfundene RSA-Wurzel,O=Beispiel", "-keyalg", "RSA", "-keysize", "2048");
    }

    @Test
    void theCodeHoldsThePemTextAndReadsBack() {
        BufferedImage image = CertificateQrCodes.image(rsa, 4).orElseThrow();

        assertThat(CertificateQrCodes.text(rsa)).startsWith("-----BEGIN CERTIFICATE-----\n")
                .endsWith("-----END CERTIFICATE-----\n").doesNotContain("\r");
        assertThat(CertificateQrCodes.read(image)).containsExactly(rsa);
    }

    @Test
    void severalCodesInOnePictureAreAllFound() throws IOException {
        BufferedImage left = CertificateQrCodes.image(ec, 4).orElseThrow();
        BufferedImage right = CertificateQrCodes.image(rsa, 4).orElseThrow();
        BufferedImage both = new BufferedImage(left.getWidth() + right.getWidth() + 40,
                Math.max(left.getHeight(), right.getHeight()) + 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = both.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, both.getWidth(), both.getHeight());
        g.drawImage(left, 20, 20, null);
        g.drawImage(right, left.getWidth() + 20, 20, null);
        g.dispose();
        Path file = dir.resolve("beide.png");
        ImageIO.write(both, "png", file.toFile());

        assertThat(CertificateQrCodes.read(file)).containsExactlyInAnyOrder(ec, rsa);
    }

    @Test
    void pngBytesAreAReadableImage() throws IOException {
        Path file = dir.resolve("ec.png");
        Files.write(file, CertificateQrCodes.png(ec).orElseThrow());

        assertThat(CertificateQrCodes.read(file)).containsExactly(ec);
    }

    @Test
    void onlyASingleCertificateInPemIsAccepted() {
        assertThat(CertificateQrCodes.parse(CertificateQrCodes.text(ec))).contains(ec);
        assertThat(CertificateQrCodes.parse("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----")).isEmpty();
        assertThat(CertificateQrCodes.parse(CertificateQrCodes.text(ec) + CertificateQrCodes.text(rsa))).isEmpty();
        assertThat(CertificateQrCodes.parse("https://beispiel.test")).isEmpty();
        assertThat(CertificateQrCodes.parse(null)).isEmpty();
    }

    @Test
    void aPictureWithoutCodeYieldsNothingAndNonImagesAreRejected() throws IOException {
        BufferedImage blank = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        assertThat(CertificateQrCodes.read(blank)).isEmpty();
        Path text = dir.resolve("kein-bild.png");
        Files.writeString(text, "kein Bild");
        assertThatThrownBy(() -> CertificateQrCodes.read(text)).isInstanceOf(IOException.class)
                .hasMessageContaining("Kein lesbares Bild");
    }

    @Test
    void oversizedPicturesAreRejectedBeforeDecoding() throws IOException {
        // 7000 × 7000 Pixel = 49 Megapixel, als PNG aber klein: geprüft wird die Pixelzahl aus dem Kopf.
        BufferedImage huge = new BufferedImage(7000, 7000, BufferedImage.TYPE_BYTE_BINARY);
        Path file = dir.resolve("riesig.png");
        ImageIO.write(huge, "png", file.toFile());

        assertThatThrownBy(() -> CertificateQrCodes.read(file)).isInstanceOf(IOException.class)
                .hasMessageContaining("Megapixel");
    }

    @Test
    void certificatesInTheQrArchiveAreOnlyTheTrustedOnes() throws IOException {
        CertificatePackage bundle = CertificatePackage.of("Wiki", "wiki.beispiel.test", List.of(), false,
                List.of(ec), List.of(), List.of(), List.of(), java.time.ZonedDateTime.now());
        Path zip = dir.resolve("qr.zip");

        // Ohne Kette vom Server und ohne Client-Zertifikat gibt es nichts Importierbares.
        assertThat(bundle.writeQrZip(zip)).isZero();
    }

    @Test
    void aCertificateTooLargeForOneCodeGetsNoCodeAndIsNamedInTheReadme() throws Exception {
        StringBuilder names = new StringBuilder("SAN=");
        for (int i = 0; i < 90; i++) {
            names.append(i == 0 ? "" : ",").append("dns:dienst-").append(i)
                    .append(".sehr-lange-erfundene-domain.beispiel.test");
        }
        X509Certificate large = selfSigned("gross", "CN=Riesige Wurzel,O=Beispiel", "-keyalg", "EC", "-groupname",
                "secp256r1", "-ext", names.toString());
        assertThat(CertificateQrCodes.image(large, 1)).isEmpty();

        CertificatePackage bundle = CertificatePackage.of("Wiki", "wiki.beispiel.test", List.of(large), true,
                List.of(large), List.of(), List.of(), List.of(), java.time.ZonedDateTime.now());
        Path zip = dir.resolve("gross.zip");
        assertThat(bundle.writeQrZip(zip)).isZero();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry entry = in.getNextEntry();
            assertThat(entry.getName()).isEqualTo("LIESMICH.txt");
            assertThat(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("Passt nicht in einen Code").contains("Riesige Wurzel");
        }
    }

    private static X509Certificate selfSigned(String name, String dname, String... key) throws Exception {
        Path store = dir.resolve(name + ".p12");
        List<String> args = new ArrayList<>(List.of("-genkeypair", "-alias", name, "-dname", dname, "-validity",
                "30", "-ext", "bc:c", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass",
                "changeit"));
        args.addAll(List.of(key));
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
