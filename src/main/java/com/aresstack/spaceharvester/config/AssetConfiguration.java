package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.assets.AssetStore;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Verdrahtet die Asset-Ernte (#19). Anhänge liegen unter {@code space-harvester.assets.directory} (Standard
 * {@code data/assets}); sie sind so schutzbedürftig wie die Seiten, das Verzeichnis gehört nur dem Dienstkonto. Größere
 * Dateien als {@code space-harvester.assets.max-size} (Standard 50 MB) werden weder geladen noch gekürzt abgelegt.
 */
@Configuration(proxyBeanMethods = false)
class AssetConfiguration {

    @Bean
    AssetStore assetStore(@Value("${space-harvester.assets.directory:data/assets}") Path directory) {
        return new FileSystemAssetStore(directory);
    }

    @Bean
    Assets assets(SourceCatalog catalog, AssetStore store,
                  @Value("${space-harvester.assets.max-size:50MB}") DataSize maxSize) {
        return new Assets(catalog, store, maxSize.toBytes(), Clock.systemUTC());
    }
}
