package com.aresstack.spaceharvester.mainframe.cobol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Query immutable parser facts without reading sources or depending on a presentation. */
public final class CobolSearch {
    public enum Mode { ALL, PROGRAM, COPYBOOK, COPY_USAGE, DATA, CALLERS, CALLEES, CALLERS_HIERARCHY, CALLEES_HIERARCHY }
    public enum Kind { PROGRAM, COPYBOOK, COPYBOOK_CANDIDATE, COPY_USAGE, DATA, CALL, DYNAMIC_CALL }

    public record Fact(Kind kind, String name, String program, String member, String source, int line,
                       String revision, Map<String, String> attributes, boolean partial) {
        public Fact {
            attributes = Map.copyOf(attributes);
        }
    }

    public record Result(List<Fact> hits, int total) {
        public Result {
            hits = List.copyOf(hits);
        }
    }

    private final List<Fact> facts;
    private final CobolCallHierarchy hierarchy;

    public CobolSearch(List<Fact> facts) {
        List<Fact> searchable = new ArrayList<>();
        Map<String, Fact> copybooks = new java.util.LinkedHashMap<>();
        for (Fact fact : facts) {
            if (fact.kind() == Kind.COPYBOOK || fact.kind() == Kind.COPYBOOK_CANDIDATE) {
                String key = normalize(fact.source());
                Fact previous = copybooks.get(key);
                if (previous == null || (fact.kind() == Kind.COPYBOOK && fact.attributes().containsKey("PAGE_ID"))
                        || (previous.kind() == Kind.COPYBOOK_CANDIDATE && fact.kind() == Kind.COPYBOOK)) {
                    copybooks.put(key, fact);
                }
            } else {
                searchable.add(fact);
                if (fact.kind() == Kind.COPY_USAGE) {
                    String resolved = fact.attributes().get("RESOLVED_SOURCE");
                    if (resolved != null && !resolved.isBlank()) {
                        Fact derived = new Fact(Kind.COPYBOOK, fact.name(), "", "",
                                resolved, 0, "", Map.of("STATUS", "In COPY-Auflösung gefunden"), false);
                        copybooks.compute(normalize(resolved), (key, previous) ->
                                previous == null || previous.kind() == Kind.COPYBOOK_CANDIDATE ? derived : previous);
                    }
                }
            }
        }
        searchable.addAll(copybooks.values());
        this.facts = List.copyOf(searchable);
        this.hierarchy = new CobolCallHierarchy(this.facts);
    }

    /** Reuse the immutable stored fact set for language-neutral search adapters. */
    public List<Fact> facts() { return facts; }

    public Result search(Mode mode, String query, int limit, int offset) {
        Objects.requireNonNull(mode);
        if (limit < 1 || limit > 500 || offset < 0) {
            throw new IllegalArgumentException("Invalid search page");
        }
        if (mode == Mode.CALLERS_HIERARCHY || mode == Mode.CALLEES_HIERARCHY) {
            List<Fact> rows = hierarchy.find(normalize(query), mode == Mode.CALLERS_HIERARCHY);
            int start = Math.min(offset, rows.size());
            int end = start + Math.min(limit, rows.size() - start);
            return new Result(rows.subList(start, end), rows.size());
        }
        String needle = normalize(query);
        List<Fact> hits = new ArrayList<>();
        int total = 0;
        for (Fact fact : facts) {
            if (matches(mode, fact, needle)) {
                if (total >= offset && hits.size() < limit) {
                    hits.add(fact);
                }
                total++;
            }
        }
        return new Result(hits, total);
    }

    private boolean matches(Mode mode, Fact fact, String needle) {
        return switch (mode) {
            case ALL -> contains(fact.name(), needle) || contains(fact.program(), needle)
                    || contains(fact.member(), needle) || contains(fact.source(), needle);
            case PROGRAM -> fact.kind() == Kind.PROGRAM
                    && (contains(fact.name(), needle) || contains(fact.member(), needle));
            case COPYBOOK -> (fact.kind() == Kind.COPYBOOK || fact.kind() == Kind.COPYBOOK_CANDIDATE)
                    && (contains(fact.name(), needle) || contains(fact.source(), needle));
            case COPY_USAGE -> fact.kind() == Kind.COPY_USAGE
                    && (contains(fact.name(), needle) || contains(fact.attributes().get("RESOLVED_SOURCE"), needle));
            case DATA -> fact.kind() == Kind.DATA && (contains(fact.name(), needle)
                    || contains(fact.attributes().get("QUALIFIED_NAME"), needle));
            case CALLERS -> fact.kind() == Kind.CALL && contains(fact.name(), needle);
            case CALLEES -> (fact.kind() == Kind.CALL || fact.kind() == Kind.DYNAMIC_CALL)
                    && (contains(fact.program(), needle) || contains(fact.member(), needle));
            case CALLERS_HIERARCHY, CALLEES_HIERARCHY -> throw new IllegalStateException("Hierarchy query handled separately");
        };
    }

    private static boolean contains(String value, String needle) {
        return normalize(value).contains(needle);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    /** Extract facts from a single analyzed member, not from a guessed textual pattern. */
    public static List<Fact> programFacts(String member, String revision, CobolAnalysis analysis) {
        List<String> names = analysis.elements().stream()
                .filter(e -> e.kind() == CobolAnalysis.Kind.PROGRAM_ID).map(CobolAnalysis.Element::name).distinct().toList();
        if (names.isEmpty()) {
            return List.of();
        }
        String program = String.join(", ", names);
        List<Fact> facts = new ArrayList<>();
        for (var element : analysis.elements()) {
            Kind kind = switch (element.kind()) {
                case PROGRAM_ID -> Kind.PROGRAM;
                case COPY_STMT -> Kind.COPY_USAGE;
                case DATA_ITEM, LEVEL_01, LEVEL_77, LEVEL_88 -> Kind.DATA;
                default -> null;
            };
            if (kind != null && !element.span().start().debuggingLine()) {
                Map<String, String> attributes = new java.util.LinkedHashMap<>(element.attributes());
                if (kind == Kind.DATA) {
                    attributes.put("QUALIFIED_NAME", qualifiedName(element, analysis.elements()));
                } else if (kind == Kind.COPY_USAGE) {
                    attributes.put("REFERENCE_SCOPE", member.equals(element.span().start().source())
                            ? "DIRECT" : "NESTED");
                }
                facts.add(fact(kind, element.name(), program, member, revision, element.span().start(),
                        attributes, analysis.isPartial()));
            }
        }
        for (var call : analysis.calls()) {
            if (!call.span().start().debuggingLine()) {
                Kind kind = call.targetKind() == CobolAnalysis.TargetKind.LITERAL ? Kind.CALL : Kind.DYNAMIC_CALL;
                facts.add(fact(kind, call.target(), program, member, revision, call.span().start(),
                        Map.of("TARGET_KIND", call.targetKind().name()), analysis.isPartial()));
            }
        }
        return List.copyOf(facts);
    }

    private static String qualifiedName(CobolAnalysis.Element element, List<CobolAnalysis.Element> elements) {
        List<String> parts = new ArrayList<>();
        parts.add(element.name());
        int parent = element.parentIndex();
        while (parent >= 0 && parent < elements.size()) {
            var enclosing = elements.get(parent);
            if (!enclosing.attributes().containsKey("LEVEL")) break;
            parts.add(enclosing.name());
            parent = enclosing.parentIndex();
        }
        return String.join(" OF ", parts);
    }

    private static Fact fact(Kind kind, String name, String program, String member, String revision,
                             CobolAnalysis.Position position, Map<String, String> attributes, boolean partial) {
        return new Fact(kind, name, program, member, position.source(), position.line(), revision, attributes, partial);
    }
}
