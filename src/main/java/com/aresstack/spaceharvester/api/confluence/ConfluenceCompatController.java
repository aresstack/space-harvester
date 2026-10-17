package com.aresstack.spaceharvester.api.confluence;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.compat.ContentListing;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Lesende Confluence-REST-Endpunkte aus dem Cache (#31). {@code /api/compat/confluence/{sourceId}} ist die Basis-URL,
 * die ein Confluence-Client statt der echten Instanz konfiguriert. Antworten sind die gespeicherten Original-Payloads
 * Byte für Byte; was der Cache nicht hält, wird abgelehnt statt erfunden, und nichts wird an Confluence
 * weitergereicht. Rechte prüft {@link PageQueries} wie für die eigene API. Unterstützter Umfang:
 * {@code docs/CONFLUENCE_COMPAT.md}.
 */
@RestController
@RequestMapping(path = ConfluenceCompatController.BASE + "/rest/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Confluence-kompatibel (Cache)",
        description = "Ausgewählte lesende Confluence-REST-Endpunkte aus dem Cache, ohne Schreiben und ohne "
                + "Durchgriff auf Confluence")
public class ConfluenceCompatController {

    static final String BASE = "/api/compat/confluence/{sourceId}";
    static final String CONTENT_FORMAT = "confluence-rest-content+json";
    static final String CACHE_GENERATION = "X-Cache-Generation";
    static final String CACHE_STATE = "X-Cache-State";

    /** Wie bei Confluence. */
    static final int DEFAULT_LIMIT = 25;

    private static final String EXPAND_PREFIX = CONTENT_FORMAT + ";expand=";

    private final PageQueries queries;
    private final ContentListing listing;
    private final ObjectMapper json;

    public ConfluenceCompatController(PageQueries queries, ContentListing listing, ObjectMapper json) {
        this.queries = queries;
        this.listing = listing;
        this.json = json;
    }

    @GetMapping("/content/{id}")
    @Operation(summary = "GET /rest/api/content/{id} aus dem Cache",
            description = "Die gespeicherte Antwort von Confluence Byte für Byte. Sie enthält alle beim Ernten "
                    + "abgerufenen Erweiterungen (body.storage, version, space, ancestors, metadata.labels, history, "
                    + "history.lastUpdated), also auch nicht angefragte; _links zeigen auf die Confluence-Instanz. "
                    + "Ein expand, das die gespeicherte Antwort nicht enthält, ergibt 400 statt einer erfundenen "
                    + "Antwort. Nur status=current; version (ältere Revisionen) wird nicht unterstützt.")
    @ApiResponse(responseCode = "200", description = "Gespeicherte Confluence-Antwort")
    @ApiResponse(responseCode = "304", description = "Payload unverändert gegenüber If-None-Match")
    @ApiResponse(responseCode = "400", description = "expand, status oder version, die der Cache nicht bedienen kann")
    @ApiResponse(responseCode = "403", description = "Keine content-Freigabe für diese Quelle")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle, Seite nicht im Cache oder nicht sichtbar, "
            + "bewusst nicht unterschieden")
    @ApiResponse(responseCode = "503", description = "Cache nicht eingerichtet oder nicht lesbar")
    public ResponseEntity<byte[]> content(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Confluence-Content-ID", example = "123456") @PathVariable String id,
            @Parameter(description = "Kommagetrennte Erweiterungen wie bei Confluence")
            @RequestParam(required = false) String expand,
            @Parameter(description = "Nur current") @RequestParam(required = false) String status,
            @Parameter(description = "Nicht unterstützt") @RequestParam(required = false) String version) {
        requireCurrent(status);
        if (version != null) {
            throw new UnsupportedRequestException("version wird nicht unterstützt; der Cache hält nur die aktuelle "
                    + "Fassung einer Seite");
        }
        DeliveredPage page = queries.page(caller, sourceId, id, false);
        NativePayload payload = page.snapshot().payload();
        requireExpand(id, payload, expand);
        // Spring wertet If-None-Match am ETag der Antwort aus; die 304 behält so die Cache-Kopfzeilen.
        String etag = "\"" + payload.rawSha256() + "\"";
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .contentType(MediaType.parseMediaType(payload.mediaType()));
        CacheGeneration generation = page.generation();
        if (generation != null) {
            response.header(CACHE_GENERATION, generation.id())
                    .header(CACHE_STATE, generation.state().name().toLowerCase(Locale.ROOT));
        }
        return response.body(payload.bytes());
    }

    @GetMapping("/content")
    @Operation(summary = "GET /rest/api/content?spaceKey=… aus dem Cache",
            description = "Seiten eines Space mit start und limit (höchstens " + ContentListing.MAX_LIMIT + ", "
                    + "größere Werte werden gesenkt), aufsteigend nach Content-ID als Zeichenkette. results enthält "
                    + "die gespeicherten Confluence-Antworten Byte für Byte; start, limit, size und _links stellt "
                    + "der Harvester. title filtert auf genau diesen Titel. Nur type=page und status=current; "
                    + "spaceKey ist Pflicht. Ist der Abgleich des Space unvollständig oder liegt eine Seite nur mit "
                    + "Kopfdaten im Cache, antwortet die Route mit 503 statt einer unvollständigen Liste.")
    @ApiResponse(responseCode = "200", description = "Ausschnitt der Seitenliste")
    @ApiResponse(responseCode = "400", description = "Fehlender spaceKey, anderer type oder status, negatives start "
            + "oder limit, expand, das eine Seite nicht gespeichert hat")
    @ApiResponse(responseCode = "403", description = "Keine Freigabe für diesen Space")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle oder Space nicht im Cache")
    @ApiResponse(responseCode = "503", description = "Cache nicht lesbar oder Liste nur unvollständig lieferbar")
    public ResponseEntity<byte[]> contentList(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Space-Key", example = "DEV") @RequestParam(required = false) String spaceKey,
            @Parameter(description = "Nur page") @RequestParam(defaultValue = "page") String type,
            @Parameter(description = "Genau dieser Titel") @RequestParam(required = false) String title,
            @Parameter(description = "Nur current") @RequestParam(required = false) String status,
            @Parameter(description = "Kommagetrennte Erweiterungen wie bei Confluence")
            @RequestParam(required = false) String expand,
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        listing.requireSource(caller, sourceId);
        if (spaceKey == null || spaceKey.isBlank()) {
            throw new UnsupportedRequestException("spaceKey ist Pflicht; eine Liste über alle Spaces wird nicht "
                    + "unterstützt");
        }
        if (!"page".equals(type)) {
            throw new UnsupportedRequestException("type=" + type + " wird nicht unterstützt; nur type=page");
        }
        requireCurrent(status);
        ContentListing.Slice slice = listing.space(caller, sourceId, spaceKey, title, start, limit);
        return list(sourceId, slice, expand, "/rest/api/content");
    }

    @GetMapping("/content/{id}/child/page")
    @Operation(summary = "GET /rest/api/content/{id}/child/page aus dem Cache",
            description = "Direkte Kinder einer Seite im Space der Seite, mit start und limit wie die Seitenliste, "
                    + "aufsteigend nach Content-ID als Zeichenkette; results enthält die gespeicherten "
                    + "Confluence-Antworten. Unvollständig lieferbare Listen ergeben 503.")
    @ApiResponse(responseCode = "200", description = "Ausschnitt der Kinder")
    @ApiResponse(responseCode = "400", description = "Negatives start oder limit, expand, das eine Seite nicht "
            + "gespeichert hat")
    @ApiResponse(responseCode = "403", description = "Keine Freigabe für diese Quelle")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle, Seite nicht im Cache oder nicht sichtbar")
    @ApiResponse(responseCode = "503", description = "Cache nicht lesbar oder Liste nur unvollständig lieferbar")
    public ResponseEntity<byte[]> children(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Confluence-Content-ID der Elternseite", example = "123456")
            @PathVariable String id,
            @Parameter(description = "Kommagetrennte Erweiterungen wie bei Confluence")
            @RequestParam(required = false) String expand,
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        ContentListing.Slice slice = listing.children(caller, sourceId, id, start, limit);
        return list(sourceId, slice, expand, "/rest/api/content/" + id + "/child/page");
    }

    /**
     * Die Hülle wie bei Confluence; {@code results} sind die gespeicherten Payloads unverändert hintereinander, nur die
     * Hülle wird geschrieben.
     */
    private ResponseEntity<byte[]> list(String sourceId, ContentListing.Slice slice, String expand, String path) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes("{\"results\":[".getBytes(StandardCharsets.UTF_8));
        String separator = "";
        for (DeliveredPage page : slice.pages()) {
            NativePayload payload = page.snapshot().payload();
            requireExpand(page.header().key().pageId(), payload, expand);
            body.writeBytes(separator.getBytes(StandardCharsets.UTF_8));
            body.writeBytes(payload.bytes());
            separator = ",";
        }
        // Wie bei Confluence: base ist die Basis-URL, context ihr Pfad, next und self hängen dahinter.
        UriComponents base = ServletUriComponentsBuilder.fromCurrentContextPath()
                .pathSegment("api", "compat", "confluence", sourceId).build();
        String compatBase = base.toUriString();
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("base", compatBase);
        links.put("context", base.getPath());
        if (slice.more()) {
            links.put("next", UriComponentsBuilder.fromUriString(path)
                    .query(ServletUriComponentsBuilder.fromCurrentRequest().build().getQuery())
                    .replaceQueryParam("start", slice.start() + slice.pages().size())
                    .replaceQueryParam("limit", slice.limit())
                    .build(true).toUriString());
        }
        links.put("self", compatBase + path);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("start", slice.start());
        envelope.put("limit", slice.limit());
        envelope.put("size", slice.pages().size());
        envelope.put("_links", links);
        byte[] tail = json.writeValueAsBytes(envelope);
        body.writeBytes("],".getBytes(StandardCharsets.UTF_8));
        // Ohne die öffnende Klammer der Hülle: Sie steht schon vor results.
        body.write(tail, 1, tail.length - 1);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toByteArray());
    }

    private static void requireCurrent(String status) {
        if (status != null && !"current".equals(status)) {
            throw new UnsupportedRequestException("status=" + status + " wird nicht unterstützt; der Cache hält nur "
                    + "die aktuelle Fassung (status=current)");
        }
    }

    private static void requireExpand(String id, NativePayload payload, String expand) {
        Set<String> stored = storedExpand(payload).orElseThrow(() -> new NotInConfluenceFormatException(id));
        List<String> missing = requested(expand).stream().filter(path -> !covered(path, stored)).toList();
        if (!missing.isEmpty()) {
            throw new UnsupportedRequestException("expand=" + String.join(",", missing) + " ist im Cache nicht "
                    + "gespeichert; verfügbar: " + String.join(",", stored));
        }
    }

    /** Die Erweiterungen der gespeicherten Abrufform, oder leer, wenn die Payload keine Content-Antwort ist. */
    static Optional<Set<String>> storedExpand(NativePayload payload) {
        String format = payload.nativeFormat();
        if (format.equals(CONTENT_FORMAT)) {
            return Optional.of(Set.of());
        }
        if (!format.startsWith(EXPAND_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(paths(format.substring(EXPAND_PREFIX.length())));
    }

    static List<String> requested(String expand) {
        return expand == null ? List.of() : List.copyOf(paths(expand));
    }

    /**
     * Wie bei Confluence erweitert {@code a.b} auch {@code a}: Ein angefragter Pfad ist gedeckt, wenn er gespeichert
     * ist oder ein gespeicherter Pfad unter ihm liegt.
     */
    static boolean covered(String path, Set<String> stored) {
        return stored.stream().anyMatch(s -> s.equals(path) || s.startsWith(path + "."));
    }

    private static Set<String> paths(String list) {
        Set<String> paths = new TreeSet<>();
        Arrays.stream(list.split(",")).map(String::trim).filter(p -> !p.isEmpty()).forEach(paths::add);
        return paths;
    }

    /** Eine Anfrage, die der Cache nicht ohne erfundene Daten beantworten kann. */
    static final class UnsupportedRequestException extends RuntimeException {

        UnsupportedRequestException(String message) {
            super(message);
        }
    }

    /** Die gespeicherte Payload stammt nicht aus {@code /rest/api/content/{id}}. */
    static final class NotInConfluenceFormatException extends RuntimeException {

        NotInConfluenceFormatException(String id) {
            super("Content " + id + " liegt nicht als Confluence-Content-Antwort im Cache");
        }
    }
}
