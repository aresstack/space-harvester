package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.PageHeader;
import com.aresstack.spaceharvester.application.query.PageListing;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.query.SpaceTree;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.MetadataHash;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
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
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

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

    private final PageQueries queries;

    public PageQueryController(PageQueries queries) {
        this.queries = queries;
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
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Space-Key", example = "DEV") @PathVariable String spaceKey,
            @Parameter(description = "nextCursor der vorigen Antwort; leer für den ersten Stapel")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Größe des Stapels, 1 bis " + PageQueries.MAX_PAGE_LIMIT)
            @RequestParam(defaultValue = "" + PageQueries.MAX_PAGE_LIMIT) int limit) {
        return PagesResponse.of(queries.pages(sourceId, spaceKey, cursor, limit));
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
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Space-Key", example = "DEV") @PathVariable String spaceKey,
            @Parameter(description = "Zusätzliche Angaben je Knoten. Derzeit nur metadata (title, version, "
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
        return TreeResponse.of(queries.tree(sourceId, spaceKey), metadata);
    }

    @GetMapping("/{sourceId}/pages/{pageId}")
    @Operation(summary = "Native Payload einer Seite aus dem Cache",
            description = "Die Antwort der Quelle Byte für Byte, ohne Harvester-Metadaten im Body. Cache-Stand und "
                    + "Revision stehen in Headern; ETag ist rawSha256 der Payload, If-None-Match ergibt 304. "
                    + "Kanonische Route ohne Space-Key: eine verschobene Seite behält ihre pageId. "
                    + "refresh=true verlangt eine Prüfung gegen die Quelle und liefert nie stattdessen den "
                    + "Cache-Stand; solange diese Prüfung nicht angebunden ist, antwortet die Route darauf mit 501.")
    @ApiResponse(responseCode = "200", description = "Native Payload der Seite",
            headers = {
                    @Header(name = "ETag", description = "\"sha256:<hex>\" über die Payload-Bytes"),
                    @Header(name = CACHE_GENERATION, description = "Cache-Generation des Space"),
                    @Header(name = CACHE_STATE, description = "confirmed, incomplete oder stale"),
                    @Header(name = CACHE_SYNCED_AT, description = "Ende des Abgleichs, falls bekannt"),
                    @Header(name = CACHE_LAST_CHECKED_AT, description = "Letzte Prüfung gegen die Quelle, falls "
                            + "bekannt"),
                    @Header(name = PAGE_REVISION, description = "Revision der Quelle, falls bekannt"),
                    @Header(name = NATIVE_FORMAT, description = "Format und Abrufform der Payload")})
    @ApiResponse(responseCode = "304", description = "Payload unverändert gegenüber If-None-Match")
    @ApiResponse(responseCode = "400", description = "Nicht unterstützte view (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found) oder Seite nicht im "
            + "Cache bzw. nicht sichtbar (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "501", description = "refresh=true, aber keine Prüfung gegen die Quelle angebunden "
            + "(refresh-unavailable)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Cache nicht eingerichtet oder nicht lesbar (cache-unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<byte[]> page(
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "ID der Seite in der Quelle (Confluence-Content-ID)", example = "123456")
            @PathVariable String pageId,
            @Parameter(description = "true: vor der Auslieferung gegen die Quelle prüfen; nie stiller Rückfall auf "
                    + "den Cache") @RequestParam(defaultValue = "false") boolean refresh,
            @Parameter(description = "Ansicht; derzeit nur native. Der Container mit data, metadata und "
                    + "enrichments folgt.", schema = @Schema(allowableValues = {"native"}))
            @RequestParam(defaultValue = "native") String view,
            WebRequest request) {
        if (!"native".equals(view)) {
            throw new InvalidQueryException("view=" + view + " wird noch nicht unterstützt");
        }
        CachedPage page = queries.page(sourceId, pageId, refresh);
        PageSnapshot snapshot = page.snapshot();
        NativePayload payload = snapshot.payload();
        String etag = "\"" + payload.rawSha256() + "\"";
        if (request.checkNotModified(etag)) {
            return null;
        }
        CacheGeneration generation = page.generation();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .contentType(MediaType.parseMediaType(payload.mediaType()))
                .header(CACHE_GENERATION, generation.id())
                .header(CACHE_STATE, state(generation))
                .header(NATIVE_FORMAT, payload.nativeFormat());
        if (generation.syncedAt() != null) {
            response.header(CACHE_SYNCED_AT, generation.syncedAt().toString());
        }
        if (generation.lastCheckedAt() != null) {
            response.header(CACHE_LAST_CHECKED_AT, generation.lastCheckedAt().toString());
        }
        if (snapshot.revision() != null) {
            response.header(PAGE_REVISION, snapshot.revision());
        }
        return response.body(payload.bytes());
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
     * Eine Seite der Liste, ohne Inhalt. Felder wie in der Live-Metadatenliste, dazu {@code rawSha256}.
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
     * @param rawSha256      {@code sha256:<hex>} über die gespeicherte native Payload; zugleich ihr ETag
     */
    public record PageEntry(String pageId, String resourceId, String title, String spaceKey,
                            @Schema(nullable = true) String parentPageId,
                            @Schema(nullable = true) String version,
                            @Schema(nullable = true) Instant modifiedAt,
                            @Schema(nullable = true) URI url, Map<String, String> attributes,
                            String metadataHash, String attributesHash, String rawSha256) {

        static PageEntry of(PageHeader header) {
            PageMetadata page = header.metadata();
            return new PageEntry(page.pageId(), page.resourceId(), page.title(), page.partitionKey(),
                    page.parentPageId(), page.version(), page.modifiedAt(), page.url(), page.attributes(),
                    header.metadataHash(), header.attributesHash(), header.rawSha256());
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
            Map<String, PageHeader> headers = metadata ? tree.headers() : Map.of();
            return new TreeResponse(tree.sourceId(), tree.spaceKey(), "cache", CacheResponse.of(tree.generation()),
                    tree.tree().nodeCount(), tree.tree().depth(),
                    tree.tree().roots().stream().map(root -> TreeNode.of(root, headers)).toList(),
                    tree.tree().anomalies().stream().map(TreeAnomaly::of).toList());
        }
    }

    /**
     * Ein Knoten des Baums. {@code title}, {@code version} und {@code modifiedAt} nur mit {@code include=metadata}.
     *
     * @param id       pageId
     * @param children Kinder, aufsteigend nach pageId
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TreeNode(String id, String title, String version, Instant modifiedAt, List<TreeNode> children) {

        static TreeNode of(PageTree.Node node, Map<String, PageHeader> headers) {
            PageHeader header = headers.get(node.pageId());
            List<TreeNode> children = node.children().stream().map(child -> of(child, headers)).toList();
            if (header == null) {
                return new TreeNode(node.pageId(), null, null, null, children);
            }
            PageMetadata page = header.metadata();
            return new TreeNode(node.pageId(), page.title(), page.version(), page.modifiedAt(), children);
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
