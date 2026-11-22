package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.knowledge.TermKnowledge;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.structure.PageRef;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Die Suchmethode „Fachbegriffe“ (Paket W8, nur mit Haken, Standard aus): Nennt die Anfrage einen bestätigten
 * Fachbegriff oder ein bestätigtes Synonym, kommen die Seiten dazu, auf denen der Wissensgraph Aussagen zu diesem
 * Begriff belegt ({@link TermKnowledge}, Paket W6).
 *
 * <p>Ablauf: Wortgruppen der Anfrage (drei, zwei, ein Wort, längste zuerst, ohne Überlappung) gehen als Probe an
 * {@link TermKnowledge#answer}, höchstens {@link #MAX_PROBES} Proben und {@link #MAX_TERMS} gefundene Begriffe.
 * Die Belegseiten eines Begriffs sind nur Kandidaten: Dieselbe {@link KeywordSearch} sucht sie mit dem Begriff als
 * Suchtext, begrenzt auf genau diese Seiten und im Umfang der Anfrage ({@link KeywordSearch.Query#withText},
 * {@link KeywordSearch.Query#within}). Rechte, Quellen, Spaces samt ausgeschlossenen, Dateitypen und Auszüge gelten
 * also wie bei jedem Treffer; was die Suche dort nicht liefert, erscheint nicht. Jeder Treffer trägt zusätzlich die
 * Ursache {@link HitReason.Kind#TERM}.
 *
 * <p>Keine Aussage des Wissensgraphen geht weiter, nur Seiten als Treffer; deshalb meldet die Methode an
 * {@link TermKnowledge.Delivery#NONE}. Wer die Treffer an ein Modell gibt (Chat), prüft deren KI-Freigabe wie bei jedem
 * Treffer. Aufrufer ist immer der suchende Benutzer.
 *
 * <p>Ohne Haken wird die Klasse nicht aufgerufen; die Suche bleibt dann unverändert.
 */
public final class TermSearch {

    /** Höchstens so viele Begriffe je Anfrage. */
    public static final int MAX_TERMS = 3;
    /** Höchstens so viele Proben (Aufrufe von {@link TermKnowledge#answer}) je Anfrage. */
    public static final int MAX_PROBES = 24;
    /** Längste Wortgruppe, die als Begriff geprüft wird. */
    public static final int MAX_WORDS = 3;
    /** Höchstens so viele neue Treffer je Anfrage. */
    public static final int MAX_HITS = 20;

    /**
     * Wörter, mit denen kein Fachbegriff beginnt oder endet; sie sparen Proben bei Fragen in ganzen Sätzen. Ein
     * Fachbegriff wie „Ablauf der Frist“ wird trotzdem als Ganzes geprüft.
     */
    private static final Set<String> STOP_WORDS = Set.of("der", "die", "das", "den", "dem", "des", "ein", "eine",
            "einen", "einem", "einer", "eines", "und", "oder", "aber", "wie", "was", "wer", "wo", "wann", "warum",
            "welche", "welcher", "welches", "ist", "sind", "war", "wird", "werden", "wurde", "kann", "muss", "soll",
            "mit", "von", "vom", "zu", "zum", "zur", "im", "in", "am", "an", "auf", "für", "bei", "aus", "nach",
            "über", "unter", "nicht", "auch", "es", "sich", "ich", "wir", "man", "gibt", "habe", "hat", "haben");

    /** Die Seiten des Bereichs einer Anfrage, mit den Rechten des Aufrufers. */
    @FunctionalInterface
    public interface Scope {

        /** Die Seiten, die die Anfrage durchsucht (Quellen, Spaces, Seitengrenze); Seiten ohne Leserecht fehlen. */
        PageSet of(AccessContext caller, KeywordSearch.Query query);
    }

    /** Begriffswissen; in der Anwendung {@link TermKnowledge#answer} mit {@link TermKnowledge.Delivery#NONE}. */
    @FunctionalInterface
    public interface Knowledge {

        TermKnowledge.Answer answer(AccessContext caller, String term, PageSet scope, BooleanSupplier cancelled);

        /** Über {@link TermKnowledge}; die Suche gibt keine Aussage weiter, also {@link TermKnowledge.Delivery#NONE}. */
        static Knowledge of(java.util.function.Supplier<TermKnowledge> knowledge) {
            Objects.requireNonNull(knowledge, "knowledge");
            return (caller, term, scope, cancelled) -> knowledge.get().answer(caller, term, scope,
                    TermKnowledge.Delivery.NONE, cancelled);
        }
    }

    /**
     * Ergebnis.
     *
     * @param hits     neue Treffer, höchstens {@link #MAX_HITS}, mit Ursache {@link HitReason.Kind#TERM}
     * @param terms    die gefundenen Begriffe in der Reihenfolge der Anfrage
     * @param capped   ob der Bereich größer war als {@link TermKnowledge#MAX_PAGES} und nur ein Teil gelesen wurde
     * @param complete ob alle Proben und Suchen gelaufen sind; {@code false} nach Frist, Abbruch, Grenze der Proben
     *                 oder Fehler einer Suche. Gefundene Treffer bleiben trotzdem stehen.
     * @param searches gestellte Suchanfragen an die {@link KeywordSearch}; sie zählen gegen das Budget der Suche
     */
    public record Result(List<KeywordSearch.Hit> hits, List<String> terms, boolean capped, boolean complete,
                         int searches) {

        public static final Result NONE = new Result(List.of(), List.of(), false, true, 0);

        public Result {
            hits = List.copyOf(hits);
            terms = List.copyOf(terms);
        }
    }

    private static final System.Logger LOG = System.getLogger(TermSearch.class.getName());

    private final KeywordSearch keywords;
    private final Scope scope;
    private final Knowledge knowledge;

    public TermSearch(KeywordSearch keywords, Scope scope, Knowledge knowledge) {
        this.keywords = Objects.requireNonNull(keywords, "keywords");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    }

    /**
     * @param query     die Anfrage; ihr Umfang gilt auch für die neuen Treffer
     * @param known     schon gefundene Seiten; sie kommen nicht erneut
     * @param cancelled Frist oder Abbruch der Suche; geprüft zwischen den Proben und an
     *                  {@link TermKnowledge#answer} durchgereicht
     */
    public Result search(AccessContext caller, KeywordSearch.Query query, Collection<PageKey> known,
                         BooleanSupplier cancelled) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(cancelled, "cancelled");
        Set<PageKey> seen = new HashSet<>(known == null ? List.of() : known);
        List<String> words = words(query.text());
        if (words.isEmpty() || cancelled.getAsBoolean()) {
            return new Result(List.of(), List.of(), false, !cancelled.getAsBoolean(), 0);
        }
        PageSet pages = scope.of(caller, query);
        if (pages == null || pages.isEmpty()) {
            return Result.NONE;
        }
        boolean complete = true;
        boolean capped = false;
        int probes = 0;
        boolean[] taken = new boolean[words.size()];
        List<String> terms = new ArrayList<>();
        // Je Begriff seine Belegseiten im Umfang der Anfrage, in der Reihenfolge der Antwort.
        Map<String, List<PageKey>> evidence = new LinkedHashMap<>();
        probing:
        for (int length = Math.min(MAX_WORDS, words.size()); length >= 1; length--) {
            for (int start = 0; start + length <= words.size(); start++) {
                if (terms.size() >= MAX_TERMS) {
                    break probing;
                }
                if (free(taken, start, length) && candidate(words, start, length)) {
                    if (probes >= MAX_PROBES) {
                        complete = false;
                        break probing;
                    }
                    if (cancelled.getAsBoolean()) {
                        return partial(caller, query, evidence, terms, seen, capped, cancelled);
                    }
                    probes++;
                    String phrase = String.join(" ", words.subList(start, start + length));
                    TermKnowledge.Answer answer;
                    try {
                        answer = knowledge.answer(caller, phrase, pages, cancelled);
                    } catch (CancellationException e) {
                        return partial(caller, query, evidence, terms, seen, capped, cancelled);
                    }
                    if (answer.outcome() != TermKnowledge.Outcome.FOUND) {
                        continue;
                    }
                    for (int i = start; i < start + length; i++) {
                        taken[i] = true;
                    }
                    String term = answer.terms().isEmpty() ? phrase : answer.terms().getFirst().term();
                    terms.add(term);
                    capped |= answer.limits().pagesRead() < answer.limits().pagesInScope();
                    List<PageKey> keys = new ArrayList<>();
                    for (TermKnowledge.Page page : answer.pages()) {
                        if (!seen.contains(page.key()) && NeighborScope.allows(query, page.key(),
                                new PageRef(page.key(), page.title(), page.spaceKey()))) {
                            keys.add(page.key());
                        }
                    }
                    evidence.put(term, keys);
                }
            }
        }
        Result found = collect(caller, query, evidence, terms, seen, capped, cancelled);
        return new Result(found.hits(), found.terms(), found.capped(), complete && found.complete(),
                found.searches());
    }

    /** Nach Frist oder Abbruch: was bis dahin gefunden ist, als unvollständig. */
    private Result partial(AccessContext caller, KeywordSearch.Query query, Map<String, List<PageKey>> evidence,
                           List<String> terms, Set<PageKey> seen, boolean capped, BooleanSupplier cancelled) {
        Result found = collect(caller, query, evidence, terms, seen, capped, cancelled);
        return new Result(found.hits(), found.terms(), found.capped(), false, found.searches());
    }

    /** Sucht je Begriff seine Belegseiten mit dem Begriff als Suchtext; höchstens {@link #MAX_HITS} neue Treffer. */
    private Result collect(AccessContext caller, KeywordSearch.Query query, Map<String, List<PageKey>> evidence,
                           List<String> terms, Set<PageKey> seen, boolean capped, BooleanSupplier cancelled) {
        List<KeywordSearch.Hit> hits = new ArrayList<>();
        boolean complete = true;
        int searches = 0;
        for (Map.Entry<String, List<PageKey>> entry : evidence.entrySet()) {
            List<PageKey> candidates = entry.getValue().stream().filter(key -> !seen.contains(key)).toList();
            if (candidates.isEmpty()) {
                continue;
            }
            if (hits.size() >= MAX_HITS || cancelled.getAsBoolean()) {
                complete = false;
                break;
            }
            String term = entry.getKey();
            KeywordSearch.Results results;
            searches++;
            try {
                results = keywords.search(caller, query.withText(term).within(PageSet.ofKeys(candidates)), null,
                        Math.min(KeywordSearch.MAX_LIMIT, MAX_HITS));
            } catch (RuntimeException e) {
                // Die Methode ergänzt nur; ihr Scheitern lässt die übrigen Treffer stehen.
                LOG.log(System.Logger.Level.INFO, "Fachbegriff nicht gesucht ({0})", e.getClass().getName());
                complete = false;
                continue;
            }
            complete &= results.complete();
            Set<PageKey> allowed = new HashSet<>(candidates);
            for (KeywordSearch.Hit hit : results.hits()) {
                PageKey key = new PageKey(hit.sourceId(), hit.pageId());
                if (!allowed.contains(key) || !seen.add(key)) {
                    continue;
                }
                if (hits.size() >= MAX_HITS) {
                    complete = false;
                    break;
                }
                hits.add(hit.withReasons(HitReason.union(hit.reasons(), List.of(HitReason.term(term)))));
            }
        }
        return new Result(hits, terms, capped, complete, searches);
    }

    private static boolean free(boolean[] taken, int start, int length) {
        for (int i = start; i < start + length; i++) {
            if (taken[i]) {
                return false;
            }
        }
        return true;
    }

    /** Eine Wortgruppe beginnt und endet nicht mit einem Füllwort. */
    private static boolean candidate(List<String> words, int start, int length) {
        return !STOP_WORDS.contains(words.get(start).toLowerCase(Locale.ROOT))
                && !STOP_WORDS.contains(words.get(start + length - 1).toLowerCase(Locale.ROOT));
    }

    /** Die Wörter der Anfrage ohne Satzzeichen am Rand; Bindestriche und Punkte im Wort bleiben. */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String raw : (text == null ? "" : text).split("\\s+")) {
            String word = raw.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
            if (!word.isEmpty() && !TermText.normalize(word).isBlank()) {
                words.add(word);
            }
        }
        return words;
    }
}
