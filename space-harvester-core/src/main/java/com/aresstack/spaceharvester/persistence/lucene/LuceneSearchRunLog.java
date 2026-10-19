package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.searchrun.SearchRun;
import com.aresstack.spaceharvester.application.searchrun.SearchRunId;
import com.aresstack.spaceharvester.application.searchrun.SearchRunLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dauerhaftes Suchlauf-Protokoll ({@link SearchRunLog}, #182) in einem eigenen kleinen Lucene-Index neben der
 * Diagnoseablage (ADR 0003: nur Lucene; der Enterprise AI Client nutzt dafür H2); Schema {@value #VERSION} in den
 * Commit-Daten. Ein Dokument je Lauf, jedes {@link #record} ein eigener Commit. Läufe, die älter als die Frist sind,
 * und alles über {@link SearchRunLog#MAX_RUNS} fallen beim Öffnen und laufend beim Schreiben weg.
 *
 * <p>Geschrieben wird wie in {@link LuceneJobEventStore} auf einem eigenen Thread, damit eine Unterbrechung des
 * Aufrufers nie mitten in einer Dateioperation den Kanal der Indexsperre schließt.
 */
public final class LuceneSearchRunLog implements SearchRunLog, AutoCloseable {

    /** Schemaversion in den Commit-Daten. */
    public static final String VERSION = "search-runs/v1";

    /** Alle so viele Läufe räumt das Schreiben Frist und Obergrenze auf. */
    static final int PURGE_EVERY = 100;

    private static final String ID = "id";
    private static final String PART_OF = "partOf";
    private static final String OWNER = "owner";
    private static final String STARTED = "started";
    private static final String STARTED_SORT = "startedSort";
    private static final String RUN = "run";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path path;
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private final Duration maxAge;
    private final Clock clock;
    private final ExecutorService writes = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon()
            .name("search-runs-writer").factory());
    private int recorded;
    private volatile boolean closed;

    private LuceneSearchRunLog(Path path, Directory directory, IndexWriter writer, Duration maxAge, Clock clock)
            throws IOException {
        this.path = path;
        this.directory = directory;
        this.writer = writer;
        this.searchers = new SearcherManager(writer, null);
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /** Ohne Frist; es gilt nur {@link SearchRunLog#MAX_RUNS}. */
    public static LuceneSearchRunLog open(Path path) {
        return open(path, null, Clock.systemUTC());
    }

    /**
     * Öffnet das Protokoll im Verzeichnis {@code path} und legt es an, wenn es dort noch keinen Index gibt.
     *
     * @param maxAge so lange bleiben Läufe, {@code null} ohne Frist
     * @throws PageCacheException wenn der Index gesperrt, beschädigt oder in einem anderen Schema ist
     */
    public static LuceneSearchRunLog open(Path path, Duration maxAge, Clock clock) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(clock, "clock");
        if (maxAge != null && (maxAge.isNegative() || maxAge.isZero())) {
            throw new IllegalArgumentException("maxAge muss positiv sein");
        }
        Path root = path.toAbsolutePath().normalize();
        Directory directory = null;
        IndexWriter writer = null;
        try {
            Files.createDirectories(root);
            directory = FSDirectory.open(root);
            boolean exists = DirectoryReader.indexExists(directory);
            if (exists) {
                String schema = SegmentInfos.readLatestCommit(directory).getUserData()
                        .get(PageCacheSchema.SCHEMA_KEY);
                if (!VERSION.equals(schema)) {
                    throw new PageCacheException("Suchlauf-Protokoll " + root + " hat Schema "
                            + (schema == null ? "(unbekannt)" : schema) + ", erwartet " + VERSION
                            + "; Verzeichnis löschen (nur das Protokoll geht verloren)");
                }
            }
            IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            config.setCommitOnClose(false);
            writer = new IndexWriter(directory, config);
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, VERSION).entrySet());
            if (!exists) {
                writer.commit();
            }
            LuceneSearchRunLog log = new LuceneSearchRunLog(root, directory, writer, maxAge, clock);
            log.purge();
            return log;
        } catch (LockObtainFailedException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Suchlauf-Protokoll " + root + " ist von einem anderen Prozess geöffnet", e);
        } catch (IOException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Suchlauf-Protokoll " + root + " kann nicht geöffnet werden (beschädigt "
                    + "oder unlesbar)", e);
        } catch (RuntimeException e) {
            closeQuietly(writer, directory);
            throw e;
        }
    }

    /** Verzeichnis des Index. */
    public Path path() {
        return path;
    }

    @Override
    public void record(SearchRun run) {
        Objects.requireNonNull(run, "run");
        requireOpen();
        write(() -> {
            try {
                if (count(new TermQuery(new Term(ID, run.id().value()))) > 0) {
                    throw new IllegalStateException("Suchlauf existiert schon");
                }
                writer.addDocument(document(run));
                if (++recorded % PURGE_EVERY == 0) {
                    purgeNow();
                }
                writer.commit();
                searchers.maybeRefreshBlocking();
            } catch (IOException e) {
                throw new PageCacheException("Suchlauf-Protokoll " + path + " kann nicht geschrieben werden", e);
            }
            return null;
        });
    }

    @Override
    public Optional<SearchRun> find(SearchRunId id) {
        Objects.requireNonNull(id, "id");
        List<SearchRun> found = search(new TermQuery(new Term(ID, id.value())), 1, null);
        return found.stream().findFirst();
    }

    @Override
    public List<SearchRun> parts(SearchRunId id) {
        Objects.requireNonNull(id, "id");
        return search(new TermQuery(new Term(PART_OF, id.value())), MAX_PARTS,
                new Sort(new SortField(STARTED_SORT, SortField.Type.LONG)));
    }

    @Override
    public List<SearchRun> recent(String owner, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        Query query = owner == null ? new MatchAllDocsQuery() : new TermQuery(new Term(OWNER, owner));
        return search(query, limit, new Sort(new SortField(STARTED_SORT, SortField.Type.LONG, true)));
    }

    @Override
    public int deleteBefore(Instant before) {
        Objects.requireNonNull(before, "before");
        requireOpen();
        return write(() -> {
            try {
                Query older = LongPoint.newRangeQuery(STARTED, Long.MIN_VALUE, before.toEpochMilli() - 1);
                int removed = count(older);
                if (removed > 0) {
                    writer.deleteDocuments(older);
                    writer.commit();
                    searchers.maybeRefreshBlocking();
                }
                return removed;
            } catch (IOException e) {
                throw new PageCacheException("Suchlauf-Protokoll " + path + " kann nicht geschrieben werden", e);
            }
        });
    }

    /** Frist und Obergrenze beim Öffnen. */
    private void purge() throws IOException {
        purgeNow();
        writer.commit();
        searchers.maybeRefreshBlocking();
    }

    /** Entfernt zu alte Läufe und die ältesten über {@link SearchRunLog#MAX_RUNS}; ohne Commit. */
    private void purgeNow() throws IOException {
        if (maxAge != null) {
            writer.deleteDocuments(LongPoint.newRangeQuery(STARTED, Long.MIN_VALUE,
                    clock.instant().minus(maxAge).toEpochMilli() - 1));
        }
        searchers.maybeRefreshBlocking();
        IndexSearcher searcher = searchers.acquire();
        try {
            int total = searcher.count(new MatchAllDocsQuery());
            if (total <= MAX_RUNS) {
                return;
            }
            // Die ältesten zuerst; die Grenze ist der Beginn des jüngsten zu entfernenden Laufs.
            TopDocs oldest = searcher.search(new MatchAllDocsQuery(), total - MAX_RUNS,
                    new Sort(new SortField(STARTED_SORT, SortField.Type.LONG)));
            StoredFields stored = searcher.storedFields();
            for (ScoreDoc hit : oldest.scoreDocs) {
                writer.deleteDocuments(new Term(ID, stored.document(hit.doc).get(ID)));
            }
        } finally {
            searchers.release(searcher);
        }
    }

    private int count(Query query) throws IOException {
        IndexSearcher searcher = searchers.acquire();
        try {
            return searcher.count(query);
        } finally {
            searchers.release(searcher);
        }
    }

    private List<SearchRun> search(Query query, int limit, Sort sort) {
        requireOpen();
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                TopDocs top = sort == null ? searcher.search(query, limit) : searcher.search(query, limit, sort);
                StoredFields stored = searcher.storedFields();
                List<SearchRun> runs = new ArrayList<>(top.scoreDocs.length);
                for (ScoreDoc hit : top.scoreDocs) {
                    SearchRun run = run(stored.document(hit.doc));
                    if (run != null) {
                        runs.add(run);
                    }
                }
                return runs;
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new PageCacheException("Suchlauf-Protokoll " + path + " kann nicht gelesen werden", e);
        }
    }

    /** Führt den Schreibzugriff auf dem Schreibthread aus und wartet auf ihn. */
    private <T> T write(Supplier<T> change) {
        Future<T> done;
        try {
            done = writes.submit(change::get);
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("Suchlauf-Protokoll ist geschlossen", e);
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return done.get();
                } catch (InterruptedException e) {
                    // Der Schreibzugriff läuft ohnehin zu Ende; danach gilt die Unterbrechung wieder.
                    interrupted = true;
                }
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new PageCacheException("Suchlauf-Protokoll " + path + " kann nicht geschrieben werden",
                    e.getCause());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Schließt das Protokoll, nachdem ausstehende Schreibzugriffe erledigt sind, und gibt die Sperre frei. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        writes.shutdown();
        try {
            if (!writes.awaitTermination(10, TimeUnit.SECONDS)) {
                writes.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            searchers.close();
            writer.rollback();
        } catch (IOException e) {
            throw new PageCacheException("Suchlauf-Protokoll " + path + " kann nicht geschlossen werden", e);
        } finally {
            closeQuietly(null, directory);
        }
    }

    private static Document document(SearchRun run) {
        Document doc = new Document();
        doc.add(new StringField(ID, run.id().value(), Field.Store.YES));
        if (run.partOf() != null) {
            doc.add(new StringField(PART_OF, run.partOf().value(), Field.Store.YES));
        }
        doc.add(new StringField(OWNER, run.owner(), Field.Store.YES));
        long started = run.startedAt().toEpochMilli();
        doc.add(new LongPoint(STARTED, started));
        doc.add(new NumericDocValuesField(STARTED_SORT, started));
        doc.add(new StoredField(RUN, json(run)));
        return doc;
    }

    private static String json(SearchRun run) {
        ObjectNode node = JSON.createObjectNode();
        node.put("kind", run.kind().name());
        node.put("query", run.query());
        node.put("startedAt", run.startedAt().toString());
        node.put("durationMillis", run.duration().toMillis());
        ArrayNode sources = node.putArray("sources");
        for (SearchRun.SourceOutcome source : run.sources()) {
            ObjectNode entry = sources.addObject();
            entry.put("sourceId", source.sourceId());
            entry.put("status", source.status().name());
            if (source.reason() != null) {
                entry.put("reason", source.reason());
            }
        }
        ObjectNode stages = node.putObject("stages");
        run.stages().forEach(stage -> stages.put(stage.stage().name(), stage.count()));
        node.put("complete", run.complete());
        if (run.completion() != null) {
            node.put("completion", run.completion());
        }
        return node.toString();
    }

    /** Der Lauf oder {@code null}, wenn das Dokument sich nicht lesen lässt (es fehlt dann, statt alles zu stören). */
    private static SearchRun run(Document doc) {
        try {
            JsonNode node = JSON.readTree(doc.get(RUN));
            List<SearchRun.SourceOutcome> sources = new ArrayList<>();
            for (JsonNode entry : node.path("sources")) {
                sources.add(new SearchRun.SourceOutcome(entry.path("sourceId").asString(),
                        SearchRun.SourceOutcome.Status.valueOf(entry.path("status").asString()),
                        entry.hasNonNull("reason") ? entry.get("reason").asString() : null));
            }
            List<SearchRun.StageCount> stages = new ArrayList<>();
            for (SearchRun.Stage stage : SearchRun.Stage.values()) {
                JsonNode count = node.path("stages").get(stage.name());
                if (count != null && count.canConvertToInt()) {
                    stages.add(new SearchRun.StageCount(stage, count.asInt()));
                }
            }
            String partOf = doc.get(PART_OF);
            return new SearchRun(new SearchRunId(doc.get(ID)), partOf == null ? null : new SearchRunId(partOf),
                    SearchRun.Kind.valueOf(node.path("kind").asString()), doc.get(OWNER),
                    node.path("query").asString(), Instant.parse(node.path("startedAt").asString()),
                    Duration.ofMillis(node.path("durationMillis").asLong()), sources, stages,
                    node.path("complete").asBoolean(), node.hasNonNull("completion")
                    ? node.get("completion").asString() : null);
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Suchlauf-Protokoll ist geschlossen");
        }
    }

    private static void closeQuietly(IndexWriter writer, Directory directory) {
        try {
            if (writer != null) {
                writer.rollback();
            }
        } catch (IOException | RuntimeException ignored) {
            // Beim Aufräumen nach einem Fehler zählt der ursprüngliche Fehler.
        }
        try {
            if (directory != null) {
                directory.close();
            }
        } catch (IOException | RuntimeException ignored) {
            // wie oben
        }
    }
}
