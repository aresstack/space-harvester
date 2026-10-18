package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.application.configuration.SourceSyncOverride;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustSettings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 * @param tls         Vertrauen für Quellen und KI-Dienst ohne eigenes {@code trust-bundle}
 *                    ({@code space-harvester.network.tls}, #189); ohne Angabe unter Windows JVM-Truststore und
 *                    Windows-Speicher wie im Enterprise AI Client, sonst nur der JVM-Truststore
 * @param disabledSources abgeschaltete Quellen ({@code space-harvester-app.disabled-sources}): bleiben eingerichtet
 *                        samt Bestand, der Core kennt sie aber nicht ({@code SourceManagement.setEnabled})
 * @param setupCompleted  der Einrichtungsassistent lief schon ({@code space-harvester-app.setup-completed}); sonst
 *                        öffnet er sich beim Start
 * @param syncRules       Regeln je Quelle ({@code space-harvester.sync.sources.<Quelle>.*}); leer gilt global
 * @param sync            globale Synchronisierungswerte ({@code space-harvester.sync.*}) ohne die Regeln je Quelle
 * @param aiAccess        woher die App den API-Key des KI-Dienstes nimmt (KeePass oder Tresor); nur die App
 */
public record AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                          ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                          Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                          AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                          TrustSettings tls, Cache cache, boolean setupCompleted,
                          Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess) {

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
        disabledSources = disabledSources == null ? Set.of() : Set.copyOf(disabledSources);
        tls = tls == null ? TrustSettings.jvm() : tls;
        cache = cache == null ? Cache.DEFAULT : cache;
        syncRules = syncRules == null ? Map.of() : Map.copyOf(syncRules);
        sync = sync == null ? SyncSettings.DEFAULT : sync;
        aiAccess = aiAccess == null ? AiAccess.NONE : aiAccess;
    }

    /** Ohne Bezugsart des KI-Keys, für Aufrufer, die sie nicht brauchen. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, AiAccess.NONE);
    }

    /** Dieselbe Konfiguration mit anderem Ausgabeordner; die Laufzeit braucht ihn nicht. */
    public AppSettings withOutputDirectory(Path directory) {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, directory,
                problems, disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess);
    }

    /**
     * Diese Konfiguration mit den Laufzeitwerten von {@code other}: KI-Dienst, Aktualität und Frist, Synchronisierung
     * und Regeln je Quelle, Bezug des API-Keys. Gleicht das Ergebnis {@code other}, unterscheiden sich beide nur darin.
     */
    public AppSettings withLive(AppSettings other) {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, other.ai,
                outputDirectory, other.problems, disabledSources, tls, other.cache, setupCompleted, other.syncRules,
                other.sync, other.aiAccess);
    }

    /** Dieselbe Konfiguration mit gesetztem Merker des Einrichtungsassistenten; die Laufzeit braucht ihn nicht. */
    public AppSettings withSetupCompleted() {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai,
                outputDirectory, problems, disabledSources, tls, cache, true, syncRules, sync, aiAccess);
    }

    /** Die Regeln dieser Quelle; ohne Eintrag {@link SourceSyncOverride#NONE}, also alles wie global. */
    public SourceSyncOverride syncRule(String sourceId) {
        return syncRules.getOrDefault(sourceId, SourceSyncOverride.NONE);
    }

    /** Was für diese Quelle wirkt: ihre Regeln, wo gesetzt, sonst die globalen Werte ({@code SyncSettings.forSource}). */
    public SyncSettings effectiveSync(String sourceId) {
        return sync.withSource(sourceId, syncRule(sourceId)).forSource(sourceId);
    }

    /** Ob die Quelle eingerichtet, aber abgeschaltet ist. */
    public boolean disabled(String sourceId) {
        return disabledSources.contains(sourceId);
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
                             Duration timeout, List<String> externalProcessingSources, String rerankModel,
                             Integer chunkTokens, Integer chunkOverlapSentences) {

        public static final AiSettings NONE = new AiSettings(null, null, null, null, null, List.of());

        public AiSettings {
            externalProcessingSources = List.copyOf(externalProcessingSources);
        }

        /** Ohne Rerank-Modell und mit der Zerlegung des Core. */
        public AiSettings(String baseUrl, String chatModel, String embeddingModel, Integer embeddingDimensions,
                          Duration timeout, List<String> externalProcessingSources) {
            this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources, null,
                    null, null);
        }

        /** Ob die semantische Suche damit möglich wäre: Adresse und Embedding-Modell sind gesetzt. */
        public boolean configured() {
            return baseUrl != null && embeddingModel != null;
        }
    }

    /**
     * Cache und Aufbewahrung, mit denselben Schlüsseln wie der Server: {@code space-harvester.cache.max-age} (Zeitfenster,
     * in dem eine unveränderte Seite ohne Rückfrage gilt; die Rechte prüft der Core trotzdem),
     * {@code space-harvester.cache.version-check} und {@code space-harvester.sync.withheld-retention} (wie lange eine
     * gesperrte Seite im Bestand bleibt, ADR 0014).
     */
    public record Cache(Duration maxAge, boolean versionCheck, Duration withheldRetention) {

        public static final Duration DEFAULT_RETENTION = Duration.ofDays(30);
        public static final Cache DEFAULT = new Cache(Duration.ZERO, true, DEFAULT_RETENTION);

        public Cache {
            maxAge = maxAge == null ? Duration.ZERO : maxAge;
            withheldRetention = withheldRetention == null ? DEFAULT_RETENTION : withheldRetention;
        }
    }
}
