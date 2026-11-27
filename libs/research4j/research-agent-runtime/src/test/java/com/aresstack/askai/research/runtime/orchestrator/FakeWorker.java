package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.role.RoleToolCatalog;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** A web-research stand-in: each query yields the sources scripted for it; tracks concurrency and order. */
final class FakeWorker implements TaskWorker {

    static final ResearchRole ROLE = ResearchRole.builder("web-researcher", ResearchModelSlots.JUDGE)
            .purpose("fake web research").promptFromCaller()
            .tools(RoleToolCatalog.of("source_accept"))
            .output(RoleOutput.text("fake")).build();

    final Map<String, List<String>> sourcesByQuery = new ConcurrentHashMap<String, List<String>>();
    final List<String> executed = new CopyOnWriteArrayList<String>();
    final AtomicInteger running = new AtomicInteger();
    final AtomicInteger maxRunning = new AtomicInteger();
    volatile long workMillis;
    volatile Function<ResearchTask, WorkerResult> override;

    FakeWorker sources(String query, List<String> urls) {
        sourcesByQuery.put(query, urls);
        return this;
    }

    @Override
    public ResearchRole role() {
        return ROLE;
    }

    @Override
    public boolean needsBrowser() {
        return false;
    }

    @Override
    public WorkerResult execute(ResearchTask task, WorkerContext context) {
        int now = running.incrementAndGet();
        maxRunning.accumulateAndGet(now, Math::max);
        try {
            executed.add(task.getTaskId());
            if (workMillis > 0) {
                Thread.sleep(workMillis);
            }
            Function<ResearchTask, WorkerResult> custom = override;
            if (custom != null) {
                WorkerResult result = custom.apply(task);
                if (result != null) {
                    return result;
                }
            }
            List<String> urls = sourcesByQuery.get(context.query());
            if (urls != null) {
                for (String url : urls) {
                    context.artifact(TaskArtifact.Kind.SOURCE_REFERENCE, url + " title", "src-" + url.hashCode());
                }
            }
            return WorkerResult.completed("ok");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return WorkerResult.canceled("interrupted");
        } finally {
            running.decrementAndGet();
        }
    }
}
