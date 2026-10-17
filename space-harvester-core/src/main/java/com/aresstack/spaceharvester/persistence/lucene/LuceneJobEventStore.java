package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobEventStore;
import com.aresstack.spaceharvester.application.jobs.JobType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
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

/**
 * Dauerhafte Ablage der Diagnoseereignisse ({@link JobEventStore}) in einem eigenen kleinen Lucene-Index neben der
 * Auftragsablage; Schema {@value #VERSION} in den Commit-Daten. Ein Dokument je Ereignis, jedes {@link #append} ein
 * eigener Commit, damit der Befund eines Absturzes erhalten bleibt.
 *
 * <p>Geschrieben wird auf einem eigenen Thread: Ereignisse entstehen auf Auftragsthreads, die beim Beenden unterbrochen
 * werden, und eine Unterbrechung mitten in einer Dateioperation schlösse den Kanal der Indexsperre.
 */
public final class LuceneJobEventStore implements JobEventStore, AutoCloseable {

    /** Schemaversion in den Commit-Daten. */
    public static final String VERSION = "job-events/v1";

    private static final String JOB_ID = "jobId";
    private static final String SEQUENCE = "sequence";
    private static final String SEQUENCE_SORT = "sequenceSort";
    private static final String AT = "at";
    private static final String AT_MILLIS = "atMillis";
    private static final String TYPE = "type";
    private static final String SOURCE_ID = "sourceId";
    private static final String PARTITION_KEY = "partitionKey";
    private static final String SEVERITY = "severity";
    private static final String CATEGORY = "category";
    private static final String COMPONENT = "component";
    private static final String PHASE = "phase";
    private static final String CODE = "code";
    private static final String MESSAGE = "message";
    private static final String HTTP_STATUS = "httpStatus";
    private static final String PROXY_ROUTE = "proxyRoute";
    private static final String TLS_FINDING = "tlsFinding";
    private static final String DETAIL = "detail";

    private final Path path;
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private final ExecutorService writes = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon()
            .name("job-events-writer").factory());
    private volatile boolean closed;

    private LuceneJobEventStore(Path path, Directory directory, IndexWriter writer) throws IOException {
        this.path = path;
        this.directory = directory;
        this.writer = writer;
        this.searchers = new SearcherManager(directory, null);
    }

    /**
     * Öffnet die Ablage im Verzeichnis {@code path} und legt sie an, wenn es dort noch keinen Index gibt.
     *
     * @throws PageCacheException wenn der Index gesperrt, beschädigt oder in einem anderen Schema ist
     */
    public static LuceneJobEventStore open(Path path) {
        Objects.requireNonNull(path, "path");
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
                    throw new PageCacheException("Diagnoseablage " + root + " hat Schema "
                            + (schema == null ? "(unbekannt)" : schema) + ", erwartet " + VERSION
                            + "; Verzeichnis löschen (nur die Diagnose geht verloren)");
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
            return new LuceneJobEventStore(root, directory, writer);
        } catch (LockObtainFailedException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Diagnoseablage " + root + " ist von einem anderen Prozess geöffnet", e);
        } catch (IOException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Diagnoseablage " + root + " kann nicht geöffnet werden (beschädigt oder "
                    + "unlesbar)", e);
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
    public void append(JobEvent event) {
        Objects.requireNonNull(event, "event");
        requireOpen();
        write(() -> appendNow(event));
    }

    private void appendNow(JobEvent event) {
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                if (searcher.count(new TermQuery(new Term(JOB_ID, event.jobId()))) >= MAX_EVENTS_PER_JOB) {
                    return;
                }
            } finally {
                searchers.release(searcher);
            }
            writer.addDocument(document(event));
            writer.commit();
            searchers.maybeRefreshBlocking();
        } catch (IOException e) {
            throw new PageCacheException("Diagnoseablage " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public List<JobEvent> events(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        return search(new TermQuery(new Term(JOB_ID, jobId)), MAX_EVENTS_PER_JOB,
                new Sort(new SortField(SEQUENCE_SORT, SortField.Type.LONG)));
    }

    @Override
    public List<JobEvent> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return search(new MatchAllDocsQuery(), limit, new Sort(new SortField(AT_MILLIS, SortField.Type.LONG, true),
                new SortField(SEQUENCE_SORT, SortField.Type.LONG, true)));
    }

    @Override
    public void delete(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        requireOpen();
        write(() -> deleteNow(jobId));
    }

    private void deleteNow(String jobId) {
        try {
            writer.deleteDocuments(new Term(JOB_ID, jobId));
            writer.commit();
            searchers.maybeRefreshBlocking();
        } catch (IOException e) {
            throw new PageCacheException("Diagnoseablage " + path + " kann nicht geschrieben werden", e);
        }
    }

    /** Führt den Schreibzugriff auf dem Schreibthread aus und wartet auf ihn; unterbrochen wartet der Aufrufer nicht. */
    private void write(Runnable change) {
        Future<?> done;
        try {
            done = writes.submit(change);
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("Diagnoseablage ist geschlossen", e);
        }
        try {
            done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new PageCacheException("Diagnoseablage " + path + " kann nicht geschrieben werden", e.getCause());
        }
    }

    /** Schließt die Ablage, nachdem ausstehende Schreibzugriffe erledigt sind, und gibt die Sperre frei. */
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
            throw new PageCacheException("Diagnoseablage " + path + " kann nicht geschlossen werden", e);
        } finally {
            closeQuietly(null, directory);
        }
    }

    private List<JobEvent> search(Query query, int limit, Sort sort) {
        requireOpen();
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                TopDocs top = searcher.search(query, limit, sort);
                StoredFields stored = searcher.storedFields();
                List<JobEvent> events = new ArrayList<>(top.scoreDocs.length);
                for (ScoreDoc hit : top.scoreDocs) {
                    events.add(event(stored.document(hit.doc)));
                }
                return events;
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new PageCacheException("Diagnoseablage " + path + " kann nicht gelesen werden", e);
        }
    }

    private static Document document(JobEvent event) {
        Document doc = new Document();
        doc.add(new StringField(JOB_ID, event.jobId(), Field.Store.YES));
        doc.add(new StoredField(SEQUENCE, event.sequence()));
        doc.add(new NumericDocValuesField(SEQUENCE_SORT, event.sequence()));
        doc.add(new StoredField(AT, event.at().toString()));
        doc.add(new NumericDocValuesField(AT_MILLIS, event.at().toEpochMilli()));
        doc.add(new StoredField(TYPE, event.type().name()));
        doc.add(new StoredField(SOURCE_ID, event.sourceId()));
        storeIfPresent(doc, PARTITION_KEY, event.partitionKey());
        doc.add(new StoredField(SEVERITY, event.severity().name()));
        doc.add(new StoredField(CATEGORY, event.category().name()));
        doc.add(new StoredField(COMPONENT, event.component()));
        storeIfPresent(doc, PHASE, event.phase());
        storeIfPresent(doc, CODE, event.code());
        doc.add(new StoredField(MESSAGE, event.message()));
        if (event.httpStatus() != null) {
            doc.add(new StoredField(HTTP_STATUS, event.httpStatus()));
        }
        storeIfPresent(doc, PROXY_ROUTE, event.proxyRoute());
        storeIfPresent(doc, TLS_FINDING, event.tlsFinding());
        storeIfPresent(doc, DETAIL, event.detail());
        return doc;
    }

    private static JobEvent event(Document doc) {
        Number status = doc.getField(HTTP_STATUS) == null ? null : doc.getField(HTTP_STATUS).numericValue();
        return new JobEvent(doc.get(JOB_ID), doc.getField(SEQUENCE).numericValue().longValue(),
                Instant.parse(doc.get(AT)), JobType.valueOf(doc.get(TYPE)), doc.get(SOURCE_ID), doc.get(PARTITION_KEY),
                JobEvent.Severity.valueOf(doc.get(SEVERITY)), JobEvent.Category.valueOf(doc.get(CATEGORY)),
                doc.get(COMPONENT), doc.get(PHASE), doc.get(CODE), doc.get(MESSAGE),
                status == null ? null : status.intValue(), doc.get(PROXY_ROUTE), doc.get(TLS_FINDING),
                doc.get(DETAIL));
    }

    private static void storeIfPresent(Document doc, String field, String value) {
        if (value != null) {
            doc.add(new StoredField(field, value));
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Diagnoseablage ist geschlossen");
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
