package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConversionLimitExceededException;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheGenerationChangedException;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.CacheUnavailableException;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.SyncUnavailableException;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Startet Exporte eines Space als Auftrag und findet ihre Artefakte. Der Export liest die Seitenliste
 * einer Generation stapelweise, lädt je Seite den Snapshot und schreibt ihn sofort weiter; im Speicher bleiben nur IDs
 * für den Baum.
 *
 * <p>Der Cache ist dabei keine Voraussetzung (ADR 0010): Liegt für den Space keine bestätigte Generation vor oder
 * verlangt der Auftrag Aktualität ({@link ExportRequest#refresh()}), liest der Export den Space zuerst selbst ein
 * ({@link SpaceProvisioning}), im selben Auftrag und auf demselben Ausführungsplatz. Eine vorhandene bestätigte
 * Generation wird sonst wiederverwendet. Raw-First bleibt: exportiert wird immer, was die bestätigte Generation im
 * Cache hält, mit Originalpayload, Revision und Hashes.
 *
 * <p>Jede Anmeldeart nutzt denselben Ablauf; der Export enthält genau die Seiten, die der Auftraggeber lesen darf
 * ({@link ExportRequest#visible()}). Nicht lesbare Seiten erscheinen weder im Artefakt noch im Manifest, in Warnungen
 * oder Zählern. Ein Artefakt ist nur {@code complete}, wenn es aus einer bestätigten Generation stammt und keine
 * lesbare Seite fehlt oder beim Einlesen scheiterte. Ein abgebrochener oder gescheiterter Export veröffentlicht nichts.
 *
 * <p>Angefragte Formate (#30) entstehen ganz am Ende aus denselben Seiten: nach dem Raw-First-Artefakt liest jeder
 * {@link ExportConverter} die exportierten Seiten noch einmal einzeln aus dem Cache, in Baumreihenfolge, und prüft
 * dabei, dass jede unverändert ist. Quelldaten und Cache bleiben unberührt.
 *
 * <p>Artefakte leben so lange wie ihr Auftrag ({@code space-harvester.jobs.retention}): {@link #purge()} entfernt
 * beim Start und vor jedem neuen Export Artefakte ohne Auftrag sowie Reste von Läufen, die nie fertig wurden.
 */
public class Exports {

    /** Seiten je Lesestapel. */
    public static final int BATCH_SIZE = 100;
    private volatile java.util.function.BiConsumer<String, String> published = (exportId, owner) -> {
    };

    private static final System.Logger LOG = System.getLogger(Exports.class.getName());
    private static final String COMPONENT = "Exports";

    private final Jobs jobs;
    private final PageCacheReader cache;
    private final ExportArtifactWriter writer;
    private final ExportConverters converters;
    private final SpaceProvisioning provisioning;
    private final Clock clock;

    public Exports(Jobs jobs, PageCacheReader cache, ExportArtifactWriter writer, Clock clock) {
        this(jobs, cache, writer, ExportConverters.none(), clock);
    }

    public Exports(Jobs jobs, PageCacheReader cache, ExportArtifactWriter writer, ExportConverters converters,
                   Clock clock) {
        this(jobs, cache, writer, converters, SpaceProvisioning.unavailable(), clock);
    }

    /** @param provisioning liest fehlende oder veraltete Spaces im Exportauftrag ein */
    public Exports(Jobs jobs, PageCacheReader cache, ExportArtifactWriter writer, ExportConverters converters,
                   SpaceProvisioning provisioning, Clock clock) {
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.converters = Objects.requireNonNull(converters, "converters");
        this.clock = Objects.requireNonNull(clock, "clock");
        purgeQuietly();
    }

    /**
     * Das Standardformat eines Exports (Angelo, 17.10.): JSON. REST und Desktop-App fordern es an, wenn niemand ein
     * Format wählt; Markdown, DOCX und PDF sind zusätzlich wählbar. Das Raw-First-Artefakt entsteht immer.
     */
    public static final String DEFAULT_FORMAT = "json";

    /** Verfügbare Formate über das Raw-First-Artefakt hinaus. */
    public List<String> formats() {
        return converters.formats().stream().sorted().toList();
    }

    /**
     * Nimmt einen Export an, ohne auf ihn zu warten; die Job-ID ist zugleich die Export-ID.
     *
     * @param owner auslösender Client; nur er sieht Auftrag und Artefakt
     * @throws com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException wenn zu viele Aufträge offen
     *                                                                                   sind
     */
    public JobStatus start(String sourceId, String spaceKey, String owner) {
        return start(sourceId, spaceKey, owner, List.of());
    }

    /**
     * Wie {@link #start(String, String, String)}, zusätzlich mit abgeleiteten Dateien in den angefragten Formaten.
     *
     * @param formats Formate aus {@link #formats()}; leer für nur das Raw-First-Artefakt
     * @throws com.aresstack.spaceharvester.application.sources.InvalidQueryException bei einem unbekannten Format,
     *                                                                                 bevor ein Auftrag entsteht
     */
    public JobStatus start(String sourceId, String spaceKey, String owner, List<String> formats) {
        return start(ExportRequest.of(sourceId, spaceKey, owner, formats));
    }

    /**
     * Nimmt einen Export an, ohne auf ihn zu warten; die Job-ID ist zugleich die Export-ID.
     *
     * @throws com.aresstack.spaceharvester.application.sources.InvalidQueryException bei einem unbekannten Format,
     *                                                                                 bevor ein Auftrag entsteht
     * @throws com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException wenn zu viele Aufträge offen
     *                                                                                   sind
     */
    public JobStatus start(ExportRequest request) {
        Objects.requireNonNull(request, "request");
        List<ExportConverter> selected = converters.select(request.formats());
        purgeQuietly();
        writer.requireCapacity();
        return jobs.submit(JobType.EXPORT, request.sourceId(), request.spaceKey(), request.owner(),
                JobTask.blocking(context -> export(context, request, selected)));
    }

    /**
     * Das veröffentlichte Artefakt des eigenen Exports oder leer, solange es keines gibt.
     *
     * @throws com.aresstack.spaceharvester.application.jobs.UnknownJobException wenn es keinen Export mit dieser ID für
     *                                                                           diesen Client gibt
     */
    public Optional<ExportArtifact> artifact(String exportId, String owner) {
        JobStatus status = jobs.status(exportId, owner);
        if (status.type() != JobType.EXPORT) {
            return Optional.empty();
        }
        return writer.find(exportId);
    }

    /**
     * Die Seiten-IDs des veröffentlichten Artefakts des eigenen Exports, damit vor jeder Auslieferung die Leserechte
     * je Seite geprüft werden können.
     *
     * @return leer, solange es kein Artefakt gibt
     * @throws com.aresstack.spaceharvester.application.jobs.UnknownJobException wenn es keinen Export mit dieser ID für
     *                                                                           diesen Client gibt
     */
    public Optional<List<String>> pageIds(String exportId, String owner) {
        if (jobs.status(exportId, owner).type() != JobType.EXPORT) {
            return Optional.empty();
        }
        return writer.pageIds(exportId);
    }

    /**
     * Eine Datei des veröffentlichten Artefakts des eigenen Exports.
     *
     * @return leer, solange es kein Artefakt oder keine Datei dieses Namens gibt
     * @throws com.aresstack.spaceharvester.application.jobs.UnknownJobException wenn es keinen Export mit dieser ID für
     *                                                                           diesen Client gibt
     */
    public Optional<InputStream> artifactFile(String exportId, String owner, String name) {
        if (jobs.status(exportId, owner).type() != JobType.EXPORT) {
            return Optional.empty();
        }
        return writer.open(exportId, name);
    }

    /**
     * Entfernt veröffentlichte Artefakte, deren Auftrag nicht mehr existiert (nach Ablauf der Aufbewahrung oder
     * Verlust der Job-Ablage), und Artefakte im Entstehen, deren Auftrag nicht mehr läuft (Abbruch des Dienstes). Ein
     * Artefakt eines laufenden oder wartenden Exports bleibt immer.
     *
     * @return Anzahl entfernter Artefakte
     */
    public int purge() {
        jobs.purgeExpired();
        int removed = 0;
        for (String exportId : writer.published()) {
            if (!exportJob(exportId)) {
                writer.delete(exportId);
                removed++;
            }
        }
        for (String exportId : writer.unpublished()) {
            if (!running(exportId)) {
                writer.discard(exportId);
                removed++;
            }
        }
        return removed;
    }

    /** Aufräumen darf keinen Export verhindern; was liegen bleibt, entfernt der nächste Lauf. */
    private void purgeQuietly() {
        try {
            purge();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Alte Exportartefakte nicht vollständig entfernt: {0}",
                    e.getClass().getSimpleName());
        }
    }

    private boolean exportJob(String exportId) {
        try {
            return jobs.status(exportId).type() == JobType.EXPORT;
        } catch (UnknownJobException e) {
            return false;
        }
    }

    private boolean running(String exportId) {
        try {
            JobStatus status = jobs.status(exportId);
            return status.type() == JobType.EXPORT && !status.state().terminal();
        } catch (UnknownJobException e) {
            return false;
        }
    }

    private void export(JobContext context, ExportRequest request, List<ExportConverter> selected) {
        try {
            requireAuthorized(request);
            Prepared prepared = prepare(context, request);
            exportGeneration(context, request, prepared, selected);
        } catch (UnknownSourceException e) {
            throw new JobFailedException("UNKNOWN_SOURCE", "Die Quelle " + request.sourceId()
                    + " ist nicht konfiguriert", e);
        } catch (SyncUnavailableException e) {
            throw new JobFailedException("PROVISIONING_UNAVAILABLE", "Der Space kann nicht von der Quelle "
                    + "eingelesen werden, weil Cache-Speicher oder Inhaltsabruf nicht angebunden sind", e);
        } catch (CacheUnavailableException e) {
            throw new JobFailedException("CACHE_UNAVAILABLE", "Der Seitencache ist nicht lesbar", e);
        } catch (CacheGenerationChangedException e) {
            throw new JobFailedException("GENERATION_CHANGED",
                    "Die Cache-Generation wurde während des Exports ersetzt; bitte neu starten", e);
        } catch (ExportStorageFullException e) {
            throw new JobFailedException("EXPORT_STORAGE_FULL", e.getMessage(), e);
        }
    }

    /**
     * Die Freigabe des Auftraggebers muss über den ganzen Auftrag gelten; ein abgelaufenes oder entzogenes Recht wird
     * nie durch das Erntekonto ersetzt, der Export scheitert dann ohne Artefakt.
     */
    private static void requireAuthorized(ExportRequest request) {
        if (!request.authorized().getAsBoolean()) {
            throw new JobFailedException("ACCESS_REVOKED", "Die Freigabe für den Export dieses Space gilt nicht mehr "
                    + "(Recht entzogen oder Anmeldung abgelaufen); es entstand kein Export");
        }
    }

    /**
     * Bestand für den Export: wiederverwendet oder im Auftrag eingelesen.
     *
     * @param unrefreshed Seiten, deren Abruf beim Einlesen scheiterte; nur intern, nach außen zählen nur lesbare
     */
    /** @param counted ob das Einlesen die lesbaren Seiten schon als gefunden und beschafft gezählt hat */
    private record Prepared(Instant startedAt, CacheGeneration generation, List<PageHeader> unrefreshed,
                            List<String> warnings, boolean counted) {
    }

    /**
     * Sorgt für eine Generation, aus der exportiert wird: eine vorhandene bestätigte, wenn keine Aktualität verlangt
     * ist, sonst eine im Auftrag neu eingelesene. Scheitert das Einlesen ohne bestätigte Generation, gibt es nur dann
     * einen (unvollständigen) Export aus dem bisherigen Bestand, wenn keine Aktualität verlangt war.
     */
    private Prepared prepare(JobContext context, ExportRequest request) {
        context.phase(COMPONENT, "Bestand prüfen");
        Instant startedAt = clock.instant();
        String sourceId = request.sourceId();
        String spaceKey = request.spaceKey();
        Optional<CacheGeneration> current = cache.currentGeneration(sourceId, spaceKey);
        boolean reusable = current.filter(generation -> generation.state() == CacheState.CONFIRMED).isPresent();
        if (reusable && !request.refresh()) {
            context.record(JobEvent.Draft.info(COMPONENT, "Bestätigte Generation " + current.get().id()
                    + " wird wiederverwendet"));
            return new Prepared(startedAt, current.get(), List.of(), List.of(), false);
        }
        if (!provisioning.available()) {
            if (current.isEmpty() || request.refresh()) {
                throw new SyncUnavailableException("kein Einlesen");
            }
            return new Prepared(startedAt, current.get(), List.of(), List.of(), false);
        }
        List<String> warnings = new ArrayList<>();
        context.phase(COMPONENT, "Einlesen");
        context.record(JobEvent.Draft.info(COMPONENT, current.isEmpty() ? "Kein Bestand im Cache; der Space wird von "
                + "der Quelle eingelesen" : request.refresh() ? "Aktueller Stand verlangt; der Space wird von der Quelle "
                + "eingelesen" : "Bestand nicht bestätigt (" + current.get().state() + "); der Space wird von der "
                + "Quelle eingelesen"));
        SpaceProvisioning.Provisioned provisioned = provisioning.provision(
                new ProvisioningContext(context), sourceId, spaceKey, request.visible());
        context.checkpoint();
        if (!provisioned.confirmed()) {
            if (current.isEmpty() || request.refresh()) {
                throw new JobFailedException("SYNC_INCOMPLETE", "Space " + spaceKey + " der Quelle " + sourceId
                        + " ließ sich nicht vollständig einlesen (Inventur abgebrochen oder widersprüchlich); "
                        + "es entstand kein Export");
            }
            warnings.add("Neues Einlesen ohne bestätigte Generation; exportiert wird der bisherige Bestand");
        }
        if (provisioned.superseded()) {
            warnings.add("Ein gleichzeitiger Abgleich hat zuerst bestätigt; exportiert wird dessen Generation");
        }
        CacheGeneration generation = cache.currentGeneration(sourceId, spaceKey).orElseThrow(() ->
                new JobFailedException("SYNC_INCOMPLETE", "Nach dem Einlesen liegt für Space " + spaceKey
                        + " der Quelle " + sourceId + " keine Generation vor"));
        return new Prepared(startedAt, generation, provisioned.failedPages(), warnings, true);
    }

    private void exportGeneration(JobContext context, ExportRequest request, Prepared prepared,
                                  List<ExportConverter> selected) {
        String sourceId = request.sourceId();
        String spaceKey = request.spaceKey();
        Instant startedAt = prepared.startedAt();
        CacheGeneration generation = prepared.generation();
        ExportScope scope = new ExportScope(sourceId, spaceKey, generation);
        List<TreeEntry> entries = new ArrayList<>();
        List<PageFailure> failures = new ArrayList<>();
        Map<String, ListedPage> exported = new HashMap<>();
        Set<String> hidden = new HashSet<>();
        // Nur lesbare gescheiterte Seiten zählen, auch solche, die nie in den Cache kamen und deshalb in keiner
        // Seitenliste stehen.
        List<PageHeader> failedVisible = prepared.unrefreshed().stream().filter(request.visible()).toList();
        Set<String> failedIds = new HashSet<>();
        failedVisible.forEach(header -> failedIds.add(header.key().pageId()));
        int unrefreshed = failedVisible.size();
        context.phase(COMPONENT, "Seiten exportieren");
        try (ExportArtifactWriter.Session session = writer.begin(context.jobId(), scope)) {
            String after = null;
            List<ListedPage> batch;
            do {
                context.checkpoint();
                batch = cache.pages(sourceId, spaceKey, generation.id(), after, BATCH_SIZE);
                for (ListedPage listed : batch) {
                    context.checkpoint();
                    if (after != null && listed.pageId().compareTo(after) <= 0) {
                        throw new JobFailedException("INCONSISTENT_READ", "Seitenliste des Cache ist nicht "
                                + "aufsteigend sortiert (Seite " + listed.pageId() + ")");
                    }
                    after = listed.pageId();
                    if (!request.visible().test(listed.header())) {
                        // Nicht lesbar: gehört nicht zur Sicht des Auftraggebers. Weder Artefakt, Manifest, Warnung
                        // noch Zähler verraten, dass es die Seite gibt.
                        hidden.add(listed.pageId());
                        continue;
                    }
                    if (!prepared.counted()) {
                        context.discovered(1);
                    }
                    failedIds.remove(listed.pageId());
                    PageFailure failure = exportPage(session, listed, generation);
                    if (!prepared.counted()) {
                        context.processed(1);
                    }
                    if (failure == null) {
                        entries.add(new TreeEntry(listed.pageId(), listed.parentPageId()));
                        exported.put(listed.pageId(), listed);
                        context.written(1);
                    } else {
                        failures.add(failure);
                        context.failed(1);
                    }
                }
            } while (batch.size() == BATCH_SIZE);
            // Lesbar, aber ohne Stand im Cache: fehlt im Export und zählt als gescheitert.
            if (!prepared.counted()) {
                context.discovered(failedIds.size());
            }
            context.failed(failedIds.size());
            context.checkpoint();
            PageTree tree = PageTreeBuilder.build(entries);
            List<String> warnings = new ArrayList<>(prepared.warnings());
            if (unrefreshed > 0) {
                warnings.add(unrefreshed + " Seiten ließen sich beim Einlesen nicht abrufen; sie fehlen oder sind auf "
                        + "altem Stand");
            }
            for (PageTree.Anomaly anomaly : tree.anomalies()) {
                ListedPage page = exported.get(anomaly.pageId());
                if (anomaly.kind() == PageTree.Kind.MISSING_PARENT && page != null
                        && hidden.contains(page.parentPageId())) {
                    // Die Elternseite ist nur nicht lesbar; das ist kein Mangel des Exports.
                    continue;
                }
                warnings.add("Seite " + anomaly.pageId() + " ist im Baum Wurzel: " + switch (anomaly.kind()) {
                    case MISSING_PARENT -> "Elternseite fehlt im Export";
                    case CYCLE -> "Zyklus in der Elternbeziehung aufgebrochen";
                });
            }
            if (generation.state() != CacheState.CONFIRMED) {
                warnings.add("Cache-Generation " + generation.id() + " ist " + generation.state()
                        + "; das Artefakt ist kein bestätigter vollständiger Stand");
            }
            convert(context, session, scope, tree, exported, selected);
            requireAuthorized(request);
            // Auch je Seite: Wer eine exportierte Seite inzwischen nicht mehr lesen darf, bekommt kein Artefakt.
            if (exported.values().stream().anyMatch(page -> !request.visible().test(page.header()))) {
                throw new JobFailedException("ACCESS_REVOKED", "Die Freigabe für einzelne Seiten dieses Exports gilt "
                        + "nicht mehr; es entstand kein Export");
            }
            context.phase(COMPONENT, "Ablegen");
            for (String warning : warnings) {
                context.record(JobEvent.Draft.warning(JobEvent.Category.STORAGE, COMPONENT, "EXPORT_WARNING",
                        warning));
            }
            if (!failures.isEmpty()) {
                context.record(JobEvent.Draft.warning(JobEvent.Category.STORAGE, COMPONENT, failures.get(0).code(),
                        failures.size() + " Seiten fehlen im Export, etwa Seite " + failures.get(0).pageId() + ": "
                                + failures.get(0).message()));
            }
            // Vollständig heißt: alle für den Auftraggeber lesbaren Seiten aus einer bestätigten Generation.
            boolean complete = generation.state() == CacheState.CONFIRMED && failures.isEmpty() && unrefreshed == 0;
            session.publish(tree, new ExportSummary(context.jobId(), scope, startedAt, clock.instant(),
                    entries.size(), failures, warnings, complete));
            afterPublish(context);
        }
    }

    /**
     * Folgearbeit, sobald ein Datenbestand veröffentlicht ist, etwa das Beschaffen der Bilder als eigener Auftrag; der
     * Export wartet nicht darauf, und ein Fehler darin lässt ihn nie scheitern.
     *
     * @param listener bekommt Export-ID und Auftraggeber
     */
    public void onPublished(java.util.function.BiConsumer<String, String> listener) {
        this.published = Objects.requireNonNull(listener, "listener");
    }

    private void afterPublish(JobContext context) {
        try {
            published.accept(context.jobId(), context.owner());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Folgearbeit nach Export {0} nicht eingeplant ({1})",
                    context.jobId(), e.getClass().getSimpleName());
        }
    }

    /** @return {@code null}, wenn die Seite geschrieben wurde, sonst warum nicht */
    private PageFailure exportPage(ExportArtifactWriter.Session session, ListedPage listed,
                                          CacheGeneration generation) {
        if (listed.rawSha256() == null) {
            return new PageFailure(listed.pageId(), "PAYLOAD_MISSING",
                    "Für Seite " + listed.pageId() + " liegt noch keine Payload im Cache");
        }
        Optional<CachedPage> cached = cache.page(listed.header().key());
        if (cached.isEmpty()) {
            return new PageFailure(listed.pageId(), "PAYLOAD_MISSING",
                    "Seite " + listed.pageId() + " ist nicht mehr im Cache");
        }
        CachedPage page = cached.get();
        if (!page.generation().id().equals(generation.id())
                || !page.snapshot().payload().rawSha256().equals(listed.rawSha256())) {
            return new PageFailure(listed.pageId(), "CHANGED_DURING_EXPORT",
                    "Seite " + listed.pageId() + " wurde während des Exports im Cache geändert");
        }
        session.page(listed.header(), page.snapshot());
        return null;
    }

    /** Erzeugt die angefragten Formate aus den exportierten Seiten; scheitert eines, scheitert der Export. */
    private void convert(JobContext context, ExportArtifactWriter.Session session, ExportScope scope, PageTree tree,
                         Map<String, ListedPage> exported, List<ExportConverter> selected) {
        if (selected.isEmpty()) {
            return;
        }
        String inputSha256 = inputSha256(tree, exported);
        ConversionInput input = new CachedConversionInput(context, scope, tree, exported);
        for (ExportConverter converter : selected) {
            context.checkpoint();
            context.phase(COMPONENT, "Konvertieren (" + converter.format() + ")");
            Conversion conversion = new Conversion(converter.format(), converter.version(), converter.mediaType(),
                    converter.fileName(), inputSha256, Map.of());
            try {
                session.converted(conversion, out -> converter.convert(input,
                        new LimitedOutputStream(out, converter.fileName(), converters.maxBytes())));
            } catch (JobFailedException | JobCancelledException | CacheUnavailableException
                     | CacheGenerationChangedException | ExportStorageFullException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                    if (cause instanceof ExportStorageFullException full) {
                        throw full;
                    }
                }
                ConversionLimitExceededException tooLarge = limitExceeded(e);
                if (tooLarge != null) {
                    throw new JobFailedException("CONVERSION_TOO_LARGE", tooLarge.getMessage(), e);
                }
                throw new JobFailedException("CONVERSION_FAILED",
                        "Format " + converter.format() + " ließ sich nicht erzeugen", e);
            }
        }
    }

    /** Die Grenze kann ein Converter in eine eigene Ausnahme gehüllt haben. */
    private static ConversionLimitExceededException limitExceeded(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConversionLimitExceededException limit) {
                return limit;
            }
        }
        return null;
    }

    /** {@link Conversion#INPUT_HASH_VERSION} über die exportierten Seiten, ohne den Cache erneut zu lesen. */
    private static String inputSha256(PageTree tree, Map<String, ListedPage> exported) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
        walk(tree, (pageId, depth) -> {
            ListedPage listed = exported.get(pageId);
            digest.update((depth + "\t" + pageId + "\t" + listed.header().metadataHash() + "\t" + listed.rawSha256()
                    + "\n").getBytes(StandardCharsets.UTF_8));
        });
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    /** Besucht den Baum in Tiefensuche ohne Rekursion; auch sehr tiefe Bäume sprengen keinen Stack. */
    private static void walk(PageTree tree, PositionVisitor visitor) {
        Deque<Position> stack = new ArrayDeque<>();
        for (int i = tree.roots().size() - 1; i >= 0; i--) {
            stack.push(new Position(tree.roots().get(i), 0));
        }
        while (!stack.isEmpty()) {
            Position position = stack.pop();
            visitor.visit(position.node().pageId(), position.depth());
            List<PageTree.Node> children = position.node().children();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(new Position(children.get(i), position.depth() + 1));
            }
        }
    }

    private record Position(PageTree.Node node, int depth) {
    }

    @FunctionalInterface
    private interface PositionVisitor {
        void visit(String pageId, int depth);
    }

    /** Liest die exportierten Seiten einzeln aus dem Cache und besteht darauf, dass sie unverändert sind. */
    private final class CachedConversionInput implements ConversionInput {

        private final JobContext context;
        private final ExportScope scope;
        private final PageTree tree;
        private final Map<String, ListedPage> exported;

        CachedConversionInput(JobContext context, ExportScope scope, PageTree tree, Map<String, ListedPage> exported) {
            this.context = context;
            this.scope = scope;
            this.tree = tree;
            this.exported = exported;
        }

        @Override
        public String sourceId() {
            return scope.sourceId();
        }

        @Override
        public String spaceKey() {
            return scope.spaceKey();
        }

        @Override
        public PageTree tree() {
            return tree;
        }

        @Override
        public List<PageHeader> headers() {
            List<PageHeader> headers = new ArrayList<>(exported.size());
            walk(tree, (pageId, depth) -> headers.add(exported.get(pageId).header()));
            return headers;
        }

        @Override
        public void forEachPage(Consumer<ConvertiblePage> action) {
            walk(tree, (pageId, depth) -> {
                context.checkpoint();
                ListedPage listed = exported.get(pageId);
                CachedPage page = cache.page(listed.header().key())
                        .filter(cached -> cached.generation().id().equals(scope.generation().id()))
                        .filter(cached -> cached.snapshot().payload().rawSha256().equals(listed.rawSha256()))
                        .filter(cached -> cached.header().metadataHash().equals(listed.header().metadataHash()))
                        .orElseThrow(() -> new JobFailedException("CHANGED_DURING_EXPORT",
                                "Seite " + pageId + " wurde während der Konvertierung im Cache geändert"));
                action.accept(new ConvertiblePage(page.header(), page.snapshot(), depth));
            });
        }
    }

    /**
     * Sicht des Einlesens auf den Exportauftrag: Abbruch, Laufzeitgrenze, Phasen und die Zähler „gefunden“
     * ({@code discovered}) und „beschafft“ ({@code processed}) greifen. Das Einlesen zählt dabei nur Seiten, die der
     * Auftraggeber lesen darf ({@link SpaceProvisioning#provision(JobContext, String, String, java.util.function.Predicate)}).
     * „Geschrieben“ und „gescheitert“ gehören dem Export, damit keine Seite doppelt zählt.
     */
    private static final class ProvisioningContext implements JobContext {

        private final JobContext context;

        ProvisioningContext(JobContext context) {
            this.context = context;
        }

        @Override
        public String jobId() {
            return context.jobId();
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
        public void discovered(long pages) {
            context.discovered(pages);
        }

        @Override
        public void processed(long pages) {
            context.processed(pages);
        }

        @Override
        public void written(long pages) {
        }

        @Override
        public void failed(long pages) {
        }

        /** Phasen nennen keine Seiten und dürfen durch; Meldungen des Einlesens nicht. */
        @Override
        public void phase(String component, String phase) {
            context.phase(component, phase);
        }
    }

    /** Bricht ab, sobald mehr als erlaubt geschrieben würde; nichts wird abgeschnitten. */
    private static final class LimitedOutputStream extends FilterOutputStream {

        private final String fileName;
        private final long maxBytes;
        private long written;

        LimitedOutputStream(OutputStream out, String fileName, long maxBytes) {
            super(out);
            this.fileName = fileName;
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) throws IOException {
            reserve(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            reserve(len);
            out.write(b, off, len);
        }

        private void reserve(int len) throws ConversionLimitExceededException {
            if (written + len > maxBytes) {
                throw new ConversionLimitExceededException(fileName, maxBytes);
            }
            written += len;
        }

        @Override
        public void close() throws IOException {
            // Den Strom schließt der Writer.
            flush();
        }
    }
}
