package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.source.network.CertificatePackage;
import com.aresstack.spaceharvester.source.network.CertificateQrCodes;
import java.awt.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JFileChooser;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * „Zertifikat aus QR-Bild lesen …“ im Dialog der Serverzertifikate (Strang ZX): Ein Bild mit QR-Codes aus dem
 * Zertifikatsexport wählen, jedes Zertifikat einzeln mit Betreff, Aussteller, SHA-1 und Gültigkeit bestätigen
 * („… wirklich als vertrauenswürdig eintragen?“), dann zusammen mit den Zertifikaten der bisherigen CA-Datei in eine
 * PEM-Datei im Datenordner schreiben und diese ins Feld eintragen. Gilt erst mit „Speichern“ und nur für die App,
 * nie für Windows; entfernbar durch Leeren des Felds. Der Inhalt der Codes kommt nie ins Protokoll.
 */
final class CertificateQrImport {

    static final String TOOLTIP = "Zertifikat aus QR-Bild lesen …";
    static final String FILE_NAME = "vertraut-aus-qr.pem";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

    /** Fragt je Zertifikat; {@code true} = eintragen. */
    interface Confirm {
        boolean ask(String title, String question);
    }

    private CertificateQrImport() {
    }

    /** Öffnet die Bildauswahl und führt den Import im Hintergrund aus; Fehler gehen an {@code problem}. */
    static void choose(Component parent, JTextField field, Path dataDirectory, Consumer<Throwable> problem,
                       Consumer<String> done) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("QR-Bild mit Zertifikat wählen");
        chooser.setFileFilter(new FileNameExtensionFilter("Bilder (PNG, JPEG, GIF, BMP)", "png", "jpg", "jpeg",
                "gif", "bmp"));
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path image = chooser.getSelectedFile().toPath();
        Background.run(() -> CertificateQrCodes.read(image), certificates -> {
            try {
                String result = apply(certificates, field, dataDirectory,
                        (title, question) -> Dialogs.confirm(parent, title, question));
                if (result != null) {
                    done.accept(result);
                }
            } catch (IOException | RuntimeException e) {
                problem.accept(e);
            }
        }, problem);
    }

    /**
     * Fragt je Zertifikat nach und schreibt die bestätigten samt der bisherigen CA-Datei nach
     * {@code <Datenordner>/zertifikate/vertraut-aus-qr.pem}; trägt die Datei ins Feld ein.
     *
     * @return kurze Meldung für den Benutzer, {@code null}, wenn nichts eingetragen wurde
     * @throws IllegalArgumentException wenn das Bild kein Zertifikat enthält
     */
    static String apply(List<X509Certificate> certificates, JTextField field, Path dataDirectory, Confirm confirm)
            throws IOException {
        if (certificates.isEmpty()) {
            throw new IllegalArgumentException("Im Bild ist kein QR-Code mit einem Zertifikat zu erkennen.");
        }
        List<X509Certificate> accepted = new ArrayList<>();
        for (X509Certificate certificate : certificates) {
            if (confirm.ask("Zertifikat eintragen", question(certificate))) {
                accepted.add(certificate);
            }
        }
        if (accepted.isEmpty()) {
            return null;
        }
        Path existing = field.getText().isBlank() ? null : Path.of(field.getText().strip());
        Path target = dataDirectory.resolve("zertifikate").resolve(FILE_NAME);
        merge(existing, accepted, target);
        field.setText(target.toAbsolutePath().toString());
        return accepted.size() == 1 ? "1 Zertifikat eingetragen; gilt nach „Speichern“"
                : accepted.size() + " Zertifikate eingetragen; gilt nach „Speichern“";
    }

    /** Die Rückfrage mit Betreff, Aussteller, SHA-1, Gültigkeit und Hinweisen (abgelaufen, keine CA). */
    static String question(X509Certificate certificate) {
        StringBuilder text = new StringBuilder();
        text.append("„").append(CertificatePackage.fileName(certificate))
                .append("“ wirklich als vertrauenswürdig eintragen?\n\n")
                .append("Betreff: ").append(certificate.getSubjectX500Principal().getName()).append('\n')
                .append("Aussteller: ").append(certificate.getIssuerX500Principal().getName()).append('\n')
                .append("SHA-1: ").append(CertificatePackage.colons(CertificatePackage.sha1(certificate)))
                .append('\n')
                .append("Gültig: ").append(day(certificate.getNotBefore().toInstant())).append(" bis ")
                .append(day(certificate.getNotAfter().toInstant()));
        if (certificate.getNotAfter().toInstant().isBefore(Instant.now())) {
            text.append("\n\nHinweis: Das Zertifikat ist abgelaufen.");
        }
        if (certificate.getBasicConstraints() < 0) {
            text.append("\n\nHinweis: Das ist kein Zertifikat einer Zertifizierungsstelle; es bestätigt nur sich "
                    + "selbst bzw. einen Server.");
        }
        text.append("\n\nDie Fingerabdrücke mit dem Export auf dem anderen Rechner vergleichen. Das Zertifikat gilt "
                + "nur für den Space Harvester, nicht für Windows.");
        return text.toString();
    }

    /** Schreibt die Zertifikate der bisherigen Datei und die neuen ohne Dubletten als PEM nach {@code target}. */
    static void merge(Path existing, List<X509Certificate> added, Path target) throws IOException {
        Map<String, X509Certificate> all = new LinkedHashMap<>();
        if (existing != null && Files.isRegularFile(existing)) {
            try (InputStream in = Files.newInputStream(existing)) {
                for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    if (certificate instanceof X509Certificate x509) {
                        all.putIfAbsent(CertificatePackage.sha1(x509), x509);
                    }
                }
            } catch (CertificateException e) {
                throw new IOException("Die bisherige CA-Datei ist nicht lesbar; sie bleibt unverändert", e);
            }
        }
        for (X509Certificate certificate : added) {
            all.putIfAbsent(CertificatePackage.sha1(certificate), certificate);
        }
        StringBuilder pem = new StringBuilder();
        for (X509Certificate certificate : all.values()) {
            pem.append("# ").append(certificate.getSubjectX500Principal().getName()
                    .replaceAll("[^\\x20-\\x7e]", "_")).append('\n');
            pem.append(CertificateQrCodes.text(certificate));
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".neu");
        Files.writeString(temp, pem.toString(), StandardCharsets.US_ASCII);
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String day(Instant instant) {
        return DAY.format(instant.atZone(ZoneId.systemDefault()));
    }
}
