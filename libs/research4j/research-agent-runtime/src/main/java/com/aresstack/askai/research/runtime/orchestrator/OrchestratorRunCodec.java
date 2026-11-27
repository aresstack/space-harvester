package com.aresstack.askai.research.runtime.orchestrator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The JSON form of an {@link OrchestratorRun} in the project folder (Gson, ADR 0001, 3.1). Written and read
 * field by field, so the record's invariants are checked on load and a broken file is rejected with a reason
 * instead of being half-accepted. {@code format} versions the layout.
 */
public final class OrchestratorRunCodec {

    public static final int FORMAT = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private OrchestratorRunCodec() {
    }

    public static String encode(OrchestratorRun run) {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("runId", run.getRunId());
        o.addProperty("question", run.getQuestion());
        o.addProperty("state", run.getState().wireName());
        o.addProperty("createdAt", run.getCreatedAtMillis());
        o.addProperty("updatedAt", run.getUpdatedAtMillis());
        JsonArray sections = new JsonArray();
        for (ResearchOutline.Section section : run.getSections()) {
            JsonObject s = new JsonObject();
            s.addProperty("id", section.getId());
            s.addProperty("title", section.getTitle());
            sections.add(s);
        }
        o.add("sections", sections);
        o.addProperty("planRationale", run.getPlanRationale());
        o.add("plan", tasks(run.getPlan()));
        o.add("pending", tasks(run.getPending()));
        o.addProperty("waitingReason", run.getWaitingReason());
        JsonObject envelope = new JsonObject();
        envelope.addProperty("maxRounds", run.getMaxRounds());
        envelope.addProperty("maxTasks", run.getMaxTasks());
        envelope.addProperty("tokenBudget", run.getTokenBudget());
        envelope.addProperty("timeBudgetMillis", run.getTimeBudgetMillis());
        envelope.add("sections", strings(run.getApprovedSections()));
        envelope.add("roles", strings(run.getApprovedRoles()));
        o.add("envelope", envelope);
        JsonObject spent = new JsonObject();
        spent.addProperty("rounds", run.getRoundsUsed());
        spent.addProperty("tasks", run.getTasksSubmitted());
        spent.addProperty("tokens", run.getTokensUsed());
        spent.addProperty("timeMillis", run.getTimeUsedMillis());
        o.add("spent", spent);
        o.add("openGaps", findings(run.getOpenGaps()));
        o.add("contradictions", findings(run.getContradictions()));
        o.addProperty("lastNote", run.getLastNote());
        o.add("evaluatedTasks", strings(run.getEvaluatedTasks()));
        o.add("userNotes", strings(run.getUserNotes()));
        o.add("journal", strings(run.getJournal()));
        return GSON.toJson(o);
    }

    /** @throws IllegalArgumentException when the text is not a valid run record */
    public static OrchestratorRun decode(String json) {
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException notJson) {
            throw new IllegalArgumentException("not a run record: " + notJson.getMessage(), notJson);
        }
        int format = intOf(o, "format");
        if (format != FORMAT) {
            throw new IllegalArgumentException("unsupported run record format " + format);
        }
        OrchestratorRun.Builder b = OrchestratorRun.builder(text(o, "runId"), text(o, "question"),
                longOf(o, "createdAt"));
        b.state(OrchestratorRun.State.fromWireName(text(o, "state")));
        b.updatedAt(longOf(o, "updatedAt"));
        List<ResearchOutline.Section> sections = new ArrayList<ResearchOutline.Section>();
        for (JsonElement element : array(o, "sections")) {
            JsonObject s = element.getAsJsonObject();
            sections.add(new ResearchOutline.Section(text(s, "id"), text(s, "title")));
        }
        b.sections(sections);
        b.plan(text(o, "planRationale"), plannedTasks(array(o, "plan")));
        b.pending(plannedTasks(array(o, "pending")), text(o, "waitingReason"));
        JsonObject envelope = object(o, "envelope");
        b.envelope(intOf(envelope, "maxRounds"), intOf(envelope, "maxTasks"), longOf(envelope, "tokenBudget"),
                longOf(envelope, "timeBudgetMillis"));
        b.approved(stringSet(array(envelope, "sections")), stringSet(array(envelope, "roles")));
        JsonObject spent = object(o, "spent");
        b.spent(intOf(spent, "rounds"), intOf(spent, "tasks"), longOf(spent, "tokens"), longOf(spent, "timeMillis"));
        b.findings(findingList(array(o, "openGaps")), findingList(array(o, "contradictions")), text(o, "lastNote"));
        b.evaluated(stringSet(array(o, "evaluatedTasks")));
        b.userNotesAll(stringList(array(o, "userNotes")));
        b.journalAll(stringList(array(o, "journal")));
        return b.build();
    }

    private static JsonArray tasks(List<PlannedTask> tasks) {
        JsonArray array = new JsonArray();
        for (PlannedTask task : tasks) {
            JsonObject t = new JsonObject();
            t.addProperty("id", task.getId());
            t.addProperty("role", task.getRole());
            t.addProperty("query", task.getQuery());
            t.addProperty("brief", task.getBrief());
            t.add("sections", strings(task.getSections()));
            t.add("dependsOn", strings(task.getDependsOn()));
            array.add(t);
        }
        return array;
    }

    private static List<PlannedTask> plannedTasks(JsonArray array) {
        List<PlannedTask> tasks = new ArrayList<PlannedTask>();
        for (JsonElement element : array) {
            JsonObject t = element.getAsJsonObject();
            tasks.add(new PlannedTask(text(t, "id"), text(t, "role"), text(t, "query"), text(t, "brief"),
                    new ArrayList<String>(stringSet(array(t, "sections"))),
                    new ArrayList<String>(stringSet(array(t, "dependsOn")))));
        }
        return tasks;
    }

    private static JsonArray findings(List<RoundDecision.Finding> findings) {
        JsonArray array = new JsonArray();
        for (RoundDecision.Finding finding : findings) {
            JsonObject f = new JsonObject();
            f.addProperty("section", finding.getSection());
            f.addProperty("description", finding.getDescription());
            array.add(f);
        }
        return array;
    }

    private static List<RoundDecision.Finding> findingList(JsonArray array) {
        List<RoundDecision.Finding> findings = new ArrayList<RoundDecision.Finding>();
        for (JsonElement element : array) {
            JsonObject f = element.getAsJsonObject();
            findings.add(new RoundDecision.Finding(text(f, "section"), text(f, "description")));
        }
        return findings;
    }

    private static JsonArray strings(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /** Keeps order and repeats (the journal). */
    private static List<String> stringList(JsonArray array) {
        List<String> values = new ArrayList<String>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    /** Keeps order, drops repeats. */
    private static Set<String> stringSet(JsonArray array) {
        Set<String> values = new LinkedHashSet<String>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static JsonObject object(JsonObject o, String key) {
        JsonElement value = o.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("missing object: " + key);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject o, String key) {
        JsonElement value = o.get(key);
        return value == null || !value.isJsonArray() ? new JsonArray() : value.getAsJsonArray();
    }

    private static String text(JsonObject o, String key) {
        JsonElement value = o.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static int intOf(JsonObject o, String key) {
        JsonElement value = o.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsInt();
    }

    private static long longOf(JsonObject o, String key) {
        JsonElement value = o.get(key);
        return value == null || value.isJsonNull() ? 0L : value.getAsLong();
    }
}
