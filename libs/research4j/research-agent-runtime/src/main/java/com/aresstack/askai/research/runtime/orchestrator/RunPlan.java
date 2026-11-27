package com.aresstack.askai.research.runtime.orchestrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The orchestrator's initial decomposition of the approved question: work packages and why (schema output). */
public final class RunPlan {

    private final String rationale;
    private final List<PlannedTask> tasks;

    public RunPlan(String rationale, List<PlannedTask> tasks) {
        this.rationale = rationale == null ? "" : rationale.trim();
        this.tasks = tasks == null ? Collections.<PlannedTask>emptyList()
                : Collections.unmodifiableList(new ArrayList<PlannedTask>(tasks));
    }

    public String getRationale() {
        return rationale;
    }

    public List<PlannedTask> getTasks() {
        return tasks;
    }
}
