package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.export.ExportArtifactWriter;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.output.convert.DocxConverter;
import com.aresstack.spaceharvester.output.convert.JsonArrayConverter;
import com.aresstack.spaceharvester.output.convert.MarkdownConverter;
import com.aresstack.spaceharvester.output.convert.PdfConverter;
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
 * {@link PageCacheReader}-Bean scheitern Exporte mit {@code CACHE_UNAVAILABLE}. Einen noch nicht eingelesenen Space liest
 * der Export über {@link SpaceSyncJobs} im eigenen Auftrag ein (ADR 0010).
 *
 * <p>Converter (#30) sind Beans vom Typ {@link ExportConverter}; jede konvertierte Datei darf höchstens
 * {@code space-harvester.export.max-converted-bytes} groß werden (Standard 512 MiB), sonst scheitert der Export.
 * Alle Artefakte zusammen belegen höchstens {@code space-harvester.export.max-total-bytes} (Standard 20 GiB); ist die
 * Ablage voll, lehnt der Start mit 507 ab und ein laufender Export scheitert mit {@code EXPORT_STORAGE_FULL}.
 * {@code space-harvester.export.pdf.font} nennt optional eine TrueType-Schrift, die PDFs einbetten.
 */
@Configuration(proxyBeanMethods = false)
class ExportConfiguration {

    @Bean
    ExportArtifactWriter exportArtifactWriter(
            @Value("${space-harvester.export.directory:data/exports}") Path directory,
            @Value("${space-harvester.export.max-total-bytes:"
                    + FileSystemExportArtifactWriter.DEFAULT_MAX_TOTAL_BYTES + "}") long maxTotalBytes) {
        return new FileSystemExportArtifactWriter(directory, maxTotalBytes);
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
    PdfConverter pdfConverter(@Value("${space-harvester.export.pdf.font:}") String font) {
        return new PdfConverter(font.isBlank() ? null : Path.of(font));
    }

    @Bean
    ExportConverters exportConverters(List<ExportConverter> converters,
            @Value("${space-harvester.export.max-converted-bytes:" + ExportConverters.DEFAULT_MAX_BYTES + "}")
            long maxBytes) {
        return new ExportConverters(converters, maxBytes);
    }

    @Bean
    Exports exports(Jobs jobs, ObjectProvider<PageCacheReader> cache, ExportArtifactWriter writer,
                    ExportConverters converters, SpaceSyncJobs syncs) {
        return new Exports(jobs, cache.getIfAvailable(PageCacheReader::unavailable), writer, converters, syncs,
                Clock.systemUTC());
    }

    @Bean
    ExportConversions exportConversions(Jobs jobs, ExportArtifactWriter writer, ExportConverters converters) {
        return new ExportConversions(jobs, writer, converters);
    }
}
