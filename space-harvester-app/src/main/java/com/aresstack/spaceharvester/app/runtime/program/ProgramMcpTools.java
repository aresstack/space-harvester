package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only MCP tools over the same stored facts as the desktop search. */
public final class ProgramMcpTools {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SEARCH = "program_search";
    private static final String SOURCES = "program_sources";
    private static final String ANALYSIS = "program_analysis";
    private static final Set<String> SECTIONS = Set.of("elements", "calls", "references", "diagnostics", "statements",
            "dependencies", "symbols", "operations", "readSites", "dataLimitations", "procedures",
            "nodes", "edges", "controlLimitations");

    public interface Holdings {
        Set<String> sources();
        StoredProgramSearch.Snapshot search(Set<String> sources, BooleanSupplier cancelled);
        Optional<DeliveredPage> page(PageKey key);
        List<Enrichments.View> analyses(DeliveredPage page);
        void authorize(DeliveredPage page);
        /** Revalidate origins of a result through {@link ProgramAnalysisAccess#current(java.util.Collection)}. */
        boolean current(java.util.Collection<StoredCobolSearch.Origin> origins);
    }

    /** A search result rested on an analysis that changed or became unreadable after the snapshot was taken. */
    public static final class StaleResultException extends IllegalStateException {
        StaleResultException() {
            super("Stored analyses changed during the request");
        }
    }

    private final Holdings holdings;
    private final Set<String> selected;

    public ProgramMcpTools(Holdings holdings, Set<String> selected) {
        this.holdings = java.util.Objects.requireNonNull(holdings);
        this.selected = Set.copyOf(selected);
    }

    public List<Map<String, Object>> definitions() {
        return List.of(
                definition(SOURCES, "List explicitly enabled and AI-released program source IDs.", "{}"),
                definition(SEARCH, "Search stored COBOL, JCL and Natural facts. CANDIDATES are possible name/type matches, not runtime bindings. No live source access.",
                        """
                        {"source":{"type":"string"},"language":{"type":"string","enum":["COBOL","JCL","NATURAL"]},
                        "mode":{"type":"string","enum":["ALL","OBJECTS","COPYBOOKS","COPY_USAGE","DATA","CALLERS","CALLEES","DEPENDENCIES","RESOURCES","CANDIDATES","COBOL_CALLERS_HIERARCHY","COBOL_CALLEES_HIERARCHY"]},
                        "query":{"type":"string","maxLength":256},"offset":{"type":"integer","minimum":0},
                        "limit":{"type":"integer","minimum":1,"maximum":50}}
                        """),
                definition(ANALYSIS, "Read a page of an exact current technical analysis. Obtain source, pageId and rawSha256 from search. List sections with no section; procedure is a zero-based CFG procedure index for nodes/edges.",
                        """
                        {"source":{"type":"string"},"pageId":{"type":"string"},"rawSha256":{"type":"string"},
                        "section":{"type":"string","enum":["elements","calls","references","diagnostics","statements","dependencies","symbols","operations","readSites","dataLimitations","procedures","nodes","edges","controlLimitations"]},
                        "procedure":{"type":"integer","minimum":0},"offset":{"type":"integer","minimum":0},
                        "limit":{"type":"integer","minimum":1,"maximum":50}}
                        """, List.of("source", "pageId", "rawSha256")));
    }

    private static Map<String, Object> definition(String name, String description, String properties) {
        return definition(name, description, properties, List.of());
    }

    private static Map<String, Object> definition(String name, String description, String properties, List<String> required) {
        return Map.of("name", name, "description", description,
                "inputSchema", Map.of("type", "object", "properties", JSON.readTree(properties),
                        "required", required, "additionalProperties", false),
                "annotations", Map.of("readOnlyHint", true, "destructiveHint", false, "openWorldHint", false));
    }

    public Object call(String name, JsonNode args, BooleanSupplier stopped) {
        if (!args.isObject()) throw new IllegalArgumentException("Arguments must be an object");
        var allowedKeys = switch (name) {
            case SOURCES -> Set.<String>of();
            case SEARCH -> Set.of("source", "language", "mode", "query", "offset", "limit");
            case ANALYSIS -> Set.of("source", "pageId", "rawSha256", "section", "procedure", "offset", "limit");
            default -> throw new IllegalArgumentException("Unknown tool");
        };
        if (args.propertyNames().stream().anyMatch(key -> !allowedKeys.contains(key))) {
            throw new IllegalArgumentException("Unknown argument");
        }
        return switch (name) {
            case SOURCES -> Map.of("sources", available().stream().sorted().toList());
            case SEARCH -> search(args, stopped);
            case ANALYSIS -> analysis(args, stopped);
            default -> throw new IllegalArgumentException("Unknown tool");
        };
    }

    private Set<String> available() {
        var result = new LinkedHashSet<>(holdings.sources());
        result.retainAll(selected);
        return Set.copyOf(result);
    }

    private Object search(JsonNode args, BooleanSupplier stopped) {
        String source = optionalText(args, "source", 1024);
        String language = optionalText(args, "language", 16);
        String mode = optionalText(args, "mode", 64);
        String query = optionalText(args, "query", 256);
        int limit = number(args, "limit", 25, 1, 50);
        int offset = number(args, "offset", 0, 0, Integer.MAX_VALUE);
        var allowed = available();
        if (allowed.isEmpty() || source != null && !allowed.contains(source)) throw unavailable();
        var filter = language == null ? null : StoredProgramSearch.Language.valueOf(language);
        var kind = mode == null ? StoredProgramSearch.Mode.ALL : StoredProgramSearch.Mode.valueOf(mode);
        if ((kind == StoredProgramSearch.Mode.COBOL_CALLERS_HIERARCHY || kind == StoredProgramSearch.Mode.COBOL_CALLEES_HIERARCHY)
                && source == null) throw new IllegalArgumentException("Select one FTP source for COBOL hierarchy");
        // Include all selected sources for candidate discovery; filter the origin in the query.
        var scope = kind == StoredProgramSearch.Mode.CANDIDATES || source == null ? allowed : Set.of(source);
        BooleanSupplier cancelled = () -> stopped.getAsBoolean() || !available().containsAll(scope);
        checkpoint(cancelled);
        var snapshot = holdings.search(scope, cancelled);
        var result = snapshot.search(kind, filter, source, query == null ? "" : query, limit, offset);
        var origins = new LinkedHashSet<StoredCobolSearch.Origin>();
        for (var match : result.hits()) {
            collect(match.fact(), origins);
            if (match.candidate() != null) collect(match.candidate(), origins);
        }
        var response = Map.of("coverage", snapshot.coverage(), "total", result.total(), "offset", offset,
                "limit", limit, "hits", result.hits(), "candidateMeaning", "Possible target only; not a verified runtime binding.");
        authorize(origins, scope, cancelled);
        // Fail closed: never deliver hits, candidates or hierarchy steps whose analysis is no longer current.
        if (!holdings.current(origins)) throw new StaleResultException();
        checkpoint(cancelled);
        return response;
    }

    private static void collect(StoredProgramSearch.Fact fact, Set<StoredCobolSearch.Origin> origins) {
        origins.add(new StoredCobolSearch.Origin(fact.location().key(), fact.location().rawSha256()));
        origins.addAll(fact.evidence());
    }

    private Object analysis(JsonNode args, BooleanSupplier stopped) {
        String source = requiredText(args, "source", 1024);
        String id = requiredText(args, "pageId", 4096);
        String hash = requiredText(args, "rawSha256", 80);
        String section = optionalText(args, "section", 64);
        int procedure = number(args, "procedure", 0, 0, Integer.MAX_VALUE);
        int offset = number(args, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = number(args, "limit", 25, 1, 50);
        if (section != null && !SECTIONS.contains(section)) throw new IllegalArgumentException("Unknown section");
        var key = new PageKey(source, id);
        var origin = new StoredCobolSearch.Origin(key, hash);
        var scope = Set.of(source);
        BooleanSupplier cancelled = () -> stopped.getAsBoolean() || !available().contains(source);
        checkpoint(cancelled);
        var page = readable(origin, scope);
        var versions = Map.of(CobolProgramEnrichment.ID, CobolProgramEnrichment.VERSION,
                JclProgramEnrichment.ID, JclProgramEnrichment.VERSION,
                NaturalProgramEnrichment.ID, NaturalProgramEnrichment.VERSION);
        var stored = holdings.analyses(page).stream().filter(view ->
                versions.containsKey(view.enrichment().processorId())
                        && versions.get(view.enrichment().processorId()).equals(view.enrichment().processorVersion())
                        && view.freshness() == Enrichment.Freshness.CURRENT
                        && view.enrichment().status() == Enrichment.Status.OK
                        && key.equals(view.enrichment().key())).findFirst().orElseThrow(ProgramMcpTools::unavailable).enrichment();
        var json = JSON.readTree(stored.payloadJson());
        if (!hash.equals(json.path("rawSha256").asString(""))) throw unavailable();
        var origins = new LinkedHashSet<StoredCobolSearch.Origin>();
        origins.add(origin);
        if (CobolProgramEnrichment.ID.equals(stored.processorId())) {
            for (var probe : CobolProgramEnrichment.read(stored.payloadJson()).probes()) {
                if (probe.rawSha256() != null) origins.add(new StoredCobolSearch.Origin(
                        new PageKey(source, probe.pageId()), probe.rawSha256()));
            }
        }
        var data = json.path("analysis");
        var sections = sections(data, procedure);
        var response = new LinkedHashMap<String, Object>();
        response.put("source", source);
        response.put("pageId", id);
        response.put("rawSha256", hash);
        response.put("processor", stored.processorId());
        response.put("processorVersion", stored.processorVersion());
        response.put("warnings", stored.warnings());
        if (data.path("semanticAnalysisComplete").isBoolean()) {
            response.put("semanticAnalysisComplete", data.path("semanticAnalysisComplete").asBoolean());
        }
        response.put("interpretation", "Static technical analysis only; consult diagnostics and limitations.");
        response.put("sections", sections.keySet());
        response.put("evidence", origins);
        if (section != null) {
            var array = sections.get(section);
            if (array == null) throw new IllegalArgumentException("Section unavailable for this program or procedure");
            var rows = new ArrayList<JsonNode>();
            int end = (int) Math.min(array.size(), (long) offset + limit);
            for (int i = offset; i < end; i++) {
                checkpoint(cancelled);
                var row = array.get(i);
                if ("procedures".equals(section)) {
                    rows.add(JSON.valueToTree(Map.of("index", i, "name", row.path("name").asString(""),
                            "entry", row.path("entry").asString(""), "exit", row.path("exit").asString(""),
                            "nodeCount", row.path("nodes").size(), "edgeCount", row.path("edges").size())));
                } else rows.add(row);
            }
            response.put("section", section);
            response.put("procedure", procedure);
            response.put("offset", offset);
            response.put("total", array.size());
            response.put("rows", rows);
        }
        authorize(origins, scope, cancelled);
        // Recheck the exact parser artifact before returning evidence.
        if (holdings.analyses(readable(origin, scope)).stream().noneMatch(view ->
                view.freshness() == Enrichment.Freshness.CURRENT && stored.equals(view.enrichment()))) throw unavailable();
        return response;
    }

    private static Map<String, JsonNode> sections(JsonNode analysis, int procedure) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (String key : List.of("elements", "calls", "references", "diagnostics", "statements", "dependencies")) {
            addArray(result, key, analysis.path(key));
        }
        var data = analysis.path("dataFlow");
        for (String key : List.of("symbols", "operations", "readSites")) addArray(result, key, data.path(key));
        addArray(result, "dataLimitations", data.path("limitations"));
        var control = analysis.path("controlFlow");
        var graphs = control.path("procedures");
        addArray(result, "procedures", graphs);
        addArray(result, "controlLimitations", control.path("limitations"));
        if (graphs.isArray() && procedure < graphs.size()) {
            addArray(result, "nodes", graphs.get(procedure).path("nodes"));
            addArray(result, "edges", graphs.get(procedure).path("edges"));
        }
        return result;
    }

    private static void addArray(Map<String, JsonNode> sections, String name, JsonNode value) {
        if (value.isArray()) sections.put(name, value);
    }

    private void authorize(Set<StoredCobolSearch.Origin> origins, Set<String> scope, BooleanSupplier cancelled) {
        // One grant per document, even if it is evidence both as content and as analysis.
        var documents = new LinkedHashMap<PageKey, StoredCobolSearch.Origin>();
        for (var origin : origins) documents.putIfAbsent(origin.key(), origin);
        for (var origin : documents.values()) { checkpoint(cancelled); holdings.authorize(readable(origin, scope)); }
        for (var origin : origins) { checkpoint(cancelled); readable(origin, scope); }
        checkpoint(cancelled);
    }

    private DeliveredPage readable(StoredCobolSearch.Origin origin, Set<String> scope) {
        if (!scope.contains(origin.key().sourceId()) || !available().contains(origin.key().sourceId())) throw unavailable();
        return holdings.page(origin.key()).filter(page -> page.header().key().equals(origin.key())
                && page.snapshot().key().equals(origin.key())
                && origin.rawSha256().equals(page.snapshot().payload().rawSha256())).orElseThrow(ProgramMcpTools::unavailable);
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("Source or current evidence unavailable"); }

    private static void checkpoint(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static String optionalText(JsonNode args, String key, int max) {
        var value = args.path(key);
        if (value.isMissingNode()) return null;
        if (!value.isString() || value.asString().length() > max) throw new IllegalArgumentException("Invalid " + key);
        return value.asString();
    }

    private static String requiredText(JsonNode args, String key, int max) {
        String value = optionalText(args, key, max);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static int number(JsonNode args, String key, int fallback, int min, int max) {
        var value = args.path(key);
        if (value.isMissingNode()) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("Invalid " + key);
        int n = value.asInt();
        if (n < min || n > max) throw new IllegalArgumentException("Invalid " + key);
        return n;
    }
}
