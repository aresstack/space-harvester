package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.ProxySettings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Die gelesene Konfiguration der App: dieselben Schlüssel unter {@code space-harvester} wie beim Dienst (Quellen,
 * Netzwerk, Export, Anhänge, KI-Dienst, {@code spring.ssl.bundle.*} für Vertrauen), dazu die eigenen unter
 * {@code space-harvester-app}. Geheimnisse liegen hier nur aufgelöst im Speicher; {@code toString} der
 * Einstellungstypen zeigt keine.
 *
 * @param file        gelesene Datei, {@code null} ohne Datei
 * @param sources     Quellen in Dateireihenfolge
 * @param trustStores Vertrauensquellen je Bundle-Name aus {@code spring.ssl.bundle.*}
 * @param outputDirectory Ausgabeordner für Dateien, die der Benutzer aus einem Export erzeugt
 *                        ({@code space-harvester-app.output-directory}, Vorgabe der Ordner Downloads)
 * @param problems    verständliche Hinweise auf Fehler der Datei; Quellen mit Fehlern fehlen in {@code sources}
 */
public record AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                          ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                          Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                          AiSettings ai, Path outputDirectory, List<String> problems) {

    public AppSettings {
        sources = List.copyOf(sources);
        proxy = proxy == null ? ProxySettings.disabled() : proxy;
        asyncTransport = asyncTransport == null ? ConfiguredSourceCatalog.AsyncTransport.WEB_CLIENT : asyncTransport;
        trustStores = Map.copyOf(trustStores);
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(jobLimits, "jobLimits");
        ai = ai == null ? AiSettings.NONE : ai;
        outputDirectory = outputDirectory == null ? defaultOutputDirectory() : outputDirectory;
        problems = List.copyOf(problems);
    }

    /** Dieselbe Konfiguration mit anderem Ausgabeordner; die Laufzeit braucht ihn nicht. */
    public AppSettings withOutputDirectory(Path directory) {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, directory,
                problems);
    }

    /** Vorgabe des Ausgabeordners: Downloads im Benutzerverzeichnis. */
    public static Path defaultOutputDirectory() {
        return Path.of(System.getProperty("user.home"), "Downloads");
    }

    /**
     * Eine Quelle.
     *
     * @param config         Einstellungen; fehlt das Geheimnis ({@code secretMissing}), steht dort ein leeres
     * @param secretMissing  Passwort bzw. Token ist weder in der Datei noch in der Umgebung gesetzt; die App fragt es
     *                       für die Sitzung ab
     * @param secretVariable Name der Umgebungsvariable, die das Geheimnis liefern sollte, oder {@code null}
     */
    public record SourceEntry(SourceSettingsConfig config, boolean secretMissing, String secretVariable) {

        public SourceEntry {
            Objects.requireNonNull(config, "config");
        }

        public String id() {
            return config.id();
        }
    }

    /**
     * Ablageorte und Grenzen.
     *
     * @param dataDirectory   Datenverzeichnis; darunter liegt der Cache in {@code cache/}
     * @param exportDirectory Exportartefakte
     * @param assetDirectory  abgelegte Anhänge
     * @param maxAssetBytes   größter Anhang, der geladen wird
     * @param searchTimeout   Zeitgrenze einer Suche
     * @param maxTreeNodes    größter Seitenbaum
     * @param pdfFont         TrueType-Schrift für PDF-Exporte oder {@code null}
     */
    public record Storage(Path dataDirectory, Path exportDirectory, Path assetDirectory, long maxAssetBytes,
                          Duration searchTimeout, int maxTreeNodes, Path pdfFont) {

        public Storage {
            Objects.requireNonNull(dataDirectory, "dataDirectory");
            Objects.requireNonNull(exportDirectory, "exportDirectory");
            Objects.requireNonNull(assetDirectory, "assetDirectory");
            Objects.requireNonNull(searchTimeout, "searchTimeout");
        }
    }

    /**
     * Vertrauensquelle aus {@code spring.ssl.bundle.jks.<name>.truststore} oder
     * {@code spring.ssl.bundle.pem.<name>.truststore}.
     *
     * @param location Keystore-Datei (JKS/PKCS12) oder {@code null} bei PEM
     * @param type     Keystore-Typ, Standard nach Dateiendung
     * @param password Passwort des Keystores oder {@code null}
     * @param pem      PEM-Datei mit CA-Zertifikaten oder {@code null}
     */
    public record TrustStoreSettings(String name, Path location, String type, String password, Path pem) {

        @Override
        public String toString() {
            return "TrustStoreSettings[name=" + name + ", location=" + location + ", type=" + type + ", password="
                    + (password == null ? "-" : "***") + ", pem=" + pem + "]";
        }
    }

    /**
     * KI-Dienst ({@code space-harvester.ai}, ADR 0011): OpenAI-kompatible Basisadresse, gewählte Modelle und die für
     * externe Verarbeitung freigegebenen Quellen. Der API-Key steht nie in der Datei, sondern im Tresor der App.
     *
     * @param timeout {@code null} für den Standard des Core
     */
    public record AiSettings(String baseUrl, String chatModel, String embeddingModel, Integer embeddingDimensions,
                             Duration timeout, List<String> externalProcessingSources) {

        public static final AiSettings NONE = new AiSettings(null, null, null, null, null, List.of());

        public AiSettings {
            externalProcessingSources = List.copyOf(externalProcessingSources);
        }

        /** Ob die semantische Suche damit möglich wäre: Adresse und Embedding-Modell sind gesetzt. */
        public boolean configured() {
            return baseUrl != null && embeddingModel != null;
        }
    }
}
