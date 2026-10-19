package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.api.PageQueryController.CacheResponse;
import com.aresstack.spaceharvester.api.PageQueryController.PageEntry;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageSearch;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchResults;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.ranking.Reranking;
import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.MetadataHash;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Suche des Harvesters (#28, ADR 0010, ADR 0013): mit {@code text} die Schlagwortsuche über den Cache
 * ({@link KeywordSearch}: die Quellen finden, gefundene Seiten werden geladen, der Volltextindex sucht), sonst Titel-
 * und Metadatensuche über den Index.
 * Liefert nie Seiteninhalte. {@code sort=relevance} ordnet zusätzlich nach der Ähnlichkeit zu den
 * gespeicherten Embeddings (#89). Ohne {@code sourceId} sucht sie global über alle freigegebenen, indexierten Quellen
 * (#114, ADR 0009); Rechte, Sortierung und Cursor kommen aus demselben Core-Anwendungsfall wie in der Desktop-App.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/search", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Suche", description = "Schlagwortsuche über die Suche der Quellen, Titel- und Metadatensuche im Index")
public class SearchController {

    private final PageSearch search;
    private final KeywordSearch keywords;
    private final RelevanceSearch relevance;

    public SearchController(PageSearch search, KeywordSearch keywords, RelevanceSearch relevance) {
        this.search = search;
        this.keywords = keywords;
        this.relevance = relevance;
    }

    @GetMapping("/progress/{id}")
    @Operation(summary = "Stand des Ladens einer Schlagwortsuche",
            description = "Zähler zu progress.id einer Antwort mit text: wie viele der besten Funde geladen sind. "
                    + "Steigt loaded, dieselbe Suche erneut stellen; die geladenen Seiten stehen dann in hits. Nennt "
                    + "keine Seiten. Bekannt sind die letzten " + 256 + " Suchen.")
    @ApiResponse(responseCode = "200", description = "Der Stand")
    @ApiResponse(responseCode = "404", description = "Unbekannt oder verdrängt (not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProgressResponse> progress(@PathVariable String id) {
        return keywords.progress(id).map(ProgressResponse::of).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/progress/{id}")
    @Operation(summary = "Laden einer Schlagwortsuche abbrechen",
            description = "Wartende Abrufe entfallen, ein laufender endet regulär; Geladenes bleibt im Cache.")
    @ApiResponse(responseCode = "204", description = "Abgebrochen")
    @ApiResponse(responseCode = "404", description = "Unbekannt oder verdrängt (not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        return keywords.cancel(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping
    @Operation(summary = "Seiten einer oder aller Quellen nach Titel und Metadaten suchen, stapelweise",
            description = "Mit text: Schlagwortsuche über den Cache (ADR 0013, origin=cache, Antwort KeywordResponse). "
                    + "Die Suche jeder Quelle des Umfangs (Confluence: CQL siteSearch über /rest/api/search) findet "
                    + "Kandidaten; deren Kopfdaten liest der Harvester live und prüft an ihnen metadata für genau "
                    + "diese Seite, bei delegierten Benutzern über die Quelle. Sichtbare Kandidaten lädt er mit den "
                    + "Rechten des Aufrufers in den Cache, ohne vorherigen Abgleich; gleiche Versionen nicht erneut. "
                    + "Dann sucht der Volltextindex (BM25) über den Cache, auch für Quellen ohne eigene Suche "
                    + "(sources: indexed); nur von dort kommen Treffer, jeder verlangt metadata und content. Was beim "
                    + "Antworten noch lädt, zählt loading und erscheint über nextCursor (höchstens "
                    + KeywordSearch.MAX_DEPTH + " je Quelle). sources nennt je Quelle searched, indexed, "
                    + "not-supported (kann nicht selbst suchen, kein Cache) oder unavailable (mit reason); eine nicht "
                    + "erreichbare Quelle verwirft "
                    + "die anderen nicht, complete ist dann false. sourceId, sourceIds und spaceKey gelten wie unten; "
                    + "andere Filter und sort gibt es mit text nicht. Ohne text: Titel- und Metadatensuche im Index "
                    + "(origin=cache). Mit sourceId nur diese Quelle (scope=source), ohne "
                    + "alle Quellen, für die der Aufrufer metadata hat und die im Index liegen (scope=global): eine "
                    + "Abfrage, global sortiert und paginiert, kein Zusammenfügen je Quelle. Global gelten Filter in "
                    + "jeder Quelle (spaceKey=DEV heißt DEV in jeder freigegebenen Quelle), nicht freigegebene Quellen "
                    + "fallen ohne Fehler und ohne Spur weg, und ohne lesbare indexierte Quelle ist die Antwort leer "
                    + "(index=null). sourceIds schränkt die globale Suche auf diese Quellen ein (wiederholbar oder durch "
                    + "Komma getrennt); unbekannte oder nicht freigegebene IDs darin fallen ebenso ohne Fehler weg, "
                    + "zusammen mit sourceId ist es 400. Jeder Treffer nennt seine sourceId und ist unter /api/v1/sources/{sourceId}/... "
                    + "adressierbar; gleiche Sortierwerte ordnet pageId, dann sourceId. Alle Bedingungen gelten "
                    + "zugleich. title: jedes "
                    + "Wort muss als ganzes Wort im Titel vorkommen, ohne Beachtung der Groß-/Kleinschreibung; "
                    + "Wörter trennt jedes Zeichen, das weder Buchstabe noch Ziffer ist. Keine Abfragesprache, kein "
                    + "Präfix, kein Volltext. Alle Stapel einer Suche stammen aus derselben Indexgeneration "
                    + "(index.generation, global aus allen gelesenen Quellen); ersetzt ein Commit sie, endet die Suche "
                    + "mit 409 und beginnt ohne cursor neu. Ein Stapel kann kürzer als limit oder leer sein, auch wenn noch Treffer folgen: "
                    + "nextCursor mit denselben Parametern übergeben, bis er null ist (complete=true). Jeder Treffer "
                    + "nennt den Cache-Stand seines Space. sort=relevance: ein einziger Stapel (complete=true, kein "
                    + "cursor) aus höchstens " + Reranking.MAX_WINDOW + " Kandidaten der Vektorsuche nach q und der "
                    + "Titelsuche, global ein gemeinsames Fenster über alle Quellen (keine Top-K je Quelle), geordnet "
                    + "nach der Kosinusähnlichkeit zu den gespeicherten Embeddings; Kandidaten "
                    + "der Vektorsuche nur mit content-Freigabe, Ähnlichkeiten (relevance) nur für Seiten mit "
                    + "content-Freigabe. Ohne eingerichtetes Modell nur Titeltreffer in Titelreihenfolge.")
    @ApiResponse(responseCode = "200", description = "Ein Stapel Treffer")
    @ApiResponse(responseCode = "400", description = "Ungültige Parameter, überschrittene Grenzen, sourceId zusammen "
            + "mit sourceIds oder cursor einer anderen Suche (invalid-request)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Nur mit sourceId: keine metadata-Freigabe für einen genannten "
            + "Space bzw. die Quelle, oder keine Quelle mit dieser ID (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Nur mit sourceId: für die Quelle liegt nichts im Index "
            + "(cache-miss); global nie",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Der cursor gehört zu einer ersetzten Indexgeneration "
            + "(cache-generation-changed)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Index nicht eingerichtet oder nicht lesbar (cache-unavailable); "
            + "sort=relevance ohne title, aber ohne verfügbares Embedding-Modell (semantic-search-unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public Object search(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Schlagworte für die Suche der Quellen über Titel und Inhalt, höchstens "
                    + KeywordSearch.MAX_TEXT_LENGTH + " Zeichen; nicht mit title, pageId, parentPageId, version, "
                    + "modifiedFrom, modifiedBefore, sort oder q", example = "Nachtlauf")
            @RequestParam(required = false) String text,
            @Parameter(description = "Quellen-ID aus der Konfiguration; ohne Angabe alle freigegebenen, indexierten "
                    + "Quellen", example = "confluence")
            @RequestParam(required = false) String sourceId,
            @Parameter(description = "Ohne sourceId: nur diese Quellen durchsuchen (wiederholbar oder durch Komma "
                    + "getrennt, höchstens " + PageSearchQuery.MAX_SOURCE_IDS + "); ohne Angabe alle freigegebenen, "
                    + "indexierten Quellen. Unbekannte oder nicht freigegebene IDs fallen still weg",
                    example = "confluence,wiki2")
            @RequestParam(required = false) List<String> sourceIds,
            @Parameter(description = "Nur diese Spaces (wiederholbar, höchstens " + PageSearchQuery.MAX_SPACE_KEYS
                    + "); ohne Angabe alle freigegebenen Spaces; global in jeder Quelle")
            @RequestParam(required = false) List<String> spaceKey,
            @Parameter(description = "Nur mit text: ohne diese Spaces (wiederholbar, höchstens "
                    + PageSearchQuery.MAX_SPACE_KEYS + "); geht wie spaceKey als Filter in die Anfrage der Quelle")
            @RequestParam(required = false) List<String> excludeSpaceKey,
            @Parameter(description = "Wörter im Titel, höchstens " + PageSearchQuery.MAX_TITLE_TERMS + " Wörter und "
                    + PageSearchQuery.MAX_TITLE_LENGTH + " Zeichen", example = "Architektur Übersicht")
            @RequestParam(required = false) String title,
            @Parameter(description = "Genau diese Seite") @RequestParam(required = false) String pageId,
            @Parameter(description = "Nur direkte Kinder dieser Seite") @RequestParam(required = false)
            String parentPageId,
            @Parameter(description = "Genau diese Version der Quelle") @RequestParam(required = false) String version,
            @Parameter(description = "Geändert ab einschließlich (ISO-8601, UTC)", example = "2026-10-01T00:00:00Z")
            @RequestParam(required = false) String modifiedFrom,
            @Parameter(description = "Geändert vor ausschließlich (ISO-8601, UTC)")
            @RequestParam(required = false) String modifiedBefore,
            @Parameter(description = "Sortierung, jeweils dann nach pageId und sourceId: title (aufsteigend, ohne Groß-/"
                    + "Kleinschreibung), -modifiedAt (neueste zuerst, ohne Änderungszeit zuletzt), pageId; relevance "
                    + "(ähnlichste zuerst, braucht q oder title, ohne cursor)",
                    schema = @Schema(allowableValues = {"title", "-modifiedAt", "pageId", "relevance"}))
            @RequestParam(required = false) String sort,
            @Parameter(description = "Nur mit sort=relevance: Frage oder Stichwörter in natürlicher Sprache, höchstens "
                    + RelevanceSearch.MAX_TEXT_LENGTH + " Zeichen; ohne Angabe die Wörter aus title",
                    example = "Wie läuft der nächtliche Betrieb?")
            @RequestParam(required = false) String q,
            @Parameter(description = "nextCursor der vorigen Antwort; leer für den ersten Stapel")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Größe des Stapels, 1 bis " + PageSearch.MAX_LIMIT)
            @RequestParam(defaultValue = "50") int limit) {
        if (text != null) {
            if (title != null || pageId != null || parentPageId != null || version != null || modifiedFrom != null
                    || modifiedBefore != null || sort != null || q != null) {
                throw new InvalidQueryException("text gibt es nur mit sourceId, sourceIds, spaceKey, excludeSpaceKey, "
                        + "cursor und limit");
            }
            KeywordSearch.Query query = new KeywordSearch.Query(text, sourceId,
                    Set.copyOf(sourceIds == null ? List.of() : sourceIds),
                    Set.copyOf(spaceKey == null ? List.of() : spaceKey),
                    Set.copyOf(excludeSpaceKey == null ? List.of() : excludeSpaceKey));
            return KeywordResponse.of(keywords.searchesTheCache(), keywords.search(caller, query, cursor, limit));
        }
        if (excludeSpaceKey != null && !excludeSpaceKey.isEmpty()) {
            throw new InvalidQueryException("excludeSpaceKey gibt es nur mit text");
        }
        if (sort == null) {
            sort = "title";
        }
        boolean byRelevance = RELEVANCE.equals(sort);
        PageSearchQuery query = PageSearchQuery.of(sourceId, sourceIds, spaceKey, title, pageId, parentPageId, version,
                instant("modifiedFrom", modifiedFrom), instant("modifiedBefore", modifiedBefore),
                byRelevance ? SearchSort.TITLE : sort(sort));
        if (!byRelevance) {
            if (q != null) {
                throw new InvalidQueryException("q gibt es nur mit sort=relevance");
            }
            return SearchResponse.of(search.search(caller, query, cursor, limit));
        }
        if (cursor != null && !cursor.isEmpty()) {
            throw new InvalidQueryException("sort=relevance liefert nur einen Stapel und kennt keinen cursor");
        }
        return SearchResponse.of(relevance.search(caller, query, q, limit));
    }

    private static final String RELEVANCE = "relevance";

    private static SearchSort sort(String value) {
        return switch (value) {
            case "title" -> SearchSort.TITLE;
            case "-modifiedAt" -> SearchSort.MODIFIED_DESC;
            case "pageId" -> SearchSort.PAGE_ID;
            default -> throw new InvalidQueryException("sort kennt nur title, -modifiedAt, pageId und relevance");
        };
    }

    private static Instant instant(String name, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidQueryException(name + " ist kein ISO-8601-Zeitpunkt in UTC");
        }
    }

    /**
     * Ein Stapel Suchtreffer.
     *
     * @param scope    {@code source} mit {@code sourceId}, {@code global} ohne (#114)
     * @param sourceId die angefragte Quelle; global {@code null}, denn die Treffer stammen aus mehreren Quellen
     * @param origin   immer {@code cache}: Titel- und Metadatensuche im Index
     * @param index    gelesener Indexstand; global {@code null}, wenn keine lesbare Quelle im Index liegt
     * @param reranker nur bei {@code sort=relevance}: Kennung des Rerankers, {@code none} ohne Modell
     * @param searchRun nur bei {@code sort=relevance}: Kennung des Laufs im Suchlauf-Protokoll
     *                  ({@code GET /api/v1/search/runs/{id}}), wenn protokolliert
     */
    public record SearchResponse(@Schema(allowableValues = {"source", "global"}) String scope,
                                 @Schema(nullable = true) String sourceId, String origin,
                                 @Schema(nullable = true) IndexResponse index, String metadataHashSchema,
                                 String attributesHashSchema, List<HitResponse> hits,
                                 @Schema(nullable = true) String nextCursor, boolean complete,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String reranker,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String searchRun) {

        static SearchResponse of(SearchResults results) {
            return new SearchResponse(scope(results.sourceId()), results.sourceId(),
                    "cache", IndexResponse.of(results.index()), MetadataHash.SCHEMA,
                    AttributesHash.SCHEMA, results.hits().stream().map(HitResponse::of).toList(),
                    results.nextCursor(), results.complete(), null, null);
        }

        static SearchResponse of(RelevanceSearch.RelevanceResults results) {
            return new SearchResponse(scope(results.sourceId()), results.sourceId(), "cache",
                    IndexResponse.of(results.index()), MetadataHash.SCHEMA, AttributesHash.SCHEMA,
                    results.hits().stream().map(HitResponse::of).toList(), null, results.complete(),
                    results.reranker(), results.searchRun());
        }

        static String scope(String sourceId) {
            return sourceId == null ? "global" : "source";
        }
    }

    /**
     * Ein Stapel der Schlagwortsuche ({@code text}).
     *
     * @param scope      {@code source} mit {@code sourceId}, {@code global} ohne
     * @param origin     {@code cache}: die Quellen finden, gefundene Seiten werden in den Cache geladen und dort gesucht
     *                   (ADR 0013); {@code source} nur ohne Cache, dann Treffer aus der Suche der Quellen
     * @param sources    je Quelle, ob sie gesucht hat
     * @param nextCursor für weitere Treffer mit denselben Parametern, {@code null}, wenn nichts mehr folgt
     * @param complete   nichts folgt mehr, und jede Quelle ließ sich fragen und jeder Treffer prüfen
     * @param loading    gefundene Seiten, die beim Antworten noch nicht im Cache lagen; sie kommen mit nextCursor
     * @param found      vorläufige Treffer: Funde der Quellen mit Titel und Auszug, nach Titel und Auszug bewertet, noch
     *                   nicht aus dem Cache; ein Fund verschwindet hier, sobald er in {@code hits} steht
     * @param progress   Stand des Ladens der besten Funde; mit {@code GET /api/v1/search/progress/{id}} abfragen und
     *                   neu suchen, sobald {@code loaded} steigt
     * @param searchRun  Kennung des Laufs im Suchlauf-Protokoll ({@code GET /api/v1/search/runs/{id}}) oder
     *                   {@code null}, wenn nicht protokolliert
     */
    public record KeywordResponse(@Schema(allowableValues = {"source", "global"}) String scope,
                                  @Schema(nullable = true) String sourceId,
                                  @Schema(allowableValues = {"cache", "source"}) String origin,
                                  List<KeywordHitResponse> hits, List<SourceStateResponse> sources,
                                  @Schema(nullable = true) String nextCursor, boolean complete, int loading,
                                  List<KeywordHitResponse> found,
                                  @Schema(nullable = true) ProgressResponse progress,
                                  @Schema(nullable = true) String searchRun) {

        static KeywordResponse of(boolean fromCache, KeywordSearch.Results results) {
            return new KeywordResponse(SearchResponse.scope(results.sourceId()), results.sourceId(),
                    fromCache ? "cache" : "source", results.hits().stream().map(KeywordHitResponse::of).toList(),
                    results.sources().stream().map(SourceStateResponse::of).toList(), results.nextCursor(),
                    results.complete(), results.loading(),
                    results.found().stream().map(KeywordHitResponse::of).toList(),
                    results.progress() == null ? null : ProgressResponse.of(results.progress()),
                    results.searchRun());
        }
    }

    /**
     * Wie weit die besten Funde einer Suche geladen sind.
     *
     * @param total  so viele lädt der Stapel
     * @param loaded davon im Cache
     * @param failed    davon nicht ladbar oder abgebrochen
     * @param cancelled abgebrochen
     * @param done      nichts lädt mehr: die Suche ist abgeschlossen
     */
    public record ProgressResponse(String id, int total, int loaded, int failed, boolean cancelled, boolean done) {

        static ProgressResponse of(KeywordSearch.Progress progress) {
            return new ProgressResponse(progress.id(), progress.total(), progress.loaded(), progress.failed(),
                    progress.cancelled(), progress.done());
        }
    }

    /**
     * Ein Treffer der Schlagwortsuche; Kopfdaten live von der Quelle, Auszug aus dem Cache. Den Inhalt liefert
     * {@code GET /api/v1/sources/{sourceId}/pages/{pageId}}.
     *
     * @param snippet    Textauszug als Klartext, leer, wenn es keinen gibt
     * @param matchedIn  Trefferart: {@code page} oder {@code attachment}, wenn die Fundstelle im Text eines Anhangs der
     *                   Seite liegt; der Treffer ist immer die Seite und gilt mit ihren Rechten
     * @param attachment bei {@code attachment} der Anhang, sonst {@code null}
     */
    public record KeywordHitResponse(String sourceId, String pageId, @Schema(nullable = true) String spaceKey,
                                     String title, String snippet,
                                     @Schema(nullable = true) URI url, @Schema(nullable = true) String version,
                                     @Schema(nullable = true) Instant modifiedAt,
                                     @Schema(nullable = true) String parentPageId,
                                     @Schema(allowableValues = {"page", "attachment"}) String matchedIn,
                                     @Schema(nullable = true) AttachmentMatchResponse attachment) {

        static KeywordHitResponse of(KeywordSearch.Hit hit) {
            return new KeywordHitResponse(hit.sourceId(), hit.pageId(), hit.spaceKey(), hit.title(), hit.snippet(),
                    hit.url(), hit.version(), hit.modifiedAt(), hit.parentPageId(), matchKind(hit),
                    AttachmentMatchResponse.of(hit.attachment()));
        }
    }

    /** Trefferart: {@code page} (Seite) oder {@code attachment} (Text eines Anhangs der Seite, #183). */
    static String matchKind(KeywordSearch.Hit hit) {
        return hit.matchedIn().name().toLowerCase(Locale.ROOT);
    }

    /**
     * Der Anhang eines Anhangstreffers; Text unter
     * {@code GET /api/v1/sources/{sourceId}/pages/{pageId}/assets/{attachmentId}/text}.
     *
     * @param title Dateiname
     */
    public record AttachmentMatchResponse(String attachmentId, String title) {

        static AttachmentMatchResponse of(KeywordSearch.AttachmentMatch attachment) {
            return attachment == null ? null : new AttachmentMatchResponse(attachment.attachmentId(),
                    attachment.title());
        }
    }

    /**
     * Ob eine Quelle gesucht hat.
     *
     * @param status {@code searched}, {@code indexed} (kann nicht selbst suchen, gesucht hat der Volltextindex des
     *               Caches), {@code not-supported} (kann nicht selbst suchen, kein Index) oder {@code unavailable}
     * @param reason bei {@code unavailable} der Grund, z. B. {@code UNAVAILABLE} oder {@code ACCESS_DENIED}
     */
    public record SourceStateResponse(String sourceId,
                                      @Schema(allowableValues = {"searched", "indexed", "not-supported", "unavailable"})
                                      String status, @Schema(nullable = true) String reason) {

        static SourceStateResponse of(KeywordSearch.SourceState state) {
            return new SourceStateResponse(state.sourceId(),
                    state.status().name().toLowerCase(Locale.ROOT).replace('_', '-'), state.reason());
        }
    }

    /**
     * Stand des Suchindex, aus dem alle Treffer dieser Suche stammen.
     *
     * @param generation  Generation der Quelle bzw. global aller gelesenen Quellen zusammen
     * @param committedAt jüngster Commit dieser Quellen
     */
    public record IndexResponse(String generation, Instant committedAt) {

        static IndexResponse of(SearchIndexState state) {
            return state == null ? null : new IndexResponse(state.generation(), state.committedAt());
        }
    }

    /**
     * Ein Treffer: seine Quelle, dieselben Felder wie in der Seitenliste und der Cache-Stand seines Space.
     *
     * @param sourceId Quelle des Treffers; mit {@code page.pageId} eindeutig, auch global
     */
    public record HitResponse(String sourceId, PageEntry page, CacheResponse cache,
                              @JsonInclude(JsonInclude.Include.NON_NULL) RelevanceResponse relevance) {

        static HitResponse of(SearchHit hit) {
            return new HitResponse(hit.page().header().key().sourceId(), PageEntry.of(hit.page()),
                    CacheResponse.of(hit.generation()), null);
        }

        static HitResponse of(RelevanceSearch.RankedHit ranked) {
            RelevanceSearch.Relevance relevance = ranked.relevance();
            return new HitResponse(ranked.hit().page().header().key().sourceId(), PageEntry.of(ranked.hit().page()),
                    CacheResponse.of(ranked.hit().generation()),
                    relevance == null ? null : new RelevanceResponse(relevance.similarity(),
                            new ChunkResponse(relevance.charStart(), relevance.charEnd()),
                            relevance.freshness() == RelevanceSearch.Freshness.CURRENT ? "current" : "stale"));
        }
    }

    /**
     * Ähnlichkeit eines Treffers zur Anfrage, nur bei {@code sort=relevance} und {@code content}-Freigabe.
     *
     * @param similarity Kosinusähnlichkeit des ähnlichsten Abschnitts, -1 bis 1
     * @param chunk      dieser Abschnitt als Zeichenbereich der Textprojektion
     * @param freshness  {@code current} oder {@code stale}, wenn das Embedding aus einer älteren Revision stammt
     */
    public record RelevanceResponse(double similarity, ChunkResponse chunk,
                                    @Schema(allowableValues = {"current", "stale"}) String freshness) {
    }

    /** Zeichenbereich {@code [charStart, charEnd)} in der Textprojektion {@code confluence-storage-text/v1}. */
    public record ChunkResponse(int charStart, int charEnd) {
    }
}
