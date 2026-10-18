package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.AssetStoreFullException;
import com.aresstack.spaceharvester.application.assets.AssetTooLargeException;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bilder gehören zum einmal beschafften Datenbestand: Nach jedem veröffentlichten Export beschafft ein eigener Auftrag
 * {@link JobType#ASSETS} („Bilder beschaffen“) die Bilder seiner Seiten über die Anmeldung der Quelle, legt sie
 * versioniert und SHA-256-adressiert im Asset-Speicher ab und schreibt das Manifest {@code assets.json}
 * ({@link ExportAssetManifest}). Der Export wartet nicht darauf; konvertierbar mit Bildern ist er, sobald das Manifest
 * vorliegt ({@link #images}). Converter lesen dann nur noch aus dem Speicher ({@link #assets}).
 *
 * <p>Je Seite: Anhänge der Arten Bild, Vektor und Diagramm aus der Anhangsliste, dazu die Bilder der Lese-Ansicht,
 * die kein Anhang sind (Makros, gerenderte Diagramme), und die Mermaid-Diagramme des Storage-Formats als Text. Eine
 * Seite, deren Space inzwischen ein anderer ist, bleibt außen vor. Zähler: {@code discovered} gefundene Bilder,
 * {@code processed} geprüfte, {@code written} abgelegte (auch bereits vorhandene), {@code failed} fehlende.
 */
public class ExportAssetCollection {

    private static final System.Logger LOG = System.getLogger(ExportAssetCollection.class.getName());
    private static final String COMPONENT = "Assets";
    static final String PHASE = "Bilder beschaffen";
    /** Mermaid-Makros: {@code mermaid}, {@code mermaid-cloud}, {@code mermaid-macro} usw. */
    private static final Pattern MERMAID = Pattern.compile(
            "<ac:structured-macro\\b[^>]*\\bac:name=\"[^\"]*mermaid[^\"]*\"[^>]*>(.*?)</ac:structured-macro>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAIN_TEXT = Pattern.compile(
            "<ac:plain-text-body>\\s*<!\\[CDATA\\[(.*?)]]>\\s*</ac:plain-text-body>", Pattern.DOTALL);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Jobs jobs;
    private final ExportArtifactWriter writer;
    private final SourceQueries sources;
    private final Assets assets;
    /** Laufende Aufträge je Export; nach einem Neustart entscheidet allein das Manifest. */
    private final Map<String, String> running = new ConcurrentHashMap<>();

    public ExportAssetCollection(Jobs jobs, ExportArtifactWriter writer, SourceQueries sources, Assets assets) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    /**
     * Plant das Beschaffen der Bilder für einen veröffentlichten Export ein; für {@link Exports#onPublished}. Nimmt
     * die Auftragsverwaltung nichts mehr an, bleibt der Export ohne Bilder ({@link ExportDatasetInfo.Images#NOT_COLLECTED})
     * bis zum nächsten {@link #start}.
     *
     * @return der Auftrag oder leer, wenn keiner eingeplant wurde
     */
    public Optional<JobStatus> start(String exportId, String owner) {
        Optional<ConversionInput> dataset = writer.dataset(exportId);
        if (dataset.isEmpty()) {
            return Optional.empty();
        }
        String sourceId = dataset.get().sourceId();
        String spaceKey = dataset.get().spaceKey();
        try {
            JobStatus status = jobs.submit(JobType.ASSETS, sourceId, spaceKey, owner,
                    JobTask.blocking(context -> collect(context, exportId)));
            running.put(exportId, status.id());
            return Optional.of(status);
        } catch (JobCapacityExceededException | IllegalStateException e) {
            LOG.log(System.Logger.Level.INFO, "Bilder für Export {0} nicht eingeplant ({1})", exportId,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Stand der Bilder eines Exports. */
    public ExportDatasetInfo.Images images(String exportId) {
        Optional<ExportAssetManifest> manifest = writer.assets(exportId);
        if (manifest.isPresent()) {
            return manifest.get().complete() ? ExportDatasetInfo.Images.COMPLETE
                    : ExportDatasetInfo.Images.INCOMPLETE;
        }
        String jobId = running.get(exportId);
        if (jobId != null) {
            try {
                if (!jobs.status(jobId).state().terminal()) {
                    return ExportDatasetInfo.Images.COLLECTING;
                }
            } catch (UnknownJobException e) {
                running.remove(exportId, jobId);
            }
        }
        return ExportDatasetInfo.Images.NOT_COLLECTED;
    }

    /** Die abgelegten Bilder eines Exports für Converter; ohne Manifest {@link ExportAssets#NONE}. */
    public ExportAssets assets(String exportId) {
        Optional<ExportAssetManifest> manifest = writer.assets(exportId);
        if (manifest.isEmpty()) {
            return ExportAssets.NONE;
        }
        String sourceId = writer.dataset(exportId).map(ConversionInput::sourceId).orElse("");
        return new ExportAssets() {
            @Override
            public Optional<ExportAssetManifest> manifest() {
                return manifest;
            }

            @Override
            public Optional<byte[]> content(ExportAssetManifest.Entry entry) {
                if (!entry.stored() || entry.attachmentId() == null) {
                    return Optional.empty();
                }
                try {
                    return assets.stored(sourceId, entry.attachmentId(), entry.version(), entry.sha256());
                } catch (AssetStoreException e) {
                    return Optional.empty();
                }
            }
        };
    }

    private void collect(JobContext context, String exportId) {
        try {
            ConversionInput dataset = writer.dataset(exportId).orElseThrow(() ->
                    new ExportNotConvertibleException(exportId, "kein Datenbestand vorhanden"));
            context.phase(COMPONENT, PHASE);
            List<ExportAssetManifest.Entry> entries = new ArrayList<>();
            boolean[] complete = {true};
            boolean[] stopped = {false};
            dataset.forEachPage(page -> {
                context.checkpoint();
                if (stopped[0]) {
                    return;
                }
                try {
                    if (!collectPage(context, dataset, page.snapshot(), entries)) {
                        complete[0] = false;
                    }
                } catch (SourceUnreachable e) {
                    // Nicht erreichbar oder gedrosselt: die übrigen Seiten nicht mehr anfragen, der nächste Export
                    // holt die Bilder nach.
                    complete[0] = false;
                    stopped[0] = true;
                    LOG.log(System.Logger.Level.INFO, "Bilder für Export {0} abgebrochen: Quelle {1}", exportId,
                            e.reason);
                }
            });
            boolean allStored = entries.stream().allMatch(ExportAssetManifest.Entry::stored);
            writer.putAssets(exportId, new ExportAssetManifest(complete[0] && allStored, entries));
            LOG.log(System.Logger.Level.INFO, "Bilder für Export {0}: {1} Referenzen, {2} fehlend", exportId,
                    entries.size(), entries.stream().filter(entry -> !entry.stored()).count());
        } finally {
            running.remove(exportId);
        }
    }

    /** @return ob die Seite vollständig geprüft werden konnte */
    private boolean collectPage(JobContext context, ConversionInput dataset, PageSnapshot snapshot,
                                List<ExportAssetManifest.Entry> entries) {
        String sourceId = dataset.sourceId();
        String pageId = snapshot.key().pageId();
        boolean checked = true;
        Set<String> attachmentIds = new HashSet<>();
        try {
            PageAttachmentList list = sources.attachments(sourceId, pageId);
            if (!dataset.spaceKey().equals(list.spaceKey())) {
                // Inzwischen in einem anderen Space: die Seite bleibt, wie sie exportiert wurde, ohne Bilder.
                return false;
            }
            for (PageAttachment attachment : list.attachments().attachments()) {
                AssetKind kind = AssetKind.of(attachment.mediaType(), attachment.title());
                if (kind == AssetKind.OTHER) {
                    continue;
                }
                attachmentIds.add(attachment.attachmentId());
                context.discovered(1);
                ExportAssetManifest.Reference reference = ExportAssetManifest.Reference.attachment(
                        attachment.attachmentId(), attachment.title());
                entries.add(store(context, pageId, reference, attachment.attachmentId(), attachment.version(),
                        attachment.title(), attachment.mediaType(), () -> assets.content(list,
                                attachment.attachmentId(), true).asset()));
            }
        } catch (SourceAccessException e) {
            unreachable(e);
            if (e.reason() != SourceAccessException.Reason.UNSUPPORTED) {
                checked = false;
            }
        }
        try {
            for (HarvestSource.ViewImage image : assets.viewImages(sourceId, pageId)) {
                if (image.attachmentId() != null && attachmentIds.contains(image.attachmentId())) {
                    continue;
                }
                context.discovered(1);
                ExportAssetManifest.Reference reference = ExportAssetManifest.Reference.viewImage(withoutUserInfo(
                        image.src()));
                entries.add(store(context, pageId, reference, null, null, image.title(), image.mediaType(),
                        () -> assets.viewImage(sourceId, pageId, snapshot.revision(), image).asset()));
            }
        } catch (SourceAccessException e) {
            unreachable(e);
            if (e.reason() != SourceAccessException.Reason.UNSUPPORTED) {
                checked = false;
            }
        }
        List<String> diagrams = mermaid(snapshot);
        for (int i = 0; i < diagrams.size(); i++) {
            entries.add(new ExportAssetManifest.Entry(pageId, ExportAssetManifest.Reference.mermaid(i,
                    diagrams.get(i)), null, null, "Mermaid " + (i + 1), "text/vnd.mermaid", diagrams.get(i)
                    .getBytes(StandardCharsets.UTF_8).length, null, ExportAssetManifest.Status.STORED, null));
        }
        return checked;
    }

    private static void unreachable(SourceAccessException e) {
        if (e.reason() == SourceAccessException.Reason.UNAVAILABLE
                || e.reason() == SourceAccessException.Reason.RATE_LIMITED) {
            throw new SourceUnreachable(e.reason().name());
        }
    }

    /** Die Quelle ist nicht erreichbar oder drosselt: der Lauf hört auf. */
    private static final class SourceUnreachable extends RuntimeException {

        final String reason;

        SourceUnreachable(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }

    private interface Download {
        com.aresstack.spaceharvester.application.assets.Asset run();
    }

    private ExportAssetManifest.Entry store(JobContext context, String pageId, ExportAssetManifest.Reference reference,
                                            String attachmentId, String version, String title, String mediaType,
                                            Download download) {
        ExportAssetManifest.Status status;
        String reason;
        try {
            com.aresstack.spaceharvester.application.assets.Asset asset = download.run();
            context.processed(1);
            context.written(1);
            return new ExportAssetManifest.Entry(pageId, reference, asset.attachmentId(), asset.version(),
                    asset.title(), asset.mediaType(), asset.size(), asset.sha256(), ExportAssetManifest.Status.STORED,
                    null);
        } catch (AssetTooLargeException e) {
            status = ExportAssetManifest.Status.TOO_LARGE;
            reason = "asset-too-large";
        } catch (AssetStoreFullException e) {
            status = ExportAssetManifest.Status.UNAVAILABLE;
            reason = "asset-store-full";
        } catch (SourceAccessException e) {
            unreachable(e);
            status = switch (e.reason()) {
                case NOT_FOUND -> ExportAssetManifest.Status.MISSING;
                case ACCESS_DENIED -> ExportAssetManifest.Status.FORBIDDEN;
                default -> ExportAssetManifest.Status.UNAVAILABLE;
            };
            reason = "source-" + e.reason().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        } catch (AssetStoreException e) {
            status = ExportAssetManifest.Status.UNAVAILABLE;
            reason = "asset-store-error";
        }
        context.processed(1);
        context.failed(1);
        return new ExportAssetManifest.Entry(pageId, reference, attachmentId, version, title, mediaType, 0, null,
                status, reason);
    }

    /** Mermaid-Diagrammtexte aus dem Storage-Format der Seite, in ihrer Reihenfolge. */
    static List<String> mermaid(PageSnapshot snapshot) {
        String storage = storage(snapshot);
        List<String> diagrams = new ArrayList<>();
        if (storage == null) {
            return diagrams;
        }
        Matcher macro = MERMAID.matcher(storage);
        while (macro.find()) {
            Matcher body = PLAIN_TEXT.matcher(macro.group(1));
            if (body.find() && !body.group(1).isBlank()) {
                diagrams.add(body.group(1).strip());
            }
        }
        return diagrams;
    }

    private static String storage(PageSnapshot snapshot) {
        byte[] bytes = snapshot.payload().bytes();
        if (!"application/json".equals(snapshot.payload().mediaType())) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            JsonNode value = JSON.readTree(bytes).path("body").path("storage").path("value");
            return value.isString() ? value.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Adresse ohne Anmeldedaten im Host-Teil. */
    static String withoutUserInfo(String src) {
        try {
            URI uri = URI.create(src);
            if (uri.getRawUserInfo() == null) {
                return src;
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(),
                    uri.getFragment()).toString();
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            return src;
        }
    }
}
