package com.aresstack.research4j;

import com.aresstack.knowledge4j.access.ContentFetchPort;
import com.aresstack.knowledge4j.access.FetchedContent;
import com.aresstack.knowledge4j.access.SourceAccessException;
import com.aresstack.knowledge4j.access.SourceHit;
import com.aresstack.knowledge4j.access.SourceQuery;
import com.aresstack.knowledge4j.access.SourceSearchPort;
import com.aresstack.knowledge4j.run.CancellationSignal;
import com.aresstack.knowledge4j.source.Classification;
import com.aresstack.knowledge4j.source.Provenance;
import com.aresstack.knowledge4j.source.SourceKind;
import com.aresstack.knowledge4j.source.SourceRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An invented internal knowledge base (made-up subject: rainwater in an allotment garden) behind the neutral search
 * and read ports, in memory. Every text carries a marker the scripted judge reads: [INNEN], [GEMISCHT], [AUSSEN],
 * [UNKLAR]. Document "ueberlauf" is a child page that no query finds; it is reached only by following the link.
 */
public final class InventedStock implements SourceSearchPort, ContentFetchPort {

    public record Doc(String id, String title, String text, List<String> children) {
    }

    private final Map<String, Doc> docs = new LinkedHashMap<>();
    public final AtomicInteger searches = new AtomicInteger();
    public final AtomicInteger reads = new AtomicInteger();

    public InventedStock() {
        add("regentonne", "Regentonne anschliessen", "[INNEN] Eine Regentonne am Fallrohr des Kleingartens sammelt "
                + "Regenwasser fuer das Giessen.", "ueberlauf");
        add("zisterne", "Zisterne planen", "[INNEN] Eine Zisterne speichert Regenwasser im Kleingarten unterirdisch.");
        add("grillfest", "Grillfest im Vereinsheim", "[AUSSEN] Das Grillfest der Kleingarten-Kolonie findet im "
                + "Vereinsheim statt.");
        add("wasser", "Wasser im Garten", "[GEMISCHT] Regenwasser sammeln und eine Brunnenbohrung im Kleingarten.");
        add("notiz", "Notiz Regenwasser", "[UNKLAR] Regenwasser siehe Protokoll.");
        add("leer", "Regenwasser Formular", "");
        add("ueberlauf", "Ueberlaufschutz", "[INNEN] Der Ueberlauf fuehrt ueberschuessiges Wasser in die Sickergrube.");
    }

    private void add(String id, String title, String text, String... children) {
        docs.put(id, new Doc(id, title, text, List.of(children)));
    }

    public static SourceRef ref(String id) {
        return SourceRef.of(SourceKind.INTERNAL_DOCUMENT, id);
    }

    public Doc doc(String id) {
        return docs.get(id);
    }

    @Override
    public List<SourceHit> search(SourceQuery query, CancellationSignal cancel) {
        searches.incrementAndGet();
        List<String> words = new ArrayList<>();
        for (String word : query.text().toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (word.length() >= 6) {
                words.add(word);
            }
        }
        record Scored(Doc doc, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Doc doc : docs.values()) {
            if (doc.id().equals("ueberlauf")) {
                continue;
            }
            String hay = (doc.title() + " " + doc.text()).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String word : words) {
                if (hay.contains(word)) {
                    score++;
                }
            }
            if (score > 0) {
                scored.add(new Scored(doc, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        List<SourceHit> hits = new ArrayList<>();
        for (Scored s : scored.subList(0, Math.min(query.maxHits(), scored.size()))) {
            hits.add(new SourceHit(ref(s.doc().id()).withTitle(s.doc().title()), s.doc().title(), "",
                    hits.size() + 1, Provenance.of(Classification.INTERNAL, ref(s.doc().id()))));
        }
        return hits;
    }

    @Override
    public boolean supports(SourceRef ref) {
        return ref.kind().equals(SourceKind.INTERNAL_DOCUMENT);
    }

    @Override
    public FetchedContent fetch(SourceRef ref, CancellationSignal cancel) throws SourceAccessException {
        reads.incrementAndGet();
        Doc doc = docs.get(ref.id());
        if (doc == null) {
            throw new SourceAccessException(SourceAccessException.Reason.NOT_FOUND, "no such document");
        }
        List<SourceRef> links = new ArrayList<>();
        for (String child : doc.children()) {
            links.add(ref(child).withTitle(docs.get(child).title()));
        }
        return new FetchedContent(ref(doc.id()).withTitle(doc.title()), doc.text(), Map.of("space", "Garten"), links,
                Provenance.of(Classification.INTERNAL, ref(doc.id())));
    }
}
