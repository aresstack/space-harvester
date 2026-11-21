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
import com.aresstack.spaceharvester.application.jobs.JobListener;
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
 * „Hintergrund“, anhaltbar und abbrechbar; der Teil bestimmt seine Arbeit allein aus dem Bestand, eine Meldung vermerkt
 * nur, dass es Arbeit gibt. Ohne Hintergrundaufträge (Dienst) als Auftrag {@link JobType#ENRICHMENT} der Quelle und des
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
 *       Partition; ein angehaltener Hintergrundteil rechnet sie beim Fortsetzen, auch nach einem Neustart.</li>
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
     * Aufträge, die Anhänge eines Space ablegen, neu lesen oder schon selbst analysieren: Solange einer davon wartet
     * oder läuft, startet kein Teil; sein Ende startet ihn (Abnahme #1052: eine Gruppe je Abgleich, nicht je Anhang).
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
    /** Partitionen, deren Teil gerade rechnet und danach noch einmal nachsieht; bewacht von {@code this}. */
    private final Set<Partition> active = new HashSet<>();
    /** Partitionen mit Arbeit, deren Start auf das Ende eines Abgleichs wartet; bewacht von {@code this}. */
    private final Set<Partition> deferred = new HashSet<>();

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
     * ({@link #catchUp}); eine Meldung ({@link #indexed}) vermerkt nur, dass es Arbeit gibt. Je Space ist höchstens ein
     * Teil offen: Wartet oder ruht einer, nimmt er die Arbeit mit; rechnet einer, sieht er danach noch einmal nach.
     * Solange ein Abgleich, eine Anreicherung, ein Anhangsauftrag oder ein Neulesen der Quelle für den Space wartet
     * oder läuft ({@link #INDEXING}), startet kein Teil; erst sein Ende (Auftragsmeldung, ADR 0027). Außerhalb davon
     * bündelt der Dienst Meldungen über {@code bundle}. Nach einem Neustart setzt ein unterbrochener Teil nur fort,
     * wenn die Analysen je Dokument eingeschaltet sind.
     *
     * @param pages  die Seiten eines Space mit gespeichertem Inhalt
     * @param bundle so lange werden Meldungen je Space gebündelt; {@link Duration#ZERO} startet im meldenden Thread
     */
    public void background(BackgroundJobs background, SpacePages pages, Duration bundle) {
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(bundle, "bundle");
        this.spacePages = Objects.requireNonNull(pages, "pages");
        background.register(new BackgroundKind(BACKGROUND_KIND, part -> JobTask.blocking(context ->
                analyse(new Partition(part.sourceId(), part.partitionKey()), context)), enabled));
        this.starts = bundle.isZero() ? null
                : new Coalescer<>("document-enrichments", bundle, (partition, ignored) -> startWhenIdle(partition));
        if (jobs != null) {
            this.indexingEnds = jobs.subscribeAll(new JobListener() {
                @Override
                public void changed(JobStatus status) {
                    if (INDEXING.contains(status.type()) && !indexing(status.state())) {
                        wake(status.sourceId(), status.partitionKey());
                    } else if (status.type() == JobType.BACKGROUND && status.state().terminal()
                            && BACKGROUND_KIND.equals(status.parameter(BackgroundJobs.KIND))) {
                        // Kam Arbeit, als der Teil schon fertig war, aber noch lief: jetzt ein neuer.
                        ended(new Partition(status.sourceId(), status.partitionKey()));
                    }
                }

                @Override
                public void removed(JobStatus last) {
                    if (INDEXING.contains(last.type())) {
                        wake(last.sourceId(), last.partitionKey());
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
    private record Pending(DocumentKey attachment, String spaceKey, String sha256, DocumentExtractions extractions) {
    }

    @Override
    public void indexed(PageAttachmentList list, Asset asset, DocumentExtractions extractions) {
        if (!enabled.getAsBoolean() || enrichments.documentProcessorIds().isEmpty()) {
            return;
        }
        if (background != null) {
            note(new Partition(list.sourceId(), list.spaceKey()));
            return;
        }
        PageKey page = new PageKey(list.sourceId(), list.attachments().pageId());
        Pending item = new Pending(DocumentKey.attachment(page, asset.attachmentId()), list.spaceKey(),
                asset.sha256(), Objects.requireNonNull(extractions, "extractions"));
        String partition = partition(list.sourceId(), list.spaceKey());
        String job;
        synchronized (this) {
            // Ein erneut gemeldeter Anhang rückt nach hinten und rechnet mit seinem neuesten Stand.
            pending.remove(item.attachment().value());
            pending.put(item.attachment().value(), item);
            job = scheduled.get(partition);
        }
        if (jobs == null) {
            drain(list.sourceId(), list.spaceKey(), null);
            return;
        }
        if (job != null && alive(job)) {
            return;
        }
        try {
            String id = submit(list.sourceId(), list.spaceKey());
            synchronized (this) {
                scheduled.put(partition, id);
            }
        } catch (JobCapacityExceededException | IllegalStateException e) {
            LOG.log(System.Logger.Level.INFO, "Analysen der Anhänge in {0} nicht eingeplant ({1}); die nächste "
                    + "Meldung holt sie nach", list.sourceId(), e.getClass().getSimpleName());
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

    /** Nach dem Ende eines Teils: Arbeit, die er nicht mehr gesehen hat, bekommt einen neuen. */
    private void ended(Partition partition) {
        boolean open;
        synchronized (this) {
            open = noted.contains(partition) && !active.contains(partition);
        }
        if (open) {
            schedule(partition);
        }
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
     * wartet, ruht oder beginnt eben (er nimmt sie mit) oder ein Abgleich der Partition läuft noch (dessen Ende startet
     * ihn). Endet ein Teil, kurz nachdem er zuletzt nachsah, startet sein Ende den nächsten ({@link #ended}).
     */
    private void startWhenIdle(Partition partition) {
        BackgroundJobs hosted = background;
        if (hosted == null) {
            return;
        }
        synchronized (this) {
            if (!noted.contains(partition) || active.contains(partition)) {
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

    /** Ob ein Abgleich, eine Anreicherung, ein Anhangsauftrag oder ein Neulesen der Partition wartet oder läuft. */
    private boolean indexing(Partition partition) {
        if (jobs == null) {
            return false;
        }
        for (JobType type : INDEXING) {
            for (JobStatus status : jobs.listAll(type)) {
                if (indexing(status.state()) && partition.sourceId().equals(status.sourceId())
                        && (status.partitionKey() == null || status.partitionKey().equals(partition.spaceKey()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Wartend oder laufend; ein angehaltener Abgleich hält die Analysen nicht auf. */
    private static boolean indexing(JobState state) {
        return state == JobState.QUEUED || state == JobState.RUNNING;
    }

    /**
     * Ein Hintergrundteil: die Anhänge der Seiten des Space ohne aktuelle Analyse, aus dem Bestand; kam währenddessen
     * eine Meldung, noch ein Durchgang. Nach Anhalten, Abbruch oder Neustart beginnt er von vorn, Aktuelles kostet dann
     * nur den Blick auf den Analysestand.
     */
    private void analyse(Partition partition, JobContext context) {
        synchronized (this) {
            active.add(partition);
            noted.remove(partition);
        }
        boolean idle = false;
        try {
            while (true) {
                SpacePages pages = spacePages;
                catchUp(partition.sourceId(), partition.spaceKey(), pages == null ? List.of()
                        : pages.pageIds(partition.sourceId(), partition.spaceKey()), context);
                synchronized (this) {
                    if (!noted.remove(partition)) {
                        active.remove(partition);
                        idle = true;
                        return;
                    }
                }
            }
        } finally {
            if (!idle) {
                synchronized (this) {
                    active.remove(partition);
                }
            }
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
                if (attachment.page().equals(page) && !attachmentIds.contains(attachment.attachmentId().orElse(""))) {
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
                    process(item, context);
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
        if (removals(item) != before) {
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
        DocumentInventory source = assets;
        if (source == null || !enabled.getAsBoolean() || enrichments.documentProcessorIds().isEmpty()) {
            return;
        }
        Optional<DocumentExtractions> structured = source.documentExtractions();
        if (structured.isEmpty()) {
            return;
        }
        List<Asset> documents = source.indexedDocuments(sourceId, pageIds);
        if (documents.isEmpty()) {
            return;
        }
        if (context != null) {
            context.phase(COMPONENT, PHASE);
        }
        try {
            for (Asset asset : documents) {
                if (context != null) {
                    context.checkpoint();
                }
                PageKey page = new PageKey(sourceId, asset.pageId());
                Pending item = new Pending(DocumentKey.attachment(page, asset.attachmentId()), spaceKey,
                        asset.sha256(), structured.get());
                int removed = removals(item);
                try {
                    process(item, context);
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
    private void process(Pending item, JobContext context) {
        Optional<StoredExtraction> extraction = item.extractions().readable(item.sha256());
        if (extraction.isEmpty()) {
            // Ohne lesbare Extraktion gilt der alte Stand weiter.
            return;
        }
        DocumentKey attachment = item.attachment();
        // Mit der Grenze für das Embedding-Modell: eine andere Grenze nimmt die Abkürzung über das Manifest nicht.
        long embeddingMaxChars = settings.get().documentEmbeddingMaxChars();
        String basis = basis(extraction.get()) + "|embedding-max-chars=" + embeddingMaxChars;
        Map<String, String> versions = enrichments.documentProcessorVersions(attachment.page());
        Map<DocumentKey, List<Enrichment>> stored = new HashMap<>();
        for (Enrichment enrichment : enrichments.store().findDocuments(attachment.page())) {
            if (enrichment.document().attachmentId().equals(attachment.attachmentId())) {
                stored.computeIfAbsent(enrichment.document(), key -> new ArrayList<>()).add(enrichment);
            }
        }
        List<DocumentKey> expected = item.extractions().documents(extraction.get()).stream()
                .map(part -> part.map(entry -> attachment.child(entry.id())).orElse(attachment)).toList();
        // Die Extraktion steht erst am Stand, wenn das Dokument vollständig durchlief; ein abgebrochener Lauf genügt
        // dieser Abkürzung also nie.
        if (!expected.isEmpty() && expected.stream().allMatch(document -> current(stored.get(document), versions,
                basis) && stored.get(document).stream().noneMatch(enrichments::outdated))) {
            return;
        }
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
                            && hash.equals(result.revision()) && !enrichments.outdated(result)) {
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
    private static String basis(StoredExtraction extraction) {
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
