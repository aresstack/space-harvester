package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceSearchMatch;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Die globale Schlagwortsuche über den Cache (ADR 0013). Sie läuft in drei Schritten, REST und Desktop-App nutzen genau
 * diesen Anwendungsfall:
 *
 * <ol>
 *   <li><b>Finden:</b> Jede Quelle des Umfangs mit eigener Suche sucht über Titel und Inhalt (Confluence: CQL
 *       {@code siteSearch} über {@code /rest/api/search}, wie die Websuche). Ihre Treffer sind nur Kandidaten: Die
 *       Suche liest ihre Kopfdaten live bei der Quelle und prüft an ihnen {@link AccessKind#METADATA} für genau diese
 *       Seite, bei delegierten Benutzern über die Quelle mit ihrer Identität. Eine längere Frage geht nicht als Satz
 *       an die Quelle, sondern als einige Anfragen aus ihren Begriffen ({@link #nativeQueries}, wie im Enterprise AI
 *       Client), alle Quellen und Anfragen zugleich; je Quelle werden ihre Funde reihum zusammengeführt, jede Seite
 *       einmal. Ein Stapel nimmt reihum Kandidaten aus jeder Quelle in deren Rangfolge, bis {@code limit} erreicht
 *       ist; je Quelle reicht das Finden höchstens {@value #MAX_DEPTH} Treffer tief.</li>
 *   <li><b>Laden:</b> Sichtbare Kandidaten, deren Version nicht im Cache liegt, lädt {@link Prefetch} seitenweise mit
 *       den Rechten des Aufrufers über den Beschaffungs-Use-Case; gleiche Versionen werden nicht erneut geladen. Ein
 *       vollständiger Abgleich ist nie Voraussetzung. Was zuerst lädt, bestimmt mit Reranker {@link ExcerptPriority}
 *       nach Titel und Auszug; das ändert nur die Ladereihenfolge. Was beim Antworten noch lädt, zählt
 *       {@link Results#loading()}.</li>
 *   <li><b>Suchen:</b> Der {@link PageTextIndex} (BM25 über Titel und Text) sucht im Cache, über die Seiten aller
 *       erreichbaren Quellen des Umfangs, auch solcher ohne eigene Suche (lokale Dateien, #180). Nur von hier kommen
 *       Treffer; Ergebnislisten werden nicht zusammengeführt. Jeder Treffer braucht {@link AccessKind#METADATA} und
 *       {@link AccessKind#CONTENT}, weil Fundstelle und Auszug aus dem Inhalt stammen, und wird live geprüft, wenn er
 *       nicht eben erst gefunden wurde.</li>
 * </ol>
 *
 * <p>Der Space-Filter gilt in beiden Suchen. Nicht freigegebene Seiten erscheinen nirgends, auch nicht als Titel,
 * Auszug oder Zähler; was nicht mehr existiert, fällt ohne Spur weg. Der Cursor („Weitere laden“) merkt sich je Quelle,
 * wie weit sie gefunden hat, und welche Seiten schon ausgeliefert sind; der nächste Stapel findet weiter und sucht
 * erneut im Cache, sodass inzwischen geladene Seiten nachrücken. Eine Quelle, die nicht antwortet, verwirft die anderen
 * nicht: sie steht mit {@link SourceState.Status#UNAVAILABLE} in {@link Results#sources()}, und das Ergebnis ist nicht
 * {@code complete}, auch in allen Folgestapeln desselben Cursors. Quellen ohne eigene Suche stehen als
 * {@link SourceState.Status#INDEXED} darin, ohne Cache als {@link SourceState.Status#NOT_SUPPORTED}. Ohne Cache
 * ({@code texts == null}) liefert die Suche die geprüften Treffer der Quellen selbst.
 *
 * <p>Nur Schlagworte, nie Embeddings; die semantische Suche (nur mit Haken) ist
 * {@link com.aresstack.spaceharvester.application.ranking.RelevanceSearch}.
 */
public class KeywordSearch {

    /** Höchstens so viele Treffer je Stapel. */
    public static final int MAX_LIMIT = 100;
    /** So tief blättert die Suche je Quelle höchstens. */
    public static final int MAX_DEPTH = 1000;
    /** Längster Suchtext. */
    public static final int MAX_TEXT_LENGTH = 200;

    /** Standard für die Anfragen je Quelle aus einer Frage (wie {@code DirectAnswers.MAX_QUERIES} im EAC). */
    public static final int DEFAULT_MAX_QUERIES = 4;
    /** Höchstens so viele Anfragen je Quelle aus einer Frage. */
    public static final int MAX_QUERIES = 8;

    /**
     * Standard: alle Funde eines Stapels laden in den Cache, in der Rangfolge nach Titel und Auszug; die Rangfolge
     * bestimmt den Vorrang, keine Obergrenze.
     */
    public static final int DEFAULT_LOADS = MAX_LIMIT;

    /** Gleichzeitige Kopfdatenabrufe eines Stapels. */
    static final int PARALLEL_CHECKS = 6;

    private static final System.Logger LOG = System.getLogger(KeywordSearch.class.getName());
    private static final String CURSOR_PREFIX = "k2.";
    private static final Pattern CURSOR_OFFSET = Pattern.compile("s\\t[^\\t\\n]{1,255}\\t[0-9]{1,4}");
    private static final Pattern CURSOR_UNAVAILABLE = Pattern.compile("u\\t[^\\t\\n]{1,255}");
    private static final Pattern CURSOR_PENDING = Pattern.compile(
            "p\\t[^\\t\\n]{1,255}\\t[A-Za-z0-9._:-]{1,64}\\t[^\\t\\n]{1,255}");
    private static final Pattern CURSOR_VERSION = Pattern.compile("[A-Za-z0-9._:]{1,64}");
    private static final Pattern CURSOR_DELIVERED = Pattern.compile("d\\t[A-Za-z0-9_-]{1,14000}");
    /** Länge der Hashes ausgelieferter Seiten im Cursor. */
    private static final int MARK_BYTES = 6;
    private static final Pattern CURSOR_HEAD = Pattern.compile("[0-9a-f]{24}\\t[01]");

    private final SourceCatalog catalog;
    private final AccessGuard access;
    private final PageTextIndex texts;
    private final Prefetch prefetch;
    private final int maxQueries;
    private final ExcerptPriority excerpts;
    private final int loads;

    public KeywordSearch(SourceCatalog catalog, AccessGuard access) {
        this(catalog, access, null, null);
    }

    /**
     * @param texts    Volltextindex über den Cache oder {@code null} ohne Cache
     * @param prefetch lädt gefundene Seiten in den Cache oder {@code null}; ohne findet die Suche nur, was schon dort
     *                 liegt
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch) {
        this(catalog, access, texts, prefetch, DEFAULT_MAX_QUERIES, ExcerptPriority.off());
    }

    /**
     * @param maxQueries höchstens so viele Anfragen je Quelle für eine Frage (1 bis {@value #MAX_QUERIES}; 1: der
     *                   Suchtext unverändert, wie vor der Fortschreibung von ADR 0013)
     * @param excerpts   Ladereihenfolge der gefundenen Seiten nach Titel und Auszug; {@link ExcerptPriority#off()}
     *                   lädt in der Reihenfolge der Quellen
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch,
                         int maxQueries, ExcerptPriority excerpts) {
        this(catalog, access, texts, prefetch, maxQueries, excerpts, MAX_LIMIT);
    }

    /**
     * @param loads so viele Funde eines Stapels lädt die Suche höchstens in den Cache (1 bis {@value #MAX_LIMIT}), die
     *              besten nach {@code excerpts}, ohne Bewertung die ersten in der Reihenfolge der Quellen; die übrigen
     *              stehen nur als vorläufige Treffer in {@link Results#found()} und laden erst, wenn jemand sie öffnet
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch,
                         int maxQueries, ExcerptPriority excerpts, int loads) {
        if (loads < 1 || loads > MAX_LIMIT) {
            throw new IllegalArgumentException("loads muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        this.loads = loads;
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.access = Objects.requireNonNull(access, "access");
        this.texts = texts;
        this.prefetch = prefetch;
        if (maxQueries < 1 || maxQueries > MAX_QUERIES) {
            throw new IllegalArgumentException("maxQueries muss zwischen 1 und " + MAX_QUERIES + " liegen");
        }
        this.maxQueries = maxQueries;
        this.excerpts = Objects.requireNonNull(excerpts, "excerpts");
    }

    /**
     * Die Anfragen an die Suche der Quellen. Ein knapper Suchausdruck (höchstens {@value SearchTerms#MAX_QUERY_WORDS}
     * Wörter) geht unverändert hinaus. Aus einer längeren Frage werden wie im Enterprise AI Client zuerst ihre stärksten
     * wörtlichen Begriffe ({@link SearchTerms#literalQuery}), dann ihre Schlüsselwörter
     * ({@link SearchTerms#keywordQueries}, ohne Wörter einer Bitte wie „wird“), ohne Dopplungen (Groß- und
     * Kleinschreibung zählt nicht), höchstens {@code maxQueries}.
     */
    List<String> nativeQueries(String text) {
        if (maxQueries == 1 || SearchTerms.isConcise(text)) {
            return List.of(text);
        }
        Map<String, String> queries = new LinkedHashMap<>();
        List<String> derived = new ArrayList<>();
        derived.add(SearchTerms.literalQuery(text));
        derived.addAll(SearchTerms.keywordQueries(SearchTerms.withoutRequestWords(text), maxQueries));
        for (String candidate : derived) {
            String trimmed = candidate.strip();
            if (!trimmed.isEmpty() && queries.size() < maxQueries) {
                queries.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
            }
        }
        return queries.isEmpty() ? List.of(text) : List.copyOf(queries.values());
    }

    /**
     * Die Funde mehrerer Anfragen einer Quelle reihum in ihrer Rangfolge, jede Seite nur beim ersten Fund. Weil jede
     * Anfrage in jedem Stapel ab dem ersten Treffer tiefer gesucht wird, ist die Zusammenführung eines Stapels ein
     * Anfang der des nächsten; die Positionen im Cursor bleiben gültig, solange die Quelle gleich ordnet.
     */
    private static List<SourceSearchMatch> merge(List<List<SourceSearchMatch>> answered) {
        if (answered.size() == 1) {
            return answered.getFirst();
        }
        List<SourceSearchMatch> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int rank = 0; ; rank++) {
            boolean any = false;
            for (List<SourceSearchMatch> matches : answered) {
                if (rank < matches.size()) {
                    any = true;
                    if (seen.add(matches.get(rank).pageId())) {
                        merged.add(matches.get(rank));
                    }
                }
            }
            if (!any) {
                return merged;
            }
        }
    }

    /**
     * Der laufende Stand des Ladens eines Stapels ({@link Results#progress()}); leer, wenn unbekannt. Verrät keine
     * Seiten, nur Zähler.
     */
    public Optional<Progress> progress(String id) {
        return prefetch == null || id == null ? Optional.empty() : prefetch.progress(id);
    }

    /**
     * Bricht das Laden eines Stapels ab ({@link Results#progress()}): Wartende Abrufe entfallen, ein laufender endet
     * regulär. Was schon im Cache liegt, bleibt.
     *
     * @return {@code false}, wenn der Stapel unbekannt ist
     */
    public boolean cancel(String id) {
        return prefetch != null && id != null && prefetch.cancel(id);
    }

    /** Ob die Treffer aus dem Cache kommen (ADR 0013); ohne Cache kommen sie aus der Suche der Quellen. */
    public boolean searchesTheCache() {
        return texts != null;
    }

    /**
     * Ein Stapel Treffer.
     *
     * @param cursor {@link Results#nextCursor()} des vorigen Stapels derselben Suche oder {@code null}
     * @param limit  1 bis {@value #MAX_LIMIT}
     * @throws InvalidQueryException bei ungültigem Limit oder Cursor
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException nur mit {@code sourceId}, wie bei
     *                                                                              {@link SearchScope#authorize}
     * @throws com.aresstack.spaceharvester.application.sources.UnknownSourceException mit unbekannter {@code sourceId}
     */
    public Results search(AccessContext caller, Query query, String cursor, int limit) {
        Objects.requireNonNull(query, "query");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        SearchScope scope = SearchScope.authorize(access, catalog, caller, query.scope());
        scope.requireKnown(catalog);
        Position position = cursor == null || cursor.isEmpty() ? null : decode(cursor, fingerprint(query));
        Map<String, Integer> offsets = position == null ? null : position.offsets();
        boolean cached = texts != null;

        List<SourceState> states = new ArrayList<>();
        Map<String, List<SourceSearchMatch>> pending = new LinkedHashMap<>();
        Map<String, Boolean> more = new LinkedHashMap<>();
        Map<String, Integer> start = new LinkedHashMap<>();
        Set<String> indexed = new HashSet<>();
        Set<String> searchable = new LinkedHashSet<>();
        // Was ein früherer Stapel nicht prüfen konnte, bleibt bis zum Ende offen.
        boolean complete = position == null || !position.incomplete();
        List<LiveSearch> searches = new ArrayList<>();
        for (HarvestSource source : catalog.all()) {
            String sourceId = source.id();
            if (!scope.sourceIds().contains(sourceId)) {
                continue;
            }
            boolean live = source.capabilities().contains(SourceCapability.LIVE_SEARCH);
            if (!live && !cached) {
                if (position == null) {
                    states.add(new SourceState(sourceId, SourceState.Status.NOT_SUPPORTED, null));
                }
                continue;
            }
            if (!live) {
                // Ohne eigene Suche: nur der Cache, in jedem Stapel.
                indexed.add(sourceId);
                searchable.add(sourceId);
                if (position == null) {
                    states.add(new SourceState(sourceId, SourceState.Status.INDEXED, null));
                }
                continue;
            }
            if (offsets != null && !offsets.containsKey(sourceId)) {
                // Fertig gesucht; ihre geladenen Seiten stehen weiter im Cache.
                if (cached && !position.unavailable().contains(sourceId)) {
                    searchable.add(sourceId);
                }
                continue;
            }
            int offset = offsets == null ? 0 : offsets.get(sourceId);
            searches.add(new LiveSearch(source, offset, Math.min(offset + limit + 1, MAX_DEPTH), states.size()));
            states.add(null);
        }
        // Alle Quellen und alle Anfragen zugleich; ausgewertet in der Reihenfolge der Quellen.
        List<String> nativeQueries = nativeQueries(query.text());
        List<List<Future<List<SourceSearchMatch>>>> calls = new ArrayList<>();
        try (ExecutorService searching = Executors.newVirtualThreadPerTaskExecutor()) {
            for (LiveSearch search : searches) {
                List<Future<List<SourceSearchMatch>>> perQuery = new ArrayList<>();
                for (String text : nativeQueries) {
                    perQuery.add(searching.submit(() -> search.source().searchPages(text, search.wanted(),
                            query.spaces())));
                }
                calls.add(perQuery);
            }
        }
        for (int s = 0; s < searches.size(); s++) {
            LiveSearch search = searches.get(s);
            String sourceId = search.source().id();
            int offset = search.offset();
            List<List<SourceSearchMatch>> answered = new ArrayList<>();
            SourceAccessException failure = null;
            for (Future<List<SourceSearchMatch>> call : calls.get(s)) {
                try {
                    answered.add(call.get());
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof SourceAccessException sourceFailure) {
                        failure = failure == null ? sourceFailure : failure;
                    } else if (e.getCause() instanceof RuntimeException runtime) {
                        throw runtime;
                    } else {
                        throw new IllegalStateException(e.getCause());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure = failure == null ? new SourceAccessException(sourceId,
                            SourceAccessException.Reason.UNAVAILABLE, "unterbrochen", null) : failure;
                }
            }
            if (answered.isEmpty()) {
                // Ohne Meldung: Texte des Transports können Hosts und Routen nennen.
                LOG.log(System.Logger.Level.INFO, "Suche in Quelle {0} nicht möglich ({1})", sourceId,
                        failure.reason());
                states.set(search.state(), new SourceState(sourceId, SourceState.Status.UNAVAILABLE,
                        failure.reason().name()));
                complete = false;
                continue;
            }
            if (failure != null) {
                // Ein Teil der Anfragen fehlt: gefunden ist, was die übrigen fanden, aber nicht alles.
                LOG.log(System.Logger.Level.INFO, "Suche in Quelle {0} nur teilweise möglich ({1})", sourceId,
                        failure.reason());
                complete = false;
            }
            List<SourceSearchMatch> matches = merge(answered);
            List<SourceSearchMatch> fresh = matches.size() <= offset ? List.of()
                    : matches.subList(offset, Math.min(matches.size(), offset + limit));
            pending.put(sourceId, new ArrayList<>(fresh));
            more.put(sourceId, matches.size() > offset + fresh.size() && offset + fresh.size() < MAX_DEPTH);
            start.put(sourceId, offset);
            searchable.add(sourceId);
            states.set(search.state(), new SourceState(sourceId, SourceState.Status.SEARCHED, null));
        }
        Set<String> unavailable = new TreeSet<>(position == null ? Set.of() : position.unavailable());
        states.stream().filter(state -> state.status() == SourceState.Status.UNAVAILABLE)
                .forEach(state -> unavailable.add(state.sourceId()));

        // Reihum je Quelle in ihrer Rangfolge, bis der Stapel voll ist.
        List<Candidate> candidates = new ArrayList<>();
        Map<String, Integer> taken = new LinkedHashMap<>();
        pending.keySet().forEach(id -> taken.put(id, 0));
        boolean added = true;
        while (candidates.size() < limit && added) {
            added = false;
            for (Map.Entry<String, List<SourceSearchMatch>> entry : pending.entrySet()) {
                int index = taken.get(entry.getKey());
                if (index < entry.getValue().size() && candidates.size() < limit) {
                    candidates.add(new Candidate(entry.getKey(), entry.getValue().get(index), false, null));
                    taken.put(entry.getKey(), index + 1);
                    added = true;
                }
            }
        }

        Map<String, Integer> next = new LinkedHashMap<>();
        for (String sourceId : pending.keySet()) {
            int used = taken.get(sourceId);
            if (used < pending.get(sourceId).size() || more.get(sourceId)) {
                next.put(sourceId, start.get(sourceId) + used);
            }
        }
        if (!cached) {
            // Ohne Cache: die Treffer der Quellen selbst, live geprüft.
            Checked found = check(caller, query, candidates);
            boolean checkedAll = complete && found.complete();
            return new Results(scope.sourceId(), found.hits(), states, next.isEmpty() ? null
                    : encode(fingerprint(query), !checkedAll, next, unavailable, Set.of(), List.of()),
                    checkedAll && next.isEmpty(), 0);
        }

        // ADR 0013 (Fortschreibung 18.10.): Die Funde der Quellen sind sofort vorläufige Treffer mit Titel und Auszug,
        // ohne Inhaltsabruf. Bewertet nach Titel und Auszug (#154) laden nur die besten in den Cache; der Volltextindex
        // findet sie dort, und seine Treffer ersetzen die vorläufigen. Was ein früherer Stapel noch lud, fragt dieser
        // Stapel wieder ab, damit es nicht verloren geht.
        // ADR 0014, Fortschreibung Punkt 4: Ein Endnutzer mit delegierter Anmeldung bekommt nur die endgültige Liste aus
        // dem Cache, seitenweise mit seinem Token geprüft. Die Funde der Quelle bleiben ungeprüft und nur zum Laden.
        boolean finalListOnly = caller != null && caller.kind() == AccessContext.PrincipalKind.END_USER;
        Checked discovered = finalListOnly ? unchecked(candidates) : provisional(caller, query, candidates);
        complete &= discovered.complete();
        List<Hit> found = discovered.hits();
        List<Hit> ranked = excerpts.prioritize(query.text(), found).apply(found);
        List<Hit> toLoad = new ArrayList<>(ranked.subList(0, Math.min(loads, ranked.size())));
        if (position != null) {
            Set<PageKey> seen = new HashSet<>();
            toLoad.forEach(hit -> seen.add(new PageKey(hit.sourceId(), hit.pageId())));
            for (Hit hit : position.pending()) {
                if (scope.sourceIds().contains(hit.sourceId()) && !unavailable.contains(hit.sourceId())
                        && seen.add(new PageKey(hit.sourceId(), hit.pageId()))) {
                    toLoad.add(hit);
                }
            }
        }
        List<Hit> stillLoading = List.of();
        Progress progress = null;
        if (prefetch != null && !toLoad.isEmpty()) {
            try {
                Prefetch.Fill fill = prefetch.prefetch(caller, toLoad);
                stillLoading = fill.pending().size() > MAX_LIMIT ? fill.pending().subList(0, MAX_LIMIT)
                        : fill.pending();
                progress = fill.progress();
                // Gescheitertes Laden ist eine Lücke; der Stapel ist dann nicht vollständig. Langsames Laden ist keine:
                // Was über die verfolgten Abrufe hinaus noch lädt, steht schon als vorläufiger Treffer in der Liste.
                complete &= fill.failed() == 0;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Laden der Treffer nicht möglich ({0})", e.getClass().getName());
                complete = false;
            }
        }
        int loading = stillLoading.size();
        // Eben gefunden und geprüft: Der Cache-Treffer braucht keine zweite Rückfrage bei der Quelle.
        Map<PageKey, Hit> known = new HashMap<>();
        found.forEach(hit -> known.putIfAbsent(new PageKey(hit.sourceId(), hit.pageId()), hit));
        Set<String> delivered = position == null ? new LinkedHashSet<>() : new LinkedHashSet<>(position.delivered());
        List<Candidate> fromCache = new ArrayList<>();
        boolean moreInCache = false;
        if (!searchable.isEmpty() && delivered.size() < MAX_DEPTH) {
            try {
                int wanted = Math.min(delivered.size() + limit + 1, MAX_DEPTH);
                for (PageTextIndex.Match match : texts.search(query.text(), searchable, query.spaces(), wanted)) {
                    String mark = mark(match.sourceId(), match.pageId());
                    if (delivered.contains(mark)) {
                        continue;
                    }
                    if (fromCache.size() == limit) {
                        moreInCache = true;
                        break;
                    }
                    SourceSearchMatch hit = new SourceSearchMatch(match.pageId(), match.title(), match.snippet(), null,
                            null, null);
                    PageKey key = new PageKey(match.sourceId(), match.pageId());
                    Hit seen = known.get(key);
                    if (seen != null && seen.spaceKey() == null) {
                        // Vorläufige Treffer kennen Space und Elternseite nicht; der Cache kennt sie, sonst live.
                        Hit provisional = seen;
                        seen = prefetch == null ? null : prefetch.cachedMetadata(key)
                                .map(m -> new Hit(provisional.sourceId(), m.pageId(), m.partitionKey(),
                                        m.title().isEmpty() ? provisional.title() : m.title(), provisional.snippet(),
                                        m.url() != null ? m.url() : provisional.url(),
                                        m.version() != null ? m.version() : provisional.version(),
                                        m.modifiedAt() != null ? m.modifiedAt() : provisional.modifiedAt(),
                                        m.parentPageId()))
                                .orElse(null);
                    }
                    fromCache.add(new Candidate(match.sourceId(), hit, true, seen));
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Volltextindex nicht lesbar ({0})", e.getClass().getName());
                for (int i = 0; i < states.size(); i++) {
                    SourceState state = states.get(i);
                    if (state.status() == SourceState.Status.INDEXED) {
                        states.set(i, new SourceState(state.sourceId(), SourceState.Status.UNAVAILABLE,
                                SourceAccessException.Reason.UNAVAILABLE.name()));
                    }
                }
                complete = false;
            }
        }
        Checked checked = check(caller, query, fromCache);
        fromCache.forEach(candidate -> delivered.add(mark(candidate.sourceId(), candidate.match().pageId())));
        boolean checkedAll = complete && checked.complete();
        boolean exhausted = next.isEmpty() && !moreInCache && loading == 0;
        String nextCursor = exhausted || delivered.size() >= MAX_DEPTH ? null
                : encode(fingerprint(query), !checkedAll, next, unavailable, delivered,
                        // Der Cursor geht an den Aufrufer: keine IDs ungeprüfter Seiten.
                        finalListOnly ? List.of() : stillLoading);
        // Vorläufig bleibt, was der Volltextindex (noch) nicht liefert, in der Rangfolge nach Titel und Auszug.
        Set<PageKey> fromIndex = new HashSet<>();
        checked.hits().forEach(hit -> fromIndex.add(new PageKey(hit.sourceId(), hit.pageId())));
        List<Hit> provisional = finalListOnly ? List.of() : ranked.stream()
                .filter(hit -> !fromIndex.contains(new PageKey(hit.sourceId(), hit.pageId()))).toList();
        return new Results(scope.sourceId(), checked.hits(), states, nextCursor, checkedAll && exhausted, loading,
                provisional, progress);
    }

    /** Kurzes Zeichen einer ausgelieferten Seite für den Cursor; verrät weder Quelle noch Seite. */
    private static String mark(String sourceId, String pageId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((sourceId + "\u0000" + pageId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, MARK_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    /**
     * Die Funde der Quellen als vorläufige Treffer, ohne Abruf bei der Quelle: Die Quelle hat mit dem Space-Filter und,
     * bei delegierten Benutzern, mit deren Identität gesucht. Hier entscheidet nur die Richtlinie für die ganze Quelle:
     * ohne {@link AccessKind#METADATA} fällt der Fund weg, ohne {@link AccessKind#CONTENT} sein Auszug, der aus dem
     * Inhalt stammt. Gilt die Freigabe nur für einzelne Spaces, prüft die Suche diese Funde wie bisher live je Seite,
     * denn der Fund nennt seinen Space nicht. Endgültige Treffer prüft der Volltextschritt je Seite.
     */
    private Checked provisional(AccessContext caller, Query query, List<Candidate> candidates) {
        Map<String, Boolean> metadata = new HashMap<>();
        Map<String, Boolean> content = new HashMap<>();
        List<Candidate> live = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!metadata.computeIfAbsent(candidate.sourceId(),
                    id -> access.permitsSourceWide(caller, id, AccessKind.METADATA))) {
                live.add(candidate);
            }
        }
        Map<PageKey, Hit> checked = new HashMap<>();
        boolean complete = true;
        if (!live.isEmpty()) {
            Checked result = check(caller, query, live);
            result.hits().forEach(hit -> checked.put(new PageKey(hit.sourceId(), hit.pageId()), hit));
            complete = result.complete();
        }
        List<Hit> hits = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String sourceId = candidate.sourceId();
            SourceSearchMatch match = candidate.match();
            if (!metadata.get(sourceId)) {
                Hit hit = checked.get(new PageKey(sourceId, match.pageId()));
                if (hit != null) {
                    hits.add(hit);
                }
                continue;
            }
            boolean snippet = content.computeIfAbsent(sourceId,
                    id -> access.permitsSourceWide(caller, id, AccessKind.CONTENT));
            hits.add(new Hit(sourceId, match.pageId(), null, match.title(), snippet ? match.snippet() : "",
                    match.url(), match.version(), match.modifiedAt(), null));
        }
        return new Checked(hits, complete);
    }

    /**
     * Die Funde der Quellen ohne jede Prüfung, nur als Kandidaten zum Bewerten und Laden; sie verlassen die Suche nicht.
     */
    private static Checked unchecked(List<Candidate> candidates) {
        List<Hit> hits = new ArrayList<>();
        for (Candidate candidate : candidates) {
            SourceSearchMatch match = candidate.match();
            hits.add(new Hit(candidate.sourceId(), match.pageId(), null, match.title(), match.snippet(), match.url(),
                    match.version(), match.modifiedAt(), null));
        }
        return new Checked(hits, true);
    }

    /** Kopfdaten live und Freigabe je Treffer; parallel, Reihenfolge der Kandidaten bleibt. */
    private Checked check(AccessContext caller, Query query, List<Candidate> candidates) {
        Semaphore slots = new Semaphore(PARALLEL_CHECKS);
        List<Future<Optional<Hit>>> futures = new ArrayList<>();
        try (ExecutorService checks = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Candidate candidate : candidates) {
                futures.add(checks.submit(() -> {
                    slots.acquireUninterruptibly();
                    try {
                        return hit(caller, query, candidate);
                    } finally {
                        slots.release();
                    }
                }));
            }
        }
        List<Hit> hits = new ArrayList<>();
        boolean complete = true;
        for (Future<Optional<Hit>> future : futures) {
            try {
                future.get().ifPresent(hits::add);
            } catch (ExecutionException e) {
                complete = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                complete = false;
            }
        }
        return new Checked(hits, complete);
    }

    /**
     * Der Treffer, wenn die Seite existiert, zum Filter passt und für den Aufrufer freigegeben ist.
     *
     * @throws SourceAccessException wenn die Kopfdaten nicht lesbar sind; der Stapel ist dann unvollständig
     */
    private Optional<Hit> hit(AccessContext caller, Query query, Candidate candidate) {
        if (candidate.known() != null) {
            // Eben gefunden, live geprüft und geladen; aus dem Cache kommen nur Fundstelle und Auszug.
            Hit known = candidate.known();
            if (!access.permits(caller, ResourceScope.page(known.sourceId(), known.spaceKey(), known.pageId()),
                    AccessKind.CONTENT)) {
                return Optional.empty();
            }
            // Steht das Wort nur im Titel, hat der Cache kein Textfenster; dann bleibt der Auszug der Quelle.
            String snippet = candidate.match().snippet() == null || candidate.match().snippet().isEmpty()
                    ? known.snippet() : candidate.match().snippet();
            return Optional.of(new Hit(known.sourceId(), known.pageId(), known.spaceKey(), known.title(), snippet,
                    known.url(), known.version(), known.modifiedAt(),
                    known.parentPageId()));
        }
        PageMetadata metadata;
        HarvestSource source = catalog.find(candidate.sourceId()).orElse(null);
        if (source == null) {
            return Optional.empty();
        }
        try {
            metadata = source.pageMetadata(candidate.match().pageId());
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.NOT_FOUND
                    || e.reason() == SourceAccessException.Reason.UNSUPPORTED) {
                return Optional.empty();
            }
            // Ohne Seiten-ID: der Treffer kann für den Aufrufer unsichtbar sein.
            LOG.log(System.Logger.Level.INFO, "Kopfdaten eines Suchtreffers in {0} nicht lesbar ({1})",
                    candidate.sourceId(), e.reason());
            throw e;
        }
        if (!query.spaces().permits(metadata.partitionKey())) {
            return Optional.empty();
        }
        ResourceScope page = ResourceScope.page(candidate.sourceId(), metadata.partitionKey(), metadata.pageId());
        if (!access.permits(caller, page, AccessKind.METADATA)) {
            return Optional.empty();
        }
        // Fundstelle und Auszug aus dem Index stammen aus dem Inhalt.
        if (candidate.indexed() && !access.permits(caller, page, AccessKind.CONTENT)) {
            return Optional.empty();
        }
        SourceSearchMatch match = candidate.match();
        // Auch der Auszug der Quelle stammt aus dem Inhalt: ohne Freigabe für den Inhalt kein Auszug.
        String snippet = candidate.indexed() || access.permits(caller, page, AccessKind.CONTENT) ? match.snippet() : "";
        return Optional.of(new Hit(candidate.sourceId(), metadata.pageId(), metadata.partitionKey(),
                metadata.title().isEmpty() ? match.title() : metadata.title(), snippet,
                metadata.url() != null ? metadata.url() : match.url(),
                metadata.version() != null ? metadata.version() : match.version(),
                metadata.modifiedAt() != null ? metadata.modifiedAt() : match.modifiedAt(),
                metadata.parentPageId()));
    }

    /**
     * Der Cursor gilt nur für dieselbe Anfrage. Er trägt mit, ob ein früherer Stapel unvollständig war, wie weit jede
     * Quelle gesucht ist, welche ausgefallen sind und welche Seiten schon ausgeliefert wurden (als kurze Hashes), damit
     * „Weitere laden“ nach dem Nachladen im Cache weitersucht, ohne Seiten doppelt zu zeigen.
     */
    private static String encode(String fingerprint, boolean incomplete, Map<String, Integer> offsets,
                                 Set<String> unavailable, Set<String> delivered, List<Hit> pending) {
        StringBuilder text = new StringBuilder(fingerprint).append('\t').append(incomplete ? '1' : '0');
        offsets.forEach((id, offset) -> text.append("\ns\t").append(id).append('\t').append(offset));
        unavailable.forEach(id -> text.append("\nu\t").append(id));
        for (Hit hit : pending) {
            if (hit.pageId().length() <= 255 && hit.pageId().indexOf('\t') < 0 && hit.pageId().indexOf('\n') < 0
                    && (hit.version() == null || CURSOR_VERSION.matcher(hit.version()).matches())) {
                text.append("\np\t").append(hit.sourceId()).append('\t')
                        .append(hit.version() == null ? "-" : hit.version()).append('\t').append(hit.pageId());
            }
        }
        if (!delivered.isEmpty()) {
            text.append("\nd\t").append(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(HexFormat.of().parseHex(String.join("", delivered))));
        }
        return CURSOR_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Position decode(String cursor, String fingerprint) {
        if (!cursor.startsWith(CURSOR_PREFIX) || cursor.length() > 20_000) {
            throw new InvalidQueryException("cursor ist ungültig");
        }
        String text;
        try {
            text = new String(Base64.getUrlDecoder().decode(cursor.substring(CURSOR_PREFIX.length())),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException("cursor ist ungültig");
        }
        String[] lines = text.split("\n");
        if (lines.length < 1 || !CURSOR_HEAD.matcher(lines[0]).matches()) {
            throw new InvalidQueryException("cursor ist ungültig");
        }
        if (!lines[0].startsWith(fingerprint)) {
            throw new InvalidQueryException("cursor gehört zu einer anderen Suchanfrage");
        }
        Map<String, Integer> offsets = new LinkedHashMap<>();
        Set<String> unavailable = new TreeSet<>();
        Set<String> delivered = new LinkedHashSet<>();
        List<Hit> pending = new ArrayList<>();
        for (String entry : List.of(lines).subList(1, lines.length)) {
            if (CURSOR_OFFSET.matcher(entry).matches()) {
                int tab = entry.lastIndexOf('\t');
                int offset = Integer.parseInt(entry.substring(tab + 1));
                if (offset >= MAX_DEPTH) {
                    throw new InvalidQueryException("cursor ist ungültig");
                }
                offsets.put(entry.substring(2, tab), offset);
            } else if (CURSOR_PENDING.matcher(entry).matches() && pending.size() < MAX_LIMIT) {
                String[] parts = entry.split("\t", 4);
                pending.add(new Hit(parts[1], parts[3], null, "", "", null, "-".equals(parts[2]) ? null : parts[2],
                        null, null));
            } else if (CURSOR_UNAVAILABLE.matcher(entry).matches()) {
                unavailable.add(entry.substring(2));
            } else if (CURSOR_DELIVERED.matcher(entry).matches() && delivered.isEmpty()) {
                byte[] marks;
                try {
                    marks = Base64.getUrlDecoder().decode(entry.substring(2));
                } catch (IllegalArgumentException e) {
                    throw new InvalidQueryException("cursor ist ungültig");
                }
                if (marks.length % MARK_BYTES != 0 || marks.length / MARK_BYTES > MAX_DEPTH) {
                    throw new InvalidQueryException("cursor ist ungültig");
                }
                for (int i = 0; i < marks.length; i += MARK_BYTES) {
                    delivered.add(HexFormat.of().formatHex(marks, i, i + MARK_BYTES));
                }
            } else {
                throw new InvalidQueryException("cursor ist ungültig");
            }
        }
        return new Position(offsets, lines[0].endsWith("1"), unavailable, delivered, pending);
    }

    private static String fingerprint(Query query) {
        String canonical = String.join("\u0000", query.text(), String.valueOf(query.sourceId()),
                String.join("\u0001", new TreeSet<>(query.sourceIds())),
                String.join("\u0001", new TreeSet<>(query.spaceKeys())));
        if (!query.excludedSpaceKeys().isEmpty()) {
            // Nur mit Ausschluss, damit Cursor von Anfragen ohne ihn gültig bleiben.
            canonical += "\u0000-" + String.join("\u0001", new TreeSet<>(query.excludedSpaceKeys()));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    /** @param pending Seiten, die beim vorigen Stapel noch luden; nur Quelle, Seite und Version */
    private record Position(Map<String, Integer> offsets, boolean incomplete, Set<String> unavailable,
                            Set<String> delivered, List<Hit> pending) {
    }

    /** @param state Platz der Quelle in den Zuständen des Stapels */
    private record LiveSearch(HarvestSource source, int offset, int wanted, int state) {
    }

    /** @param known eben live geprüfter Treffer derselben Seite oder {@code null} */
    private record Candidate(String sourceId, SourceSearchMatch match, boolean indexed, Hit known) {
    }

    /**
     * Lädt die gefundenen Seiten in den Cache (ADR 0013, wie {@code KnowledgePrefetchService} im Enterprise AI Client),
     * mit den Rechten des Aufrufers über den Beschaffungs-Use-Case.
     */
    @FunctionalInterface
    public interface Prefetch {

        /**
         * Lädt und wartet begrenzt.
         *
         * @param hits für {@code caller} sichtbare Treffer der Quellensuche in Rangfolge, dazu was ein früherer Stapel
         *             noch lud
         */
        Fill prefetch(AccessContext caller, List<Hit> hits);

        /** Der laufende Stand eines Stapels ({@link Fill#progressId()}); leer, wenn unbekannt oder verdrängt. */
        default Optional<Progress> progress(String id) {
            return Optional.empty();
        }

        /** Bricht das Laden eines Stapels ab; {@code false}, wenn unbekannt. */
        /** Kopfdaten einer Seite im Cache, ohne Rückfrage bei der Quelle; leer, wenn unbekannt. */
        default Optional<PageMetadata> cachedMetadata(PageKey key) {
            return Optional.empty();
        }

        default boolean cancel(String id) {
            return false;
        }

        /**
         * Ausgang des Ladens.
         *
         * @param pending    Seiten, die beim Antworten noch laden; der Cursor fragt sie im nächsten Stapel wieder ab
         * @param failed     wie viele sich nicht laden ließen; das Ergebnis ist dann unvollständig
         * @param progressId Kennung für {@link #progress} oder {@code null}
         * @param progress   Stand beim Antworten oder {@code null}
         */
        record Fill(List<Hit> pending, int failed, String progressId, Progress progress) {

            public static final Fill NONE = new Fill(List.of(), 0);

            public Fill {
                pending = List.copyOf(pending);
            }

            public Fill(List<Hit> pending, int failed) {
                this(pending, failed, null, null);
            }
        }
    }

    private record Checked(List<Hit> hits, boolean complete) {
    }

    /**
     * Eine Schlagwortsuche.
     *
     * @param text      Suchtext, wie ihn die Quelle versteht (Confluence: Websuche über Titel und Inhalt)
     * @param sourceId  nur diese Quelle oder {@code null} für alle freigegebenen
     * @param sourceIds ohne {@code sourceId}: nur diese Quellen; leer für alle freigegebenen
     * @param spaceKeys nur Seiten dieser Spaces, global in jeder Quelle; leer für alle
     * @param excludedSpaceKeys ohne Seiten dieser Spaces (#179, wie die abgehakten Spaces im Enterprise AI Client)
     */
    public record Query(String text, String sourceId, Set<String> sourceIds, Set<String> spaceKeys,
                        Set<String> excludedSpaceKeys) {

        public Query {
            if (text == null || text.isBlank()) {
                throw new InvalidQueryException("Suchtext fehlt");
            }
            text = text.strip();
            if (text.length() > MAX_TEXT_LENGTH) {
                throw new InvalidQueryException("Suchtext hat höchstens " + MAX_TEXT_LENGTH + " Zeichen");
            }
            // Prüft Quellen- und Space-Angaben wie die Titelsuche.
            PageSearchQuery checked = PageSearchQuery.of(sourceId, List.copyOf(sourceIds), List.copyOf(spaceKeys),
                    null, null, null, null, null, null, SearchSort.TITLE);
            sourceId = checked.sourceId();
            sourceIds = checked.sourceIds();
            spaceKeys = checked.spaceKeys();
            excludedSpaceKeys = PageSearchQuery.of(null, List.of(), List.copyOf(excludedSpaceKeys == null ? Set.of()
                    : excludedSpaceKeys), null, null, null, null, null, null, SearchSort.TITLE).spaceKeys();
        }

        public Query(String text, String sourceId, Set<String> sourceIds, Set<String> spaceKeys) {
            this(text, sourceId, sourceIds, spaceKeys, Set.of());
        }

        /** Alle freigegebenen Quellen. */
        public static Query of(String text) {
            return new Query(text, null, Set.of(), Set.of());
        }

        /** Der Space-Filter für die Quelle. */
        public SearchSpaces spaces() {
            return new SearchSpaces(spaceKeys, excludedSpaceKeys);
        }

        PageSearchQuery scope() {
            return new PageSearchQuery(sourceId, sourceIds, spaceKeys, List.of(), null, null, null, null, null,
                    SearchSort.TITLE);
        }
    }

    /**
     * Ein Stapel.
     *
     * @param sourceId   die angefragte Quelle oder {@code null} bei der globalen Suche
     * @param hits       sichtbare Treffer aus dem Cache, in der Rangfolge des Volltextindex; kann kürzer als das Limit
     *                   sein, auch wenn noch Treffer folgen
     * @param sources    je Quelle des Umfangs, ob sie gesucht hat; im ersten Stapel alle, danach nur die noch offenen
     * @param nextCursor für „Weitere laden“ mit derselben Anfrage, {@code null}, wenn keine Quelle mehr Treffer hat,
     *                   der Cache keine weiteren kennt und nichts mehr lädt
     * @param complete   nichts folgt mehr, und jede Quelle und jeder Treffer dieses und aller früheren Stapel ließ sich
     *                   prüfen
     * @param loading    gefundene Seiten dieses Stapels, die noch nicht im Cache liegen; sie fehlen in {@code hits}
     *                   und erscheinen, sobald geladen, über „Weitere laden“
     * @param found      vorläufige Treffer: Funde der Quellen mit Titel und Auszug der Quelle, ohne Inhaltsabruf,
     *                   nach Titel und Auszug bewertet ({@link ExcerptPriority}), ohne die Seiten in {@code hits}
     * @param progress   Stand des Ladens der besten Funde oder {@code null}, wenn nichts lädt; siehe {@link Progress}
     */
    public record Results(String sourceId, List<Hit> hits, List<SourceState> sources, String nextCursor,
                          boolean complete, int loading, List<Hit> found, Progress progress) {

        public Results {
            hits = List.copyOf(hits);
            sources = List.copyOf(sources);
            found = found == null ? List.of() : List.copyOf(found);
        }

        public Results(String sourceId, List<Hit> hits, List<SourceState> sources, String nextCursor,
                       boolean complete, int loading) {
            this(sourceId, hits, sources, nextCursor, complete, loading, List.of(), null);
        }

        /**
         * Die ganze Trefferliste des Stapels: zuerst die Treffer aus dem Cache (im Inhalt bestätigt, Rangfolge des
         * Volltextindex), dann die vorläufigen in ihrer Bewertung nach Titel und Auszug. Ein Treffer behält seine
         * Identität (Quelle und Seiten-ID) über erneute Suchen: Ist er geladen, rückt er nach vorn, statt zu
         * verschwinden; die Liste wird neu geordnet, nicht ersetzt.
         */
        public List<Hit> ranked() {
            List<Hit> all = new ArrayList<>(hits);
            all.addAll(found);
            return List.copyOf(all);
        }
    }

    /**
     * Wie weit die ausgewählten Seiten eines Stapels in den Cache geladen sind. Die Oberfläche fragt
     * {@link #progress(String)} ab, zeigt „{@code loaded} von {@code total}“ und sucht erneut, sobald {@code loaded}
     * gestiegen ist; dann stehen die neuen Seiten in {@link Results#hits()}.
     *
     * <p>Geladen wird in der Rangfolge nach Titel und Auszug, die besten zuerst, alle Funde des Stapels; die Liste
     * wird dabei nur neu geordnet ({@link Results#ranked()}). Ist {@link #done()} wahr, ist die Suche abgeschlossen;
     * {@link #cancel(String)} bricht sie vorher ab.
     *
     * @param id        Kennung für {@link #progress(String)} und {@link #cancel(String)}
     * @param total     wie viele Seiten dieser Stapel lädt
     * @param loaded    davon im Cache
     * @param failed    davon nicht ladbar oder abgebrochen
     * @param cancelled ob abgebrochen wurde
     */
    public record Progress(String id, int total, int loaded, int failed, boolean cancelled) {

        public Progress(String id, int total, int loaded, int failed) {
            this(id, total, loaded, failed, false);
        }

        /** Ob nichts mehr lädt: alles geladen, gescheitert oder abgebrochen. */
        public boolean done() {
            return loaded + failed >= total;
        }
    }

    /**
     * Ein Treffer; Kopfdaten live von der Quelle, Auszug aus dem Cache (ohne Cache aus der Suche der Quelle).
     *
     * @param snippet Textauszug als Klartext, leer, wenn es keinen gibt
     */
    public record Hit(String sourceId, String pageId, String spaceKey, String title, String snippet, URI url,
                      String version, Instant modifiedAt, String parentPageId) {
    }

    /**
     * Ob eine Quelle gesucht hat.
     *
     * @param reason bei {@link Status#UNAVAILABLE} der Grund ({@link SourceAccessException.Reason}), sonst
     *               {@code null}
     */
    public record SourceState(String sourceId, Status status, String reason) {

        public enum Status {
            /** Die Quelle hat gesucht; ihre Funde sind in den Cache geladen und dort gesucht. */
            SEARCHED,
            /** Die Quelle kann nicht selbst suchen; der Volltextindex des Caches hat für sie gesucht (#180). */
            INDEXED,
            /** Die Quelle kann nicht selbst suchen, und es gibt keinen Index; ihre Seiten fehlen. */
            NOT_SUPPORTED,
            /** Die Quelle hat nicht oder fehlerhaft geantwortet. */
            UNAVAILABLE
        }
    }
}
