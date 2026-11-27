package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link TaskBoard} in memory: for tests, the benchmark and a session without a project folder. Thread-safe;
 * insertion order is kept, so {@link #tasks()} is oldest first like the file store.
 */
public final class InMemoryTaskBoard implements TaskBoard {

    private final Map<String, ResearchTask> tasks = new LinkedHashMap<String, ResearchTask>();
    private OrchestratorRun run;

    @Override
    public synchronized List<ResearchTask> tasks() {
        return new ArrayList<ResearchTask>(tasks.values());
    }

    @Override
    public synchronized void save(ResearchTask task) {
        if (task != null) {
            tasks.put(task.getTaskId(), task);
        }
    }

    @Override
    public synchronized OrchestratorRun currentRun() {
        return run;
    }

    @Override
    public synchronized void saveRun(OrchestratorRun run) {
        this.run = run;
    }
}
