package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.query.DocumentKind;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.BytesRef;

/**
 * Volltextindex über den Cache ({@link PageTextIndex}, #180) in einem eigenen Lucene-Index neben dem Seiten-Index:
 * ein Dokument je Seite mit Titel und Lesetext, BM25 mit {@link StandardAnalyzer}, Titel doppelt gewichtet. Dazu ein
 * Dokument je Anhang einer Seite mit Dateiname (als Titel) und Text (#183, ab Schema {@code v2}); es trägt Quelle, Seite
 * und Space der Seite, und die Suche liefert je Seite nur ihr bestes Dokument. Er ist
 * abgeleitet: ein Index in anderem oder fehlendem Schema wird beim Öffnen leer neu angelegt und füllt sich beim
 * nächsten Abruf oder Abgleich. Seiten-Index und Payloads bleiben davon unberührt.
 *
 * <p>Wie die Anreicherungsablage macht er nicht jede Änderung einzeln dauerhaft, sondern spätestens alle
 * {@value #COMMIT_EVERY} Änderungen, bei {@link #flush()} und beim Schließen; Lesen sieht jede Änderung sofort.
 */
public final class LucenePageTextIndex implements PageTextIndex, AutoCloseable {

    /**
     * Schemaversion in den Commit-Daten; {@code v2}: Anhänge als eigene Dokumente der Seite (#183); {@code v3}: je
     * Anhang ein Eintrag je Dokument (auch enthaltene Dateien) und Abschnitt (ADR 0026, AP4b).
     */
    public static final String VERSION = "page-text-index/v3";
    /** So viel Text je Seite wird indiziert und für Auszüge gespeichert; der Rest einer sehr langen Seite fehlt. */
    public static final int MAX_TEXT_CHARS = 1_000_000;
    /** Höchstens so viele Treffer je Suche. */
    public static final int MAX_LIMIT = 1_000;

    static final int COMMIT_EVERY = 500;
    /** Länge eines Auszugs ungefähr; vor der Fundstelle ein Drittel. */
    static final int WINDOW = 240;

    private static final String ID = "id";
    private static final String SOURCE_ID = "sourceId";
    private static final String PAGE_ID = "pageId";
    private static final String SPACE_KEY = "spaceKey";
    private static final String TITLE = "title";
    private static final String TEXT = "text";
    private static final String HAS_TEXT = "hasSearchableText";
    private volatile com.aresstack.spaceharvester.application.holdings.HoldingsChanges holdings =
            com.aresstack.spaceharvester.application.holdings.HoldingsChanges.NONE;

    public void holdingsChanges(com.aresstack.spaceharvester.application.holdings.HoldingsChanges changes) {
        holdings = Objects.requireNonNull(changes, "changes");
    }
    private static final String INPUT_SHA256 = "inputSha256";
    /** Art des Dokuments: {@value #PAGE_KIND} oder {@value #ATTACHMENT_KIND}. */
    private static final String KIND = "kind";
    /** Die Seite, zu der ein Dokument gehört (Seite und ihre Anhänge), zum gemeinsamen Entfernen. */
    private static final String PAGE = "page";
    private static final String ATTACHMENT_ID = "attachmentId";
    /** Alle Einträge eines Anhangs einer Seite, zum Ersetzen in einem Schritt. */
    private static final String ATTACHMENT_KEY = "attachmentKey";
    /** Alle Einträge eines Dokuments eines Anhangs (Dokument selbst oder enthaltene Datei). */
    private static final String DOCUMENT_KEY = "documentKey";
    private static final String PART_ID = "partId";
    private static final String PART_TITLE = "partTitle";
    private static final String SECTION = "section";
    private static final String PAGE_KIND = "page";
    private static final String ATTACHMENT_KIND = "attachment";
    /** Medientyp der Quelle, nur gespeichert: entscheidet mit dem Namen über die Art ({@link DocumentKind}). */
    private static final String MEDIA_TYPE = "mediaType";
    /** So viele Dokumente liest eine Suche höchstens, um je Seite das beste zu finden. */
    static final int MAX_DOCUMENTS = 8 * MAX_LIMIT;
    private static final char SEPARATOR = '\u0000';
    private static final int MAX_TERMS = 32;
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final Path path;
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private final Analyzer analyzer;
    private int uncommitted;
    private volatile boolean closed;

    private LucenePageTextIndex(Path path, Directory directory, IndexWriter writer, Analyzer analyzer)
            throws IOException {
        this.path = path;
        this.directory = directory;
        this.writer = writer;
        this.analyzer = analyzer;
        this.searchers = new SearcherManager(writer, null);
    }

    /**
     * Öffnet den Index im Verzeichnis {@code path}; ohne Index oder in anderem Schema wird er leer angelegt.
     *
     * @throws PageCacheException wenn der Index gesperrt oder unlesbar ist
     */
    public static LucenePageTextIndex open(Path path) {
        Objects.requireNonNull(path, "path");
        Path root = path.toAbsolutePath().normalize();
        Directory directory = null;
        IndexWriter writer = null;
        Analyzer analyzer = new StandardAnalyzer();
        try {
            Files.createDirectories(root);
            directory = FSDirectory.open(root);
            boolean current = DirectoryReader.indexExists(directory) && VERSION.equals(SegmentInfos
                    .readLatestCommit(directory).getUserData().get(PageCacheSchema.SCHEMA_KEY));
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(current ? IndexWriterConfig.OpenMode.APPEND : IndexWriterConfig.OpenMode.CREATE);
            config.setCommitOnClose(false);
            writer = new IndexWriter(directory, config);
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, VERSION).entrySet());
            if (!current) {
                writer.commit();
            }
            return new LucenePageTextIndex(root, directory, writer, analyzer);
        } catch (LockObtainFailedException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Volltextindex " + root + " ist von einem anderen Prozess geöffnet", e);
        } catch (IOException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Volltextindex " + root + " kann nicht geöffnet werden", e);
        } catch (RuntimeException e) {
            closeQuietly(writer, directory);
            throw e;
        }
    }

    @Override
    public synchronized void put(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        requireOpen();
        replacePageSections(entry.key(), List.of(entry));
    }

    @Override
    public synchronized void replacePageSections(PageKey page, Iterable<Entry> sections) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(sections, "sections");
        requireOpen();
        String[] space = {null};
        Iterable<Document> documents = () -> new java.util.Iterator<>() {
            private final java.util.Iterator<Entry> source = sections.iterator();

            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public Document next() {
                Entry entry = source.next();
                if (!page.equals(entry.key()) || space[0] != null && !space[0].equals(entry.spaceKey())) {
                    throw new IllegalArgumentException("Section belongs to a different page or space");
                }
                space[0] = entry.spaceKey();
                return pageDocument(entry);
            }
        };
        try {
            writer.updateDocuments(new Term(ID, id(page)), documents);
            changed();
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht geschrieben werden", e);
        }
        if (space[0] != null) {
            holdings.pagesChanged(page.sourceId(), space[0], List.of(page.pageId()));
            respace(page, space[0]);
        } else {
            holdings.sourceChanged(page.sourceId());
        }
    }

    private Document pageDocument(Entry entry) {
        String text = entry.text().length() > MAX_TEXT_CHARS ? entry.text().substring(0, MAX_TEXT_CHARS)
                : entry.text();
        Document doc = new Document();
        // Give all page sections the same replacement key; attachment IDs have a separate namespace.
        doc.add(new StringField(ID, id(entry.key()), Field.Store.NO));
        doc.add(new StringField(KIND, PAGE_KIND, Field.Store.YES));
        doc.add(new StringField(PAGE, id(entry.key()), Field.Store.NO));
        doc.add(new StringField(SOURCE_ID, entry.key().sourceId(), Field.Store.YES));
        doc.add(new StoredField(PAGE_ID, entry.key().pageId()));
        doc.add(new StringField(SPACE_KEY, entry.spaceKey(), Field.Store.YES));
        doc.add(new TextField(TITLE, entry.title(), Field.Store.YES));
        doc.add(new TextField(TEXT, text, Field.Store.YES));
        doc.add(new StoredField(HAS_TEXT, Boolean.toString(
                !terms(text.substring(Math.min(text.length(), entry.metadataPrefixChars()))).isEmpty())));
        doc.add(new StoredField(INPUT_SHA256, entry.inputSha256()));
        if (!entry.mediaType().isEmpty()) {
            doc.add(new StoredField(MEDIA_TYPE, entry.mediaType()));
        }
        return doc;
    }

    /**
     * Hat die Seite den Space gewechselt, folgen ihr die Anhänge sofort, nicht erst bei der nächsten Ernte: Sonst
     * blendete der Space-Filter der Suche sie aus. Text und Stand bleiben; die nächste Ernte schreibt den Stand neu.
     */
    private void respace(PageKey page, String spaceKey) {
        Query moved = new BooleanQuery.Builder()
                .add(new TermQuery(new Term(PAGE, id(page))), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(KIND, ATTACHMENT_KIND)), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(SPACE_KEY, spaceKey)), BooleanClause.Occur.MUST_NOT)
                .build();
        List<AttachmentEntry> entries = read(searcher -> {
            int count = searcher.count(moved);
            if (count == 0) {
                return List.<AttachmentEntry>of();
            }
            StoredFields stored = searcher.storedFields();
            List<AttachmentEntry> found = new java.util.ArrayList<>(count);
            for (ScoreDoc hit : searcher.search(moved, count).scoreDocs) {
                Document doc = stored.document(hit.doc);
                found.add(new AttachmentEntry(page, spaceKey, doc.get(ATTACHMENT_ID), doc.get(TITLE), doc.get(TEXT),
                        doc.get(INPUT_SHA256), doc.get(PART_ID), doc.get(PART_TITLE),
                        Integer.parseInt(Objects.requireNonNullElse(doc.get(SECTION), "0")), doc.get(MEDIA_TYPE)));
            }
            return found;
        });
        try {
            for (AttachmentEntry entry : entries) {
                writer.updateDocument(new Term(ID, entryId(entry)), document(entry));
            }
            if (!entries.isEmpty()) {
                changed();
            }
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public synchronized void removeAttachment(PageKey page, String attachmentId) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(attachmentId, "attachmentId");
        requireOpen();
        try {
            writer.deleteDocuments(new Term(ATTACHMENT_KEY, attachmentId(page, attachmentId)));
            changed();
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public synchronized void putAttachment(AttachmentEntry entry) {
        Objects.requireNonNull(entry, "entry");
        replaceAttachment(entry.page(), entry.attachmentId(), List.of(entry));
    }

    @Override
    public synchronized void replaceAttachment(PageKey page, String attachmentId, Iterable<AttachmentEntry> entries) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(entries, "entries");
        requireOpen();
        replace(new Term(ATTACHMENT_KEY, attachmentId(page, attachmentId)), page, attachmentId, null, entries);
    }

    @Override
    public synchronized void replaceDocument(PageKey page, String attachmentId, String partId,
                                             Iterable<AttachmentEntry> entries) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(partId, "partId");
        Objects.requireNonNull(entries, "entries");
        requireOpen();
        replace(new Term(DOCUMENT_KEY, documentKey(page, attachmentId, partId)), page, attachmentId, partId, entries);
    }

    /**
     * Löscht alle Einträge unter {@code key} und schreibt die neuen in einem Schritt des IndexWriters
     * ({@link IndexWriter#updateDocuments}): Ein Leser sieht den alten oder den neuen Stand. Die Dokumente entstehen
     * erst beim Schreiben, einzeln aus den Einträgen.
     */
    private void replace(Term key, PageKey page, String attachmentId, String partId,
                         Iterable<AttachmentEntry> entries) {
        Iterable<Document> documents = () -> new java.util.Iterator<>() {
            private final java.util.Iterator<AttachmentEntry> source = entries.iterator();

            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public Document next() {
                AttachmentEntry entry = source.next();
                if (!entry.page().equals(page) || !entry.attachmentId().equals(attachmentId)
                        || partId != null && !entry.partId().equals(partId)) {
                    throw new IllegalArgumentException("Eintrag gehört nicht zu diesem Anhang");
                }
                return document(entry);
            }
        };
        try {
            writer.updateDocuments(key, documents);
            changed();
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht geschrieben werden", e);
        }
    }

    private static Document document(AttachmentEntry entry) {
        String text = entry.text().length() > MAX_TEXT_CHARS ? entry.text().substring(0, MAX_TEXT_CHARS)
                : entry.text();
        Document doc = new Document();
        doc.add(new StringField(ID, entryId(entry), Field.Store.NO));
        doc.add(new StringField(KIND, ATTACHMENT_KIND, Field.Store.YES));
        doc.add(new StringField(PAGE, id(entry.page()), Field.Store.NO));
        doc.add(new StringField(ATTACHMENT_KEY, attachmentId(entry.page(), entry.attachmentId()), Field.Store.NO));
        doc.add(new StringField(DOCUMENT_KEY, documentKey(entry.page(), entry.attachmentId(), entry.partId()),
                Field.Store.NO));
        doc.add(new StringField(ATTACHMENT_ID, entry.attachmentId(), Field.Store.YES));
        doc.add(new StringField(SOURCE_ID, entry.page().sourceId(), Field.Store.YES));
        doc.add(new StoredField(PAGE_ID, entry.page().pageId()));
        doc.add(new StringField(SPACE_KEY, entry.spaceKey(), Field.Store.YES));
        doc.add(new TextField(TITLE, entry.title(), Field.Store.YES));
        doc.add(new TextField(TEXT, text, Field.Store.YES));
        doc.add(new StoredField(INPUT_SHA256, entry.inputSha256()));
        if (!entry.partId().isEmpty()) {
            doc.add(new StoredField(PART_ID, entry.partId()));
            // Der Name einer enthaltenen Datei ist durchsuchbar wie der Dateiname des Anhangs.
            doc.add(new TextField(PART_TITLE, entry.partTitle(), Field.Store.YES));
        }
        doc.add(new StoredField(SECTION, Integer.toString(entry.section())));
        if (!entry.mediaType().isEmpty()) {
            doc.add(new StoredField(MEDIA_TYPE, entry.mediaType()));
        }
        return doc;
    }

    @Override
    public Optional<String> attachmentInputSha256(PageKey page, String attachmentId) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(attachmentId, "attachmentId");
        requireOpen();
        return stored(ATTACHMENT_KEY, attachmentId(page, attachmentId), INPUT_SHA256);
    }

    @Override
    public synchronized void retainAttachments(PageKey page, Set<String> attachmentIds) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(attachmentIds, "attachmentIds");
        requireOpen();
        BooleanQuery.Builder stale = new BooleanQuery.Builder()
                .add(new TermQuery(new Term(PAGE, id(page))), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(KIND, ATTACHMENT_KIND)), BooleanClause.Occur.FILTER);
        if (!attachmentIds.isEmpty()) {
            stale.add(new TermInSetQuery(ATTACHMENT_ID, attachmentIds.stream().map(BytesRef::new).toList()),
                    BooleanClause.Occur.MUST_NOT);
        }
        try {
            writer.deleteDocuments(stale.build());
            changed();
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public Optional<DocumentTitles> documentTitles(PageKey page, String attachmentId, String partId) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(attachmentId, "attachmentId");
        requireOpen();
        String key = documentKey(page, attachmentId, partId == null ? "" : partId);
        return read(searcher -> {
            TopDocs top = searcher.search(new TermQuery(new Term(DOCUMENT_KEY, key)), 1);
            if (top.scoreDocs.length == 0) {
                return Optional.<DocumentTitles>empty();
            }
            Document doc = searcher.storedFields().document(top.scoreDocs[0].doc, Set.of(TITLE, PART_TITLE));
            return Optional.of(new DocumentTitles(doc.get(TITLE), doc.get(PART_TITLE)));
        });
    }

    @Override
    public Map<String, ContentState> contentStates(String sourceId, String spaceKey) {
        requireOpen();
        return read(searcher -> {
            Query query = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(SOURCE_ID, sourceId)), BooleanClause.Occur.FILTER)
                    .add(new TermQuery(new Term(SPACE_KEY, spaceKey)), BooleanClause.Occur.FILTER)
                    .add(new TermQuery(new Term(KIND, PAGE_KIND)), BooleanClause.Occur.FILTER).build();
            Map<String, ContentState> states = new java.util.HashMap<>();
            StoredFields fields = searcher.storedFields();
            ScoreDoc after = null;
            while (true) {
                TopDocs batch = searcher.searchAfter(after, query, 1024);
                for (ScoreDoc hit : batch.scoreDocs) {
                    Document doc = fields.document(hit.doc, Set.of(PAGE_ID, INPUT_SHA256, HAS_TEXT));
                    String hasText = doc.get(HAS_TEXT);
                    // Do not infer content from an older entry that may contain only embedded file names.
                    ContentState state = new ContentState(doc.get(INPUT_SHA256),
                            Boolean.parseBoolean(hasText), hasText != null);
                    states.merge(doc.get(PAGE_ID), state, (a, b) -> {
                        if (!a.inputSha256().equals(b.inputSha256())) {
                            return new ContentState("", false, false);
                        }
                        return new ContentState(a.inputSha256(), a.hasText() || b.hasText(),
                                a.verified() && b.verified());
                    });
                }
                if (batch.scoreDocs.length < 1024) {
                    return Map.copyOf(states);
                }
                after = batch.scoreDocs[batch.scoreDocs.length - 1];
            }
        });
    }

    @Override
    public boolean contentStateKnown(PageKey key) {
        return stored(id(key), HAS_TEXT).isPresent();
    }

    @Override
    public Optional<String> inputSha256(PageKey key) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        return stored(id(key), INPUT_SHA256);
    }

    @Override
    public Optional<String> mediaType(PageKey key) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        return stored(id(key), MEDIA_TYPE);
    }

    @Override
    public Optional<String> text(PageKey key) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        return stored(id(key), TEXT);
    }

    /** Ein gespeichertes Feld des Dokuments mit dieser ID. */
    private Optional<String> stored(String id, String field) {
        return stored(ID, id, field);
    }

    private Optional<String> stored(String keyField, String key, String field) {
        return read(searcher -> {
            TopDocs top = searcher.search(new TermQuery(new Term(keyField, key)), 1);
            return top.scoreDocs.length == 0 ? Optional.<String>empty()
                    : Optional.ofNullable(searcher.storedFields().document(top.scoreDocs[0].doc, Set.of(field))
                            .get(field));
        });
    }

    @Override
    public synchronized void remove(PageKey key) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        try {
            Optional<String> space = stored(id(key), SPACE_KEY);
            // Die Seite und ihre Anhänge; Einträge von vor v2 hat das Öffnen schon verworfen.
            writer.deleteDocuments(new Term(PAGE, id(key)));
            changed();
            space.ifPresent(value -> holdings.pagesChanged(key.sourceId(), value, List.of(key.pageId())));
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public synchronized void removeSource(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        requireOpen();
        try {
            writer.deleteDocuments(new Term(SOURCE_ID, sourceId));
            writer.commit();
            uncommitted = 0;
            searchers.maybeRefreshBlocking();
            holdings.sourceChanged(sourceId);
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public List<Match> search(String text, Set<String> sourceIds, SearchSpaces spaces, int limit) {
        return search(text, sourceIds, spaces, null, limit);
    }

    @Override
    public List<Match> search(String text, Set<String> sourceIds, SearchSpaces spaces, PageSet within, int limit) {
        return search(text, sourceIds, spaces, within, Set.of(), limit);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Die Art entscheidet sich je Dokument aus den gespeicherten Feldern (Name, Medientyp), auch für Einträge, die
     * vor dem Medientyp abgelegt wurden: dann nach dem Namen allein. Gefiltert wird beim Lesen der Rangfolge, die
     * Schleife liest so lange weiter, bis genug Seiten passender Art beisammen sind.
     */
    @Override
    public List<Match> search(String text, Set<String> sourceIds, SearchSpaces spaces, PageSet within,
                              Set<DocumentKind> kinds, int limit) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sourceIds, "sourceIds");
        Objects.requireNonNull(spaces, "spaces");
        Objects.requireNonNull(kinds, "kinds");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        requireOpen();
        List<String> terms = terms(text.length() > MAX_QUERY_LENGTH ? text.substring(0, MAX_QUERY_LENGTH) : text);
        if (terms.isEmpty() || sourceIds.isEmpty() || within != null && within.isEmpty()) {
            return List.of();
        }
        BooleanQuery.Builder query = new BooleanQuery.Builder()
                .add(new TermInSetQuery(SOURCE_ID, sourceIds.stream().map(BytesRef::new).toList()),
                        BooleanClause.Occur.FILTER);
        if (within != null) {
            // Seite samt Unterseiten (ADR 0029): vor der Rangfolge, Anhänge tragen das Seitenfeld ihrer Seite.
            List<BytesRef> ids = new ArrayList<>(within.size());
            within.asMap().forEach((sourceId, pageIds) -> pageIds.forEach(pageId ->
                    ids.add(new BytesRef(id(new PageKey(sourceId, pageId))))));
            query.add(new TermInSetQuery(PAGE, ids), BooleanClause.Occur.FILTER);
        }
        if (!spaces.only().isEmpty()) {
            query.add(new TermInSetQuery(SPACE_KEY, spaces.only().stream().map(BytesRef::new).toList()),
                    BooleanClause.Occur.FILTER);
        }
        for (String excluded : spaces.excluded()) {
            query.add(new TermQuery(new Term(SPACE_KEY, excluded)), BooleanClause.Occur.MUST_NOT);
        }
        for (String term : terms) {
            query.add(new TermQuery(new Term(TEXT, term)), BooleanClause.Occur.SHOULD);
            query.add(new BoostQuery(new TermQuery(new Term(TITLE, term)), 2f), BooleanClause.Occur.SHOULD);
            query.add(new BoostQuery(new TermQuery(new Term(PART_TITLE, term)), 2f), BooleanClause.Occur.SHOULD);
        }
        query.setMinimumNumberShouldMatch(1);
        Set<String> wanted = Set.copyOf(terms);
        BooleanQuery built = query.build();
        return read(searcher -> {
            // Je Seite nur ihr bestes Dokument: so viele Dokumente lesen, bis genug Seiten beisammen sind.
            int documents = Math.min(limit * 2, MAX_DOCUMENTS);
            while (true) {
                TopDocs top = searcher.search(built, documents);
                StoredFields stored = searcher.storedFields();
                Map<String, Match> pages = new java.util.LinkedHashMap<>();
                for (ScoreDoc hit : top.scoreDocs) {
                    if (pages.size() == limit) {
                        break;
                    }
                    Document doc = stored.document(hit.doc);
                    String page = doc.get(SOURCE_ID) + SEPARATOR + doc.get(PAGE_ID);
                    if (pages.containsKey(page)) {
                        continue;
                    }
                    if (!kinds.isEmpty() && !kinds.contains(kind(doc))) {
                        continue;
                    }
                    String snippet = window(doc.get(TEXT), wanted);
                    // Nur für ausgelieferte Treffer: je Wort und Feld ein Blick in die Postings dieses Dokuments.
                    Words words = words(searcher, hit.doc, terms);
                    pages.put(page, (ATTACHMENT_KIND.equals(doc.get(KIND))
                            ? new Match(doc.get(SOURCE_ID), doc.get(PAGE_ID), doc.get(SPACE_KEY), "", snippet,
                                    doc.get(ATTACHMENT_ID), doc.get(TITLE), null, doc.get(PART_ID),
                                    doc.get(PART_ID) == null ? null : doc.get(PART_TITLE))
                            : new Match(doc.get(SOURCE_ID), doc.get(PAGE_ID), doc.get(SPACE_KEY), doc.get(TITLE),
                                    snippet)).withWords(words));
                }
                if (pages.size() == limit || top.scoreDocs.length < documents || documents >= MAX_DOCUMENTS) {
                    return List.copyOf(pages.values());
                }
                documents = Math.min(documents * 2, MAX_DOCUMENTS);
            }
        });
    }

    /**
     * Welche Wörter der Anfrage in diesem Dokument trafen (R1): dieselben Wortabfragen wie in der Rangfolge, über
     * die Postings des Dokuments, nicht aus dem Auszug erschlossen. Titel heißt Titel der Seite, Dateiname des Anhangs
     * oder Name der enthaltenen Datei.
     */
    private static Words words(IndexSearcher searcher, int doc, List<String> terms) throws IOException {
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        LeafReaderContext leaf = leaves.get(ReaderUtil.subIndex(doc, leaves));
        int local = doc - leaf.docBase;
        List<String> inTitle = new ArrayList<>();
        List<String> inText = new ArrayList<>();
        for (String term : terms) {
            if (contains(leaf, TITLE, term, local) || contains(leaf, PART_TITLE, term, local)) {
                inTitle.add(term);
            }
            if (contains(leaf, TEXT, term, local)) {
                inText.add(term);
            }
        }
        return new Words(inTitle, inText);
    }

    private static boolean contains(LeafReaderContext leaf, String field, String term, int doc) throws IOException {
        PostingsEnum postings = leaf.reader().postings(new Term(field, term), PostingsEnum.NONE);
        return postings != null && postings.advance(doc) == doc;
    }

    /** Die Art des Dokuments: enthaltene Datei nach Anzeigename, sonst Name und gemeldeter Medientyp. */
    private static DocumentKind kind(Document doc) {
        if (doc.get(PART_ID) != null) {
            return DocumentKind.of(doc.get(PART_TITLE), null);
        }
        return DocumentKind.of(doc.get(TITLE), doc.get(MEDIA_TYPE));
    }

    @Override
    public synchronized void flush() {
        requireOpen();
        try {
            writer.commit();
            uncommitted = 0;
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            if (uncommitted > 0) {
                TragicWriters.commitOnClose(writer, "Volltextindex");
            }
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht geschrieben werden", e);
        } finally {
            closed = true;
            try {
                searchers.close();
            } catch (IOException | org.apache.lucene.store.AlreadyClosedException ignored) {
                // Der Writer wird trotzdem geschlossen.
            }
            closeQuietly(writer, directory);
            analyzer.close();
        }
    }

    /** Die Wörter des Suchtexts, wie der Index sie zerlegt, ohne Doppelte. */
    private List<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream tokens = analyzer.tokenStream(TEXT, text)) {
            CharTermAttribute term = tokens.addAttribute(CharTermAttribute.class);
            tokens.reset();
            while (tokens.incrementToken() && terms.size() < MAX_TERMS) {
                terms.add(term.toString());
            }
            tokens.end();
        } catch (IOException e) {
            throw new PageCacheException("Suchtext nicht zerlegbar", e);
        }
        return List.copyOf(terms);
    }

    /**
     * Ein Textfenster um die erste Fundstelle, an Wortgrenzen, Leerraum zusammengezogen (wie RawTextWindows). Die
     * Fundstelle ist das erste Wort, das der Analyzer genauso zerlegt wie ein Suchwort, nicht ein beliebiger Teiltext.
     */
    String window(String text, Set<String> terms) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int at = firstToken(text, terms);
        if (at < 0) {
            return "";
        }
        int start = Math.max(0, at - WINDOW / 3);
        int end = Math.min(text.length(), start + WINDOW);
        while (start > 0 && start > at - WINDOW / 2 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && end < start + WINDOW + WINDOW / 4 && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        String window = SPACES.matcher(text.substring(start, end)).replaceAll(" ").strip();
        return (start > 0 ? "… " : "") + window + (end < text.length() ? " …" : "");
    }

    /** Zeichenposition des ersten Worts im Text, das eines der Suchwörter ist, sonst -1. */
    private int firstToken(String text, Set<String> terms) {
        try (TokenStream tokens = analyzer.tokenStream(TEXT, text)) {
            CharTermAttribute term = tokens.addAttribute(CharTermAttribute.class);
            OffsetAttribute offset = tokens.addAttribute(OffsetAttribute.class);
            tokens.reset();
            int found = -1;
            while (tokens.incrementToken()) {
                if (terms.contains(term.toString())) {
                    found = offset.startOffset();
                    break;
                }
            }
            tokens.end();
            return found;
        } catch (IOException e) {
            return -1;
        }
    }

    private static String id(PageKey key) {
        return key.sourceId() + SEPARATOR + key.pageId();
    }

    private static String attachmentId(PageKey page, String attachmentId) {
        return id(page) + SEPARATOR + attachmentId;
    }

    private static String documentKey(PageKey page, String attachmentId, String partId) {
        return attachmentId(page, attachmentId) + SEPARATOR + partId;
    }

    private static String entryId(AttachmentEntry entry) {
        return documentKey(entry.page(), entry.attachmentId(), entry.partId()) + SEPARATOR + entry.section();
    }

    private void changed() throws IOException {
        if (++uncommitted >= COMMIT_EVERY) {
            writer.commit();
            uncommitted = 0;
        }
        searchers.maybeRefreshBlocking();
    }

    private <T> T read(Reader<T> reader) {
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                return reader.read(searcher);
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht gelesen werden", e);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Volltextindex ist geschlossen");
        }
    }

    private static void closeQuietly(IndexWriter writer, Directory directory) {
        try {
            if (writer != null) {
                writer.rollback();
            }
        } catch (IOException | RuntimeException ignored) {
            // Beim Aufräumen zählt der ursprüngliche Fehler; der Index ist neu aufbaubar.
        }
        try {
            if (directory != null) {
                directory.close();
            }
        } catch (IOException | RuntimeException ignored) {
            // wie oben
        }
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(IndexSearcher searcher) throws IOException;
    }
}
