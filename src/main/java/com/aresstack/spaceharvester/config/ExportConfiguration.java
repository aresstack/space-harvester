package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.export.ExportArtifactWriter;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.output.export.FileSystemExportArtifactWriter;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet Space-Exporte (#11). Artefakte liegen unter {@code space-harvester.export.directory} (Standard
 * {@code data/exports}); sie enthalten schutzbedürftige Rohdaten, das Verzeichnis gehört nur dem Dienstkonto. Ohne
 * {@link PageCacheReader}-Bean scheitern Exporte mit {@code CACHE_UNAVAILABLE}.
 */
@Configuration(proxyBeanMethods = false)
class ExportConfiguration {

    @Bean
    ExportArtifactWriter exportArtifactWriter(
            @Value("${space-harvester.export.directory:data/exports}") Path directory) {
        return new FileSystemExportArtifactWriter(directory);
    }

    @Bean
    Exports exports(Jobs jobs, ObjectProvider<PageCacheReader> cache, ExportArtifactWriter writer) {
        return new Exports(jobs, cache.getIfAvailable(PageCacheReader::unavailable), writer, Clock.systemUTC());
    }
}
