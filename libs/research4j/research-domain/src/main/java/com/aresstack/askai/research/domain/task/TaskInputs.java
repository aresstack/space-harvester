package com.aresstack.askai.research.domain.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a worker gets: the brief the orchestrator wrote and REFERENCES to project artifacts (source, outline
 * section, concept ids). The project store stays the blackboard; a task never carries copies of its content.
 */
public final class TaskInputs {

    private static final TaskInputs EMPTY = new TaskInputs("", null);

    private final String brief;
    private final List<String> artifactRefs;

    public TaskInputs(String brief, List<String> artifactRefs) {
        this.brief = brief == null ? "" : brief.trim();
        List<String> refs = new ArrayList<String>();
        if (artifactRefs != null) {
            for (String ref : artifactRefs) {
                if (ref != null && !ref.trim().isEmpty() && !refs.contains(ref.trim())) {
                    refs.add(ref.trim());
                }
            }
        }
        this.artifactRefs = Collections.unmodifiableList(refs);
    }

    public static TaskInputs empty() {
        return EMPTY;
    }

    public String getBrief() {
        return brief;
    }

    /** Ids of project artifacts the worker may read, de-duplicated, in the order given. */
    public List<String> getArtifactRefs() {
        return artifactRefs;
    }
}
