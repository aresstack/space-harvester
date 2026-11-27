package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskBudget;
import com.aresstack.askai.research.domain.task.TaskReceipt;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.team.MainModelChat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** A worker's view for unit tests: a fixed run on the blackboard, scripted chats, a fake source lane. */
final class FakeWorkerContext implements WorkerContext {

    final ResearchTask task;
    final List<ResearchTask> run = new ArrayList<ResearchTask>();
    final ScriptedChats chats = new ScriptedChats(10);
    final List<String> logs = new ArrayList<String>();
    final List<TaskReceipt> receipts = new ArrayList<TaskReceipt>();
    ResearchTask recorded;
    private final MainModelChat chat;
    private long clock = 100L;

    final AtomicLong tokens = new AtomicLong();

    FakeWorkerContext(ResearchTask task, ResearchRole role) {
        this.task = task;
        this.recorded = task;
        this.chat = chats.chatFor(role, tokens);
    }

    /** A completed upstream task of the run with the given artifacts. */
    static ResearchTask completed(String id, String role, List<String> sections, TaskArtifact... artifacts) {
        ResearchTask task = ResearchTask.submit(id, "research", role, Collections.<String>emptyList(),
                TaskBriefs.inputsOf(new PlannedTask(id.substring(id.indexOf('.') + 1), role, "q " + id, "",
                        sections, Collections.<String>emptyList())), "", 0, TaskBudget.unlimited(), 1, 1L)
                .start("local", 2L);
        for (TaskArtifact artifact : artifacts) {
            task = task.withArtifact(artifact);
        }
        return task.complete(3L);
    }

    /** The task under test, depending on {@code dependsOn}. */
    static ResearchTask task(String id, String role, String query, List<String> sections, String... dependsOn) {
        return ResearchTask.submit(id, "research", role, Arrays.asList(dependsOn),
                TaskBriefs.inputsOf(new PlannedTask(id.substring(id.indexOf('.') + 1), role, query, "a brief",
                        sections, Collections.<String>emptyList())), "", 0, TaskBudget.unlimited(), 1, 1L)
                .start("local", 2L);
    }

    static TaskArtifact source(String id, String url, String title) {
        return new TaskArtifact("a-" + id, TaskArtifact.Kind.SOURCE_REFERENCE, url + " " + title, id, 2L);
    }

    static TaskArtifact artifact(TaskArtifact.Kind kind, String content, String ref, long at) {
        return new TaskArtifact("x-" + ref + "-" + at, kind, content, ref, at);
    }

    List<TaskArtifact> artifacts(TaskArtifact.Kind kind) {
        List<TaskArtifact> out = new ArrayList<TaskArtifact>();
        for (TaskArtifact artifact : recorded.getArtifacts()) {
            if (artifact.getKind() == kind) {
                out.add(artifact);
            }
        }
        return out;
    }

    public String query() {
        return TaskBriefs.queryOf(task.getInputs());
    }

    public String brief() {
        return TaskBriefs.briefOf(task.getInputs());
    }

    public List<ResearchTask> dependencies() {
        List<ResearchTask> out = new ArrayList<ResearchTask>();
        for (String id : task.getDependsOn()) {
            for (ResearchTask candidate : run) {
                if (candidate.getTaskId().equals(id)) {
                    out.add(candidate);
                }
            }
        }
        return out;
    }

    public List<ResearchTask> runTasks() {
        List<ResearchTask> all = new ArrayList<ResearchTask>(run);
        all.add(recorded);
        return all;
    }

    public AtomicLong tokens() {
        return tokens;
    }

    public MainModelChat chat() {
        return chat;
    }

    public ToolInvoker permitted(ToolInvoker lane) {
        return lane;
    }

    public void artifact(TaskArtifact.Kind kind, String content, String projectRef) {
        recorded = recorded.withArtifact(new TaskArtifact(recorded.getTaskId() + ".a"
                + (recorded.getArtifacts().size() + 1), kind, content, projectRef, ++clock));
    }

    public void receipt(String toolName, boolean succeeded, String receipt) {
        receipts.add(new TaskReceipt(toolName, succeeded, receipt, ++clock));
    }

    public void log(String line) {
        logs.add(line);
    }

    public boolean isCancelled() {
        return false;
    }

    public boolean isBudgetExhausted() {
        return false;
    }

    /** The research lane's {@code source_review_context}, served from {@code texts} (id → text). */
    static final class SourceLane implements ToolInvoker {
        final Map<String, String> texts = new LinkedHashMap<String, String>();
        final List<Map<String, Object>> calls = new ArrayList<Map<String, Object>>();
        volatile boolean down;
        /** The host's review-context source cap; 0 = none. */
        int maxSources;

        SourceLane maxSources(int max) {
            this.maxSources = max;
            return this;
        }

        SourceLane text(String id, String text) {
            texts.put(id, text);
            return this;
        }

        public String call(String toolName, Map<String, Object> args) throws ToolFailure, EndpointUnavailable {
            calls.add(new HashMap<String, Object>(args));
            if (down) {
                throw new EndpointUnavailable("research lane closed");
            }
            if (!SourceMaterial.TOOL.equals(toolName)) {
                throw new ToolFailure("unexpected tool " + toolName);
            }
            List<String> wanted = Arrays.asList(String.valueOf(args.get("source_ids")).split(","));
            StringBuilder sb = new StringBuilder();
            int matching = 0;
            int rendered = 0;
            for (Map.Entry<String, String> source : texts.entrySet()) {
                if (!wanted.contains(source.getKey())) {
                    continue;
                }
                matching++;
                if (maxSources > 0 && rendered >= maxSources) {
                    continue;
                }
                rendered++;
                sb.append("--- ").append(source.getKey()).append('\n')
                        .append("title: Title of ").append(source.getKey()).append('\n')
                        .append("url: https://site.test/").append(source.getKey()).append('\n')
                        .append("text: ").append(source.getValue().isEmpty()
                                ? "(no readable text was captured for this source)" : source.getValue())
                        .append('\n');
            }
            if (matching > rendered) {
                sb.append("(").append(matching - rendered)
                        .append(" further sources not shown in this review context)\n");
            }
            return sb.length() == 0 ? "No sources to review." : sb.toString();
        }
    }
}
