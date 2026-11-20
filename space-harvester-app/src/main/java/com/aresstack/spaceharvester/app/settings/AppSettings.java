package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.configuration.ChangeLogSettings;
import com.aresstack.spaceharvester.application.configuration.EnrichmentSettings;
import com.aresstack.spaceharvester.application.configuration.CheckSettings;
import com.aresstack.spaceharvester.application.configuration.SimplificationSettings;
import com.aresstack.spaceharvester.application.configuration.StandingSettings;
import com.aresstack.spaceharvester.application.configuration.LocalFileSettings;
import com.aresstack.spaceharvester.application.configuration.SourceSyncOverride;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.configuration.TermSettings;
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
 * @param localFiles      freigegebene Ordner für lokale Dateien ({@code space-harvester.local-files.allow-all},
 *                        {@code .roots}); ohne Angabe nur das Verzeichnis der Ordner-Quelle selbst
 * @param sourceKeePass   Quellen-ID → Titel des KeePass-Eintrags ({@code space-harvester-app.source-keepass-entries})
 * @param inactiveRoots   vorübergehend abgeschaltete Ordner ({@code space-harvester-app.inactive-roots}): bleiben in
 *                        der Tabelle „Dateizugriff“ stehen, gelten aber nicht; nur die App kennt sie
 * @param terms           Budget der Synonymerkennung ({@code space-harvester.terms.synonyms.*}, #336), wie beim
 *                        Dienst
 * @param consoleOutput   welche Meldungen im Terminal erscheinen ({@code logging.threshold.console}, derselbe Schlüssel
 *                        wie beim Dienst); ohne Angabe {@link ConsoleOutput#DEFAULT}, die Protokolldatei bekommt immer
 *                        alles
 * @param analytics       Zeitraum und Pfade des Exports „Analytics“ ({@code space-harvester-app.analytics}); ohne
 *                        Angabe die Vorgaben des Core, nur die App
 * @param simplification  Vorstufe „Sätze vereinfachen“ der Aussagenanalyse ({@code space-harvester.statements.simplification.*},
 *                        ADR 0032), wie beim Dienst
 * @param standing        Wörter für überholte Seiten und Entwürfe der Status-quo-Sicht
 *                        ({@code space-harvester.statements.standing.*}, ADR 0032 FL6), wie beim Dienst
 * @param check           Prüfschritt der Aussagenanalyse ({@code space-harvester.statements.check.*}, ADR 0032 FL4),
 *                        wie beim Dienst
 * @param viewerBackground Hintergrund des Bildbetrachters ({@code space-harvester-app.viewer.background}); ohne
 *                        Angabe {@link ViewerBackground#DEFAULT}, die Oberfläche der App; nur die App
 * @param video           die Werte für Videos ({@code space-harvester-app.video.*}): Marken, Standbilder, Player;
 *                        ohne Angabe {@link VideoSettings#DEFAULT}; nur die App
 */
public record AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                          ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                          Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                          AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                          TrustSettings tls, Cache cache, boolean setupCompleted,
                          Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess,
                          LocalFileSettings localFiles, Map<String, String> sourceKeePass,
                          List<Path> inactiveRoots, TermSettings terms, ConsoleOutput consoleOutput,
                          EnrichmentSettings enrichment, AnalyticsSettings analytics,
                          SimplificationSettings simplification, StandingSettings standing,
                          CheckSettings check, ViewerBackground viewerBackground, VideoSettings video) {

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
        localFiles = localFiles == null ? LocalFileSettings.DEFAULT : localFiles;
        sourceKeePass = sourceKeePass == null ? Map.of() : Map.copyOf(sourceKeePass);
        inactiveRoots = inactiveRoots == null ? List.of() : List.copyOf(inactiveRoots);
        terms = terms == null ? TermSettings.DEFAULT : terms;
        consoleOutput = consoleOutput == null ? ConsoleOutput.DEFAULT : consoleOutput;
        enrichment = enrichment == null ? EnrichmentSettings.DEFAULT : enrichment;
        analytics = analytics == null ? AnalyticsSettings.DEFAULT : analytics;
        simplification = simplification == null ? SimplificationSettings.DEFAULT : simplification;
        standing = standing == null ? StandingSettings.DEFAULT : standing;
        check = check == null ? CheckSettings.DEFAULT : check;
        viewerBackground = viewerBackground == null ? ViewerBackground.DEFAULT : viewerBackground;
        video = video == null ? VideoSettings.DEFAULT : video;
    }

    /** Mit den Werten für Videos ohne Angabe ({@link VideoSettings#DEFAULT}), für Aufrufer ohne Bedarf. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess,
                       LocalFileSettings localFiles, Map<String, String> sourceKeePass,
                       List<Path> inactiveRoots, TermSettings terms, ConsoleOutput consoleOutput,
                       EnrichmentSettings enrichment, AnalyticsSettings analytics,
                       SimplificationSettings simplification, StandingSettings standing,
                       CheckSettings check, ViewerBackground viewerBackground) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles, sourceKeePass,
                inactiveRoots, terms, consoleOutput, enrichment, analytics, simplification, standing, check,
                viewerBackground, VideoSettings.DEFAULT);
    }

    /** Mit dem Hintergrund des Bildbetrachters ohne Angabe ({@link ViewerBackground#DEFAULT}), für Aufrufer ohne Bedarf. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess,
                       LocalFileSettings localFiles, Map<String, String> sourceKeePass,
                       List<Path> inactiveRoots, TermSettings terms, ConsoleOutput consoleOutput,
                       EnrichmentSettings enrichment, AnalyticsSettings analytics,
                       SimplificationSettings simplification, StandingSettings standing,
                       CheckSettings check) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles, sourceKeePass,
                inactiveRoots, terms, consoleOutput, enrichment, analytics, simplification, standing, check,
                ViewerBackground.DEFAULT);
    }

    /** Mit den Vorgabewörtern des Stands ({@link StandingSettings#DEFAULT}), für Aufrufer ohne Bedarf. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess,
                       LocalFileSettings localFiles, Map<String, String> sourceKeePass,
                       List<Path> inactiveRoots, TermSettings terms, ConsoleOutput consoleOutput,
                       EnrichmentSettings enrichment, AnalyticsSettings analytics,
                       SimplificationSettings simplification) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles, sourceKeePass,
                inactiveRoots, terms, consoleOutput, enrichment, analytics, simplification, StandingSettings.DEFAULT,
                CheckSettings.DEFAULT);
    }

    /** Mit dem Prüfschritt ohne Angabe ({@link CheckSettings#DEFAULT}), für Aufrufer ohne Bedarf. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess,
                       LocalFileSettings localFiles, Map<String, String> sourceKeePass,
                       List<Path> inactiveRoots, TermSettings terms, ConsoleOutput consoleOutput,
                       EnrichmentSettings enrichment, AnalyticsSettings analytics,
                       SimplificationSettings simplification, StandingSettings standing) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles, sourceKeePass,
                inactiveRoots, terms, consoleOutput, enrichment, analytics, simplification, standing,
                CheckSettings.DEFAULT);
    }

    /** Mit der Vorstufe ohne Angabe ({@link SimplificationSettings#DEFAULT}), für Aufrufer ohne Bedarf. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess,
                       LocalFileSettings localFiles, Map<String, String> sourceKeePass,
                       List<Path> inactiveRoots, TermSettings terms, ConsoleOutput consoleOutput,
                       EnrichmentSettings enrichment, AnalyticsSettings analytics) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles, sourceKeePass,
                inactiveRoots, terms, consoleOutput, enrichment, analytics, SimplificationSettings.DEFAULT);
    }

    /** Mit dem Export „Analytics“ ohne Angabe ({@link AnalyticsSettings#DEFAULT}), für Aufrufer ohne Bedarf. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess,
                       LocalFileSettings localFiles, Map<String, String> sourceKeePass,
                       List<Path> inactiveRoots, TermSettings terms, ConsoleOutput consoleOutput,
                       EnrichmentSettings enrichment) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles, sourceKeePass,
                inactiveRoots, terms, consoleOutput, enrichment, AnalyticsSettings.DEFAULT);
    }

    /** Mit den Analysen je Dokument ohne Angabe ({@link EnrichmentSettings#DEFAULT}), für Aufrufer ohne Bedarf. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess,
                       LocalFileSettings localFiles, Map<String, String> sourceKeePass,
                       List<Path> inactiveRoots, TermSettings terms, ConsoleOutput consoleOutput) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles, sourceKeePass,
                inactiveRoots, terms, consoleOutput, EnrichmentSettings.DEFAULT);
    }

    /** Mit der Terminal-Ausgabe ohne Angabe ({@link ConsoleOutput#DEFAULT}), für Aufrufer, die sie nicht brauchen. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess,
                       LocalFileSettings localFiles, Map<String, String> sourceKeePass,
                       List<Path> inactiveRoots, TermSettings terms) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles, sourceKeePass,
                inactiveRoots, terms, ConsoleOutput.DEFAULT);
    }

    /** Mit dem Budget der Synonymerkennung aus der Konfiguration, für Aufrufer, die es nicht brauchen. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess,
                       LocalFileSettings localFiles, Map<String, String> sourceKeePass,
                       List<Path> inactiveRoots) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles, sourceKeePass,
                inactiveRoots, TermSettings.DEFAULT);
    }

    /** Ohne abgeschaltete Ordner, für Aufrufer, die sie nicht brauchen. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess,
                       LocalFileSettings localFiles, Map<String, String> sourceKeePass) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles, sourceKeePass,
                List.of());
    }

    /** Ohne Zugangsdaten aus KeePass für Quellen, für Aufrufer, die sie nicht brauchen. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess,
                       LocalFileSettings localFiles) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles, Map.of());
    }

    /** Titel des KeePass-Eintrags, aus dem die Quelle Benutzer und Passwort bzw. Token bezieht, oder {@code null}. */
    public String keePassEntry(String sourceId) {
        return sourceKeePass.get(sourceId);
    }

    /** Ohne freigegebene Ordner, für Aufrufer, die sie nicht brauchen. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync, AiAccess aiAccess) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, LocalFileSettings.DEFAULT);
    }

    /** Ohne Bezugsart des KI-Keys, für Aufrufer, die sie nicht brauchen. */
    public AppSettings(Path file, List<SourceEntry> sources, ProxySettings proxy,
                       ConfiguredSourceCatalog.AsyncTransport asyncTransport,
                       Map<String, TrustStoreSettings> trustStores, Storage storage, JobLimits jobLimits,
                       AiSettings ai, Path outputDirectory, List<String> problems, Set<String> disabledSources,
                       TrustSettings tls, Cache cache, boolean setupCompleted,
                       Map<String, SourceSyncOverride> syncRules, SyncSettings sync) {
        this(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, outputDirectory, problems,
                disabledSources, tls, cache, setupCompleted, syncRules, sync, AiAccess.NONE, LocalFileSettings.DEFAULT);
    }

    /** Dieselbe Konfiguration mit anderem Ausgabeordner; die Laufzeit braucht ihn nicht. */
    public AppSettings withOutputDirectory(Path directory) {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai, directory,
                problems, disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles, sourceKeePass,
                inactiveRoots, terms, consoleOutput, enrichment, analytics, simplification, standing, check, viewerBackground, video);
    }

    /** Dieselbe Konfiguration mit anderen Grenzen für Bilder- und Anhängeaufträge ({@link JobLimits#withParallel}). */
    public AppSettings withParallel(int preparations, int downloads) {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage,
                jobLimits.withParallel(preparations, downloads), ai, outputDirectory, problems, disabledSources, tls,
                cache, setupCompleted, syncRules, sync, aiAccess, localFiles, sourceKeePass, inactiveRoots, terms,
                consoleOutput, enrichment, analytics, simplification, standing, check, viewerBackground, video);
    }

    /**
     * Wie {@link #withParallel(int, int)}, zusätzlich mit dem Gesamtbudget an Verbindungen für Bilder und Anhänge
     * ({@link JobLimits#withConnections}).
     */
    public AppSettings withParallel(int preparations, int downloads, int connections) {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage,
                jobLimits.withParallel(preparations, downloads).withConnections(connections), ai, outputDirectory,
                problems, disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess, localFiles,
                sourceKeePass, inactiveRoots, terms, consoleOutput, enrichment, analytics, simplification, standing, check, viewerBackground, video);
    }

    /**
     * Diese Konfiguration mit den Laufzeitwerten von {@code other}: KI-Dienst, Aktualität und Frist, Synchronisierung
     * und Regeln je Quelle, Bezug des API-Keys, freigegebene Ordner, parallele Vorbereitungen und Downloads,
     * Terminal-Ausgabe, Export „Analytics“, Hintergrund des Bildbetrachters. Gleicht das Ergebnis {@code other}, unterscheiden sich beide nur darin.
     */
    public AppSettings withLive(AppSettings other) {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage,
                jobLimits.withParallel(other.jobLimits.parallelPreparations(), other.jobLimits.parallelDownloads())
                        .withConnections(other.jobLimits.downloadConnections()),
                other.ai,
                outputDirectory, other.problems, disabledSources, tls, other.cache, other.setupCompleted, other.syncRules,
                other.sync, other.aiAccess, other.localFiles, other.sourceKeePass, other.inactiveRoots, other.terms,
                other.consoleOutput, other.enrichment, other.analytics, other.simplification, other.standing, other.check, other.viewerBackground, other.video);
    }

    /** Dieselbe Konfiguration mit gesetztem Merker des Einrichtungsassistenten; die Laufzeit braucht ihn nicht. */
    public AppSettings withSetupCompleted() {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai,
                outputDirectory, problems, disabledSources, tls, cache, true, syncRules, sync, aiAccess, localFiles, sourceKeePass,
                inactiveRoots, terms, consoleOutput, enrichment, analytics, simplification, standing, check, viewerBackground, video);
    }

    /** Dieselbe Konfiguration mit anderer Terminal-Ausgabe; die Laufzeit braucht sie nicht. */
    public AppSettings withConsoleOutput(ConsoleOutput output) {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai,
                outputDirectory, problems, disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess,
                localFiles, sourceKeePass, inactiveRoots, terms, output, enrichment, analytics, simplification, standing, check, viewerBackground, video);
    }

    /** Dieselbe Konfiguration mit anderem Hintergrund des Bildbetrachters; die Laufzeit braucht ihn nicht. */
    public AppSettings withViewerBackground(ViewerBackground background) {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai,
                outputDirectory, problems, disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess,
                localFiles, sourceKeePass, inactiveRoots, terms, consoleOutput, enrichment, analytics, simplification,
                standing, check, background, video);
    }

    /** Dieselbe Konfiguration mit anderen Werten für Videos; der Player liest sie beim nächsten Öffnen. */
    public AppSettings withVideo(VideoSettings video) {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai,
                outputDirectory, problems, disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess,
                localFiles, sourceKeePass, inactiveRoots, terms, consoleOutput, enrichment, analytics, simplification,
                standing, check, viewerBackground, video);
    }

    /** Dieselbe Konfiguration mit anderem Export „Analytics“; die Laufzeit braucht ihn nicht. */
    public AppSettings withAnalytics(AnalyticsSettings analytics) {
        return new AppSettings(file, sources, proxy, asyncTransport, trustStores, storage, jobLimits, ai,
                outputDirectory, problems, disabledSources, tls, cache, setupCompleted, syncRules, sync, aiAccess,
                localFiles, sourceKeePass, inactiveRoots, terms, consoleOutput, enrichment, analytics, simplification, standing, check, viewerBackground, video);
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
    public record SourceEntry(SourceSettingsConfig config, boolean secretMissing, String secretVariable,
                              Map<String, String> placeholderOptions) {

        public SourceEntry {
            Objects.requireNonNull(config, "config");
            placeholderOptions = placeholderOptions == null ? Map.of() : Map.copyOf(placeholderOptions);
        }

        public SourceEntry(SourceSettingsConfig config, boolean secretMissing, String secretVariable) {
            this(config, secretMissing, secretVariable, Map.of());
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
     * @param maxAssetBytes   größter Anhang, der geladen wird; {@code 0} ohne Grenze (Standard)
     * @param largeAssetBytes ab dieser Größe gilt ein Anhang als groß ({@code assets.large-file-threshold})
     * @param maxLargeAssets  so viele große Anhänge höchstens gleichzeitig ({@code assets.max-concurrent-large-files})
     * @param searchTimeout   Zeitgrenze einer Suche
     * @param maxTreeNodes    größter Seitenbaum
     * @param pdfFont         TrueType-Schrift für PDF-Exporte oder {@code null}
     * @param extraction      Schutzgrenzen beim Lesen von Dokumenten ({@code assets.extraction.*}, ADR 0026)
     */
    public record Storage(Path dataDirectory, Path exportDirectory, Path assetDirectory, long maxAssetBytes,
                          long largeAssetBytes, int maxLargeAssets, Duration searchTimeout, int maxTreeNodes,
                          Path pdfFont, ExtractionLimits extraction) {

        public Storage {
            Objects.requireNonNull(dataDirectory, "dataDirectory");
            Objects.requireNonNull(exportDirectory, "exportDirectory");
            Objects.requireNonNull(assetDirectory, "assetDirectory");
            Objects.requireNonNull(searchTimeout, "searchTimeout");
            extraction = extraction == null ? ExtractionLimits.DEFAULTS : extraction;
        }

        /** Mit den Standardgrenzen der Extraktion. */
        public Storage(Path dataDirectory, Path exportDirectory, Path assetDirectory, long maxAssetBytes,
                       long largeAssetBytes, int maxLargeAssets, Duration searchTimeout, int maxTreeNodes,
                       Path pdfFont) {
            this(dataDirectory, exportDirectory, assetDirectory, maxAssetBytes, largeAssetBytes, maxLargeAssets,
                    searchTimeout, maxTreeNodes, pdfFont, ExtractionLimits.DEFAULTS);
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
     * @param timeout       {@code null} für den Standard des Core
     * @param chatMaxTokens Ausgabelimit einer Antwort des Chat-Modells in Tokens ({@code chat-max-tokens});
     *                      {@code null} für den Standard des Core (8192)
     * @param provider      Art des Dienstes ({@code provider}): OpenAI-kompatibel oder Ollama (Basisadresse = Server)
     * @param contextTokens Kontextlänge je Anfrage an Ollama ({@code context-tokens}); {@code null}: Standard des Core
     * @param loadTimeout   so lange darf Ollama ein Modell laden ({@code load-timeout}); {@code null}: Standard des Core
     * @param retries       Wiederholungen einer fehlgeschlagenen Bildbeschreibung ({@code retries}); {@code null}:
     *                      Standard des Core
     * @param loadPollInterval Abstand der Frage an Ollama, ob ein angestoßenes Modell läuft
     *                      ({@code load-poll-interval}); {@code null}: Standard des Core
     */
    public record AiSettings(String baseUrl, String chatModel, String embeddingModel, Integer embeddingDimensions,
                             Duration timeout, List<String> externalProcessingSources, String rerankModel,
                             Integer chunkTokens, Integer chunkOverlapSentences, String visionModel,
                             Integer chatMaxTokens, AiProvider provider, Integer contextTokens,
                             Duration loadTimeout, Integer retries, Duration loadPollInterval) {

        public static final AiSettings NONE = new AiSettings(null, null, null, null, null, List.of());

        public AiSettings {
            externalProcessingSources = List.copyOf(externalProcessingSources);
            provider = provider == null ? AiProvider.OPENAI_COMPATIBLE : provider;
        }

        /** Für einen OpenAI-kompatiblen Dienst. */
        public AiSettings(String baseUrl, String chatModel, String embeddingModel, Integer embeddingDimensions,
                          Duration timeout, List<String> externalProcessingSources, String rerankModel,
                          Integer chunkTokens, Integer chunkOverlapSentences, String visionModel,
                          Integer chatMaxTokens) {
            this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources,
                    rerankModel, chunkTokens, chunkOverlapSentences, visionModel, chatMaxTokens, null, null, null,
                    null, null);
        }

        /** Mit dem Ausgabelimit des Core. */
        public AiSettings(String baseUrl, String chatModel, String embeddingModel, Integer embeddingDimensions,
                          Duration timeout, List<String> externalProcessingSources, String rerankModel,
                          Integer chunkTokens, Integer chunkOverlapSentences, String visionModel) {
            this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources,
                    rerankModel, chunkTokens, chunkOverlapSentences, visionModel, null);
        }

        /** Ohne Vision-Modell: keine Bildbeschreibung (#362). */
        public AiSettings(String baseUrl, String chatModel, String embeddingModel, Integer embeddingDimensions,
                          Duration timeout, List<String> externalProcessingSources, String rerankModel,
                          Integer chunkTokens, Integer chunkOverlapSentences) {
            this(baseUrl, chatModel, embeddingModel, embeddingDimensions, timeout, externalProcessingSources,
                    rerankModel, chunkTokens, chunkOverlapSentences, null);
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
    public record Cache(Duration maxAge, boolean versionCheck, Duration withheldRetention,
                        ChangeLogSettings changeLog) {

        public static final Duration DEFAULT_RETENTION = Duration.ofDays(30);
        public static final Cache DEFAULT = new Cache(Duration.ZERO, true, DEFAULT_RETENTION);

        /** Mit der Vorgabe des Änderungsprotokolls ({@link ChangeLogSettings#DEFAULT}). */
        public Cache(Duration maxAge, boolean versionCheck, Duration withheldRetention) {
            this(maxAge, versionCheck, withheldRetention, ChangeLogSettings.DEFAULT);
        }

        public Cache {
            maxAge = maxAge == null ? Duration.ZERO : maxAge;
            withheldRetention = withheldRetention == null ? DEFAULT_RETENTION : withheldRetention;
            changeLog = changeLog == null ? ChangeLogSettings.DEFAULT : changeLog;
        }
    }
}
