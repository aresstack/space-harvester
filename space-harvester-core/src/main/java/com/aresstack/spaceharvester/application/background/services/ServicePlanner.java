package com.aresstack.spaceharvester.application.background.services;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundPart;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Der Dienst-Planer (D1, Nachtrag ADR 0025): bekommt ein Ziel und eine Seitenmenge und legt EINE Hintergrundgruppe
 * an, je Stufe und Space ein Unterauftrag, die Vorstufen in topologischer Reihenfolge. Eine Stufe wartet je Space auf
 * ihre Vorstufen ({@link com.aresstack.spaceharvester.application.jobs.Jobs#AFTER}), nie auf andere Spaces.
 *
 * <ul>
 *     <li>Ein ausdrücklich gestartetes Ziel startet auch {@link ServiceStart#MANUAL}-Vorstufen;
 *     {@link ServiceStart#DISABLED} startet nie und wird gelb gemeldet.</li>
 *     <li>Eine Vorstufe ohne fehlende Seiten legt nichts an; das Ziel legt seinen Teil je Space immer an, sobald die
 *     Seitenmenge für den Aufrufer nicht leer ist.</li>
 *     <li>Läuft schon ein Teil gleicher Art für Quelle und Space, wird er Vorgänger statt neu angelegt.</li>
 *     <li>Eine gescheiterte, abgebrochene oder gelöschte Vorstufe hält die Kette nicht auf (gelb); eine angehaltene
 *     hält sie an.</li>
 * </ul>
 */
public final class ServicePlanner {

    private final BackgroundJobs background;
    private final AnalysisService.Missing scope;
    private final Map<String, AnalysisService> services = new ConcurrentHashMap<>();

    /**
     * @param scope die Seiten der Menge, die der Aufrufer sehen darf, je Quelle und Space, samt Zahl der übrigen; für
     *              die Teile des Ziels
     */
    public ServicePlanner(BackgroundJobs background, AnalysisService.Missing scope) {
        this.background = Objects.requireNonNull(background, "background");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    /**
     * Meldet einen Dienst an; ein gleichnamiger wird ersetzt.
     *
     * @throws IllegalStateException wenn er mit den angemeldeten Diensten einen Zyklus bildet; dann bleibt alles,
     *                               wie es war
     */
    public synchronized void register(AnalysisService service) {
        Objects.requireNonNull(service, "service");
        AnalysisService previous = services.put(service.id(), service);
        List<String> cycle = cycle();
        if (!cycle.isEmpty()) {
            if (previous == null) {
                services.remove(service.id());
            } else {
                services.put(previous.id(), previous);
            }
            throw new IllegalStateException("Zyklus in den Vorstufen: " + String.join(" → ", cycle));
        }
    }

    /** Der angemeldete Dienst. */
    public Optional<AnalysisService> service(String id) {
        return Optional.ofNullable(services.get(id));
    }

    /** Alle angemeldeten Dienste. */
    public List<AnalysisService> services() {
        return List.copyOf(services.values());
    }

    /**
     * Startet das Ziel ausdrücklich, samt fehlender Vorstufen.
     *
     * @throws IllegalArgumentException wenn das Ziel oder eine Vorstufe nicht angemeldet ist oder das Ziel noch keine
     *                                  Arbeit hat
     */
    public ServicePlan start(AccessContext caller, String target, PageSet pages) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(pages, "pages");
        AnalysisService goal = require(target);
        List<String> notes = new ArrayList<>();
        if (goal.start().get() == ServiceStart.DISABLED) {
            notes.add(skipped(goal));
            return new ServicePlan(Optional.empty(), notes, 0);
        }
        if (goal.kind() == null) {
            throw new IllegalArgumentException("„" + goal.title() + "“ hat noch keine Arbeit");
        }
        Map<String, AnalysisService> all = new HashMap<>(services);
        List<AnalysisService> order = order(goal, all);
        // Je Dienst und Bereich: worauf ein Nachfolger warten muss (eigener Teil oder wiederverwendeter Auftrag).
        Map<String, Map<MissingPages.Scope, List<Ref>>> produced = new HashMap<>();
        List<BackgroundJobs.Step> steps = new ArrayList<>();
        int inaccessible = 0;
        for (AnalysisService service : order) {
            boolean isGoal = service == goal;
            Map<MissingPages.Scope, List<Ref>> own = new LinkedHashMap<>();
            produced.put(service.id(), own);
            if (!isGoal && service.start().get() == ServiceStart.DISABLED) {
                notes.add(skipped(service));
                continue;
            }
            if (!isGoal && service.kind() == null) {
                continue;
            }
            MissingPages missing = isGoal ? scope.of(caller, pages) : service.missing().of(caller, pages);
            Map<String, String> given = new LinkedHashMap<>(service.parameters().of(caller));
            given.remove(AnalysisService.PAGES);
            inaccessible = Math.max(inaccessible, missing.inaccessible());
            for (Map.Entry<MissingPages.Scope, Set<String>> entry : missing.pages().entrySet()) {
                MissingPages.Scope area = entry.getKey();
                List<Ref> after = new ArrayList<>(new LinkedHashSet<>(waitsFor(service, area, produced, all)));
                Set<String> rest = new java.util.TreeSet<>(entry.getValue());
                List<Ref> refs = new ArrayList<>();
                if (!isGoal) {
                    // Nur Vorstufen verwenden einen laufenden Teil wieder, und nur für die Seiten, die er abdeckt; für
                    // den Rest entsteht ein eigener Teil (Abnahme D1). Das Ziel legt seine Teile immer an.
                    Optional<JobStatus> running = background.running(service.kind(), area.sourceId(),
                            area.spaceKey());
                    if (running.isPresent()) {
                        rest.removeAll(pages(running.get()));
                        refs.add(new Ref(running.get().id(), -1, service.title()));
                    }
                }
                if (rest.isEmpty()) {
                    own.put(area, refs);
                    continue;
                }
                refs.add(new Ref(null, steps.size(), service.title()));
                own.put(area, refs);
                steps.add(new BackgroundJobs.Step(service.kind(), service.title(),
                        new BackgroundPart.Draft(area.sourceId(), area.spaceKey(),
                                withPages(given, rest)),
                        after.stream().filter(ref -> ref.jobId() != null).map(Ref::jobId).toList(),
                        after.stream().filter(ref -> ref.jobId() == null).map(Ref::step).toList(),
                        after.stream().map(Ref::title).toList()));
            }
        }
        if (inaccessible > 0) {
            notes.add(inaccessible + (inaccessible == 1 ? " Seite" : " Seiten") + " ohne Zugriff ausgelassen");
        }
        if (steps.isEmpty()) {
            return new ServicePlan(Optional.empty(), notes, inaccessible);
        }
        BackgroundGroup group = background.startSteps(BackgroundJobs.newGroupId(), goal.kind(), goal.title(), steps);
        return new ServicePlan(Optional.of(group), notes, inaccessible);
    }

    /** Die Angaben eines Teils: die des Dienstes und seine Seiten. */
    private static Map<String, String> withPages(Map<String, String> given, Set<String> pages) {
        Map<String, String> parameters = new LinkedHashMap<>(given);
        parameters.put(AnalysisService.PAGES, String.join("\n", pages));
        return parameters;
    }

    /** Die Seiten eines laufenden Teils ({@link AnalysisService#PAGES}). */
    private static Set<String> pages(JobStatus status) {
        String pages = status.parameter(AnalysisService.PAGES);
        if (pages == null || pages.isBlank()) {
            return Set.of();
        }
        Set<String> ids = new java.util.HashSet<>();
        for (String id : pages.split("\n")) {
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Worauf ein Dienst in einem Bereich wartet: je direkter Vorstufe deren Teil, sonst deren Vorstufen. */
    private static List<Ref> waitsFor(AnalysisService service, MissingPages.Scope area,
                                      Map<String, Map<MissingPages.Scope, List<Ref>>> produced,
                                      Map<String, AnalysisService> all) {
        List<Ref> refs = new ArrayList<>();
        for (String prerequisite : service.prerequisites()) {
            refs.addAll(effective(prerequisite, area, produced, all, new java.util.HashSet<>()));
        }
        return refs;
    }

    private static List<Ref> effective(String id, MissingPages.Scope area,
                                       Map<String, Map<MissingPages.Scope, List<Ref>>> produced,
                                       Map<String, AnalysisService> all, Set<String> seen) {
        if (!seen.add(id)) {
            return List.of();
        }
        Map<MissingPages.Scope, List<Ref>> own = produced.get(id);
        if (own != null && own.containsKey(area)) {
            return own.get(area);
        }
        // Leere oder ausgelassene Stufe: ihr Nachfolger wartet auf das, worauf sie gewartet hätte.
        List<Ref> refs = new ArrayList<>();
        Optional.ofNullable(all.get(id)).ifPresent(service -> service.prerequisites()
                .forEach(prerequisite -> refs.addAll(effective(prerequisite, area, produced, all, seen))));
        return refs;
    }

    /** Vorstufen vor ihren Nachfolgern, das Ziel zuletzt. */
    private static List<AnalysisService> order(AnalysisService goal, Map<String, AnalysisService> all) {
        List<AnalysisService> order = new ArrayList<>();
        visit(goal, all, new LinkedHashSet<>(), new java.util.HashSet<>(), order);
        return order;
    }

    private static void visit(AnalysisService service, Map<String, AnalysisService> all, Set<String> path,
                              Set<String> done, List<AnalysisService> order) {
        if (done.contains(service.id())) {
            return;
        }
        if (!path.add(service.id())) {
            throw new IllegalStateException("Zyklus in den Vorstufen bei „" + service.title() + "“");
        }
        for (String prerequisite : service.prerequisites()) {
            AnalysisService before = all.get(prerequisite);
            if (before == null) {
                throw new IllegalArgumentException("Vorstufe „" + prerequisite + "“ von „" + service.title()
                        + "“ ist nicht angemeldet");
            }
            visit(before, all, path, done, order);
        }
        path.remove(service.id());
        done.add(service.id());
        order.add(service);
    }

    /** Ein Zyklus unter den angemeldeten Diensten oder leer; unbekannte Vorstufen zählen hier nicht. */
    private List<String> cycle() {
        Map<String, Integer> state = new HashMap<>();
        for (String id : services.keySet()) {
            List<String> path = new ArrayList<>();
            if (cycleFrom(id, state, path)) {
                return path;
            }
        }
        return List.of();
    }

    private boolean cycleFrom(String id, Map<String, Integer> state, List<String> path) {
        Integer seen = state.get(id);
        if (seen != null) {
            if (seen == 1) {
                path.add(id);
                return true;
            }
            return false;
        }
        AnalysisService service = services.get(id);
        if (service == null) {
            return false;
        }
        state.put(id, 1);
        path.add(id);
        for (String prerequisite : service.prerequisites()) {
            if (cycleFrom(prerequisite, state, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        state.put(id, 2);
        return false;
    }

    private AnalysisService require(String id) {
        AnalysisService service = id == null ? null : services.get(id);
        if (service == null) {
            throw new IllegalArgumentException("Dienst „" + id + "“ ist nicht angemeldet");
        }
        return service;
    }

    private static String skipped(AnalysisService service) {
        return "„" + service.title() + "“ ist abgeschaltet und wurde bewusst ausgelassen";
    }

    /** Ein Vorgänger: ein bestehender Auftrag oder ein Teil dieser Gruppe. */
    private record Ref(String jobId, int step, String title) {
    }
}
