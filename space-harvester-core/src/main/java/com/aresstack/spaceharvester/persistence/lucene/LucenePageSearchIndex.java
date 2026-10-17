package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.PageCacheSchema.HEADER_FIELDS;

import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheGenerationChangedException;
import com.aresstack.spaceharvester.application.query.CacheUnavailableException;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageSearchIndex;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongField;
import org.apache.lucene.index.QueryTimeoutImpl;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.SortedSetSortField;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;

/**
 * Titel- und Metadatensuche (#28) über die aktuellen Kopfdaten im {@link LucenePageCache}.
 *
 * <p><b>Generation.</b> Je Quelle zählt der Cache eine Suchgeneration ({@code s<n>}), die jeder Commit erhöht, der
 * Seiten oder bestätigte Generationen dieser Quelle ändert. Über mehrere Quellen (#114) ist die Generation
 * {@code g<hex>}, ein Hash der sortierten Paare aus Quelle und Suchgeneration aller indexierten Quellen der Menge: Sie
 * ändert sich, sobald sich eine davon ändert oder eine Quelle der Menge in den Index kommt, und nur dann. Quellen
 * außerhalb der Menge (z. B. ohne Freigabe) gehen nicht ein und sind an ihr nicht zu erkennen. Eine Suche liest genau
 * einen Commit und prüft die Generation an ihm; passt die Generation des Aufrufers nicht mehr zu ihm, gilt der Cursor
 * nicht ({@link CacheGenerationChangedException}). Ältere Commits werden nicht aufbewahrt.
 *
 * <p><b>Semantik</b> wie {@link PageSearchQuery#matches} und {@link SearchSort#order()}, ganz im Index: die Wörter
 * des Titels stehen exakt nach {@link PageSearchQuery#words}, die Änderungszeit in Nanosekunden, und die
 * Sortierschlüssel vergleichen wie {@link String#compareTo}.
 *
 * <p><b>Global</b> (#114): eine Abfrage über den gemeinsamen Index mit {@code sourceId} aus der Menge, sortiert und
 * begrenzt über alle Quellen; letzter Sortierschlüssel ist {@code sourceId} (bytewise UTF-8, für die ASCII-IDs der
 * Konfiguration gleich {@link String#compareTo}), danach ist jeder Treffer eindeutig.
 *
 * <p><b>Position</b> eines Treffers: Sortierung und Sortierwerte genau dieses Treffers (Base64url), nichts sonst.
 * Positionen von vor #114 ohne {@code sourceId} gelten für Anfragen mit {@code sourceId} weiter.
 *
 * <p><b>Zeitgrenze:</b> Eine Suche, die länger als {@code timeout} läuft, wird abgebrochen und als
 * {@link CacheUnavailableException} gemeldet, nie mit einem stillschweigend gekürzten Ergebnis.
 */
public final class LucenePageSearchIndex implements PageSearchIndex {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final LucenePageCache cache;
    private final Duration timeout;

    public LucenePageSearchIndex(LucenePageCache cache, Duration timeout) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout muss positiv sein");
        }
    }

    @Override
    public Optional<SearchIndexState> state(Set<String> sourceIds) {
        Set<String> sources = sources(sourceIds);
        return guarded(() -> cache.read(searcher -> state(searcher, sources)));
    }

    @Override
    public List<SearchHit> search(PageSearchQuery query, Set<String> sourceIds, String generation,
                                  String afterPosition, int limit) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(generation, "generation");
        Set<String> sources = sources(sourceIds);
        if (query.sourceId() != null && !sources.contains(query.sourceId())) {
            throw new IllegalArgumentException("Die Quelle der Anfrage liegt nicht im Suchumfang");
        }
        if (limit < 1 || limit > LucenePageCache.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit muss zwischen 1 und " + LucenePageCache.MAX_PAGE_SIZE
                    + " liegen");
        }
        Sort sort = sort(query.sort());
        Object[] after = afterPosition == null ? null : decode(afterPosition, query.sort(), query.sourceId());
        long deadline = System.nanoTime() + timeout.toNanos();
        return guarded(() -> cache.read(committed -> {
            String current = state(committed, sources).map(SearchIndexState::generation).orElse(null);
            if (!generation.equals(current)) {
                throw new CacheGenerationChangedException("Suchgeneration " + generation + " von "
                        + String.join(",", sources) + " ist nicht mehr lesbar");
            }
            // Eigener Searcher je Suche, damit die Zeitgrenze nur diese Suche betrifft.
            IndexSearcher searcher = new IndexSearcher(committed.getIndexReader());
            searcher.setTimeout(new QueryTimeoutImpl(timeout.toMillis()));
            Query filter = filter(query, sources);
            // sourceId + pageId ist eindeutig: gleiche Sortierwerte hat nur der Treffer selbst, und mit der letzten
            // Dokumentnummer liegt er nie dahinter.
            TopDocs top = after == null ? searcher.search(filter, limit, sort)
                    : searcher.searchAfter(new FieldDoc(Math.max(0, searcher.getIndexReader().maxDoc() - 1),
                            Float.NaN, after), filter, limit, sort);
            if (searcher.timedOut()) {
                throw new CacheUnavailableException("Suche überschreitet die Zeitgrenze von " + timeout);
            }
            StoredFields stored = searcher.storedFields();
            Map<String, CacheGeneration> generations = new HashMap<>();
            List<SearchHit> hits = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                Document doc = stored.document(hit.doc, HEADER_FIELDS);
                ListedPage page = new ListedPage(PageCacheSchema.header(doc), doc.get(PageCacheSchema.RAW_SHA256));
                String sourceId = page.header().key().sourceId();
                String spaceKey = page.header().metadata().partitionKey();
                // Schlüssel ohne Trenner-Kollision: Quellen-IDs enthalten keinen Zeilenumbruch.
                CacheGeneration space = generations.computeIfAbsent(sourceId + "\n" + spaceKey,
                        key -> spaceGeneration(committed, sourceId, spaceKey));
                hits.add(new SearchHit(page, space, encode(query.sort(), (FieldDoc) hit)));
            }
            // Die Zeitgrenze gilt für die ganze Suche, auch für das Laden der Treffer.
            if (System.nanoTime() - deadline > 0) {
                throw new CacheUnavailableException("Suche überschreitet die Zeitgrenze von " + timeout);
            }
            return hits;
        }));
    }

    private static Set<String> sources(Set<String> sourceIds) {
        Objects.requireNonNull(sourceIds, "sourceIds");
        if (sourceIds.isEmpty()) {
            throw new IllegalArgumentException("sourceIds ist leer");
        }
        return new TreeSet<>(sourceIds);
    }

    /** Generation der Menge aus genau diesem Commit; für eine Quelle deren {@code s<n>}. */
    private static Optional<SearchIndexState> state(IndexSearcher searcher, Set<String> sources) throws IOException {
        if (sources.size() == 1) {
            return source(searcher, sources.iterator().next());
        }
        StringBuilder plain = new StringBuilder();
        Instant committedAt = null;
        for (String sourceId : sources) {
            Optional<SearchIndexState> state = source(searcher, sourceId);
            if (state.isPresent()) {
                plain.append(sourceId).append('=').append(state.get().generation()).append('\n');
                Instant at = state.get().committedAt();
                committedAt = committedAt == null || at.isAfter(committedAt) ? at : committedAt;
            }
        }
        if (committedAt == null) {
            return Optional.empty();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(plain.toString()
                    .getBytes(StandardCharsets.UTF_8));
            return Optional.of(new SearchIndexState("g" + HexFormat.of().formatHex(digest, 0, 16), committedAt));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    private static Optional<SearchIndexState> source(IndexSearcher searcher, String sourceId) throws IOException {
        TopDocs top = searcher.search(new TermQuery(PageCacheSchema.sourceTerm(sourceId)), 1);
        if (top.scoreDocs.length == 0) {
            return Optional.empty();
        }
        Document doc = searcher.storedFields().document(top.scoreDocs[0].doc);
        return Optional.of(new SearchIndexState("s" + PageCacheSchema.sourceGeneration(doc),
                PageCacheSchema.sourceCommittedAt(doc)));
    }

    /** Die Cache-Generation des Space aus demselben Commit wie die Treffer; ein Treffer belegt, dass er Seiten hat. */
    private static CacheGeneration spaceGeneration(IndexSearcher searcher, String sourceId, String spaceKey) {
        try {
            TopDocs top = searcher.search(new TermQuery(PageCacheSchema.spaceTerm(sourceId, spaceKey)), 1);
            if (top.scoreDocs.length == 0) {
                return LucenePageCacheReader.initial();
            }
            return LucenePageCacheReader.of(PageCacheSchema.confirmedGeneration(
                    searcher.storedFields().document(top.scoreDocs[0].doc)));
        } catch (IOException e) {
            throw new PageCacheException("Generation von " + sourceId + "/" + spaceKey + " nicht lesbar", e);
        }
    }

    private static Query filter(PageSearchQuery query, Set<String> sources) {
        BooleanQuery.Builder filter = new BooleanQuery.Builder()
                .add(term(PageCacheSchema.TYPE, PageCacheSchema.PAGE), BooleanClause.Occur.FILTER);
        if (query.sourceId() != null) {
            filter.add(term(PageCacheSchema.SOURCE_ID, query.sourceId()), BooleanClause.Occur.FILTER);
        } else {
            filter.add(new TermInSetQuery(PageCacheSchema.SOURCE_ID,
                    sources.stream().map(BytesRef::new).toList()), BooleanClause.Occur.FILTER);
        }
        if (!query.spaceKeys().isEmpty()) {
            BooleanQuery.Builder spaces = new BooleanQuery.Builder();
            for (String spaceKey : query.spaceKeys()) {
                spaces.add(term(PageCacheSchema.SPACE_KEY, spaceKey), BooleanClause.Occur.SHOULD);
            }
            filter.add(spaces.build(), BooleanClause.Occur.FILTER);
        }
        for (String word : query.titleTerms()) {
            filter.add(term(PageCacheSchema.TITLE_WORD, word), BooleanClause.Occur.FILTER);
        }
        if (query.pageId() != null) {
            filter.add(term(PageCacheSchema.PAGE_ID, query.pageId()), BooleanClause.Occur.FILTER);
        }
        if (query.parentPageId() != null) {
            filter.add(term(PageCacheSchema.PARENT_ID, query.parentPageId()), BooleanClause.Occur.FILTER);
        }
        if (query.version() != null) {
            filter.add(term(PageCacheSchema.VERSION_FIELD, query.version()), BooleanClause.Occur.FILTER);
        }
        if (query.modifiedFrom() != null || query.modifiedBefore() != null) {
            long from = query.modifiedFrom() == null ? Long.MIN_VALUE : PageCacheSchema.nanos(query.modifiedFrom());
            long before = query.modifiedBefore() == null ? Long.MAX_VALUE
                    : PageCacheSchema.nanos(query.modifiedBefore());
            if (before == Long.MIN_VALUE) {
                // Nichts liegt vor dem kleinsten Wert; ohne diese Prüfung liefe before - 1 über.
                return new MatchNoDocsQuery();
            }
            long to = query.modifiedBefore() == null ? Long.MAX_VALUE : before - 1;
            filter.add(LongField.newRangeQuery(PageCacheSchema.MODIFIED_AT_NANOS, from, to),
                    BooleanClause.Occur.FILTER);
        }
        return filter.build();
    }

    private static Query term(String field, String value) {
        return new TermQuery(new Term(field, value));
    }

    private static Sort sort(SearchSort sort) {
        SortField pageId = new SortField(PageCacheSchema.SORT_PAGE_ID, SortField.Type.STRING);
        // Letzter Schlüssel (#114): die Quelle, über die SortedSet-DocValues des KeywordField (UTF-8-Bytes).
        SortField sourceId = new SortedSetSortField(PageCacheSchema.SOURCE_ID, false);
        return switch (sort) {
            case TITLE -> new Sort(new SortField(PageCacheSchema.SORT_TITLE, SortField.Type.STRING), pageId,
                    sourceId);
            case MODIFIED_DESC -> {
                SortField modified = new SortedNumericSortField(PageCacheSchema.MODIFIED_AT_NANOS,
                        SortField.Type.LONG, true);
                // Absteigend sortiert kommt der kleinste Wert zuletzt: Seiten ohne Änderungszeit.
                modified.setMissingValue(Long.MIN_VALUE);
                yield new Sort(modified, pageId, sourceId);
            }
            case PAGE_ID -> new Sort(pageId, sourceId);
        };
    }

    // Position: <Sortierung>.<Wert>.<Wert>, Texte als Base64url ihrer Sortierschlüssel, Zahlen dezimal.

    private static String encode(SearchSort sort, FieldDoc hit) {
        StringBuilder position = new StringBuilder(sort.name());
        for (Object value : hit.fields) {
            position.append('.');
            if (value instanceof BytesRef bytes) {
                position.append(ENCODER.encodeToString(BytesRef.deepCopyOf(bytes).bytes));
            } else {
                position.append(((Number) value).longValue());
            }
        }
        return position.toString();
    }

    /**
     * @param sourceId Quelle der Anfrage oder {@code null}; ergänzt Positionen von vor #114, denen sie fehlt
     */
    private static Object[] decode(String position, SearchSort sort, String sourceId) {
        String[] parts = position.split("\\.", -1);
        int expected = sort == SearchSort.PAGE_ID ? 2 : 3;
        boolean legacy = sourceId != null && parts.length == expected;
        if (parts.length != expected + 1 && !legacy || !parts[0].equals(sort.name())) {
            throw new InvalidQueryException("Position passt nicht zur Sortierung");
        }
        try {
            Object[] fields = new Object[expected];
            for (int i = 0; i < expected; i++) {
                boolean number = sort == SearchSort.MODIFIED_DESC && i == 0;
                if (legacy && i == expected - 1) {
                    fields[i] = new BytesRef(sourceId);
                } else {
                    fields[i] = number ? Long.parseLong(parts[i + 1]) : new BytesRef(DECODER.decode(parts[i + 1]));
                }
            }
            return fields;
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException("Position ist beschädigt");
        }
    }

    private static <T> T guarded(Supplier<T> call) {
        try {
            return call.get();
        } catch (PageCacheException | IllegalStateException e) {
            throw new CacheUnavailableException("Suchindex nicht lesbar", e);
        }
    }
}
