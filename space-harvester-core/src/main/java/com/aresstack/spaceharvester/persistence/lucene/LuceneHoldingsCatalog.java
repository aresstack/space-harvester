package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;

/**
 * Der lesende Port des Bestands (ADR 0027) über dem {@link LucenePageCache}: je Space aus einem einzigen Lesestand die
 * Seiten des Katalogs mit Elternseite, Version der Kopfdaten und des Inhalts, dazu die veralteten und gesperrten Seiten
 * der bestätigten Generation. Ohne Payload; gesperrte Seiten bekommen ihre Elternseite aus dem zurückgehaltenen Stand.
 * Die Generation heißt wie beim Seitenbaum ({@link LucenePageCacheReader}).
 */
public final class LuceneHoldingsCatalog implements HoldingsCatalog {

    private static final Set<String> PAGE_FIELDS = Set.of(PageCacheSchema.PAGE_ID, PageCacheSchema.PARENT_ID,
            PageCacheSchema.RAW_SHA256, PageCacheSchema.VERSION_FIELD,
            PageCacheSchema.SNAPSHOT + PageCacheSchema.VERSION_FIELD);
    private static final String WITHHELD_PARENT = PageCacheSchema.WITHHELD + "." + PageCacheSchema.PARENT_ID;

    private final LucenePageCache cache;

    public LuceneHoldingsCatalog(LucenePageCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public Optional<Stock> stock(String sourceId, String spaceKey) {
        Query pages = spacePages(sourceId, spaceKey);
        return cache.read(searcher -> {
            StoredFields stored = searcher.storedFields();
            TopDocs top = searcher.search(new TermQuery(PageCacheSchema.spaceTerm(sourceId, spaceKey)), 1);
            Document space = top.scoreDocs.length == 0 ? null : stored.document(top.scoreDocs[0].doc);
            int count = searcher.count(pages);
            if (space == null && count == 0) {
                return Optional.<Stock>empty();
            }
            List<CatalogPage> catalog = new ArrayList<>(count);
            if (count > 0) {
                for (ScoreDoc hit : searcher.search(pages, count).scoreDocs) {
                    Document doc = stored.document(hit.doc, PAGE_FIELDS);
                    catalog.add(new CatalogPage(doc.get(PageCacheSchema.PAGE_ID), doc.get(PageCacheSchema.PARENT_ID),
                            doc.get(PageCacheSchema.RAW_SHA256) != null, doc.get(PageCacheSchema.VERSION_FIELD),
                            doc.get(PageCacheSchema.SNAPSHOT + PageCacheSchema.VERSION_FIELD)));
                }
            }
            if (space == null) {
                return Optional.of(new Stock(LucenePageCacheReader.initial(), catalog, Set.of(), List.of()));
            }
            List<LockedPage> locked = new ArrayList<>();
            for (String pageId : PageCacheSchema.withheldPageIds(space).keySet()) {
                TopDocs held = searcher.search(new TermQuery(PageCacheSchema.withheldTerm(
                        new PageKey(sourceId, pageId))), 1);
                String parent = held.scoreDocs.length == 0 ? null
                        : stored.document(held.scoreDocs[0].doc, Set.of(WITHHELD_PARENT)).get(WITHHELD_PARENT);
                locked.add(new LockedPage(pageId, parent));
            }
            Set<String> stale = new HashSet<>(Arrays.asList(space.getValues(PageCacheSchema.STALE_PAGE_ID)));
            return Optional.of(new Stock(LucenePageCacheReader.of(PageCacheSchema.confirmedGeneration(space)),
                    catalog, stale, locked));
        });
    }

    @Override
    public Optional<String> spaceOf(String sourceId, String pageId) {
        return cache.findHeader(new PageKey(sourceId, pageId)).map(header -> header.metadata().partitionKey());
    }

    @Override
    public List<String> spaceKeys(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        TreeSet<String> keys = cache.read(searcher -> {
            TreeSet<String> found = new TreeSet<>();
            // Space-Keys stehen nur in Seitendokumenten als Suchbegriff; je Begriff prüfen, ob er zur Quelle gehört.
            Terms terms = MultiTerms.getTerms(searcher.getIndexReader(), PageCacheSchema.SPACE_KEY);
            if (terms != null) {
                TermsEnum each = terms.iterator();
                for (BytesRef term = each.next(); term != null; term = each.next()) {
                    String spaceKey = term.utf8ToString();
                    if (searcher.count(spacePages(sourceId, spaceKey)) > 0) {
                        found.add(spaceKey);
                    }
                }
            }
            return found;
        });
        for (SyncBaselineStore.SpaceRef space : cache.confirmedSpaces()) {
            if (space.sourceId().equals(sourceId)) {
                keys.add(space.spaceKey());
            }
        }
        return List.copyOf(keys);
    }

    private static Query spacePages(String sourceId, String spaceKey) {
        return new BooleanQuery.Builder()
                .add(new TermQuery(new Term(PageCacheSchema.TYPE, PageCacheSchema.PAGE)), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(PageCacheSchema.SOURCE_ID, Objects.requireNonNull(sourceId, "sourceId"))),
                        BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(PageCacheSchema.SPACE_KEY, Objects.requireNonNull(spaceKey, "spaceKey"))),
                        BooleanClause.Occur.FILTER)
                .build();
    }
}
