package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.PageCacheSchema.HEADER_FIELDS;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.application.sync.BaselineEntry;
import com.aresstack.spaceharvester.application.sync.SyncBaseline;
import com.aresstack.spaceharvester.application.sync.SyncConflictException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortedSetSortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;

/**
 * Persistenter Seiten-Cache in einem lokalen Lucene-Index ({@link SnapshotRepository}, #26).
 *
 * <p><b>Schreiben.</b> Ein einziger {@link IndexWriter} je Index, der die Lucene-Sperre {@code write.lock} hält,
 * solange der Cache offen ist; ein zweiter Prozess auf demselben Verzeichnis scheitert beim Öffnen. Schreibzugriffe
 * laufen nacheinander. {@link #save} und {@link #remove} sind je ein eigener Commit. Für einen Synchronisierungslauf
 * gibt es {@link #begin()}: alle Änderungen eines {@link Batch} werden mit {@link Batch#commit()} in einem einzigen
 * Lucene-Commit sichtbar oder gar nicht. Ein nicht bestätigter Batch, ein Absturz oder ein Neustart davor lassen den
 * zuletzt bestätigten Stand unverändert stehen.
 *
 * <p><b>Zwei Stände je Seite</b> (Snapshot-Vertrag, ADR 0002): der zuletzt gespeicherte Snapshot mit Payload und den
 * Kopfdaten derselben Antwort, und die aktuellen Kopfdaten, die {@link #saveHeader} ohne neue Payload ersetzt. Listen-
 * und Baumabfragen gehen nach den aktuellen Kopfdaten.
 *
 * <p><b>Lesen.</b> Leser sehen nur bestätigte Commits (kein Near-Real-Time-Lesen aus dem Writer); nach jedem Commit
 * wird der Leser erneuert, sodass ein abgeschlossenes {@code save} sofort sichtbar ist. Listen und Baumabfragen laden
 * nur Kopfdaten ({@link CachedPageHeader}), nie die Payload.
 *
 * <p><b>Integrität.</b> Beim Lesen eines Snapshots wird der SHA-256 der gespeicherten Bytes mit dem gespeicherten
 * {@code rawSha256} verglichen; eine Abweichung ist ein {@link PageCacheException}, nie stillschweigend eine andere
 * Payload. Ein unlesbarer Index oder ein Index mit anderem Schema ({@link PageCacheSchema}) wird nicht geöffnet.
 *
 * <p>Keine relationalen Transaktionen: ein Batch ist atomar gegenüber Lesern und Abstürzen, aber es gibt nur einen
 * Schreiber zur Zeit und keine Isolation zwischen mehreren gleichzeitigen Läufen.
 */
public final class LucenePageCache implements SnapshotRepository, AutoCloseable {

    /** Standardobergrenze einer Payload, wie {@code maxResponseBytes} des Upstream-Transports (ADR 0002). */
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 20 * 1024 * 1024;
    /** Obergrenze einer Seite in Listenabfragen. */
    public static final int MAX_PAGE_SIZE = 1000;

    private final Path path;
    private final Directory directory;
    private final int maxPayloadBytes;
    private final SearcherManager searchers;
    private final ReentrantLock writeLock = new ReentrantLock();
    private IndexWriter writer;
    private volatile boolean closed;
    /** Writer und Verzeichnis sind freigegeben; nur unter {@link #writeLock}. */
    private boolean released;

    private LucenePageCache(Path path, Directory directory, IndexWriter writer, int maxPayloadBytes)
            throws IOException {
        this.path = path;
        this.directory = directory;
        this.writer = writer;
        this.maxPayloadBytes = maxPayloadBytes;
        this.searchers = new SearcherManager(directory, null);
    }

    /** Öffnet den Cache im Verzeichnis {@code path} mit {@link #DEFAULT_MAX_PAYLOAD_BYTES}. */
    public static LucenePageCache open(Path path) {
        return open(path, DEFAULT_MAX_PAYLOAD_BYTES);
    }

    /**
     * Öffnet den Cache im Verzeichnis {@code path} und legt ihn an, wenn es dort noch keinen Index gibt.
     *
     * @param maxPayloadBytes größte zulässige Payload; größere Snapshots werden abgewiesen, nie gekürzt
     * @throws PageCacheException wenn der Index gesperrt, beschädigt oder in einem anderen Schema ist
     */
    public static LucenePageCache open(Path path, int maxPayloadBytes) {
        Objects.requireNonNull(path, "path");
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes muss positiv sein");
        }
        Path root = path.toAbsolutePath().normalize();
        Directory directory = null;
        IndexWriter writer = null;
        try {
            Files.createDirectories(root);
            directory = FSDirectory.open(root);
            boolean exists = DirectoryReader.indexExists(directory);
            if (exists) {
                requireSchema(directory, root);
            }
            writer = new IndexWriter(directory, writerConfig());
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, PageCacheSchema.VERSION).entrySet());
            if (!exists) {
                writer.commit();
            }
            return new LucenePageCache(root, directory, writer, maxPayloadBytes);
        } catch (LockObtainFailedException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Cache " + root + " ist von einem anderen Prozess geöffnet", e);
        } catch (IOException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Cache " + root + " kann nicht geöffnet werden (beschädigt oder unlesbar); "
                    + "aus einer Sicherung wiederherstellen oder das Verzeichnis löschen und neu ernten", e);
        } catch (RuntimeException e) {
            closeQuietly(writer, directory);
            throw e;
        }
    }

    /** Verzeichnis des Index. */
    public Path path() {
        return path;
    }

    // --- SnapshotRepository ---------------------------------------------------------------------------------------

    @Override
    public void save(PageSnapshot snapshot) {
        try (Batch batch = begin()) {
            batch.save(snapshot);
            batch.commit();
        }
    }

    /**
     * Der gespeicherte Stand einer Seite als Vergleichswert für {@link #trySave}: Prüfsummen und Beobachtungszeit der
     * Kopfdaten und die Payload-Prüfsumme, {@code -} ohne Payload; {@code null}, wenn die Seite unbekannt ist.
     */
    public String state(PageKey key) {
        return stored(key).map(LucenePageCache::state).orElse(null);
    }

    private static String state(StoredPage page) {
        PageHeader header = page.header();
        return header.metadataHash() + ":" + header.attributesHash() + ":" + header.observedAt() + ":"
                + (page.snapshot() == null ? "-" : page.snapshot().payload().rawSha256());
    }

    /** Längste Wartezeit von {@link #trySave} auf die Schreibsperre. */
    static final Duration FILL_WAIT = Duration.ofMillis(500);

    /**
     * Wie {@link #save}, aber nur, wenn binnen {@link #FILL_WAIT} kein anderer Schreiblauf offen ist und die Seite im
     * Cache noch den Stand hat, den der Aufrufer vor seinem Abruf gelesen hat ({@link #state}) (Nachladen beim Lesen
     * einer Seite und bei der Suche, ADR 0010). Die kurze Wartezeit lässt gleichzeitige Einzelablagen nacheinander
     * zum Zug kommen; ein laufender Abgleich hält die Sperre länger, dann bleibt es beim Nicht-Ablegen. So überschreibt ein spät ankommender Abruf nie neuere Kopfdaten oder eine neuere Payload, die
     * ein Abgleich oder ein anderer Abruf inzwischen abgelegt hat.
     *
     * @param expectedState {@link #state} vor dem Abruf
     * @return ob gespeichert wurde
     */
    public boolean trySave(PageSnapshot snapshot, String expectedState) {
        requireOpen();
        try {
            if (!writeLock.tryLock(FILL_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            if (closed) {
                return false;
            }
            if (!Objects.equals(state(snapshot.key()), expectedState)) {
                return false;
            }
            // Die Sperre ist wiedereintrittsfähig: begin() nimmt sie ein zweites Mal, der Lauf gibt sie wieder ab.
            try (Batch batch = begin()) {
                batch.save(snapshot);
                batch.commit();
            }
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void saveHeader(PageHeader header) {
        try (Batch batch = begin()) {
            batch.saveHeader(header);
            batch.commit();
        }
    }

    @Override
    public Optional<PageSnapshot> find(PageKey key) {
        Objects.requireNonNull(key, "key");
        return first(new TermQuery(PageCacheSchema.pageTerm(key)), null,
                doc -> PageCacheSchema.hasSnapshot(doc) ? PageCacheSchema.snapshot(doc) : null);
    }

    @Override
    public Optional<PageHeader> findHeader(PageKey key) {
        Objects.requireNonNull(key, "key");
        return first(new TermQuery(PageCacheSchema.pageTerm(key)), HEADER_FIELDS, PageCacheSchema::header);
    }

    /** Entfernt alles einer Quelle in einem eigenen Schreiblauf, siehe {@link Batch#removeSource}. */
    public void removeSource(String sourceId) {
        try (Batch batch = begin()) {
            batch.removeSource(sourceId);
            batch.commit();
        }
    }

    @Override
    public boolean remove(PageKey key) {
        try (Batch batch = begin()) {
            boolean removed = batch.remove(key);
            batch.commit();
            return removed;
        }
    }

    /**
     * Aktuelle Kopfdaten und gespeicherter Snapshot einer Seite aus demselben Stand.
     *
     * @param snapshot {@code null}, solange nur Kopfdaten bekannt sind
     */
    public record StoredPage(PageHeader header, PageSnapshot snapshot) {
    }

    /** Kopfdaten und Snapshot einer Seite in einem Lesezugriff, damit beide zum selben Commit gehören. */
    public Optional<StoredPage> stored(PageKey key) {
        Objects.requireNonNull(key, "key");
        return first(new TermQuery(PageCacheSchema.pageTerm(key)), null, doc -> new StoredPage(
                PageCacheSchema.header(doc), PageCacheSchema.hasSnapshot(doc) ? PageCacheSchema.snapshot(doc) : null));
    }

    // --- Abfragen ohne Payload ------------------------------------------------------------------------------------

    /**
     * Seiten eines Space nach ihren aktuellen Kopfdaten, nach Seiten-ID aufsteigend ({@link String#compareTo}),
     * ohne Payload.
     *
     * @param afterPageId nur Seiten mit größerer Seiten-ID (Fortsetzung einer vorigen Liste) oder {@code null}
     * @param limit       höchstens so viele Einträge, 1 bis {@link #MAX_PAGE_SIZE}
     */
    public List<CachedPageHeader> spacePages(String sourceId, String spaceKey, String afterPageId, int limit) {
        BooleanQuery.Builder query = pages(sourceId)
                .add(exact(PageCacheSchema.SPACE_KEY, spaceKey), BooleanClause.Occur.FILTER);
        if (afterPageId != null) {
            query.add(TermRangeQuery.newStringRange(PageCacheSchema.PAGE_ID, afterPageId, null, false, false),
                    BooleanClause.Occur.FILTER);
        }
        return headers(query.build(), limit);
    }

    /** Direkte Kinder einer Seite nach aktuellen Kopfdaten, über alle Spaces der Quelle, nach Seiten-ID. */
    public List<CachedPageHeader> children(PageKey parent, int limit) {
        Objects.requireNonNull(parent, "parent");
        return headers(pages(parent.sourceId())
                .add(exact(PageCacheSchema.PARENT_ID, parent.pageId()), BooleanClause.Occur.FILTER)
                .build(), limit);
    }

    /** Seiten eines Space ohne Elternseite nach aktuellen Kopfdaten, nach Seiten-ID; ohne Payload. */
    public List<CachedPageHeader> rootPages(String sourceId, String spaceKey, int limit) {
        return headers(pages(sourceId)
                .add(exact(PageCacheSchema.SPACE_KEY, spaceKey), BooleanClause.Occur.FILTER)
                .add(new FieldExistsQuery(PageCacheSchema.PARENT_ID), BooleanClause.Occur.MUST_NOT)
                .build(), limit);
    }

    /** Anzahl der bekannten Seiten eines Space nach aktuellen Kopfdaten. */
    public int countSpacePages(String sourceId, String spaceKey) {
        Query query = pages(sourceId).add(exact(PageCacheSchema.SPACE_KEY, spaceKey), BooleanClause.Occur.FILTER)
                .build();
        return read(searcher -> searcher.count(query));
    }

    /** Gespeicherte Revisionen einer Seite, nach Abrufzeit aufsteigend; ohne Payload. */
    public List<CachedRevision> revisions(PageKey key) {
        Objects.requireNonNull(key, "key");
        List<CachedRevision> revisions = read(searcher -> {
            TopDocs top = searcher.search(new TermQuery(PageCacheSchema.revisionsOfPage(key)), Integer.MAX_VALUE);
            StoredFields stored = searcher.storedFields();
            List<CachedRevision> found = new ArrayList<>();
            for (ScoreDoc hit : top.scoreDocs) {
                found.add(PageCacheSchema.cachedRevision(stored.document(hit.doc)));
            }
            return found;
        });
        revisions.sort((a, b) -> a.retrievedAt().compareTo(b.retrievedAt()));
        return revisions;
    }

    /** Kopf der bestätigten Generation eines Space ohne ihre Seiten; leer, solange keine bestätigt ist. */
    public Optional<ConfirmedGeneration> confirmedGeneration(String sourceId, String spaceKey) {
        return first(new TermQuery(PageCacheSchema.spaceTerm(sourceId, spaceKey)), null,
                doc -> PageCacheSchema.confirmedGeneration(doc));
    }

    /** Die bestätigte Generation eines Space samt Stand je Seite; leer, solange keine bestätigt ist. */
    public Optional<SyncBaseline> confirmed(String sourceId, String spaceKey) {
        return read(searcher -> {
            TopDocs top = searcher.search(new TermQuery(PageCacheSchema.spaceTerm(sourceId, spaceKey)), 1);
            if (top.scoreDocs.length == 0) {
                return Optional.<SyncBaseline>empty();
            }
            StoredFields stored = searcher.storedFields();
            Document space = stored.document(top.scoreDocs[0].doc);
            Query entries = new TermQuery(PageCacheSchema.baselineOf(sourceId, spaceKey));
            int count = searcher.count(entries);
            List<Document> docs = new ArrayList<>(count);
            if (count > 0) {
                for (ScoreDoc hit : searcher.search(entries, count).scoreDocs) {
                    docs.add(stored.document(hit.doc));
                }
            }
            return Optional.of(PageCacheSchema.baseline(space, docs));
        });
    }

    /** Anzahl aller Dokumente im Index (Seiten, Revisionen, Spaces), für Betrieb und Tests. */
    int documentCount() {
        return read(searcher -> searcher.count(new MatchAllDocsQuery()));
    }

    // --- Schreibläufe ---------------------------------------------------------------------------------------------

    /**
     * Beginnt einen Schreiblauf. Bis {@link Batch#commit()} sieht kein Leser seine Änderungen; {@link Batch#close()}
     * ohne Commit verwirft sie. Wartet, solange ein anderer Lauf offen ist.
     */
    public Batch begin() {
        requireOpen();
        writeLock.lock();
        if (closed) {
            writeLock.unlock();
            throw new IllegalStateException("Seiten-Cache ist geschlossen");
        }
        return new Batch();
    }

    /** Ein atomarer Schreiblauf, siehe {@link #begin()}. Nur im Thread benutzen, der ihn begonnen hat. */
    public final class Batch implements AutoCloseable {

        private final Set<String> confirmedSpaces = new HashSet<>();
        /** Quellen, deren Suchgeneration dieser Lauf mit dem Commit erhöht. */
        private final Set<String> touchedSources = new HashSet<>();
        private DirectoryReader uncommitted;
        private boolean done;
        private boolean dirty;

        private Batch() {
        }

        /**
         * Speichert den Snapshot als aktuellen Stand seiner Seite; die aktuellen Kopfdaten werden die des Snapshots.
         * Dazu ein Eintrag im Verlauf seiner Revision.
         */
        public void save(PageSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            requireActive();
            if (snapshot.payload().length() > maxPayloadBytes) {
                throw new IllegalArgumentException("Payload von " + snapshot.key() + " ist " + snapshot.payload()
                        .length() + " Bytes groß, zulässig sind " + maxPayloadBytes + "; sie wird nicht gekürzt");
            }
            dirty = true;
            touchedSources.add(snapshot.key().sourceId());
            write(() -> {
                writer.updateDocument(PageCacheSchema.pageTerm(snapshot.key()),
                        PageCacheSchema.page(snapshot.header(), snapshot));
                writer.updateDocument(PageCacheSchema.revisionTerm(snapshot.key(),
                        PageCacheSchema.revisionOf(snapshot)), PageCacheSchema.revision(snapshot));
            });
        }

        /** Ersetzt nur die aktuellen Kopfdaten; ein gespeicherter Snapshot bleibt samt seinen Kopfdaten erhalten. */
        public void saveHeader(PageHeader header) {
            Objects.requireNonNull(header, "header");
            requireActive();
            Document existing = lookup(header.key());
            PageSnapshot snapshot = existing != null && PageCacheSchema.hasSnapshot(existing)
                    ? PageCacheSchema.snapshot(existing) : null;
            dirty = true;
            touchedSources.add(header.key().sourceId());
            write(() -> writer.updateDocument(PageCacheSchema.pageTerm(header.key()),
                    PageCacheSchema.page(header, snapshot)));
        }

        /**
         * Entfernt eine Seite samt Verlauf. Nur aus einem bestätigt vollständigen Abgleich aufrufen (#16).
         *
         * @return {@code true}, wenn die Seite bekannt war (bestätigt oder in diesem Lauf gespeichert)
         */
        public boolean remove(PageKey key) {
            Objects.requireNonNull(key, "key");
            requireActive();
            boolean existed = lookup(key) != null;
            dirty = true;
            touchedSources.add(key.sourceId());
            write(() -> {
                writer.deleteDocuments(PageCacheSchema.pageTerm(key));
                writer.deleteDocuments(PageCacheSchema.revisionsOfPage(key));
            });
            return existed;
        }

        /**
         * Entfernt alles einer Quelle (#188): Seiten samt Verlauf, bestätigte Generationen und ihre Seiteneinträge. Die
         * Suchgeneration der Quelle steigt mit dem Commit, damit kein Cursor von vorher weiter gilt.
         */
        public void removeSource(String sourceId) {
            Objects.requireNonNull(sourceId, "sourceId");
            requireActive();
            dirty = true;
            touchedSources.add(sourceId);
            write(() -> writer.deleteDocuments(PageCacheSchema.ofSource(sourceId)));
        }

        /**
         * Merkt {@code next} als neue bestätigte Generation ihres Space vor; sie ersetzt die bisherige samt aller
         * Seiteneinträge und wird mit {@link #commit()} zusammen mit den übrigen Änderungen sichtbar.
         *
         * @throws SyncConflictException wenn {@code next} nicht genau auf die bestätigte Generation folgt; der Lauf ist
         *                               dann zu verwerfen ({@link #close()})
         */
        public void confirm(SyncBaseline next) {
            Objects.requireNonNull(next, "next");
            requireActive();
            Term space = PageCacheSchema.spaceTerm(next.sourceId(), next.spaceKey());
            if (!confirmedSpaces.add(space.text())) {
                throw new SyncConflictException("Generation von " + next.sourceId() + "/" + next.spaceKey()
                        + " ist in diesem Lauf bereits vorgemerkt");
            }
            // Leser sehen nur Commits, und Commits gibt es nur unter der Schreibsperre dieses Laufs: der gelesene
            // Stand ist der zuletzt bestätigte.
            long current = confirmedGeneration(next.sourceId(), next.spaceKey())
                    .map(ConfirmedGeneration::generation).orElse(0L);
            if (next.generation() != current + 1) {
                throw new SyncConflictException("Generation " + next.generation() + " von " + next.sourceId() + "/"
                        + next.spaceKey() + " folgt nicht auf die bestätigte " + current);
            }
            dirty = true;
            touchedSources.add(next.sourceId());
            write(() -> {
                writer.deleteDocuments(PageCacheSchema.baselineOf(next.sourceId(), next.spaceKey()));
                List<Document> entries = new ArrayList<>(next.pages().size());
                for (BaselineEntry entry : next.pages().values()) {
                    entries.add(PageCacheSchema.baselineEntry(next.sourceId(), next.spaceKey(), entry));
                }
                writer.addDocuments(entries);
                writer.updateDocument(space, PageCacheSchema.space(next));
            });
        }

        /** Macht alle Änderungen dieses Laufs in einem Lucene-Commit dauerhaft und sichtbar. */
        public void commit() {
            requireActive();
            try {
                Instant committedAt = Instant.now();
                for (String sourceId : touchedSources) {
                    // Unter der Schreibsperre ist der gelesene Stand der zuletzt bestätigte.
                    long current = first(new TermQuery(PageCacheSchema.sourceTerm(sourceId)), null,
                            PageCacheSchema::sourceGeneration).orElse(0L);
                    writer.updateDocument(PageCacheSchema.sourceTerm(sourceId),
                            PageCacheSchema.source(sourceId, current + 1, committedAt));
                }
                writer.commit();
            } catch (IOException | RuntimeException e) {
                // Nichts Unbestätigtes im Writer zurücklassen, das ein späterer Commit mitnähme.
                try {
                    rollback();
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
                finish();
                throw new PageCacheException("Cache " + path + " kann nicht geschrieben werden", e);
            }
            try {
                searchers.maybeRefreshBlocking();
            } catch (IOException e) {
                throw new PageCacheException("Cache " + path + " ist geschrieben, aber nicht neu lesbar", e);
            } finally {
                finish();
            }
        }

        /** Verwirft die Änderungen, wenn der Lauf nicht bestätigt wurde. */
        @Override
        public void close() {
            if (done) {
                return;
            }
            try {
                if (dirty && !closed) {
                    rollback();
                }
            } finally {
                finish();
            }
        }

        private void requireActive() {
            if (done) {
                throw new IllegalStateException("Schreiblauf ist bereits beendet");
            }
            requireOpen();
        }

        /** Das Seitendokument einschließlich der unbestätigten Änderungen dieses Laufs. */
        private Document lookup(PageKey key) {
            Query query = new TermQuery(PageCacheSchema.pageTerm(key));
            if (!dirty) {
                return first(query, null, doc -> doc).orElse(null);
            }
            try {
                if (uncommitted == null) {
                    uncommitted = DirectoryReader.open(writer);
                } else {
                    DirectoryReader newer = DirectoryReader.openIfChanged(uncommitted, writer);
                    if (newer != null) {
                        uncommitted.close();
                        uncommitted = newer;
                    }
                }
                IndexSearcher searcher = new IndexSearcher(uncommitted);
                TopDocs top = searcher.search(query, 1);
                return top.scoreDocs.length == 0 ? null : searcher.storedFields().document(top.scoreDocs[0].doc);
            } catch (IOException e) {
                throw new PageCacheException("Cache " + path + " kann nicht gelesen werden", e);
            }
        }

        private void finish() {
            done = true;
            try {
                if (uncommitted != null) {
                    uncommitted.close();
                }
            } catch (IOException ignored) {
                // Nur ein Lesezugriff auf den eigenen Lauf; der Lauf selbst ist bereits entschieden.
            } finally {
                uncommitted = null;
                try {
                    if (closed) {
                        // close() hat aufgegeben, auf diesen Lauf zu warten.
                        release();
                    }
                } finally {
                    writeLock.unlock();
                }
            }
        }
    }

    // --- Lebenszyklus ---------------------------------------------------------------------------------------------

    /** Wie lange {@link #close()} auf einen offenen Schreiblauf wartet. */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(10);

    /**
     * Schließt den Cache und gibt die Sperre frei. Ein offener, nicht bestätigter Schreiblauf wird verworfen.
     *
     * @throws PageCacheException wenn ein Schreiblauf eines anderen Threads nicht innerhalb von {@link #CLOSE_WAIT}
     *                            endet; der Cache ist dann geschlossen, und der Lauf gibt Writer und Verzeichnis frei,
     *                            sobald er endet
     */
    @Override
    public void close() {
        close(CLOSE_WAIT);
    }

    void close(Duration wait) {
        boolean locked;
        boolean interrupted = false;
        try {
            locked = writeLock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Erst nach dem Freigeben wieder setzen: Lucene bricht Datei-I/O eines unterbrochenen Threads ab.
            interrupted = true;
            locked = false;
        }
        try {
            if (!locked) {
                // Ein hängender Lauf darf das Herunterfahren nicht aufhalten: Er scheitert an seinem nächsten Schritt,
                // und Batch.finish gibt danach frei. Hat er die Sperre freigegeben, bevor er closed sah (oder war der
                // Aufrufer nur unterbrochen), gibt dieser Thread frei; einer von beiden tut es also immer.
                closed = true;
                if (!writeLock.tryLock()) {
                    throw new PageCacheException(
                            "Cache " + path + ": ein Schreiblauf endet nicht; er wird beim Ende verworfen");
                }
            }
            try {
                closed = true;
                release();
            } finally {
                writeLock.unlock();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Gibt Writer und Verzeichnis frei; nur unter {@link #writeLock}. */
    private void release() {
        if (released) {
            return;
        }
        released = true;
        try {
            searchers.close();
            writer.rollback();
        } catch (IOException e) {
            throw new PageCacheException("Cache " + path + " kann nicht geschlossen werden", e);
        } finally {
            closeQuietly(null, directory);
        }
    }

    // --- intern ---------------------------------------------------------------------------------------------------

    private static IndexWriterConfig writerConfig() {
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        // Nur ein ausdrücklicher Commit macht Änderungen dauerhaft; Schließen verwirft Unbestätigtes.
        config.setCommitOnClose(false);
        return config;
    }

    private static void requireSchema(Directory directory, Path root) throws IOException {
        String schema = SegmentInfos.readLatestCommit(directory).getUserData().get(PageCacheSchema.SCHEMA_KEY);
        if (!PageCacheSchema.VERSION.equals(schema)) {
            throw new PageCacheException("Cache " + root + " hat Schema " + (schema == null ? "(unbekannt)" : schema)
                    + ", erwartet " + PageCacheSchema.VERSION + "; Verzeichnis löschen und neu ernten");
        }
    }

    /** Verwirft unbestätigte Änderungen; Lucene schließt dabei den Writer, daher wird er neu geöffnet. */
    private void rollback() {
        try {
            writer.rollback();
            writer = new IndexWriter(directory, writerConfig());
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, PageCacheSchema.VERSION).entrySet());
        } catch (IOException e) {
            throw new PageCacheException("Cache " + path + " kann nach einem abgebrochenen Schreiblauf nicht wieder "
                    + "geöffnet werden", e);
        }
    }

    private BooleanQuery.Builder pages(String sourceId) {
        return new BooleanQuery.Builder()
                .add(exact(PageCacheSchema.TYPE, PageCacheSchema.PAGE), BooleanClause.Occur.FILTER)
                .add(exact(PageCacheSchema.SOURCE_ID, sourceId), BooleanClause.Occur.FILTER);
    }

    private static Query exact(String field, String value) {
        return new TermQuery(new Term(field, Objects.requireNonNull(value, field)));
    }

    private List<CachedPageHeader> headers(Query query, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit muss zwischen 1 und " + MAX_PAGE_SIZE + " liegen");
        }
        return read(searcher -> {
            TopDocs top = searcher.search(query, limit, new Sort(new SortedSetSortField(PageCacheSchema.PAGE_ID,
                    false)));
            StoredFields stored = searcher.storedFields();
            List<CachedPageHeader> headers = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                Document doc = stored.document(hit.doc, HEADER_FIELDS);
                headers.add(new CachedPageHeader(PageCacheSchema.header(doc),
                        doc.get(PageCacheSchema.RAW_SHA256)));
            }
            return headers;
        });
    }

    /** Das erste Dokument zur Abfrage, abgebildet; ein {@code null} des Mappers ergibt leer. */
    private <T> Optional<T> first(Query query, Set<String> fields, Function<Document, T> mapper) {
        return read(searcher -> {
            TopDocs top = searcher.search(query, 1);
            if (top.scoreDocs.length == 0) {
                return Optional.<T>empty();
            }
            StoredFields stored = searcher.storedFields();
            int doc = top.scoreDocs[0].doc;
            return Optional.ofNullable(mapper.apply(fields == null ? stored.document(doc)
                    : stored.document(doc, fields)));
        });
    }

    interface SearcherCall<T> {
        T apply(IndexSearcher searcher) throws IOException;
    }

    /** Liest aus dem zuletzt bestätigten Commit; alle Abfragen eines Aufrufs sehen denselben Stand. */
    <T> T read(SearcherCall<T> call) {
        requireOpen();
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                return call.apply(searcher);
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new PageCacheException("Cache " + path + " kann nicht gelesen werden", e);
        }
    }

    private interface WriterCall {
        void run() throws IOException;
    }

    private void write(WriterCall call) {
        try {
            call.run();
        } catch (IOException e) {
            throw new PageCacheException("Cache " + path + " kann nicht geschrieben werden", e);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Seiten-Cache ist geschlossen");
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
