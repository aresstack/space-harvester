package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.sources.CertificateCode;
import com.aresstack.spaceharvester.application.sources.CertificateExport;
import com.aresstack.spaceharvester.application.sources.ConnectionStep;
import com.aresstack.spaceharvester.source.network.CertificatePackage;
import com.aresstack.spaceharvester.source.network.CertificateQrCodes;
import com.aresstack.spaceharvester.source.network.ChainRecordingTrustManager;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zertifikatsexport einer Quelle (Strang ZX): Geht den Netzweg der Quelle wie die Verbindungsdiagnose (Route, Proxy,
 * TLS mit dem Vertrauen der Quelle), zeichnet die Kette des Servers auf, ergänzt sie aus den Vertrauensankern, nimmt
 * den öffentlichen Teil des Client-Zertifikats dazu und schreibt ein {@link CertificatePackage} als ZIP. Scheitert
 * der Handshake, entsteht das ZIP trotzdem mit dem Bekannten und dem Grund. Das Protokoll nennt nur Quelle und
 * Ausnahmeklasse, nie Betreffs oder Fingerabdrücke.
 */
public final class SourceCertificates {

    private static final Logger LOG = LoggerFactory.getLogger(SourceCertificates.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ConfiguredSourceCatalog catalog;
    private final Duration timeout;

    public SourceCertificates(ConfiguredSourceCatalog catalog) {
        this(catalog, catalog.timeouts().probe());
    }

    SourceCertificates(ConfiguredSourceCatalog catalog, Duration timeout) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Exportiert die Zertifikate einer Quelle, auch einer abgeschalteten. Blockiert (Netz), also nur im Hintergrund.
     *
     * @param directory Zielordner (der Ausgabeordner der App)
     * @param cancelled wird vor dem Schreiben gefragt; {@code true} bricht ohne Datei ab
     * @return leer ohne Quelle dieser ID
     * @throws IllegalArgumentException wenn die Quelle keine https-Adresse hat
     * @throws CancellationException    wenn abgebrochen wurde
     * @throws IOException              wenn das ZIP nicht geschrieben werden kann
     */
    public Optional<CertificateExport> export(String sourceId, Path directory, BooleanSupplier cancelled)
            throws IOException {
        Optional<SourceSettingsConfig> found = catalog.config(sourceId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(export(found.get(), directory, cancelled, ZonedDateTime.now()));
    }

    /**
     * Wie {@link #export(String, Path, BooleanSupplier)}, aber nur die QR-Codes der vertrauten Zertifikate als
     * Bilder in einem ZIP (Strang ZX, nicht der Standard).
     */
    public Optional<CertificateExport> exportQrCodes(String sourceId, Path directory, BooleanSupplier cancelled)
            throws IOException {
        Optional<SourceSettingsConfig> found = catalog.config(sourceId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(write(found.get(), directory, cancelled, ZonedDateTime.now(), true));
    }

    /**
     * Die vertrauten Zertifikate der Quelle als QR-Codes zur Anzeige; schreibt nichts. Blockiert (Netz).
     *
     * @return leer ohne Quelle dieser ID
     */
    public Optional<List<CertificateCode>> codes(String sourceId, BooleanSupplier cancelled) {
        Optional<SourceSettingsConfig> found = catalog.config(sourceId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CertificatePackage bundle = collect(found.get(), ZonedDateTime.now(), new ArrayList<>());
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("abgebrochen");
        }
        List<CertificateCode> codes = new ArrayList<>();
        for (CertificatePackage.Entry entry : bundle.importable()) {
            codes.add(new CertificateCode(CertificatePackage.name(entry), CertificatePackage.roleLabel(entry.role()),
                    CertificatePackage.colons(CertificatePackage.sha1(entry.certificate())),
                    CertificateQrCodes.image(entry.certificate(), 1).orElse(null)));
        }
        return Optional.of(codes);
    }

    CertificateExport export(SourceSettingsConfig config, Path directory, BooleanSupplier cancelled,
                             ZonedDateTime now) throws IOException {
        return write(config, directory, cancelled, now, false);
    }

    private CertificateExport write(SourceSettingsConfig config, Path directory, BooleanSupplier cancelled,
                                    ZonedDateTime now, boolean qr) throws IOException {
        List<String> problems = new ArrayList<>();
        CertificatePackage bundle = collect(config, now, problems);
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("abgebrochen");
        }
        Files.createDirectories(directory);
        Path file = unique(directory, "Zertifikate " + CertificatePackage.safe(title(config)) + " "
                + DAY.format(now) + (qr ? " QR-Codes" : ""));
        if (qr) {
            bundle.writeQrZip(file);
        } else {
            bundle.writeZip(file);
        }
        long missing = bundle.entries().stream()
                .filter(e -> (e.role() == CertificatePackage.Role.ROOT
                        || e.role() == CertificatePackage.Role.INTERMEDIATE) && !e.sentByServer())
                .count();
        return new CertificateExport(file, bundle.count(CertificatePackage.Role.ROOT),
                bundle.count(CertificatePackage.Role.INTERMEDIATE), missing, bundle.serverTrusted(), problems);
    }

    /** Geht den Netzweg der Quelle und ordnet ein; {@code problems} bekommt die Gründe für Fehlendes. */
    private CertificatePackage collect(SourceSettingsConfig config, ZonedDateTime now, List<String> problems) {
        URI base = catalog.connectionTarget(config);
        if (base == null || base.getHost() == null || !"https".equalsIgnoreCase(base.getScheme())) {
            throw new IllegalArgumentException("Nur für Quellen mit https");
        }
        List<String> notes = new ArrayList<>();
        X509TrustManager trust = catalog.trustFor(config);
        ChainRecordingTrustManager recorder = new ChainRecordingTrustManager(trust);
        List<ConnectionStep> steps = new ArrayList<>();
        catalog.connectionProbe(timeout).run(base, recorder, config.clientCertificate().enabled(), steps::add);
        if (!recorder.checked()) {
            steps.stream().filter(ConnectionStep::failed).findFirst().ifPresent(step -> problems.add(
                    "Keine Kette vom Server: " + step.stage().label() + " gescheitert (" + step.message() + ")"));
        } else if (recorder.rejection() != null) {
            problems.add("Das Vertrauen dieses Rechners lehnt die Kette des Servers ab ("
                    + recorder.rejection().getClass().getSimpleName() + ")");
        }
        List<X509Certificate> anchors = anchors(recorder, config.id(), problems);
        List<X509Certificate> client = client(config, problems, notes);
        return CertificatePackage.of(title(config), base.getHost(), recorder.chain(),
                recorder.checked() && recorder.rejection() == null, anchors, client, problems, notes, now);
    }

    private static List<X509Certificate> anchors(ChainRecordingTrustManager recorder, String sourceId,
                                                 List<String> problems) {
        try {
            return recorder.anchors();
        } catch (RuntimeException e) {
            LOG.warn("Zertifikatsexport {}: Vertrauensanker nicht lesbar ({})", sourceId, e.getClass().getSimpleName());
            problems.add("Vertrauensanker dieses Rechners nicht lesbar (" + e.getClass().getSimpleName() + ")");
            return List.of();
        }
    }

    /** Die Kette des Client-Zertifikats aus Windows-MY, nur öffentliche Teile; PKCS12 bleibt eine Notiz. */
    private static List<X509Certificate> client(SourceSettingsConfig config, List<String> problems,
                                                List<String> notes) {
        SourceSettingsConfig.ClientCertificate certificate = config.clientCertificate();
        if (!certificate.enabled()) {
            notes.add("Die Quelle nutzt kein Client-Zertifikat.");
            return List.of();
        }
        if (certificate.type() == SourceSettingsConfig.CertificateStoreType.PKCS12) {
            notes.add("Das Client-Zertifikat liegt als PKCS12-Datei vor (" + fileName(certificate.path())
                    + "); diese Datei samt Passwort auf den anderen Rechner mitnehmen.");
            return List.of();
        }
        try {
            KeyStore store = KeyStore.getInstance("Windows-MY");
            store.load(null, null);
            Certificate[] chain = store.getCertificateChain(certificate.alias());
            if (chain == null || chain.length == 0) {
                Certificate single = store.getCertificate(certificate.alias());
                chain = single == null ? new Certificate[0] : new Certificate[] {single};
            }
            List<X509Certificate> result = new ArrayList<>();
            for (Certificate c : chain) {
                if (c instanceof X509Certificate x509) {
                    result.add(x509);
                }
            }
            if (result.isEmpty()) {
                problems.add("Client-Zertifikat '" + certificate.alias() + "' nicht in Windows-MY gefunden");
            } else {
                notes.add("Das Client-Zertifikat liegt in Windows-MY (Eigene Zertifikate). Auf dem anderen Rechner "
                        + "muss es samt privatem Schlüssel als .pfx in „Eigene Zertifikate“ importiert werden.");
            }
            return result;
        } catch (Exception e) {
            LOG.warn("Zertifikatsexport {}: Windows-MY nicht lesbar ({})", config.id(), e.getClass().getSimpleName());
            problems.add("Windows-Zertifikatsspeicher (Windows-MY) nicht lesbar (" + e.getClass().getSimpleName()
                    + ")");
            return List.of();
        }
    }

    private static String fileName(Path path) {
        return path == null || path.getFileName() == null ? "-" : path.getFileName().toString();
    }

    private static String title(SourceSettingsConfig config) {
        return config.title() == null || config.title().isBlank() ? config.id() : config.title();
    }

    private static Path unique(Path directory, String stem) {
        Path file = directory.resolve(stem + ".zip");
        for (int i = 2; Files.exists(file); i++) {
            file = directory.resolve(stem + " (" + i + ").zip");
        }
        return file;
    }
}
