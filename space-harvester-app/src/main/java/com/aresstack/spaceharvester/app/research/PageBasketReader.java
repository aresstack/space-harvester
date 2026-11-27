package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.basket.BasketReader;
import com.aresstack.knowledge4j.passage.PassageView;
import com.aresstack.knowledge4j.source.Classification;
import com.aresstack.knowledge4j.source.Provenance;
import com.aresstack.knowledge4j.source.SourceRef;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.TextSegmentation;
import com.aresstack.spaceharvester.application.passages.OpenNlpSentenceSegmenter;
import com.aresstack.spaceharvester.application.passages.PassageSegmentation;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Liest die Seiten des Korbs für Writing4j als Passagen: die Seite wie das Chat-Werkzeug {@code seite_lesen}
 * ({@link PageStock#page}), zerlegt mit derselben Passagen-Zerlegung wie die Anreicherung
 * ({@link PassageSegmentation}, Rückfallebene nach Struktur ohne Embedding-Modell), die Überschriftenkette jeder Passage
 * aus den Abschnitten der Seite. Jede gelesene Seite kommt auf die Liste freigegebener Seiten, gebündelt vor dem
 * nächsten Modellaufruf ({@link PendingReleases}); eine Seite ohne Space lässt sich nicht freigeben, ihr Text bleibt
 * draußen. Eine Seite, die sich nicht lesen lässt, liefert keine Passagen; das Buch entsteht aus den übrigen.
 */
public final class PageBasketReader implements BasketReader {

    private static final System.Logger LOG = System.getLogger(PageBasketReader.class.getName());

    private final PageStock stock;
    private final PendingReleases releases;
    private final PassageSegmentation segmentation;
    private int unreadable;
    /** Die gelesenen Seiten in der Reihenfolge des ersten Lesens (Stand der Quellen einer Auflage). */
    private final java.util.Set<PageKey> read = new java.util.LinkedHashSet<>();

    PageBasketReader(PageStock stock, PendingReleases releases, PassageSegmentation segmentation) {
        this.stock = Objects.requireNonNull(stock, "stock");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.segmentation = Objects.requireNonNull(segmentation, "segmentation");
    }

    /** Die Zerlegung wie in der Anreicherung (Satzmodell Deutsch, sonst Regel-Splitter). */
    static PassageSegmentation defaultSegmentation() {
        TextSegmentation rules = KnowledgeTextSegmentation.defaults();
        return new PassageSegmentation(OpenNlpSentenceSegmenter.german(SentenceSegmenter.splitter(rules)), rules);
    }

    /** Seiten, die sich nicht lesen oder nicht freigeben ließen. */
    synchronized int unreadable() {
        return unreadable;
    }

    /** Der Stand der gelesenen Seiten: je Seite Kennung und Version laut Ablage. */
    List<BookEditions.Source> sources() {
        List<PageKey> keys;
        synchronized (this) {
            keys = List.copyOf(read);
        }
        return keys.stream().map(key -> new BookEditions.Source(key.sourceId(), key.pageId(), stock.version(key)))
                .toList();
    }

    @Override
    public List<PassageView> passages(SourceRef source) {
        PageKey key = PageRefs.key(source);
        if (key == null) {
            return List.of();
        }
        PageStock.Page page;
        try {
            page = stock.page(key).orElse(null);
        } catch (RuntimeException failed) {
            LOG.log(System.Logger.Level.INFO, "Buch: Seite nicht lesbar ({0})", failed.getClass().getSimpleName());
            page = null;
        }
        if (page == null || page.spaceKey() == null || page.spaceKey().isBlank()) {
            synchronized (this) {
                unreadable++;
            }
            return List.of();
        }
        releases.add(page.spaceKey(), key);
        synchronized (this) {
            read.add(key);
        }
        SourceRef ref = PageRefs.of(key, page.title());
        Provenance provenance = Provenance.of(Classification.INTERNAL, ref);
        PageText text = page.reading() != null ? page.reading()
                : new PageText("book-page-text/v1", page.text() == null ? "" : page.text(), List.of());
        List<PassageView> out = new ArrayList<>();
        int number = 0;
        for (PassageSegmentation.Passage passage : segmentation.segment(text, null).passages()) {
            String body = text.text().substring(passage.start(), passage.end()).strip();
            if (!body.isEmpty()) {
                out.add(new PassageView(ref.id() + "#" + number++, ref, body,
                        passage.section() == null ? "" : passage.section(), "de", provenance));
            }
        }
        return out;
    }
}
