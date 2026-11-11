package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.AssetStore;
import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.reading.ReadingViewStore;
import com.aresstack.spaceharvester.application.reading.StoredReadingView;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.MetadataHash;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.BaselineEntry;
import com.aresstack.spaceharvester.application.sync.Inventory;
import com.aresstack.spaceharvester.application.sync.PageChange;
import com.aresstack.spaceharvester.application.sync.PlanApplier;
import com.aresstack.spaceharvester.application.sync.RemovalCheck;
import com.aresstack.spaceharvester.application.sync.SpaceSync;
import com.aresstack.spaceharvester.application.sync.StagedGeneration;
import com.aresstack.spaceharvester.application.sync.SyncBaseline;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import com.aresstack.spaceharvester.application.sync.SyncCommitted;
import com.aresstack.spaceharvester.application.sync.SyncListener;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import com.aresstack.spaceharvester.application.sync.SyncPlan;
import com.aresstack.spaceharvester.application.sync.SyncPlanner;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
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
import java.util.function.Predicate;

/**
 * Import eines JSON-Pakets ({@code export.zip} ab {@code json-package/3}) in den Cache einer eingerichteten Quelle,
 * etwa auf einem anderen Rechner ohne Zugang zur Quelle.
 *
 * <p>Der Import läuft wie ein Abgleich, nur mit dem Paket statt der Quelle: Die Seiten des Pakets sind die Inventur,
 * ihre Rohdaten der Abruf. Er bestätigt eine neue Generation des Space ({@link SyncBaselineStore}), sodass Lesen,
 * Suche und Export der Seiten danach ohne Quelle gehen, und meldet sie wie ein Abgleich an die Folgeschritte
 * ({@link SyncCommitted}: Volltext und Anreicherungen rechnen neu). {@code changesSince} der Generation ist der früheste
 * Abruf im Paket: Der nächste Abgleich mit der Quelle holt alles nach, was sich seitdem geändert hat.
 *
 * <ul>
 *   <li>Leerer Space (keine Generation, keine Seiten im Cache): alles wird übernommen, auch ein Teilbaum.</li>
 *   <li>Space mit Daten: nur mit {@code replace} und nur mit einem Paket des ganzen Space; dann ersetzt das Paket den
 *       Stand, auch Seiten gleicher Version, und Seiten, die es nicht enthält, werden entfernt.</li>
 * </ul>
 *
 * <p>Danach legt der Import die mitgebrachten Bilder, Anhänge und Diagrammgrafiken in der Asset-Ablage ab (gegen ihre
 * Prüfsumme), die Texte der Anhänge unter der eingestellten Extraktion und die Lese-Ansichten. Die Quelle wird nie
 * gefragt. Ein neuer Export mit Bildern fragt die Quelle weiterhin nach den Anhängen der Seiten; die abgelegten Dateien
 * lädt er dann nicht noch einmal. Rechte prüft der Aufrufer, wie bei {@code SourceDataPurge}.
 */
public final class ExportPackageImport {

    /** Komponente in Ereignissen und Phasen des Auftrags. */
    public static final String COMPONENT = "import";
    /** Der Space hat schon Daten und das Paket soll sie nicht ersetzen. */
    public static final String SPACE_NOT_EMPTY = "import-space-not-empty";
    /** Ein Teilbaum-Paket kann keinen Space mit Daten ersetzen, ohne den Rest zu verlieren. */
    public static final String SUBTREE_INTO_FILLED_SPACE = "import-subtree-into-filled-space";
    /** Ein Abgleich hat währenddessen eine Generation bestätigt. */
    public static final String CONFLICT = "import-conflict";

    private static final System.Logger LOG = System.getLogger(ExportPackageImport.class.getName());

    private final Jobs jobs;
    private final ExportPackage.Reader reader;
    private final SyncBaselineStore store;
    private final Predicate<String> configured;
    private final AssetStore assets;
    private final BiPredicate<String, String> texts;
    private final ReadingViewStore views;
    private final ImportedAssets imported;
    private final List<SyncCommitted> committed;
    private final Clock clock;
    /** Dateien, die Aufträgen gehören ({@code consume}), nach Auftrags-ID; auch für Aufträge, die nie starten. */
    private final Map<String, Path> owned = new ConcurrentHashMap<>();

    /**
     * @param configured ob eine Quelle eingerichtet ist
     * @param assets     Asset-Ablage oder {@code null}, dann ohne Bilder und Anhänge
     * @param texts      legt den Text eines Anhangs zu seinem SHA-256 ab ({@link Assets#adoptText}), {@code null}
     *                   ohne Texte
     * @param views      Ablage der Lese-Ansichten oder {@code null}
     * @param committed  Folgeschritte nach der neuen Generation, wie nach einem Abgleich
     */
    public ExportPackageImport(Jobs jobs, ExportPackage.Reader reader, SyncBaselineStore store,
                               Predicate<String> configured, AssetStore assets, BiPredicate<String, String> texts,
                               ReadingViewStore views, List<SyncCommitted> committed, Clock clock) {
        this(jobs, reader, store, configured, assets, texts, views, null, committed, clock);
    }

    /**
     * Wie oben; {@code imported} merkt sich je Seite die mitgebrachten Bilder und Anhänge, damit ein Export sie ohne
     * Quelle mitnimmt ({@link ImportedAssets}), {@code null} ohne.
     */
    public ExportPackageImport(Jobs jobs, ExportPackage.Reader reader, SyncBaselineStore store,
                               Predicate<String> configured, AssetStore assets, BiPredicate<String, String> texts,
                               ReadingViewStore views, ImportedAssets imported, List<SyncCommitted> committed,
                               Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.store = Objects.requireNonNull(store, "store");
        this.configured = Objects.requireNonNull(configured, "configured");
        this.assets = assets;
        this.texts = texts;
        this.views = views;
        this.imported = imported;
        this.committed = List.copyOf(committed);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Was ein Paket enthält, ohne etwas zu übernehmen; für die Rückfrage vor dem Import. */
    public record Preview(String sourceId, String spaceKey, String spaceName, String rootPageId, int pages,
                          int assets, boolean spaceHasData) {
    }

    /**
     * Liest das Paket und sagt, was es enthält und ob der Ziel-Space schon Daten hat.
     *
     * @throws ExportPackageException wenn die Datei kein importierbares Paket ist
     * @throws UnknownSourceException ohne eingerichtete Zielquelle
     */
    public Preview preview(Path file, String targetSourceId) {
        sweep();
        requireConfigured(targetSourceId);
        try (ExportPackage pkg = reader.open(file)) {
            int files = pkg.assets().manifest().map(m -> (int) m.assets().stream()
                    .filter(ExportAssetManifest.Entry::stored).count()).orElse(0);
            return new Preview(pkg.sourceId(), pkg.spaceKey(), pkg.spaceName(), pkg.rootPageId(), pkg.pages().size(),
                    files, hasData(targetSourceId, pkg.spaceKey()));
        }
    }

    /**
     * Startet den Import als Auftrag {@link JobType#IMPORT}. Das Paket wird vorab geöffnet, damit eine falsche Datei
     * sofort scheitert.
     *
     * @param targetSourceId eingerichtete Quelle, in deren Cache die Seiten kommen; meist die Quelle des Pakets
     * @param replace        ob vorhandene Daten des Space ersetzt werden dürfen
     * @param owner          auslösender Client
     * @throws ExportPackageException wenn die Datei kein importierbares Paket ist
     * @throws UnknownSourceException ohne eingerichtete Zielquelle
     */
    public JobStatus start(Path file, String targetSourceId, boolean replace, String owner) {
        return start(file, targetSourceId, replace, owner, false);
    }

    /**
     * Wie {@link #start(Path, String, boolean, String)}; mit {@code consume} gehört die Datei dem Import, etwa ein
     * hochgeladenes Paket. Sie wird gelöscht, sobald der Auftrag endet oder nicht startet. Endet er, ohne je zu laufen
     * (abgebrochen oder gelöscht, solange er wartete), räumt der nächste Import oder spätestens das Ende des Prozesses
     * sie weg.
     */
    public JobStatus start(Path file, String targetSourceId, boolean replace, String owner, boolean consume) {
        Objects.requireNonNull(file, "file");
        sweep();
        boolean submitted = false;
        try {
            requireConfigured(targetSourceId);
            String spaceKey;
            try (ExportPackage pkg = reader.open(file)) {
                spaceKey = pkg.spaceKey();
            }
            JobStatus job = jobs.submit(JobType.IMPORT, targetSourceId, spaceKey, owner, JobTask.blocking(context -> {
                try {
                    run(file, targetSourceId, replace, owner, context);
                } finally {
                    if (consume) {
                        delete(file);
                    }
                }
            }));
            submitted = true;
            if (consume) {
                owned.put(job.id(), file);
                file.toFile().deleteOnExit();
            }
            return job;
        } finally {
            if (consume && !submitted) {
                delete(file);
            }
        }
    }

    /** Löscht die Dateien beendeter oder verschwundener Aufträge; die laufender löscht ihr Auftrag selbst. */
    private void sweep() {
        for (Map.Entry<String, Path> entry : List.copyOf(owned.entrySet())) {
            boolean ended;
            try {
                ended = jobs.status(entry.getKey()).state().terminal();
            } catch (UnknownJobException e) {
                ended = true;
            }
            if (ended && owned.remove(entry.getKey(), entry.getValue())) {
                delete(entry.getValue());
            }
        }
    }

    private static void delete(Path file) {
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (java.io.IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Paketdatei nach dem Import nicht gelöscht ({0})",
                    e.getClass().getName());
        }
    }

    private void requireConfigured(String targetSourceId) {
        Objects.requireNonNull(targetSourceId, "targetSourceId");
        if (!configured.test(targetSourceId)) {
            throw new UnknownSourceException(targetSourceId);
        }
    }

    private boolean hasData(String sourceId, String spaceKey) {
        return hasData(store.confirmed(sourceId, spaceKey).orElse(null), store.knownPages(sourceId, spaceKey));
    }

    private static boolean hasData(SyncBaseline baseline, Map<String, BaselineEntry> cached) {
        return baseline != null && (!baseline.pages().isEmpty() || !baseline.missingPageIds().isEmpty())
                || !cached.isEmpty();
    }

    private void run(Path file, String target, boolean replace, String owner, JobContext context) {
        try (ExportPackage pkg = reader.open(file)) {
            String spaceKey = pkg.spaceKey();
            context.phase(COMPONENT, "Paket prüfen");
            // Entscheidung und Plan auf demselben Stand: bestätigt ein anderer Lauf dazwischen eine Generation,
            // scheitert das Bestätigen an ihr (import-conflict), statt dessen Seiten ungefragt zu ersetzen.
            SyncBaseline baseline = store.confirmed(target, spaceKey).orElse(null);
            Map<String, BaselineEntry> cached = store.knownPages(target, spaceKey);
            boolean filled = hasData(baseline, cached);
            if (filled && pkg.rootPageId() != null) {
                throw new JobFailedException(SUBTREE_INTO_FILLED_SPACE, "Der Space " + spaceKey + " hat schon Daten;"
                        + " ein Teilbaum-Paket lässt sich nur in einen leeren Space importieren");
            }
            if (filled && !replace) {
                throw new JobFailedException(SPACE_NOT_EMPTY, "Der Space " + spaceKey + " hat schon Daten;"
                        + " der Import ersetzt sie nur auf ausdrücklichen Wunsch");
            }
            SpaceSync.SyncRun run = pages(pkg, target, spaceKey, baseline, cached, context);
            try {
                context.phase(COMPONENT, "Bilder und Anhänge übernehmen");
                assets(pkg, target, run.confirmed(), context);
                context.phase(COMPONENT, "Lese-Ansichten übernehmen");
                views(pkg, target, run.confirmed());
            } finally {
                // Die Generation steht: Volltext und Anreicherungen erfahren davon auch, wenn danach etwas scheitert
                // oder der Auftrag abgebrochen wird, sonst blieben entfernte Seiten im Index.
                committed(run, owner, target, spaceKey);
            }
        } catch (ExportPackageException e) {
            throw new JobFailedException(e.code(), e.getMessage(), e);
        }
    }

    private void committed(SpaceSync.SyncRun run, String owner, String target, String spaceKey) {
        for (SyncCommitted next : committed) {
            try {
                next.committed(run, owner);
            } catch (RuntimeException e) {
                // Der Import steht; Volltext und Anreicherungen holt der nächste Abgleich nach.
                LOG.log(System.Logger.Level.WARNING, "Folgeschritt nach dem Import von {0}/{1} gescheitert ({2})",
                        target, spaceKey, e.getClass().getName());
            }
        }
    }

    /** Seiten wie ein Abgleich: Inventur aus dem Paket, Abruf aus {@code raw/}, eine neue Generation. */
    private SpaceSync.SyncRun pages(ExportPackage pkg, String target, String spaceKey, SyncBaseline baseline,
                                    Map<String, BaselineEntry> cached, JobContext context) {
        List<ExportPackage.Page> pages = pkg.pages();
        List<PageMetadataListing.Entry> listing = new ArrayList<>(pages.size());
        Instant earliest = null;
        Instant latest = null;
        for (ExportPackage.Page page : pages) {
            listing.add(new PageMetadataListing.Entry(page.metadata(), MetadataHash.of(target, page.metadata()),
                    AttributesHash.of(target, page.metadata())));
            earliest = earliest == null || page.retrievedAt().isBefore(earliest) ? page.retrievedAt() : earliest;
            latest = latest == null || page.retrievedAt().isAfter(latest) ? page.retrievedAt() : latest;
        }
        Instant now = clock.instant();
        earliest = earliest == null ? now : earliest;
        latest = latest == null ? earliest : latest;
        Inventory inventory = new Inventory(target, spaceKey, Inventory.Status.COMPLETE, null, listing, earliest,
                latest);
        // Das Paket gilt: auch Seiten gleicher Version kommen aus dem Paket, fehlende werden entfernt.
        SyncPlan plan = SyncPlanner.plan(baseline, inventory, cached, SyncMode.FULL)
                .withContentReloaded(pageId -> true);
        Map<String, RemovalCheck.Result> removals = new HashMap<>();
        for (PageChange change : plan.changes()) {
            if (change.kind() == PageChange.Kind.MISSING) {
                removals.put(change.pageId(), new RemovalCheck.Result(change.pageId(), RemovalCheck.Verdict.DELETED,
                        null, null));
            }
        }
        plan = plan.withRemovals(removals);
        context.discovered(plan.changes().size());
        context.phase(COMPONENT, "Seiten übernehmen");
        PlanApplier applier = new PlanApplier((sourceId, pageId) -> {
            PageSnapshot snapshot = pkg.snapshot(pageId);
            return PageSnapshot.of(sourceId, snapshot.metadata(), snapshot.payload(), snapshot.retrievedAt());
        });
        SyncListener listener = new SyncListener() {
            @Override
            public void pageDone(PageChange change, boolean written, boolean failed) {
                context.processed(1);
                if (written) {
                    context.written(1);
                }
                if (failed) {
                    context.failed(1);
                }
            }

            @Override
            public void checkpoint() {
                context.checkpoint();
            }
        };
        Set<String> failed;
        SyncBaseline next;
        try (StagedGeneration staged = store.begin(target, spaceKey)) {
            failed = applier.apply(plan, staged, listener);
            next = plan.nextBaseline(now, failed);
            context.checkpoint();
            staged.commit(next);
        } catch (com.aresstack.spaceharvester.application.sync.SyncConflictException e) {
            throw new JobFailedException(CONFLICT, "Während des Imports wurde " + target + "/" + spaceKey
                    + " abgeglichen; nichts übernommen", e);
        }
        if (!failed.isEmpty()) {
            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, ExportPackageException.DAMAGED,
                    failed.size() + " Seiten passten nicht zu ihren Kopfdaten und wurden nicht übernommen"));
        }
        return new SpaceSync.SyncRun(plan, failed, next, null);
    }

    /**
     * Bilder, Anhänge und Diagrammgrafiken in die Asset-Ablage, unter denselben Kennungen wie beim Beschaffen
     * (Anhang, Version), damit ein späterer Export sie nicht neu lädt; Texte unter der eingestellten Extraktion.
     */
    private void assets(ExportPackage pkg, String target, SyncBaseline confirmed, JobContext context) {
        if (assets == null) {
            return;
        }
        ExportAssets files = pkg.assets();
        Optional<ExportAssetManifest> manifest = files.manifest();
        if (manifest.isEmpty()) {
            return;
        }
        int stored = 0;
        int missing = 0;
        Instant now = clock.instant();
        // Einträge, deren Datei hier nicht abgelegt werden konnte: im gemerkten Manifest als nicht verfügbar.
        Set<ExportAssetManifest.Entry> lost = new HashSet<>();
        List<ExportAssetManifest.Entry> entries = new ArrayList<>(manifest.get().assets());
        entries.sort(Comparator.comparing(ExportAssetManifest.Entry::pageId));
        for (ExportAssetManifest.Entry entry : entries) {
            context.checkpoint();
            if (!entry.stored()) {
                // Nicht Abgelegtes bleibt mit Status und Grund im Manifest.
                continue;
            }
            try {
                boolean mermaid = entry.reference().kind() == ExportAssetManifest.Kind.MERMAID;
                // Mermaid trägt seinen Text im Manifest; abgelegt wird nur die gezeichnete Grafik.
                if (!mermaid && entry.attachmentId() != null) {
                    if (!putFile(files, entry, target, now)) {
                        missing++;
                        lost.add(entry);
                        continue;
                    }
                    stored++;
                    if (entry.document() && texts != null) {
                        files.text(entry).filter(text -> !text.isBlank())
                                .ifPresent(text -> texts.test(entry.sha256(), text));
                    }
                }
                if (entry.rendition() != null && entry.rendition().drawn()) {
                    Optional<byte[]> rendition = files.rendition(entry);
                    if (rendition.isEmpty()) {
                        missing++;
                        lost.add(entry);
                        continue;
                    }
                    assets.put(new Asset(target, entry.pageId(), Assets.renditionId(entry.sha256()),
                            mermaid ? Assets.MERMAID_RENDITION : Assets.DRAWIO_RENDITION, "",
                            entry.rendition().mediaType(), AssetKind.of(entry.rendition().mediaType(), ""),
                            rendition.get().length, entry.rendition().sha256(), now), rendition.get());
                    stored++;
                }
            } catch (AssetStoreException | IllegalArgumentException | IOException e) {
                missing++;
                lost.add(entry);
                LOG.log(System.Logger.Level.WARNING, "Datei aus dem Paket für {0} nicht abgelegt ({1})", target,
                        e.getClass().getName());
            }
        }
        if (missing > 0) {
            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, ExportPackageException.DAMAGED,
                    missing + " Dateien aus dem Paket nicht abgelegt"));
        }
        context.record(JobEvent.Draft.info(COMPONENT, stored + " Dateien übernommen"));
        remember(manifest.get(), lost, target, confirmed, pkg.pages());
    }

    /**
     * Merkt sich je übernommener Seite ihre Einträge des Bildmanifests zur bestätigten Version ({@link ImportedAssets}),
     * auch eine leere Liste: Ein Export ohne erreichbare Quelle nimmt Bilder und Anhänge dann daraus.
     */
    private void remember(ExportAssetManifest manifest, Set<ExportAssetManifest.Entry> lost, String target,
                          SyncBaseline confirmed, List<ExportPackage.Page> pages) {
        if (imported == null || confirmed == null) {
            return;
        }
        Map<String, List<ExportAssetManifest.Entry>> byPage = new HashMap<>();
        for (ExportAssetManifest.Entry entry : manifest.assets()) {
            byPage.computeIfAbsent(entry.pageId(), id -> new ArrayList<>()).add(lost.contains(entry)
                    ? new ExportAssetManifest.Entry(entry.pageId(), entry.reference(), entry.attachmentId(),
                    entry.version(), entry.title(), entry.mediaType(), 0, null,
                    ExportAssetManifest.Status.UNAVAILABLE, "import-missing") : entry);
        }
        for (ExportPackage.Page page : pages) {
            // Nur Seiten, die mit der Version aus dem Paket bestätigt sind.
            BaselineEntry entry = confirmed.pages().get(page.pageId());
            if (entry == null || !Objects.equals(entry.version(), page.metadata().version())) {
                continue;
            }
            imported.save(new PageKey(target, page.pageId()), entry.version(), new ExportAssetManifest(
                    manifest.imagesComplete(), manifest.documentsComplete(),
                    byPage.getOrDefault(page.pageId(), List.of())));
        }
    }

    /**
     * Legt die Datei eines Eintrags als Strom über eine Datei der Ablage ab ({@link AssetStore#stagingFile()}), nie ganz
     * im Speicher: Dateien beliebiger Größe (Angelo 11.11.2026). Hash und Größe prüft die Ablage beim Ablegen.
     *
     * @return {@code false}, wenn das Paket die Datei nicht hat
     * @throws IOException wenn sie nicht lesbar ist oder nicht zu ihrer Prüfsumme passt
     */
    private boolean putFile(ExportAssets files, ExportAssetManifest.Entry entry, String target, Instant now)
            throws IOException {
        Optional<InputStream> content = files.open(entry);
        if (content.isEmpty()) {
            return false;
        }
        try (InputStream in = content.get()) {
            Path staging = assets.stagingFile();
            try {
                long size;
                // Die leere Datei der Ablage weiterverwenden, nicht neu anlegen: sie behält ihre Rechte.
                try (OutputStream out = Files.newOutputStream(staging)) {
                    size = in.transferTo(out);
                }
                // Danach gehört die Datei der Ablage (verschoben oder gelöscht), auch wenn sie scheitert.
                assets.putFile(new Asset(target, entry.pageId(), entry.attachmentId(), entry.version(),
                        entry.title(), entry.mediaType(), AssetKind.of(entry.mediaType(), entry.title()), size,
                        entry.sha256(), now), staging);
            } catch (IOException | RuntimeException e) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        }
        return true;
    }

    /** Die mitgebrachten Lese-Ansichten zur übernommenen Version jeder Seite. */
    private void views(ExportPackage pkg, String target, SyncBaseline confirmed) {
        if (views == null || confirmed == null) {
            return;
        }
        Instant now = clock.instant();
        for (ExportPackage.Page page : pkg.pages()) {
            BaselineEntry entry = confirmed.pages().get(page.pageId());
            Optional<ExportReadingView> view = pkg.readingView(page.pageId());
            if (entry == null || view.isEmpty() || !view.get().available()
                    || !Objects.equals(view.get().version(), entry.version())) {
                continue;
            }
            PageSnapshot snapshot = pkg.snapshot(page.pageId());
            views.save(new StoredReadingView(new PageKey(target, page.pageId()), snapshot.payload().rawSha256(),
                    entry.version() == null ? "" : entry.version(), view.get().html(), view.get().baseUri(),
                    view.get().text(), List.of(), now));
        }
    }
}
