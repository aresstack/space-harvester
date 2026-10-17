package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.export.ExportArtifactWriter;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.output.convert.DocxConverter;
import com.aresstack.spaceharvester.output.convert.JsonArrayConverter;
import com.aresstack.spaceharvester.output.convert.MarkdownConverter;
import com.aresstack.spaceharvester.output.export.FileSystemExportArtifactWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet Space-Exporte (#11). Artefakte liegen unter {@code space-harvester.export.directory} (Standard
 * {@code data/exports}); sie enthalten schutzbedürftige Rohdaten, das Verzeichnis gehört nur dem Dienstkonto. Ohne
 * {@link PageCacheReader}-Bean scheitern Exporte mit {@code CACHE_UNAVAILABLE}.
 *
 * <p>Converter (#30) sind Beans vom Typ {@link ExportConverter}; jede konvertierte Datei darf höchstens
 * {@code space-harvester.export.max-converted-bytes} groß werden (Standard 512 MiB), sonst scheitert der Export.
 */
@Configuration(proxyBeanMethods = false)
class ExportConfiguration {

    @Bean
    ExportArtifactWriter exportArtifactWriter(
            @Value("${space-harvester.export.directory:data/exports}") Path directory) {
        return new FileSystemExportArtifactWriter(directory);
    }

    @Bean
    JsonArrayConverter jsonArrayConverter() {
        return new JsonArrayConverter();
    }

    @Bean
    MarkdownConverter markdownConverter() {
        return new MarkdownConverter();
    }

    @Bean
    DocxConverter docxConverter() {
        return new DocxConverter();
    }

    @Bean
    ExportConverters exportConverters(List<ExportConverter> converters,
            @Value("${space-harvester.export.max-converted-bytes:" + ExportConverters.DEFAULT_MAX_BYTES + "}")
            long maxBytes) {
        return new ExportConverters(converters, maxBytes);
    }

    @Bean
    Exports exports(Jobs jobs, ObjectProvider<PageCacheReader> cache, ExportArtifactWriter writer,
                    ExportConverters converters) {
        return new Exports(jobs, cache.getIfAvailable(PageCacheReader::unavailable), writer, converters,
                Clock.systemUTC());
    }
}
