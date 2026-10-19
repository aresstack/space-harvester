package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.assets.AssetStore;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.assets.LargeFileGate;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.source.TikaDocumentText;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Verdrahtet die Asset-Ernte (#19). Anhänge liegen unter {@code space-harvester.assets.directory} (Standard
 * {@code data/assets}); sie sind so schutzbedürftig wie die Seiten, das Verzeichnis gehört nur dem Dienstkonto. Größere
 * Dateien als {@code space-harvester.assets.max-size} (Standard 100 MB, wie {@code READER_MAX_BYTES} im Enterprise AI
 * Client) werden weder geladen noch gekürzt abgelegt; der Download bricht schon im Transport an dieser Grenze ab.
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
                  @Value("${space-harvester.assets.max-size:100MB}") DataSize maxSize,
                  @Value("${space-harvester.assets.large-file-threshold:20MB}") DataSize largeFileThreshold,
                  @Value("${space-harvester.assets.max-concurrent-large-files:"
                          + LargeFileGate.DEFAULT_MAX_CONCURRENT + "}") int maxConcurrentLargeFiles,
                  @Value("${space-harvester.assets.large-file-wait:30s}") Duration largeFileWait) {
        LargeFileGate gate = new LargeFileGate(largeFileThreshold.toBytes(), maxConcurrentLargeFiles, largeFileWait);
        // Der Index-Bean selbst, nie eine Hülle der Suche (etwa Auszüge aus Absätzen): Assets schreibt Anhänge hinein.
        return new Assets(catalog, store, maxSize.toBytes(), Clock.systemUTC(), describer.getIfAvailable(),
                TikaDocumentText.shared(), texts.getIfAvailable(), gate,
                signIns.getIfAvailable());
    }
}
