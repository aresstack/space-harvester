package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.MetadataHash;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SinglePageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Quellen", description = "Konfigurierte Wissensquellen und ihre Spaces")
public class SourcesController {

    private final SourceQueries queries;
    private final AccessGuard access;

    public SourcesController(SourceQueries queries, AccessGuard access) {
        this.queries = queries;
        this.access = access;
    }

    @GetMapping
    @Operation(summary = "Konfigurierte Quellen (ohne Verbindungs- und Zugangsdaten)",
            description = "Nur Quellen, für die der Client eine Metadaten-Freigabe hat.")
    public List<SourceResponse> sources(@Parameter(hidden = true) AccessContext caller) {
        return access.filter(caller, queries.sources(), AccessKind.METADATA,
                source -> ResourceScope.source(source.id())).stream().map(SourceResponse::of).toList();
    }

    @GetMapping("/{sourceId}/spaces")
    @Operation(summary = "Alle Spaces einer Quelle, die mit den konfigurierten Zugangsdaten sichtbar sind",
            description = "Vollständige Liste in der Reihenfolge der Quelle, beschränkt auf die für den Client "
                    + "freigegebenen Spaces.")
    @ApiResponse(responseCode = "200", description = "Spaces der Quelle")
    @ApiResponse(responseCode = "403", description = "Keine Freigabe für diese Quelle oder keine Quelle mit dieser ID "
            + "(access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Keine Quelle mit dieser ID konfiguriert (source-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "Quelle verweigert den Zugriff (source-access-denied) oder "
            + "antwortet fehlerhaft (source-invalid-response)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Quelle nicht erreichbar (source-unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public SpacesResponse spaces(@Parameter(hidden = true) AccessContext caller,
                                 @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
                                 @PathVariable String sourceId) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        return new SpacesResponse(sourceId, access.filter(caller, queries.partitions(sourceId), AccessKind.METADATA,
                partition -> ResourceScope.space(sourceId, partition.key())).stream().map(SpaceResponse::of).toList());
    }

    @GetMapping("/{sourceId}/spaces/{spaceKey}/pages/metadata")
    @Operation(summary = "Metadaten aller Seiten eines Space, ohne Inhalte, stapelweise",
            description = "Live aus der Quelle gelesen (origin=live), kein Cache. Alle Seiten des Space, auch "
                    + "außerhalb des Baums der Startseite und über mehrere Wurzelseiten, ohne Gesamtgrenze: "
                    + "nextCursor an den nächsten Aufruf übergeben, bis er null ist (complete=true). "
                    + "metadataHash ist ein Fingerprint der Verwaltungsmetadaten nach metadataHashSchema, "
                    + "attributesHash einer der weiteren Attribute (z. B. Labels) nach attributesHashSchema; "
                    + "beide sind keine Inhalts-Hashes.")
    @ApiResponse(responseCode = "200", description = "Ein Stapel Seitenmetadaten")
    @ApiResponse(responseCode = "403", description = "Keine Freigabe für diesen Space (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "400", description = "Ungültiges limit (invalid-request) oder ein Cursor, der "
            + "nicht zu diesem Space gehört (source-unsupported)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found) oder unbekannter bzw. "
            + "nicht sichtbarer Space (source-object-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "Quelle verweigert den Zugriff oder antwortet fehlerhaft",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Quelle nicht erreichbar (source-unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PageMetadataResponse pageMetadata(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Space-Key", example = "DEV") @PathVariable String spaceKey,
            @Parameter(description = "nextCursor der vorigen Antwort; leer für den ersten Stapel")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Größe des Stapels, 1 bis " + SourceQueries.MAX_PAGE_METADATA_LIMIT
                    + "; die Quelle darf weniger liefern, auch wenn noch Seiten folgen")
            @RequestParam(defaultValue = "" + SourceQueries.MAX_PAGE_METADATA_LIMIT) int limit) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA);
        PageMetadataListing listing = queries.pageMetadata(sourceId, spaceKey, cursor, limit);
        // Je Seite, weil ein Endnutzer (#24) im freigegebenen Space nicht jede Seite sehen muss.
        return PageMetadataResponse.of(listing, access.filter(caller, listing.entries(), AccessKind.METADATA,
                entry -> ResourceScope.page(sourceId, entry.page().partitionKey(), entry.page().pageId())));
    }

    @GetMapping("/{sourceId}/pages/{pageId}/metadata")
    @Operation(summary = "Metadaten einer einzelnen Seite, ohne Inhalt",
            description = "Live aus der Quelle gelesen (origin=live), kein Cache. Kanonische Route ohne Space-Key: "
                    + "eine in einen anderen Space verschobene Seite behält ihre pageId, spaceKey zeigt den aktuellen "
                    + "Space. Felder und Hashes wie in der Metadatenliste eines Space. Liegt die Seite in einem Space "
                    + "ohne Freigabe für den Client, ist die Antwort dieselbe wie für eine unbekannte Seite.")
    @ApiResponse(responseCode = "200", description = "Metadaten der Seite")
    @ApiResponse(responseCode = "403", description = "Keine Freigabe für diese Quelle (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "400", description = "pageId hat keine Form, die die Quelle kennt "
            + "(source-unsupported)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found) oder unbekannte bzw. "
            + "nicht sichtbare Seite (source-object-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "Quelle verweigert den Zugriff oder antwortet fehlerhaft",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Quelle nicht erreichbar (source-unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public SinglePageMetadataResponse singlePageMetadata(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "ID der Seite in der Quelle (Confluence-Content-ID)", example = "123456")
            @PathVariable String pageId) {
        // Der Space der Seite steht erst nach dem Lesen fest. Ohne Freigabe für alle Spaces der Quelle wird danach
        // geprüft und eine Verweigerung wie eine unbekannte Seite beantwortet, damit sie keine Existenz verrät.
        if (access.permits(caller, ResourceScope.page(sourceId, null, pageId), AccessKind.METADATA)) {
            return SinglePageMetadataResponse.of(queries.pageMetadata(sourceId, pageId));
        }
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        SinglePageMetadata metadata = queries.pageMetadata(sourceId, pageId);
        access.requireConcealed(caller, ResourceScope.page(sourceId, metadata.entry().page().partitionKey(), pageId),
                AccessKind.METADATA);
        return SinglePageMetadataResponse.of(metadata);
    }

    /**
     * Eine konfigurierte Quelle.
     *
     * @param capabilities was die Quelle kann ({@code partitions}, {@code page-listing}, {@code page-metadata},
     *                     {@code stored-page}, {@code async-stored-page}); fehlt eine, antwortet die Abfrage mit 400 ({@code source-unsupported})
     */
    public record SourceResponse(String id, String type, String title, List<String> capabilities) {

        static SourceResponse of(HarvestSource source) {
            return new SourceResponse(source.id(), source.type(), source.title(), source.capabilities().stream()
                    .sorted().map(capability -> capability.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
                    .toList());
        }
    }

    /** Die Spaces einer Quelle. */
    public record SpacesResponse(String sourceId, List<SpaceResponse> spaces) {
    }

    /**
     * Ein Space.
     *
     * @param key            Space-Key, z. B. {@code DEV}
     * @param name           Anzeigename
     * @param homeResourceId ID der Startseite in der Quelle oder {@code null}, wenn der Space keine hat
     */
    public record SpaceResponse(String key, String name,
                                @Schema(nullable = true) String homeResourceId) {

        static SpaceResponse of(Partition partition) {
            return new SpaceResponse(partition.key(), partition.name(), partition.homeResourceId());
        }
    }

    /**
     * Ein Stapel Seitenmetadaten eines Space.
     *
     * @param origin               {@code live}: direkt aus der Quelle gelesen, nicht aus einem Cache
     * @param retrievedAt          Zeitpunkt der Abfrage bei der Quelle
     * @param metadataHashSchema   Schema und Version von {@code metadataHash}
     * @param attributesHashSchema Schema und Version von {@code attributesHash}
     * @param nextCursor           Cursor für den nächsten Stapel oder {@code null}, wenn vollständig
     * @param complete             {@code true}, wenn dies der letzte Stapel ist
     */
    public record PageMetadataResponse(String sourceId, String spaceKey, String origin, Instant retrievedAt,
                                       String metadataHashSchema, String attributesHashSchema,
                                       List<PageMetadataEntry> pages,
                                       @Schema(nullable = true) String nextCursor, boolean complete) {

        static PageMetadataResponse of(PageMetadataListing listing, List<PageMetadataListing.Entry> visible) {
            return new PageMetadataResponse(listing.sourceId(), listing.partitionKey(), "live",
                    listing.retrievedAt(), MetadataHash.SCHEMA, AttributesHash.SCHEMA,
                    visible.stream().map(PageMetadataEntry::of).toList(), listing.nextCursor(),
                    listing.complete());
        }
    }

    /**
     * Metadaten einer einzelnen Seite.
     *
     * @param origin               {@code live}: direkt aus der Quelle gelesen, nicht aus einem Cache
     * @param retrievedAt          Zeitpunkt der Abfrage bei der Quelle
     * @param metadataHashSchema   Schema und Version von {@code metadataHash}
     * @param attributesHashSchema Schema und Version von {@code attributesHash}
     */
    public record SinglePageMetadataResponse(String sourceId, String origin, Instant retrievedAt,
                                             String metadataHashSchema, String attributesHashSchema,
                                             PageMetadataEntry page) {

        static SinglePageMetadataResponse of(SinglePageMetadata metadata) {
            return new SinglePageMetadataResponse(metadata.sourceId(), "live", metadata.retrievedAt(),
                    MetadataHash.SCHEMA, AttributesHash.SCHEMA, PageMetadataEntry.of(metadata.entry()));
        }
    }

    /**
     * Metadaten einer Seite.
     *
     * @param pageId       ID der Seite in der Quelle (Confluence-Content-ID)
     * @param resourceId   quellübergreifende Ressourcen-ID
     * @param parentPageId ID der Elternseite oder {@code null} für eine Wurzelseite
     * @param version      Versionskennung oder {@code null}, wenn unbekannt
     * @param modifiedAt   letzte Änderung oder {@code null}, wenn unbekannt
     * @param url          Adresse in der Quelle oder {@code null}, wenn unbekannt
     * @param attributes   weitere Metadaten der Quelle, z. B. {@code confluence.labels}
     * @param metadataHash   {@code sha256:<hex>} nach {@code metadataHashSchema}
     * @param attributesHash {@code sha256:<hex>} über {@code attributes} nach {@code attributesHashSchema}
     */
    public record PageMetadataEntry(String pageId, String resourceId, String title, String spaceKey,
                                    @Schema(nullable = true) String parentPageId,
                                    @Schema(nullable = true) String version,
                                    @Schema(nullable = true) Instant modifiedAt,
                                    @Schema(nullable = true) URI url, Map<String, String> attributes,
                                    String metadataHash, String attributesHash) {

        static PageMetadataEntry of(PageMetadataListing.Entry entry) {
            PageMetadata page = entry.page();
            return new PageMetadataEntry(page.pageId(), page.resourceId(), page.title(), page.partitionKey(),
                    page.parentPageId(), page.version(), page.modifiedAt(), page.url(), page.attributes(),
                    entry.metadataHash(), entry.attributesHash());
        }
    }
}
