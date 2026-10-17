package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConversionLimitExceededException;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Startet Exporte eines Space aus dem Cache als Auftrag und findet ihre Artefakte. Der Export liest die Seitenliste
 * einer Generation stapelweise, lädt je Seite den Snapshot und schreibt ihn sofort weiter; im Speicher bleiben nur IDs
 * für den Baum.
 *
 * <p>Ein Artefakt ist nur {@code complete}, wenn es aus einer bestätigten Generation stammt und keine Seite fehlt. Ein
 * abgebrochener oder gescheiterter Export veröffentlicht nichts.
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

    private static final System.Logger LOG = System.getLogger(Exports.class.getName());

    private final Jobs jobs;
    private final PageCacheReader cache;
    private final ExportArtifactWriter writer;
    private final ExportConverters converters;
    private final Clock clock;

    public Exports(Jobs jobs, PageCacheReader cache, ExportArtifactWriter writer, Clock clock) {
        this(jobs, cache, writer, ExportConverters.none(), clock);
    }

    public Exports(Jobs jobs, PageCacheReader cache, ExportArtifactWriter writer, ExportConverters converters,
                   Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.converters = Objects.requireNonNull(converters, "converters");
        this.clock = Objects.requireNonNull(clock, "clock");
        purgeQuietly();
    }

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
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        List<ExportConverter> selected = converters.select(formats);
        purgeQuietly();
        return jobs.submit(JobType.EXPORT, sourceId, spaceKey, owner,
                JobTask.blocking(context -> export(context, sourceId, spaceKey, selected)));
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

    private void export(JobContext context, String sourceId, String spaceKey, List<ExportConverter> selected) {
        try {
            exportGeneration(context, sourceId, spaceKey, selected);
        } catch (CacheUnavailableException e) {
            throw new JobFailedException("CACHE_UNAVAILABLE", "Der Seitencache ist nicht lesbar", e);
        } catch (CacheGenerationChangedException e) {
            throw new JobFailedException("GENERATION_CHANGED",
                    "Die Cache-Generation wurde während des Exports ersetzt; bitte neu starten", e);
        }
    }

    private void exportGeneration(JobContext context, String sourceId, String spaceKey,
                                  List<ExportConverter> selected) {
        Instant startedAt = clock.instant();
        CacheGeneration generation = cache.currentGeneration(sourceId, spaceKey).orElseThrow(() ->
                new JobFailedException("NOT_CACHED", "Space " + spaceKey + " der Quelle " + sourceId
                        + " liegt nicht im Cache"));
        ExportScope scope = new ExportScope(sourceId, spaceKey, generation);
        List<TreeEntry> entries = new ArrayList<>();
        List<PageFailure> failures = new ArrayList<>();
        Map<String, ListedPage> exported = new HashMap<>();
        try (ExportArtifactWriter.Session session = writer.begin(context.jobId(), scope)) {
            String after = null;
            List<ListedPage> batch;
            do {
                context.checkpoint();
                batch = cache.pages(sourceId, spaceKey, generation.id(), after, BATCH_SIZE);
                context.discovered(batch.size());
                for (ListedPage listed : batch) {
                    context.checkpoint();
                    if (after != null && listed.pageId().compareTo(after) <= 0) {
                        throw new JobFailedException("INCONSISTENT_READ", "Seitenliste des Cache ist nicht "
                                + "aufsteigend sortiert (Seite " + listed.pageId() + ")");
                    }
                    after = listed.pageId();
                    PageFailure failure = exportPage(session, listed, generation);
                    context.processed(1);
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
            context.checkpoint();
            PageTree tree = PageTreeBuilder.build(entries);
            List<String> warnings = new ArrayList<>();
            for (PageTree.Anomaly anomaly : tree.anomalies()) {
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
            boolean complete = generation.state() == CacheState.CONFIRMED && failures.isEmpty();
            session.publish(tree, new ExportSummary(context.jobId(), scope, startedAt, clock.instant(),
                    entries.size(), failures, warnings, complete));
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
            Conversion conversion = new Conversion(converter.format(), converter.version(), converter.mediaType(),
                    converter.fileName(), inputSha256, Map.of());
            try {
                session.converted(conversion, out -> converter.convert(input,
                        new LimitedOutputStream(out, converter.fileName(), converters.maxBytes())));
            } catch (JobFailedException | JobCancelledException | CacheUnavailableException
                     | CacheGenerationChangedException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
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
