package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.KeywordSearch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Standardadapter des Rechtswissens (ADR 0035, Abschnitt 5): sucht mit der vorhandenen Stichwortsuche
 * ({@link KeywordSearch}) nur in den Wissensquellen, die der Benutzer als Rechtsquellen markiert hat. Es gibt keinen
 * eigenen Index; die Rechte prüft die Suche mit dem Aufrufer.
 *
 * <p>Art und Gültigkeit einer Rechtsquelle liefert die Suche nicht. Sie bleiben {@link Kind#UNKNOWN} und leer, bis
 * die Metadaten der Quellen sie tragen (O3); der Evaluator wertet unbekannte Gültigkeit nie als nachgewiesen. Ein
 * Stichtag ({@link LegalQuery#validOn()}) schränkt darum noch nichts ein.
 */
public final class SearchLegalKnowledge implements LegalKnowledgeRepository {

    /** So oft folgt der Adapter dem Cursor, solange Quellen noch laden. */
    static final int MAX_BATCHES = 3;

    private final KeywordSearch search;
    private final Supplier<AccessContext> caller;
    private final Supplier<Set<String>> legalSources;
    private final Predicate<String> hasContent;

    /**
     * @param search       die Stichwortsuche des Core
     * @param caller       in wessen Namen gesucht wird (Rechte wie bei jeder Suche)
     * @param legalSources Quellen-IDs, die der Benutzer als Rechtsquellen markiert hat
     * @param hasContent   ob eine Quelle schon gelesene Seiten hat
     */
    public SearchLegalKnowledge(KeywordSearch search, Supplier<AccessContext> caller,
                                Supplier<Set<String>> legalSources, Predicate<String> hasContent) {
        this.search = Objects.requireNonNull(search, "search");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.legalSources = Objects.requireNonNull(legalSources, "legalSources");
        this.hasContent = Objects.requireNonNull(hasContent, "hasContent");
    }

    @Override
    public State state() {
        Set<String> sources = sources();
        if (sources.isEmpty()) {
            return State.NOT_CONFIGURED;
        }
        return sources.stream().anyMatch(hasContent) ? State.READY : State.EMPTY;
    }

    @Override
    public List<LegalHit> search(LegalQuery query, int limit) {
        Objects.requireNonNull(query, "query");
        Set<String> sources = sources();
        String text = text(query);
        if (sources.isEmpty() || text.isEmpty() || limit < 1 || state() != State.READY) {
            return List.of();
        }
        int wanted = Math.min(limit, KeywordSearch.MAX_LIMIT);
        KeywordSearch.Query keywords = new KeywordSearch.Query(text, null, sources, Set.of());
        List<KeywordSearch.Hit> hits = new ArrayList<>();
        String cursor = null;
        for (int batch = 0; batch < MAX_BATCHES && hits.size() < wanted; batch++) {
            KeywordSearch.Results results = search.search(caller.get(), keywords, cursor, wanted - hits.size());
            hits.addAll(results.hits());
            cursor = results.nextCursor();
            if (cursor == null || (results.complete() && results.hits().isEmpty())) {
                break;
            }
        }
        List<LegalHit> out = new ArrayList<>();
        for (int i = 0; i < hits.size() && out.size() < wanted; i++) {
            KeywordSearch.Hit hit = hits.get(i);
            if (!sources.contains(hit.sourceId())) {
                continue; // nie ein Treffer außerhalb der Rechtsquellen
            }
            out.add(new LegalHit(hit.sourceId(), hit.pageId(), hit.version(), hit.title(), "", "",
                    hit.snippet(), Kind.UNKNOWN, null, null, 1.0 - (double) i / hits.size(), provenance(hit)));
        }
        return List.copyOf(out);
    }

    private Set<String> sources() {
        Set<String> sources = legalSources.get();
        return sources == null ? Set.of() : Set.copyOf(sources);
    }

    /** Suchtext aus Freitext, Rechtsvorschrift und Thema, gekürzt auf die Länge, die die Suche annimmt. */
    static String text(LegalQuery query) {
        Set<String> parts = new LinkedHashSet<>();
        for (String part : List.of(query.text(), query.legalBasis(), query.topic())) {
            if (!part.isBlank()) {
                parts.add(part.strip());
            }
        }
        String text = String.join(" ", parts);
        if (text.length() > KeywordSearch.MAX_TEXT_LENGTH) {
            int cut = text.lastIndexOf(' ', KeywordSearch.MAX_TEXT_LENGTH);
            text = text.substring(0, cut > 0 ? cut : KeywordSearch.MAX_TEXT_LENGTH);
        }
        return text;
    }

    private static String provenance(KeywordSearch.Hit hit) {
        StringBuilder out = new StringBuilder();
        if (hit.url() != null) {
            out.append(hit.url());
        }
        if (hit.modifiedAt() != null) {
            out.append(out.isEmpty() ? "" : ", ").append("geändert ").append(hit.modifiedAt());
        }
        if (hit.version() != null && !hit.version().isBlank()) {
            out.append(out.isEmpty() ? "" : ", ").append("Version ").append(hit.version());
        }
        return out.toString();
    }
}
