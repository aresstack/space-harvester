package com.aresstack.spaceharvester.mainframe.cobol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch.Fact;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch.Kind;

/** Traverse static call sites in one analysis snapshot without guessing ambiguous targets. */
final class CobolCallHierarchy {
    private final List<Fact> programs = new ArrayList<>();
    private final Map<String, List<Fact>> programsByName = new LinkedHashMap<>();
    private final Map<String, List<Fact>> programsByMember = new LinkedHashMap<>();
    private final Map<String, List<Fact>> callsByMember = new LinkedHashMap<>();
    private final Map<String, List<Fact>> staticCallsByName = new LinkedHashMap<>();

    CobolCallHierarchy(List<Fact> facts) {
        for (Fact fact : facts) {
            if (fact.kind() == Kind.PROGRAM) {
                programs.add(fact);
                index(programsByName, fact.name(), fact);
                index(programsByMember, fact.member(), fact);
            } else if (fact.kind() == Kind.CALL || fact.kind() == Kind.DYNAMIC_CALL) {
                index(callsByMember, fact.member(), fact);
                if (fact.kind() == Kind.CALL) {
                    index(staticCallsByName, fact.name(), fact);
                }
            }
        }
    }

    List<Fact> find(String query, boolean incoming) {
        List<Fact> roots = findRoots(query);
        if (roots.size() != 1 || query.trim().isEmpty()) {
            List<Fact> choices = new ArrayList<>();
            for (Fact root : roots) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("HIERARCHY_STATUS", "SELECT_ROOT");
                details.put("ROOT_SELECTOR", selector(root));
                choices.add(decorate(root, details));
            }
            return choices;
        }
        return traverse(roots.get(0), incoming);
    }

    private List<Fact> findRoots(String query) {
        String needle = normalize(query);
        List<Fact> matches = new ArrayList<>();
        for (Fact program : programs) {
            if (needle.isEmpty() || needle.equals(normalize(program.name()))
                    || needle.equals(normalize(program.member()))
                    || needle.equals(normalize(selector(program)))) {
                matches.add(program);
            }
        }
        return matches;
    }

    private List<Fact> traverse(Fact root, boolean incoming) {
        List<Fact> rows = new ArrayList<>();
        Map<String, String> rootDetails = new LinkedHashMap<>();
        rootDetails.put("HIERARCHY_STATUS", "ROOT");
        rootDetails.put("HIERARCHY_DEPTH", "0");
        rootDetails.put("ROOT_SELECTOR", selector(root));
        rows.add(decorate(root, rootDetails));
        Deque<Visit> pending = new ArrayDeque<>();
        Set<Fact> discovered = new HashSet<>();
        discovered.add(root);
        pending.add(new Visit(root, 0));
        while (!pending.isEmpty()) {
            Visit visit = pending.removeFirst();
            List<Fact> calls = incoming
                    ? lookup(staticCallsByName, visit.program.name())
                    : lookup(callsByMember, visit.program.member());
            for (Fact call : calls) {
                List<Fact> owners = lookup(programsByMember, call.member());
                List<Fact> targets = call.kind() == Kind.DYNAMIC_CALL
                        ? Collections.emptyList() : lookup(programsByName, call.name());
                Fact next = unique(incoming ? owners : targets);
                String status = status(call, owners, targets);
                boolean resolved = "RESOLVED_IN_SNAPSHOT".equals(status);
                boolean revisited = resolved && !discovered.add(next);
                rows.add(edge(call, visit, owners, targets, status, revisited, incoming));
                if (resolved && !revisited) {
                    pending.addLast(new Visit(next, visit.depth + 1));
                }
            }
        }
        return rows;
    }

    private String status(Fact call, List<Fact> owners, List<Fact> targets) {
        if (owners.size() != 1) return "AMBIGUOUS_CALLER";
        if (call.kind() == Kind.DYNAMIC_CALL) return "DYNAMIC_TARGET";
        if (targets.isEmpty()) return "TARGET_NOT_IN_SNAPSHOT";
        if (targets.size() > 1) return "AMBIGUOUS_TARGET";
        return "RESOLVED_IN_SNAPSHOT";
    }

    private Fact edge(Fact call, Visit visit, List<Fact> owners, List<Fact> targets,
                      String status, boolean revisited, boolean incoming) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("HIERARCHY_DEPTH", Integer.toString(visit.depth + 1));
        details.put("HIERARCHY_DIRECTION", incoming ? "INCOMING" : "OUTGOING");
        details.put("HIERARCHY_STATUS", status);
        details.put("HIERARCHY_FROM", selector(visit.program));
        details.put("CALLER_CANDIDATES", selectors(owners));
        details.put("TARGET_CANDIDATES", selectors(targets));
        if (revisited) details.put("ALREADY_VISITED", "true");
        return decorate(call, details);
    }

    private static Fact decorate(Fact fact, Map<String, String> details) {
        Map<String, String> attributes = new LinkedHashMap<>(fact.attributes());
        attributes.putAll(details);
        return new Fact(fact.kind(), fact.name(), fact.program(), fact.member(), fact.source(),
                fact.line(), fact.revision(), attributes, fact.partial());
    }

    private static String selectors(List<Fact> candidates) {
        List<String> values = new ArrayList<>();
        for (Fact candidate : candidates) values.add(selector(candidate));
        return String.join(", ", values);
    }

    private static String selector(Fact program) {
        return program.member() + "#" + program.name() + "@" + program.line();
    }

    private static Fact unique(List<Fact> facts) {
        return facts.size() == 1 ? facts.get(0) : null;
    }

    private static void index(Map<String, List<Fact>> index, String key, Fact fact) {
        index.computeIfAbsent(normalize(key), ignored -> new ArrayList<>()).add(fact);
    }

    private static List<Fact> lookup(Map<String, List<Fact>> index, String key) {
        return index.getOrDefault(normalize(key), Collections.emptyList());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static final class Visit {
        private final Fact program;
        private final int depth;

        private Visit(Fact program, int depth) {
            this.program = program;
            this.depth = depth;
        }
    }
}
