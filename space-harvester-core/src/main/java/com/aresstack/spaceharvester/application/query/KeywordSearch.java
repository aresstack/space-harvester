package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.acquisition.FindingCatalog;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.searchrun.SearchRun;
import com.aresstack.spaceharvester.application.searchrun.SearchRunId;
import com.aresstack.spaceharvester.application.searchrun.SearchRunLog;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
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
import java.time.Duration;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
 *       nach Titel und Auszug; das ändert nur die Ladereihenfolge. Wie viele jetzt laden, wählt die
 *       {@link CandidateSelectionStrategy}. Was beim Antworten noch lädt, zählt {@link Results#loading()}.</li>
 *   <li><b>Suchen:</b> Der {@link PageTextIndex} (BM25 über Titel und Text) sucht im Cache, über die Seiten aller
 *       erreichbaren Quellen des Umfangs, auch solcher ohne eigene Suche (lokale Dateien, #180). Nur von hier kommen
 *       Treffer; Ergebnislisten werden nicht zusammengeführt. Jeder Treffer braucht {@link AccessKind#METADATA} und
 *       {@link AccessKind#CONTENT}, weil Fundstelle und Auszug aus dem Inhalt stammen, und wird live geprüft, wenn er
 *       nicht eben erst gefunden wurde. Liegt die Fundstelle im Text eines Anhangs (#183), ist der Treffer die Seite
 *       mit {@link Hit#matchedIn()} {@link MatchedIn#ATTACHMENT} und dem Anhang; geprüft wird wie jede Seite, ein
 *       Anhang wird nie ohne seine lesbare Seite sichtbar.</li>
 * </ol>
 *
 * <p><b>Lokal zuerst</b> (Angelo 27.11.2026): Die Online-Suche der Quellen ist nur der Rückfall für Bereiche, die
 * nicht vollständig und aktuell lokal vorliegen ({@link LocalCoverage}), und dient dort nur dem Einsammeln, auch mit
 * Varianten der Anfrage. Abgedeckte Spaces fragt sie nicht, ganz abgedeckte Quellen gar nicht
 * ({@link SourceState.Status#LOCAL}); was schon im lokalen Index liegt und dort nicht trifft, erscheint auch nicht als
 * vorläufiger Treffer.
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
 * <p>Die Funde jedes Stapels gehen außerdem an den {@link FindingCatalog} (ADR 0016): Was noch nicht im Katalog steht,
 * kommt dort im Hintergrund als Kopfdaten hinein, damit die Hintergrundfüllung es lädt; nur für technische Aufrufer,
 * nie für Endnutzer.
 *
 * <p>Nur Schlagworte, nie Embeddings; die semantische Suche (nur mit Haken) ist
 * {@link com.aresstack.spaceharvester.application.ranking.RelevanceSearch}.
 *
 * <p>Jeder Stapel ist ein Lauf im Suchlauf-Protokoll ({@link SearchRunLog}, #182): Suchtext, Quellen mit Ausgang und
 * die Trefferzahlen je Stufe ({@link Results#searchRun()}). Gezählt wird nur Geprüftes; für Endnutzer mit delegierter
 * Anmeldung fehlen deshalb die Stufen der ungeprüften Funde. Ein Fehler des Protokolls lässt die Suche nie scheitern.
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
     * Standard der Obergrenze, wie viele Funde eines Stapels in den Cache laden: keine ({@value #MAX_LIMIT}). Was davon
     * jetzt lädt, entscheidet die {@link CandidateSelectionStrategy}.
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
    /** Was der Benutzer ausgeschlossen hat, fehlt in jeder Trefferliste (Angelo 26.11.2026). */
    private volatile com.aresstack.spaceharvester.application.sync.Exclusions exclusions =
            com.aresstack.spaceharvester.application.sync.Exclusions.NONE;
    private final PageTextIndex texts;
    private final Prefetch prefetch;
    private final java.util.function.Predicate<String> prefetching;
    private final java.util.function.Predicate<String> cataloguing;
    private final BeforeSearch before;
    private final int maxQueries;
    private final ExcerptPriority excerpts;
    private final CandidateSelectionStrategy selection;
    private final SearchRunLog runs;
    private final FindingCatalog findings;
    /**
     * Je Suche (Kennung des Aufrufers) die Seiten, deren Funde schon zum Katalogisieren angeboten wurden (Fundstelle
     * 23, Abnahme PR 4): Neusortieren derselben Suche bietet sie nicht noch einmal an. Die jüngsten Suchen zuerst.
     */
    private final Map<String, Set<PageKey>> offeredIn = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Set<PageKey>> eldest) {
                    return size() > REMEMBERED_SEARCHES;
                }
            });
    /** So viele Suchen merkt sich {@link #offeredIn}. */
    static final int REMEMBERED_SEARCHES = 64;
    private final SearchExpansion expansion;
    /** Suchmethode „Synonyme der Fachbegriffe“ (K5a): nur, wenn die Anfrage sie einschaltet. */
    private final SearchExpansion glossarySynonyms;

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
     *              besten nach {@code excerpts}, ohne Bewertung die ersten in der Reihenfolge der Quellen
     *              ({@link CandidateSelectionStrategy#topN}); die übrigen stehen nur als vorläufige Treffer in
     *              {@link Results#found()} und laden erst, wenn jemand sie öffnet
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch,
                         int maxQueries, ExcerptPriority excerpts, int loads) {
        this(catalog, access, texts, prefetch, maxQueries, excerpts, checkedLoads(loads));
    }

    /**
     * @param runs Suchlauf-Protokoll (#182) oder {@link SearchRunLog#NONE}
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch,
                         int maxQueries, ExcerptPriority excerpts, int loads, SearchRunLog runs) {
        this(catalog, access, texts, prefetch, maxQueries, excerpts, checkedLoads(loads), runs);
    }

    /**
     * @param selection welche Funde eines Stapels jetzt in den Cache laden (ADR 0016, Punkt 4); die übrigen stehen nur
     *                  als vorläufige Treffer in {@link Results#found()}
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch,
                         int maxQueries, ExcerptPriority excerpts, CandidateSelectionStrategy selection) {
        this(catalog, access, texts, prefetch, maxQueries, excerpts, selection, SearchRunLog.NONE);
    }

    /**
     * @param selection welche Funde eines Stapels jetzt in den Cache laden (ADR 0016, Punkt 4)
     * @param runs      Suchlauf-Protokoll (#182) oder {@link SearchRunLog#NONE}
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch,
                         int maxQueries, ExcerptPriority excerpts, CandidateSelectionStrategy selection,
                         SearchRunLog runs) {
        this(catalog, access, texts, prefetch, maxQueries, excerpts, selection, runs, FindingCatalog.NONE);
    }

    /**
     * @param runs     Suchlauf-Protokoll (#182) oder {@link SearchRunLog#NONE}
     * @param findings nimmt die Funde jedes Stapels zum Katalogisieren als Kopfdaten (ADR 0016), ohne die Suche
     *                 aufzuhalten; {@link FindingCatalog#NONE} katalogisiert nichts
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch,
                         int maxQueries, ExcerptPriority excerpts, CandidateSelectionStrategy selection,
                         SearchRunLog runs, FindingCatalog findings) {
        this(catalog, access, texts, prefetch, maxQueries, excerpts, selection, runs, findings, () -> true);
    }

    /**
     * @param prefetching ob ein Stapel jetzt lädt (ADR 0015: {@code search.prefetch-hits} zur Laufzeit); ohne
     *                    {@code prefetch} lädt nie einer. Stand und Abbruch schon ladender Stapel bleiben erreichbar.
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch,
                         int maxQueries, ExcerptPriority excerpts, CandidateSelectionStrategy selection,
                         SearchRunLog runs, FindingCatalog findings, BooleanSupplier prefetching) {
        this(catalog, access, texts, prefetch, maxQueries, excerpts, selection, runs, findings, prefetching,
                BeforeSearch.NONE);
    }

    /**
     * @param before erfährt zu Beginn jeder Suche (erster Stapel, nicht bei Folgestapeln über den Cursor) die Quellen,
     *               die der Aufrufer darin lesen darf ({@code sync.before-search}, ADR 0015); hält die Suche nicht auf
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch,
                         int maxQueries, ExcerptPriority excerpts, CandidateSelectionStrategy selection,
                         SearchRunLog runs, FindingCatalog findings, BooleanSupplier prefetching,
                         BeforeSearch before) {
        this(catalog, access, texts, prefetch, maxQueries, excerpts, selection, runs, findings, prefetching, before,
                SearchExpansion.NONE);
    }

    /**
     * @param expansion bestätigte Begriffsbeziehungen als zusätzliche Anfragen je Quelle (#336, Teil 4) oder
     *                  {@link SearchExpansion#NONE}
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch,
                         int maxQueries, ExcerptPriority excerpts, CandidateSelectionStrategy selection,
                         SearchRunLog runs, FindingCatalog findings, BooleanSupplier prefetching,
                         BeforeSearch before, SearchExpansion expansion) {
        this(catalog, access, texts, prefetch, maxQueries, excerpts, selection, runs, findings, prefetching, before,
                expansion, SearchExpansion.NONE);
    }

    /**
     * @param glossarySynonyms zusätzliche Anfragen der Suchmethode „Synonyme der Fachbegriffe“ (K5a): vorgeschlagene
     *                         Synonyme bestätigter Fachbegriffe; wirkt nur mit {@link Query#glossarySynonyms()}
     */
    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch,
                         int maxQueries, ExcerptPriority excerpts, CandidateSelectionStrategy selection,
                         SearchRunLog runs, FindingCatalog findings, BooleanSupplier prefetching,
                         BeforeSearch before, SearchExpansion expansion, SearchExpansion glossarySynonyms) {
        this(catalog, access, texts, prefetch, maxQueries, excerpts, selection, runs, findings,
                sourceId -> prefetching.getAsBoolean(), before, expansion, glossarySynonyms, sourceId -> true);
    }

    public KeywordSearch(SourceCatalog catalog, AccessGuard access, PageTextIndex texts, Prefetch prefetch,
                         int maxQueries, ExcerptPriority excerpts, CandidateSelectionStrategy selection,
                         SearchRunLog runs, FindingCatalog findings, java.util.function.Predicate<String> prefetching,
                         BeforeSearch before, SearchExpansion expansion, SearchExpansion glossarySynonyms,
                         java.util.function.Predicate<String> cataloguing) {
        this.cataloguing = Objects.requireNonNull(cataloguing, "cataloguing");
        this.expansion = Objects.requireNonNull(expansion, "expansion");
        this.glossarySynonyms = Objects.requireNonNull(glossarySynonyms, "glossarySynonyms");
        this.before = Objects.requireNonNull(before, "before");
        this.prefetching = Objects.requireNonNull(prefetching, "prefetching");
        this.findings = Objects.requireNonNull(findings, "findings");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.runs = Objects.requireNonNull(runs, "runs");
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

    private static CandidateSelectionStrategy checkedLoads(int loads) {
        if (loads < 1 || loads > MAX_LIMIT) {
            throw new IllegalArgumentException("loads muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        return CandidateSelectionStrategy.topN(loads);
    }

    /**
     * Die Anfragen an die Suche der Quellen. Ein knapper Suchausdruck (höchstens {@value SearchTerms#MAX_QUERY_WORDS}
     * Wörter) geht unverändert hinaus. Aus einer längeren Frage werden wie im Enterprise AI Client zuerst ihre stärksten
     * wörtlichen Begriffe ({@link SearchTerms#literalQuery}), dann ihre Schlüsselwörter
     * ({@link SearchTerms#keywordQueries}, ohne Wörter einer Bitte wie „wird“), ohne Dopplungen (Groß- und
     * Kleinschreibung zählt nicht), höchstens {@code maxQueries}.
     */
    List<String> nativeQueries(String text) {
        if (maxQueries == 1) {
            return List.of(text);
        }
        Map<String, String> queries = new LinkedHashMap<>();
        List<String> derived = new ArrayList<>();
        if (SearchTerms.isConcise(text)) {
            derived.add(text);
        } else {
            derived.add(SearchTerms.literalQuery(text));
            derived.addAll(SearchTerms.keywordQueries(SearchTerms.withoutRequestWords(text), maxQueries));
        }
        // Lokal zuerst (Angelo 27.11.2026): Die Online-Suche sammelt nur Seiten ein; dafür fragt sie auch mit den
        // Varianten ohne Modell (schwächste Begriffe weglassen, dann die stärksten einzeln), damit auch Seiten
        // ankommen, die nicht alle Begriffe enthalten. Getroffen wird danach nur im lokalen Index.
        derived.addAll(new com.aresstack.spaceharvester.application.query.progressive.RuleBasedExpansion()
                .expand(text, maxQueries));
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
     * Meldet einen Empfänger für den Ladestand eines Stapels an (ADR 0027), statt {@link #progress} in Abständen zu
     * lesen: nach jeder geladenen oder gescheiterten Seite und nach einem Abbruch der jüngste {@link Progress},
     * gebündelt, zuletzt mit {@link Progress#done()}. Erst anmelden, dann {@link #progress} lesen; ohne Vorabladen oder
     * ohne Stapel {@link Subscription#NONE}.
     */
    public Subscription subscribe(String progressId, Consumer<Progress> listener) {
        Objects.requireNonNull(listener, "listener");
        return prefetch == null || progressId == null ? Subscription.NONE : prefetch.subscribe(progressId, listener);
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
     * Die zusätzlichen Anfragen einer Quelle: die bestätigten Beziehungen (#336) und, nur mit
     * {@link Query#glossarySynonyms()}, die Synonyme bestätigter Fachbegriffe samt Vorschlägen (K5a), höchstens
     * {@link SearchExpansion#MAX_EXPANSIONS} dazu. Ohne die Methode bleibt alles wie zuvor.
     */
    private List<SearchExpansion.Expansion> expansions(AccessContext caller, Query query, String sourceId,
                                                       String spaceKey) {
        List<SearchExpansion.Expansion> confirmed = expansion.expansions(caller, query.text(), sourceId, spaceKey);
        if (!query.glossarySynonyms()) {
            return confirmed;
        }
        List<SearchExpansion.Expansion> proposed = proposals(caller, query, sourceId, spaceKey, confirmed);
        List<SearchExpansion.Expansion> all = new ArrayList<>(confirmed);
        all.addAll(proposed.subList(0, Math.min(proposed.size(), SearchExpansion.MAX_EXPANSIONS)));
        return List.copyOf(all);
    }

    /**
     * Die vorgeschlagenen Synonyme einer Quelle ohne die Anfragen, die die bestätigten Beziehungen schon stellen, noch
     * ungekappt: Erst nach dem Entfernen der Dubletten gilt {@link SearchExpansion#MAX_EXPANSIONS} (K5a).
     */
    private List<SearchExpansion.Expansion> proposals(AccessContext caller, Query query, String sourceId,
                                                      String spaceKey, List<SearchExpansion.Expansion> confirmed) {
        Map<String, SearchExpansion.Expansion> all = new LinkedHashMap<>();
        confirmed.forEach(extra -> all.put(extra.query().toLowerCase(java.util.Locale.ROOT), extra));
        List<SearchExpansion.Expansion> proposed = new ArrayList<>();
        for (SearchExpansion.Expansion extra : glossarySynonyms.allExpansions(caller, query.text(), sourceId,
                spaceKey)) {
            if (all.putIfAbsent(extra.query().toLowerCase(java.util.Locale.ROOT), extra) == null) {
                proposed.add(extra);
            }
        }
        return proposed;
    }

    /**
     * Wie viele Synonymvorschläge die Suche mit {@link Query#glossarySynonyms()} ohne Kürzung je Quelle höchstens
     * stellen würde (für den Hinweis auf {@link SearchExpansion#MAX_EXPANSIONS}): derselbe Umfang je Quelle und dieselbe
     * Entdoppelung gegen die bestätigten Beziehungen wie {@link #search}. Ohne die Methode 0. Wirft, wenn das Glossar
     * nicht lesbar ist.
     */
    public int glossarySynonymsWanted(AccessContext caller, Query query) {
        Objects.requireNonNull(query, "query");
        if (!query.glossarySynonyms() || query.text() == null || query.text().isBlank()) {
            return 0;
        }
        SearchScope scope = SearchScope.authorize(access, catalog, caller, query.scope());
        String spaceKey = query.singleSpace();
        int wanted = 0;
        for (String sourceId : scope.sourceIds()) {
            if (!query.reaches(sourceId)) {
                continue;
            }
            List<SearchExpansion.Expansion> confirmed = expansion.expansions(caller, query.text(), sourceId, spaceKey);
            wanted = Math.max(wanted, proposals(caller, query, sourceId, spaceKey, confirmed).size());
        }
        return wanted;
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
        return search(caller, query, cursor, limit, null);
    }

    /**
     * Wie {@link #search(AccessContext, Query, String, int)} als Teil eines übergeordneten Laufs im Suchlauf-Protokoll,
     * etwa einer Suchanfrage der progressiven Suche.
     *
     * @param partOf der übergeordnete Lauf oder {@code null}
     */
    public Results search(AccessContext caller, Query query, String cursor, int limit, SearchRunId partOf) {
        return search(caller, query, cursor, limit, partOf, null);
    }

    /**
     * Wie {@link #search(AccessContext, Query, String, int, SearchRunId)}; zusätzlich meldet {@code found} die Funde
     * jeder Quelle, sobald sie geantwortet hat, noch während die übrigen suchen. So steht ein erster Teil der Treffer
     * schon da, bevor der Stapel fertig ist; die Meldungen kommen aus den Suchthreads, nie aus dem des Aufrufers.
     *
     * @param found Beobachter der laufenden Suche oder {@code null}
     */
    public Results search(AccessContext caller, Query query, String cursor, int limit, SearchRunId partOf,
                          Found found) {
        return search(caller, query, cursor, limit, partOf, found, null);
    }

    /**
     * Wie {@link #search(AccessContext, Query, String, int, SearchRunId, Found)} als Teil einer Suche des Aufrufers,
     * etwa der Trefferliste, die denselben Stapel bei jedem Ladefortschritt neu einsortiert (Fundstelle 23, Abnahme PR
     * 4): Funde, die diese Suche schon zum Katalogisieren angeboten hat, bietet sie nicht noch einmal an; Neusortieren
     * allein erzeugt so keinen Auftrag „Funde katalogisieren“.
     *
     * @param searchId Kennung der Suche, gleich für ihre Stapel und ihr Neusortieren, oder {@code null}
     */
    public Results search(AccessContext caller, Query query, String cursor, int limit, SearchRunId partOf,
                          Found found, String searchId) {
        Instant startedAt = Instant.now();
        long started = System.nanoTime();
        Tally tally = new Tally();
        tally.searchId = searchId;
        Found shown = found == null ? null : (sourceId, hits) -> found.found(sourceId, withoutExcluded(hits));
        Results results = withoutExcluded(batch(caller, query, cursor, limit, tally, shown));
        if (runs == SearchRunLog.NONE) {
            return results;
        }
        SearchRunId id = SearchRunId.random();
        List<SearchRun.StageCount> stages = new ArrayList<>();
        stages.add(new SearchRun.StageCount(SearchRun.Stage.QUERIES, tally.queries));
        tally.discovered.ifPresent(count -> stages.add(new SearchRun.StageCount(SearchRun.Stage.DISCOVERED, count)));
        tally.queued.ifPresent(count -> stages.add(new SearchRun.StageCount(SearchRun.Stage.LOAD_QUEUED, count)));
        tally.pending.ifPresent(count -> stages.add(new SearchRun.StageCount(SearchRun.Stage.PENDING, count)));
        tally.confirmed.ifPresent(count -> stages.add(new SearchRun.StageCount(SearchRun.Stage.CONFIRMED, count)));
        stages.add(new SearchRun.StageCount(SearchRun.Stage.DELIVERED, results.hits().size()
                + results.found().size()));
        boolean recorded = SearchRunLog.recordQuietly(runs, new SearchRun(id, partOf, SearchRun.Kind.KEYWORD,
                SearchRun.ownerOf(caller), query.text(), startedAt, Duration.ofNanos(System.nanoTime() - started),
                results.sources().stream().map(KeywordSearch::outcome).toList(), stages, results.complete(), null));
        return recorded ? results.withSearchRun(id.value()) : results;
    }

    /** Was lokal vollständig und aktuell vorliegt; ohne Aufruf nichts (jede Quelle mit Online-Suche wird gefragt). */
    private volatile LocalCoverage coverage = LocalCoverage.NONE;

    /**
     * Lokal zuerst (Angelo 27.11.2026): Für abgedeckte Spaces fragt die Suche die Quelle nicht online, und ist der ganze
     * Umfang einer Quelle abgedeckt, gar nicht; dort sucht nur der lokale Index.
     */
    public void coverage(LocalCoverage value) {
        this.coverage = Objects.requireNonNull(value, "coverage");
    }

    /** Ausgeschlossenes lässt die Suche weg; ohne Aufruf ist nichts ausgeschlossen. */
    public void exclusions(com.aresstack.spaceharvester.application.sync.Exclusions exclusions) {
        this.exclusions = Objects.requireNonNull(exclusions, "exclusions");
    }

    /** Der Stapel ohne Treffer unter einem Ausschluss; der Zähler „lädt“ bleibt, er betrifft nur den Fortschritt. */
    private Results withoutExcluded(Results results) {
        List<Hit> hits = withoutExcluded(results.hits());
        List<Hit> found = withoutExcluded(results.found());
        if (hits.size() == results.hits().size() && found.size() == results.found().size()) {
            return results;
        }
        return new Results(results.sourceId(), hits, results.sources(), results.nextCursor(), results.complete(),
                results.loading(), found, results.progress(), results.searchRun());
    }

    private List<Hit> withoutExcluded(List<Hit> hits) {
        com.aresstack.spaceharvester.application.sync.Exclusions excluded = exclusions;
        if (hits.isEmpty() || hits.stream().noneMatch(hit -> excluded.any(hit.sourceId()))) {
            return hits;
        }
        return hits.stream().filter(hit -> !excluded(excluded, hit)).toList();
    }

    /** Ob der Treffer unter einem Ausschluss liegt; seine Elternseite gilt vor dem Bestand. */
    private boolean excluded(Hit hit) {
        com.aresstack.spaceharvester.application.sync.Exclusions excluded = exclusions;
        return excluded.any(hit.sourceId()) && excluded(excluded, hit);
    }

    private static boolean excluded(com.aresstack.spaceharvester.application.sync.Exclusions exclusions, Hit hit) {
        return exclusions.within(hit.sourceId(), hit.spaceKey(),
                pageId -> pageId.equals(hit.pageId()) ? hit.parentPageId() : null).test(hit.pageId());
    }

    private static SearchRun.SourceOutcome outcome(SourceState state) {
        return new SearchRun.SourceOutcome(state.sourceId(), SearchRun.SourceOutcome.Status.valueOf(
                state.status().name()), state.reason());
    }

    /**
     * Was vor einer Suche geschieht, etwa das Nachholen der Änderungen ihrer Quellen ({@code sync.before-search}). Muss
     * sofort zurückkehren und darf nicht werfen: Die Suche antwortet aus dem Stand, den sie vorfindet.
     */
    @FunctionalInterface
    public interface BeforeSearch {

        BeforeSearch NONE = sourceIds -> {
        };

        /** @param sourceIds die Quellen der Suche, die der Aufrufer lesen darf */
        void searching(Set<String> sourceIds);
    }

    /**
     * Beobachter einer laufenden Suche: bekommt die Funde einer Quelle als vorläufige Treffer (Titel und Auszug der
     * Quelle, wie {@link Results#ranked()} sie zu Beginn nennt), sobald diese Quelle geantwortet hat. Quellen, deren
     * Funde die Suche erst je Seite prüfen muss, meldet er nicht; die stehen erst im fertigen Stapel. Ein Endnutzer
     * mit delegierter Anmeldung (ADR 0014) bekommt keine Meldung: Für ihn gilt nur die endgültige Liste.
     */
    @FunctionalInterface
    public interface Found {

        /**
         * @param sourceId die Quelle, die geantwortet hat
         * @param hits     ihre vorläufigen Treffer dieses Stapels in ihrer Rangfolge, höchstens {@code limit}
         */
        void found(String sourceId, List<Hit> hits);
    }

    /** Zahlen eines Stapels für das Suchlauf-Protokoll; nur geprüfte Seiten. */
    private static final class Tally {

        int queries;
        OptionalInt discovered = OptionalInt.empty();
        OptionalInt queued = OptionalInt.empty();
        OptionalInt pending = OptionalInt.empty();
        OptionalInt confirmed = OptionalInt.empty();
        /** Die Suche des Aufrufers, zu der der Stapel gehört, oder {@code null}. */
        String searchId;
    }

    private Results batch(AccessContext caller, Query query, String cursor, int limit, Tally tally, Found watcher) {
        Objects.requireNonNull(query, "query");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        SearchScope scope = SearchScope.authorize(access, catalog, caller, query.scope());
        scope.requireKnown(catalog);
        Position position = cursor == null || cursor.isEmpty() ? null : decode(cursor, fingerprint(query));
        // Mit einer Seitenmenge (ADR 0029) nur die Quellen, in denen sie Seiten hat.
        Set<String> sourceIds = scope.sourceIds().stream().filter(query::reaches)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (position == null && !sourceIds.isEmpty()) {
            before.searching(sourceIds);
        }
        Map<String, Integer> offsets = position == null ? null : position.offsets();
        boolean cached = texts != null;
        // ADR 0014, Fortschreibung Punkt 4: Ein Endnutzer mit delegierter Anmeldung bekommt nur die endgültige Liste aus
        // dem Cache, seitenweise mit seinem Token geprüft. Die Funde der Quelle bleiben ungeprüft und nur zum Laden.
        boolean finalListOnly = caller != null && caller.kind() == AccessContext.PrincipalKind.END_USER;
        // Vorläufige Treffer je Quelle gibt es nur mit Cache (dort sind sie der erste Stand der Liste) und nie für
        // Endnutzer.
        Found observer = watcher != null && cached && !finalListOnly ? watcher : null;

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
            if (!sourceIds.contains(sourceId)) {
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
            LocalCoverage.Coverage covered = cached ? coverage.coverage(source, query.spaces())
                    : LocalCoverage.Coverage.NONE;
            if (covered.all()) {
                // Lokal vollständig und aktuell: nur der lokale Index, keine Online-Suche (Angelo 27.11.2026).
                searchable.add(sourceId);
                if (position == null) {
                    states.add(new SourceState(sourceId, SourceState.Status.LOCAL, null));
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
            searches.add(new LiveSearch(source, offset, Math.min(offset + limit + 1, MAX_DEPTH), states.size(),
                    withoutCovered(query.spaces(), covered.spaces())));
            states.add(null);
        }
        // Alle Quellen und alle Anfragen zugleich; ausgewertet in der Reihenfolge der Quellen. Je Quelle kommen die
        // bestätigten Begriffsbeziehungen ihres Umfangs als Anfragen dazu (#336).
        List<String> nativeQueries = nativeQueries(query.text());
        String spaceKey = query.singleSpace();
        Map<String, List<SearchExpansion.Expansion>> expansions = new HashMap<>();
        // Je Quelle die Anfragen in der Reihenfolge ihrer Aufrufe, für die Ursache am Treffer (R1).
        Map<String, List<SourceQuery>> asked = new HashMap<>();
        Map<String, List<HitReason>> sourceReasons = new HashMap<>();
        tally.queries = searches.isEmpty() ? 0 : nativeQueries.size();
        List<List<Future<List<SourceSearchMatch>>>> calls = new ArrayList<>();
        try (ExecutorService searching = Executors.newVirtualThreadPerTaskExecutor()) {
            for (LiveSearch search : searches) {
                List<Future<List<SourceSearchMatch>>> perQuery = new ArrayList<>();
                List<SearchExpansion.Expansion> expanded = expansions(caller, query, search.source().id(),
                        spaceKey);
                expansions.put(search.source().id(), expanded);
                tally.queries = Math.max(tally.queries, nativeQueries.size() + expanded.size());
                List<SourceQuery> all = new ArrayList<>();
                nativeQueries.forEach(text -> all.add(new SourceQuery(text, null)));
                expanded.forEach(extra -> all.add(new SourceQuery(extra.query(), extra.relation())));
                asked.put(search.source().id(), List.copyOf(all));
                for (SourceQuery sent : all) {
                    perQuery.add(searching.submit(Jobs.carryingWithheld(() -> search.source().searchPages(sent.text(),
                            search.wanted(), search.spaces()))));
                }
                calls.add(perQuery);
                if (observer != null) {
                    // Sobald diese Quelle geantwortet hat, erfährt es der Beobachter, auch wenn andere noch suchen.
                    List<Future<List<SourceSearchMatch>>> answers = List.copyOf(perQuery);
                    List<SourceQuery> sent = List.copyOf(all);
                    searching.submit(Jobs.carryingWithheld(() -> {
                        report(observer, caller, query, search, answers, sent, limit);
                        return null;
                    }));
                }
            }
        }
        for (int s = 0; s < searches.size(); s++) {
            LiveSearch search = searches.get(s);
            String sourceId = search.source().id();
            int offset = search.offset();
            List<List<SourceSearchMatch>> answered = new ArrayList<>();
            SourceAccessException failure = null;
            List<SourceQuery> sent = asked.get(sourceId);
            for (int q = 0; q < calls.get(s).size(); q++) {
                Future<List<SourceSearchMatch>> call = calls.get(s).get(q);
                try {
                    List<SourceSearchMatch> answer = call.get();
                    answered.add(answer);
                    noteSourceReasons(sourceReasons, sourceId, sent.get(q), answer);
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
                List<SourceSearchMatch> matches = entry.getValue();
                int index = taken.get(entry.getKey());
                // Funde außerhalb der Seitenmenge zählen als genommen, damit der Cursor in der Rangfolge der Quelle
                // weiterzählt; sie werden weder Kandidat noch geladen.
                while (index < matches.size() && !query.admits(entry.getKey(), matches.get(index))) {
                    index++;
                }
                if (index < matches.size() && candidates.size() < limit) {
                    candidates.add(new Candidate(entry.getKey(), matches.get(index), false, null, null, null,
                            sourceReasons.getOrDefault(mark(entry.getKey(), matches.get(index).pageId()),
                                    List.of())));
                    index++;
                    added = true;
                }
                taken.put(entry.getKey(), index);
            }
        }

        // K5a: Was nur ein Synonymvorschlag fand, steht hinter allen übrigen; der Cursor zählt je Quelle weiter.
        candidates = HitReason.proposedSynonymsLast(candidates, Candidate::reasons);

        catalogFindings(caller, query, pending, tally.searchId);

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
            tally.discovered = OptionalInt.of(found.hits().size());
            boolean checkedAll = complete && found.complete();
            return new Results(scope.sourceId(), HitReason.proposedSynonymsLast(found.hits(), Hit::reasons), states,
                    next.isEmpty() ? null
                    : encode(fingerprint(query), !checkedAll, next, unavailable, Set.of(), List.of()),
                    checkedAll && next.isEmpty(), 0);
        }

        // ADR 0013 (Fortschreibung 18.10.): Die Funde der Quellen sind sofort vorläufige Treffer mit Titel und Auszug,
        // ohne Inhaltsabruf. Bewertet nach Titel und Auszug (#154) wählt die Strategie (ADR 0016, Punkt 4), welche
        // jetzt in den Cache laden; der Volltextindex findet sie dort, und seine Treffer ersetzen die vorläufigen. Was
        // ein früherer Stapel noch lud, fragt dieser Stapel wieder ab, damit es nicht verloren geht.
        Checked discovered = finalListOnly ? unchecked(candidates) : provisional(caller, query, candidates);
        complete &= discovered.complete();
        List<Hit> found = discovered.hits();
        ExcerptPriority.Priority priority = excerpts.prioritize(query.text(), found);
        List<Hit> ranked = priority.apply(found);
        boolean loads = prefetch != null;
        List<Hit> eligible = found.stream().filter(hit -> allowsPrefetch(hit.sourceId())).toList();
        List<Hit> eligibleRanked = ranked.stream().filter(hit -> allowsPrefetch(hit.sourceId())).toList();
        List<Hit> toLoad = new ArrayList<>(eligible.isEmpty() ? List.of()
                : select(eligible, eligibleRanked, priority.ranked()));
        if (!finalListOnly) {
            // Für Endnutzer sind die Funde ungeprüft: keine Zahl, auch nicht im Protokoll.
            tally.discovered = OptionalInt.of(found.size());
        }
        if (loads && position != null) {
            Set<PageKey> seen = new HashSet<>();
            toLoad.forEach(hit -> seen.add(new PageKey(hit.sourceId(), hit.pageId())));
            for (Hit hit : position.pending()) {
                if (sourceIds.contains(hit.sourceId()) && !unavailable.contains(hit.sourceId())
                        && allowsPrefetch(hit.sourceId()) && seen.add(new PageKey(hit.sourceId(), hit.pageId()))) {
                    toLoad.add(hit);
                }
            }
        }
        List<Hit> stillLoading = List.of();
        Progress progress = null;
        toLoad.removeIf(hit -> !allowsPrefetch(hit.sourceId()));
        // Ausgeschlossenes wird nie nachgeladen (Angelo 26.11.2026).
        toLoad.removeIf(this::excluded);
        if (loads && !toLoad.isEmpty()) {
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
        if (!finalListOnly) {
            tally.queued = OptionalInt.of(toLoad.size());
            tally.pending = OptionalInt.of(loading);
        }
        // Eben gefunden und geprüft: Der Cache-Treffer braucht keine zweite Rückfrage bei der Quelle.
        Map<PageKey, Hit> known = new HashMap<>();
        found.forEach(hit -> known.putIfAbsent(new PageKey(hit.sourceId(), hit.pageId()), hit));
        Set<String> delivered = position == null ? new LinkedHashSet<>() : new LinkedHashSet<>(position.delivered());
        List<Candidate> fromCache = new ArrayList<>();
        boolean moreInCache = false;
        if (!searchable.isEmpty() && delivered.size() < MAX_DEPTH) {
            try {
                int wanted = Math.min(delivered.size() + limit + 1, MAX_DEPTH);
                List<PageTextIndex.Match> matches = new ArrayList<>(searchTexts(query.text(), searchable, query,
                        wanted));
                // Ursachen je Seite (R1): jede Anfrage, die sie im Index lieferte, mit den Wörtern, die trafen.
                Map<String, List<DocumentReason>> textReasons = new HashMap<>();
                noteTextReasons(textReasons, matches, query.text(), null);
                // Erweiterungen je Quelle, nur in dieser Quelle gesucht, damit Beziehungen ihres Umfangs bleiben.
                for (String sourceId : searchable) {
                    List<SearchExpansion.Expansion> expanded = expansions.computeIfAbsent(sourceId, id ->
                            expansions(caller, query, id, spaceKey));
                    for (SearchExpansion.Expansion extra : expanded) {
                        List<PageTextIndex.Match> byExtra = searchTexts(extra.query(), Set.of(sourceId), query,
                                wanted);
                        noteTextReasons(textReasons, byExtra, extra.query(), extra.relation());
                        matches.addAll(byExtra);
                    }
                }
                // K5a: über alle Quellen hinter alles andere, was nur ein Synonymvorschlag fand.
                matches = HitReason.proposedSynonymsLast(matches, match -> textReasons
                        .getOrDefault(mark(match.sourceId(), match.pageId()), List.of()).stream()
                        .map(DocumentReason::reason).toList());
                Set<String> seenMatches = new HashSet<>();
                for (PageTextIndex.Match match : matches) {
                    if (!seenMatches.add(mark(match.sourceId(), match.pageId()))) {
                        continue;
                    }
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
                    fromCache.add(new Candidate(match.sourceId(), hit, true, seen, match.inAttachment()
                            ? new AttachmentMatch(match.attachmentId(), match.attachmentTitle(), match.partId(),
                                    match.partTitle()) : null,
                            match.passageId(), reasonsFor(textReasons.getOrDefault(mark, List.of()), match)));
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
        tally.confirmed = OptionalInt.of(checked.hits().size());
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
        // Lokal zuerst (Angelo 27.11.2026): Was schon im lokalen Index liegt und dort nicht trifft, ist kein Treffer;
        // vorläufig bleiben nur Funde, die noch nicht lokal liegen.
        List<Hit> provisional = finalListOnly ? List.of() : ranked.stream()
                .filter(hit -> !fromIndex.contains(new PageKey(hit.sourceId(), hit.pageId())))
                .filter(hit -> !locallyIndexed(new PageKey(hit.sourceId(), hit.pageId()))).toList();
        return new Results(scope.sourceId(), HitReason.proposedSynonymsLast(checked.hits(), Hit::reasons), states,
                nextCursor, checkedAll && exhausted, loading, HitReason.proposedSynonymsLast(provisional, Hit::reasons),
                progress);
    }

    /**
     * Bietet die Funde dieses Stapels zum Katalogisieren als Kopfdaten an (ADR 0016). Das reiht nur ein; ob ein Fund in
     * den Katalog kommt, entscheiden Aufrufer und Richtlinie dort. Ein Fehler lässt die Suche nie scheitern. Mit einer
     * Seitenmenge nur ihre Funde (ADR 0029): Die Hintergrundfüllung lädt angebotene Funde, und außerhalb der Auswahl
     * soll nichts geladen werden.
     */
    private void catalogFindings(AccessContext caller, Query query, Map<String, List<SourceSearchMatch>> found,
                                 String searchId) {
        Set<PageKey> offered = searchId == null ? null
                : offeredIn.computeIfAbsent(searchId, ignored -> ConcurrentHashMap.newKeySet());
        found.forEach((sourceId, matches) -> {
            if (!cataloguing.test(sourceId)) {
                return;
            }
            try {
                List<FindingCatalog.Finding> fresh = matches.stream()
                        .filter(match -> query.admits(sourceId, match.pageId()))
                        .filter(match -> !exclusions.excluded(sourceId, null, match.pageId()))
                        // Fundstelle 23: in dieser Suche nur, was sie noch nicht angeboten hat.
                        .filter(match -> offered == null || !offered.contains(new PageKey(sourceId, match.pageId())))
                        .map(match -> new FindingCatalog.Finding(match.pageId(), match.version())).toList();
                if (fresh.isEmpty()) {
                    return;
                }
                // Abnahme #1082: Nur Angenommenes gilt als angeboten; den Rest holt ein Neusortieren nach.
                Set<String> accepted = findings.accept(caller, sourceId, fresh);
                if (offered != null && accepted != null) {
                    accepted.forEach(pageId -> offered.add(new PageKey(sourceId, pageId)));
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Funde aus {0} nicht katalogisiert ({1})", sourceId,
                        e.getClass().getName());
            }
        });
    }

    /** Was jetzt lädt, nach der Strategie; scheitert sie, lädt nichts, die Funde bleiben vorläufig. */
    /** Keep implicit search extensions inside the configured global source scope. */
    public boolean includesSource(Query query, String sourceId) {
        return query.sourceId() != null || !query.sourceIds().isEmpty() || query.pages() != null
                || query.boundary() != null || catalog.includeInGlobalSearch(sourceId);
    }

    public boolean allowsPrefetch(String sourceId) {
        return prefetch != null && prefetching.test(sourceId);
    }

    private List<Hit> select(List<Hit> found, List<Hit> ranked, boolean byExcerpt) {
        CandidateSelectionStrategy.Capacity capacity;
        try {
            capacity = prefetch.capacity();
        } catch (RuntimeException e) {
            capacity = CandidateSelectionStrategy.Capacity.DEFAULT;
        }
        List<Hit> selected;
        try {
            selected = selection.select(new CandidateSelectionStrategy.Candidates(found, ranked, byExcerpt,
                    prefetch::current, capacity));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Auswahl der Beschaffung nicht möglich ({0})", e.getClass().getName());
            return List.of();
        }
        // Nur Funde dieses Stapels, jeder einmal; eine fremde Strategie kann nichts hinzufügen.
        Set<PageKey> visible = new HashSet<>();
        found.forEach(hit -> visible.add(new PageKey(hit.sourceId(), hit.pageId())));
        List<Hit> checked = new ArrayList<>();
        for (Hit hit : selected == null ? List.<Hit>of() : selected) {
            if (hit != null && visible.remove(new PageKey(hit.sourceId(), hit.pageId()))) {
                checked.add(hit);
            }
        }
        return checked;
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
                    match.url(), match.version(), match.modifiedAt(), null, null,
                    snippet ? candidate.passageId() : null, candidate.reasons()));
        }
        return new Checked(hits, complete);
    }

    /** Der Volltextindex, mit Seitenmenge der Anfrage vor der Rangfolge gefiltert (ADR 0029), ohne wie bisher. */
    private List<PageTextIndex.Match> searchTexts(String text, Set<String> sourceIds, Query query, int wanted) {
        if (query.boundary() != null) {
            // Teilbaum-Grenze: Die Seitenmenge kennt nicht alle Seiten der Grenze; der Index sucht in Quellen und
            // Spaces, und vor der Rangfolge bleibt nur, was die Anfrage zulässt.
            List<PageTextIndex.Match> all = query.kinds().isEmpty()
                    ? texts.search(text, sourceIds, query.spaces(), wanted)
                    : texts.search(text, sourceIds, query.spaces(), null, query.kinds(), wanted);
            return all.stream().filter(match -> query.admits(match.sourceId(), match.pageId())).toList();
        }
        if (!query.kinds().isEmpty()) {
            return texts.search(text, sourceIds, query.spaces(), query.pages(), query.kinds(), wanted);
        }
        return query.pages() == null ? texts.search(text, sourceIds, query.spaces(), wanted)
                : texts.search(text, sourceIds, query.spaces(), query.pages(), wanted);
    }

    /**
     * Meldet dem Beobachter die Funde einer Quelle, sobald alle ihre Anfragen geantwortet haben: derselbe Ausschnitt
     * und dieselben vorläufigen Treffer wie am Ende in {@link #provisional}, aber nur für Quellen, deren Freigabe für
     * die ganze Quelle gilt; die übrigen prüft erst der Stapel je Seite. Ausgefallene Anfragen und Fehler des
     * Beobachters lässt die Suche hier unbeachtet, der Stapel meldet sie.
     */
    private void report(Found found, AccessContext caller, Query query, LiveSearch search,
                        List<Future<List<SourceSearchMatch>>> answers, List<SourceQuery> sent, int limit) {
        List<List<SourceSearchMatch>> answered = new ArrayList<>();
        Map<String, List<HitReason>> reasons = new HashMap<>();
        String sourceId = search.source().id();
        for (int q = 0; q < answers.size(); q++) {
            try {
                List<SourceSearchMatch> answer = answers.get(q).get();
                answered.add(answer);
                noteSourceReasons(reasons, sourceId, sent.get(q), answer);
            } catch (ExecutionException e) {
                // Der Stapel wertet den Ausfall aus.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (answered.isEmpty() || !access.permitsSourceWide(caller, sourceId, AccessKind.METADATA)) {
            return;
        }
        List<SourceSearchMatch> matches = merge(answered);
        int offset = search.offset();
        List<SourceSearchMatch> fresh = matches.size() <= offset ? List.of()
                : matches.subList(offset, Math.min(matches.size(), offset + limit));
        boolean snippet = access.permitsSourceWide(caller, sourceId, AccessKind.CONTENT);
        List<Hit> hits = new ArrayList<>();
        for (SourceSearchMatch match : fresh) {
            if (!query.admits(sourceId, match)) {
                continue;
            }
            hits.add(new Hit(sourceId, match.pageId(), null, match.title(), snippet ? match.snippet() : "",
                    match.url(), match.version(), match.modifiedAt(), null, null, null,
                    reasons.getOrDefault(mark(sourceId, match.pageId()), List.of())));
        }
        try {
            found.found(sourceId, List.copyOf(hits));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Beobachter der Suche fehlgeschlagen ({0})", e.getClass().getName());
        }
    }

    /**
     * Die Funde der Quellen ohne jede Prüfung, nur als Kandidaten zum Bewerten und Laden; sie verlassen die Suche nicht.
     */
    private static Checked unchecked(List<Candidate> candidates) {
        List<Hit> hits = new ArrayList<>();
        for (Candidate candidate : candidates) {
            SourceSearchMatch match = candidate.match();
            hits.add(new Hit(candidate.sourceId(), match.pageId(), null, match.title(), match.snippet(), match.url(),
                    match.version(), match.modifiedAt(), null, null, null, candidate.reasons()));
        }
        return new Checked(hits, true);
    }

    /** Kopfdaten live und Freigabe je Treffer; parallel, Reihenfolge der Kandidaten bleibt. */
    private Checked check(AccessContext caller, Query query, List<Candidate> candidates) {
        Semaphore slots = new Semaphore(PARALLEL_CHECKS);
        List<Future<Optional<Hit>>> futures = new ArrayList<>();
        try (ExecutorService checks = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Candidate candidate : candidates) {
                // Die ausgelassenen Quellen eines quellenübergreifenden Auftrags gelten auch hier (Abnahme #1090).
                futures.add(checks.submit(Jobs.carryingWithheld(() -> {
                    slots.acquireUninterruptibly();
                    try {
                        return hit(caller, query, candidate);
                    } finally {
                        slots.release();
                    }
                })));
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
        if (!query.admits(candidate.sourceId(), candidate.match().pageId())) {
            return Optional.empty();
        }
        if (candidate.known() != null) {
            // Eben gefunden, live geprüft und geladen; aus dem Cache kommen nur Fundstelle und Auszug.
            Hit known = candidate.known();
            if (!access.permits(caller, ResourceScope.page(known.sourceId(), known.spaceKey(), known.pageId()),
                    AccessKind.CONTENT)) {
                return Optional.empty();
            }
            // Steht das Wort nur im Titel, hat der Cache kein Textfenster; dann bleibt der Auszug der Quelle.
            // Ein Anhangstreffer behält den Auszug aus dem Anhang, auch einen leeren (Wort nur im Dateinamen).
            boolean fromSource = candidate.attachment() == null && (candidate.match().snippet() == null
                    || candidate.match().snippet().isEmpty());
            String snippet = fromSource ? known.snippet() : candidate.match().snippet();
            return Optional.of(new Hit(known.sourceId(), known.pageId(), known.spaceKey(), known.title(), snippet,
                    known.url(), known.version(), known.modifiedAt(),
                    known.parentPageId(), candidate.attachment(), fromSource ? null : candidate.passageId(),
                    candidate.reasons()));
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
        // Ein Fund der Quelle (nicht aus dem Index): die Kopfdaten entscheiden endgültig über die Art.
        if (!candidate.indexed() && !query.admits(metadata)) {
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
        // Die Passage-ID nennt den Texthash: nur mit dem Auszug des Index, also nur mit content.
        return Optional.of(new Hit(candidate.sourceId(), metadata.pageId(), metadata.partitionKey(),
                metadata.title().isEmpty() ? match.title() : metadata.title(), snippet,
                metadata.url() != null ? metadata.url() : match.url(),
                metadata.version() != null ? metadata.version() : match.version(),
                metadata.modifiedAt() != null ? metadata.modifiedAt() : match.modifiedAt(),
                metadata.parentPageId(), candidate.attachment(), candidate.indexed() ? candidate.passageId() : null,
                candidate.reasons()));
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
        if (query.pages() != null) {
            // Nur mit Grenze, aus demselben Grund; eine andere Auswahl macht den Cursor ungültig (ADR 0029).
            canonical += "\u0000p" + query.pages().fingerprint();
        }
        if (query.boundary() != null) {
            canonical += "\u0000b" + query.boundary().fingerprint();
        }
        if (!query.kinds().isEmpty()) {
            // Nur mit Arten, aus demselben Grund; andere Arten machen den Cursor ungültig.
            canonical += "\u0000k" + query.kinds().stream().map(Enum::name).sorted()
                    .collect(java.util.stream.Collectors.joining("\u0001"));
        }
        if (query.glossarySynonyms()) {
            // Nur mit der Suchmethode (K5a), aus demselben Grund; andere Anfragen machen den Cursor ungültig.
            canonical += "\u0000s";
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
    private record LiveSearch(HarvestSource source, int offset, int wanted, int state, SearchSpaces spaces) {
    }

    /** Ob die Seite schon im lokalen Volltextindex liegt; ohne Cache nie. */
    private boolean locallyIndexed(PageKey key) {
        if (texts == null) {
            return false;
        }
        try {
            return texts.inputSha256(key).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Der Space-Filter der Online-Suche ohne die lokal abgedeckten Spaces: Die fragt sie nicht mehr. */
    static SearchSpaces withoutCovered(SearchSpaces spaces, Set<String> covered) {
        if (covered.isEmpty()) {
            return spaces;
        }
        Set<String> only = new LinkedHashSet<>(spaces.only());
        only.removeAll(covered);
        Set<String> excluded = new LinkedHashSet<>(spaces.excluded());
        excluded.addAll(covered);
        return new SearchSpaces(only, excluded);
    }

    /**
     * @param known      eben live geprüfter Treffer derselben Seite oder {@code null}
     * @param attachment der Anhang der Seite, in dem der Volltextindex fand, oder {@code null}
     * @param passageId  Passage-ID des Absatzes, aus dem der Auszug des Index stammt, oder {@code null}
     */
    private record Candidate(String sourceId, SourceSearchMatch match, boolean indexed, Hit known,
                             AttachmentMatch attachment, String passageId, List<HitReason> reasons) {

        Candidate {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    /**
     * Eine Anfrage an die Suche einer Quelle.
     *
     * @param relation die bestätigte Beziehung, aus der die Anfrage entstand, oder {@code null}
     */
    private record SourceQuery(String text, HitReason.Relation relation) {
    }

    /** Merkt je gefundener Seite der Quelle die Anfrage, auf die sie gemeldet wurde (R1). */
    private static void noteSourceReasons(Map<String, List<HitReason>> reasons, String sourceId, SourceQuery sent,
                                          List<SourceSearchMatch> answer) {
        if (sent == null || answer == null) {
            return;
        }
        HitReason reason = HitReason.source(sent.text(), sent.relation());
        for (SourceSearchMatch match : answer) {
            reasons.merge(mark(sourceId, match.pageId()), List.of(reason), HitReason::union);
        }
    }

    /**
     * Eine Ursache aus dem Volltextindex mit dem Dokument (Seite, Anhang oder enthaltene Datei), in dem ihre Wörter
     * trafen.
     */
    private record DocumentReason(String document, HitReason reason) {
    }

    /** Merkt je Seite die Anfrage, die sie im Volltextindex lieferte, mit den Wörtern, die dort trafen (R1). */
    private static void noteTextReasons(Map<String, List<DocumentReason>> reasons, List<PageTextIndex.Match> matches,
                                        String text, HitReason.Relation relation) {
        for (PageTextIndex.Match match : matches) {
            HitReason reason = HitReason.text(text, relation, match.words().inTitle(), match.words().inText());
            reasons.computeIfAbsent(mark(match.sourceId(), match.pageId()), key -> new ArrayList<>())
                    .add(new DocumentReason(document(match), reason));
        }
    }

    /**
     * Die Ursachen einer Seite für den Treffer mit dem Dokument {@code shown}. Fand eine Anfrage die Seite über ein
     * anderes Dokument (etwa einen anderen Anhang), bleibt sie Ursache, aber ohne Wörter: Sie stehen nicht im
     * gezeigten Dokument.
     */
    private static List<HitReason> reasonsFor(List<DocumentReason> found, PageTextIndex.Match shown) {
        String document = document(shown);
        List<HitReason> reasons = List.of();
        for (DocumentReason entry : found) {
            HitReason reason = entry.document().equals(document) ? entry.reason()
                    : HitReason.text(entry.reason().query(), entry.reason().relation(), List.of(), List.of());
            reasons = HitReason.union(reasons, List.of(reason));
        }
        return reasons;
    }

    private static String document(PageTextIndex.Match match) {
        return match.inAttachment() ? match.attachmentId() + "\u0000" + (match.partId() == null ? "" : match.partId())
                : "";
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

        /**
         * Meldet einen Empfänger für den Stand eines Stapels an (ADR 0027); ohne Fortschritt {@link Subscription#NONE}.
         */
        default Subscription subscribe(String id, Consumer<Progress> listener) {
            return Subscription.NONE;
        }

        /** Kopfdaten einer Seite im Cache, ohne Rückfrage bei der Quelle; leer, wenn unbekannt. */
        default Optional<PageMetadata> cachedMetadata(PageKey key) {
            return Optional.empty();
        }

        /** Bricht das Laden eines Stapels ab; {@code false}, wenn unbekannt. */
        default boolean cancel(String id) {
            return false;
        }

        /**
         * Ob die Version dieses Fundes schon im Cache liegt, er also nicht laden muss; für die
         * {@link CandidateSelectionStrategy}. Standard: unbekannt, also nein.
         */
        default boolean current(Hit hit) {
            return false;
        }

        /** Lage des Vordergrunds für die {@link CandidateSelectionStrategy}. */
        default CandidateSelectionStrategy.Capacity capacity() {
            return CandidateSelectionStrategy.Capacity.DEFAULT;
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
     * @param pages     nur diese Seiten samt ihren Anhängen, etwa eine Seite samt Unterseiten aus der Auswahl im Baum
     *                  (ADR 0029), oder {@code null} ohne diese Grenze. Ein Fund der Quelle außerhalb der Menge fällt
     *                  weg und wird nicht geladen; Quellen ohne Seite in der Menge werden nicht gefragt.
     * @param kinds     nur Dokumente dieser Arten ({@link DocumentKind}: Seiten, PDF, Word, …), leer für alle. Der
     *                  Volltextindex entscheidet je Dokument nach Name und Medientyp; ein Fund der Quelle nach seinem
     *                  Titel.
     */
    public record Query(String text, String sourceId, Set<String> sourceIds, Set<String> spaceKeys,
                        Set<String> excludedSpaceKeys, PageSet pages, Set<DocumentKind> kinds,
                        boolean glossarySynonyms, SearchBoundary boundary) {

        /** Ohne Teilbaum-Grenze ({@link SearchBoundary}). */
        public Query(String text, String sourceId, Set<String> sourceIds, Set<String> spaceKeys,
                     Set<String> excludedSpaceKeys, PageSet pages, Set<DocumentKind> kinds,
                     boolean glossarySynonyms) {
            this(text, sourceId, sourceIds, spaceKeys, excludedSpaceKeys, pages, kinds, glossarySynonyms, null);
        }

        public Query {
            kinds = kinds == null || kinds.isEmpty() ? Set.of() : Set.copyOf(kinds);
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

        /** Ohne Suchmethode „Synonyme der Fachbegriffe“. */
        public Query(String text, String sourceId, Set<String> sourceIds, Set<String> spaceKeys,
                     Set<String> excludedSpaceKeys, PageSet pages, Set<DocumentKind> kinds) {
            this(text, sourceId, sourceIds, spaceKeys, excludedSpaceKeys, pages, kinds, false);
        }

        public Query(String text, String sourceId, Set<String> sourceIds, Set<String> spaceKeys,
                     Set<String> excludedSpaceKeys, PageSet pages) {
            this(text, sourceId, sourceIds, spaceKeys, excludedSpaceKeys, pages, Set.of());
        }

        public Query(String text, String sourceId, Set<String> sourceIds, Set<String> spaceKeys,
                     Set<String> excludedSpaceKeys) {
            this(text, sourceId, sourceIds, spaceKeys, excludedSpaceKeys, null);
        }

        public Query(String text, String sourceId, Set<String> sourceIds, Set<String> spaceKeys) {
            this(text, sourceId, sourceIds, spaceKeys, Set.of());
        }

        /** Dieselbe Suche, begrenzt auf diese Seiten (ADR 0029); {@code null} hebt die Grenze auf. */
        public Query within(PageSet pages) {
            // Eingrenzen auf genau diese Seiten (etwa die Kandidaten einer Runde): ohne Teilbaum-Grenze, die sonst
            // wieder erweitern würde.
            return new Query(text, sourceId, sourceIds, spaceKeys, excludedSpaceKeys, pages, kinds, glossarySynonyms,
                    null);
        }

        /**
         * Dieselbe Suche über die Seiten {@code pages} (die bekannten, etwa aus dem Katalog) und alles, was
         * {@code boundary} zulässt (Teilbäume, die keine Seitenmenge vollständig kennt). Die Grenze gilt für jeden
         * Schritt: Funde außerhalb werden weder katalogisiert noch geladen, bewertet oder Nachbarn.
         */
        public Query bounded(PageSet pages, SearchBoundary boundary) {
            return new Query(text, sourceId, sourceIds, spaceKeys, excludedSpaceKeys,
                    pages == null ? PageSet.EMPTY : pages, kinds, glossarySynonyms,
                    java.util.Objects.requireNonNull(boundary, "boundary"));
        }

        /** Dieselbe Suche, nur Dokumente dieser Arten; leer hebt die Grenze auf. */
        public Query ofKinds(Set<DocumentKind> kinds) {
            return new Query(text, sourceId, sourceIds, spaceKeys, excludedSpaceKeys, pages, kinds, glossarySynonyms,
                    boundary);
        }

        /**
         * Dieselbe Suche mit der Suchmethode „Synonyme der Fachbegriffe einbeziehen“ (K5a): zu bestätigten
         * Fachbegriffen des Glossars kommen auch ihre vorgeschlagenen Synonyme als Anfragen dazu.
         */
        public Query withGlossarySynonyms(boolean on) {
            return new Query(text, sourceId, sourceIds, spaceKeys, excludedSpaceKeys, pages, kinds, on, boundary);
        }

        /** Derselbe Umfang und dieselben Methoden mit anderem Wortlaut (Varianten der progressiven Suche). */
        public Query withText(String other) {
            return new Query(other, sourceId, sourceIds, spaceKeys, excludedSpaceKeys, pages, kinds, glossarySynonyms,
                    boundary);
        }

        /**
         * Ob die Seite in der Grenze der Suche liegt: in der Seitenmenge oder, mit Teilbaum-Grenze, nach dieser; ohne
         * beides jede.
         */
        public boolean admits(String sourceId, String pageId) {
            if (pages == null && boundary == null) {
                return true;
            }
            return pages != null && pages.contains(sourceId, pageId)
                    || boundary != null && boundary.admits(new PageKey(sourceId, pageId));
        }

        /**
         * Ob ein Fund der Quelle Kandidat bleibt: in der Grenze der Seiten und, mit Arten, nicht schon nach seinem
         * Titel von einer anderen Art. Ein Titel ohne bekannte Endung bleibt Kandidat (und lädt), denn erst die
         * Kopfdaten kennen den gemeldeten Medientyp; {@link #admits(String, PageMetadata)} entscheidet dann.
         */
        boolean admits(String sourceId, SourceSearchMatch match) {
            return admits(sourceId, match.pageId()) && (kinds.isEmpty()
                    || !DocumentKind.namedByExtension(match.title())
                    || kinds.contains(DocumentKind.of(match.title(), null)));
        }

        /** Ob eine Seite nach ihren Kopfdaten (Titel und gemeldeter Medientyp, #964) von einer gewählten Art ist. */
        boolean admits(PageMetadata metadata) {
            return kinds.isEmpty() || kinds.contains(DocumentKind.of(metadata.title(),
                    metadata.attributes().get(PageMetadata.REMOTE_MEDIA_TYPE)));
        }

        /** Ob die Quelle Seiten in der Grenze der Suche hat; ohne Grenze jede. */
        boolean reaches(String sourceId) {
            if (pages == null && boundary == null) {
                return true;
            }
            return pages != null && !pages.pagesOf(sourceId).isEmpty()
                    || boundary != null && boundary.reaches(sourceId);
        }

        /** Alle freigegebenen Quellen. */
        public static Query of(String text) {
            return new Query(text, null, Set.of(), Set.of());
        }

        /** Der Space-Filter für die Quelle. */
        public SearchSpaces spaces() {
            return new SearchSpaces(spaceKeys, excludedSpaceKeys);
        }

        /** Der eine Space der Suche oder {@code null}, wenn sie mehrere oder alle Spaces durchsucht. */
        String singleSpace() {
            return spaceKeys.size() == 1 ? spaceKeys.iterator().next() : null;
        }

        PageSearchQuery scope() {
            // Mit Teilbaum-Grenze bestimmen Quellen und Spaces den Umfang; die Seitenmenge kennt nicht jede Seite der
            // Grenze, und welche Funde bleiben, entscheidet admits.
            return new PageSearchQuery(sourceId, sourceIds, spaceKeys, List.of(), null, null, null, null, null,
                    SearchSort.TITLE, boundary != null ? null : pages);
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
     * @param searchRun  Kennung des Laufs im Suchlauf-Protokoll (#182) oder {@code null}, wenn nicht protokolliert
     */
    public record Results(String sourceId, List<Hit> hits, List<SourceState> sources, String nextCursor,
                          boolean complete, int loading, List<Hit> found, Progress progress, String searchRun) {

        public Results {
            hits = List.copyOf(hits);
            sources = List.copyOf(sources);
            found = found == null ? List.of() : List.copyOf(found);
        }

        public Results(String sourceId, List<Hit> hits, List<SourceState> sources, String nextCursor,
                       boolean complete, int loading, List<Hit> found, Progress progress) {
            this(sourceId, hits, sources, nextCursor, complete, loading, found, progress, null);
        }

        /** Derselbe Stapel mit der Kennung seines Laufs im Suchlauf-Protokoll. */
        public Results withSearchRun(String id) {
            return new Results(sourceId, hits, sources, nextCursor, complete, loading, found, progress, id);
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
     * <p>Geladen wird in der Rangfolge nach Titel und Auszug, die besten zuerst, so viele Funde des Stapels, wie die
     * {@link CandidateSelectionStrategy} auswählt; die Liste wird dabei nur neu geordnet ({@link Results#ranked()}). Ist {@link #done()} wahr, ist die Suche abgeschlossen;
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
     * Ein Treffer, immer eine Seite; Kopfdaten live von der Quelle, Auszug aus dem Cache (ohne Cache aus der Suche der
     * Quelle).
     *
     * @param snippet    Textauszug als Klartext, leer, wenn es keinen gibt; bei einem Anhangstreffer aus dem Anhang
     * @param attachment der Anhang der Seite, in dessen Text der Volltextindex fand (#183), oder {@code null}, wenn die
     *                   Seite selbst passt
     * @param passageId  die Passage-ID ({@code PassageId}) des Absatzes, aus dem der Auszug stammt, wie
     *                   {@code passage:} an den Treffern im Enterprise AI Client; {@code null} ohne Auszug aus Absätzen
     * @param reasons    warum der Treffer gefunden wurde (R1), aus dem Lauf der Suche; leer, wenn unbekannt
     */
    public record Hit(String sourceId, String pageId, String spaceKey, String title, String snippet, URI url,
                      String version, Instant modifiedAt, String parentPageId, AttachmentMatch attachment,
                      String passageId, List<HitReason> reasons) {

        public Hit {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public Hit(String sourceId, String pageId, String spaceKey, String title, String snippet, URI url,
                   String version, Instant modifiedAt, String parentPageId, AttachmentMatch attachment,
                   String passageId) {
            this(sourceId, pageId, spaceKey, title, snippet, url, version, modifiedAt, parentPageId, attachment,
                    passageId, List.of());
        }

        public Hit(String sourceId, String pageId, String spaceKey, String title, String snippet, URI url,
                   String version, Instant modifiedAt, String parentPageId) {
            this(sourceId, pageId, spaceKey, title, snippet, url, version, modifiedAt, parentPageId, null);
        }

        public Hit(String sourceId, String pageId, String spaceKey, String title, String snippet, URI url,
                   String version, Instant modifiedAt, String parentPageId, AttachmentMatch attachment) {
            this(sourceId, pageId, spaceKey, title, snippet, url, version, modifiedAt, parentPageId, attachment,
                    null);
        }

        /** Derselbe Treffer mit diesen Ursachen. */
        public Hit withReasons(List<HitReason> reasons) {
            return new Hit(sourceId, pageId, spaceKey, title, snippet, url, version, modifiedAt, parentPageId,
                    attachment, passageId, reasons);
        }

        /** Wo der Treffer liegt: in der Seite oder in einem ihrer Anhänge. */
        public MatchedIn matchedIn() {
            return attachment == null ? MatchedIn.PAGE : MatchedIn.ATTACHMENT;
        }
    }

    /** Trefferart: wo die Fundstelle liegt. */
    public enum MatchedIn {
        /** Titel oder Text der Seite (oder die Suche der Quelle). */
        PAGE,
        /** Text eines Anhangs der Seite (#183). */
        ATTACHMENT
    }

    /**
     * Der Anhang eines Anhangstreffers. Sichtbar nur mit der Seite, deren Rechte er trägt.
     *
     * @param title     Dateiname
     * @param partId    Teilkennung der enthaltenen Datei des Anhangs, in der der Treffer liegt (ADR 0026), oder
     *                  {@code null} für das Dokument selbst
     * @param partTitle Anzeigename dieser enthaltenen Datei oder {@code null}
     */
    public record AttachmentMatch(String attachmentId, String title, String partId, String partTitle) {

        public AttachmentMatch {
            Objects.requireNonNull(attachmentId, "attachmentId");
            title = title == null ? "" : title;
            partId = partId == null || partId.isEmpty() ? null : partId;
            partTitle = partId == null ? null : partTitle == null ? "" : partTitle;
        }

        public AttachmentMatch(String attachmentId, String title) {
            this(attachmentId, title, null, null);
        }
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
            UNAVAILABLE,
            /** Lokal vollständig und aktuell: nur der Volltextindex hat gesucht, die Quelle wurde nicht gefragt. */
            LOCAL
        }
    }
}
