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
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
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
import java.util.concurrent.CompletableFuture;
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
 * <p>Dokument-Anhänge (ADR 0023): Ist {@link ExportSettings#attachments()} an, legt ein zweiter Auftrag
 * {@link JobType#ATTACHMENTS} („Anhänge beschaffen“) die übrigen Anhänge der Seite ab (PDF, Office, Mails …), mit
 * demselben Code, über denselben Download, dieselbe Anmeldung und dieselbe Ablage, als
 * {@link ExportAssetManifest.Kind#DOCUMENT} im selben Manifest, und liest ihren Text einmal über die Textextraktion
 * der Ablage ({@link Assets#text}). Jeder der beiden Aufträge schreibt nur seine eigenen Einträge und lässt die des
 * anderen stehen; beide lassen sich anhalten und im selben Eintrag fortsetzen. Ein Anhang mit nicht zugelassenem Medientyp oder über der Größengrenze wird nicht
 * geladen und steht als {@link ExportAssetManifest.Status#EXCLUDED} mit Grund {@code media-type-excluded} bzw.
 * {@code too-large} im Manifest. Die Einstellung gilt je Lauf, wie sie gerade wirksam ist.
 */
public class ExportAssetCollection {

    private static final System.Logger LOG = System.getLogger(ExportAssetCollection.class.getName());
    private static final String COMPONENT = "Assets";
    static final String PHASE = "Bilder beschaffen";
    static final String DOCUMENTS_PHASE = "Anhänge beschaffen";
    /** Phase, solange der Auftrag auf den Einmalcode wartet; die Oberfläche zeigt „Wartet auf Anmeldung“. */
    public static final String SIGN_IN_PHASE = "Wartet auf Anmeldung";
    /** Phase der Vorbereitung: Verweise zählen, noch nichts laden. */
    public static final String PREPARE_PHASE = "Verweise zählen";
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
    /** Laufende Aufträge je Art und Export; nach einem Neustart entscheidet allein das Manifest. */
    private final Map<String, String> running = new ConcurrentHashMap<>();
    /** Je Export ein Schloss: beide Aufträge lesen, mischen und schreiben dasselbe Manifest. */
    private final Map<String, Object> manifests = new ConcurrentHashMap<>();

    /**
     * Was ein Auftrag beschafft: Bilder (mit Lese-Ansicht und Mermaid) oder Dokument-Anhänge. Beide teilen jeden
     * Schritt; nur die Auswahl der Einträge unterscheidet sich.
     */
    enum Scope {
        MEDIA(JobType.ASSETS, PHASE, "Bilder"),
        DOCUMENTS(JobType.ATTACHMENTS, DOCUMENTS_PHASE, "Anhänge");

        final JobType type;
        final String phase;
        final String label;

        Scope(JobType type, String phase, String label) {
            this.type = type;
            this.phase = phase;
            this.label = label;
        }

        static Scope of(JobType type) {
            for (Scope scope : values()) {
                if (scope.type == type) {
                    return scope;
                }
            }
            return null;
        }

        String key(String exportId) {
            return name() + "\u0000" + exportId;
        }
    }

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
     * Legt Bilder und, bei eingeschalteten Anhängen, „Anhänge beschaffen“ schon beim Start eines Exports an; für
     * {@link Exports#onSubmitted}. Sie warten ({@link com.aresstack.spaceharvester.application.jobs.JobStage#AWAITING}),
     * ohne einen Platz zu belegen, bis {@link #start} sie beim Veröffentlichen freigibt (Angelo 11.11.2026). Löscht der
     * Benutzer sie vorher, entsteht still nur der Text. Scheitert, endet oder hält der Text an, warten sie weiter, bis
     * er veröffentlicht ist; wird der Export gelöscht, verschwinden sie mit.
     */
    public void announce(JobStatus export) {
        String exportId = export.id();
        ExportSettings current = settings.get();
        boolean attachments = current != null && current.attachments();
        CompletableFuture<Boolean> together = new CompletableFuture<>();
        try {
            if (!hold(Scope.MEDIA, export, new AssetTask(exportId, Scope.MEDIA, together))) {
                // Nimmt die Auftragsverwaltung nichts an: beim Veröffentlichen wie früher neu einplanen.
                unannounced.add(exportId);
                return;
            }
            boolean separate = !attachments || hold(Scope.DOCUMENTS, export, new AssetTask(exportId,
                    Scope.DOCUMENTS));
            together.complete(!separate);
        } finally {
            together.complete(false);
        }
    }

    /** Exporte, deren Folgeaufträge beim Start nicht angelegt werden konnten. */
    private final Set<String> unannounced = ConcurrentHashMap.newKeySet();

    private boolean hold(Scope scope, JobStatus export, AssetTask task) {
        try {
            jobs.submitAwaiting(scope.type, export.sourceId(), export.partitionKey(), null, export.owner(),
                    Map.of(EXPORT_ID, export.id()), task, export.id());
            return true;
        } catch (JobCapacityExceededException | IllegalStateException e) {
            LOG.log(System.Logger.Level.INFO, "{0} für Export {1} nicht vorab angelegt ({2})", scope.label,
                    export.id(), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Plant das Beschaffen der Bilder für einen veröffentlichten Export ein, bei eingeschalteten Anhängen
     * ({@link ExportSettings#attachments()}) daneben einen eigenen Auftrag „Anhänge beschaffen“; für
     * {@link Exports#onPublished}. Nimmt die Auftragsverwaltung nichts mehr an, bleibt der Export ohne Bilder
     * ({@link ExportDatasetInfo.Images#NOT_COLLECTED}) bis zum nächsten {@link #start}.
     *
     * @return der Bilderauftrag oder leer, wenn keiner eingeplant wurde
     */
    public Optional<JobStatus> start(String exportId, String owner) {
        Optional<ConversionInput> dataset = writer.dataset(exportId);
        if (dataset.isEmpty()) {
            return Optional.empty();
        }
        if (announced(exportId, owner)) {
            // Beim Start angelegt: freigeben, was noch da ist; was der Benutzer gelöscht hat, bleibt weg.
            Optional<JobStatus> media = Optional.empty();
            for (Scope scope : Scope.values()) {
                Optional<JobStatus> earlier = jobs.list(owner, scope.type).stream()
                        .filter(job -> exportId.equals(job.parameter(EXPORT_ID))).findFirst();
                if (earlier.isEmpty()) {
                    continue;
                }
                // Ein fehlender Anhängeauftrag heißt hier gelöscht, nie „Bilder holen die Anhänge mit“.
                Optional<JobStatus> started = submit(scope, dataset.get(), exportId, owner, new AssetTask(exportId,
                        scope, CompletableFuture.completedFuture(false)));
                if (scope == Scope.MEDIA) {
                    media = started;
                }
            }
            return media;
        }
        ExportSettings current = settings.get();
        boolean attachments = current != null && current.attachments();
        // Nimmt die Auftragsverwaltung keinen zweiten Folgeauftrag an (etwa ein Platz, keine Warteschlange), beschafft
        // der Bilderauftrag die Anhänge mit, wie vor der Trennung; er wartet beim Start auf diese Entscheidung.
        CompletableFuture<Boolean> together = new CompletableFuture<>();
        try {
            Optional<JobStatus> media = submit(Scope.MEDIA, dataset.get(), exportId, owner,
                    new AssetTask(exportId, Scope.MEDIA, together));
            boolean separate = !attachments || media.isEmpty() || submit(Scope.DOCUMENTS, dataset.get(), exportId,
                    owner, new AssetTask(exportId, Scope.DOCUMENTS)).isPresent();
            together.complete(!separate);
            return media;
        } finally {
            together.complete(false);
        }
    }

    /**
     * Plant den Auftrag dieser Art ein. Gibt es für den Export schon einen (etwa nach „Nur Fehler neu versuchen“),
     * läuft derselbe Eintrag erneut, statt einen zweiten anzulegen; ein noch offener bleibt, wie er ist.
     */
    private Optional<JobStatus> submit(Scope scope, ConversionInput dataset, String exportId, String owner,
                                       AssetTask task) {
        try {
            Optional<JobStatus> earlier = jobs.list(owner, scope.type).stream()
                    .filter(job -> exportId.equals(job.parameter(EXPORT_ID))).findFirst();
            if (earlier.isPresent() && !earlier.get().state().terminal()) {
                if (jobs.releaseAwaiting(earlier.get().id())) {
                    running.put(scope.key(exportId), earlier.get().id());
                    return Optional.of(jobs.status(earlier.get().id(), owner));
                }
                return earlier;
            }
            JobStatus status = earlier.isPresent() ? jobs.rerun(earlier.get().id(), owner, task)
                    : jobs.submit(scope.type, dataset.sourceId(), dataset.spaceKey(), null, owner,
                    Map.of(EXPORT_ID, exportId), task);
            running.put(scope.key(exportId), status.id());
            return Optional.of(status);
        } catch (JobCapacityExceededException | JobNotResumableException | IllegalStateException e) {
            LOG.log(System.Logger.Level.INFO, "{0} für Export {1} nicht eingeplant ({2})", scope.label, exportId,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Ob die Folgeaufträge dieses Exports schon beim Start angelegt wurden ({@link #announce}). */
    private boolean announced(String exportId, String owner) {
        if (unannounced.remove(exportId)) {
            return false;
        }
        try {
            return Exports.ANNOUNCED.equals(jobs.status(exportId, owner).parameter(Exports.FOLLOW_UPS));
        } catch (UnknownJobException e) {
            return false;
        }
    }

    /** Wiederanlauf-Angabe: der Export, dessen Bilder der Auftrag beschafft ({@link JobStatus#parameters()}). */
    public static final String EXPORT_ID = "exportId";

    /**
     * Setzt den eigenen angehaltenen, gescheiterten, abgebrochenen oder unvollständig fertigen Bilder- oder
     * Anhängeauftrag im selben Eintrag fort (ADR 0025, „Nur Fehler neu versuchen“):
     * Seiten, die er schon vollständig geprüft hat, übernimmt er ohne Abruf; schon abgelegte Dateien kommen aus dem
     * Speicher. Nach einem Neustart läuft derselbe Eintrag für denselben Export neu ({@link Jobs#rerun}).
     *
     * @throws UnknownJobException wenn es keinen eigenen Bilder- oder Anhängeauftrag mit dieser ID gibt
     * @throws JobNotResumableException wie {@link Jobs#resume}, oder wenn ein alter Auftrag seinen Export nicht nennt
     */
    public JobStatus resume(String jobId, String owner) {
        return resume(jobId, owner, false);
    }

    /**
     * „Nur Fehler neu versuchen“ für den eigenen Bilder- oder Anhängeauftrag: wie {@link #resume}, aber Seiten mit
     * gescheiterten Dateien gelten nicht mehr als fertig geprüft und werden erneut besucht; schon abgelegte Dateien
     * kommen weiter aus dem Speicher.
     *
     * @throws UnknownJobException      wenn es keinen eigenen Bilder- oder Anhängeauftrag mit dieser ID gibt
     * @throws JobNotResumableException wie {@link #resume}
     */
    public JobStatus retryFailed(String jobId, String owner) {
        return resume(jobId, owner, true);
    }

    private JobStatus resume(String jobId, String owner, boolean failedAgain) {
        JobStatus status = jobs.status(jobId, owner);
        Scope scope = Scope.of(status.type());
        if (scope == null) {
            throw new UnknownJobException(jobId);
        }
        String exportId = status.parameter(EXPORT_ID);
        JobStatus resumed;
        if (jobs.resumable(jobId, owner) || !status.state().terminal()) {
            resumed = jobs.resume(jobId, owner, failedAgain ? AssetTask::withoutFailedPages : null);
        } else if (exportId == null) {
            throw new JobNotResumableException(jobId, "der Auftrag nennt seinen Export nicht");
        } else {
            resumed = jobs.rerun(jobId, owner, new AssetTask(exportId, scope,
                    CompletableFuture.completedFuture(scope == Scope.MEDIA && documentsWithImages(exportId, owner)),
                    FORCE_REFRESH.equals(status.parameter(RERUN))));
        }
        if (exportId != null) {
            running.put(scope.key(exportId), jobId);
        }
        return resumed;
    }

    /** Wiederanlauf-Angabe: der Lauf ist „Neu erzwingen“ ({@link #FORCE_REFRESH}); fehlt für einen gewöhnlichen. */
    static final String RERUN = "rerun";
    static final String FORCE_REFRESH = "force-refresh";

    /**
     * „Neu erzwingen“ (ADR 0025): Der eigene beendete Bilder- oder Anhängeauftrag läuft im selben Eintrag erneut und
     * lädt jede Datei am Speicher vorbei neu von der Quelle; der abgelegte Stand einer Datei gilt, bis ihr neuer
     * Download ihn ersetzt, und bleibt, wenn er scheitert. Die Art des Laufs steht in den Angaben, damit Fortsetzen
     * nach einem Neustart weiter neu lädt.
     *
     * @throws UnknownJobException      wenn es keinen eigenen Bilder- oder Anhängeauftrag mit dieser ID gibt
     * @throws JobNotResumableException wenn er noch wartet oder läuft, oder ein alter Auftrag seinen Export nicht nennt
     */
    public JobStatus forceRefresh(String jobId, String owner) {
        JobStatus status = jobs.status(jobId, owner);
        Scope scope = Scope.of(status.type());
        if (scope == null) {
            throw new UnknownJobException(jobId);
        }
        String exportId = status.parameter(EXPORT_ID);
        if (exportId == null) {
            throw new JobNotResumableException(jobId, "der Auftrag nennt seinen Export nicht");
        }
        Map<String, String> parameters = new java.util.HashMap<>(status.parameters());
        parameters.put(RERUN, FORCE_REFRESH);
        JobStatus rerun = jobs.rerun(jobId, owner, parameters, new AssetTask(exportId, scope,
                CompletableFuture.completedFuture(scope == Scope.MEDIA && documentsWithImages(exportId, owner)),
                true));
        running.put(scope.key(exportId), jobId);
        return rerun;
    }

    /**
     * Ob ein Bilderauftrag nach einem Neustart die Anhänge mitbeschafft: bei eingeschalteten Anhängen, wenn es für den
     * Export keinen eigenen Auftrag „Anhänge beschaffen“ gibt.
     */
    private boolean documentsWithImages(String exportId, String owner) {
        ExportSettings current = settings.get();
        return current != null && current.attachments() && jobs.list(owner, JobType.ATTACHMENTS).stream()
                .noneMatch(job -> exportId.equals(job.parameter(EXPORT_ID)));
    }

    /**
     * Die Arbeit eines Bilderauftrags. Merkt sich über Anhalten und Fortsetzen hinweg, welche Seiten fertig geprüft
     * sind, mit ihren Manifest-Einträgen und Zählern, damit ein Fortsetzen sie nicht erneut abruft.
     */
    private final class AssetTask implements JobTask {

        private final String exportId;
        private final Scope scope;
        /** Nur Bilder: ob dieser Lauf auch die Anhänge beschafft, weil sie keinen eigenen Auftrag bekamen. */
        private final CompletableFuture<Boolean> withDocuments;
        /** „Neu erzwingen“: alle Dateien am Speicher vorbei neu laden. */
        private final boolean fresh;
        private final Map<String, CheckedPage> checked = new ConcurrentHashMap<>();

        AssetTask(String exportId, Scope scope) {
            this(exportId, scope, CompletableFuture.completedFuture(false));
        }

        AssetTask(String exportId, Scope scope, CompletableFuture<Boolean> withDocuments) {
            this(exportId, scope, withDocuments, false);
        }

        AssetTask(String exportId, Scope scope, CompletableFuture<Boolean> withDocuments, boolean fresh) {
            this.exportId = exportId;
            this.scope = scope;
            this.withDocuments = withDocuments;
            this.fresh = fresh;
        }

        /** Vergisst die geprüften Seiten, auf denen etwas scheiterte, damit der nächste Lauf sie erneut besucht. */
        static JobTask withoutFailedPages(JobTask task) {
            if (task instanceof AssetTask asset) {
                asset.checked.values().removeIf(page -> page.failed() > 0);
            }
            return task;
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> start(JobContext context) {
            running.put(scope.key(exportId), context.jobId());
            return JobTask.blocking(job -> collect(job, scope, new Share(scope == Scope.MEDIA,
                    scope == Scope.DOCUMENTS || withDocuments.join()), exportId, checked, fresh)).start(context);
        }
    }

    /** Welche Einträge ein Lauf beschafft und schreibt: Bilder, Dokumente oder beides. */
    private record Share(boolean images, boolean documents) {

        boolean owns(ExportAssetManifest.Entry entry) {
            return entry.document() ? documents : images;
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
        public void awaitDownloadSlot() {
            context.awaitDownloadSlot();
        }

        @Override
        public void pause(PauseReason reason) {
            context.pause(reason);
        }
    }

    /** Stand der Bilder eines Exports. */
    public ExportDatasetInfo.Images images(String exportId) {
        String key = Scope.MEDIA.key(exportId);
        String jobId = running.get(key);
        if (jobId != null) {
            try {
                if (!jobs.status(jobId).state().terminal()) {
                    return ExportDatasetInfo.Images.COLLECTING;
                }
            } catch (UnknownJobException e) {
                running.remove(key, jobId);
            }
        }
        Optional<ExportAssetManifest> manifest = writer.assets(exportId);
        if (manifest.isPresent()) {
            return manifest.get().imagesComplete() ? ExportDatasetInfo.Images.COMPLETE
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

    private void collect(JobContext job, Scope scope, Share share, String exportId, Map<String, CheckedPage> done,
                         boolean fresh) {
        Tally context = new Tally(job);
        List<ExportAssetManifest.Entry> entries = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        Run run = null;
        try {
            ConversionInput dataset = writer.dataset(exportId).orElseThrow(() ->
                    new ExportNotConvertibleException(exportId, "kein Datenbestand vorhanden"));
            context.phase(COMPONENT, scope.phase);
            run = new Run(context, scope, share, dataset.sourceId(), settings.get(), fresh);
            Run current = run;
            boolean[] complete = {true};
            // Erst vorbereiten: alle Verweise aller Seiten zählen, ohne zu laden; danach steht die Gesamtzahl fest
            // (Angelo 11.11.2026). Vor dem Anhalten schon vollständig geladene Seiten zählen ohne Abruf mit.
            context.phase(COMPONENT, PREPARE_PHASE);
            // Nur Kennungen und Verweise je Seite, nie die Seiteninhalte: die liest der Ladedurchgang erneut einzeln.
            Map<String, PagePlan> plans = new java.util.LinkedHashMap<>();
            // Anhangskatalog über die Suche der Quelle statt zweier Abrufe je Seite (Angelo 11.11.2026).
            Map<String, PageAttachmentList> catalog = Map.of();
            try {
                catalog = catalog(context, dataset, done.keySet());
            } catch (SourceUnreachable e) {
                LOG.log(System.Logger.Level.INFO, "{0} für Export {1} angehalten: Quelle {2}", scope.label,
                        exportId, e.reason);
                context.pause(PauseReason.CONNECTION_LOST);
            }
            Map<String, PageAttachmentList> searched = catalog;
            dataset.forEachPage(page -> {
                context.checkpoint();
                String pageId = page.snapshot().key().pageId();
                CheckedPage earlier = done.get(pageId);
                if (earlier != null) {
                    entries.addAll(earlier.entries());
                    context.discovered(earlier.discovered());
                    context.processed(earlier.processed());
                    context.written(earlier.written());
                    context.failed(earlier.failed());
                    checked.add(pageId);
                    return;
                }
                try {
                    PagePlan plan = plan(current, exportId, dataset, page.snapshot(), searched.get(pageId));
                    if (plan == null) {
                        complete[0] = false;
                    } else {
                        plans.put(pageId, plan);
                    }
                } catch (SourceUnreachable e) {
                    // Nicht erreichbar oder gedrosselt: die übrigen Seiten nicht mehr anfragen und nicht als Fehler
                    // zählen; der Auftrag hält an, ▶ macht später weiter.
                    LOG.log(System.Logger.Level.INFO, "{0} für Export {1} angehalten: Quelle {2}", scope.label,
                            exportId, e.reason);
                    context.pause(PauseReason.CONNECTION_LOST);
                }
            });
            // Vorbereitet: wartet auf einen Download-Platz (jobs.parallel-downloads), dann wird geladen.
            context.awaitDownloadSlot();
            context.phase(COMPONENT, scope.phase);
            dataset.forEachPage(page -> {
                String pageId = page.snapshot().key().pageId();
                PagePlan plan = plans.get(pageId);
                if (plan == null) {
                    return;
                }
                context.checkpoint();
                int first = entries.size();
                long processed = context.processed;
                long written = context.written;
                long failed = context.failed;
                try {
                    Loaded loaded = load(current, exportId, dataset, plan, page.snapshot(), entries);
                    if (loaded.checked()) {
                        checked.add(pageId);
                        done.put(pageId, new CheckedPage(List.copyOf(entries.subList(first, entries.size())),
                                loaded.discovered(), context.processed - processed, context.written - written,
                                context.failed - failed));
                    } else {
                        complete[0] = false;
                    }
                } catch (SourceUnreachable e) {
                    LOG.log(System.Logger.Level.INFO, "{0} für Export {1} angehalten: Quelle {2}", scope.label,
                            exportId, e.reason);
                    context.pause(PauseReason.CONNECTION_LOST);
                }
            });
            List<ExportAssetManifest.Entry> own = write(share, exportId, entries, checked, complete[0], run.settings);
            if (!(complete[0] && own.stream().allMatch(ExportAssetManifest.Entry::present))) {
                context.incomplete();
            }
            LOG.log(System.Logger.Level.INFO, "{0} für Export {1}: {2} Referenzen, {3} fehlend", scope.label,
                    exportId, own.size(), own.stream().filter(entry -> !entry.present()).count());
        } catch (JobCancelledException e) {
            // Angehalten oder abgebrochen: was bis hier abgelegt ist, steht im Manifest; vollständig ist es nicht.
            try {
                write(share, exportId, entries, checked, false, run == null ? settings.get() : run.settings);
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "Bisheriges Manifest für Export {0} nicht geschrieben ({1})",
                        exportId, failure.getClass().getName());
            }
            throw e;
        } finally {
            running.remove(scope.key(exportId));
        }
    }

    /**
     * Mischt die Einträge eines Laufs ins Manifest, unter dem Schloss des Exports: die eigenen Arten wie
     * {@link #merged}, die Einträge und die Vollständigkeit der anderen Art bleiben, wie der andere Auftrag sie
     * geschrieben hat.
     * Hat er noch nie geschrieben, gelten Dokumente bei ausgeschalteten Anhängen als vollständig, Bilder nie.
     *
     * @return die Einträge der eigenen Art, wie sie jetzt im Manifest stehen
     */
    private List<ExportAssetManifest.Entry> write(Share share, String exportId, List<ExportAssetManifest.Entry> entries,
                                                  Set<String> checked, boolean pagesComplete,
                                                  ExportSettings current) {
        synchronized (manifests.computeIfAbsent(exportId, id -> new Object())) {
            Optional<ExportAssetManifest> previous = writer.assets(exportId);
            List<ExportAssetManifest.Entry> own = merged(previous.map(manifest -> new ExportAssetManifest(false,
                    manifest.assets().stream().filter(share::owns).toList())), entries, checked);
            List<ExportAssetManifest.Entry> result = new ArrayList<>(own);
            previous.ifPresent(manifest -> manifest.assets().stream().filter(entry -> !share.owns(entry))
                    .forEach(result::add));
            boolean images = share.images() ? pagesComplete && own.stream().filter(entry -> !entry.document())
                    .allMatch(ExportAssetManifest.Entry::present)
                    : previous.map(ExportAssetManifest::imagesComplete).orElse(false);
            boolean documents = share.documents() ? pagesComplete && own.stream()
                    .filter(ExportAssetManifest.Entry::document).allMatch(ExportAssetManifest.Entry::present)
                    : previous.map(ExportAssetManifest::documentsComplete)
                            .orElse(current == null || !current.attachments());
            writer.putAssets(exportId, new ExportAssetManifest(images, documents, result));
            return own;
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

    /**
     * Was auf einer Seite zu laden ist, aus der Vorbereitung (Angelo 11.11.2026): die Anhangsliste ist abgefragt und
     * gezählt, geladen ist noch nichts. Die Lese-Ansicht holt erst der Ladedurchgang (Angelo 11.11.2026): die
     * Vorbereitung ruft je Seite nichts ab.
     *
     * @param list      die Anhangsliste oder {@code null}, wenn sie nicht abrufbar war
     * @param catalog   ob die Liste aus dem Anhangskatalog (der Suche) stammt
     * @param steps     die Verweise in Ladereihenfolge (Quelldaten von Diagrammen vor Bildern)
     * @param order     Anhangs-IDs in der Reihenfolge der Quelle, für das Manifest
     * @param checked   ob die Seite vollständig geprüft werden konnte
     */
    private record PagePlan(String pageId, PageAttachmentList list, boolean catalog, List<Step> steps,
                            List<String> order, boolean checked, long discovered) {
    }

    /** Seiten je Katalogabfrage; die Quelle teilt sie selbst weiter auf (Confluence: 50 Seiten je CQL). */
    static final int CATALOG_PAGES = 500;

    /**
     * Die Anhangslisten aller noch nicht geprüften Seiten über die Suche der Quelle ({@link SourceQueries#attachmentsOf}),
     * in Abfragen zu {@value #CATALOG_PAGES} Seiten, beschränkt auf den Space des Exports: eine inzwischen verschobene
     * Seite hat eine leere Liste und bekommt so keine Anhänge. Kann die Quelle das nicht oder scheitert eine Abfrage
     * (etwa zu viele Treffer), fragt die Vorbereitung die betroffenen Seiten einzeln wie bisher.
     *
     * @throws SourceUnreachable wenn die Quelle nicht erreichbar ist oder drosselt
     */
    private Map<String, PageAttachmentList> catalog(JobContext context, ConversionInput dataset, Set<String> done) {
        List<String> pageIds = new ArrayList<>();
        for (PageHeader header : dataset.headers()) {
            String pageId = header.key().pageId();
            if (!done.contains(pageId)) {
                pageIds.add(pageId);
            }
        }
        Map<String, PageAttachmentList> catalog = new HashMap<>();
        for (int from = 0; from < pageIds.size(); from += CATALOG_PAGES) {
            context.checkpoint();
            List<String> chunk = pageIds.subList(from, Math.min(pageIds.size(), from + CATALOG_PAGES));
            try {
                catalog.putAll(sources.attachmentsOf(dataset.sourceId(), dataset.spaceKey(), chunk));
            } catch (SourceAccessException e) {
                unreachable(e);
                if (e.reason() == SourceAccessException.Reason.UNSUPPORTED) {
                    return catalog;
                }
                LOG.log(System.Logger.Level.INFO, "Anhangskatalog über die Suche nicht lieferbar ({0}); einzeln",
                        e.reason());
            }
        }
        return catalog;
    }

    /** Ein Verweis einer Seite: ein Bild- oder Dokument-Anhang, oder ein Bild der Lese-Ansicht. */
    private record Step(PageAttachment attachment, boolean document, HarvestSource.ViewImage image,
                        boolean forbidden) {
    }

    /**
     * Vorbereitung einer Seite: nimmt die Anhangsliste (aus dem Katalog oder einzeln) und zählt jeden Anhang
     * ({@link JobContext#discovered}); lädt keine Datei und holt keine Lese-Ansicht.
     */
    private PagePlan plan(Run run, String exportId, ConversionInput dataset, PageSnapshot snapshot,
                          PageAttachmentList searched) {
        JobContext context = run.context;
        String sourceId = dataset.sourceId();
        String pageId = snapshot.key().pageId();
        boolean checked = true;
        long discovered = 0;
        List<Step> steps = new ArrayList<>();
        List<String> order = new ArrayList<>();
        PageAttachmentList list = null;
        try {
            // Aus dem Katalog schon auf den Space beschränkt; sonst einzeln mit dem aktuellen Space der Seite.
            list = searched != null ? searched : sources.attachments(sourceId, pageId);
            if (!dataset.spaceKey().equals(list.spaceKey())) {
                // Inzwischen in einem anderen Space: die Seite bleibt, wie sie exportiert wurde, ohne Bilder.
                return null;
            }
            if (!list.attachments().complete()) {
                // Die Quelle hat nicht alle Anhänge genannt: was fehlt, ist nicht einmal als Eintrag da.
                checked = false;
            }
            // Quelldaten vor Bildern: ob eine Vorschau gebraucht wird, steht erst nach dem Diagramm fest. Im Manifest
            // bleibt die Reihenfolge der Quelle.
            List<PageAttachment> ordered = list.attachments().attachments().stream()
                    .sorted(Comparator.comparing(a -> !Diagrams.isDrawio(a.mediaType(), a.title()))).toList();
            for (PageAttachment attachment : list.attachments().attachments()) {
                order.add(attachment.attachmentId());
            }
            for (PageAttachment attachment : ordered) {
                AssetKind kind = AssetKind.of(attachment.mediaType(), attachment.title());
                if (kind == AssetKind.OTHER) {
                    if (run.share.documents() && run.settings.attachments()) {
                        steps.add(new Step(attachment, true, null, false));
                        discovered++;
                    }
                    continue;
                }
                if (!run.share.images()) {
                    continue;
                }
                steps.add(new Step(attachment, false, null, false));
                discovered++;
            }
        } catch (SourceAccessException e) {
            unreachable(e);
            if (e.reason() != SourceAccessException.Reason.UNSUPPORTED) {
                checked = false;
            }
        }
        context.discovered(discovered);
        return new PagePlan(pageId, list, searched != null, steps, order, checked, discovered);
    }

    /**
     * Ergebnis des Ladens einer Seite.
     *
     * @param checked     ob die Seite vollständig geprüft werden konnte
     * @param discovered  alle Verweise der Seite: die gezählten Anhänge und die Bilder der Lese-Ansicht
     */
    private record Loaded(boolean checked, long discovered) {
    }

    /**
     * Lädt, was die Vorbereitung für eine Seite gefunden hat, und schreibt die Einträge in {@code entries}: Anhänge in
     * der Reihenfolge der Quelle, dann Bilder der Lese-Ansicht, dann Mermaid. Die Lese-Ansicht (Bilder aus Makros wie
     * draw.io oder eingebundenen Seiten) holt erst dieser Durchgang, Seite für Seite wie vor #635; die
     * Vorbereitung ruft je Seite nichts ab (Angelo 11.11.2026).
     */
    private Loaded load(Run run, String exportId, ConversionInput dataset, PagePlan plan, PageSnapshot snapshot,
                        List<ExportAssetManifest.Entry> entries) {
        JobContext context = run.context;
        String sourceId = dataset.sourceId();
        String pageId = plan.pageId();
        PageAttachmentList list = plan.list();
        boolean checked = plan.checked();
        long discovered = plan.discovered();
        Set<String> drawn = new HashSet<>();
        Map<String, ExportAssetManifest.Entry> byId = new HashMap<>();
        List<ExportAssetManifest.Entry> views = new ArrayList<>();
        try {
            List<Step> steps = new ArrayList<>(plan.steps());
            if (run.share.images()) {
                if (plan.catalog() && list != null && list.attachments().attachments().isEmpty()) {
                    // Leer aus der Suche heißt ohne Anhänge oder verschoben. Vor der Lese-Ansicht, die den Space nicht
                    // prüft, deshalb den aktuellen Space dieser Seite abfragen; Seiten mit Treffern liegen
                    // nachweislich im Space.
                    String current = sources.pageMetadata(sourceId, pageId).entry().page().partitionKey();
                    if (!dataset.spaceKey().equals(current)) {
                        return new Loaded(false, discovered);
                    }
                }
                Set<String> imageIds = new HashSet<>();
                for (Step step : plan.steps()) {
                    if (step.attachment() != null && !step.document()) {
                        imageIds.add(step.attachment().attachmentId());
                    }
                }
                Set<String> own = new HashSet<>();
                if (list != null) {
                    list.attachments().attachments().forEach(attachment -> own.add(attachment.attachmentId()));
                }
                boolean foreign = ForeignContent.of(snapshot).present();
                boolean mayShowForeign = sourceWide.test(context.owner(), sourceId);
                try {
                    long found = 0;
                    for (HarvestSource.ViewImage image : readingView(exportId, sourceId, snapshot,
                            foreign && !mayShowForeign)) {
                        if (image.attachmentId() != null && imageIds.contains(image.attachmentId())) {
                            continue;
                        }
                        boolean otherPage = image.attachmentId() != null && !own.contains(image.attachmentId());
                        steps.add(new Step(null, false, image, (foreign || otherPage) && !mayShowForeign));
                        found++;
                    }
                    context.discovered(found);
                    discovered += found;
                } catch (SourceAccessException e) {
                    unreachable(e);
                    if (e.reason() != SourceAccessException.Reason.UNSUPPORTED) {
                        checked = false;
                    }
                }
            }
            for (Step step : steps) {
                if (step.document()) {
                    byId.put(step.attachment().attachmentId(), document(run, list, pageId, step.attachment()));
                } else if (step.attachment() != null) {
                    byId.put(step.attachment().attachmentId(), image(run, list, pageId, step.attachment(), drawn));
                } else {
                    ExportAssetManifest.Entry entry = view(run, sourceId, snapshot, step, drawn);
                    if (entry != null) {
                        views.add(entry);
                    }
                }
            }
        } finally {
            for (String attachmentId : plan.order()) {
                ExportAssetManifest.Entry entry = byId.get(attachmentId);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            entries.addAll(views);
        }
        if (!run.share.images()) {
            return new Loaded(checked, discovered);
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
        return new Loaded(checked, discovered);
    }

    /** Ein Bild-Anhang; das Backup-PNG eines schon gezeichneten Diagramms wird nicht geladen. */
    private ExportAssetManifest.Entry image(Run run, PageAttachmentList list, String pageId,
                                            PageAttachment attachment, Set<String> drawn) {
        String sourceId = list.sourceId();
        ExportAssetManifest.Reference reference = ExportAssetManifest.Reference.attachment(attachment.attachmentId(),
                attachment.title());
        if (preview(attachment.title(), drawn)) {
            // Das Backup-PNG eines gezeichneten Diagramms: nicht laden, die Quelldaten tragen den Inhalt.
            run.context.processed(1);
            return new ExportAssetManifest.Entry(pageId, reference, attachment.attachmentId(), attachment.version(),
                    attachment.title(), attachment.mediaType(), 0, null, ExportAssetManifest.Status.REDUNDANT,
                    "drawio-preview");
        }
        boolean reload = run.fresh("attachment:" + attachment.attachmentId());
        ExportAssetManifest.Entry entry = store(run, pageId, reference, attachment.attachmentId(),
                attachment.version(), attachment.title(), attachment.mediaType(),
                () -> assets.content(list, attachment.attachmentId(), true, reload).asset());
        if (entry.stored() && Diagrams.isDrawio(entry.mediaType(), attachment.title())) {
            entry = assets.stored(sourceId, entry.attachmentId(), entry.version(), entry.sha256())
                    .map(mxfile -> rendered(sourceId, pageId, Assets.DRAWIO_RENDITION, mxfile))
                    .map(entry::withRendition).orElse(entry);
            if (entry.rendition() != null && entry.rendition().drawn()) {
                drawn.add(Diagrams.drawioName(attachment.title()));
            }
        }
        return entry;
    }

    /**
     * Ein Bild der Lese-Ansicht; {@code null} für die Vorschau eines gezeichneten Diagramms, wie die Ansicht sie zeigt
     * (gezählt, aber ohne Eintrag).
     */
    private ExportAssetManifest.Entry view(Run run, String sourceId, PageSnapshot snapshot, Step step,
                                           Set<String> drawn) {
        JobContext context = run.context;
        String pageId = snapshot.key().pageId();
        HarvestSource.ViewImage image = step.image();
        if (image.attachmentId() == null && preview(Assets.viewFileName(image), drawn)) {
            context.processed(1);
            return null;
        }
        ExportAssetManifest.Reference reference = ExportAssetManifest.Reference.viewImage(withoutUserInfo(
                image.src()));
        if (step.forbidden()) {
            // Gerendert mit den Zugangsdaten der Quelle: nicht laden, nur als nicht freigegeben melden.
            context.processed(1);
            context.failed(1);
            return new ExportAssetManifest.Entry(pageId, reference, null, null, image.title(), image.mediaType(), 0,
                    null, ExportAssetManifest.Status.FORBIDDEN, "foreign-content");
        }
        boolean reload = run.fresh(image.attachmentId() != null ? "attachment:" + image.attachmentId()
                : "view:" + image.src());
        return store(run, pageId, reference, null, null, image.title(), image.mediaType(),
                () -> assets.viewImage(sourceId, pageId, snapshot.revision(), image, reload).asset());
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
        boolean reload = run.fresh("attachment:" + attachment.attachmentId());
        ExportAssetManifest.Entry entry = store(run, pageId, reference, attachment.attachmentId(),
                attachment.version(), attachment.title(), attachment.mediaType(),
                () -> assets.content(list, attachment.attachmentId(), true, reload).asset());
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
        final Scope scope;
        final Share share;
        final String sourceId;
        final ExportSettings settings;
        /** „Neu erzwingen“: jede Datei einmal je Lauf am Speicher vorbei neu laden. */
        final boolean fresh;
        private final Set<String> refreshed = new HashSet<>();
        boolean declined;

        Run(JobContext context, Scope scope, Share share, String sourceId, ExportSettings settings, boolean fresh) {
            this.context = context;
            this.scope = scope;
            this.share = share;
            this.sourceId = sourceId;
            this.settings = settings == null ? ExportSettings.DEFAULT : settings;
            this.fresh = fresh;
        }

        /** Ob die Datei mit diesem Schlüssel in diesem Lauf neu geladen werden soll (nur beim ersten Mal). */
        boolean fresh(String key) {
            return fresh && refreshed.add(key);
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
                    context.phase(COMPONENT, scope.phase);
                }
                if (outcome != SignInRequests.Outcome.UNLOCKED) {
                    if (signIns.interactive()) {
                        // Mit Oberfläche zählen die übrigen Dateien nicht als Fehler: der Auftrag hält an, das
                        // Manifest behält das Abgelegte, und ▶ macht nach der Anmeldung weiter.
                        LOG.log(System.Logger.Level.INFO, "{0} der Quelle {1} angehalten: Anmeldung fehlt",
                                scope.label, sourceId);
                        context.pause(PauseReason.SIGN_IN_REQUIRED);
                    }
                    declined = true;
                    LOG.log(System.Logger.Level.INFO, "{0} der Quelle {1} ohne Anmeldung weiter", scope.label,
                            sourceId);
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
