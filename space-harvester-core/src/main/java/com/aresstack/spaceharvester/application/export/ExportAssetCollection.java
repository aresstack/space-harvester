package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.AssetStoreFullException;
import com.aresstack.spaceharvester.application.assets.AssetTooLargeException;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import com.aresstack.spaceharvester.application.diagrams.Diagrams;
import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.reading.ForeignContent;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.SignInRequiredException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.net.URI;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
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
 * Seite, deren Space inzwischen ein anderer ist, bleibt außen vor.
 *
 * <p>Inhalte anderer Seiten: Bindet die Seite Fremdes ein ({@link ForeignContent}, etwa {@code include} oder
 * {@code jira}) oder zeigt die Ansicht einen Anhang einer anderen Seite, hat die Quelle diese Bilder mit ihren eigenen
 * Zugangsdaten gerendert. Sie werden nur beschafft, wenn der Auftraggeber alles lesen darf, was diese Zugangsdaten
 * sehen ({@code sourceWide}); sonst stehen sie mit {@link ExportAssetManifest.Status#FORBIDDEN} und Grund
 * {@code foreign-content} im Manifest. Zähler: {@code discovered} gefundene Bilder,
 * {@code processed} geprüfte, {@code written} abgelegte (auch bereits vorhandene), {@code failed} fehlende.
 *
 * <p>Medien werden vor dem Download eingeordnet (ADR 0018): draw.io-Quelldaten zuerst; ist ein Diagramm gezeichnet,
 * wird seine Vorschau ({@code <Name>.png}, das Backup des Plugins) nicht geladen und steht als
 * {@link ExportAssetManifest.Status#REDUNDANT} im Manifest. Sind die Quelldaten nicht lesbar, ist die Vorschau der
 * Ersatz und wird geladen. Ein Bild ohne Quelldaten wird immer geladen.
 *
 * <p>Verlangt die Quelle für Dateien einen Einmalcode ({@link SignInRequiredException}), wartet der Auftrag an
 * derselben Stelle auf die Anmeldung ({@link SignInRequests}, Phase „Wartet auf Anmeldung“); schon abgelegte Bilder
 * bleiben. Danach lädt er dieselbe Datei neu und macht weiter. Ohne Anmeldung fehlen die übrigen Dateien des Laufs
 * mit {@link ExportAssetManifest.Status#SIGN_IN_REQUIRED}, ohne erneut zu warten; {@link #start} holt sie später nach,
 * schon abgelegte kommen dann aus dem Speicher.
 */
public class ExportAssetCollection {

    private static final System.Logger LOG = System.getLogger(ExportAssetCollection.class.getName());
    private static final String COMPONENT = "Assets";
    static final String PHASE = "Bilder beschaffen";
    /** Phase, solange der Auftrag auf den Einmalcode wartet; die Oberfläche zeigt „Wartet auf Anmeldung“. */
    public static final String SIGN_IN_PHASE = "Wartet auf Anmeldung";
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
    private final BiPredicate<String, String> sourceWide;
    private final SignInRequests signIns;
    /** Laufende Aufträge je Export; nach einem Neustart entscheidet allein das Manifest. */
    private final Map<String, String> running = new ConcurrentHashMap<>();

    /**
     * @param sourceWide ob ein Auftraggeber ({@link JobContext#owner()}) für eine Quelle alles lesen darf, was ihre
     *                   Zugangsdaten sehen ({@link com.aresstack.spaceharvester.application.access.AccessGuard#permitsSourceWide})
     */
    public ExportAssetCollection(Jobs jobs, ExportArtifactWriter writer, SourceQueries sources, Assets assets,
                                 BiPredicate<String, String> sourceWide) {
        this(jobs, writer, sources, assets, sourceWide, SignInRequests.none());
    }

    /** @param signIns wo Aufträge auf einen Einmalcode warten (ADR 0018) */
    public ExportAssetCollection(Jobs jobs, ExportArtifactWriter writer, SourceQueries sources, Assets assets,
                                 BiPredicate<String, String> sourceWide, SignInRequests signIns) {
        this.signIns = Objects.requireNonNull(signIns, "signIns");
        this.sourceWide = Objects.requireNonNull(sourceWide, "sourceWide");
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
        Optional<ExportAssetManifest> manifest = writer.assets(exportId);
        if (manifest.isPresent()) {
            return manifest.get().complete() ? ExportDatasetInfo.Images.COMPLETE
                    : ExportDatasetInfo.Images.INCOMPLETE;
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
                if (entry.stored() && entry.reference().kind() == ExportAssetManifest.Kind.MERMAID) {
                    return Optional.of(entry.reference().source().getBytes(StandardCharsets.UTF_8));
                }
                if (!entry.stored() || entry.attachmentId() == null) {
                    return Optional.empty();
                }
                try {
                    return assets.stored(sourceId, entry.attachmentId(), entry.version(), entry.sha256());
                } catch (AssetStoreException e) {
                    return Optional.empty();
                }
            }

            @Override
            public Optional<byte[]> rendition(ExportAssetManifest.Entry entry) {
                if (entry.rendition() == null || !entry.rendition().drawn() || entry.sha256() == null) {
                    return Optional.empty();
                }
                String renderer = entry.reference().kind() == ExportAssetManifest.Kind.MERMAID
                        ? Assets.MERMAID_RENDITION : Assets.DRAWIO_RENDITION;
                try {
                    return assets.stored(sourceId, Assets.renditionId(entry.sha256()), renderer,
                            entry.rendition().sha256());
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
            Run run = new Run(context, dataset.sourceId());
            boolean[] complete = {true};
            boolean[] stopped = {false};
            Set<String> checked = new HashSet<>();
            dataset.forEachPage(page -> {
                context.checkpoint();
                if (stopped[0]) {
                    return;
                }
                try {
                    if (collectPage(run, exportId, dataset, page.snapshot(), entries)) {
                        checked.add(page.snapshot().key().pageId());
                    } else {
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
            List<ExportAssetManifest.Entry> result = merged(writer.assets(exportId), entries, checked);
            boolean allStored = result.stream().allMatch(ExportAssetManifest.Entry::present);
            writer.putAssets(exportId, new ExportAssetManifest(complete[0] && allStored, result));
            LOG.log(System.Logger.Level.INFO, "Bilder für Export {0}: {1} Referenzen, {2} fehlend", exportId,
                    result.size(), result.stream().filter(entry -> !entry.present()).count());
        } finally {
            running.remove(exportId);
        }
    }

    /**
     * Ein erneuter Lauf ({@link ExportConversions#collectImages}) verliert nichts, was der vorige schon abgelegt hatte:
     * fehlt ein Eintrag jetzt, war er vorher aber vorhanden, gilt der vorige; Einträge von Seiten, die dieser Lauf
     * nicht vollständig prüfen konnte, bleiben.
     */
    static List<ExportAssetManifest.Entry> merged(Optional<ExportAssetManifest> previous,
                                                  List<ExportAssetManifest.Entry> entries, Set<String> checked) {
        if (previous.isEmpty()) {
            return entries;
        }
        Map<String, ExportAssetManifest.Entry> before = new java.util.LinkedHashMap<>();
        for (ExportAssetManifest.Entry entry : previous.get().assets()) {
            if (entry.present()) {
                before.putIfAbsent(key(entry), entry);
            }
        }
        List<ExportAssetManifest.Entry> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ExportAssetManifest.Entry entry : entries) {
            String key = key(entry);
            seen.add(key);
            ExportAssetManifest.Entry earlier = before.get(key);
            merged.add(!entry.present() && earlier != null ? earlier : entry);
        }
        for (Map.Entry<String, ExportAssetManifest.Entry> earlier : before.entrySet()) {
            if (!seen.contains(earlier.getKey()) && !checked.contains(earlier.getValue().pageId())) {
                merged.add(earlier.getValue());
            }
        }
        return merged;
    }

    private static String key(ExportAssetManifest.Entry entry) {
        ExportAssetManifest.Reference reference = entry.reference();
        return entry.pageId() + "\u0000" + reference.kind() + "\u0000" + reference.attachmentId() + "\u0000"
                + reference.src() + "\u0000" + reference.index();
    }

    /** @return ob die Seite vollständig geprüft werden konnte */
    private boolean collectPage(Run run, String exportId, ConversionInput dataset, PageSnapshot snapshot,
                                List<ExportAssetManifest.Entry> entries) {
        JobContext context = run.context;
        String sourceId = dataset.sourceId();
        String pageId = snapshot.key().pageId();
        boolean checked = true;
        Set<String> attachmentIds = new HashSet<>();
        Set<String> ownAttachments = new HashSet<>();
        Set<String> drawn = new HashSet<>();
        try {
            PageAttachmentList list = sources.attachments(sourceId, pageId);
            if (!dataset.spaceKey().equals(list.spaceKey())) {
                // Inzwischen in einem anderen Space: die Seite bleibt, wie sie exportiert wurde, ohne Bilder.
                return false;
            }
            if (!list.attachments().complete()) {
                // Die Quelle hat nicht alle Anhänge genannt: was fehlt, ist nicht einmal als Eintrag da.
                checked = false;
            }
            // Quelldaten vor Bildern: ob eine Vorschau gebraucht wird, steht erst nach dem Diagramm fest. Im Manifest
            // bleibt die Reihenfolge der Quelle.
            List<PageAttachment> ordered = list.attachments().attachments().stream()
                    .sorted(Comparator.comparing(a -> !Diagrams.isDrawio(a.mediaType(), a.title()))).toList();
            Map<String, ExportAssetManifest.Entry> byId = new HashMap<>();
            try {
                for (PageAttachment attachment : ordered) {
                    ownAttachments.add(attachment.attachmentId());
                    AssetKind kind = AssetKind.of(attachment.mediaType(), attachment.title());
                    if (kind == AssetKind.OTHER) {
                        continue;
                    }
                    attachmentIds.add(attachment.attachmentId());
                    context.discovered(1);
                    ExportAssetManifest.Reference reference = ExportAssetManifest.Reference.attachment(
                            attachment.attachmentId(), attachment.title());
                    if (preview(attachment.title(), drawn)) {
                        // Das Backup-PNG eines gezeichneten Diagramms: nicht laden, die Quelldaten tragen den Inhalt.
                        context.processed(1);
                        byId.put(attachment.attachmentId(), new ExportAssetManifest.Entry(pageId, reference,
                                attachment.attachmentId(), attachment.version(), attachment.title(),
                                attachment.mediaType(), 0, null, ExportAssetManifest.Status.REDUNDANT,
                                "drawio-preview"));
                        continue;
                    }
                    ExportAssetManifest.Entry entry = store(run, pageId, reference, attachment.attachmentId(),
                            attachment.version(), attachment.title(), attachment.mediaType(),
                            () -> assets.content(list, attachment.attachmentId(), true).asset());
                    if (entry.stored() && Diagrams.isDrawio(entry.mediaType(), attachment.title())) {
                        entry = assets.stored(sourceId, entry.attachmentId(), entry.version(), entry.sha256())
                                .map(mxfile -> rendered(sourceId, pageId, Assets.DRAWIO_RENDITION, mxfile))
                                .map(entry::withRendition).orElse(entry);
                        if (entry.rendition() != null && entry.rendition().drawn()) {
                            drawn.add(Diagrams.drawioName(attachment.title()));
                        }
                    }
                    byId.put(attachment.attachmentId(), entry);
                }
            } finally {
                for (PageAttachment attachment : list.attachments().attachments()) {
                    ExportAssetManifest.Entry entry = byId.get(attachment.attachmentId());
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
        } catch (SourceAccessException e) {
            unreachable(e);
            if (e.reason() != SourceAccessException.Reason.UNSUPPORTED) {
                checked = false;
            }
        }
        boolean foreign = ForeignContent.of(snapshot).present();
        boolean mayShowForeign = sourceWide.test(context.owner(), sourceId);
        try {
            List<HarvestSource.ViewImage> viewImages = readingView(exportId, sourceId, snapshot,
                    foreign && !mayShowForeign);
            for (HarvestSource.ViewImage image : viewImages) {
                if (image.attachmentId() != null && attachmentIds.contains(image.attachmentId())) {
                    continue;
                }
                if (image.attachmentId() == null && preview(Assets.viewFileName(image), drawn)) {
                    // Die Vorschau eines gezeichneten Diagramms, wie die Ansicht sie zeigt: ebenso überflüssig.
                    continue;
                }
                context.discovered(1);
                ExportAssetManifest.Reference reference = ExportAssetManifest.Reference.viewImage(withoutUserInfo(
                        image.src()));
                boolean otherPage = image.attachmentId() != null && !ownAttachments.contains(image.attachmentId());
                if ((foreign || otherPage) && !mayShowForeign) {
                    // Gerendert mit den Zugangsdaten der Quelle: nicht laden, nur als nicht freigegeben melden.
                    context.processed(1);
                    context.failed(1);
                    entries.add(new ExportAssetManifest.Entry(pageId, reference, null, null, image.title(),
                            image.mediaType(), 0, null, ExportAssetManifest.Status.FORBIDDEN, "foreign-content"));
                    continue;
                }
                entries.add(store(run, pageId, reference, null, null, image.title(), image.mediaType(),
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
            // Der Diagrammtext steht im Manifest selbst; der Hash gilt seinen UTF-8-Bytes.
            byte[] text = diagrams.get(i).getBytes(StandardCharsets.UTF_8);
            entries.add(new ExportAssetManifest.Entry(pageId, ExportAssetManifest.Reference.mermaid(i,
                    diagrams.get(i)), null, null, "Mermaid " + (i + 1), "text/vnd.mermaid", text.length,
                    Assets.sha256(text), ExportAssetManifest.Status.STORED, null,
                    rendered(sourceId, pageId, Assets.MERMAID_RENDITION, text)));
        }
        return checked;
    }

    /**
     * Holt die Lese-Ansicht der Seite einmal, legt sie im Datenbestand ab (#218) und liefert ihre Bilder. Rechte wie in
     * der Lese-Ansicht (#176): Bindet die Seite Fremdes ein und darf der Besitzer nicht die ganze Quelle lesen, bleibt
     * sie ohne Inhalt ({@code RESTRICTED}). Nur die Ansicht zur exportierten Version wird abgelegt.
     */
    private List<HarvestSource.ViewImage> readingView(String exportId, String sourceId, PageSnapshot snapshot,
                                                      boolean restricted) {
        String pageId = snapshot.key().pageId();
        String version = snapshot.metadata().version();
        HarvestSource.ReadingPage live;
        try {
            live = assets.readingPage(sourceId, pageId);
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.UNSUPPORTED) {
                putView(exportId, ExportReadingView.without(pageId, version, ExportReadingView.Status.UNSUPPORTED,
                        "no-reading-view"));
                // Eine Quelle kann Bilder der Ansicht kennen, ohne die Ansicht selbst zu liefern.
                return assets.viewImages(sourceId, pageId);
            }
            putView(exportId, ExportReadingView.without(pageId, version, ExportReadingView.Status.UNAVAILABLE,
                    "source-unavailable"));
            throw e;
        }
        ExportReadingView view;
        if (restricted) {
            view = ExportReadingView.without(pageId, version, ExportReadingView.Status.RESTRICTED, "foreign-content");
        } else if (live.version() == null || !live.version().equals(version)) {
            view = ExportReadingView.without(pageId, version, ExportReadingView.Status.UNAVAILABLE, "page-changed");
        } else {
            view = ExportReadingView.available(pageId, version, live.html(), live.baseUri(), live.text());
        }
        putView(exportId, view);
        return live.images();
    }

    /** Ablegen darf die Bilder nie verhindern; ohne Ansicht melden die Converter den eingebundenen Inhalt fehlend. */
    private void putView(String exportId, ExportReadingView view) {
        try {
            writer.putReadingView(exportId, view);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Lese-Ansicht für Export {0} nicht abgelegt ({1})", exportId,
                    e.getClass().getName());
        }
    }

    /** Zeichnet ein Diagramm einmal und legt die Grafik ab (#190); ein Fehler kostet nur die Grafik. */
    private ExportAssetManifest.Rendition rendered(String sourceId, String pageId, String renderer, byte[] source) {
        Assets.Rendered rendered;
        try {
            rendered = assets.rendition(sourceId, pageId, renderer, source);
        } catch (RuntimeException e) {
            return ExportAssetManifest.Rendition.notDrawn("Grafik nicht erzeugt (" + e.getClass().getSimpleName()
                    + ")");
        }
        return rendered.asset() == null ? ExportAssetManifest.Rendition.notDrawn(rendered.problem())
                : ExportAssetManifest.Rendition.drawn(rendered.asset().mediaType(), rendered.asset().size(),
                        rendered.asset().sha256());
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

    private ExportAssetManifest.Entry store(Run run, String pageId, ExportAssetManifest.Reference reference,
                                            String attachmentId, String version, String title, String mediaType,
                                            Download download) {
        JobContext context = run.context;
        ExportAssetManifest.Status status;
        String reason;
        try {
            com.aresstack.spaceharvester.application.assets.Asset asset = run.signedIn(download);
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
        } catch (SignInRequiredException e) {
            status = ExportAssetManifest.Status.SIGN_IN_REQUIRED;
            reason = "sign-in-required";
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

    /** Ob {@code filename} die Vorschau eines der gezeichneten Diagramme ist. */
    private static boolean preview(String filename, Set<String> drawn) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        for (String name : drawn) {
            if (Diagrams.isDrawioPreview(filename, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ein Lauf des Auftrags mit seiner Anmeldung (ADR 0018): verlangt die Quelle einen Einmalcode, wartet er einmal
     * auf {@link SignInRequests}; ohne Anmeldung wartet der Lauf nicht noch einmal.
     */
    private final class Run {

        final JobContext context;
        final String sourceId;
        boolean declined;

        Run(JobContext context, String sourceId) {
            this.context = context;
            this.sourceId = sourceId;
        }

        com.aresstack.spaceharvester.application.assets.Asset signedIn(Download download) {
            Instant started = Instant.now();
            try {
                return download.run();
            } catch (SignInRequiredException e) {
                if (declined) {
                    throw e;
                }
                context.phase(COMPONENT, SIGN_IN_PHASE);
                SignInRequests.Outcome outcome;
                try {
                    outcome = signIns.await(sourceId, context.jobId(), e.challenge(), started, context::checkpoint);
                } finally {
                    context.phase(COMPONENT, PHASE);
                }
                if (outcome != SignInRequests.Outcome.UNLOCKED) {
                    declined = true;
                    LOG.log(System.Logger.Level.INFO, "Bilder der Quelle {0} ohne Anmeldung weiter", sourceId);
                    throw e;
                }
                // Dieselbe Datei noch einmal, jetzt mit der freigeschalteten Sitzung.
                return download.run();
            }
        }
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
