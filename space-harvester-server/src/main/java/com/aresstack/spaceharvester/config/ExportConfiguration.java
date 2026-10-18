package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.export.ExportArtifactWriter;
import com.aresstack.spaceharvester.application.export.ExportAssetCollection;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.output.convert.DocxConverter;
import com.aresstack.spaceharvester.output.convert.JsonArrayConverter;
import com.aresstack.spaceharvester.output.convert.JsonPackageConverter;
import com.aresstack.spaceharvester.output.convert.MarkdownPackageConverter;
import com.aresstack.spaceharvester.output.convert.MarkdownConverter;
import com.aresstack.spaceharvester.output.convert.PdfConverter;
import com.aresstack.spaceharvester.output.export.FileSystemExportArtifactWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
    JsonPackageConverter jsonPackageConverter() {
        return new JsonPackageConverter();
    }

    @Bean
    MarkdownPackageConverter markdownPackageConverter() {
        return new MarkdownPackageConverter();
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
    SignInRequests signInRequests(@Value("${space-harvester.export.sign-in-wait:PT0S}") Duration wait) {
        // ADR 0018: ohne Wartezeit (Standard) wartet kein Auftrag; mit Wartezeit beantwortet ein Client die
        // Anforderung über /api/v1/sign-ins.
        return new SignInRequests(wait, Clock.systemUTC());
    }

    @Bean
    ExportAssetCollection exportAssetCollection(Jobs jobs, ExportArtifactWriter writer, SourceQueries sources,
                                                Assets assets, Exports exports, AccessGuard access,
                                                SignInRequests signIns) {
        // Bilder gehören zum Datenbestand: nach jedem veröffentlichten Export ein eigener Auftrag ASSETS. Bilder, die
        // die Quelle aus anderen Seiten einbindet, nur für technische Clients mit Freigabe der ganzen Quelle.
        ExportAssetCollection images = new ExportAssetCollection(jobs, writer, sources, assets,
                (owner, sourceId) -> technicalClient(owner)
                        .filter(client -> access.permitsSourceWide(client, sourceId, AccessKind.CONTENT))
                        .isPresent(), signIns);
        exports.onPublished(images::start);
        return images;
    }

    /** Der technische Client zu einem Auftragseigentümer ({@link AccessContext#jobOwner()}); Endnutzer: leer. */
    static Optional<AccessContext> technicalClient(String owner) {
        String prefix = AccessContext.PrincipalKind.TECHNICAL_CLIENT.name() + ":";
        if (owner == null || !owner.startsWith(prefix)) {
            return Optional.empty();
        }
        AccessContext client = AccessContext.technicalClient(owner.substring(prefix.length()));
        return client.jobOwner().equals(owner) ? Optional.of(client) : Optional.empty();
    }

    @Bean
    ExportConversions exportConversions(Jobs jobs, ExportArtifactWriter writer, ExportConverters converters,
                                        ExportAssetCollection images) {
        return new ExportConversions(jobs, writer, converters, images);
    }
}
