package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.assets.AssetStore;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStore;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.assets.LargeFileGate;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.release.AiRelease;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore;
import com.aresstack.spaceharvester.source.TikaDocumentStructure;
import com.aresstack.spaceharvester.source.TikaDocumentText;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Verdrahtet die Asset-Ernte (#19). Anhänge liegen unter {@code space-harvester.assets.directory} (Standard
 * {@code data/assets}); sie sind so schutzbedürftig wie die Seiten, das Verzeichnis gehört nur dem Dienstkonto.
 * {@code space-harvester.assets.max-size} ist ohne Grenze ({@code 0}, Standard seit Angelo 11.11.2026): Anhänge fließen
 * als Strom in die Ablage, über {@link Assets#MAX_IN_MEMORY_BYTES} gibt es sie nur als Strom. Mit einer Größe werden
 * größere Dateien weder geladen noch gekürzt abgelegt; der Download bricht schon beim Lesen an dieser Grenze ab.
 * Dateien über {@code space-harvester.assets.large-file-threshold} (Standard 20 MB) laufen höchstens
 * {@code space-harvester.assets.max-concurrent-large-files} (Standard 2) gleichzeitig, weitere warten höchstens
 * {@code space-harvester.assets.large-file-wait} (Standard 30 s) und scheitern dann vorübergehend
 * ({@link LargeFileGate}). Alle Inhalte zusammen belegen höchstens
 * {@code space-harvester.assets.max-total-bytes} (Standard 20 GiB), sonst {@code asset-store-full}.
 * Bildbeschreibungen gibt es nur mit einer {@link ImageDescriber}-Bean; ohne sie antworten die Routen mit
 * {@code image-description-unavailable}. Beim Ablegen prüft Apache Tika den Typ am Inhalt, und Dokument-Anhänge
 * bekommen ihren Text als Anreicherung ({@link TikaDocumentText}, #183); mit Cache kommt er mit der Seite als Eltern in
 * den Volltextindex ({@link PageTextIndex}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExtractionProperties.class)
class AssetConfiguration {

    @Bean
    AssetStore assetStore(@Value("${space-harvester.assets.directory:data/assets}") Path directory,
                          @Value("${space-harvester.assets.max-total-bytes:"
                                  + FileSystemAssetStore.DEFAULT_MAX_TOTAL_BYTES + "}") long maxTotalBytes) {
        return new FileSystemAssetStore(directory, maxTotalBytes);
    }

    @Bean
    Assets assets(SourceCatalog catalog, AssetStore store, ObjectProvider<ImageDescriber> describer,
                  ObjectProvider<PageTextIndex> texts, ObjectProvider<SignInRequests> signIns,
                  @Value("${space-harvester.assets.max-size:0}") DataSize maxSize,
                  @Value("${space-harvester.assets.large-file-threshold:20MB}") DataSize largeFileThreshold,
                  @Value("${space-harvester.assets.max-concurrent-large-files:"
                          + LargeFileGate.DEFAULT_MAX_CONCURRENT + "}") int maxConcurrentLargeFiles,
                  @Value("${space-harvester.assets.large-file-wait:30s}") Duration largeFileWait,
                  ObjectProvider<JobsProperties> jobs,
                  @Value("${space-harvester.assets.directory:data/assets}") Path directory,
                  ExtractionProperties extraction, ObjectProvider<AiRelease> release) {
        LargeFileGate gate = new LargeFileGate(largeFileThreshold.toBytes(), maxConcurrentLargeFiles, largeFileWait);
        // Der Index-Bean selbst, nie eine Hülle der Suche (etwa Auszüge aus Absätzen): Assets schreibt Anhänge hinein.
        Assets assets = new Assets(catalog, store, Assets.maxBytes(maxSize.toBytes()), Clock.systemUTC(),
                describer.getIfAvailable(), TikaDocumentText.shared(), texts.getIfAvailable(), gate,
                signIns.getIfAvailable());
        // ADR 0026, AP3b: abgelegte Dokumente aus der Datei lesen, Text in Abschnitten unter extractions/.
        ExtractionLimits extractionLimits = extraction.toLimits();
        assets.documentExtractions(new DocumentExtractions(new TikaDocumentStructure(extractionLimits.parallel(),
                ExtractionStore.DEFAULT_SECTION_CHARS), new FileSystemExtractionStore(directory), extractionLimits));
        // ADR 0028: Bilder einer Seite auf der Liste freigegebener Seiten gehen auch aus einer gesperrten Quelle an das
        // Vision-Modell.
        AiRelease pages = release.getIfAvailable();
        if (pages != null) {
            assets.pageReleases(pages.pages()::contains);
        }
        // Genug Plätze für die Dateien aller gleichzeitig ladenden Aufträge (jobs.parallel-downloads).
        JobsProperties limits = jobs.getIfAvailable();
        if (limits != null) {
            assets.downloadLimit(Math.max(limits.parallelDownloads(), limits.downloadConnections()));
        }
        return assets;
    }
}
