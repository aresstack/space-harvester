package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.PageCacheSchema.HEADER_FIELDS;

import com.aresstack.spaceharvester.application.query.PageCacheReader.ChildOrderState;
import com.aresstack.spaceharvester.application.acquisition.FindingCataloging;
import com.aresstack.spaceharvester.application.changelog.ChangeEntry;
import com.aresstack.spaceharvester.application.changelog.ChangeEntries;
import com.aresstack.spaceharvester.application.changelog.ChangeLogStore;
import com.aresstack.spaceharvester.application.changelog.ChangeQuery;
import com.aresstack.spaceharvester.application.changelog.ChangeWindow;
import com.aresstack.spaceharvester.application.changelog.ContentChange;
import com.aresstack.spaceharvester.application.changelog.ContentDiffer;
import com.aresstack.spaceharvester.application.changelog.PageEditors;
import com.aresstack.spaceharvester.application.changelog.TextDiff;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.changelog.StoredSummary;
import com.aresstack.spaceharvester.application.changelog.UpdateMark;
import com.aresstack.spaceharvester.application.holdings.HoldingsChanges;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.application.sync.BaselineEntry;
import com.aresstack.spaceharvester.application.sync.SyncBaseline;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import com.aresstack.spaceharvester.application.sync.SyncConflictException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
 * <p><b>Meldungen.</b> Nach jedem Commit meldet der Lauf dem Bestand ({@link #changes}, ADR 0027), welche Seiten
 * welcher Spaces er geschrieben, entfernt oder gesperrt hat; eine neue Generation meldet ihren Space, das Entfernen
 * einer Quelle die Quelle. Eine verschobene Seite meldet auch ihren bisherigen Space. Ohne Empfänger merkt sich ein
 * Lauf nichts dafür.
 *
 * <p>Keine relationalen Transaktionen: ein Batch ist atomar gegenüber Lesern und Abstürzen, aber es gibt nur einen
 * Schreiber zur Zeit und keine Isolation zwischen mehreren gleichzeitigen Läufen.
 */
public final class LucenePageCache implements SnapshotRepository, ChangeLogStore, AutoCloseable {

    /** Standardobergrenze einer Payload, wie {@code maxResponseBytes} des Upstream-Transports (ADR 0002). */
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 20 * 1024 * 1024;
    /** Obergrenze einer Seite in Listenabfragen. */
    public static final int MAX_PAGE_SIZE = 1000;
    private static final int SNAPSHOT_BATCH = 500;
    private static final System.Logger LOG = System.getLogger(LucenePageCache.class.getName());
    /** Nur der Space einer Seite, um eine Verschiebung zu melden. */
    private static final Set<String> SPACE_AND_PARENT = Set.of(PageCacheSchema.SPACE_KEY, PageCacheSchema.PARENT_ID);
    private static final String WITHHELD_SPACE = PageCacheSchema.WITHHELD + "." + PageCacheSchema.SPACE_KEY;
    private static final String WITHHELD_PARENT = PageCacheSchema.WITHHELD + "." + PageCacheSchema.PARENT_ID;

    private final Path path;
    private final Directory directory;
    private final int maxPayloadBytes;
    private final SearcherManager searchers;
    private final ReentrantLock writeLock = new ReentrantLock();
    private IndexWriter writer;
    private volatile boolean closed;
    private volatile HoldingsChanges changes = HoldingsChanges.NONE;
    /** Wem neue Zeilen und Marken gemeldet werden (ADR 0030), je Quelle nach dem Commit. */
    private volatile ContentDiffer contentDiffer = ContentDiffer.NONE;
    /** Bearbeiter einer gespeicherten Fassung für die Protokollzeile (ADR 0031). */
    private volatile PageEditors editors = PageEditors.NONE;
    /**
     * Obergrenze der Seiten und Payload-Bytes, die ein Schreiblauf bis zum Commit für Unterschiede vorhält (CL5);
     * darüber bekommen weitere Seiten des Laufs keinen Unterschied, der Lauf selbst geht weiter.
     */
    private volatile int diffPageBudget = 500;
    private volatile long diffByteBudget = 64L * 1024 * 1024;
    private volatile java.util.function.Consumer<String> changeLogRecorded = sourceId -> {
    };
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

    /**
     * Wem jeder Commit seine Änderungen meldet (Bestand, ADR 0027). Gilt für Schreibläufe, die danach beginnen; die
     * Meldung kommt nach dem Commit, wenn die Schreibsperre des Laufs frei ist, und darf nur vormerken.
     */
    public void changes(HoldingsChanges changes) {
        this.changes = Objects.requireNonNull(changes, "changes");
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
     * ein Abgleich oder ein anderer Abruf inzwischen abgelegt hat. Kopfdaten ohne Inhalt, die inzwischen etwa das
     * Katalogisieren eines Fundes abgelegt hat (ADR 0016), halten den Abruf nicht auf, solange sie nicht neuer sind.
     *
     * @param expectedState {@link #state} vor dem Abruf
     * @return ob gespeichert wurde
     */
    public boolean trySave(PageSnapshot snapshot, String expectedState) {
        return saveIfUnchanged(snapshot, expectedState, FILL_WAIT);
    }

    /** Längste Wartezeit von {@link #saveWaiting} auf die Schreibsperre. */
    static final Duration ACQUIRE_WAIT = Duration.ofSeconds(60);

    /**
     * Wie {@link #trySave}, wartet aber bis {@link #ACQUIRE_WAIT} auf die Schreibsperre: für die Beschaffung (ADR 0016),
     * die auf eigenen Threads läuft und deren Seite sonst verloren wäre, obwohl sie schon geholt ist. Gleichzeitige
     * Ablagen (Katalogisieren der Funde, die übrigen Funde desselben Stapels) halten die Sperre je für einen Commit;
     * unter Last dauert das länger als {@link #FILL_WAIT}. Die Prüfung auf den vorher gelesenen Stand bleibt.
     *
     * @return ob gespeichert wurde
     */
    public boolean saveWaiting(PageSnapshot snapshot, String expectedState) {
        return saveIfUnchanged(snapshot, expectedState, ACQUIRE_WAIT);
    }

    private boolean saveIfUnchanged(PageSnapshot snapshot, String expectedState, Duration wait) {
        requireOpen();
        try {
            if (!writeLock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS)) {
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
            StoredPage now = stored(snapshot.key()).orElse(null);
            if (!Objects.equals(now == null ? null : state(now), expectedState)
                    && !onlyCataloguedSince(now, expectedState, snapshot)) {
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

    /**
     * Ob seit dem gelesenen Stand nur Kopfdaten ohne Inhalt hinzukamen, etwa durch das Katalogisieren eines Fundes
     * ({@link #tryCatalog}), die nicht neuer sind als der Abruf: vorher und jetzt keine Payload, und die Kopfdaten sind
     * dieselben wie die des Abrufs oder wurden vor ihm beobachtet. Dann verdrängt der Abruf nichts Neueres.
     */
    private static boolean onlyCataloguedSince(StoredPage now, String expectedState, PageSnapshot snapshot) {
        if (now == null || now.snapshot() != null || expectedState != null && !expectedState.endsWith(":-")) {
            return false;
        }
        PageHeader header = now.header();
        return header.metadataHash().equals(snapshot.metadataHash())
                && header.attributesHash().equals(snapshot.attributesHash())
                || header.observedAt().isBefore(snapshot.retrievedAt());
    }

    /**
     * Der Katalogeintrag einer Seite für das Katalogisieren von Funden (ADR 0016), ohne Payload: gesperrt oder mit der
     * Version seiner Kopfdaten; leer, wenn der Katalog die Seite nicht kennt.
     */
    public Optional<FindingCataloging.Entry> catalogEntry(PageKey key) {
        Objects.requireNonNull(key, "key");
        return read(searcher -> {
            if (searcher.count(new TermQuery(PageCacheSchema.withheldTerm(key))) > 0) {
                return Optional.of(new FindingCataloging.Entry(true, null));
            }
            TopDocs top = searcher.search(new TermQuery(PageCacheSchema.pageTerm(key)), 1);
            if (top.scoreDocs.length == 0) {
                return Optional.<FindingCataloging.Entry>empty();
            }
            Document doc = searcher.storedFields().document(top.scoreDocs[0].doc,
                    Set.of(PageCacheSchema.VERSION_FIELD));
            return Optional.of(new FindingCataloging.Entry(false, doc.get(PageCacheSchema.VERSION_FIELD)));
        });
    }

    /**
     * Legt die Kopfdaten eines Fundes der Quellensuche in den Katalog (ADR 0016), wie {@link #saveHeader}, aber nur,
     * wenn binnen {@link #FILL_WAIT} kein anderer Schreiblauf offen ist, und nur, wenn die Seite fehlt oder ihr Eintrag
     * eine andere Version nennt. Eine gesperrte Seite (zurückgehaltener Stand oder in der bestätigten Generation ihres
     * Space gesperrt) bekommt nie wieder ein Seitendokument; neuere Kopfdaten (später beobachtet) bleiben; ohne Version
     * ersetzt der Fund keinen Eintrag. Ein gespeicherter Inhalt bleibt erhalten.
     */
    public FindingCataloging.Outcome tryCatalog(PageHeader header) {
        Objects.requireNonNull(header, "header");
        requireOpen();
        try {
            if (!writeLock.tryLock(FILL_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                return FindingCataloging.Outcome.BUSY;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FindingCataloging.Outcome.BUSY;
        }
        try {
            if (closed) {
                return FindingCataloging.Outcome.BUSY;
            }
            PageKey key = header.key();
            String spaceKey = header.metadata().partitionKey();
            // Unter der Schreibsperre ist der gelesene Stand der zuletzt bestätigte.
            boolean withheld = read(searcher -> searcher.count(new TermQuery(PageCacheSchema.withheldTerm(key))) > 0)
                    || first(new TermQuery(PageCacheSchema.spaceTerm(key.sourceId(), spaceKey)),
                    Set.of(PageCacheSchema.WITHHELD_PAGE), doc -> PageCacheSchema.withheldPageIds(doc)
                            .containsKey(key.pageId())).orElse(false);
            if (withheld) {
                return FindingCataloging.Outcome.WITHHELD;
            }
            Document existing = first(new TermQuery(PageCacheSchema.pageTerm(key)), CATALOG_FIELDS, doc -> doc)
                    .orElse(null);
            if (existing != null) {
                String current = existing.get(PageCacheSchema.VERSION_FIELD);
                String observed = existing.get(PageCacheSchema.OBSERVED_AT);
                if (header.revision() == null || header.revision().equals(current)
                        || observed != null && Instant.parse(observed).isAfter(header.observedAt())) {
                    return FindingCataloging.Outcome.UNCHANGED;
                }
            }
            boolean loaded = existing != null && existing.get(PageCacheSchema.RAW_SHA256) != null;
            String loadedVersion = existing == null ? null
                    : existing.get(PageCacheSchema.SNAPSHOT + PageCacheSchema.VERSION_FIELD);
            // Die Sperre ist wiedereintrittsfähig: begin() nimmt sie ein zweites Mal, der Lauf gibt sie wieder ab.
            try (Batch batch = begin()) {
                batch.saveHeader(header);
                batch.commit();
            }
            if (!loaded) {
                return FindingCataloging.Outcome.NOT_LOADED;
            }
            return header.revision().equals(loadedVersion) ? FindingCataloging.Outcome.CURRENT
                    : FindingCataloging.Outcome.OUTDATED;
        } finally {
            writeLock.unlock();
        }
    }

    /** Gespeicherte Felder für {@link #tryCatalog}: Version und Beobachtungszeit der Kopfdaten, Stand des Inhalts. */
    private static final Set<String> CATALOG_FIELDS = Set.of(PageCacheSchema.VERSION_FIELD,
            PageCacheSchema.OBSERVED_AT, PageCacheSchema.RAW_SHA256,
            PageCacheSchema.SNAPSHOT + PageCacheSchema.VERSION_FIELD);

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
        return headers(after(pages(sourceId)
                .add(exact(PageCacheSchema.SPACE_KEY, spaceKey), BooleanClause.Occur.FILTER), afterPageId)
                .build(), limit);
    }

    /**
     * Seiten einer Quelle über alle Spaces nach ihren aktuellen Kopfdaten, nach Seiten-ID aufsteigend, ohne Payload;
     * etwa um nach einem Wechsel der KI-Freigabe alle Seiten neu anzureichern (ADR 0015).
     *
     * @param afterPageId nur Seiten mit größerer Seiten-ID (Fortsetzung einer vorigen Liste) oder {@code null}
     * @param limit       höchstens so viele Einträge, 1 bis {@link #MAX_PAGE_SIZE}
     */
    public List<CachedPageHeader> sourcePages(String sourceId, String afterPageId, int limit) {
        return headers(after(pages(sourceId), afterPageId).build(), limit);
    }

    /** Schränkt eine Liste nach Seiten-ID auf die Fortsetzung nach {@code afterPageId} ein; {@code null} = von vorn. */
    private static BooleanQuery.Builder after(BooleanQuery.Builder query, String afterPageId) {
        if (afterPageId != null) {
            query.add(TermRangeQuery.newStringRange(PageCacheSchema.PAGE_ID, afterPageId, null, false, false),
                    BooleanClause.Occur.FILTER);
        }
        return query;
    }

    /**
     * Die gespeicherten Snapshots einer Quelle über alle Spaces nach Seiten-ID, je {@value #SNAPSHOT_BATCH} Kopfdaten
     * gelesen; Seiten nur mit Kopfdaten fehlen. Jeder Snapshot wird erst beim Weiterlesen geholt.
     */
    public Iterator<PageSnapshot> snapshots(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        return new Iterator<>() {
            private List<CachedPageHeader> batch = sourcePages(sourceId, null, SNAPSHOT_BATCH);
            private int position;
            private PageSnapshot next;

            @Override
            public boolean hasNext() {
                while (next == null) {
                    if (position == batch.size()) {
                        if (batch.size() < SNAPSHOT_BATCH) {
                            return false;
                        }
                        batch = sourcePages(sourceId, batch.getLast().header().key().pageId(), SNAPSHOT_BATCH);
                        position = 0;
                        continue;
                    }
                    next = find(batch.get(position++).header().key()).orElse(null);
                }
                return true;
            }

            @Override
            public PageSnapshot next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                PageSnapshot found = next;
                next = null;
                return found;
            }
        };
    }

    /**
     * Was der Cache von einem Space kennt (ADR 0014, Punkt 2): je Seite mit gespeichertem Snapshot, deren aktuelle
     * Kopfdaten in diesem Space liegen, der Stand dieses Snapshots, auch ohne bestätigte Generation, etwa durch Suche
     * oder Abruf. Ohne Payload.
     */
    public Map<String, BaselineEntry> knownPages(String sourceId, String spaceKey) {
        Query query = pages(sourceId).add(exact(PageCacheSchema.SPACE_KEY, spaceKey), BooleanClause.Occur.FILTER)
                .build();
        return read(searcher -> {
            int count = searcher.count(query);
            Map<String, BaselineEntry> known = new TreeMap<>();
            if (count == 0) {
                return known;
            }
            StoredFields stored = searcher.storedFields();
            for (ScoreDoc hit : searcher.search(query, count).scoreDocs) {
                Document doc = stored.document(hit.doc, PageCacheSchema.KNOWN_FIELDS);
                BaselineEntry entry = PageCacheSchema.known(doc);
                if (entry != null) {
                    known.put(entry.pageId(), entry);
                }
            }
            return known;
        });
    }

    /**
     * Direkte Kinder einer Seite nach aktuellen Kopfdaten, über alle Spaces der Quelle, nach Seiten-ID.
     *
     * @param afterPageId nur Seiten mit größerer Seiten-ID (Fortsetzung einer vorigen Liste) oder {@code null}
     * @param limit       höchstens so viele Einträge, 1 bis {@link #MAX_PAGE_SIZE}
     */
    public List<CachedPageHeader> children(PageKey parent, String afterPageId, int limit) {
        Objects.requireNonNull(parent, "parent");
        return headers(after(pages(parent.sourceId())
                .add(exact(PageCacheSchema.PARENT_ID, parent.pageId()), BooleanClause.Occur.FILTER), afterPageId)
                .build(), limit);
    }

    /** Alle direkten Kinder einer Seite, je {@link #MAX_PAGE_SIZE} gelesen, ohne Gesamtgrenze; nach Seiten-ID. */
    public List<CachedPageHeader> allChildren(PageKey parent) {
        List<CachedPageHeader> all = new ArrayList<>();
        List<CachedPageHeader> batch = children(parent, null, MAX_PAGE_SIZE);
        while (!batch.isEmpty()) {
            all.addAll(batch);
            batch = batch.size() < MAX_PAGE_SIZE ? List.of()
                    : children(parent, batch.getLast().header().metadata().pageId(), MAX_PAGE_SIZE);
        }
        return all;
    }

    /** Ob der Cache direkte Kinder der Seite kennt, über alle Spaces der Quelle. */
    public boolean hasChildren(PageKey parent) {
        Objects.requireNonNull(parent, "parent");
        Query query = pages(parent.sourceId()).add(exact(PageCacheSchema.PARENT_ID, parent.pageId()),
                BooleanClause.Occur.FILTER).build();
        return read(searcher -> searcher.count(query) > 0);
    }

    /**
     * Die Seiten-IDs der Kinder in der Reihenfolge, in der die Quelle sie zuletzt lieferte; leer, wenn unbekannt.
     *
     * @param parentPageId Elternseite oder {@code null} für die Wurzelseiten von {@code spaceKey}
     */
    public List<String> childOrder(String sourceId, String spaceKey, String parentPageId) {
        return childOrderState(sourceId, spaceKey, parentPageId).pageIds();
    }

    public ChildOrderState childOrderState(
            String sourceId, String spaceKey, String parentPageId) {
        Objects.requireNonNull(sourceId, "sourceId");
        Term term = PageCacheSchema.childOrderTerm(sourceId, spaceKey, parentPageId);
        return read(searcher -> {
            TopDocs top = searcher.search(new TermQuery(term), 1);
            if (top.scoreDocs.length == 0) {
                return new ChildOrderState(null, java.util.Collections.emptyList());
            }
            Document document = searcher.storedFields().document(top.scoreDocs[0].doc);
            return new ChildOrderState(
                    PageCacheSchema.childOrderRevision(document), PageCacheSchema.childOrder(document));
        });
    }

    /** Replace the display hint without affecting the search generation. */
    public void saveChildOrder(String sourceId, String spaceKey, String parentPageId, List<String> pageIds) {
        try (Batch batch = begin()) {
            writeChildOrder(batch, sourceId, spaceKey, parentPageId, pageIds);
        }
    }

    /** Compare and update under the same write lock. */
    public Optional<String> compareAndSetChildOrder(String sourceId, String spaceKey, String parentPageId,
                                                     String expectedRevision, List<String> pageIds) {
        // Do not wait for an unrelated inventory write just to persist a display hint.
        if (!writeLock.tryLock()) {
            return Optional.empty();
        }
        try (Batch batch = begin()) {
            if (!Objects.equals(expectedRevision, childOrderState(sourceId, spaceKey, parentPageId).revision())) {
                return Optional.empty();
            }
            return Optional.of(writeChildOrder(batch, sourceId, spaceKey, parentPageId, pageIds));
        } finally {
            writeLock.unlock();
        }
    }

    private String writeChildOrder(Batch batch, String sourceId, String spaceKey, String parentPageId,
                                   List<String> pageIds) {
        Objects.requireNonNull(pageIds, "pageIds");
        Term term = PageCacheSchema.childOrderTerm(sourceId, spaceKey, parentPageId);
        String revision = java.util.UUID.randomUUID().toString();
        batch.dirty = true;
        write(() -> writer.updateDocument(term,
                PageCacheSchema.childOrder(sourceId, spaceKey, parentPageId, pageIds, revision)));
        batch.commit();
        return revision;
    }


    /**
     * Seiten eines Space ohne Elternseite nach aktuellen Kopfdaten, nach Seiten-ID; ohne Payload.
     *
     * @param afterPageId nur Seiten mit größerer Seiten-ID (Fortsetzung einer vorigen Liste) oder {@code null}
     * @param limit       höchstens so viele Einträge, 1 bis {@link #MAX_PAGE_SIZE}
     */
    public List<CachedPageHeader> rootPages(String sourceId, String spaceKey, String afterPageId, int limit) {
        return headers(after(pages(sourceId)
                .add(exact(PageCacheSchema.SPACE_KEY, spaceKey), BooleanClause.Occur.FILTER)
                .add(new FieldExistsQuery(PageCacheSchema.PARENT_ID), BooleanClause.Occur.MUST_NOT), afterPageId)
                .build(), limit);
    }

    /** Anzahl der bekannten Seiten einer Quelle nach aktuellen Kopfdaten. */
    @Override
    public long pageCount(String sourceId) {
        Query query = pages(sourceId).build();
        return read(searcher -> searcher.count(query));
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

    /** Alle Spaces mit bestätigter Generation, nach Quelle und Space. */
    public List<SyncBaselineStore.SpaceRef> confirmedSpaces() {
        return read(searcher -> {
            Query spaces = new TermQuery(new Term(PageCacheSchema.TYPE, PageCacheSchema.SPACE));
            int count = searcher.count(spaces);
            List<SyncBaselineStore.SpaceRef> refs = new ArrayList<>(count);
            if (count > 0) {
                StoredFields stored = searcher.storedFields();
                for (ScoreDoc hit : searcher.search(spaces, count).scoreDocs) {
                    Document space = stored.document(hit.doc);
                    refs.add(new SyncBaselineStore.SpaceRef(
                            space.get(PageCacheSchema.SPACE_DOC + PageCacheSchema.SOURCE_ID),
                            space.get(PageCacheSchema.SPACE_DOC + PageCacheSchema.SPACE_KEY)));
                }
            }
            refs.sort(java.util.Comparator.comparing(SyncBaselineStore.SpaceRef::sourceId)
                    .thenComparing(SyncBaselineStore.SpaceRef::spaceKey));
            return refs;
        });
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

    /**
     * Anzahl aller Dokumente im Index (Seiten, Revisionen, Spaces) ohne Änderungsprotokoll, Update-Marken und
     * Zusammenfassungen, für
     * Betrieb und Tests.
     */
    int documentCount() {
        return read(searcher -> searcher.count(new BooleanQuery.Builder()
                .add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
                .add(ChangeLogSchema.all(), BooleanClause.Occur.MUST_NOT)
                .add(ChangeLogSchema.allMarks(), BooleanClause.Occur.MUST_NOT)
                .add(ChangeLogSchema.allSummaries(), BooleanClause.Occur.MUST_NOT)
                .add(ChangeLogSchema.allDiffs(), BooleanClause.Occur.MUST_NOT).build()));
    }

    // --- Änderungsprotokoll (ADR 0030) ----------------------------------------------------------------------------

    /**
     * Die Protokollzeilen einer Quelle in der Reihenfolge ihres Entstehens; Grundlage der Abfragen in
     * {@code ChangeLog} (CL2).
     */
    @Override
    public List<ChangeEntry> changeEntries(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        List<ChangeEntry> entries = read(searcher -> {
            Query query = new BooleanQuery.Builder().add(ChangeLogSchema.all(), BooleanClause.Occur.FILTER)
                    .add(new TermQuery(new Term(ChangeLogSchema.SOURCE_ID, sourceId)), BooleanClause.Occur.FILTER)
                    .build();
            TopDocs top = searcher.search(query, Integer.MAX_VALUE);
            StoredFields stored = searcher.storedFields();
            List<ChangeEntry> found = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                found.add(ChangeLogSchema.entry(stored.document(hit.doc)));
            }
            return found;
        });
        entries.sort(Comparator.comparing(ChangeEntry::at));
        return entries;
    }

    /** Treffer je Lesegang beim seitenweisen Lesen des Protokolls; Tests setzen weniger. */
    int changeBatch = 500;

    /**
     * Die Zeilen zu {@code query}, gefiltert und nach Zeit geordnet im Index; nur {@code view} (die Rechte je Zeile)
     * prüft im Speicher. Gelesen wird in Gängen zu {@link #changeBatch} Treffern.
     */
    @Override
    public ChangeEntries changeEntries(ChangeQuery query, java.util.function.UnaryOperator<ChangeEntry> view,
                                       int offset, int limit) {
        Objects.requireNonNull(query, "query");
        ChangeWindow window = new ChangeWindow(view, offset, limit);
        Query lucene = ChangeLogSchema.query(query);
        Sort order = ChangeLogSchema.order(query);
        int batch = changeBatch;
        read(searcher -> {
            StoredFields stored = searcher.storedFields();
            ScoreDoc after = null;
            while (true) {
                TopDocs top = after == null ? searcher.search(lucene, batch, order)
                        : searcher.searchAfter(after, lucene, batch, order);
                for (ScoreDoc hit : top.scoreDocs) {
                    window.offer(ChangeLogSchema.entry(stored.document(hit.doc)));
                }
                if (top.scoreDocs.length < batch) {
                    return null;
                }
                after = top.scoreDocs[top.scoreDocs.length - 1];
            }
        });
        return window.result();
    }

    /** Die Unterschiede beim Ersetzen des Inhalts einer Seite nach {@code after}, älteste zuerst (ADR 0030, CL5). */
    @Override
    public List<ContentChange> contentChanges(PageKey key, Instant after, int limit) {
        Objects.requireNonNull(key, "key");
        if (limit <= 0) {
            return List.of();
        }
        return read(searcher -> {
            TopDocs top = searcher.search(ChangeLogSchema.diffsOf(key, after), limit, ChangeLogSchema.diffOrder());
            StoredFields stored = searcher.storedFields();
            List<ContentChange> found = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                found.add(ChangeLogSchema.diff(stored.document(hit.doc)));
            }
            return found;
        });
    }

    /**
     * Bildet beim Ersetzen eines Inhalts den Unterschied der Lesetexte (ADR 0030, CL5); ohne bleibt es bei den Zeilen.
     */
    public void contentDiffer(ContentDiffer differ) {
        this.contentDiffer = Objects.requireNonNull(differ, "differ");
    }

    /** Liest beim Speichern eines neuen Inhalts den Bearbeiter für die Protokollzeile (ADR 0031). */
    public void editors(PageEditors editors) {
        this.editors = Objects.requireNonNull(editors, "editors");
    }

    /** Für Tests: Obergrenze der vorgehaltenen Seiten und Bytes je Schreiblauf (CL5). */
    void diffBudget(int pages, long bytes) {
        this.diffPageBudget = pages;
        this.diffByteBudget = bytes;
    }

    @Override
    public void changeLogRecorded(java.util.function.Consumer<String> recorded) {
        this.changeLogRecorded = Objects.requireNonNull(recorded, "recorded");
    }

    private void recorded(Collection<String> sourceIds) {
        for (String sourceId : sourceIds) {
            try {
                changeLogRecorded.accept(sourceId);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Meldung des Änderungsprotokolls gescheitert ({0})",
                        e.getClass().getName());
            }
        }
    }

    /** Die Update-Marken einer Quelle, älteste zuerst (ADR 0030, Abschnitt 3). */
    @Override
    public List<UpdateMark> marks(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        List<UpdateMark> marks = read(searcher -> {
            TopDocs top = searcher.search(ChangeLogSchema.marksOf(sourceId), Integer.MAX_VALUE);
            StoredFields stored = searcher.storedFields();
            List<UpdateMark> found = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                found.add(ChangeLogSchema.mark(stored.document(hit.doc)));
            }
            return found;
        });
        marks.sort(Comparator.comparing(UpdateMark::at));
        return marks;
    }

    /** Legt eine Update-Marke in einem eigenen Commit ab; dieselbe Quelle zum selben Zeitpunkt ersetzt die vorige. */
    @Override
    public void mark(UpdateMark mark) {
        Objects.requireNonNull(mark, "mark");
        try (Batch batch = begin()) {
            batch.dirty = true;
            write(() -> writer.updateDocument(ChangeLogSchema.markTerm(mark), ChangeLogSchema.mark(mark)));
            batch.commit();
        }
        recorded(List.of(mark.sourceId()));
    }

    // --- Zusammenfassungen (ADR 0030, Abschnitt 5) ---------------------------------------------------------------

    @Override
    public Optional<StoredSummary> summary(String sourceId, String updateId, String spaceKey) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(updateId, "updateId");
        return read(searcher -> {
            TopDocs top = searcher.search(new TermQuery(ChangeLogSchema.summaryTerm(sourceId, updateId, spaceKey)), 1);
            return top.scoreDocs.length == 0 ? Optional.<StoredSummary>empty()
                    : Optional.of(ChangeLogSchema.summary(searcher.storedFields().document(top.scoreDocs[0].doc)));
        });
    }

    @Override
    public Set<String> summarizedUpdates(String sourceId, String spaceKey) {
        Objects.requireNonNull(sourceId, "sourceId");
        return read(searcher -> {
            TopDocs top = searcher.search(ChangeLogSchema.summariesOf(sourceId), Integer.MAX_VALUE);
            StoredFields stored = searcher.storedFields();
            Set<String> found = new HashSet<>();
            for (ScoreDoc hit : top.scoreDocs) {
                org.apache.lucene.document.Document doc = stored.document(hit.doc);
                if (Objects.equals(ChangeLogSchema.summarySpace(doc), spaceKey)) {
                    found.add(ChangeLogSchema.summaryUpdate(doc));
                }
            }
            return found;
        });
    }

    @Override
    public List<StoredSummary> summaries(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        return read(searcher -> {
            TopDocs top = searcher.search(ChangeLogSchema.summariesOf(sourceId), Integer.MAX_VALUE);
            StoredFields stored = searcher.storedFields();
            List<StoredSummary> found = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                found.add(ChangeLogSchema.summary(stored.document(hit.doc)));
            }
            return found;
        });
    }

    /**
     * Legt die Zusammenfassung in einem eigenen Commit ab, ersetzt die vorige desselben Updates und derselben Ebene und
     * meldet die Quelle.
     */
    @Override
    public void saveSummary(StoredSummary summary) {
        Objects.requireNonNull(summary, "summary");
        try (Batch batch = begin()) {
            batch.dirty = true;
            write(() -> writer.updateDocument(
                    ChangeLogSchema.summaryTerm(summary.sourceId(), summary.updateId(), summary.spaceKey()),
                    ChangeLogSchema.summary(summary)));
            batch.commit();
        }
        recorded(List.of(summary.sourceId()));
    }

    @Override
    public void deleteSummary(String sourceId, String updateId, String spaceKey) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(updateId, "updateId");
        try (Batch batch = begin()) {
            batch.dirty = true;
            write(() -> writer.deleteDocuments(ChangeLogSchema.summaryTerm(sourceId, updateId, spaceKey)));
            batch.commit();
        }
    }

    /**
     * Entfernt Protokollzeilen, Update-Marken und Zusammenfassungen vor {@code cutoff} in einem eigenen Commit (ADR 0030,
     * Abschnitt 2); die jüngste Marke jeder Quelle bleibt, damit das nächste Update eine Grenze hat. Nur beim Start und
     * nach einem Abgleich aufgerufen, nie nach Zeitgeber. Unterschiede der Lesetexte (CL5) gehen mit; Seiten und
     * Revisionen bleiben unberührt.
     *
     * @return ob etwas entfernt wurde
     */
    public boolean pruneChangeLog(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        Query expired = new BooleanQuery.Builder()
                .add(ChangeLogSchema.before(cutoff), BooleanClause.Occur.SHOULD)
                .add(ChangeLogSchema.summariesBefore(cutoff), BooleanClause.Occur.SHOULD)
                .add(ChangeLogSchema.diffsBefore(cutoff), BooleanClause.Occur.SHOULD).build();
        List<UpdateMark> expiredMarks = read(searcher -> {
            TopDocs top = searcher.search(ChangeLogSchema.marksBefore(cutoff), Integer.MAX_VALUE);
            StoredFields stored = searcher.storedFields();
            List<UpdateMark> found = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                found.add(ChangeLogSchema.mark(stored.document(hit.doc)));
            }
            return found;
        });
        List<Term> deletedMarks = new ArrayList<>();
        java.util.Map<String, UpdateMark> newest = new java.util.HashMap<>();
        for (UpdateMark mark : expiredMarks) {
            UpdateMark last = newest.computeIfAbsent(mark.sourceId(), id -> marks(id).getLast());
            if (!last.at().equals(mark.at())) {
                deletedMarks.add(ChangeLogSchema.markTerm(mark));
            }
        }
        if (deletedMarks.isEmpty() && read(searcher -> searcher.count(expired)) == 0) {
            return false;
        }
        try (Batch batch = begin()) {
            batch.dirty = true;
            write(() -> {
                writer.deleteDocuments(expired);
                if (!deletedMarks.isEmpty()) {
                    writer.deleteDocuments(deletedMarks.toArray(Term[]::new));
                }
            });
            batch.commit();
        }
        return true;
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
        /** Empfänger der Meldungen nach dem Commit, fest ab Beginn des Laufs. */
        private final HoldingsChanges observer = changes;
        /** Für die Meldung: geänderte Seiten je Space, ganze Spaces (neue Generation), ganze Quellen. */
        private final Map<SyncBaselineStore.SpaceRef, Set<String>> changedPages = new HashMap<>();
        private final Set<SyncBaselineStore.SpaceRef> changedSpaces = new HashSet<>();
        private final Set<String> changedSources = new HashSet<>();
        /** Für das Änderungsprotokoll (ADR 0030): bisheriger und neuer Stand je Seite, Auslöser ab Beginn. */
        private final ChangeRows changeRows = new ChangeRows();
        /** Neuer Inhalt je Seite, der einen schon gespeicherten anderen ersetzt; für den Unterschied (CL5). */
        private final Map<PageKey, PageSnapshot> replacedContent = new LinkedHashMap<>();
        /** Payload-Bytes in {@link #replacedContent}, begrenzt durch {@code diffByteBudget}. */
        private long replacedBytes;
        private final java.util.Set<String> recordedSources = new java.util.LinkedHashSet<>();
        private final ChangeTrigger trigger = ChangeTrigger.current();
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
            Document committed = rememberBefore(snapshot.key());
            if (observed()) {
                // Aus dem bestätigten Stand: eine verschobene Seite fehlt danach in ihrem bisherigen Space und unter
                // ihrer bisherigen Elternseite.
                Optional<Document> previous = committed != null ? Optional.of(committed)
                        : first(new TermQuery(PageCacheSchema.pageTerm(snapshot.key())), SPACE_AND_PARENT, doc -> doc);
                previous.ifPresent(doc -> changedFrom(snapshot.key(), doc.get(PageCacheSchema.SPACE_KEY),
                        doc.get(PageCacheSchema.PARENT_ID)));
                changed(snapshot.key(), snapshot.header().metadata().partitionKey());
            }
            ChangeRows.State state = ChangeRows.State.of(snapshot.header(), snapshot, editor(snapshot));
            changeRows.present(snapshot.key(), state);
            PageSnapshot held = replacedContent.remove(snapshot.key());
            if (held != null) {
                replacedBytes -= held.payload().length();
            }
            if (changeRows.replacesContent(snapshot.key(), state) && contentDiffer != ContentDiffer.NONE
                    && replacedContent.size() < diffPageBudget
                    && replacedBytes + snapshot.payload().length() <= diffByteBudget) {
                replacedContent.put(snapshot.key(), snapshot);
                replacedBytes += snapshot.payload().length();
            }
            dirty = true;
            touchedSources.add(snapshot.key().sourceId());
            write(() -> {
                writer.updateDocument(PageCacheSchema.pageTerm(snapshot.key()),
                        PageCacheSchema.page(snapshot.header(), snapshot));
                writer.updateDocument(PageCacheSchema.revisionTerm(snapshot.key(),
                        PageCacheSchema.revisionOf(snapshot)), PageCacheSchema.revision(snapshot));
                // Eine wieder sichtbare Seite braucht keinen zurückgehaltenen Stand mehr (ADR 0014).
                writer.deleteDocuments(PageCacheSchema.withheldTerm(snapshot.key()));
            });
        }

        /** Ersetzt nur die aktuellen Kopfdaten; ein gespeicherter Snapshot bleibt samt seinen Kopfdaten erhalten. */
        public void saveHeader(PageHeader header) {
            Objects.requireNonNull(header, "header");
            requireActive();
            rememberBefore(header.key());
            Document existing = lookup(header.key());
            PageSnapshot snapshot = existing != null && PageCacheSchema.hasSnapshot(existing)
                    ? PageCacheSchema.snapshot(existing) : null;
            changeRows.present(header.key(), ChangeRows.State.of(header, snapshot));
            if (existing != null) {
                changedFrom(header.key(), existing.get(PageCacheSchema.SPACE_KEY),
                        existing.get(PageCacheSchema.PARENT_ID));
            }
            changed(header.key(), header.metadata().partitionKey());
            dirty = true;
            touchedSources.add(header.key().sourceId());
            write(() -> writer.updateDocument(PageCacheSchema.pageTerm(header.key()),
                    PageCacheSchema.page(header, snapshot)));
        }

        /**
         * Entfernt eine Seite samt Verlauf und zurückgehaltenem Stand. Nur aus einem bestätigt vollständigen Abgleich
         * aufrufen (#16).
         *
         * @return {@code true}, wenn die Seite bekannt war (bestätigt oder in diesem Lauf gespeichert)
         */
        public boolean remove(PageKey key) {
            Objects.requireNonNull(key, "key");
            requireActive();
            rememberBefore(key);
            Document existing = lookup(key);
            boolean existed = existing != null;
            changeRows.removed(key);
            forgetReplaced(key);
            if (existing != null) {
                changedFrom(key, existing.get(PageCacheSchema.SPACE_KEY), existing.get(PageCacheSchema.PARENT_ID));
            } else if (observed()) {
                first(new TermQuery(PageCacheSchema.withheldTerm(key)), Set.of(WITHHELD_SPACE, WITHHELD_PARENT),
                        doc -> doc).ifPresent(withheld -> changedFrom(key, withheld.get(WITHHELD_SPACE),
                                withheld.get(WITHHELD_PARENT)));
            }
            dirty = true;
            touchedSources.add(key.sourceId());
            write(() -> {
                writer.deleteDocuments(PageCacheSchema.pageTerm(key));
                writer.deleteDocuments(PageCacheSchema.revisionsOfPage(key));
                writer.deleteDocuments(PageCacheSchema.withheldTerm(key));
                // Mit dem Inhalt verschwinden seine Unterschiede (ADR 0030, CL5).
                writer.deleteDocuments(ChangeLogSchema.diffsOf(key, null));
            });
            return existed;
        }

        /**
         * Sperrt eine Seite (ADR 0014, {@code INACCESSIBLE}): Das Seitendokument fällt weg, damit keine Ausgabe,
         * Titelsuche oder Auflistung sie mehr findet. Seine gespeicherten Felder samt Rohdaten bleiben als
         * zurückgehaltener Stand ohne Lesepfad erhalten, ebenso der Verlauf, bis {@link #remove} nach Ablauf der Frist
         * beides entfernt.
         */
        public void withhold(PageKey key) {
            Objects.requireNonNull(key, "key");
            requireActive();
            rememberBefore(key);
            Document existing = lookup(key);
            changeRows.withheld(key);
            forgetReplaced(key);
            if (existing != null) {
                changed(key, existing.get(PageCacheSchema.SPACE_KEY));
            }
            dirty = true;
            touchedSources.add(key.sourceId());
            write(() -> {
                if (existing != null) {
                    writer.updateDocument(PageCacheSchema.withheldTerm(key), PageCacheSchema.withheld(key, existing));
                }
                writer.deleteDocuments(PageCacheSchema.pageTerm(key));
                // Gesperrt heißt ohne Lesepfad: auch seine Unterschiede fallen weg (ADR 0030, CL5).
                writer.deleteDocuments(ChangeLogSchema.diffsOf(key, null));
            });
        }

        /**
         * Entfernt alles einer Quelle (#188): Seiten samt Verlauf, bestätigte Generationen und ihre Seiteneinträge. Die
         * Suchgeneration der Quelle steigt mit dem Commit, damit kein Cursor von vorher weiter gilt.
         */
        public void removeSource(String sourceId) {
            Objects.requireNonNull(sourceId, "sourceId");
            requireActive();
            if (observed()) {
                changedSources.add(sourceId);
            }
            // Das Protokoll der Quelle verschwindet mit ihr (ADR 0030), auch Zeilen dieses Laufs von vorher.
            changeRows.sourceRemoved(sourceId);
            List.copyOf(replacedContent.keySet()).stream().filter(key -> key.sourceId().equals(sourceId))
                    .forEach(this::forgetReplaced);
            dirty = true;
            touchedSources.add(sourceId);
            write(() -> {
                writer.deleteDocuments(PageCacheSchema.ofSource(sourceId));
                writer.deleteDocuments(ChangeLogSchema.ofSource(sourceId));
                writer.deleteDocuments(ChangeLogSchema.marksOfSource(sourceId));
                writer.deleteDocuments(ChangeLogSchema.summariesOfSource(sourceId));
                writer.deleteDocuments(ChangeLogSchema.diffsOfSource(sourceId));
            });
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
            if (observed()) {
                changedSpaces.add(new SyncBaselineStore.SpaceRef(next.sourceId(), next.spaceKey()));
            }
            changeRows.generation(next.sourceId(), next.spaceKey(), next.generation());
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
                if (!changeRows.isEmpty()) {
                    // Im selben Commit wie die Änderungen: ein verworfener Lauf schreibt keine Zeile (ADR 0030).
                    List<Document> rows = new ArrayList<>();
                    for (ChangeEntry entry : changeRows.entries(committedAt, trigger)) {
                        rows.add(ChangeLogSchema.change(entry));
                        recordedSources.add(entry.sourceId());
                    }
                    writer.addDocuments(rows);
                }
                writeContentChanges(committedAt);
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
                publish();
                recorded(recordedSources);
            }
        }

        /** Nur für einen anderen Inhalt als den bestätigten: Unveränderte Seiten kosten kein Lesen der Payload. */
        private String editor(PageSnapshot snapshot) {
            PageEditors reader = editors;
            if (reader == PageEditors.NONE || changeRows.sameContent(snapshot.key(), snapshot.payload().rawSha256())) {
                return null;
            }
            try {
                return reader.editor(snapshot).orElse(null);
            } catch (RuntimeException e) {
                // Ohne Meldung: sie könnte einen Namen enthalten.
                LOG.log(System.Logger.Level.WARNING, "Bearbeiter einer Seite in {0} nicht gelesen ({1})",
                        snapshot.key().sourceId(), e.getClass().getName());
                return null;
            }
        }

        private void forgetReplaced(PageKey key) {
            PageSnapshot held = replacedContent.remove(key);
            if (held != null) {
                replacedBytes -= held.payload().length();
            }
        }

        /**
         * Unterschiede der Lesetexte für jeden ersetzten Inhalt (ADR 0030, CL5), im selben Commit wie die Zeilen. Unter
         * der Schreibsperre liest der Sucher noch den bisherigen Inhalt. Ein nicht lesbarer Inhalt ergibt keinen
         * Unterschied, nie einen Fehler des Laufs.
         */
        private void writeContentChanges(Instant committedAt) throws IOException {
            ContentDiffer differ = contentDiffer;
            if (replacedContent.isEmpty() || differ == ContentDiffer.NONE) {
                return;
            }
            List<Document> diffs = new ArrayList<>();
            for (Map.Entry<PageKey, PageSnapshot> replaced : replacedContent.entrySet()) {
                PageKey key = replaced.getKey();
                try {
                    Optional<PageSnapshot> older = first(new TermQuery(PageCacheSchema.pageTerm(key)), null,
                            doc -> PageCacheSchema.hasSnapshot(doc) ? PageCacheSchema.snapshot(doc) : null);
                    if (older.isEmpty()) {
                        continue;
                    }
                    PageSnapshot newer = replaced.getValue();
                    Optional<TextDiff> diff = differ.diff(older.get(), newer);
                    if (diff.isPresent()) {
                        diffs.add(ChangeLogSchema.diff(new ContentChange(key.sourceId(), key.pageId(),
                                older.get().metadata().version(), newer.metadata().version(), committedAt,
                                diff.get().paragraphs(), diff.get().truncated())));
                    }
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Unterschied des Inhalts nicht gebildet ({0})",
                            e.getClass().getName());
                }
            }
            if (!diffs.isEmpty()) {
                writer.addDocuments(diffs);
            }
        }

        private boolean observed() {
            return observer != HoldingsChanges.NONE;
        }

        /** Merkt eine Seite in einem Space für die Meldung nach dem Commit vor. */
        private void changed(PageKey key, String spaceKey) {
            changed(key.sourceId(), spaceKey, key.pageId());
        }

        /**
         * Wie {@link #changed(PageKey, String)} für den bisherigen Stand einer Seite, samt ihrer bisherigen
         * Elternseite: Verschoben oder entfernt, fehlt sie in deren Teilbaum, und die Teilansicht zu den gemeldeten
         * Seiten erreicht den alten Pfad nur über sie.
         */
        private void changedFrom(PageKey key, String spaceKey, String parentPageId) {
            changed(key, spaceKey);
            if (parentPageId != null && !parentPageId.isEmpty()) {
                changed(key.sourceId(), spaceKey, parentPageId);
            }
        }

        private void changed(String sourceId, String spaceKey, String pageId) {
            if (observed() && spaceKey != null) {
                changedPages.computeIfAbsent(new SyncBaselineStore.SpaceRef(sourceId, spaceKey),
                        space -> new HashSet<>()).add(pageId);
            }
        }

        /**
         * Nach dem Commit, ohne die Schreibsperre dieses Laufs: was er geändert hat. Ein ganzer Space oder eine ganze
         * Quelle schließt die einzelnen Seiten darin ein. Ein Fehler des Empfängers ändert nichts am Commit.
         */
        private void publish() {
            if (!observed()) {
                return;
            }
            try {
                for (String sourceId : changedSources) {
                    observer.sourceChanged(sourceId);
                }
                for (SyncBaselineStore.SpaceRef space : changedSpaces) {
                    if (!changedSources.contains(space.sourceId())) {
                        observer.spaceChanged(space.sourceId(), space.spaceKey());
                    }
                }
                changedPages.forEach((space, pageIds) -> {
                    if (!changedSources.contains(space.sourceId()) && !changedSpaces.contains(space)) {
                        observer.pagesChanged(space.sourceId(), space.spaceKey(), pageIds);
                    }
                });
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Meldung an den Bestand scheiterte ({0})",
                        e.getClass().getName());
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

        /**
         * Merkt beim ersten Anfassen einer Seite in diesem Lauf ihren bestätigten Stand für das Änderungsprotokoll.
         *
         * @return das dafür gelesene Seitendokument (nur {@link ChangeRows#FIELDS} samt Space und Elternseite), oder
         *         {@code null}, wenn nichts gelesen wurde oder die Seite unbekannt ist
         */
        private Document rememberBefore(PageKey key) {
            if (changeRows.known(key)) {
                return null;
            }
            Document committed = first(new TermQuery(PageCacheSchema.pageTerm(key)), ChangeRows.FIELDS, doc -> doc)
                    .orElse(null);
            changeRows.before(key, committed);
            return committed;
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
            TragicWriters.rollbackOnClose(writer, "Seiten-Cache");
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
