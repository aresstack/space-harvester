package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageListing;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.query.SpaceTree;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.MetadataHash;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.ErrorResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cachegestützte Abfragen (#27): flache Seitenliste, ID-Baum und native Einzelseite. Liest nur aus dem Cache, nie
 * live aus der Quelle; jede Antwort nennt Generation und Zustand des Cache-Stands.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Seiten (Cache)", description = "Seiten, Baum und native Payload aus dem Cache des Harvesters")
public class PageQueryController {

    static final String CACHE_GENERATION = "X-Cache-Generation";
    static final String CACHE_STATE = "X-Cache-State";
    static final String CACHE_SYNCED_AT = "X-Cache-Synced-At";
    static final String CACHE_LAST_CHECKED_AT = "X-Cache-Last-Checked-At";
    static final String PAGE_REVISION = "X-Page-Revision";
    static final String NATIVE_FORMAT = "X-Native-Format";
    static final String PAGE_ORIGIN = "X-Page-Origin";
    static final String PAGE_REFRESH = "X-Page-Refresh";
    static final String PAGE_CHECKED_AT = "X-Page-Checked-At";

    private final PageQueries queries;
    private final Enrichments enrichments;
    private final ObjectMapper json;

    public PageQueryController(PageQueries queries, Enrichments enrichments, ObjectMapper json) {
        this.queries = queries;
        this.enrichments = enrichments;
        this.json = json;
    }

    @GetMapping("/{sourceId}/spaces/{spaceKey}/pages")
    @Operation(summary = "Flache Seitenliste eines Space aus dem Cache, ohne Inhalte, stapelweise",
            description = "Aus dem Cache (origin=cache), nie live. Alle Stapel einer Abfrage stammen aus derselben "
                    + "Cache-Generation (cache.generation); ersetzt ein neuer Abgleich sie, endet die Abfrage mit "
                    + "409 und beginnt ohne cursor neu. Aufsteigend nach pageId. Ein Stapel kann kürzer als limit "
                    + "oder leer sein, auch wenn noch Seiten folgen: nextCursor übergeben, bis er null ist "
                    + "(complete=true). Dieselben Seiten wie im Baum derselben Generation.")
    @ApiResponse(responseCode = "200", description = "Ein Stapel Seiten")
    @ApiResponse(responseCode = "400", description = "Ungültiges limit oder ungültiger bzw. fremder cursor "
            + "(invalid-request)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Keine metadata-Freigabe für diesen Space oder keine Quelle mit dieser ID (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found) oder Space nicht im "
            + "Cache (cache-miss)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Der cursor gehört zu einer ersetzten Cache-Generation "
            + "(cache-generation-changed)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Cache nicht eingerichtet oder nicht lesbar (cache-unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PagesResponse pages(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Space-Key", example = "DEV") @PathVariable String spaceKey,
            @Parameter(description = "nextCursor der vorigen Antwort; leer für den ersten Stapel")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Größe des Stapels, 1 bis " + PageQueries.MAX_PAGE_LIMIT)
            @RequestParam(defaultValue = "" + PageQueries.MAX_PAGE_LIMIT) int limit) {
        return PagesResponse.of(queries.pages(caller, sourceId, spaceKey, cursor, limit));
    }

    @GetMapping("/{sourceId}/spaces/{spaceKey}/tree")
    @Operation(summary = "Seitenbaum eines Space aus dem Cache, nur IDs",
            description = "Aus dem Cache (origin=cache), nie live. Hierarchie als roots[] mit id und children[], "
                    + "ohne Seiteninhalte; mehrere Wurzeln sind normal. Jede Seite erscheint genau einmal. Seiten, "
                    + "deren Elternseite nicht im Baum ist (nicht geerntet, gelöscht, verschoben oder nicht "
                    + "sichtbar), und Elternzyklen werden zu Wurzeln und in anomalies genannt. Kinder und Wurzeln "
                    + "aufsteigend nach pageId. Größere Bäume liefert der Export.")
    @ApiResponse(responseCode = "200", description = "Der Baum")
    @ApiResponse(responseCode = "400", description = "Nicht unterstützter include-Wert (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Keine metadata-Freigabe für diesen Space oder keine Quelle mit dieser ID (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found) oder Space nicht im "
            + "Cache (cache-miss)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Cache-Generation während des Lesens ersetzt "
            + "(cache-generation-changed)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Baum zu groß oder zu tief für eine synchrone Antwort "
            + "(tree-too-large)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Cache nicht eingerichtet oder nicht lesbar (cache-unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public TreeResponse tree(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Space-Key", example = "DEV") @PathVariable String spaceKey,
            @Parameter(description = "Zusätzliche Angaben je Knoten. Derzeit nur metadata (title, version, url, "
                    + "modifiedAt); content und enrichments folgen mit eigenen Grenzen.",
                    array = @ArraySchema(schema = @Schema(allowableValues = {"metadata"})))
            @RequestParam(required = false) List<String> include) {
        boolean metadata = false;
        for (String value : include == null ? List.<String>of() : include) {
            switch (value.trim()) {
                case "metadata" -> metadata = true;
                case "" -> {
                }
                case "content", "enrichments" -> throw new InvalidQueryException(
                        "include=" + value.trim() + " wird noch nicht unterstützt");
                default -> throw new InvalidQueryException("include kennt nur metadata");
            }
        }
        return TreeResponse.of(queries.tree(caller, sourceId, spaceKey), metadata);
    }

    @GetMapping("/{sourceId}/pages/{pageId}")
    @Operation(summary = "Eine Seite aus dem Cache: native Payload oder erweiterter Container",
            description = "view=native (Standard): die Antwort der Quelle Byte für Byte, ohne Harvester-Metadaten im "
                    + "Body. Cache-Stand und Revision stehen in Headern; ETag ist rawSha256 der Payload, "
                    + "If-None-Match ergibt 304. view=extended: JSON-Container mit data (die Payload als JSON, "
                    + "semantisch gleich, aber nicht bytegenau), metadata (aktuelle Kopfdaten, Hashes, Herkunft der "
                    + "Payload) und enrichments (abgeleitete Angaben der Prozessoren mit Version, Eingabe-Hash und "
                    + "Aktualität, #29); Metadaten werden nie in data "
                    + "geschrieben. Beide Ansichten verlangen die content-Freigabe. "
                    + "Kanonische Route ohne Space-Key: eine verschobene Seite behält ihre pageId. "
                    + "refresh=true prüft die Seite vor der Auslieferung gegen die Quelle und liefert nie stattdessen "
                    + "den Cache-Stand: unverändert (current) kommt der Cache-Stand, neue Kopfdaten bei gleicher "
                    + "Revision (header-changed) mit der gespeicherten Payload, eine neue Revision (content-changed) "
                    + "live geladen. Abweichungen vom Cache sind origin=live und Cache-Zustand stale; der Cache selbst "
                    + "ändert sich erst mit dem nächsten Abgleich. Ohne angebundene Prüfung antwortet refresh=true "
                    + "mit 501, bei Fehlern oder Zeitüberschreitung der Quelle mit 502/503.")
    @ApiResponse(responseCode = "200", description = "view=native: native Payload der Seite (Medientyp der Quelle); "
            + "view=extended: ExtendedPageResponse",
            content = {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(oneOf = {ExtendedPageResponse.class},
                            description = "view=extended; bei view=native die unveränderte Payload"))},
            headers = {
                    @Header(name = "ETag", description = "\"sha256:<hex>\" über die Payload-Bytes"),
                    @Header(name = PAGE_ORIGIN, description = "cache oder live (nach refresh=true vom Cache "
                            + "abweichend)"),
                    @Header(name = PAGE_REFRESH, description = "Nur mit refresh=true: current, header-changed oder "
                            + "content-changed"),
                    @Header(name = PAGE_CHECKED_AT, description = "Nur mit refresh=true: Zeitpunkt der Prüfung"),
                    @Header(name = CACHE_GENERATION, description = "Cache-Generation des Space; fehlt, wenn die "
                            + "Seite nur live vorliegt"),
                    @Header(name = CACHE_STATE, description = "confirmed, incomplete oder stale; stale auch, wenn "
                            + "refresh=true eine Abweichung fand"),
                    @Header(name = CACHE_SYNCED_AT, description = "Ende des Abgleichs, falls bekannt"),
                    @Header(name = CACHE_LAST_CHECKED_AT, description = "Letzte Prüfung gegen die Quelle, falls "
                            + "bekannt"),
                    @Header(name = PAGE_REVISION, description = "Revision der Payload, falls bekannt; aktuelle "
                            + "Kopfdaten können neuer sein"),
                    @Header(name = NATIVE_FORMAT, description = "Format und Abrufform der Payload")})
    @ApiResponse(responseCode = "304", description = "view=native: Payload unverändert gegenüber If-None-Match")
    @ApiResponse(responseCode = "400", description = "Unbekannte view (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Keine content-Freigabe für irgendeinen Space dieser Quelle oder keine Quelle mit dieser ID (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Seite nicht im Cache, bei refresh=true der Quelle unbekannt, "
            + "oder ihr Space ohne content-Freigabe, nicht unterscheidbar (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "view=extended, aber die gespeicherte Payload ist kein JSON "
            + "(payload-not-json); view=native bleibt möglich",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "refresh=true: Quelle lehnt ab oder antwortet ungültig "
            + "(source-access-denied, source-invalid-response)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "501", description = "refresh=true, aber keine Prüfung gegen die Quelle angebunden "
            + "(refresh-unavailable)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Cache nicht eingerichtet oder nicht lesbar (cache-unavailable); "
            + "bei refresh=true Quelle nicht erreichbar oder Frist überschritten (source-unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<?> page(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "ID der Seite in der Quelle (Confluence-Content-ID)", example = "123456")
            @PathVariable String pageId,
            @Parameter(description = "true: vor der Auslieferung gegen die Quelle prüfen; nie stiller Rückfall auf "
                    + "den Cache") @RequestParam(defaultValue = "false") boolean refresh,
            @Parameter(description = "native: Payload Byte für Byte; extended: Container mit data, metadata und "
                    + "enrichments", schema = @Schema(allowableValues = {"native", "extended"}))
            @RequestParam(defaultValue = "native") String view) {
        requireView(view);
        return render(sourceId, queries.page(caller, sourceId, pageId, refresh), view);
    }

    @GetMapping("/{sourceId}/pages")
    @Operation(summary = "Eine Seite über ihre URL: wie GET …/pages/{pageId}",
            description = "Die URL muss die Seiten-ID enthalten: …?pageId=123 (viewpage.action), "
                    + "…/spaces/KEY/pages/123[/Titel] oder ein Kurzlink …/x/AgAB. Formen ohne ID (/display/KEY/Titel) "
                    + "werden nicht aufgelöst (400). Eine URL auf einen anderen Server als die bekannte URL der Seite "
                    + "ergibt cache-miss. Antworten, Header, view und refresh wie bei der Route mit pageId.")
    @ApiResponse(responseCode = "200", description = "Wie GET …/pages/{pageId}")
    @ApiResponse(responseCode = "400", description = "url ohne Seiten-ID oder unbekannte view (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Keine content-Freigabe für irgendeinen Space dieser Quelle "
            + "(access-denied)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Wie GET …/pages/{pageId}, oder URL eines anderen Servers "
            + "(cache-miss)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<?> pageByUrl(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "URL der Seite mit Seiten-ID",
                    example = "https://wiki.example.org/pages/viewpage.action?pageId=123456", required = true)
            @RequestParam(required = false) String url,
            @Parameter(description = "true: vor der Auslieferung gegen die Quelle prüfen")
            @RequestParam(defaultValue = "false") boolean refresh,
            @Parameter(description = "native oder extended", schema = @Schema(allowableValues = {"native", "extended"}))
            @RequestParam(defaultValue = "native") String view) {
        requireView(view);
        return render(sourceId, queries.pageByUrl(caller, sourceId, url, refresh), view);
    }

    private static void requireView(String view) {
        if (!"native".equals(view) && !"extended".equals(view)) {
            throw new InvalidQueryException("view kennt nur native und extended");
        }
    }

    private ResponseEntity<?> render(String sourceId, DeliveredPage page, String view) {
        if ("extended".equals(view)) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noCache().cachePrivate())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ExtendedPageResponse.of(sourceId, page, data(page.snapshot().payload()),
                            EnrichmentController.entries(enrichments.view(page.snapshot()), json)));
        }
        PageSnapshot snapshot = page.snapshot();
        NativePayload payload = snapshot.payload();
        String etag = "\"" + payload.rawSha256() + "\"";
        // Kein eigenes checkNotModified: Spring beantwortet If-None-Match anhand des ETag dieser Antwort mit 304 und
        // behält dabei die übrigen Kopfzeilen, damit auch eine 304 nach refresh=true X-Page-Refresh trägt.
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .contentType(MediaType.parseMediaType(payload.mediaType()))
                .header(PAGE_ORIGIN, origin(page))
                .header(NATIVE_FORMAT, payload.nativeFormat());
        if (page.refresh() != null) {
            response.header(PAGE_REFRESH, refresh(page.refresh()));
            response.header(PAGE_CHECKED_AT, page.checkedAt().toString());
        }
        CacheGeneration generation = page.generation();
        if (generation != null) {
            response.header(CACHE_GENERATION, generation.id())
                    .header(CACHE_STATE, page.live() ? "stale" : state(generation));
            if (generation.syncedAt() != null) {
                response.header(CACHE_SYNCED_AT, generation.syncedAt().toString());
            }
            if (generation.lastCheckedAt() != null) {
                response.header(CACHE_LAST_CHECKED_AT, generation.lastCheckedAt().toString());
            }
        }
        if (snapshot.revision() != null) {
            response.header(PAGE_REVISION, snapshot.revision());
        }
        return response.body(payload.bytes());
    }

    /**
     * Die Payload als JSON-Baum. Dezimalzahlen bleiben {@link java.math.BigDecimal}, damit keine Stellen verloren
     * gehen; bytegenau ist nur {@code view=native}.
     */
    private JsonNode data(NativePayload payload) {
        if (isJson(payload.mediaType())) {
            try {
                return json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(payload.bytes());
            } catch (JacksonException e) {
                // Die Ursache nennt Ausschnitte der Payload; sie gehört weder in die Antwort noch ins Log.
            }
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "Die gespeicherte Payload ist kein JSON; nur view=native ist möglich.");
        problem.setType(URI.create("https://github.com/aresstack/space-harvester/problems/payload-not-json"));
        problem.setTitle("Payload ist kein JSON");
        throw new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT, problem, null);
    }

    private static boolean isJson(String mediaType) {
        try {
            MediaType type = MediaType.parseMediaType(mediaType);
            return type.isCompatibleWith(MediaType.APPLICATION_JSON) || type.getSubtype().endsWith("+json");
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private static String origin(DeliveredPage page) {
        return page.live() ? "live" : "cache";
    }

    private static String refresh(PageFreshness.Outcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String state(CacheGeneration generation) {
        return generation.state().name().toLowerCase(Locale.ROOT);
    }

    /**
     * Cache-Stand einer Antwort.
     *
     * @param generation    Kennung der Cache-Generation; alle Stapel einer Abfrage tragen dieselbe
     * @param state         {@code confirmed} (vollständiger Abgleich), {@code incomplete} (Abgleich unvollständig) oder
     *                      {@code stale} (Prüfung gegen die Quelle fehlgeschlagen oder überfällig)
     * @param syncedAt      Ende des Abgleichs oder {@code null}, wenn keiner abgeschlossen ist
     * @param lastCheckedAt letzte erfolgreiche Prüfung gegen die Quelle oder {@code null}, wenn unbekannt
     */
    public record CacheResponse(String generation,
                                @Schema(allowableValues = {"confirmed", "incomplete", "stale"}) String state,
                                @Schema(nullable = true) Instant syncedAt,
                                @Schema(nullable = true) Instant lastCheckedAt) {

        static CacheResponse of(CacheGeneration generation) {
            return new CacheResponse(generation.id(), PageQueryController.state(generation), generation.syncedAt(),
                    generation.lastCheckedAt());
        }
    }

    /**
     * Ein Stapel der Seitenliste eines Space.
     *
     * @param origin     {@code cache}: aus dem Cache des Harvesters, nicht live aus der Quelle
     * @param nextCursor Cursor für den nächsten Stapel oder {@code null}, wenn vollständig
     * @param complete   {@code true}, wenn dies der letzte Stapel ist
     */
    public record PagesResponse(String sourceId, String spaceKey, String origin, CacheResponse cache,
                                String metadataHashSchema, String attributesHashSchema, List<PageEntry> pages,
                                @Schema(nullable = true) String nextCursor, boolean complete) {

        static PagesResponse of(PageListing listing) {
            return new PagesResponse(listing.sourceId(), listing.spaceKey(), "cache",
                    CacheResponse.of(listing.generation()), MetadataHash.SCHEMA, AttributesHash.SCHEMA,
                    listing.pages().stream().map(PageEntry::of).toList(), listing.nextCursor(), listing.complete());
        }
    }

    /**
     * Eine Seite der Liste, ohne Inhalt, mit ihren aktuellen Kopfdaten. Felder wie in der Live-Metadatenliste, dazu
     * {@code rawSha256}.
     *
     * @param pageId         ID der Seite in der Quelle
     * @param resourceId     quellübergreifende Ressourcen-ID
     * @param spaceKey       Space laut Cache
     * @param parentPageId   ID der Elternseite oder {@code null} für eine Wurzelseite
     * @param version        Versionskennung oder {@code null}, wenn unbekannt
     * @param modifiedAt     letzte Änderung oder {@code null}, wenn unbekannt
     * @param url            Adresse in der Quelle oder {@code null}, wenn unbekannt
     * @param attributes     weitere Metadaten der Quelle, z. B. {@code confluence.labels}
     * @param metadataHash   {@code sha256:<hex>} nach {@code metadataHashSchema}
     * @param attributesHash {@code sha256:<hex>} nach {@code attributesHashSchema}
     * @param rawSha256      {@code sha256:<hex>} über die gespeicherte native Payload, zugleich ihr ETag, oder
     *                       {@code null}, wenn noch keine Payload gespeichert ist; die Kopfdaten können neuer sein als
     *                       die Payload
     */
    public record PageEntry(String pageId, String resourceId, String title, String spaceKey,
                            @Schema(nullable = true) String parentPageId,
                            @Schema(nullable = true) String version,
                            @Schema(nullable = true) Instant modifiedAt,
                            @Schema(nullable = true) URI url, Map<String, String> attributes,
                            String metadataHash, String attributesHash,
                            @Schema(nullable = true) String rawSha256) {

        static PageEntry of(ListedPage listed) {
            PageMetadata page = listed.header().metadata();
            return new PageEntry(page.pageId(), page.resourceId(), page.title(), page.partitionKey(),
                    page.parentPageId(), page.version(), page.modifiedAt(), page.url(), page.attributes(),
                    listed.header().metadataHash(), listed.header().attributesHash(), listed.rawSha256());
        }
    }

    /**
     * Erweiterter Container einer Seite ({@code view=extended}). Native Daten und Harvester-Angaben bleiben getrennt.
     *
     * @param origin      {@code cache}: aus dem Cache des Harvesters, nicht live aus der Quelle
     * @param data        die gespeicherte Payload als JSON, unverändert in der Struktur; bytegenau nur über
     *                    {@code view=native}
     * @param metadata    Kopfdaten und Herkunft der Payload
     * @param enrichments Anreicherungen (#29) aller Prozessoren, aufsteigend nach Prozessor, mit ihrer Aktualität
     *                    gegenüber {@code data}
     */
    public record ExtendedPageResponse(String sourceId,
                                       @Schema(allowableValues = {"cache", "live"}) String origin,
                                       @Schema(nullable = true, description = "Fehlt, wenn die Seite nur live vorliegt")
                                       CacheResponse cache,
                                       @Schema(nullable = true, description = "Nur mit refresh=true")
                                       RefreshResponse refresh,
                                       @Schema(type = "object", description = "Native Antwort der Quelle als JSON")
                                       JsonNode data,
                                       ExtendedMetadata metadata,
                                       List<EnrichmentController.EnrichmentEntry> enrichments) {

        static ExtendedPageResponse of(String sourceId, DeliveredPage page, JsonNode data,
                                       List<EnrichmentController.EnrichmentEntry> enrichments) {
            CacheResponse cache = page.generation() == null ? null : CacheResponse.of(page.generation());
            if (cache != null && page.live()) {
                cache = new CacheResponse(cache.generation(), "stale", cache.syncedAt(), cache.lastCheckedAt());
            }
            RefreshResponse refresh = page.refresh() == null ? null
                    : new RefreshResponse(PageQueryController.refresh(page.refresh()), page.checkedAt());
            return new ExtendedPageResponse(sourceId, PageQueryController.origin(page), cache, refresh, data,
                    ExtendedMetadata.of(page), enrichments);
        }
    }

    /**
     * Ergebnis der Prüfung gegen die Quelle ({@code refresh=true}).
     *
     * @param outcome   current, header-changed oder content-changed
     * @param checkedAt Zeitpunkt der Prüfung
     */
    public record RefreshResponse(
            @Schema(allowableValues = {"current", "header-changed", "content-changed"}) String outcome,
            Instant checkedAt) {
    }

    /**
     * Harvester-Angaben zu einer Seite im erweiterten Container.
     *
     * @param page               aktuelle Kopfdaten wie in der Seitenliste; können neuer sein als die Payload
     * @param payloadRevision    Revision der Quelle, zu der {@code data} gehört, oder {@code null}, wenn unbekannt
     * @param payloadRetrievedAt Zeitpunkt, zu dem die Quelle die Payload geliefert hat
     * @param headerObservedAt   Zeitpunkt, zu dem die Quelle die aktuellen Kopfdaten geliefert hat
     * @param nativeFormat       Format und Abrufform der Payload
     */
    public record ExtendedMetadata(String metadataHashSchema, String attributesHashSchema, PageEntry page,
                                   @Schema(nullable = true) String payloadRevision, Instant payloadRetrievedAt,
                                   Instant headerObservedAt, String nativeFormat) {

        static ExtendedMetadata of(DeliveredPage page) {
            PageSnapshot snapshot = page.snapshot();
            return new ExtendedMetadata(MetadataHash.SCHEMA, AttributesHash.SCHEMA,
                    PageEntry.of(ListedPage.of(page.header(), snapshot)), snapshot.revision(),
                    snapshot.retrievedAt(), page.header().observedAt(), snapshot.payload().nativeFormat());
        }
    }

    /**
     * Der Baum eines Space.
     *
     * @param origin    {@code cache}: aus dem Cache des Harvesters, nicht live aus der Quelle
     * @param nodeCount Anzahl aller Knoten
     * @param depth     größte Tiefe (eine Wurzel ohne Kinder hat Tiefe 1)
     * @param roots     Wurzelknoten
     * @param anomalies Wurzeln, die eine Elternseite angeben, aufsteigend nach pageId
     */
    public record TreeResponse(String sourceId, String spaceKey, String origin, CacheResponse cache, int nodeCount,
                               int depth, List<TreeNode> roots, List<TreeAnomaly> anomalies) {

        static TreeResponse of(SpaceTree tree, boolean metadata) {
            Map<String, ListedPage> pages = metadata ? tree.pages() : Map.of();
            return new TreeResponse(tree.sourceId(), tree.spaceKey(), "cache", CacheResponse.of(tree.generation()),
                    tree.tree().nodeCount(), tree.tree().depth(),
                    tree.tree().roots().stream().map(root -> TreeNode.of(root, pages)).toList(),
                    tree.tree().anomalies().stream().map(TreeAnomaly::of).toList());
        }
    }

    /**
     * Ein Knoten des Baums. {@code title}, {@code version}, {@code modifiedAt} und {@code url} nur mit {@code include=metadata}.
     *
     * @param id       pageId
     * @param children Kinder, aufsteigend nach pageId
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TreeNode(String id, String title, String version, Instant modifiedAt, URI url,
                           List<TreeNode> children) {

        static TreeNode of(PageTree.Node node, Map<String, ListedPage> pages) {
            ListedPage listed = pages.get(node.pageId());
            List<TreeNode> children = node.children().stream().map(child -> of(child, pages)).toList();
            if (listed == null) {
                return new TreeNode(node.pageId(), null, null, null, null, children);
            }
            PageMetadata page = listed.header().metadata();
            return new TreeNode(node.pageId(), page.title(), page.version(), page.modifiedAt(), page.url(), children);
        }
    }

    /**
     * Eine Seite, die als Wurzel erscheint, obwohl sie eine Elternseite angibt.
     *
     * @param pageId Wurzel des abgelösten Teilbaums
     * @param kind   {@code missing-parent}: Elternseite nicht im Baum (nicht geerntet, gelöscht, verschoben oder nicht
     *               sichtbar, bewusst nicht unterschieden); {@code cycle}: Elternzyklus, an dieser Seite aufgebrochen
     */
    public record TreeAnomaly(String pageId,
                              @Schema(allowableValues = {"missing-parent", "cycle"}) String kind) {

        static TreeAnomaly of(PageTree.Anomaly anomaly) {
            return new TreeAnomaly(anomaly.pageId(), switch (anomaly.kind()) {
                case MISSING_PARENT -> "missing-parent";
                case CYCLE -> "cycle";
            });
        }
    }
}
