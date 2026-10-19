package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult;
import com.aresstack.spaceharvester.application.query.progressive.SearchProfile;
import com.aresstack.spaceharvester.application.ranking.SemanticVariantSearch;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Progressive Suche über REST (ADR 0017): die ursprüngliche Anfrage sofort, dann Varianten in Wellen, jeder Fund
 * bewertet gegen die ursprüngliche Anfrage. Eine Oberfläche startet die Suche und fragt den Stand ab, bis {@code done};
 * ein Agent stellt {@code wait=true} und bekommt die fertige, sortierte Liste mit Abschlussgrund. Rechte prüft die
 * Schlagwortsuche je Suchanfrage: Was der Aufrufer nicht sehen darf, erscheint weder als Treffer noch in einer Zahl.
 * Jede Suche gehört dem Aufrufer, der sie gestartet hat.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/search/progressive", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Suche", description = "Schlagwortsuche über die Suche der Quellen, Titel- und Metadatensuche im Index")
public class ProgressiveSearchController {

    /** Höchstes Zeitbudget einer Suche. */
    static final Duration MAX_TIME_BUDGET = Duration.ofMinutes(2);
    /** So lange wartet {@code wait=true} über das Zeitbudget hinaus auf den Abschluss. */
    static final Duration WAIT_MARGIN = Duration.ofSeconds(5);

    private final ProgressiveSearches searches;

    public ProgressiveSearchController(ProgressiveSearches searches) {
        this.searches = searches;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Progressive Suche starten",
            description = "Sucht q sofort und erzeugt gleichzeitig Varianten (Chat-Modell des KI-Dienstes mit "
                    + "ai.chat-model, sonst Regeln): zuerst mit allen Begriffen, dann schrittweise weniger. Die "
                    + "Varianten laufen in Wellen, jeder Fund wird gegen q bewertet (Cross-Encoder mit "
                    + "ai.rerank-model, nur Quellen in external-processing-sources) und nach Quelle und Seiten-ID "
                    + "zusammengeführt. ranking=semantic sucht jede Suchanfrage über die Vektoren im Cache: Die "
                    + "Variante findet und lädt nur, Treffer kommen aus der Vektorsuche mit q, bewertet nach der "
                    + "Kosinusähnlichkeit zu q (ohne Embedding-Modell 503 semantic-search-unavailable, kein Rückfall; "
                    + "20 s je Suchanfrage, Zeitbudget 60 s). mode=quality (Standard) endet, sobald minRelevantHits Treffer über minScore "
                    + "liegen; mode=all-variants arbeitet alle Varianten ab, etwa für einen Recherche-Agenten. Zeit- "
                    + "und Abfragebudget begrenzen beides. Ohne wait antwortet der Dienst sofort mit 202 und dem Stand; "
                    + "GET auf Location fragt nach. Mit wait=true wartet die Anfrage bis zum Abschluss (höchstens "
                    + "Zeitbudget plus 5 s) und antwortet mit 200 und der fertigen Liste; sonst 202 mit dem "
                    + "Stand. Delegierte Benutzer warten immer, weil ihre Anmeldung den Aufruf nicht überdauert. "
                    + "Eine Quelle ohne Freigabe liefert nichts; scheitert jede Suchanfrage, endet die Suche mit "
                    + "reason=failed. Bekannt sind die letzten " + ProgressiveSearches.CAPACITY + " Suchen, beendete "
                    + "5 Minuten lang.")
    @ApiResponse(responseCode = "200", description = "Mit wait=true: die fertige Liste mit Abschlussgrund")
    @ApiResponse(responseCode = "202", description = "Die Suche läuft; Location nennt den Stand")
    @ApiResponse(responseCode = "400", description = "Ungültige Anfrage, etwa ohne q oder mit Werten außerhalb der "
            + "Grenzen (invalid-request)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "ranking=semantic ohne verfügbares Embedding-Modell "
            + "(semantic-search-unavailable)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Zu viele laufende Suchen (too-many-searches)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProgressiveResponse> start(@Parameter(hidden = true) AccessContext caller,
                                                     @RequestBody ProgressiveRequest request) {
        if (request == null) {
            throw new InvalidQueryException("Anfrage fehlt");
        }
        KeywordSearch.Query query = new KeywordSearch.Query(request.q(), request.sourceId(), set(request.sourceIds()),
                set(request.spaceKeys()), set(request.excludeSpaceKeys()));
        SearchProfile profile = profile(request, searches.defaults());
        ProgressiveSearches.Tracked tracked = searches.start(caller, query, profile);
        boolean wait = Boolean.TRUE.equals(request.waitForResult())
                || caller.kind() == AccessContext.PrincipalKind.END_USER;
        if (wait) {
            await(tracked, profile.timeBudget().plus(WAIT_MARGIN));
        }
        ProgressiveResponse body = ProgressiveResponse.of(tracked.snapshot());
        if (wait && body.done()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create(ApiPaths.V1 + "/search/progressive/" + tracked.id())).body(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Stand einer progressiven Suche",
            description = "Solange done=false, der letzte Stand: Suchanfragen mit Priorität und Stand und die "
                    + "Treffer, nach Bewertung sortiert, ohne Bewertung in der Reihenfolge ihrer Freigabe; reason "
                    + "ist dann null. Danach die fertige Liste mit reason. Nur für den Aufrufer, der die Suche "
                    + "gestartet hat.")
    @ApiResponse(responseCode = "200", description = "Der Stand")
    @ApiResponse(responseCode = "404", description = "Unbekannt, verdrängt oder von einem anderen Aufrufer (not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ProgressiveResponse> progress(@Parameter(hidden = true) AccessContext caller,
                                                        @PathVariable String id) {
        return searches.find(caller, id).map(tracked -> ProgressiveResponse.of(tracked.snapshot()))
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Progressive Suche abbrechen",
            description = "Was gefunden ist, bleibt im Ergebnis; die Suche endet mit reason=cancelled, auch das Laden "
                    + "ihrer Funde in den Cache wird abgebrochen. Eine beendete Suche bleibt unverändert. Laufende "
                    + "Suchanfragen werden nicht unterbrochen, nur nicht mehr abgewartet.")
    @ApiResponse(responseCode = "204", description = "Abgebrochen")
    @ApiResponse(responseCode = "404", description = "Unbekannt, verdrängt oder von einem anderen Aufrufer (not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> cancel(@Parameter(hidden = true) AccessContext caller, @PathVariable String id) {
        return searches.cancel(caller, id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static void await(ProgressiveSearches.Tracked tracked, Duration timeout) {
        try {
            tracked.search().result().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // Der Stand sagt, wie weit die Suche ist; sie scheitert nie mit einer Ausnahme.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Set<String> set(List<String> values) {
        return values == null ? Set.of() : Set.copyOf(values.stream().filter(value -> value != null).toList());
    }

    /** Das Standardprofil mit den Angaben der Anfrage; Grenzen prüft das Profil. */
    static SearchProfile profile(ProgressiveRequest request, SearchProfile defaults) {
        SearchProfile.Mode mode = defaults.mode();
        if (request.mode() != null) {
            mode = switch (request.mode()) {
                case "quality" -> SearchProfile.Mode.QUALITY;
                case "all-variants" -> SearchProfile.Mode.ALL_VARIANTS;
                default -> throw new InvalidQueryException("mode kennt nur quality und all-variants");
            };
        }
        SearchProfile.Expansion expansion = defaults.expansion();
        if (request.expansion() != null) {
            expansion = switch (request.expansion()) {
                case "ai" -> defaults.expansion();
                case "rules" -> SearchProfile.Expansion.RULES;
                default -> throw new InvalidQueryException("expansion kennt nur ai und rules");
            };
        }
        if (request.ranking() != null) {
            defaults = switch (request.ranking()) {
                case "keyword" -> defaults.withRanking(SearchProfile.Ranking.KEYWORD);
                // Mehr Zeit je Suchanfrage: Sie lädt und bettet erst ein; einzeln überschreibbar bleibt das Budget.
                case "semantic" -> SemanticVariantSearch.profile(defaults);
                default -> throw new InvalidQueryException("ranking kennt nur keyword und semantic");
            };
        }
        Duration timeBudget = defaults.timeBudget();
        if (request.timeBudgetMs() != null) {
            if (request.timeBudgetMs() < 1 || request.timeBudgetMs() > MAX_TIME_BUDGET.toMillis()) {
                throw new InvalidQueryException("timeBudgetMs muss zwischen 1 und " + MAX_TIME_BUDGET.toMillis()
                        + " liegen");
            }
            timeBudget = Duration.ofMillis(request.timeBudgetMs());
        }
        try {
            return new SearchProfile(mode, or(request.maxVariants(), defaults.maxVariants()),
                    or(request.minRelevantHits(), defaults.minRelevantHits()),
                    request.minScore() == null ? defaults.minScore() : request.minScore(), timeBudget,
                    or(request.searchBudget(), defaults.searchBudget()),
                    or(request.limitPerSearch(), defaults.limitPerSearch()), defaults.execution(), expansion,
                    defaults.ranking());
        } catch (IllegalArgumentException e) {
            // Die Meldung stammt aus der Prüfung des Profils und nennt nur die Grenze.
            throw new InvalidQueryException(e.getMessage());
        }
    }

    private static int or(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    /**
     * Eine progressive Suche.
     *
     * @param q                Suchbegriffe oder Frage, höchstens {@value KeywordSearch#MAX_TEXT_LENGTH} Zeichen
     * @param sourceId         nur diese Quelle; ohne Angabe alle freigegebenen
     * @param sourceIds        ohne sourceId: nur diese Quellen; unbekannte oder nicht freigegebene fallen still weg
     * @param spaceKeys        nur diese Spaces
     * @param excludeSpaceKeys ohne diese Spaces
     * @param mode             {@code quality} (Standard) oder {@code all-variants}
     * @param maxVariants      Varianten neben q, 0 bis 32 (Standard 8)
     * @param minRelevantHits  Qualitätsziel: so viele Treffer über minScore (Standard 5)
     * @param minScore         Schwelle der Bewertung gegen q, modellabhängig (Standard 0,5)
     * @param timeBudgetMs     Zeitbudget in Millisekunden, 1 bis 120000 (Standard 30000)
     * @param searchBudget     höchstens so viele Suchanfragen, q eingeschlossen (Standard 12)
     * @param limitPerSearch   Treffer je Suchanfrage, 1 bis 100 (Standard 20)
     * @param expansion        {@code ai} (Standard; ohne Chat-Modell wie {@code rules}) oder {@code rules}
     * @param ranking          {@code keyword} (Standard, Schlagwortsuche) oder {@code semantic} (Vektoren im Cache,
     *                         ADR 0013; ohne Embedding-Modell 503 {@code semantic-search-unavailable})
     * @param waitForResult    JSON {@code wait}: bis zum Abschluss warten und die fertige Liste liefern
     */
    public record ProgressiveRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Wie läuft der nächtliche Betrieb?")
            String q,
            @Schema(nullable = true) String sourceId, @Schema(nullable = true) List<String> sourceIds,
            @Schema(nullable = true) List<String> spaceKeys, @Schema(nullable = true) List<String> excludeSpaceKeys,
            @Schema(nullable = true, allowableValues = {"quality", "all-variants"}) String mode,
            @Schema(nullable = true) Integer maxVariants, @Schema(nullable = true) Integer minRelevantHits,
            @Schema(nullable = true) Double minScore, @Schema(nullable = true) Long timeBudgetMs,
            @Schema(nullable = true) Integer searchBudget, @Schema(nullable = true) Integer limitPerSearch,
            @Schema(nullable = true, allowableValues = {"ai", "rules"}) String expansion,
            @Schema(nullable = true, allowableValues = {"keyword", "semantic"}) String ranking,
            @JsonProperty("wait") @Schema(name = "wait", nullable = true) Boolean waitForResult) {
    }

    /**
     * Stand einer progressiven Suche.
     *
     * @param status    {@code running} oder {@code done}
     * @param done      ob die Suche beendet ist; „beendet“ heißt, die Strategie hat ihre Arbeit getan, nicht, dass
     *                  jede Seite untersucht wurde
     * @param reason    warum sie endete, {@code null}, solange sie läuft; {@code budget-exhausted} behauptet nie, das
     *                  Qualitätsziel sei erreicht
     * @param mode      {@code quality} oder {@code all-variants}
     * @param expansion woher die Varianten kommen
     * @param ranking   {@code keyword} oder {@code semantic}; semantisch ist {@code score} die Kosinusähnlichkeit zu q
     * @param hits      Treffer, nach Bewertung sortiert, beste zuerst; unbewertete danach
     * @param variants  alle Suchanfragen, q zuerst (Priorität 0)
     * @param loads     nach dem Abschluss die Kennungen des Ladens in den Cache für
     *                  {@code GET /api/v1/search/progress/{id}}
     * @param elapsedMs Dauer bisher bzw. bis zum Abschluss
     */
    public record ProgressiveResponse(String id, @Schema(allowableValues = {"running", "done"}) String status,
                                      boolean done,
                                      @Schema(nullable = true, allowableValues = {"quality-reached",
                                              "all-variants-done", "budget-exhausted", "cancelled", "failed"})
                                      String reason,
                                      @Schema(allowableValues = {"quality", "all-variants"}) String mode,
                                      @Schema(allowableValues = {"ai", "rules"}) String expansion,
                                      @Schema(allowableValues = {"keyword", "semantic"}) String ranking,
                                      List<ProgressiveHitResponse> hits, List<VariantResponse> variants,
                                      List<String> loads, long elapsedMs) {

        static ProgressiveResponse of(ProgressiveSearches.Snapshot snapshot) {
            return new ProgressiveResponse(snapshot.id(), snapshot.done() ? "done" : "running", snapshot.done(),
                    snapshot.reason() == null ? null : name(snapshot.reason()), name(snapshot.profile().mode()),
                    name(snapshot.profile().expansion()), name(snapshot.profile().ranking()),
                    snapshot.hits().stream().map(ProgressiveHitResponse::of).toList(),
                    snapshot.variants().stream().map(VariantResponse::of).toList(), snapshot.loads(),
                    snapshot.elapsed().toMillis());
        }
    }

    /**
     * Ein Treffer der zusammengeführten Liste; Identität ist Quelle und Seiten-ID. Den Inhalt liefert
     * {@code GET /api/v1/sources/{sourceId}/pages/{pageId}}.
     *
     * @param snippet   Textauszug als Klartext, leer, wenn es keinen gibt
     * @param score     Bewertung gegen q, höher ist besser; {@code null} ohne Bewertung
     * @param foundBy   Priorität der Suchanfrage, die ihn zuerst fand (0 = q)
     * @param confirmed ob er aus dem Cache stammt (Volltextindex, semantisch die Vektoren), nicht nur aus der Suche
     *                  der Quelle
     * @param matchedIn  Trefferart wie in der Schlagwortsuche: {@code page} oder {@code attachment}
     * @param attachment bei {@code attachment} der Anhang, sonst {@code null}
     * @param passageId  Passage-ID des Absatzes, aus dem der Auszug stammt (auflösbar über
     *                   {@code GET /api/v1/passages?id=}), sonst {@code null}
     */
    public record ProgressiveHitResponse(String sourceId, String pageId, @Schema(nullable = true) String spaceKey,
                                         String title, String snippet, @Schema(nullable = true) URI url,
                                         @Schema(nullable = true) String version,
                                         @Schema(nullable = true) Instant modifiedAt,
                                         @Schema(nullable = true) String parentPageId,
                                         @Schema(nullable = true) Double score, int foundBy, boolean confirmed,
                                         @Schema(allowableValues = {"page", "attachment"}) String matchedIn,
                                         @Schema(nullable = true)
                                         SearchController.AttachmentMatchResponse attachment,
                                         @Schema(nullable = true) String passageId) {

        static ProgressiveHitResponse of(ProgressiveResult.RankedHit ranked) {
            KeywordSearch.Hit hit = ranked.hit();
            double score = ranked.score();
            return new ProgressiveHitResponse(hit.sourceId(), hit.pageId(), hit.spaceKey(), hit.title(),
                    hit.snippet(), hit.url(), hit.version(), hit.modifiedAt(), hit.parentPageId(),
                    Double.isNaN(score) || Double.isInfinite(score) ? null : score, ranked.foundBy(),
                    ranked.confirmed(), SearchController.matchKind(hit),
                    SearchController.AttachmentMatchResponse.of(hit.attachment()), hit.passageId());
        }
    }

    /**
     * Eine Suchanfrage mit ihrem Stand.
     *
     * @param priority 0 für q, danach 1 = genaueste Variante
     * @param newHits  so viele Treffer brachte sie neu
     */
    public record VariantResponse(String text, int priority,
                                  @Schema(allowableValues = {"pending", "running", "done", "failed", "skipped"})
                                  String status, int newHits) {

        static VariantResponse of(ProgressiveResult.Variant variant) {
            return new VariantResponse(variant.query().text(), variant.query().priority(), name(variant.status()),
                    variant.newHits());
        }
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
