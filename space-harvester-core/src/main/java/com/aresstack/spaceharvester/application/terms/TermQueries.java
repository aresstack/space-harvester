package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Globale Begriffsabfragen (#119, ADR 0019): Begriffsliste und Fundstellen über alle berechtigten, aktuell indexierten
 * Seiten, Kontext-Snippets nur auf Abruf aus dem gecachten Text. Spring-, REST- und Swing-frei; REST-API und
 * Desktop-App rufen dieselben Methoden.
 *
 * <p>Rechte wie die Suche (ADR 0014, Punkt 4): Die Quellen bestimmt {@code metadata} auf Quelle oder Space wie
 * {@code SearchScope}; danach zählt jede Seite nur, wenn der Aufrufer ihren Inhalt lesen darf
 * ({@link AccessGuard#canRead}). Bei technischen Clients entscheidet so die Korpusfreigabe, bei Endnutzern mit
 * delegierter Anmeldung die Quelle je Seite. Zähler, Ausschreibungen und Weiterlesepositionen entstehen nur aus
 * sichtbaren Fundstellen; nicht freigegebene Seiten verraten nichts, auch nicht durch Lücken.
 *
 * <p>Aktuell heißt: Die Seite liegt im Cache, ihr Begriffsergebnis stammt von der aktuellen Version des Prozessors
 * {@code terms} und von der Revision, die der Cache für die Seite hält. Ältere Ergebnisse zählen nicht, bis der
 * nächste Lauf sie ersetzt.
 */
public class TermQueries {

    /** Höchstens so viele Einträge je Antwort. */
    public static final int MAX_LIMIT = 200;
    /** Höchstens so viele Zeichen Kontext je Seite eines Snippets. */
    public static final int MAX_SNIPPET_CHARS = 300;
    /** Ab so vielen gelesenen Indexeinträgen endet eine Begriffsliste an der nächsten Begriffsgrenze. */
    static final int MAX_SCAN = 50_000;

    private static final String CURSOR_VERSION = "t1";

    private final SourceCatalog catalog;
    private final TermIndex index;
    private final AccessGuard access;
    private final Function<PageKey, Optional<PageHeader>> headers;
    private final Function<PageKey, Optional<PageSnapshot>> snapshots;
    private final PageTextProjector projector;
    private final String processorVersion;

    /**
     * @param headers          Kopfdaten einer Seite im Cache; leer, wenn sie dort nicht (mehr) liegt
     * @param snapshots        Snapshot einer Seite im Cache, für Snippets
     * @param projector        dieselbe Textprojektion wie die Anreicherungen
     * @param processorVersion aktuelle Version des Prozessors {@code terms}
     */
    public TermQueries(SourceCatalog catalog, TermIndex index, AccessGuard access,
                       Function<PageKey, Optional<PageHeader>> headers,
                       Function<PageKey, Optional<PageSnapshot>> snapshots, PageTextProjector projector,
                       String processorVersion) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.index = Objects.requireNonNull(index, "index");
        this.access = Objects.requireNonNull(access, "access");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.processorVersion = Objects.requireNonNull(processorVersion, "processorVersion");
    }

    /**
     * Suche in der Begriffsliste.
     *
     * @param sourceId nur diese Quelle oder {@code null} für alle berechtigten
     * @param spaceKey nur Seiten dieses Space oder {@code null}
     * @param kind     nur diese Art oder {@code null}
     * @param q        Suchtext oder {@code null}: normalisiert ({@link TermText#normalize}); mit {@code exact} genau
     *                 dieser Suchwert, sonst Suchwert oder eines seiner Wörter beginnt so
     * @param limit    1 bis {@link #MAX_LIMIT}
     * @param cursor   {@code nextCursor} der vorigen Antwort derselben Suche oder {@code null}
     */
    public record TermSearch(String sourceId, String spaceKey, TermKind kind, String q, boolean exact, int limit,
                             String cursor) {
    }

    /**
     * Ein Begriff über alle sichtbaren Seiten.
     *
     * @param term        Wortlaut der ersten sichtbaren Seite
     * @param normalized  Suchwert; mit {@code kind} die Identität für {@link #occurrences}
     * @param kind        Art
     * @param occurrences Zahl der Vorkommen auf sichtbaren Seiten
     * @param pages       Zahl der sichtbaren Seiten
     * @param sources     Zahl der Quellen dieser Seiten
     * @param spellings   verschiedene Schreibweisen der Seiten (je Seite die der ersten Fundstelle), höchstens fünf
     * @param expansions  verschiedene Ausschreibungen direkt neben dem Akronym, in Index-Reihenfolge, höchstens fünf
     */
    public record TermSummary(String term, String normalized, TermKind kind, int occurrences, int pages, int sources,
                              List<String> spellings, List<String> expansions) {

        public TermSummary {
            spellings = List.copyOf(spellings);
            expansions = List.copyOf(expansions);
        }
    }

    /**
     * Ein Stapel der Begriffsliste, aufsteigend nach Suchwert, dann Art.
     *
     * @param nextCursor zum Weiterlesen oder {@code null}, wenn es nichts mehr gibt
     */
    public record TermList(List<TermSummary> terms, String nextCursor) {

        public TermList {
            terms = List.copyOf(terms);
        }
    }

    /**
     * Suche nach Fundstellen eines Begriffs.
     *
     * @param term         Begriff im Wortlaut oder als Suchwert; verglichen wird der Suchwert
     * @param kind         nur diese Art oder {@code null}
     * @param sourceId     nur diese Quelle oder {@code null}
     * @param spaceKey     nur dieser Space oder {@code null}
     * @param limit        1 bis {@link #MAX_LIMIT}
     * @param cursor       {@code nextCursor} der vorigen Antwort oder {@code null}
     * @param snippetChars 0 für keine Snippets, sonst Zeichen Kontext je Seite, höchstens {@link #MAX_SNIPPET_CHARS}
     */
    public record OccurrenceSearch(String term, TermKind kind, String sourceId, String spaceKey, int limit,
                                   String cursor, int snippetChars) {
    }

    /**
     * Eine Fundstelle auf einer sichtbaren Seite.
     *
     * @param term    Wortlaut der ersten Fundstelle des Begriffs auf dieser Seite
     * @param start   Zeichenposition (UTF-16) im projizierten Text mit {@code inputSha256}
     * @param snippet Kontext, wenn verlangt und der Text noch derselbe ist, sonst {@code null}
     */
    public record Occurrence(String sourceId, String spaceKey, String pageId, String title, TermKind kind,
                             String term, String expansion, String section, int start, int end, String revision,
                             String inputSha256, String processorVersion, Snippet snippet) {
    }

    /**
     * Ein Stapel Fundstellen, nach Art, Quelle, Seite und Position.
     *
     * @param normalized der verglichene Suchwert
     * @param nextCursor zum Weiterlesen oder {@code null}
     */
    public record OccurrenceList(String normalized, List<Occurrence> occurrences, String nextCursor) {

        public OccurrenceList {
            occurrences = List.copyOf(occurrences);
        }
    }

    /**
     * Kontext einer Fundstelle; Zeilenumbrüche des Textes sind Leerzeichen.
     *
     * @param before          Text davor, an einer Wortgrenze begonnen, wenn möglich
     * @param match           die Fundstelle selbst
     * @param after           Text danach
     * @param truncatedBefore ob davor noch Text steht
     * @param truncatedAfter  ob danach noch Text steht
     */
    public record Snippet(String before, String match, String after, boolean truncatedBefore,
                          boolean truncatedAfter) {
    }

    /**
     * Die Begriffsliste.
     *
     * @throws UnknownSourceException bei unbekannter {@code sourceId}
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException mit {@code sourceId} ohne
     *                                {@code metadata} auf Quelle oder Space
     * @throws InvalidQueryException  bei ungültigem Limit oder fremdem Cursor
     */
    public TermList terms(AccessContext caller, TermSearch search) {
        Objects.requireNonNull(search, "search");
        requireLimit(search.limit());
        String q = search.q() == null || search.q().isBlank() ? null : TermText.normalize(search.q());
        String fingerprint = fingerprint("terms", search.sourceId(), search.spaceKey(),
                search.kind() == null ? null : search.kind().id(), q, Boolean.toString(search.exact()));
        String after = search.cursor() == null ? null : decode(search.cursor(), fingerprint);
        Set<String> sourceIds = scope(caller, search.sourceId(), search.spaceKey());
        TermIndex.Filter filter = new TermIndex.Filter(sourceIds, search.kind(), search.exact() ? q : null,
                search.exact() ? null : q, processorVersion);
        Visibility visibility = new Visibility(caller, search.spaceKey());

        List<TermSummary> found = new ArrayList<>();
        String from = after == null ? null : TermIndex.afterGroup(after);
        boolean inclusive = false;
        Group group = null;
        int scanned = 0;
        String lastFinished = after;
        while (true) {
            List<TermPosting> batch = index.postings(filter, from, inclusive, TermIndex.MAX_BATCH);
            for (TermPosting posting : batch) {
                if (group != null && !group.key.equals(posting.groupKey())) {
                    if (group.add(found)) {
                        lastFinished = group.key;
                        if (found.size() == search.limit()) {
                            return new TermList(found, encode(fingerprint, lastFinished));
                        }
                    }
                    if (scanned >= MAX_SCAN) {
                        // Weiter an der Begriffsgrenze, damit kein Begriff auf zwei Antworten verteilt wird.
                        return new TermList(found, encode(fingerprint, group.key));
                    }
                    group = null;
                }
                if (group == null) {
                    group = new Group(posting.groupKey(), posting.normalized(), posting.kind());
                }
                scanned++;
                if (visibility.current(posting).isPresent()) {
                    group.count(posting);
                }
            }
            if (batch.size() < TermIndex.MAX_BATCH) {
                break;
            }
            from = batch.get(batch.size() - 1).sortKey();
        }
        if (group != null) {
            group.add(found);
        }
        return new TermList(found, null);
    }

    /**
     * Fundstellen eines Begriffs.
     *
     * @throws UnknownSourceException bei unbekannter {@code sourceId}
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException mit {@code sourceId} ohne
     *                                {@code metadata} auf Quelle oder Space
     * @throws InvalidQueryException  ohne Begriff, bei ungültigem Limit, zu langem Kontext oder fremdem Cursor
     */
    public OccurrenceList occurrences(AccessContext caller, OccurrenceSearch search) {
        Objects.requireNonNull(search, "search");
        requireLimit(search.limit());
        if (search.term() == null || search.term().isBlank()) {
            throw new InvalidQueryException("term fehlt");
        }
        if (search.snippetChars() < 0 || search.snippetChars() > MAX_SNIPPET_CHARS) {
            throw new InvalidQueryException("snippetChars muss zwischen 0 und " + MAX_SNIPPET_CHARS + " liegen");
        }
        String normalized = TermText.normalize(search.term());
        String fingerprint = fingerprint("occurrences", normalized, search.kind() == null ? null : search.kind().id(),
                search.sourceId(), search.spaceKey());
        String fromKey = null;
        int afterStart = -1;
        if (search.cursor() != null) {
            String position = decode(search.cursor(), fingerprint);
            int split = position.lastIndexOf('\u0000');
            if (split < 0) {
                throw new InvalidQueryException("cursor ist beschädigt");
            }
            fromKey = position.substring(0, split);
            try {
                afterStart = Integer.parseInt(position.substring(split + 1));
            } catch (NumberFormatException e) {
                throw new InvalidQueryException("cursor ist beschädigt");
            }
        }
        Set<String> sourceIds = scope(caller, search.sourceId(), search.spaceKey());
        TermIndex.Filter filter = new TermIndex.Filter(sourceIds, search.kind(), normalized, null, processorVersion);
        Visibility visibility = new Visibility(caller, search.spaceKey());
        Map<PageKey, Optional<PageText>> texts = new HashMap<>();

        List<Occurrence> found = new ArrayList<>();
        String from = fromKey;
        boolean inclusive = fromKey != null;
        while (true) {
            List<TermPosting> batch = index.postings(filter, from, inclusive, TermIndex.MAX_BATCH);
            for (TermPosting posting : batch) {
                Optional<PageHeader> header = visibility.current(posting);
                if (header.isEmpty()) {
                    continue;
                }
                boolean resumed = posting.sortKey().equals(fromKey);
                for (TermOccurrence occurrence : posting.occurrences()) {
                    if (resumed && occurrence.start() <= afterStart) {
                        continue;
                    }
                    if (found.size() == search.limit()) {
                        Occurrence last = found.get(found.size() - 1);
                        return new OccurrenceList(normalized, found, encode(fingerprint,
                                TermIndex.sortKey(normalized, last.kind(), new PageKey(last.sourceId(),
                                        last.pageId())), Integer.toString(last.start())));
                    }
                    Snippet snippet = null;
                    if (search.snippetChars() > 0) {
                        snippet = texts.computeIfAbsent(posting.page(), this::text)
                                .filter(text -> text.inputSha256().equals(posting.inputSha256()))
                                .map(text -> snippet(text.text(), occurrence, search.snippetChars()))
                                .orElse(null);
                    }
                    found.add(new Occurrence(posting.page().sourceId(), header.get().metadata().partitionKey(),
                            posting.page().pageId(), header.get().metadata().title(), posting.kind(),
                            posting.term(), posting.expansion(), posting.section(), occurrence.start(),
                            occurrence.end(), posting.revision(), posting.inputSha256(), posting.processorVersion(),
                            snippet));
                }
            }
            if (batch.size() < TermIndex.MAX_BATCH) {
                break;
            }
            from = batch.get(batch.size() - 1).sortKey();
            inclusive = false;
        }
        return new OccurrenceList(normalized, found, null);
    }

    /**
     * Kontext einer einzelnen Fundstelle, etwa für eine Fundstelle aus {@link #occurrences} ohne Snippet.
     *
     * @param inputSha256 Eingabe-Hash der Fundstelle; passt der gecachte Text nicht mehr, gibt es keinen Kontext
     * @return leer, wenn die Seite nicht im Cache liegt, nicht sichtbar ist, sich geändert hat oder die Position nicht
     *         im Text liegt
     * @throws InvalidQueryException bei zu langem Kontext
     */
    public Optional<Snippet> snippet(AccessContext caller, String sourceId, String pageId, String inputSha256,
                                     int start, int end, int chars) {
        if (chars < 1 || chars > MAX_SNIPPET_CHARS) {
            throw new InvalidQueryException("chars muss zwischen 1 und " + MAX_SNIPPET_CHARS + " liegen");
        }
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        PageKey key = new PageKey(sourceId, pageId);
        Optional<PageHeader> header = headers.apply(key);
        if (header.isEmpty() || !access.canRead(caller, sourceId, header.get().metadata().partitionKey(), pageId)) {
            return Optional.empty();
        }
        return text(key).filter(text -> text.inputSha256().equals(inputSha256) && end <= text.text().length())
                .map(text -> snippet(text.text(), new TermOccurrence(start, end), chars));
    }

    /** Kontext um eine Fundstelle, ohne Ersatzzeichen zu teilen. */
    static Snippet snippet(String text, TermOccurrence occurrence, int chars) {
        int end = Math.min(occurrence.end(), text.length());
        int start = Math.min(occurrence.start(), end);
        int from = Math.max(0, start - chars);
        int to = Math.min(text.length(), end + chars);
        if (from > 0 && Character.isLowSurrogate(text.charAt(from))) {
            from++;
        }
        if (to < text.length() && Character.isLowSurrogate(text.charAt(to))) {
            to--;
        }
        // Am Wortanfang beginnen, wenn das Fenster mitten in einem Wort beginnt und davor ein Leerraum folgt.
        if (from > 0 && !Character.isWhitespace(text.charAt(from - 1))) {
            int space = from;
            while (space < start && !Character.isWhitespace(text.charAt(space))) {
                space++;
            }
            if (space < start) {
                from = space + 1;
            }
        }
        return new Snippet(flat(text.substring(from, start)), flat(text.substring(start, end)),
                flat(text.substring(end, to)), from > 0, to < text.length());
    }

    private static String flat(String text) {
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private Optional<PageText> text(PageKey key) {
        try {
            return snapshots.apply(key).flatMap(projector::project);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Die Quellen, wie {@code SearchScope}: mit {@code sourceId} geprüft, sonst still beschnitten. */
    private Set<String> scope(AccessContext caller, String sourceId, String spaceKey) {
        if (sourceId != null) {
            if (catalog.find(sourceId).isEmpty()) {
                throw new UnknownSourceException(sourceId);
            }
            access.require(caller, spaceKey == null ? ResourceScope.source(sourceId)
                    : ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA);
            return Set.of(sourceId);
        }
        Set<String> visible = new TreeSet<>();
        for (HarvestSource source : catalog.all()) {
            ResourceScope scope = spaceKey == null ? ResourceScope.source(source.id())
                    : ResourceScope.space(source.id(), spaceKey);
            if (access.permits(caller, scope, AccessKind.METADATA)) {
                visible.add(source.id());
            }
        }
        return visible;
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
    }

    private static String fingerprint(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            joined.append(part == null ? "\u0001" : part).append('\u0000');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    private static String encode(String fingerprint, String... position) {
        StringBuilder value = new StringBuilder(CURSOR_VERSION).append('\u0000').append(fingerprint);
        for (String part : position) {
            value.append('\u0000').append(part);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString()
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Die Position hinter Version und Fingerabdruck; Schlüssel enthalten selbst {@code U+0000}. */
    private static String decode(String cursor, String fingerprint) {
        String value;
        try {
            value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException("cursor ist beschädigt");
        }
        String[] parts = value.split("\u0000", -1);
        if (parts.length < 3 || !CURSOR_VERSION.equals(parts[0])) {
            throw new InvalidQueryException("cursor ist beschädigt");
        }
        if (!fingerprint.equals(parts[1])) {
            throw new InvalidQueryException("cursor gehört zu einer anderen Abfrage");
        }
        return String.join("\u0000", List.of(parts).subList(2, parts.length));
    }

    /** Sichtbarkeit je Seite für eine Abfrage, je Seite einmal geprüft. */
    private final class Visibility {

        private final AccessContext caller;
        private final String spaceKey;
        private final Map<PageKey, Optional<PageHeader>> checked = new HashMap<>();

        Visibility(AccessContext caller, String spaceKey) {
            this.caller = caller;
            this.spaceKey = spaceKey;
        }

        /** Die Kopfdaten, wenn die Seite im Cache liegt, sichtbar ist und der Eintrag zu ihrer Revision passt. */
        Optional<PageHeader> current(TermPosting posting) {
            Optional<PageHeader> header = checked.computeIfAbsent(posting.page(), key -> headers.apply(key)
                    .filter(found -> spaceKey == null || spaceKey.equals(found.metadata().partitionKey()))
                    .filter(found -> access.canRead(caller, key.sourceId(), found.metadata().partitionKey(),
                            key.pageId())));
            return header.filter(found -> posting.revision() == null || found.revision() == null
                    || posting.revision().equals(found.revision()));
        }
    }

    /** Ein Begriff während der Aggregation über seine Seiten. */
    private static final class Group {

        final String key;
        final String normalized;
        final TermKind kind;
        final Set<String> sources = new TreeSet<>();
        final Set<String> spellings = new LinkedHashSet<>();
        final Set<String> expansions = new LinkedHashSet<>();
        String term;
        int occurrences;
        int pages;

        Group(String key, String normalized, TermKind kind) {
            this.key = key;
            this.normalized = normalized;
            this.kind = kind;
        }

        void count(TermPosting posting) {
            if (term == null) {
                term = posting.term();
            }
            occurrences += posting.count();
            pages++;
            sources.add(posting.page().sourceId());
            if (spellings.size() < 5) {
                spellings.add(posting.term());
            }
            if (posting.expansion() != null && expansions.size() < 5) {
                expansions.add(posting.expansion());
            }
        }

        /** Hängt den Begriff an, wenn er auf einer sichtbaren Seite vorkommt. */
        boolean add(List<TermSummary> found) {
            if (pages == 0) {
                return false;
            }
            found.add(new TermSummary(term, normalized, kind, occurrences, pages, sources.size(),
                    List.copyOf(spellings), List.copyOf(expansions)));
            return true;
        }
    }
}
