package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
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
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
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
 * ein Dokument je Seite mit Titel und Lesetext, BM25 mit {@link StandardAnalyzer}, Titel doppelt gewichtet. Er ist
 * abgeleitet: ein Index in anderem oder fehlendem Schema wird beim Öffnen leer neu angelegt und füllt sich beim
 * nächsten Abruf oder Abgleich. Seiten-Index und Payloads bleiben davon unberührt.
 *
 * <p>Wie die Anreicherungsablage macht er nicht jede Änderung einzeln dauerhaft, sondern spätestens alle
 * {@value #COMMIT_EVERY} Änderungen, bei {@link #flush()} und beim Schließen; Lesen sieht jede Änderung sofort.
 */
public final class LucenePageTextIndex implements PageTextIndex, AutoCloseable {

    /** Schemaversion in den Commit-Daten. */
    public static final String VERSION = "page-text-index/v1";
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
    private static final String INPUT_SHA256 = "inputSha256";
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
        String text = entry.text().length() > MAX_TEXT_CHARS ? entry.text().substring(0, MAX_TEXT_CHARS)
                : entry.text();
        Document doc = new Document();
        doc.add(new StringField(ID, id(entry.key()), Field.Store.NO));
        doc.add(new StringField(SOURCE_ID, entry.key().sourceId(), Field.Store.YES));
        doc.add(new StoredField(PAGE_ID, entry.key().pageId()));
        doc.add(new StringField(SPACE_KEY, entry.spaceKey(), Field.Store.YES));
        doc.add(new TextField(TITLE, entry.title(), Field.Store.YES));
        doc.add(new TextField(TEXT, text, Field.Store.YES));
        doc.add(new StoredField(INPUT_SHA256, entry.inputSha256()));
        try {
            writer.updateDocument(new Term(ID, id(entry.key())), doc);
            changed();
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public Optional<String> inputSha256(PageKey key) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        return read(searcher -> {
            TopDocs top = searcher.search(new TermQuery(new Term(ID, id(key))), 1);
            return top.scoreDocs.length == 0 ? Optional.<String>empty()
                    : Optional.ofNullable(searcher.storedFields().document(top.scoreDocs[0].doc, Set.of(INPUT_SHA256))
                            .get(INPUT_SHA256));
        });
    }

    @Override
    public Optional<String> text(PageKey key) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        return read(searcher -> {
            TopDocs top = searcher.search(new TermQuery(new Term(ID, id(key))), 1);
            return top.scoreDocs.length == 0 ? Optional.<String>empty()
                    : Optional.ofNullable(searcher.storedFields().document(top.scoreDocs[0].doc, Set.of(TEXT))
                            .get(TEXT));
        });
    }

    @Override
    public synchronized void remove(PageKey key) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        try {
            writer.deleteDocuments(new Term(ID, id(key)));
            changed();
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
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public List<Match> search(String text, Set<String> sourceIds, SearchSpaces spaces, int limit) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sourceIds, "sourceIds");
        Objects.requireNonNull(spaces, "spaces");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        requireOpen();
        List<String> terms = terms(text.length() > MAX_QUERY_LENGTH ? text.substring(0, MAX_QUERY_LENGTH) : text);
        if (terms.isEmpty() || sourceIds.isEmpty()) {
            return List.of();
        }
        BooleanQuery.Builder query = new BooleanQuery.Builder()
                .add(new TermInSetQuery(SOURCE_ID, sourceIds.stream().map(BytesRef::new).toList()),
                        BooleanClause.Occur.FILTER);
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
        }
        query.setMinimumNumberShouldMatch(1);
        Set<String> wanted = Set.copyOf(terms);
        return read(searcher -> {
            TopDocs top = searcher.search(query.build(), limit);
            StoredFields stored = searcher.storedFields();
            List<Match> matches = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                Document doc = stored.document(hit.doc);
                matches.add(new Match(doc.get(SOURCE_ID), doc.get(PAGE_ID), doc.get(SPACE_KEY), doc.get(TITLE),
                        window(doc.get(TEXT), wanted)));
            }
            return matches;
        });
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
                writer.commit();
            }
        } catch (IOException e) {
            throw new PageCacheException("Volltextindex " + path + " kann nicht geschrieben werden", e);
        } finally {
            closed = true;
            try {
                searchers.close();
            } catch (IOException ignored) {
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
