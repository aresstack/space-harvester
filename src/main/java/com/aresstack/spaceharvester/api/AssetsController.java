package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetHarvest;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.PageAssets;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anhänge und Bilder einer Seite getrennt vom Seiten-JSON ernten und ausliefern (#19). Jede Anfrage liest die
 * Anhangsliste live und prüft den aktuellen Space der Seite wie die Metadatenroute: ohne Freigabe sieht die Antwort aus
 * wie für eine unbekannte Seite. Nur Anhänge dieser Liste werden geladen, nie fremde URLs.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources/{sourceId}/pages/{pageId}/assets",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assets", description = "Anhänge und Bilder einer Seite, getrennt geerntet und versioniert")
public class AssetsController {

    private final SourceQueries queries;
    private final Assets assets;
    private final AccessGuard access;

    public AssetsController(SourceQueries queries, Assets assets, AccessGuard access) {
        this.queries = queries;
        this.assets = assets;
        this.access = access;
    }

    @GetMapping
    @Operation(summary = "Anhänge einer Seite mit Art und abgelegtem Stand",
            description = "Live gelesene Anhangsliste (#9), je Anhang die Art (DIAGRAM vor VECTOR vor IMAGE vor "
                    + "OTHER), bei Vorschaubildern eines draw.io-Diagramms previewOf, und ob die aktuelle Version "
                    + "abgelegt ist. Lädt nichts herunter. Freigabe metadata.")
    @ApiResponse(responseCode = "200", description = "Anhänge der Seite")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle oder unbekannte bzw. nicht sichtbare Seite")
    public PageAssetsResponse pageAssets(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "ID der Seite in der Quelle", example = "123456") @PathVariable String pageId) {
        return PageAssetsResponse.of(assets.overview(visible(caller, sourceId, pageId, AccessKind.METADATA)));
    }

    @PostMapping("/harvest")
    @Operation(summary = "Alle Anhänge einer Seite herunterladen und ablegen",
            description = "Lädt jeden Anhang, der in seiner aktuellen Version noch nicht abgelegt ist, über die "
                    + "Anmeldung der Quelle herunter; gleiche Versionen werden nicht erneut übertragen. Fehler je "
                    + "Anhang stehen mit festem Code im Ergebnis und im Log. Freigabe sync.")
    @ApiResponse(responseCode = "200", description = "Ergebnis der Ernte")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle oder unbekannte bzw. nicht sichtbare Seite")
    public AssetHarvestResponse harvest(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "ID der Seite in der Quelle", example = "123456") @PathVariable String pageId) {
        return AssetHarvestResponse.of(assets.harvest(visible(caller, sourceId, pageId, AccessKind.SYNC)));
    }

    @GetMapping(path = "/{attachmentId}/content", produces = MediaType.ALL_VALUE)
    @Operation(summary = "Ein Anhang in seiner aktuellen Version, Byte für Byte",
            description = "Aus der Ablage oder, falls die aktuelle Version dort fehlt, frisch über die Quelle geladen "
                    + "und abgelegt. Immer als Download (Content-Disposition attachment, nosniff, CSP sandbox), "
                    + "ETag = sha256. Freigabe content.")
    @ApiResponse(responseCode = "200", description = "Inhalt des Anhangs")
    @ApiResponse(responseCode = "304", description = "Inhalt unverändert (If-None-Match)")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle, Seite oder Anhang, oder nicht sichtbar")
    @ApiResponse(responseCode = "502", description = "Quelle verweigert den Zugriff, antwortet fehlerhaft oder der "
            + "Anhang ist größer als space-harvester.assets.max-size (asset-too-large)")
    public ResponseEntity<byte[]> content(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "ID der Seite in der Quelle", example = "123456") @PathVariable String pageId,
            @Parameter(description = "ID des Anhangs in der Quelle", example = "att123") @PathVariable String attachmentId,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Assets.AssetContent content = assets.content(visible(caller, sourceId, pageId, AccessKind.CONTENT),
                attachmentId);
        Asset asset = content.asset();
        String etag = "\"" + asset.sha256() + "\"";
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(etag);
        headers.setCacheControl("private, no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "sandbox; default-src 'none'");
        headers.set("X-Asset-Version", asset.version() == null ? "" : asset.version());
        if (etag.equals(ifNoneMatch)) {
            return new ResponseEntity<>(headers, HttpStatus.NOT_MODIFIED);
        }
        headers.setContentType(mediaType(asset.mediaType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(asset.title().isEmpty() ? asset.attachmentId() : asset.title(), StandardCharsets.UTF_8)
                .build());
        return new ResponseEntity<>(content.content(), headers, HttpStatus.OK);
    }

    /** Liest die Anhangsliste und prüft die Freigabe für den aktuellen Space der Seite, verdeckt. */
    private PageAttachmentList visible(AccessContext caller, String sourceId, String pageId, AccessKind kind) {
        if (access.permits(caller, ResourceScope.page(sourceId, null, pageId), kind)) {
            return queries.attachments(sourceId, pageId);
        }
        access.require(caller, ResourceScope.source(sourceId), kind);
        PageAttachmentList list = queries.attachments(sourceId, pageId);
        access.requireConcealed(caller, ResourceScope.page(sourceId, list.spaceKey(), pageId), kind);
        return list;
    }

    private static MediaType mediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** Anhänge einer Seite mit abgelegtem Stand. */
    public record PageAssetsResponse(String sourceId, String pageId, String spaceKey, String origin,
                                     Instant retrievedAt, boolean listComplete, List<AssetEntry> assets) {

        static PageAssetsResponse of(PageAssets page) {
            return new PageAssetsResponse(page.sourceId(), page.pageId(), page.spaceKey(), "live",
                    page.retrievedAt(), page.listComplete(), page.entries().stream().map(AssetEntry::of).toList());
        }
    }

    /**
     * Ein Anhang.
     *
     * @param previewOf  attachmentId des draw.io-Diagramms, dessen Vorschaubild dies ist; dann die Diagrammdaten nehmen
     * @param stored     abgelegter Stand der aktuellen Version oder {@code null}
     * @param contentUrl Pfad zum Inhalt in dieser API
     */
    public record AssetEntry(String attachmentId, String title, String mediaType, AssetKind kind,
                             @Schema(nullable = true) String version, @Schema(nullable = true) Long fileSize,
                             @Schema(nullable = true) String previewOf, @Schema(nullable = true) StoredAsset stored,
                             String contentUrl) {

        static AssetEntry of(PageAssets.Entry entry) {
            PageAttachment a = entry.attachment();
            return new AssetEntry(a.attachmentId(), a.title(), a.mediaType(), entry.kind(), a.version(),
                    a.fileSize(), entry.previewOf(), entry.stored() == null ? null : StoredAsset.of(entry.stored()),
                    "assets/" + a.attachmentId() + "/content");
        }
    }

    /** Abgelegter Stand eines Anhangs. */
    public record StoredAsset(String version, long size, String sha256, Instant retrievedAt) {

        static StoredAsset of(Asset asset) {
            return new StoredAsset(asset.version(), asset.size(), asset.sha256(), asset.retrievedAt());
        }
    }

    /** Ergebnis einer Ernte. */
    public record AssetHarvestResponse(String sourceId, String pageId, String spaceKey, boolean complete,
                                       boolean listComplete, long stored, long reused, long failed,
                                       Instant finishedAt, List<HarvestEntry> assets) {

        static AssetHarvestResponse of(AssetHarvest harvest) {
            return new AssetHarvestResponse(harvest.sourceId(), harvest.pageId(), harvest.spaceKey(),
                    harvest.complete(), harvest.listComplete(), harvest.count(AssetHarvest.Status.STORED),
                    harvest.count(AssetHarvest.Status.REUSED), harvest.count(AssetHarvest.Status.FAILED),
                    harvest.finishedAt(), harvest.entries().stream().map(HarvestEntry::of).toList());
        }
    }

    /** Ein Anhang einer Ernte; {@code code} nur bei {@code FAILED}. */
    public record HarvestEntry(String attachmentId, AssetHarvest.Status status,
                               @Schema(nullable = true) StoredAsset stored, @Schema(nullable = true) String code) {

        static HarvestEntry of(AssetHarvest.Entry entry) {
            return new HarvestEntry(entry.attachmentId(), entry.status(),
                    entry.asset() == null ? null : StoredAsset.of(entry.asset()), entry.code());
        }
    }
}
