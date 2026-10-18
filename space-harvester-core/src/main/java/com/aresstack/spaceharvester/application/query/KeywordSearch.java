package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * Die globale Schlagwortsuche: jede Quelle des Umfangs sucht selbst über Titel und Inhalt (Confluence: CQL
 * {@code siteSearch} über {@code /rest/api/search}, wie die Websuche), die Treffer werden zusammengeführt. Der Bestand
 * ist dafür weder Voraussetzung noch Ergebnis: die Suche lädt keine Seiteninhalte und legt nichts ab; den Inhalt
 * beschafft erst das Öffnen einer Seite ({@link PageQueries#page}). REST und Desktop-App nutzen genau diesen
 * Anwendungsfall.
 *
 * <p>Jeder Treffer trägt Titel, Textauszug der Quelle, Adresse, Version, Quelle und Space. Bevor er erscheint, liest
 * die Suche die Kopfdaten der Seite live bei der Quelle und prüft an ihnen die Freigabe {@link AccessKind#METADATA}
 * für genau diese Seite; bei delegierten Benutzern über die Quelle mit ihrer Identität. So ist der Space bekannt,
 * gilt der Space-Filter auch global, und nicht freigegebene Seiten erscheinen nirgends, auch nicht als Titel oder
 * Auszug. Was nicht mehr existiert oder nicht freigegeben ist, fällt ohne Spur weg.
 *
 * <p>Ein Stapel nimmt reihum Treffer aus jeder Quelle in deren Rangfolge, bis {@code limit} erreicht ist; der Cursor
 * merkt sich je Quelle, wie weit sie gelesen ist („Weitere laden“). Eine Quelle, die nicht antwortet, verwirft die
 * anderen nicht: sie steht mit {@link SourceState.Status#UNAVAILABLE} in {@link Results#sources()}, und das Ergebnis
 * ist nicht {@code complete}, auch in allen Folgestapeln desselben Cursors. Quellen ohne eigene Suche stehen dort als {@link SourceState.Status#NOT_SUPPORTED},
 * statt still „keine Treffer“ zu melden. Je Quelle reicht die Suche höchstens {@value #MAX_DEPTH} Treffer tief.
 *
 * <p>Nur Schlagworte, keine semantische Suche ({@code SemanticSearch} beschreibt die geplante Erweiterung).
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
    private static final String CURSOR_PREFIX = "k1.";
    private static final Pattern CURSOR_ENTRY = Pattern.compile("[^\\t\\n]{1,255}\\t[0-9]{1,4}");
    private static final Pattern CURSOR_HEAD = Pattern.compile("[0-9a-f]{24}\\t[01]");

    private final SourceCatalog catalog;
    private final AccessGuard access;

    public KeywordSearch(SourceCatalog catalog, AccessGuard access) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.access = Objects.requireNonNull(access, "access");
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

        List<SourceState> states = new ArrayList<>();
        Map<String, List<SourceSearchMatch>> pending = new LinkedHashMap<>();
        Map<String, Boolean> more = new LinkedHashMap<>();
        Map<String, Integer> start = new LinkedHashMap<>();
        // Was ein früherer Stapel nicht prüfen konnte, bleibt bis zum Ende offen.
        boolean complete = position == null || !position.incomplete();
        for (HarvestSource source : catalog.all()) {
            String sourceId = source.id();
            if (!scope.sourceIds().contains(sourceId) || (offsets != null && !offsets.containsKey(sourceId))) {
                continue;
            }
            if (!source.capabilities().contains(SourceCapability.LIVE_SEARCH)) {
                states.add(new SourceState(sourceId, SourceState.Status.NOT_SUPPORTED, null));
                continue;
            }
            int offset = offsets == null ? 0 : offsets.get(sourceId);
            int wanted = Math.min(offset + limit + 1, MAX_DEPTH);
            try {
                List<SourceSearchMatch> matches = source.searchPages(query.text(), wanted);
                List<SourceSearchMatch> fresh = matches.size() <= offset ? List.of()
                        : matches.subList(offset, Math.min(matches.size(), offset + limit));
                pending.put(sourceId, new ArrayList<>(fresh));
                more.put(sourceId, matches.size() > offset + fresh.size() && offset + fresh.size() < MAX_DEPTH);
                start.put(sourceId, offset);
                states.add(new SourceState(sourceId, SourceState.Status.SEARCHED, null));
            } catch (SourceAccessException e) {
                // Ohne Meldung: Texte des Transports können Hosts und Routen nennen.
                LOG.log(System.Logger.Level.INFO, "Suche in Quelle {0} nicht möglich ({1})", sourceId, e.reason());
                states.add(new SourceState(sourceId, SourceState.Status.UNAVAILABLE, e.reason().name()));
                complete = false;
            }
        }

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
                    candidates.add(new Candidate(entry.getKey(), entry.getValue().get(index)));
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
        Checked checked = check(caller, query, candidates);
        boolean checkedAll = complete && checked.complete();
        return new Results(scope.sourceId(), checked.hits(), states,
                next.isEmpty() ? null : encode(fingerprint(query), !checkedAll, next), checkedAll && next.isEmpty());
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
        if (!query.spaceKeys().isEmpty() && !query.spaceKeys().contains(metadata.partitionKey())) {
            return Optional.empty();
        }
        if (!access.permits(caller, ResourceScope.page(candidate.sourceId(), metadata.partitionKey(),
                metadata.pageId()), AccessKind.METADATA)) {
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

    /** Der Cursor gilt nur für dieselbe Anfrage; er trägt mit, ob ein früherer Stapel unvollständig war. */
    private static String encode(String fingerprint, boolean incomplete, Map<String, Integer> offsets) {
        StringBuilder text = new StringBuilder(fingerprint).append('\t').append(incomplete ? '1' : '0');
        offsets.forEach((id, offset) -> text.append('\n').append(id).append('\t').append(offset));
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
        if (lines.length < 2 || !CURSOR_HEAD.matcher(lines[0]).matches()) {
            throw new InvalidQueryException("cursor ist ungültig");
        }
        if (!lines[0].startsWith(fingerprint)) {
            throw new InvalidQueryException("cursor gehört zu einer anderen Suchanfrage");
        }
        Map<String, Integer> offsets = new LinkedHashMap<>();
        for (String entry : List.of(lines).subList(1, lines.length)) {
            if (!CURSOR_ENTRY.matcher(entry).matches()) {
                throw new InvalidQueryException("cursor ist ungültig");
            }
            int tab = entry.indexOf('\t');
            int offset = Integer.parseInt(entry.substring(tab + 1));
            if (offset >= MAX_DEPTH) {
                throw new InvalidQueryException("cursor ist ungültig");
            }
            offsets.put(entry.substring(0, tab), offset);
        }
        return new Position(offsets, lines[0].endsWith("1"));
    }

    private static String fingerprint(Query query) {
        String canonical = String.join("\u0000", query.text(), String.valueOf(query.sourceId()),
                String.join("\u0001", new TreeSet<>(query.sourceIds())),
                String.join("\u0001", new TreeSet<>(query.spaceKeys())));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    private record Position(Map<String, Integer> offsets, boolean incomplete) {
    }

    private record Candidate(String sourceId, SourceSearchMatch match) {
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
     */
    public record Query(String text, String sourceId, Set<String> sourceIds, Set<String> spaceKeys) {

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
        }

        /** Alle freigegebenen Quellen. */
        public static Query of(String text) {
            return new Query(text, null, Set.of(), Set.of());
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
     * @param hits       sichtbare Treffer, reihum aus den Quellen; kann kürzer als das Limit sein, auch wenn noch
     *                   Treffer folgen
     * @param sources    je Quelle des Umfangs, ob sie gesucht hat; im ersten Stapel alle, danach nur die noch offenen
     * @param nextCursor für „Weitere laden“ mit derselben Anfrage, {@code null}, wenn keine Quelle mehr Treffer hat
     * @param complete   keine Quelle hat mehr Treffer, und jede Quelle und jeder Treffer dieses und aller früheren
 *                   Stapel ließ sich prüfen
     */
    public record Results(String sourceId, List<Hit> hits, List<SourceState> sources, String nextCursor,
                          boolean complete) {

        public Results {
            hits = List.copyOf(hits);
            sources = List.copyOf(sources);
        }
    }

    /**
     * Ein Treffer; Kopfdaten live von der Quelle, Auszug aus ihrer Suche.
     *
     * @param snippet Textauszug der Quelle als Klartext, leer, wenn sie keinen liefert
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
            /** Die Quelle hat gesucht. */
            SEARCHED,
            /** Die Quelle kann nicht selbst suchen; ihre Seiten fehlen in der Schlagwortsuche. */
            NOT_SUPPORTED,
            /** Die Quelle hat nicht oder fehlerhaft geantwortet. */
            UNAVAILABLE
        }
    }
}
