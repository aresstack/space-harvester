package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobStore;
import com.aresstack.spaceharvester.application.jobs.JobType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
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
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;

/**
 * Dauerhafte Ablage der Auftragszustände ({@link JobStore}, #12) in einem eigenen kleinen Lucene-Index, neben dem
 * Seiten-Index. Ein Dokument je Auftrag, alle Felder gespeichert; Schema {@value #VERSION} in den Commit-Daten.
 *
 * <p>{@code Jobs} schreibt nur bei Zustandswechseln; jedes {@link #save} ist ein eigener Commit, damit ein Zustand nach
 * einem Absturz nicht verloren geht. Ein einziger Writer hält die Sperre, solange die Ablage offen ist.
 */
public final class LuceneJobStore implements JobStore, AutoCloseable {

    /** Schemaversion in den Commit-Daten. */
    public static final String VERSION = "job-store/v1";

    private static final String ID = "id";
    private static final String TYPE = "type";
    private static final String SOURCE_ID = "sourceId";
    private static final String PARTITION_KEY = "partitionKey";
    private static final String OWNER = "owner";
    private static final String STATE = "state";
    private static final String DISCOVERED = "discovered";
    private static final String PROCESSED = "processed";
    private static final String WRITTEN = "written";
    private static final String FAILED = "failed";
    private static final String ERROR_CODE = "errorCode";
    private static final String ERROR_MESSAGE = "errorMessage";
    private static final String CANCEL_REQUESTED = "cancelRequested";
    private static final String CREATED_AT = "createdAt";
    private static final String STARTED_AT = "startedAt";
    private static final String FINISHED_AT = "finishedAt";
    /** Optional; Aufträge aus der Zeit vor Teilbaum-Exporten haben das Feld nicht und lesen sich mit {@code null}. */
    private static final String ROOT_PAGE_ID = "rootPageId";
    private static final String ROOT_TITLE = "rootTitle";

    private final Path path;
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private volatile boolean closed;

    private LuceneJobStore(Path path, Directory directory, IndexWriter writer) throws IOException {
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
    public static LuceneJobStore open(Path path) {
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
                    throw new PageCacheException("Auftragsablage " + root + " hat Schema "
                            + (schema == null ? "(unbekannt)" : schema) + ", erwartet " + VERSION
                            + "; Verzeichnis löschen (die Aufträge gehen verloren)");
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
            return new LuceneJobStore(root, directory, writer);
        } catch (LockObtainFailedException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Auftragsablage " + root + " ist von einem anderen Prozess geöffnet", e);
        } catch (IOException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Auftragsablage " + root + " kann nicht geöffnet werden (beschädigt oder "
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
    public synchronized void save(JobStatus status) {
        Objects.requireNonNull(status, "status");
        requireOpen();
        try {
            writer.updateDocument(new Term(ID, status.id()), document(status));
            writer.commit();
            searchers.maybeRefreshBlocking();
        } catch (IOException e) {
            throw new PageCacheException("Auftragsablage " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public synchronized void delete(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        requireOpen();
        try {
            writer.deleteDocuments(new Term(ID, jobId));
            writer.commit();
            searchers.maybeRefreshBlocking();
        } catch (IOException e) {
            throw new PageCacheException("Auftragsablage " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public Optional<JobStatus> find(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        List<JobStatus> found = search(new TermQuery(new Term(ID, jobId)), 1);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<JobStatus> all() {
        return search(new MatchAllDocsQuery(), Integer.MAX_VALUE);
    }

    /** Schließt die Ablage und gibt die Sperre frei. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            searchers.close();
            writer.rollback();
        } catch (IOException e) {
            throw new PageCacheException("Auftragsablage " + path + " kann nicht geschlossen werden", e);
        } finally {
            closeQuietly(null, directory);
        }
    }

    private List<JobStatus> search(Query query, int limit) {
        requireOpen();
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                TopDocs top = searcher.search(query, limit);
                StoredFields stored = searcher.storedFields();
                List<JobStatus> jobs = new ArrayList<>(top.scoreDocs.length);
                for (ScoreDoc hit : top.scoreDocs) {
                    jobs.add(status(stored.document(hit.doc)));
                }
                return jobs;
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new PageCacheException("Auftragsablage " + path + " kann nicht gelesen werden", e);
        }
    }

    private static Document document(JobStatus status) {
        Document doc = new Document();
        doc.add(new StringField(ID, status.id(), Field.Store.YES));
        doc.add(new StoredField(TYPE, status.type().name()));
        doc.add(new StoredField(SOURCE_ID, status.sourceId()));
        storeIfPresent(doc, PARTITION_KEY, status.partitionKey());
        storeIfPresent(doc, OWNER, status.owner());
        doc.add(new StoredField(STATE, status.state().name()));
        JobProgress progress = status.progress();
        doc.add(new StoredField(DISCOVERED, progress.discovered()));
        doc.add(new StoredField(PROCESSED, progress.processed()));
        doc.add(new StoredField(WRITTEN, progress.written()));
        doc.add(new StoredField(FAILED, progress.failed()));
        if (status.error() != null) {
            doc.add(new StoredField(ERROR_CODE, status.error().code()));
            doc.add(new StoredField(ERROR_MESSAGE, status.error().message()));
        }
        doc.add(new StoredField(CANCEL_REQUESTED, status.cancelRequested() ? 1 : 0));
        doc.add(new StoredField(CREATED_AT, status.createdAt().toString()));
        storeIfPresent(doc, STARTED_AT, status.startedAt() == null ? null : status.startedAt().toString());
        storeIfPresent(doc, FINISHED_AT, status.finishedAt() == null ? null : status.finishedAt().toString());
        storeIfPresent(doc, ROOT_PAGE_ID, status.rootPageId());
        storeIfPresent(doc, ROOT_TITLE, status.rootTitle());
        return doc;
    }

    private static JobStatus status(Document doc) {
        String errorCode = doc.get(ERROR_CODE);
        JobError error = errorCode == null ? null : new JobError(errorCode, doc.get(ERROR_MESSAGE));
        JobProgress progress = new JobProgress(number(doc, DISCOVERED), number(doc, PROCESSED),
                number(doc, WRITTEN), number(doc, FAILED));
        return new JobStatus(doc.get(ID), JobType.valueOf(doc.get(TYPE)), doc.get(SOURCE_ID), doc.get(PARTITION_KEY),
                doc.get(OWNER), JobState.valueOf(doc.get(STATE)), progress, error, number(doc, CANCEL_REQUESTED) == 1,
                Instant.parse(doc.get(CREATED_AT)), instant(doc.get(STARTED_AT)), instant(doc.get(FINISHED_AT)),
                doc.get(ROOT_PAGE_ID), doc.get(ROOT_PAGE_ID) == null ? null : doc.get(ROOT_TITLE));
    }

    private static void storeIfPresent(Document doc, String field, String value) {
        if (value != null) {
            doc.add(new StoredField(field, value));
        }
    }

    private static long number(Document doc, String field) {
        if (doc.getField(field) == null || doc.getField(field).numericValue() == null) {
            throw new PageCacheException("Feld " + field + " fehlt in der Auftragsablage");
        }
        return doc.getField(field).numericValue().longValue();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Auftragsablage ist geschlossen");
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
