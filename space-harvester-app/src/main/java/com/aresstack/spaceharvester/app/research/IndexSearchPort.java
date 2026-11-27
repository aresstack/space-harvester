package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.access.SourceAccessException;
import com.aresstack.knowledge4j.access.SourceHit;
import com.aresstack.knowledge4j.access.SourceQuery;
import com.aresstack.knowledge4j.access.SourceSearchPort;
import com.aresstack.knowledge4j.run.CancellationSignal;
import com.aresstack.knowledge4j.source.Classification;
import com.aresstack.knowledge4j.source.Provenance;
import com.aresstack.knowledge4j.source.SourceRef;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Die interne Suche für Research4j: dieselbe Schlagwortsuche wie die Suchleiste. Ein Treffer nennt nur Seite und Titel,
 * nie einen Auszug aus dem Text (wie im Chat: „Die Suche zählt dafür ja nicht“); gelesen wird erst über
 * {@link PageFetchPort}. Treffer gelten als intern.
 */
public final class IndexSearchPort implements SourceSearchPort {

    private final PageStock stock;

    public IndexSearchPort(PageStock stock) {
        this.stock = Objects.requireNonNull(stock, "stock");
    }

    @Override
    public List<SourceHit> search(SourceQuery query, CancellationSignal cancel) throws SourceAccessException {
        List<KeywordSearch.Hit> hits;
        try {
            hits = stock.search(query.text(), query.maxHits());
        } catch (RuntimeException failed) {
            throw new SourceAccessException(SourceAccessException.Reason.FAILED,
                    "Suche nicht möglich (" + failed.getClass().getSimpleName() + ")", failed);
        }
        List<SourceHit> out = new ArrayList<>();
        Set<PageKey> seen = new LinkedHashSet<>();
        for (KeywordSearch.Hit hit : hits) {
            PageKey key = new PageKey(hit.sourceId(), hit.pageId());
            if (!seen.add(key)) {
                continue;
            }
            String title = hit.title() == null ? hit.pageId() : hit.title();
            SourceRef ref = PageRefs.of(key, title);
            out.add(new SourceHit(ref, title, "", out.size() + 1, Provenance.of(Classification.INTERNAL, ref)));
            if (out.size() >= query.maxHits()) {
                break;
            }
        }
        return out;
    }
}
