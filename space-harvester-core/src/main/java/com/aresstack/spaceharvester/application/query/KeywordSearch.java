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
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
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
 *       Seite, bei delegierten Benutzern über die Quelle mit ihrer Identität. Ein Stapel nimmt reihum Kandidaten aus
 *       jeder Quelle in deren Rangfolge, bis {@code limit} erreicht ist; je Quelle reicht das Finden höchstens
 *       {@value #MAX_DEPTH} Treffer tief.</li>
 *   <li><b>Laden:</b> Sichtbare Kandidaten, deren Version nicht im Cache liegt, lädt {@link Prefetch} seitenweise mit
 *       den Rechten des Aufrufers über den Beschaffungs-Use-Case; gleiche Versionen werden nicht erneut geladen. Ein
 *       vollständiger Abgleich ist nie Voraussetzung. Was beim Antworten noch lädt, zählt {@link Results#loading()}.</li>
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

    /** Gleichzeitige Kopfdatenabrufe eines Stapels. */
    static final int PARALLEL_CHECKS = 6;

    private static final System.Logger LOG = System.getLogger(KeywordSearch.class.getName());
    private static final String CURSOR_PREFIX = "k2.";
    private static final Pattern CURSOR_OFFSET = Pattern.compile("s\\t[^\\t\\n]{1,255}\\t[0-9]{1,4}");
    private static final Pattern CURSOR_UNAVAILABLE = Pattern.compile("u\\t[^\\t\\n]{1,255}");
    private static final Pattern CURSOR_DELIVERED = Pattern.compile("d\\t[A-Za-z0-9_-]{1,14000}");
    /** Länge der Hashes ausgelieferter Seiten im Cursor. */
    private static final int MARK_BYTES = 6;
    private static final Pattern CURSOR_HEAD = Pattern.compile("[0-9a-f]{24}\\t[01]");

    private final SourceCatalog catalog;
    private final AccessGuard access;
    private final PageTextIndex texts;
    private final Prefetch prefetch;

    public KeywordSearch(SourceCatalog catalog, AccessGuard access) {
        this(catalog, access, null, null);
    }

    /**
     * @param texts    Volltextindex über den Cache oder {@code null} ohne Cache
     * @param prefetch lädt gefundene Seiten in den Cache oder {@code null}; ohne findet die Suche nur, was schon dort
     *                 liegt
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.access = Objects.requireNonNull(access, "access");
        this.texts = texts;
        this.prefetch = prefetch;
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
            int wanted = Math.min(offset + limit + 1, MAX_DEPTH);
            try {
                List<SourceSearchMatch> matches = source.searchPages(query.text(), wanted, query.spaces());
                List<SourceSearchMatch> fresh = matches.size() <= offset ? List.of()
                        : matches.subList(offset, Math.min(matches.size(), offset + limit));
                pending.put(sourceId, new ArrayList<>(fresh));
                more.put(sourceId, matches.size() > offset + fresh.size() && offset + fresh.size() < MAX_DEPTH);
                start.put(sourceId, offset);
                searchable.add(sourceId);
                states.add(new SourceState(sourceId, SourceState.Status.SEARCHED, null));
            } catch (SourceAccessException e) {
                // Ohne Meldung: Texte des Transports können Hosts und Routen nennen.
                LOG.log(System.Logger.Level.INFO, "Suche in Quelle {0} nicht möglich ({1})", sourceId, e.reason());
                states.add(new SourceState(sourceId, SourceState.Status.UNAVAILABLE, e.reason().name()));
                complete = false;
            }
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
        Checked found = check(caller, query, candidates);
        if (!cached) {
            // Ohne Cache: die Treffer der Quellen selbst.
            boolean checkedAll = complete && found.complete();
            return new Results(scope.sourceId(), found.hits(), states, next.isEmpty() ? null
                    : encode(fingerprint(query), !checkedAll, next, unavailable, Set.of()),
                    checkedAll && next.isEmpty(), 0);
        }

        // ADR 0013: Gefundenes zuerst in den Cache, dann sucht der Volltextindex über den Cache.
        int loading = 0;
        if (prefetch != null && !found.hits().isEmpty()) {
            try {
                loading = prefetch.prefetch(caller, found.hits());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Laden der Treffer nicht möglich ({0})", e.getClass().getName());
                loading = found.hits().size();
            }
        }
        Map<PageKey, Hit> known = new HashMap<>();
        found.hits().forEach(hit -> known.put(new PageKey(hit.sourceId(), hit.pageId()), hit));
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
                    fromCache.add(new Candidate(match.sourceId(), hit, true,
                            known.get(new PageKey(match.sourceId(), match.pageId()))));
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
        boolean checkedAll = complete && found.complete() && checked.complete();
        boolean exhausted = next.isEmpty() && !moreInCache && loading == 0;
        String nextCursor = exhausted || delivered.size() >= MAX_DEPTH ? null
                : encode(fingerprint(query), !checkedAll, next, unavailable, delivered);
        return new Results(scope.sourceId(), checked.hits(), states, nextCursor, checkedAll && exhausted, loading);
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
            return Optional.of(new Hit(known.sourceId(), known.pageId(), known.spaceKey(), known.title(),
                    candidate.match().snippet(), known.url(), known.version(), known.modifiedAt(),
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
        return Optional.of(new Hit(candidate.sourceId(), metadata.pageId(), metadata.partitionKey(),
                metadata.title().isEmpty() ? match.title() : metadata.title(), match.snippet(),
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
                                 Set<String> unavailable, Set<String> delivered) {
        StringBuilder text = new StringBuilder(fingerprint).append('\t').append(incomplete ? '1' : '0');
        offsets.forEach((id, offset) -> text.append("\ns\t").append(id).append('\t').append(offset));
        unavailable.forEach(id -> text.append("\nu\t").append(id));
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
        for (String entry : List.of(lines).subList(1, lines.length)) {
            if (CURSOR_OFFSET.matcher(entry).matches()) {
                int tab = entry.lastIndexOf('\t');
                int offset = Integer.parseInt(entry.substring(tab + 1));
                if (offset >= MAX_DEPTH) {
                    throw new InvalidQueryException("cursor ist ungültig");
                }
                offsets.put(entry.substring(2, tab), offset);
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
        return new Position(offsets, lines[0].endsWith("1"), unavailable, delivered);
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

    private record Position(Map<String, Integer> offsets, boolean incomplete, Set<String> unavailable,
                            Set<String> delivered) {
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
         * @param hits geprüfte, für {@code caller} sichtbare Treffer der Quellensuche in Rangfolge
         * @return wie viele dieser Seiten noch nicht im Cache liegen, weil sie noch laden oder nicht geladen werden
         *         konnten
         */
        int prefetch(AccessContext caller, List<Hit> hits);
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
     */
    public record Results(String sourceId, List<Hit> hits, List<SourceState> sources, String nextCursor,
                          boolean complete, int loading) {

        public Results {
            hits = List.copyOf(hits);
            sources = List.copyOf(sources);
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
