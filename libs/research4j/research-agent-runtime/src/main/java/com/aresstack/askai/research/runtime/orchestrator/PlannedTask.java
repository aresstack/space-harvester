package com.aresstack.askai.research.runtime.orchestrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One work package as the orchestrator proposes it (part of a {@link RunPlan} or a follow-up of a
 * {@link RoundDecision}), before the executor turns it into a persisted {@code ResearchTask}. Every field
 * comes from the schema-bound answer and has been validated by {@link OrchestratorDecisionCodec}: the role is
 * one the run may delegate to, the sections exist in the outline, the dependencies name known packages.
 */
public final class PlannedTask {

    private final String id;
    private final String role;
    private final String query;
    private final String brief;
    private final List<String> sections;
    private final List<String> dependsOn;

    public PlannedTask(String id, String role, String query, String brief, List<String> sections,
                       List<String> dependsOn) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("a planned task needs an id");
        }
        if (role == null || role.trim().isEmpty()) {
            throw new IllegalArgumentException("a planned task needs a role");
        }
        this.id = id.trim();
        this.role = role.trim();
        this.query = query == null ? "" : query.trim();
        this.brief = brief == null ? "" : brief.trim();
        this.sections = copy(sections);
        this.dependsOn = copy(dependsOn);
    }

    public String getId() {
        return id;
    }

    /** The worker role id, e.g. {@code web-researcher}. */
    public String getRole() {
        return role;
    }

    /** The search query a research worker runs (the funnel decides what it finds). */
    public String getQuery() {
        return query;
    }

    /** What the worker should look for and why: the orchestrator's brief. */
    public String getBrief() {
        return brief;
    }

    /** The outline sections this package serves. */
    public List<String> getSections() {
        return sections;
    }

    /** Ids of packages of the same run that must complete first (SEQUENTIAL inside a package chain). */
    public List<String> getDependsOn() {
        return dependsOn;
    }

    private static List<String> copy(List<String> values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty() && !out.contains(value.trim())) {
                    out.add(value.trim());
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public String toString() {
        return id + "[" + role + ", sections=" + sections + ", query=" + query + "]";
    }
}
