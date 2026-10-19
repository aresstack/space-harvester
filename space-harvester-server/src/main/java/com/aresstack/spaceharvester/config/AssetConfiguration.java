package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.assets.AssetStore;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.source.TikaDocumentText;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Verdrahtet die Asset-Ernte (#19). Anhänge liegen unter {@code space-harvester.assets.directory} (Standard
 * {@code data/assets}); sie sind so schutzbedürftig wie die Seiten, das Verzeichnis gehört nur dem Dienstkonto. Größere
 * Dateien als {@code space-harvester.assets.max-size} (Standard 50 MB) werden weder geladen noch gekürzt abgelegt; der
 * Download bricht schon im Transport an dieser Grenze ab. Alle Inhalte zusammen belegen höchstens
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
                  ObjectProvider<PageTextIndex> texts,
                  @Value("${space-harvester.assets.max-size:50MB}") DataSize maxSize) {
        return new Assets(catalog, store, maxSize.toBytes(), Clock.systemUTC(), describer.getIfAvailable(),
                TikaDocumentText.shared(), texts.getIfAvailable());
    }
}
