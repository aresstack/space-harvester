package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.PageAddresses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Adressauflösung über alle Quellen (#178). */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/addresses", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Seiten (Cache)", description = "Seiten, Baum und native Payload aus dem Cache des Harvesters")
public class AddressController {

    private final PageAddresses addresses;

    public AddressController(PageAddresses addresses) {
        this.addresses = addresses;
    }

    @GetMapping
    @Operation(summary = "Eine Adresse über alle Quellen auflösen",
            description = "Fragt jede Quelle, deren Adressmuster passt, einzeln und rät nie zwischen Quellen. Es "
                    + "zählt nur, was die Quelle sich selbst zuordnet (bei Confluence: unter ihrer Basis-URL); eine "
                    + "Seiten-ID aus der URL einer anderen Instanz wird nirgends abgerufen. "
                    + "Angenommen werden Seiten-URLs mit Seiten-ID, /display/KEY/Titel, "
                    + "viewpage.action?spaceKey=…&title=…, die Space-Startseite /display/KEY, Bookmarks "
                    + "confluence://…, Ressourcen-IDs der Quellen und Lesezeichen "
                    + "space-harvester://<Quelle>/page/<ID>[?version=<Version>]. Titel und Startseite löst die Quelle "
                    + "live auf. Ein Treffer erscheint nur, wenn der Aufrufer die Seite lesen darf; keine Treffer "
                    + "ist 200 mit leerer Liste. Jeder Treffer trägt sein Lesezeichen mit der aktuellen Version.")
    @ApiResponse(responseCode = "200", description = "Treffer je Quelle und nicht erreichbare Quellen")
    @ApiResponse(responseCode = "400", description = "address fehlt, ist zu lang oder ein fehlerhaftes Lesezeichen "
            + "(invalid-request)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    public ResolutionResponse resolve(@Parameter(hidden = true) AccessContext caller,
                                      @Parameter(description = "URL, Lesezeichen oder Ressourcen-ID", required = true,
                                              example = "https://wiki.example.org/display/DEV/Start")
                                      @RequestParam String address) {
        return ResolutionResponse.of(addresses.resolve(caller, address));
    }

    /**
     * @param matches            je Quelle höchstens eine Seite
     * @param unavailableSources Quellen, deren Muster passt, die für die Auflösung aber nicht erreichbar waren
     */
    public record ResolutionResponse(List<MatchResponse> matches, List<String> unavailableSources) {

        static ResolutionResponse of(PageAddresses.Resolution resolution) {
            return new ResolutionResponse(resolution.matches().stream().map(MatchResponse::of).toList(),
                    resolution.unavailableSources());
        }
    }

    /** @param bookmark Lesezeichen {@code space-harvester://…} mit der aktuellen Version */
    public record MatchResponse(String sourceId, String pageId, String spaceKey, String title, String version,
                                String url, String bookmark) {

        static MatchResponse of(PageAddresses.Match match) {
            return new MatchResponse(match.sourceId(), match.pageId(), match.spaceKey(), match.title(),
                    match.version(), match.url() == null ? null : match.url().toString(), match.bookmark().value());
        }
    }
}
