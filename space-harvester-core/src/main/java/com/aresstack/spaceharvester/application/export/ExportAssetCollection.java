package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.AssetText;
import com.aresstack.spaceharvester.application.assets.AssetStoreFullException;
import com.aresstack.spaceharvester.application.assets.AssetTooLargeException;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.configuration.ExportSettings;
import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import com.aresstack.spaceharvester.application.diagrams.Diagrams;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
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
import java.util.function.Supplier;
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
 *
 * <p>Dokument-Anhänge (ADR 0023): Ist {@link ExportSettings#attachments()} an, legt derselbe Lauf auch die übrigen
 * Anhänge der Seite ab (PDF, Office, Mails …), über denselben Download, dieselbe Anmeldung und dieselbe Ablage, als
 * {@link ExportAssetManifest.Kind#DOCUMENT} im selben Manifest, und liest ihren Text einmal über die Textextraktion
 * der Ablage ({@link Assets#text}). Ein Anhang mit nicht zugelassenem Medientyp oder über der Größengrenze wird nicht
 * geladen und steht als {@link ExportAssetManifest.Status#EXCLUDED} mit Grund {@code media-type-excluded} bzw.
 * {@code too-large} im Manifest. Die Einstellung gilt je Lauf, wie sie gerade wirksam ist.
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
    private final Supplier<ExportSettings> settings;
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
        this(jobs, writer, sources, assets, sourceWide, signIns, () -> ExportSettings.DEFAULT);
    }

    /** @param settings die wirksame Einstellung für Dokument-Anhänge (ADR 0023), je Lauf gelesen */
    public ExportAssetCollection(Jobs jobs, ExportArtifactWriter writer, SourceQueries sources, Assets assets,
                                 BiPredicate<String, String> sourceWide, SignInRequests signIns,
                                 Supplier<ExportSettings> settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
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
            JobStatus status = jobs.submit(JobType.ASSETS, sourceId, spaceKey, null, owner,
                    Map.of(EXPORT_ID, exportId), new AssetTask(exportId));
            running.put(exportId, status.id());
            return Optional.of(status);
        } catch (JobCapacityExceededException | IllegalStateException e) {
            LOG.log(System.Logger.Level.INFO, "Bilder für Export {0} nicht eingeplant ({1})", exportId,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Wiederanlauf-Angabe: der Export, dessen Bilder der Auftrag beschafft ({@link JobStatus#parameters()}). */
    public static final String EXPORT_ID = "exportId";

    /**
     * Setzt den eigenen angehaltenen, gescheiterten oder abgebrochenen Bilderauftrag im selben Eintrag fort (ADR 0025):
     * Seiten, die er schon vollständig geprüft hat, übernimmt er ohne Abruf; schon abgelegte Dateien kommen aus dem
     * Speicher. Nach einem Neustart läuft derselbe Eintrag für denselben Export neu ({@link Jobs#rerun}).
     *
     * @throws UnknownJobException wenn es keinen eigenen Bilderauftrag mit dieser ID gibt
     * @throws JobNotResumableException wie {@link Jobs#resume}, oder wenn ein alter Auftrag seinen Export nicht nennt
     */
    public JobStatus resume(String jobId, String owner) {
        JobStatus status = jobs.status(jobId, owner);
        if (status.type() != JobType.ASSETS) {
            throw new UnknownJobException(jobId);
        }
        String exportId = status.parameter(EXPORT_ID);
        JobStatus resumed;
        if (jobs.resumable(jobId, owner) || !Jobs.resumableState(status.state())) {
            resumed = jobs.resume(jobId, owner);
        } else if (exportId == null) {
            throw new JobNotResumableException(jobId, "der Auftrag nennt seinen Export nicht");
        } else {
            resumed = jobs.rerun(jobId, owner, new AssetTask(exportId));
        }
        if (exportId != null) {
            running.put(exportId, jobId);
        }
        return resumed;
    }

    /**
     * Die Arbeit eines Bilderauftrags. Merkt sich über Anhalten und Fortsetzen hinweg, welche Seiten fertig geprüft
     * sind, mit ihren Manifest-Einträgen und Zählern, damit ein Fortsetzen sie nicht erneut abruft.
     */
    private final class AssetTask implements JobTask {

        private final String exportId;
        private final Map<String, CheckedPage> checked = new ConcurrentHashMap<>();

        AssetTask(String exportId) {
            this.exportId = exportId;
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> start(JobContext context) {
            running.put(exportId, context.jobId());
            return JobTask.blocking(job -> collect(job, exportId, checked)).start(context);
        }
    }

    /** Eine fertig geprüfte Seite: ihre Einträge und was sie zu den Zählern beitrug. */
    private record CheckedPage(List<ExportAssetManifest.Entry> entries, long discovered, long processed,
                               long written, long failed) {
    }

    /** Zählt mit, was ein Lauf meldet, und reicht alles an den Auftrag weiter. */
    private static final class Tally implements JobContext {

        private final JobContext context;
        long discovered;
        long processed;
        long written;
        long failed;

        Tally(JobContext context) {
            this.context = context;
        }

        @Override
        public String jobId() {
            return context.jobId();
        }

        @Override
        public String owner() {
            return context.owner();
        }

        @Override
        public void checkpoint() {
            context.checkpoint();
        }

        @Override
        public boolean cancelRequested() {
            return context.cancelRequested();
        }

        @Override
        public synchronized void discovered(long pages) {
            discovered += pages;
            context.discovered(pages);
        }

        @Override
        public synchronized void processed(long pages) {
            processed += pages;
            context.processed(pages);
        }

        @Override
        public synchronized void written(long pages) {
            written += pages;
            context.written(pages);
        }

        @Override
        public synchronized void failed(long pages) {
            failed += pages;
            context.failed(pages);
        }

        @Override
        public void phase(String component, String phase) {
            context.phase(component, phase);
        }

        @Override
        public void record(com.aresstack.spaceharvester.application.jobs.JobEvent.Draft event) {
            context.record(event);
        }

        @Override
        public void incomplete() {
            context.incomplete();
        }

        @Override
        public void pause(PauseReason reason) {
            context.pause(reason);
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
                // Dokumente und Bilder liegen gleich: je Anhang, Version und SHA-256.
                try {
                    return assets.stored(sourceId, entry.attachmentId(), entry.version(), entry.sha256());
                } catch (AssetStoreException e) {
                    return Optional.empty();
                }
            }

            @Override
            public Optional<String> text(ExportAssetManifest.Entry entry) {
                if (!entry.document() || !entry.stored()) {
                    return Optional.empty();
                }
                try {
                    return assets.storedText(entry.sha256()).map(AssetText::text);
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

    private void collect(JobContext job, String exportId, Map<String, CheckedPage> done) {
        Tally context = new Tally(job);
        List<ExportAssetManifest.Entry> entries = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        try {
            ConversionInput dataset = writer.dataset(exportId).orElseThrow(() ->
                    new ExportNotConvertibleException(exportId, "kein Datenbestand vorhanden"));
            context.phase(COMPONENT, PHASE);
            Run run = new Run(context, dataset.sourceId(), settings.get());
            boolean[] complete = {true};
            dataset.forEachPage(page -> {
                context.checkpoint();
                String pageId = page.snapshot().key().pageId();
                CheckedPage earlier = done.get(pageId);
                if (earlier != null) {
                    // Vor dem Anhalten schon vollständig geprüft: ohne Abruf übernehmen.
                    entries.addAll(earlier.entries());
                    context.discovered(earlier.discovered());
                    context.processed(earlier.processed());
                    context.written(earlier.written());
                    context.failed(earlier.failed());
                    checked.add(pageId);
                    return;
                }
                int first = entries.size();
                long discovered = context.discovered;
                long processed = context.processed;
                long written = context.written;
                long failed = context.failed;
                try {
                    if (collectPage(run, exportId, dataset, page.snapshot(), entries)) {
                        checked.add(pageId);
                        done.put(pageId, new CheckedPage(List.copyOf(entries.subList(first, entries.size())),
                                context.discovered - discovered, context.processed - processed,
                                context.written - written, context.failed - failed));
                    } else {
                        complete[0] = false;
                    }
                } catch (SourceUnreachable e) {
                    // Nicht erreichbar oder gedrosselt: die übrigen Seiten nicht mehr anfragen und nicht als Fehler
                    // zählen; der Auftrag hält an, ▶ macht später weiter.
                    LOG.log(System.Logger.Level.INFO, "Bilder für Export {0} angehalten: Quelle {1}", exportId,
                            e.reason);
                    context.pause(PauseReason.CONNECTION_LOST);
                }
            });
            List<ExportAssetManifest.Entry> result = merged(writer.assets(exportId), entries, checked);
            boolean allStored = result.stream().allMatch(ExportAssetManifest.Entry::present);
            writer.putAssets(exportId, new ExportAssetManifest(complete[0] && allStored, result));
            if (!(complete[0] && allStored)) {
                context.incomplete();
            }
            LOG.log(System.Logger.Level.INFO, "Bilder für Export {0}: {1} Referenzen, {2} fehlend", exportId,
                    result.size(), result.stream().filter(entry -> !entry.present()).count());
        } catch (JobCancelledException e) {
            // Angehalten oder abgebrochen: was bis hier abgelegt ist, steht im Manifest; vollständig ist es nicht.
            try {
                writer.putAssets(exportId, new ExportAssetManifest(false,
                        merged(writer.assets(exportId), entries, checked)));
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "Bisheriges Manifest für Export {0} nicht geschrieben ({1})",
                        exportId, failure.getClass().getName());
            }
            throw e;
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
            // Nur wirklich Abgelegtes (oder bewusst Überflüssiges) ersetzt einen gescheiterten Versuch; ein früherer
            // Ausschluss nach Einstellung (EXCLUDED) verdeckt nie einen fehlgeschlagenen Download.
            if (entry.present() && entry.status() != ExportAssetManifest.Status.EXCLUDED) {
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
                        if (run.settings.attachments()) {
                            byId.put(attachment.attachmentId(), document(run, list, pageId, attachment));
                        }
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

    /**
     * Ein Dokument-Anhang (ADR 0023): nach der Einstellung gefiltert, sonst wie ein Bild geladen und abgelegt; danach
     * einmal sein Text gelesen und abgelegt. Ein nicht lesbarer Text kostet nur den Text, nie das Original.
     */
    private ExportAssetManifest.Entry document(Run run, PageAttachmentList list, String pageId,
                                               PageAttachment attachment) {
        JobContext context = run.context;
        context.discovered(1);
        ExportAssetManifest.Reference reference = ExportAssetManifest.Reference.document(attachment.attachmentId(),
                attachment.title());
        String excluded = null;
        if (!run.settings.permits(attachment.mediaType())) {
            excluded = "media-type-excluded";
        } else if (attachment.fileSize() != null && attachment.fileSize() > run.settings.maxSize()) {
            excluded = "too-large";
        }
        if (excluded != null) {
            context.processed(1);
            return new ExportAssetManifest.Entry(pageId, reference, attachment.attachmentId(), attachment.version(),
                    attachment.title(), attachment.mediaType(), attachment.fileSize() == null ? 0
                    : attachment.fileSize(), null, ExportAssetManifest.Status.EXCLUDED, excluded);
        }
        ExportAssetManifest.Entry entry = store(run, pageId, reference, attachment.attachmentId(),
                attachment.version(), attachment.title(), attachment.mediaType(),
                () -> assets.content(list, attachment.attachmentId(), true).asset());
        if (!entry.stored()) {
            return entry;
        }
        if (entry.size() > run.settings.maxSize()) {
            // Die Quelle nannte keine oder eine falsche Größe: abgelegt ist es, exportiert wird es nicht.
            return new ExportAssetManifest.Entry(pageId, reference, entry.attachmentId(), entry.version(),
                    entry.title(), entry.mediaType(), entry.size(), null, ExportAssetManifest.Status.EXCLUDED,
                    "too-large");
        }
        try {
            assets.text(list, attachment.attachmentId());
        } catch (RuntimeException e) {
            // Nicht lesbar, keine Extraktion oder Zeit abgelaufen: das Original bleibt, der Text fehlt.
            LOG.log(System.Logger.Level.DEBUG, "Anhangstext der Quelle {0} nicht gelesen ({1})", list.sourceId(),
                    e.getClass().getName());
        }
        return entry;
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
        final ExportSettings settings;
        boolean declined;

        Run(JobContext context, String sourceId, ExportSettings settings) {
            this.context = context;
            this.sourceId = sourceId;
            this.settings = settings == null ? ExportSettings.DEFAULT : settings;
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
                    if (signIns.interactive()) {
                        // Mit Oberfläche zählen die übrigen Dateien nicht als Fehler: der Auftrag hält an, das
                        // Manifest behält das Abgelegte, und ▶ macht nach der Anmeldung weiter.
                        LOG.log(System.Logger.Level.INFO, "Bilder der Quelle {0} angehalten: Anmeldung fehlt",
                                sourceId);
                        context.pause(PauseReason.SIGN_IN_REQUIRED);
                    }
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
