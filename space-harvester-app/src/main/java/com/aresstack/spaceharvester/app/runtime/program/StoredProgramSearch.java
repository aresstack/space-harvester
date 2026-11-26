package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch;
import com.aresstack.spaceharvester.mainframe.parser.JclAnalysis;
import com.aresstack.spaceharvester.mainframe.parser.NaturalAnalysis;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Combine stored language-specific facts without introducing another durable index. */
public final class StoredProgramSearch {
    public enum Language { COBOL, JCL, NATURAL }
    public enum Kind { OBJECT, COPYBOOK, COPY_USAGE, DATA, CALL, DYNAMIC_CALL, DEPENDENCY, RESOURCE }
    public enum Mode { ALL, OBJECTS, COPYBOOKS, COPY_USAGE, DATA, CALLERS, CALLEES, DEPENDENCIES, RESOURCES, CANDIDATES, COBOL_CALLERS_HIERARCHY, COBOL_CALLEES_HIERARCHY }
    public enum Target { NONE, COBOL_PROGRAM, NATURAL_SUBPROGRAM, NATURAL_PROGRAM, NATURAL_SUBROUTINE,
        NATURAL_COPYCODE, NATURAL_DATA_AREA, NATURAL_MAP, NATURAL_DDM, JCL_PROCEDURE }

    public record Location(PageKey key, String rawSha256, int line, String path) { }
    public record Fact(Language language, Kind kind, String name, String owner, String detail,
                       Location location, List<StoredCobolSearch.Origin> evidence,
                       Target declaredType, Target targetType, boolean partial) {
        public Fact { evidence = List.copyOf(evidence); }
    }
    public record Match(Fact fact, Fact candidate, String rootSelector) {
        public Match(Fact fact, Fact candidate) { this(fact, candidate, null); }
    }
    public record Result(List<Match> hits, long total) {
        public Result { hits = List.copyOf(hits); }
    }

    private record TargetKey(Target type, String name) { }

    public static final class Snapshot {
        private final List<Fact> facts;
        private final Map<TargetKey, List<Fact>> declarations;
        private final Map<String, StoredCobolSearch.Snapshot> cobol;
        private final List<String> coverage;

        Snapshot(List<Fact> facts, Map<String, StoredCobolSearch.Snapshot> cobol, List<String> coverage) {
            this.facts = List.copyOf(facts);
            this.cobol = Map.copyOf(cobol);
            this.coverage = List.copyOf(coverage);
            var index = new LinkedHashMap<TargetKey, List<Fact>>();
            for (var fact : facts) {
                if (fact.declaredType() == Target.NONE) continue;
                index.computeIfAbsent(new TargetKey(fact.declaredType(), normalize(fact.name())), ignored -> new ArrayList<>()).add(fact);
            }
            declarations = Map.copyOf(index);
        }

        public Map<String, StoredCobolSearch.Snapshot> cobol() { return cobol; }
        public List<String> coverage() { return coverage; }

        public Result search(Mode mode, Language language, String source, String query, int limit, long offset) {
            if (limit < 1 || limit > 500 || offset < 0) throw new IllegalArgumentException("Invalid search page");
            if (mode == Mode.COBOL_CALLERS_HIERARCHY || mode == Mode.COBOL_CALLEES_HIERARCHY) {
                if (source == null || !cobol.containsKey(source) || offset > Integer.MAX_VALUE) return new Result(List.of(), 0);
                var snapshot = cobol.get(source);
                var result = snapshot.search().search(mode == Mode.COBOL_CALLERS_HIERARCHY
                        ? CobolSearch.Mode.CALLERS_HIERARCHY : CobolSearch.Mode.CALLEES_HIERARCHY,
                        query, limit, (int) offset);
                var hits = new ArrayList<Match>();
                for (var fact : result.hits()) {
                    var converted = cobolFact(snapshot, fact);
                    if (converted != null) hits.add(new Match(converted, null, fact.attributes().get("ROOT_SELECTOR")));
                }
                return new Result(hits, result.total());
            }
            String needle = normalize(query);
            var hits = new ArrayList<Match>();
            long total = 0;
            for (var fact : facts) {
                checkpoint(() -> false);
                if (language != null && fact.language() != language) continue;
                if (source != null && !source.equals(fact.location().key().sourceId())) continue;
                if (!matches(mode, fact, needle)) continue;
                if (mode == Mode.CANDIDATES) {
                    // Match by explicit reference kind and exact normalized name, never by substring.
                    for (var candidate : declarations.getOrDefault(new TargetKey(fact.targetType(), normalize(fact.name())), List.of())) {
                        checkpoint(() -> false);
                        if (total >= offset && hits.size() < limit) hits.add(new Match(fact, candidate));
                        total++;
                    }
                } else {
                    if (total >= offset && hits.size() < limit) hits.add(new Match(fact, null));
                    total++;
                }
            }
            return new Result(hits, total);
        }

        private boolean matches(Mode mode, Fact fact, String needle) {
            return switch (mode) {
                case ALL -> contains(fact.name(), needle) || contains(fact.owner(), needle)
                        || contains(fact.location().path(), needle) || contains(fact.detail(), needle);
                case OBJECTS -> Set.of(Kind.OBJECT, Kind.COPYBOOK).contains(fact.kind())
                        && (contains(fact.name(), needle) || contains(fact.location().path(), needle));
                case COPYBOOKS -> (fact.kind() == Kind.COPYBOOK || fact.declaredType() == Target.NATURAL_COPYCODE)
                        && (contains(fact.name(), needle) || contains(fact.location().path(), needle));
                case COPY_USAGE -> fact.kind() == Kind.COPY_USAGE
                        && (contains(fact.name(), needle) || contains(fact.detail(), needle));
                case DATA -> fact.kind() == Kind.DATA && (contains(fact.name(), needle) || contains(fact.detail(), needle));
                case CALLERS -> fact.kind() == Kind.CALL && contains(fact.name(), needle);
                case CALLEES -> Set.of(Kind.CALL, Kind.DYNAMIC_CALL).contains(fact.kind()) && contains(fact.owner(), needle);
                case DEPENDENCIES -> Set.of(Kind.DEPENDENCY, Kind.COPY_USAGE, Kind.CALL, Kind.DYNAMIC_CALL).contains(fact.kind())
                        && (contains(fact.name(), needle) || contains(fact.owner(), needle));
                case RESOURCES -> fact.kind() == Kind.RESOURCE && contains(fact.name(), needle);
                case CANDIDATES -> fact.targetType() != Target.NONE
                        && (contains(fact.name(), needle) || contains(fact.owner(), needle));
                case COBOL_CALLERS_HIERARCHY, COBOL_CALLEES_HIERARCHY -> false;
            };
        }
    }

    private final Function<PageKey, Optional<DeliveredPage>> pages;
    private final Function<DeliveredPage, List<Enrichments.View>> analyses;
    private final List<Fact> facts = new ArrayList<>();
    private final Map<String, StoredCobolSearch.Snapshot> cobol = new LinkedHashMap<>();
    private final List<String> coverage = new ArrayList<>();
    private final ProgramFactMemo memo;

    public StoredProgramSearch(Function<PageKey, Optional<DeliveredPage>> pages,
                               Function<DeliveredPage, List<Enrichments.View>> analyses) {
        this(pages, analyses, new ProgramFactMemo());
    }

    /** @param memo analyses decoded by earlier searches, reused while unchanged (#1486, P2) */
    public StoredProgramSearch(Function<PageKey, Optional<DeliveredPage>> pages,
                               Function<DeliveredPage, List<Enrichments.View>> analyses, ProgramFactMemo memo) {
        this.pages = pages;
        this.analyses = analyses;
        this.memo = memo;
    }

    public void addCobol(StoredCobolSearch.Snapshot snapshot, BooleanSupplier cancelled) {
        addCobol(facts.size(), coverage.size(), snapshot, cancelled);
    }

    private void addCobol(int factIndex, int coverageIndex, StoredCobolSearch.Snapshot snapshot, BooleanSupplier cancelled) {
        cobol.put(snapshot.sourceId(), snapshot);
        coverage.add(coverageIndex, snapshot.sourceId() + " / COBOL: " + snapshot.coverage());
        var converted = new ArrayList<Fact>();
        for (var fact : snapshot.search().facts()) {
            checkpoint(cancelled);
            var item = cobolFact(snapshot, fact);
            if (item != null) converted.add(item);
        }
        facts.addAll(factIndex, converted);
    }

    /**
     * COBOL and JCL of one FTP source in a single traversal of its headers, loading each stored page once (#1486, P2).
     * Results and their order equal {@link #addCobol} followed by {@link #addSource}.
     */
    public void addFtp(String sourceId, StoredCobolSearch cobolSearch, List<String> syslib,
                       Consumer<Consumer<PageHeader>> visitHeaders, BooleanSupplier cancelled) {
        int factIndex = facts.size();
        int coverageIndex = coverage.size();
        var collector = cobolSearch.collector(sourceId, syslib, cancelled);
        int[] counts = new int[3];
        visitHeaders.accept(header -> {
            checkpoint(cancelled);
            if (!sourceId.equals(header.key().sourceId())) return;
            var stored = pages.apply(header.key());
            collector.accept(header, () -> stored);
            program(false, header, stored, counts);
        });
        checkpoint(cancelled);
        addCobol(factIndex, coverageIndex, collector.result(), cancelled);
        coverage(sourceId, false, counts);
    }

    private static Fact cobolFact(StoredCobolSearch.Snapshot snapshot, CobolSearch.Fact fact) {
                var attrs = fact.attributes();
                String pageId = attrs.get("PAGE_ID");
                String hash = attrs.get("SOURCE_RAW_SHA256");
                if (pageId == null || hash == null || !snapshot.sourceId().equals(attrs.get("SOURCE_ID"))) return null;
                var location = new Location(new PageKey(snapshot.sourceId(), pageId), hash, fact.line(), fact.source());
                var kind = switch (fact.kind()) {
                    case PROGRAM -> Kind.OBJECT;
                    case COPYBOOK, COPYBOOK_CANDIDATE -> Kind.COPYBOOK;
                    case DATA -> Kind.DATA;
                    case COPY_USAGE -> Kind.COPY_USAGE;
                    case CALL -> Kind.CALL;
                    case DYNAMIC_CALL -> Kind.DYNAMIC_CALL;
                };
                Target declared = fact.kind() == CobolSearch.Kind.PROGRAM ? Target.COBOL_PROGRAM : Target.NONE;
                Target target = fact.kind() == CobolSearch.Kind.CALL ? Target.COBOL_PROGRAM : Target.NONE;
                String detail = attrs.entrySet().stream().filter(entry -> !Set.of("SOURCE_ID", "PAGE_ID", "SOURCE_RAW_SHA256",
                        "PROGRAM_PAGE_ID", "PROGRAM_RAW_SHA256").contains(entry.getKey()))
                        .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining(" · "));
                return new Fact(Language.COBOL, kind, fact.name(), fact.program(), detail, location,
                        snapshot.evidenceFor(fact), declared, target, fact.partial());
    }

    public void addSource(String sourceId, boolean ndv, Consumer<Consumer<PageHeader>> visitHeaders,
                          BooleanSupplier cancelled) {
        int[] counts = new int[3];
        visitHeaders.accept(header -> {
            checkpoint(cancelled);
            if (!sourceId.equals(header.key().sourceId())) return;
            program(ndv, header, pages.apply(header.key()), counts);
        });
        checkpoint(cancelled);
        coverage(sourceId, ndv, counts);
    }

    private void coverage(String sourceId, boolean ndv, int[] counts) {
        coverage.add(sourceId + (ndv ? " / Natural" : " / JCL") + ": " + counts[0]
                + " aktuelle Analysen · " + counts[1] + " ohne aktuelle Analyse · " + counts[2]
                + " nicht verfügbare oder nicht unterstützte Objekte");
    }

    private void program(boolean ndv, PageHeader header, Optional<DeliveredPage> stored, int[] counts) {
        if (stored.isEmpty() || !header.key().equals(stored.get().snapshot().key())
                || !header.key().equals(stored.get().header().key())) {
            counts[2]++;
            return;
        }
        var page = stored.get();
        if (ndv) {
            var code = StoredNaturalSource.read(page.snapshot());
            if (code.isEmpty()) { counts[2]++; return; }
            var analysis = current(page, NaturalProgramEnrichment.ID, NaturalProgramEnrichment.VERSION,
                    NaturalProgramEnrichment::read);
            var artifact = analysis.map(Stored::artifact);
            if (artifact.isEmpty() || !page.snapshot().payload().rawSha256().equals(artifact.get().rawSha256())
                    || !code.get().library().equals(artifact.get().analysis().library())
                    || !code.get().name().equals(artifact.get().analysis().name())
                    || !code.get().extension().equals(artifact.get().analysis().extension())) {
                counts[1]++;
                return;
            }
            natural(page, artifact.get().analysis(), analysis.get().identity());
        } else {
            var code = JclProgramEnrichment.source(page.snapshot());
            if (code.isEmpty()) return;
            var analysis = current(page, JclProgramEnrichment.ID, JclProgramEnrichment.VERSION, JclProgramEnrichment::read);
            var artifact = analysis.map(Stored::artifact);
            if (artifact.isEmpty() || !page.snapshot().payload().rawSha256().equals(artifact.get().rawSha256())
                    || !code.get().path().equals(artifact.get().analysis().source())) {
                counts[1]++;
                return;
            }
            jcl(page, artifact.get().analysis(), analysis.get().identity());
        }
        counts[0]++;
    }

    private record Stored<T>(T artifact, String identity) { }

    private <T> Optional<Stored<T>> current(DeliveredPage page, String processor, String version, Function<String, T> decode) {
        var stored = analyses.apply(page).stream().filter(view -> processor.equals(view.enrichment().processorId())
                && view.freshness() == Enrichment.Freshness.CURRENT
                && view.enrichment().status() == Enrichment.Status.OK
                && version.equals(view.enrichment().processorVersion())
                && page.snapshot().key().equals(view.enrichment().key())).findFirst();
        try {
            // The identity covers processor, version and payload, so an unchanged analysis is decoded once.
            return stored.map(view -> {
                String identity = ProgramAnalysisAccess.identity(view.enrichment());
                return memo.facts(page.snapshot().key(), identity,
                        () -> new Stored<>(decode.apply(view.enrichment().payloadJson()), identity));
            });
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private void natural(DeliveredPage page, NaturalAnalysis analysis, String identity) {
        String path = analysis.library() + "/" + analysis.name() + "." + analysis.extension();
        Target declared = switch (analysis.extension()) {
            case "NSP" -> Target.NATURAL_PROGRAM;
            case "NSN" -> Target.NATURAL_SUBPROGRAM;
            case "NSS" -> Target.NATURAL_SUBROUTINE;
            case "NSC" -> Target.NATURAL_COPYCODE;
            case "NSG", "NSA", "NSL" -> Target.NATURAL_DATA_AREA;
            case "NSM" -> Target.NATURAL_MAP;
            case "NSD" -> Target.NATURAL_DDM;
            default -> Target.NONE;
        };
        add(page, identity, Language.NATURAL, Kind.OBJECT, analysis.name(), path, analysis.extension(), path, 0, declared, Target.NONE, true);
        for (var element : analysis.elements()) {
            if (element.kind().startsWith("NAT_DATA_") || element.kind().equals("DDM_FIELD")) {
                add(page, identity, Language.NATURAL, Kind.DATA, element.name(), path, element.attributes().toString(), path, element.line(),
                        Target.NONE, Target.NONE, true);
            }
        }
        for (var dep : analysis.dependencies()) {
            boolean explicit = dep.resolution() == NaturalAnalysis.Resolution.LITERAL;
            boolean call = Set.of(NaturalAnalysis.Kind.CALL, NaturalAnalysis.Kind.CALLNAT, NaturalAnalysis.Kind.FETCH,
                    NaturalAnalysis.Kind.PERFORM).contains(dep.kind());
            var kind = call ? dep.resolution() == NaturalAnalysis.Resolution.DYNAMIC ? Kind.DYNAMIC_CALL : Kind.CALL
                    : Set.of(NaturalAnalysis.Kind.WORK_FILE, NaturalAnalysis.Kind.DATABASE).contains(dep.kind())
                    ? Kind.RESOURCE : dep.kind() == NaturalAnalysis.Kind.COPYCODE ? Kind.COPY_USAGE : Kind.DEPENDENCY;
            Target target = switch (dep.kind()) {
                case CALL -> explicit ? Target.COBOL_PROGRAM : Target.NONE;
                case CALLNAT -> explicit ? Target.NATURAL_SUBPROGRAM : Target.NONE;
                case FETCH -> explicit ? Target.NATURAL_PROGRAM : Target.NONE;
                case PERFORM -> dep.resolution() == NaturalAnalysis.Resolution.UNRESOLVED ? Target.NATURAL_SUBROUTINE : Target.NONE;
                case COPYCODE -> explicit ? Target.NATURAL_COPYCODE : Target.NONE;
                case DATA_AREA -> explicit ? Target.NATURAL_DATA_AREA : Target.NONE;
                case MAP -> explicit ? Target.NATURAL_MAP : Target.NONE;
                case DDM -> explicit ? Target.NATURAL_DDM : Target.NONE;
                default -> Target.NONE;
            };
            add(page, identity, Language.NATURAL, kind, dep.target(), path, dep.kind() + " · " + dep.resolution() + " · " + dep.detail(),
                    path, dep.line(), Target.NONE, target, true);
        }
    }

    private void jcl(DeliveredPage page, JclAnalysis analysis, String identity) {
        String path = analysis.source();
        add(page, identity, Language.JCL, Kind.OBJECT, path, path, "JCL", path, 0, Target.NONE, Target.NONE, analysis.partial());
        for (var statement : analysis.statements()) {
            if (statement.complete() && Set.of("JOB", "PROC", "EXEC").contains(statement.operation()) && !statement.name().isBlank()) {
                add(page, identity, Language.JCL, Kind.OBJECT, statement.name(), path, statement.operation() + " " + statement.operands(),
                        path, statement.line(), statement.operation().equals("PROC") ? Target.JCL_PROCEDURE : Target.NONE,
                        Target.NONE, analysis.partial());
            }
        }
        for (var dep : analysis.dependencies()) {
            boolean literal = dep.resolution() == JclAnalysis.Resolution.LITERAL;
            boolean call = Set.of(JclAnalysis.Kind.PROGRAM, JclAnalysis.Kind.PROCEDURE).contains(dep.kind());
            var kind = call ? dep.resolution() == JclAnalysis.Resolution.SYMBOLIC ? Kind.DYNAMIC_CALL : Kind.CALL
                    : Set.of(JclAnalysis.Kind.INCLUDE, JclAnalysis.Kind.PROCEDURE_LIBRARY).contains(dep.kind()) ? Kind.DEPENDENCY : Kind.RESOURCE;
            Target target = literal ? switch (dep.kind()) {
                case PROGRAM -> Target.COBOL_PROGRAM;
                case PROCEDURE -> Target.JCL_PROCEDURE;
                default -> Target.NONE;
            } : Target.NONE;
            add(page, identity, Language.JCL, kind, dep.target(), path,
                    dep.kind() + " · " + dep.resolution() + " · DD " + dep.ddName() + " · DISP " + dep.disposition(),
                    path, dep.line(), Target.NONE, target, analysis.partial());
        }
    }

    private void add(DeliveredPage page, String identity, Language language, Kind kind, String name, String owner,
                     String detail, String path, int line, Target declared, Target target, boolean partial) {
        var key = page.snapshot().key();
        String hash = page.snapshot().payload().rawSha256();
        facts.add(new Fact(language, kind, name, owner, detail, new Location(key, hash, line, path),
                List.of(new StoredCobolSearch.Origin(key, hash, identity)), declared, target, partial));
    }

    public Snapshot snapshot() { return new Snapshot(facts, cobol, coverage); }

    private static String normalize(String value) { return value == null ? "" : value.strip().toUpperCase(Locale.ROOT); }
    private static boolean contains(String value, String needle) { return normalize(value).contains(needle); }
    private static void checkpoint(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
