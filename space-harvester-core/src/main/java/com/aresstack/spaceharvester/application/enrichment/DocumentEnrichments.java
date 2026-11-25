package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.DocumentInventory;
import com.aresstack.spaceharvester.application.assets.ExtractionKey;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.assets.IndexedDocuments;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.assets.StoredExtraction;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundKind;
import com.aresstack.spaceharvester.application.background.BackgroundPart;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobStage;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.observe.Coalescer;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference;
import java.util.function.Function;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.aresstack.spaceharvester.application.configuration.EnrichmentSettings;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Analysen je Anhang und enthaltener Datei (ADR 0026, AP9a): Liegt ein Dokument-Anhang neu oder geändert im
 * Volltextindex, rechnen die Prozessoren mit {@link EnrichmentProcessor#documents()} je abgelegtem Abschnitt, Eingabe
 * {@link DocumentSectionText}. Gerechnet wird als Hintergrundauftrag „{@value #BACKGROUND_TITLE}“ (ADR 0025
 * Nachtrag, {@link #background}) mit höchstens einem offenen Teil je Quelle und Space, sichtbar im Reiter
 * „Hintergrund“, anhaltbar und abbrechbar; der Teil bestimmt seine Arbeit allein aus dem Bestand, eine Meldung und die
 * Anreicherung nach einem Abgleich ({@link #afterSync}) vermerken nur, dass es Arbeit gibt. Ohne Hintergrundaufträge
 * (Dienst) als Auftrag {@link JobType#ENRICHMENT} der Quelle und des
 * Space, der alle bis dahin gemeldeten Anhänge seiner Partition mitnimmt; ein weiterer entsteht erst, wenn er fertig
 * ist.
 *
 * <ul>
 *   <li>Nur Dokumente und Teile mit Status {@code OK} oder {@code PARTIAL} ({@link DocumentExtractions#documentSections});
 *       ein Abschnitt ohne Text bekommt kein Ergebnis.</li>
 *   <li>Aktuelle Ergebnisse (gleiche Eingabe, gleiche Version) bleiben; ein neu gelesenes Dokument mit gleichem Text
 *       kostet nur das Lesen der Abschnitte.</li>
 *   <li>Erst wenn alle Abschnitte eines Anhangs durchgelaufen sind, fallen Ergebnisse von Teilen und Abschnitten weg,
 *       die es nicht mehr gibt (Schlüsselform Punkt 5): Der alte Stand gilt bis zum Erfolg. Nennt die vollständige
 *       Anhangsliste einer Seite einen Anhang nicht mehr, fallen seine Ergebnisse samt Teilen sofort weg.</li>
 *   <li>Abgeschaltet ({@code enabled} liefert {@code false}) entsteht nichts Neues; vorhandene Ergebnisse bleiben
 *       lesbar.</li>
 *   <li>Nach Abbruch oder Anhalten bleiben die übrigen Anhänge gemeldet und laufen mit dem nächsten Auftrag der
 *       Partition; ein angehaltener Hintergrundteil rechnet sie beim Fortsetzen, auch nach einem Neustart. Ein
 *       abgebrochener Hintergrundteil startet keinen neuen: erst ein neuer Anlass, etwa der nächste Abgleich des Space.
 *       Vermerke, aus denen noch kein Teil wurde (die Bündelung von {@link #BUNDLE}, ein laufender Abgleich), liegen
 *       nur im Speicher; nach einem Neustart holt sie der nächste Abgleich des Space nach.</li>
 * </ul>
 */
public final class DocumentEnrichments implements IndexedDocuments, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DocumentEnrichments.class.getName());
    private static final String COMPONENT = "Enrichments";
    public static final String PHASE = "Anreichern: Anhänge";
    /** Art der Hintergrundaufträge (ADR 0025 Nachtrag; Angelo 21.11.2026: nichts läuft unsichtbar). */
    public static final String BACKGROUND_KIND = "documents";
    /** Titel der Gruppe in der Auftragsliste. */
    public static final String BACKGROUND_TITLE = "Anhänge analysieren";
    /** So lange bündelt {@link #background} Meldungen außerhalb eines Abgleichs, bevor ein Teil startet. */
    public static final Duration BUNDLE = Duration.ofSeconds(10);
    /**
     * Arten der Aufträge, die einen Teil aufhalten können ({@link #holdsBack}): Abgleich, Neulesen, Anhänge und die
     * Anreicherung nach einem Abgleich. Solange einer davon für den Space wartet oder läuft, startet kein Teil; sein
     * Ende startet ihn (Abnahme #1052: eine Gruppe je Abgleich, nicht je Anhang).
     */
    private static final Set<JobType> INDEXING = EnumSet.of(JobType.SYNC, JobType.ENRICHMENT, JobType.ATTACHMENTS,
            JobType.REREAD);

    private final Enrichments enrichments;
    private final Jobs jobs;
    private final BooleanSupplier enabled;
    private final Supplier<EnrichmentSettings> settings;
    /** Gemeldete Anhänge nach {@link DocumentKey#value()}, in Meldereihenfolge; bewacht von {@code this}. */
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    /**
     * Partitionen ({@code Quelle \0 Space}) mit dem zuletzt eingeplanten Auftrag; bewacht von {@code this}. Ob er noch
     * wartet oder läuft, fragt {@link #alive} die Auftragsverwaltung: Ein vor dem Start abgebrochener Auftrag läuft nie
     * und hält so keine spätere Meldung auf.
     */
    private final Map<String, String> scheduled = new HashMap<>();
    /** Wie oft die Ergebnisse eines Anhangs entfernt wurden, nach {@link DocumentKey#value()}; bewacht von {@code this}. */
    private final Map<String, Integer> removals = new HashMap<>();
    /** Anhänge für den Bestand ({@link #catchUp}); ohne sie holt der Lauf nichts nach. */
    private volatile DocumentInventory assets;
    private volatile LocalDocuments localDocuments;

    private record LocalDocuments(Function<PageKey, Optional<PageSnapshot>> snapshots,
                                  DocumentExtractions extractions) {
    }

    public void localDocuments(Function<PageKey, Optional<PageSnapshot>> snapshots, DocumentExtractions extractions) {
        this.localDocuments = new LocalDocuments(Objects.requireNonNull(snapshots, "snapshots"),
                Objects.requireNonNull(extractions, "extractions"));
    }

    private Optional<Pending> localPending(PageKey page) {
        LocalDocuments local = localDocuments;
        if (local == null) {
            return Optional.empty();
        }
        return local.snapshots().apply(page).flatMap(snapshot -> LocalOriginalReference.read(snapshot.payload())
                .flatMap(reference -> Optional.ofNullable(reference.extractionKey()))
                .map(key -> new Pending(DocumentKey.localOriginal(page), snapshot.spaceKey(), key.sha256(),
                        local.extractions(), key)));
    }

    /** Check a completed analysis against the exact extraction and current processor version. */
    public boolean hasCurrentLocalAnalysis(PageSnapshot snapshot, String processorId) {
        Optional<LocalOriginalReference> reference = LocalOriginalReference.read(snapshot.payload());
        Optional<Pending> pending = localPending(snapshot.key());
        if (reference.isEmpty() || pending.isEmpty()
                || !Objects.equals(reference.get().extractionKey(), pending.get().exactExtraction())) {
            return false;
        }
        Pending item = pending.get();
        return item.extraction().map(extraction -> {
            Manifest manifest = manifest(item, extraction);
            String version = manifest.versions().get(processorId);
            if (version == null) {
                return false;
            }
            List<DocumentKey> expected = item.extractions().documents(extraction).stream()
                    .map(part -> part.map(entry -> item.attachment().child(entry.id())).orElse(item.attachment()))
                    .toList();
            return !expected.isEmpty() && expected.stream().allMatch(document -> {
                List<Enrichment> results = manifest.stored().getOrDefault(document, List.of()).stream()
                        .filter(result -> processorId.equals(result.processorId())).toList();
                return !results.isEmpty() && results.stream().allMatch(result ->
                        result.status() == Enrichment.Status.OK && version.equals(result.processorVersion())
                                && manifest.basis().equals(result.extraction()) && !enrichments.outdated(result));
            });
        }).orElse(false);
    }

    /** Delegate search preparation to the existing document scheduler; do not compute a second page embedding. */
    public boolean prepareLocal(PageSnapshot snapshot, String processorId) {
        if (!enabled.getAsBoolean()
                || !enrichments.documentProcessorVersions(snapshot.key()).containsKey(processorId)
                || LocalOriginalReference.read(snapshot.payload())
                        .map(reference -> reference.extractionKey() == null).orElse(true)) {
            return true;
        }
        Optional<Pending> pending = localPending(snapshot.key());
        if (pending.isPresent() && pending.get().extraction()
                .map(extraction -> pending.get().extractions().documents(extraction).isEmpty()).orElse(false)) {
            return true;
        }
        if (hasCurrentLocalAnalysis(snapshot, processorId)) {
            return true;
        }
        indexedLocal(snapshot);
        return hasCurrentLocalAnalysis(snapshot, processorId);
    }

    /** Schedule the newly committed local original, not a preview or a synthetic attachment. */
    public void indexedLocal(PageSnapshot snapshot) {
        if (localDocuments == null || !LocalOriginalReference.recognizes(snapshot.payload())) {
            return;
        }
        Optional<Pending> item = localPending(snapshot.key());
        if (item.isEmpty()) {
            // No readable original in this revision: discard the previous sectioned analysis.
            enrichments.store().removeDocument(DocumentKey.localOriginal(snapshot.key()));
            enrichments.flush();
        } else if (enabled.getAsBoolean() && !enrichments.documentProcessorIds().isEmpty()) {
            enqueue(item.get());
        }
    }
    /** Der Netzzustand, oder {@code null}: dann zählt ein Netzfehler eines Prozessors wie jeder andere. */
    private volatile com.aresstack.spaceharvester.application.network.NetworkState network;

    /** Ein Abschnitt scheiterte, während das Netz weg ist: derselbe Anhang, sobald es zurück ist. */
    private static final class Offline extends RuntimeException {
        Offline() {
            super(null, null, false, false);
        }
    }
    /** Hintergrundaufträge; ohne sie ein eigener Auftrag ohne Auftraggeber (Dienst). */
    private volatile BackgroundJobs background;
    /** Die Seiten eines Space, aus denen ein Hintergrundteil seine Anhänge bestimmt. */
    private volatile SpacePages spacePages;
    /** Bündelt Meldungen je Partition; {@code null}: sofort im meldenden Thread. */
    private volatile Coalescer<Partition, Boolean> starts;
    /** Meldungen der Auftragsverwaltung über das Ende eines Abgleichs. */
    private volatile Subscription indexingEnds = () -> {
    };
    /** Partitionen mit Arbeit, die noch kein Teil aufgenommen hat; bewacht von {@code this}. */
    private final Set<Partition> noted = new HashSet<>();
    /**
     * Rechnende Teile nach Auftrags-ID mit ihrer Partition; ein rechnender sieht danach noch einmal nach. Je Auftrag,
     * damit das Ende eines nach {@link Jobs#STOP_GRACE} abgekoppelten Teils keinen anderen derselben Partition austrägt;
     * bewacht von {@code this}.
     */
    private final Map<String, Partition> active = new HashMap<>();
    /** Partitionen mit Arbeit, deren Start auf das Ende eines Abgleichs wartet; bewacht von {@code this}. */
    private final Set<Partition> deferred = new HashSet<>();
    /**
     * Abgebrochene Teile, deren Vermerke schon verworfen sind; bewacht von {@code this}. Die Auftragsverwaltung meldet
     * einen beendeten Auftrag erneut, etwa nach {@link Jobs#STOP_GRACE} oder bei jedem Zähler einer abgekoppelten
     * Arbeit: Verworfen wird nur beim ersten Mal, sonst gingen Vermerke neuer Anlässe verloren. Ein Fortsetzen oder
     * Löschen trägt den Teil wieder aus, ein Löschen während seiner Arbeit erst deren Ende ({@link #goneWhileComputing}).
     */
    private final Set<String> discarded = new HashSet<>();
    /**
     * Gelöschte Teile, deren abgekoppelte Arbeit noch rechnet (Nachprüfung #1052); bewacht von {@code this}. Sie bleiben
     * bis zum Ende dieser Arbeit in {@link #discarded}, damit deren {@code finally} keinen Vermerk verwirft, der erst
     * nach dem Löschen kam.
     */
    private final Set<String> goneWhileComputing = new HashSet<>();

    /** Quelle und Space eines Hintergrundteils; der Space kann fehlen. */
    private record Partition(String sourceId, String spaceKey) {
    }

    /** Die Seiten-IDs eines Space mit gespeichertem Inhalt; aus ihren Anhängen bestimmt ein Teil seine Arbeit. */
    @FunctionalInterface
    public interface SpacePages {

        Collection<String> pageIds(String sourceId, String spaceKey);
    }

    /**
     * @param jobs    Auftragsverwaltung oder {@code null}, dann im aufrufenden Thread
     * @param enabled ob Analysen je Dokument entstehen ({@code space-harvester.enrichment.documents.enabled})
     */
    public DocumentEnrichments(Enrichments enrichments, Jobs jobs, BooleanSupplier enabled) {
        this(enrichments, jobs, enabled, () -> EnrichmentSettings.DEFAULT);
    }

    /**
     * @param jobs     Auftragsverwaltung oder {@code null}, dann im aufrufenden Thread
     * @param settings die wirksamen Einstellungen {@code space-harvester.enrichment.documents.*}, beim nächsten Lauf
     *                 wirksam
     */
    public DocumentEnrichments(Enrichments enrichments, Jobs jobs, Supplier<EnrichmentSettings> settings) {
        this(enrichments, jobs, () -> settings.get().documents(), settings);
    }

    private DocumentEnrichments(Enrichments enrichments, Jobs jobs, BooleanSupplier enabled,
                                Supplier<EnrichmentSettings> settings) {
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.jobs = jobs;
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Hängt den Netzzustand an (Angelo 22.11.2026): Scheitern Prozessoren eines Abschnitts, während das Netz weg ist,
     * zählt nichts als gescheitert; der Teil wartet sichtbar und rechnet denselben Anhang im selben Teil erneut, sobald
     * das Netz zurück ist. Die Prozessoren melden ihre Netzfehler selbst an den Netzzustand.
     */
    public void network(com.aresstack.spaceharvester.application.network.NetworkState network) {
        this.network = network;
    }

    /** Schließt die Anhänge an, aus deren Ablage {@link #catchUp} den Bestand nachholt. */
    public void assets(DocumentInventory assets) {
        this.assets = assets;
    }

    /** Wie {@link #background(BackgroundJobs, SpacePages, Duration)}, Meldungen gebündelt über {@link #BUNDLE}. */
    public void background(BackgroundJobs background, SpacePages pages) {
        background(background, pages, BUNDLE);
    }

    /**
     * Rechnet als Hintergrundauftrag (Art {@value #BACKGROUND_KIND}, Abnahme #1052): Ein Teil je Quelle und Space
     * bestimmt seine Arbeit allein aus dem Bestand, die Anhänge der Seiten des Space ohne aktuelle Analyse
     * ({@link #catchUp}); eine Meldung ({@link #indexed}) und die Anreicherung nach einem Abgleich ({@link #afterSync})
     * vermerken nur, dass es Arbeit gibt. Je Space ist höchstens ein Teil offen ({@link BackgroundKind#exclusive()},
     * auch beim Fortsetzen): Wartet oder ruht einer, nimmt er die Arbeit mit; rechnet einer, sieht er danach noch
     * einmal nach. Solange ein Abgleich, ein Neulesen oder ein Anhangsauftrag der Quelle für den Space oder die
     * Anreicherung nach dem Abgleich des Space wartet oder läuft ({@link #holdsBack}), startet kein Teil und ein
     * rechnender beginnt keine weitere Runde; erst das Ende des Auftrags startet den nächsten (Auftragsmeldung,
     * ADR 0027). Außerhalb davon bündelt der Dienst Meldungen über {@code bundle}. Nur ein fertiger Teil plant für
     * Vermerke aus seiner Laufzeit einen neuen; ein abgebrochener verwirft sie. Nach einem Neustart setzt ein
     * unterbrochener Teil nur fort, wenn die Analysen je Dokument eingeschaltet sind.
     *
     * @param pages  die Seiten eines Space mit gespeichertem Inhalt
     * @param bundle so lange werden Meldungen je Space gebündelt; {@link Duration#ZERO} startet im meldenden Thread
     */
    public void background(BackgroundJobs background, SpacePages pages, Duration bundle) {
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(bundle, "bundle");
        this.spacePages = Objects.requireNonNull(pages, "pages");
        background.register(BackgroundKind.exclusive(BACKGROUND_KIND, part -> JobTask.blocking(context ->
                analyse(part.jobId(), new Partition(part.sourceId(), part.partitionKey()), context)), enabled));
        this.starts = bundle.isZero() ? null
                : new Coalescer<>("document-enrichments", bundle, (partition, ignored) -> startWhenIdle(partition));
        if (jobs != null) {
            this.indexingEnds = jobs.subscribeAll(new JobListener() {
                @Override
                public void changed(JobStatus status) {
                    if (INDEXING.contains(status.type()) && !busy(status)) {
                        wake(status.sourceId(), status.partitionKey());
                    } else if (ownPart(status)) {
                        if (status.state().terminal()) {
                            ended(status.id(), new Partition(status.sourceId(), status.partitionKey()),
                                    status.state());
                        } else {
                            reopened(status.id());
                        }
                    }
                }

                @Override
                public void removed(JobStatus last) {
                    if (INDEXING.contains(last.type())) {
                        wake(last.sourceId(), last.partitionKey());
                    } else if (ownPart(last)) {
                        synchronized (DocumentEnrichments.this) {
                            // Ein gelöschter, noch nicht fertiger Teil ist abgebrochen; seine Abbruchmeldung kommt nie.
                            if (last.state() == JobState.CANCELLED || !last.state().terminal()) {
                                ended(last.id(), new Partition(last.sourceId(), last.partitionKey()),
                                        JobState.CANCELLED);
                            }
                            // Rechnet seine Arbeit noch, trägt erst deren Ende ihn aus (analyse, finally).
                            if (active.containsKey(last.id())) {
                                goneWhileComputing.add(last.id());
                            } else {
                                discarded.remove(last.id());
                            }
                        }
                    }
                }
            });
        }
        this.background = background;
    }

    /** Beendet Bündelung und Auftragsmeldungen; laufende Teile beendet die Auftragsverwaltung. */
    @Override
    public void close() {
        indexingEnds.close();
        Coalescer<Partition, Boolean> bundled = starts;
        if (bundled != null) {
            bundled.close();
        }
    }

    /**
     * Der Lesetext eines Abschnitts ({@link DocumentSectionText}) aus der abgelegten Extraktion des Anhangs, aber nur,
     * wenn er genau der Text ist, aus dem ein Ergebnis entstand ({@code inputSha256}); sonst leer, ebenso ohne
     * angeschlossene Anhänge, ohne lesbare Extraktion oder für die Seite selbst. Für Ausschnitte von Treffern der
     * semantischen Suche in einem Dokument (ADR 0026, AP9a): Stellen gelten nur in genau diesem Text.
     */
    public Optional<PageText> sectionText(DocumentKey document, int section, String inputSha256) {
        return sectionText(document, section, inputSha256, null);
    }

    /**
     * Wie {@link #sectionText(DocumentKey, int, String)}, mit {@code extraction} (die am Stand vermerkte Extraktion,
     * {@link Enrichment#extraction()}) zudem nur, wenn der Stand zur jetzigen Extraktion des Anhangs gehört; ohne
     * vermerkte Extraktion (Lauf nicht durch) nie.
     */
    public Optional<PageText> sectionText(DocumentKey document, int section, String inputSha256,
                                          String extraction) {
        Objects.requireNonNull(document, "document");
        if (document.localOriginal()) {
            return localPending(document.page()).flatMap(Pending::extraction)
                    .filter(current -> extraction == null || extraction.startsWith(basis(current) + "|"))
                    .flatMap(current -> localDocuments.extractions().documentSection(current, document.part(), section))
                    .map(DocumentSectionText::project)
                    .filter(text -> inputSha256 != null && inputSha256.equals(text.inputSha256()));
        }
        DocumentInventory source = assets;
        if (source == null || document.attachmentId().isEmpty() || inputSha256 == null) {
            return Optional.empty();
        }
        Optional<DocumentExtractions> structured = source.documentExtractions();
        if (structured.isEmpty()) {
            return Optional.empty();
        }
        PageKey page = document.page();
        Asset asset = source.indexedDocuments(page.sourceId(), List.of(page.pageId())).stream()
                .filter(candidate -> candidate.attachmentId().equals(document.attachmentId().get()))
                .findFirst().orElse(null);
        if (asset == null) {
            return Optional.empty();
        }
        Optional<StoredExtraction> current = structured.get().readable(asset.sha256());
        if (current.isEmpty()) {
            return Optional.empty();
        }
        if (extraction != null && !extraction.startsWith(basis(current.get()) + "|")) {
            // Der Stand gehört zu einer anderen Extraktion: ein anderer Abschnitt kann sich geändert haben.
            return Optional.empty();
        }
        // Nur dieser Abschnitt: ein Ausschnitt am Ende eines großen Dokuments liest nicht das ganze Dokument.
        return structured.get().documentSection(current.get(), document.part(), section)
                .map(DocumentSectionText::project)
                .filter(text -> inputSha256.equals(text.inputSha256()));
    }

    /** Ein gemeldeter Anhang. */
    private record Pending(DocumentKey attachment, String spaceKey, String sha256, DocumentExtractions extractions,
                           ExtractionKey exactExtraction) {
        Pending(DocumentKey attachment, String spaceKey, String sha256, DocumentExtractions extractions) {
            this(attachment, spaceKey, sha256, extractions, null);
        }

        Optional<StoredExtraction> extraction() {
            return exactExtraction == null ? extractions.readable(sha256) : extractions.stored(exactExtraction);
        }
    }

    @Override
    public void indexed(PageAttachmentList list, Asset asset, DocumentExtractions extractions) {
        if (!enabled.getAsBoolean() || enrichments.documentProcessorIds().isEmpty()) {
            return;
        }
        PageKey page = new PageKey(list.sourceId(), list.attachments().pageId());
        enqueue(new Pending(DocumentKey.attachment(page, asset.attachmentId()), list.spaceKey(),
                asset.sha256(), Objects.requireNonNull(extractions, "extractions")));
    }

    private void enqueue(Pending item) {
        String sourceId = item.attachment().page().sourceId();
        String spaceKey = item.spaceKey();
        if (background != null) {
            note(new Partition(sourceId, spaceKey));
            return;
        }
        String partition = partition(sourceId, spaceKey);
        String job;
        synchronized (this) {
            // Ein erneut gemeldeter Anhang rückt nach hinten und rechnet mit seinem neuesten Stand.
            pending.remove(item.attachment().value());
            pending.put(item.attachment().value(), item);
            job = scheduled.get(partition);
        }
        if (jobs == null) {
            drain(sourceId, spaceKey, null);
            return;
        }
        if (job != null && alive(job)) {
            return;
        }
        try {
            String id = submit(sourceId, spaceKey);
            synchronized (this) {
                scheduled.put(partition, id);
            }
        } catch (JobCapacityExceededException | IllegalStateException e) {
            LOG.log(System.Logger.Level.INFO, "Analysen der Anhänge in {0} nicht eingeplant ({1}); die nächste "
                    + "Meldung holt sie nach", sourceId, e.getClass().getSimpleName());
        }
    }

    /** Plant den Auftrag der Partition ein (ohne Hintergrundaufträge). */
    private String submit(String sourceId, String spaceKey) {
        return jobs.submit(JobType.ENRICHMENT, sourceId, spaceKey, null,
                JobTask.blocking(context -> drain(sourceId, spaceKey, context))).id();
    }

    // --- Hintergrundauftrag ---------------------------------------------------------------------------------------

    /** Vermerkt Arbeit für die Partition und plant ihren Start. */
    private void note(Partition partition) {
        synchronized (this) {
            noted.add(partition);
        }
        schedule(partition);
    }

    private void schedule(Partition partition) {
        Coalescer<Partition, Boolean> bundled = starts;
        if (bundled != null) {
            bundled.mark(partition);
        } else {
            startWhenIdle(partition);
        }
    }

    /**
     * Nach dem Ende eines Teils (Auftragsmeldung): Nur ein fertiger plant für Arbeit, die er nicht mehr gesehen hat,
     * einen neuen. Ein abgebrochener verwirft die Vermerke des Space (Abnahme #1052: Abbrechen heißt diese Arbeit
     * nicht); erst ein neuer Anlass startet wieder. Ein angehaltener hält sie, bis er fortgesetzt wird, ein
     * gescheiterter lässt sie für den nächsten Anlass liegen.
     */
    private void ended(String jobId, Partition partition, JobState state) {
        boolean due;
        synchronized (this) {
            if (state == JobState.CANCELLED) {
                if (!discarded.add(jobId)) {
                    return;
                }
                noted.remove(partition);
                deferred.remove(partition);
                return;
            }
            due = state == JobState.COMPLETED && noted.contains(partition) && !computing(partition);
        }
        if (due) {
            schedule(partition);
        }
    }

    /** Ein Teil dieser Art (Auftragsmeldung). */
    private static boolean ownPart(JobStatus status) {
        return status.type() == JobType.BACKGROUND && BACKGROUND_KIND.equals(status.parameter(BackgroundJobs.KIND));
    }

    /** Ein Teil läuft wieder (fortgesetzt): ein späteres Abbrechen verwirft wieder. */
    private synchronized void reopened(String jobId) {
        discarded.remove(jobId);
    }

    /** Ob ein Teil der Partition gerade rechnet; unter {@code this}. */
    private boolean computing(Partition partition) {
        return active.containsValue(partition);
    }

    /** Nach dem Ende eines Abgleichs der Quelle: die Partitionen, deren Start darauf wartete. */
    private void wake(String sourceId, String spaceKey) {
        List<Partition> due;
        synchronized (this) {
            due = deferred.stream().filter(partition -> partition.sourceId().equals(sourceId)
                    && (spaceKey == null || spaceKey.equals(partition.spaceKey()))).toList();
        }
        due.forEach(this::schedule);
    }

    /**
     * Startet einen Teil für vermerkte Arbeit, außer ein Teil rechnet gerade (er sieht danach noch einmal nach), einer
     * wartet, ruht oder beginnt eben (er nimmt sie mit) oder ein Auftrag hält die Partition auf ({@link #holdsBack};
     * dessen Ende startet ihn). Endet ein Teil, kurz nachdem er zuletzt nachsah, startet sein Ende den nächsten
     * ({@link #ended}).
     */
    private void startWhenIdle(Partition partition) {
        BackgroundJobs hosted = background;
        if (hosted == null) {
            return;
        }
        synchronized (this) {
            if (!noted.contains(partition) || computing(partition)) {
                deferred.remove(partition);
                return;
            }
            // Erst vormerken, dann nachsehen: Endet der Abgleich dazwischen, findet seine Meldung den Vermerk.
            deferred.add(partition);
        }
        if (indexing(partition)) {
            return;
        }
        synchronized (this) {
            deferred.remove(partition);
        }
        try {
            hosted.startUnlessRunning(BACKGROUND_KIND, BACKGROUND_TITLE,
                    new BackgroundPart.Draft(partition.sourceId(), partition.spaceKey()));
        } catch (JobCapacityExceededException | IllegalStateException e) {
            LOG.log(System.Logger.Level.INFO, "Analysen der Anhänge in {0} nicht eingeplant ({1}); die nächste "
                    + "Meldung holt sie nach", partition.sourceId(), e.getClass().getSimpleName());
        }
    }

    /** Ob ein Auftrag die Partition aufhält ({@link #holdsBack}). */
    private boolean indexing(Partition partition) {
        if (jobs == null) {
            return false;
        }
        for (JobType type : INDEXING) {
            for (JobStatus status : jobs.listAll(type)) {
                if (holdsBack(status, partition.sourceId(), partition.spaceKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Ob dieser Auftrag den Start und jede weitere Runde eines Teils für Quelle und Space aufhält (Abnahme #1052): ein
     * wartender oder laufender Abgleich, ein Neulesen oder ein Anhangsauftrag der Quelle für diesen Space oder ohne
     * Space (ein Neulesen gilt für die ganze Quelle), und die Anreicherung nach dem Abgleich dieses Space
     * ({@link EnrichmentsAfterSync#TASK}). Andere Anreicherungen, etwa Bildbeschreibung, Abkürzungen oder Synonyme,
     * halten nicht auf, ebenso kein Auftrag, der auf seinen Vorgänger wartet ({@link JobStage#AWAITING}, etwa die
     * Anhänge eines Exports bis zu dessen Text) oder angehalten ist.
     */
    static boolean holdsBack(JobStatus status, String sourceId, String spaceKey) {
        if (!busy(status) || !sourceId.equals(status.sourceId())) {
            return false;
        }
        return switch (status.type()) {
            case SYNC, REREAD, ATTACHMENTS -> status.partitionKey() == null
                    || status.partitionKey().equals(spaceKey);
            case ENRICHMENT -> EnrichmentsAfterSync.TASK.equals(status.parameter(EnrichmentsAfterSync.TASK_PARAMETER))
                    && Objects.equals(status.partitionKey(), spaceKey);
            default -> false;
        };
    }

    /** Wartend, aber nicht auf seinen Vorgänger, oder laufend; angehalten oder beendet nicht. */
    private static boolean busy(JobStatus status) {
        return status.state() == JobState.RUNNING
                || status.state() == JobState.QUEUED && status.stage() != JobStage.AWAITING;
    }

    /**
     * Ein Hintergrundteil: die Anhänge der Seiten des Space ohne aktuelle Analyse, aus dem Bestand; kam währenddessen
     * eine Meldung, noch ein Durchgang. Vor jedem Durchgang sieht er nach, ob ein Auftrag die Partition aufhält
     * ({@link #holdsBack}); dann endet er und vermerkt die Arbeit für den nächsten Teil, den das Ende jenes Auftrags
     * startet (Abnahme #1052). Nach Anhalten, Abbruch oder Neustart beginnt er von vorn, Aktuelles kostet dann nur den
     * Blick auf den Analysestand.
     */
    private void analyse(String jobId, Partition partition, JobContext context) {
        boolean elsewhere;
        synchronized (this) {
            // Rechnet noch die abgekoppelte Arbeit eines anderen Teils (Jobs#STOP_GRACE), etwa nach dem Fortsetzen
            // eines älteren Eintrags: nicht doppelt rechnen; deren Ende plant den nächsten Teil.
            elsewhere = computing(partition);
            if (elsewhere) {
                noted.add(partition);
            } else {
                active.put(jobId, partition);
            }
        }
        if (elsewhere) {
            context.record(JobEvent.Draft.info(COMPONENT, "Ein anderer Teil des Space rechnet noch; danach geht es "
                    + "mit einem neuen Teil weiter"));
            return;
        }
        try {
            while (true) {
                if (indexing(partition)) {
                    synchronized (this) {
                        noted.add(partition);
                        deferred.add(partition);
                    }
                    context.record(JobEvent.Draft.info(COMPONENT, "Ein Abgleich, ein Neulesen oder ein Anhangsauftrag "
                            + "des Space läuft; danach geht es mit einem neuen Teil weiter"));
                    return;
                }
                synchronized (this) {
                    noted.remove(partition);
                }
                SpacePages pages = spacePages;
                catchUp(partition.sourceId(), partition.spaceKey(), pages == null ? List.of()
                        : pages.pageIds(partition.sourceId(), partition.spaceKey()), context);
                synchronized (this) {
                    if (!noted.contains(partition)) {
                        return;
                    }
                }
            }
        } finally {
            // Abgekoppelt abgebrochen oder gelöscht: Die Meldung darüber kann noch ausstehen, verworfen wird jetzt
            // (einmal, ended); sonst plante dieser Teil gleich wieder für Vermerke aus seiner eigenen Laufzeit. Kam
            // die Löschung schon an, ist längst verworfen, und was seither vermerkt ist, bleibt.
            boolean cancelled = cancelledOrGone(jobId);
            synchronized (this) {
                active.remove(jobId);
                if (cancelled) {
                    ended(jobId, partition, JobState.CANCELLED);
                }
                if (goneWhileComputing.remove(jobId)) {
                    discarded.remove(jobId);
                }
            }
            boolean due;
            synchronized (this) {
                due = noted.contains(partition) && !computing(partition);
            }
            // Abgekoppelt nach Jobs#STOP_GRACE ist der Eintrag schon beendet, wenn die Arbeit hier ankommt: Vermerke
            // seither bekämen sonst keinen Teil. Läuft er noch, plant sein Ende ({@link #ended}); ruht er, das
            // Fortsetzen.
            BackgroundJobs hosted = background;
            if (due && hosted != null
                    && hosted.open(BACKGROUND_KIND, partition.sourceId(), partition.spaceKey()).isEmpty()) {
                schedule(partition);
            }
        }
    }

    /** Ob der eigene Eintrag schon abgebrochen oder gelöscht ist (abgekoppelte Arbeit, Jobs#STOP_GRACE). */
    private boolean cancelledOrGone(String jobId) {
        if (jobs == null) {
            return false;
        }
        try {
            return jobs.status(jobId).state() == JobState.CANCELLED;
        } catch (UnknownJobException e) {
            return true;
        }
    }

    /** Ob der Auftrag noch wartet oder läuft; angehalten, beendet oder unbekannt zählt nicht. */
    private boolean alive(String jobId) {
        try {
            return !jobs.status(jobId).state().terminal();
        } catch (UnknownJobException e) {
            return false;
        }
    }

    @Override
    public void retained(PageKey page, Set<String> attachmentIds) {
        Set<String> gone = new HashSet<>();
        synchronized (this) {
            // Gemeldete, aber entfernte Anhänge rechnen nicht mehr; ein laufender bemerkt es über removals.
            Iterator<Pending> iterator = pending.values().iterator();
            while (iterator.hasNext()) {
                DocumentKey attachment = iterator.next().attachment();
                if (!attachment.localOriginal() && attachment.page().equals(page)
                        && !attachmentIds.contains(attachment.attachmentId().orElse(""))) {
                    iterator.remove();
                    removals.merge(attachment.value(), 1, Integer::sum);
                }
            }
        }
        for (Enrichment enrichment : enrichments.store().findDocuments(page)) {
            enrichment.document().attachmentId().filter(id -> !attachmentIds.contains(id)).ifPresent(gone::add);
        }
        for (String attachmentId : gone) {
            DocumentKey attachment = DocumentKey.attachment(page, attachmentId);
            synchronized (this) {
                removals.merge(attachment.value(), 1, Integer::sum);
            }
            enrichments.store().removeDocument(attachment);
        }
        if (!gone.isEmpty()) {
            enrichments.flush();
        }
    }

    /** Rechnet die gemeldeten Anhänge der Partition, bis keiner mehr wartet. */
    private void drain(String sourceId, String spaceKey, JobContext context) {
        String partition = partition(sourceId, spaceKey);
        if (context != null) {
            context.phase(COMPONENT, PHASE);
        }
        boolean finished = false;
        try {
            Pending item;
            while ((item = take(sourceId, spaceKey, partition)) != null) {
                if (!enabled.getAsBoolean()) {
                    // Inzwischen abgeschaltet: nichts Neues; der Lauf nach dem Abgleich holt es nach dem Einschalten
                    // aus dem Zustand nach.
                    continue;
                }
                int removed = removals(item);
                try {
                    process(item, context, null);
                } catch (JobCancelledException | JobFailedException e) {
                    // Abgebrochen, angehalten oder Zeit abgelaufen: Der Anhang bleibt gemeldet, wenn er nicht neu
                    // gemeldet wurde.
                    requeue(item);
                    throw e;
                } catch (RuntimeException e) {
                    // Ein Anhang, der sich nicht lesen oder ablegen lässt, hält die übrigen nicht auf.
                    LOG.log(System.Logger.Level.WARNING, "Analysen eines Anhangs in {0} übersprungen ({1})",
                            sourceId, e.getClass().getName());
                } finally {
                    forgetIfRemoved(item, removed);
                }
            }
            finished = true;
        } finally {
            enrichments.flush();
            if (!finished) {
                synchronized (this) {
                    scheduled.remove(partition);
                }
            }
        }
    }

    /** Der nächste gemeldete Anhang der Partition; ohne einen endet die Einplanung im selben Schritt. */
    private synchronized Pending take(String sourceId, String spaceKey, String partition) {
        Iterator<Pending> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Pending item = iterator.next();
            if (item.attachment().page().sourceId().equals(sourceId)
                    && Objects.equals(item.spaceKey(), spaceKey)) {
                iterator.remove();
                return item;
            }
        }
        scheduled.remove(partition);
        return null;
    }

    private synchronized void requeue(Pending item) {
        pending.putIfAbsent(item.attachment().value(), item);
    }

    private synchronized int removals(Pending item) {
        return removals.getOrDefault(item.attachment().value(), 0);
    }

    /** Wurde der Anhang entfernt, während er rechnete, fällt auch das eben Gespeicherte wieder weg. */
    private void forgetIfRemoved(Pending item, int before) {
        if (removals(item) != before || item.attachment().localOriginal()
                && localPending(item.attachment().page()).filter(current ->
                        Objects.equals(current.exactExtraction(), item.exactExtraction())).isEmpty()) {
            enrichments.store().removeDocument(item.attachment());
        }
    }

    /**
     * Holt den Bestand nach (ADR 0026, AP9a): für die abgelegten Dokument-Anhänge dieser Seiten, die im Volltextindex
     * stehen, jedes Dokument mit lesbarer Extraktion, dessen Analysestand fehlt oder nicht aktuell ist. Aktuell heißt:
     * Stände aller zuständigen Prozessoren in ihrer Version, gebildet für diese Extraktion; das prüft der Lauf am
     * Manifest, ohne einen Abschnitt zu lesen. Läuft im Auftrag der Anreicherung nach einem Abgleich.
     */
    public void catchUp(String sourceId, String spaceKey, Collection<String> pageIds, JobContext context) {
        if (!enabled.getAsBoolean() || enrichments.documentProcessorIds().isEmpty()) {
            return;
        }
        List<Pending> documents = work(sourceId, spaceKey, pageIds);
        if (documents.isEmpty()) {
            return;
        }
        if (context != null) {
            context.phase(COMPONENT, PHASE);
        }
        com.aresstack.spaceharvester.application.network.NetworkState net = network;
        com.aresstack.spaceharvester.application.network.NetworkWait wait = net == null || context == null ? null
                : new com.aresstack.spaceharvester.application.network.NetworkWait(net, context, COMPONENT);
        try {
            for (int next = 0; next < documents.size(); next++) {
                if (context != null) {
                    context.checkpoint();
                }
                Pending item = documents.get(next);
                int removed = removals(item);
                try {
                    process(item, context, wait);
                } catch (Offline offline) {
                    // Wartet sichtbar, bis das Netz zurück ist (oder dieser Teil prüft), dann derselbe Anhang.
                    wait.offline();
                    next--;
                } catch (JobCancelledException | JobFailedException e) {
                    throw e;
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Analysen eines Anhangs in {0} übersprungen ({1})",
                            sourceId, e.getClass().getName());
                } finally {
                    forgetIfRemoved(item, removed);
                }
            }
        } finally {
            enrichments.flush();
        }
    }

    /**
     * Nach einem Abgleich des Space, im Auftrag der Anreicherung ({@link EnrichmentsAfterSync}) für die abgeglichenen
     * Seiten. Mit Hintergrundaufträgen rechnet hier nichts und fragt kein Modell (Abnahme #1052: Anhalten hält
     * wirklich an): Ist für einen Anhang dieser Seiten laut Manifest etwas zu tun, wird nur vermerkt, dass es im Space
     * Arbeit gibt; den Teil startet das Ende der Anreicherung ({@link #holdsBack}). So erscheint nach einem Abgleich
     * ohne Arbeit keine leere Gruppe. Ohne Hintergrundaufträge (Dienst) wie bisher {@link #catchUp}.
     */
    public void afterSync(String sourceId, String spaceKey, Collection<String> pageIds, JobContext context) {
        if (background == null) {
            catchUp(sourceId, spaceKey, pageIds, context);
            return;
        }
        if (anyDue(sourceId, spaceKey, pageIds, context)) {
            note(new Partition(sourceId, spaceKey));
        }
    }

    /**
     * Ob für einen der abgelegten Dokument-Anhänge dieser Seiten etwas zu tun ist, am Manifest erkannt wie die
     * Abkürzung in {@link #process}: ohne einen Abschnitt zu lesen und ohne Prozessor.
     */
    private boolean anyDue(String sourceId, String spaceKey, Collection<String> pageIds, JobContext context) {
        if (!enabled.getAsBoolean() || enrichments.documentProcessorIds().isEmpty()) {
            return false;
        }
        for (Pending item : work(sourceId, spaceKey, pageIds)) {
            if (context != null) {
                context.checkpoint();
            }
            try {
                Optional<StoredExtraction> extraction = item.extraction();
                if (extraction.isPresent() && !manifest(item, extraction.get()).current()) {
                    return true;
                }
            } catch (RuntimeException e) {
                // Wie im Lauf: Ein Anhang, der sich nicht prüfen lässt, hält die übrigen nicht auf.
                LOG.log(System.Logger.Level.WARNING, "Analysestand eines Anhangs in {0} nicht geprüft ({1})",
                        sourceId, e.getClass().getName());
            }
        }
        return false;
    }

    private List<Pending> work(String sourceId, String spaceKey, Collection<String> pageIds) {
        List<Pending> result = new ArrayList<>();
        if (localDocuments != null) {
            for (String pageId : pageIds) {
                localPending(new PageKey(sourceId, pageId)).ifPresent(result::add);
            }
        }
        DocumentInventory source = assets;
        if (source != null) {
            source.documentExtractions().ifPresent(structured -> {
                for (Asset asset : source.indexedDocuments(sourceId, pageIds)) {
                    result.add(new Pending(DocumentKey.attachment(new PageKey(sourceId, asset.pageId()),
                            asset.attachmentId()), spaceKey, asset.sha256(), structured));
                }
            });
        }
        return result;
    }

    /**
     * Nach dem Verlust der KI-Freigabe dieser Seiten (ADR 0028, Abschnitt 4; Liste oder Quelle): holt für ihre Anhänge
     * wie {@link #catchUp} nach, was jetzt nicht mehr gilt, etwa semantische Absätze und Themenüberschriften, die mit
     * Modell entstanden; ohne Freigabe fragt dabei nichts mehr das Modell. Seiten, die {@code released} jetzt zulässt,
     * bleiben unberührt. Läuft im aufrufenden Thread, abgeschaltet geschieht nichts. Fehler bleiben hier und nennen im
     * Log nur Quelle und Klasse.
     *
     * @param released ob eine Seite jetzt freigegeben ist, etwa {@code AiRelease::mayProcess}
     */
    public void withdrawn(Collection<PageSnapshot> pages, Predicate<PageKey> released) {
        Objects.requireNonNull(released, "released");
        Map<String, Map<String, List<String>>> partitions = new LinkedHashMap<>();
        for (PageSnapshot page : pages) {
            if (released.test(page.key())) {
                continue;
            }
            partitions.computeIfAbsent(page.key().sourceId(), sourceId -> new LinkedHashMap<>())
                    .computeIfAbsent(page.metadata().partitionKey(), spaceKey -> new ArrayList<>())
                    .add(page.key().pageId());
        }
        partitions.forEach((sourceId, spaces) -> spaces.forEach((spaceKey, pageIds) -> {
            try {
                catchUp(sourceId, spaceKey, pageIds, null);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Analysen der Anhänge nach Rücknahme der Freigabe in {0} nicht "
                        + "neu gerechnet ({1})", sourceId, e.getClass().getName());
            }
        }));
    }

    /**
     * Rechnet die Dokumente eines Anhangs, deren Analysestand fehlt oder nicht aktuell ist. Erster Durchgang nur, wenn
     * das Manifest nicht schon genügt: Hash des Lesetextes je Dokument. Zweiter Durchgang nur für Dokumente mit
     * anderem Hash oder fehlenden Ständen. Gleicher Hash aus einer neuen Extraktion trägt nur die Extraktion nach.
     */
    /**
     * @param wait mit Netzzustand: Scheitert ein Abschnitt, während das Netz weg ist, endet der Anhang mit
     *             {@link Offline}, ohne etwas als gescheitert zu zählen; {@code null} wie bisher
     */
    private void process(Pending item, JobContext context,
                         com.aresstack.spaceharvester.application.network.NetworkWait wait) {
        com.aresstack.spaceharvester.application.network.NetworkState net = wait == null ? null : network;
        Optional<StoredExtraction> extraction = item.extraction();
        if (extraction.isEmpty()) {
            // Ohne lesbare Extraktion gilt der alte Stand weiter.
            return;
        }
        Manifest manifest = manifest(item, extraction.get());
        if (manifest.current()) {
            return;
        }
        long embeddingMaxChars = manifest.embeddingMaxChars();
        String basis = manifest.basis();
        Map<String, String> versions = manifest.versions();
        Map<DocumentKey, List<Enrichment>> stored = manifest.stored();
        // Erster Durchgang: Hash des Lesetextes je Dokument und die Abschnitte mit Text.
        Map<DocumentKey, MessageDigest> digests = new LinkedHashMap<>();
        Map<DocumentKey, Set<Integer>> seen = new HashMap<>();
        forEachSection(item, extraction.get(), context, (document, index, text) -> {
            digests.computeIfAbsent(document, key -> sha256()).update((text.inputSha256() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            seen.computeIfAbsent(document, key -> new HashSet<>()).add(index);
        });
        Map<DocumentKey, String> hashes = new HashMap<>();
        digests.forEach((document, digest) -> hashes.put(document, "sha256:"
                + HexFormat.of().formatHex(digest.digest())));
        Set<DocumentKey> work = new HashSet<>();
        for (Map.Entry<DocumentKey, String> hash : hashes.entrySet()) {
            List<Enrichment> results = stored.getOrDefault(hash.getKey(), List.of());
            // Andere Grenze oder inzwischen freigegeben: der zweite Durchgang entscheidet je Abschnitt und rechnet nur,
            // was an dieser Stelle nicht mehr gilt (Enrichments#refreshDocument).
            if (!covered(results, versions, hash.getValue(), seen.get(hash.getKey()))
                    || results.stream().anyMatch(result -> !Objects.equals(previousLimit(result.extraction()),
                    "embedding-max-chars=" + embeddingMaxChars))
                    || results.stream().anyMatch(enrichments::outdated)) {
                work.add(hash.getKey());
            }
        }
        // Zweiter Durchgang: nur die Dokumente mit anderem Text oder fehlenden Ständen.
        if (!work.isEmpty()) {
            // Grenze für das Embedding-Modell je Dokument, fest für diesen Lauf; gezählt über die Abschnitte davor.
            Map<DocumentKey, Long> offsets = new HashMap<>();
            forEachSection(item, extraction.get(), context, (document, index, text) -> {
                if (!work.contains(document)) {
                    return;
                }
                long offset = offsets.getOrDefault(document, 0L);
                offsets.put(document, offset + text.text().length());
                if (context != null) {
                    context.discovered(1);
                }
                // Ohne Extraktion: die trägt erst der Abschluss unten nach, wenn alle Abschnitte durchliefen.
                Enrichments.Run run = enrichments.refreshDocument(document, index, hashes.get(document), null, text,
                        stored.getOrDefault(document, List.of()), null, new DocumentPosition(offset,
                                embeddingMaxChars));
                if (wait != null && run.failed() > 0 && !net.online()) {
                    // Am Netz gescheitert: nichts rot, kein Versuch verbraucht. Das gespeicherte FAILED gälte beim
                    // Wiederholen als aktuell (gleiche Eingabe und Version) und der Abschnitt bekäme nie sein
                    // Embedding (Codex #1185): fort damit, die gelungenen Ergebnisse des Abschnitts bleiben.
                    forgetFailed(document, index);
                    if (context != null) {
                        context.processed(1);
                    }
                    throw new Offline();
                }
                if (wait != null && run.failed() == 0 && run.computed() > 0) {
                    wait.answered();
                }
                if (context != null) {
                    context.written(run.computed() - run.failed());
                    context.failed(run.failed());
                    context.processed(1);
                }
            });
        }
        // Alle Abschnitte durchliefen: Hash und Extraktion am Stand nachtragen, ohne zu rechnen; Weggefallenes fällt
        // weg. Ein Abbruch kommt nicht hierher, ein halb gerechnetes Dokument trägt also nie die Extraktion.
        Set<DocumentKey> documents = new LinkedHashSet<>(seen.keySet());
        documents.addAll(stored.keySet());
        for (DocumentKey document : documents) {
            Set<Integer> current = seen.getOrDefault(document, Set.of());
            String hash = hashes.get(document);
            Set<Integer> sections = new HashSet<>();
            for (Enrichment result : enrichments.store().find(document)) {
                sections.add(result.section());
                if (!current.contains(result.section())) {
                    continue;
                }
                if (!Objects.equals(result.revision(), hash) || !basis.equals(result.extraction())) {
                    enrichments.store().save(result.withBasis(hash, basis));
                }
            }
            sections.stream().filter(section -> !current.contains(section))
                    .forEach(section -> enrichments.store().removeSection(document, section));
        }
        enrichments.flush();
    }

    /** Entfernt die gescheiterten Ergebnisse eines Abschnitts und behält die gelungenen. */
    private void forgetFailed(DocumentKey document, int section) {
        List<Enrichment> results = enrichments.store().find(document).stream()
                .filter(result -> result.section() == section).toList();
        if (results.stream().noneMatch(result -> result.status() == Enrichment.Status.FAILED)) {
            return;
        }
        enrichments.store().removeSection(document, section);
        results.stream().filter(result -> result.status() != Enrichment.Status.FAILED)
                .forEach(enrichments.store()::save);
    }

    /**
     * Was ein Anhang laut Manifest hat, ohne einen Abschnitt zu lesen.
     *
     * @param basis   Kennung der Extraktion mit der Grenze für das Embedding-Modell: eine andere Grenze nimmt die
     *                Abkürzung über das Manifest nicht
     * @param stored  die gespeicherten Stände je Dokument des Anhangs
     * @param current ob das Manifest schon genügt: je erwartetem Dokument Stände aller zuständigen Prozessoren in ihrer
     *                Version für diese Extraktion, keiner veraltet; ohne erwartete Dokumente, wenn auch keine Stände
     *                wegfallen müssen. Die Extraktion steht erst am Stand, wenn das Dokument vollständig durchlief; ein
     *                abgebrochener Lauf genügt also nie
     */
    private record Manifest(String basis, long embeddingMaxChars, Map<String, String> versions,
                            Map<DocumentKey, List<Enrichment>> stored, boolean current) {
    }

    private Manifest manifest(Pending item, StoredExtraction extraction) {
        DocumentKey attachment = item.attachment();
        long embeddingMaxChars = settings.get().documentEmbeddingMaxChars();
        String basis = basis(extraction) + "|embedding-max-chars=" + embeddingMaxChars;
        Map<String, String> versions = enrichments.documentProcessorVersions(attachment.page());
        Map<DocumentKey, List<Enrichment>> stored = new HashMap<>();
        for (Enrichment enrichment : enrichments.store().findDocuments(attachment.page())) {
            if (enrichment.document().localOriginal() == attachment.localOriginal()
                    && enrichment.document().attachmentId().equals(attachment.attachmentId())) {
                stored.computeIfAbsent(enrichment.document(), key -> new ArrayList<>()).add(enrichment);
            }
        }
        List<DocumentKey> expected = item.extractions().documents(extraction).stream()
                .map(part -> part.map(entry -> attachment.child(entry.id())).orElse(attachment)).toList();
        boolean current = expected.isEmpty() ? stored.isEmpty() : expected.stream().allMatch(document -> {
            List<Enrichment> results = stored.get(document);
            return current(results, versions, basis)
                    && (results == null || results.stream().noneMatch(result -> enrichments.outdated(result)
                    || attachment.localOriginal() && result.status() == Enrichment.Status.FAILED));
        });
        return new Manifest(basis, embeddingMaxChars, versions, stored, current);
    }

    /**
     * Ob der Analysestand eines Dokuments zu dieser Extraktion ({@code basis}) gehört: je zuständigem Prozessor Stände in
     * seiner Version, alle mit dieser Extraktion.
     */
    private static boolean current(List<Enrichment> stored, Map<String, String> versions, String basis) {
        if (stored == null || stored.isEmpty()) {
            return versions.isEmpty();
        }
        for (Map.Entry<String, String> processor : versions.entrySet()) {
            List<Enrichment> results = stored.stream()
                    .filter(result -> result.processorId().equals(processor.getKey())).toList();
            if (results.isEmpty() || results.stream().anyMatch(result -> !result.processorVersion()
                    .equals(processor.getValue()) || !basis.equals(result.extraction()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Ob jeder Abschnitt mit Text ({@code sections}) je zuständigem Prozessor einen Stand in seiner Version für diesen
     * Hash des Lesetextes hat; fehlt einer, etwa nach einem Abbruch mitten im Dokument, wird das Dokument gerechnet.
     */
    private boolean covered(List<Enrichment> stored, Map<String, String> versions, String hash,
                                   Set<Integer> sections) {
        for (Map.Entry<String, String> processor : versions.entrySet()) {
            Set<Integer> done = new HashSet<>();
            if (stored != null) {
                for (Enrichment result : stored) {
                    if (result.processorId().equals(processor.getKey())
                            && result.processorVersion().equals(processor.getValue())
                            && hash.equals(result.revision()) && !enrichments.outdated(result)
                            && (!result.document().localOriginal() || result.status() == Enrichment.Status.OK)) {
                        done.add(result.section());
                    }
                }
            }
            if (!done.containsAll(sections)) {
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    private interface SectionVisitor {
        void visit(DocumentKey document, int index, PageText text);
    }

    /** Die Abschnitte mit Text in Reihenfolge, einzeln gelesen. */
    private static void forEachSection(Pending item, StoredExtraction extraction, JobContext context,
                                       SectionVisitor visitor) {
        DocumentKey attachment = item.attachment();
        try (Stream<DocumentExtractions.DocumentSection> sections =
                     item.extractions().documentSections(extraction)) {
            Iterator<DocumentExtractions.DocumentSection> found = sections.iterator();
            while (found.hasNext()) {
                if (context != null) {
                    context.checkpoint();
                }
                DocumentExtractions.DocumentSection section = found.next();
                PageText text = DocumentSectionText.project(section.section());
                if (!text.text().isBlank()) {
                    visitor.visit(section.part().map(part -> attachment.child(part.id())).orElse(attachment),
                            section.section().index(), text);
                }
            }
        }
    }

    /** Die Grenze, mit der ein Stand gebildet wurde, aus seiner Kennung; {@code null} ohne. */
    private static String previousLimit(String extraction) {
        if (extraction == null) {
            return null;
        }
        int at = extraction.lastIndexOf("|embedding-max-chars=");
        return at < 0 ? null : extraction.substring(at + 1);
    }

    /**
     * Undurchsichtige Kennung einer abgelegten Extraktion; nur verglichen, nie zerlegt. Mit Ablagezeitpunkt und
     * Zählern, denn ein erneutes Lesen mit weiteren Grenzen ersetzt die Abschnitte unter demselben Schlüssel.
     */
    static String basis(StoredExtraction extraction) {
        ExtractionKey key = extraction.key();
        return key.sha256() + "|" + key.extractor() + "|" + key.version() + "|" + key.model() + "|"
                + extraction.createdAt() + "|" + extraction.sections() + "|" + extraction.blocks() + "|"
                + extraction.chars();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String partition(String sourceId, String spaceKey) {
        return sourceId + "\0" + (spaceKey == null ? "" : spaceKey);
    }
}
