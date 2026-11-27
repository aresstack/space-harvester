package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.role.RoleToolCatalog;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SOURCE VERIFIER (ADR 0001, 4.1, lane G): judges the sources its upstream web research packages accepted
 * against the package's brief, names the claim each supporting source makes, and finds contradictions between
 * them. It reads the captured content of exactly those sources through the read-only
 * {@code source_review_context} tool and fetches nothing: every acquisition stays in the one funnel (driver 2).
 *
 * <p>The answer is one schema-bound object with exactly one verdict per source; code checks it and sends an
 * invalid answer back once (a setting). Verdicts, claims and contradictions are recorded on the blackboard
 * ({@link WorkerArtifacts}); a rejected source then stops counting as evidence ({@link EvidenceMeter}). The
 * verifier changes nothing in the project's source list: parking or excluding a source stays the user's (and
 * the funnel's) decision.</p>
 */
public final class SourceVerifierWorker implements TaskWorker {

    static final String SYSTEM =
            "You are the source verifier of a research assistant. You get the content of sources that a web "
                    + "search accepted for one research brief, and you check them. You never search yourself.\n"
                    + "Rules:\n"
                    + "- Answer with the JSON object the schema asks for, nothing else.\n"
                    + "- Give exactly one verdict per source id listed under SOURCES:\n"
                    + "  supports = the text gives concrete facts for the brief;\n"
                    + "  partial = the text touches the brief only vaguely or for a small part;\n"
                    + "  off-topic = the text is about something else;\n"
                    + "  unreliable = about the brief, but spam, advertising, or without substance;\n"
                    + "  unreadable = no usable text.\n"
                    + "- For supports and partial, put the main fact the text states into claim, in one sentence, "
                    + "using the text's own figures and names. Never add facts that are not in the text.\n"
                    + "- List a contradiction only when two sources state different facts about the same thing "
                    + "(for example different figures or dates); name both source ids.\n"
                    + "- Judge only what the texts say, not what you know.";

    /** The role: the verifier slot, read-only source content, a per-request schema over the source ids. */
    public static final ResearchRole ROLE = ResearchRole.builder("source-verifier", ResearchModelSlots.VERIFIER)
            .purpose("checks the sources accepted by the packages it dependsOn (relevance, reliability, the claim "
                    + "each supports) and finds contradictions between them; rejected sources stop counting")
            .systemPrompt(SYSTEM)
            .tools(RoleToolCatalog.of(SourceMaterial.TOOL))
            .output(RoleOutput.schemaPerRequest("SourceVerifierWorker.schema"))
            .thinking(com.aresstack.askai.agent.model.port.ThinkingMode.OFF)
            .build();

    private static final double TEMPERATURE = 0.1;

    private final ToolInvoker research;
    private final int maxSources;
    private final int repairAttempts;
    private final int maxOutputTokens;

    /**
     * @param research        the session's research-control lane (restricted per task to the role's tools)
     * @param maxSources      sources one package judges (a setting)
     * @param repairAttempts  how often an invalid answer is sent back (a setting)
     * @param maxOutputTokens the output budget of one answer (the agent's model-turn budget)
     */
    public SourceVerifierWorker(ToolInvoker research, int maxSources, int repairAttempts, int maxOutputTokens) {
        this.research = research;
        this.maxSources = Math.max(1, maxSources);
        this.repairAttempts = Math.max(0, repairAttempts);
        this.maxOutputTokens = Math.max(0, maxOutputTokens);
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
        List<ResearchTask> runTasks = context.runTasks();
        Map<String, WorkerArtifacts.Verdict> judged = WorkerArtifacts.verdicts(runTasks);
        List<WorkerArtifacts.SourceRef> candidates = new ArrayList<WorkerArtifacts.SourceRef>();
        for (WorkerArtifacts.SourceRef source : WorkerArtifacts.upstreamSources(task, runTasks)) {
            if (!judged.containsKey(source.getSourceId())) {
                candidates.add(source);
            }
        }
        if (candidates.isEmpty()) {
            context.artifact(TaskArtifact.Kind.TEXT, "nothing to verify: no unverified upstream source", "");
            return WorkerResult.completed("nothing to verify");
        }
        List<WorkerArtifacts.SourceRef> batch = candidates.size() > maxSources
                ? candidates.subList(0, maxSources) : candidates;
        if (candidates.size() > batch.size()) {
            context.log((candidates.size() - batch.size()) + " source(s) left unverified (limit " + maxSources + ")");
        }
        List<String> ids = new ArrayList<String>();
        for (WorkerArtifacts.SourceRef source : batch) {
            ids.add(source.getSourceId());
        }
        Map<String, String> material;
        try {
            material = SourceMaterial.read(context.permitted(research), ids);
            context.receipt(SourceMaterial.TOOL, true, "read " + material.size() + " of " + ids.size() + " source(s)");
        } catch (ToolInvoker.ToolFailure | ToolInvoker.EndpointUnavailable unavailable) {
            context.receipt(SourceMaterial.TOOL, false, unavailable.getMessage());
            return WorkerResult.retryable("source content unavailable: " + unavailable.getMessage());
        }
        // A source without captured text needs no model to be judged.
        Map<String, String> readable = new LinkedHashMap<String, String>();
        int unreadable = 0;
        for (String id : ids) {
            String block = material.get(id);
            if (SourceMaterial.hasText(block)) {
                readable.put(id, block);
            } else {
                unreadable++;
                context.artifact(TaskArtifact.Kind.VERDICT, WorkerArtifacts.Verdict.UNREADABLE.wireName()
                        + ": " + (block == null ? "the host serves no content for it (parked or excluded)"
                        : "no readable text was captured"), id);
            }
        }
        if (readable.isEmpty()) {
            return WorkerResult.completed(unreadable + " source(s) unreadable, nothing to judge");
        }
        List<String> sections = TaskBriefs.sectionsOf(task.getInputs());
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(SYSTEM));
        messages.add(ChatMessage.user(prompt(context.query(), context.brief(), sections, readable)));
        String schema = schema(readable.keySet(), sections);
        for (int attempt = 0; ; attempt++) {
            if (context.isCancelled()) {
                return WorkerResult.canceled("run stopped");
            }
            MainModelChatResult result = context.chat().completeJson(messages, TEMPERATURE, maxOutputTokens, schema);
            if (!result.isOk()) {
                return WorkerResult.retryable("verifier model call failed (" + result.getStatus() + "): "
                        + result.getDetail());
            }
            try {
                Judgement judgement = read(result.getText(), readable.keySet(), sections);
                return record(judgement, unreadable, context);
            } catch (WorkerAnswerException invalid) {
                if (attempt >= repairAttempts || context.isBudgetExhausted()) {
                    return WorkerResult.failed("invalid verification: " + invalid.getMessage());
                }
                messages.add(ChatMessage.assistant(result.getText() == null ? "" : result.getText()));
                messages.add(ChatMessage.user("Your answer was rejected: " + invalid.getMessage()
                        + "\nAnswer again with the corrected JSON object only."));
            }
        }
    }

    private static WorkerResult record(Judgement judgement, int unreadable, WorkerContext context) {
        int supporting = 0;
        int rejected = 0;
        for (SourceVerdict verdict : judgement.verdicts) {
            context.artifact(TaskArtifact.Kind.VERDICT, verdict.verdict.wireName() + ": "
                    + (verdict.section.isEmpty() ? "" : "section " + verdict.section + "; ") + verdict.reason,
                    verdict.sourceId);
            if (!verdict.claim.isEmpty() && !verdict.verdict.isRejected()
                    && verdict.verdict != WorkerArtifacts.Verdict.UNREADABLE) {
                context.artifact(TaskArtifact.Kind.FINDING, verdict.claim, verdict.sourceId);
            }
            if (verdict.verdict.isRejected()) {
                rejected++;
            } else if (verdict.verdict == WorkerArtifacts.Verdict.SUPPORTS) {
                supporting++;
            }
        }
        for (Contradiction contradiction : judgement.contradictions) {
            context.artifact(TaskArtifact.Kind.VERDICT, contradiction.description + " (sources "
                            + String.join(", ", contradiction.sources) + ")",
                    WorkerArtifacts.CONTRADICTION_PREFIX + contradiction.section);
        }
        String summary = "verified " + (judgement.verdicts.size() + unreadable) + " source(s): " + supporting
                + " supporting, " + rejected + " rejected, " + unreadable + " unreadable, "
                + judgement.contradictions.size() + " contradiction(s)";
        context.log(summary);
        return WorkerResult.completed(summary);
    }

    static String prompt(String query, String brief, List<String> sections, Map<String, String> material) {
        StringBuilder sb = new StringBuilder("BRIEF\n").append(query.isEmpty() ? "(none)" : query).append('\n');
        if (!brief.isEmpty()) {
            sb.append(brief).append('\n');
        }
        if (!sections.isEmpty()) {
            sb.append("\nSECTIONS\n").append(String.join(", ", sections)).append('\n');
        }
        sb.append("\nSOURCES\n");
        for (Map.Entry<String, String> source : material.entrySet()) {
            sb.append("--- ").append(source.getKey()).append('\n').append(source.getValue()).append('\n');
        }
        return sb.append("\nTASK: give one verdict per source id and list the contradictions.").toString();
    }

    static String schema(Collection<String> sourceIds, List<String> sections) {
        JsonObject verdict = new JsonObject();
        verdict.addProperty("type", "object");
        JsonObject verdictProps = new JsonObject();
        verdictProps.add("source", WorkerSchemas.enumOf(sourceIds));
        verdictProps.add("verdict", WorkerSchemas.enumOf(WorkerArtifacts.Verdict.wireNames()));
        verdictProps.add("section", sections.isEmpty() ? WorkerSchemas.string()
                : WorkerSchemas.enumOf(withEmpty(sections)));
        verdictProps.add("claim", WorkerSchemas.string());
        verdictProps.add("reason", WorkerSchemas.string());
        verdict.add("properties", verdictProps);
        verdict.add("required", WorkerSchemas.strings("source", "verdict", "section", "claim", "reason"));
        verdict.addProperty("additionalProperties", false);

        JsonObject contradiction = new JsonObject();
        contradiction.addProperty("type", "object");
        JsonObject contradictionProps = new JsonObject();
        JsonObject pair = WorkerSchemas.arrayOf(WorkerSchemas.enumOf(sourceIds));
        pair.addProperty("minItems", 2);
        contradictionProps.add("sources", pair);
        contradictionProps.add("section", sections.isEmpty() ? WorkerSchemas.string()
                : WorkerSchemas.enumOf(withEmpty(sections)));
        contradictionProps.add("description", WorkerSchemas.string());
        contradiction.add("properties", contradictionProps);
        contradiction.add("required", WorkerSchemas.strings("sources", "section", "description"));
        contradiction.addProperty("additionalProperties", false);

        JsonObject verdicts = WorkerSchemas.arrayOf(verdict);
        verdicts.addProperty("minItems", sourceIds.size());
        verdicts.addProperty("maxItems", sourceIds.size());
        JsonObject props = new JsonObject();
        props.add("verdicts", verdicts);
        props.add("contradictions", WorkerSchemas.arrayOf(contradiction));
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.add("properties", props);
        root.add("required", WorkerSchemas.strings("verdicts", "contradictions"));
        root.addProperty("additionalProperties", false);
        return root.toString();
    }

    private static List<String> withEmpty(List<String> sections) {
        List<String> values = new ArrayList<String>(sections);
        values.add("");
        return values;
    }

    /** The strict reader: one verdict per source, known ids and sections, contradictions over two sources. */
    static Judgement read(String answer, Set<String> sourceIds, List<String> sections) throws WorkerAnswerException {
        JsonObject root = WorkerAnswerException.object(answer);
        JsonElement verdictsElement = root.get("verdicts");
        if (verdictsElement == null || !verdictsElement.isJsonArray()) {
            throw new WorkerAnswerException("\"verdicts\" must be an array");
        }
        Map<String, SourceVerdict> verdicts = new LinkedHashMap<String, SourceVerdict>();
        for (JsonElement item : verdictsElement.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                throw new WorkerAnswerException("every verdict must be an object");
            }
            JsonObject object = item.getAsJsonObject();
            String source = WorkerAnswerException.text(object, "source");
            if (!sourceIds.contains(source)) {
                throw new WorkerAnswerException("unknown source id \"" + source + "\"; use only the ids under SOURCES");
            }
            if (verdicts.containsKey(source)) {
                throw new WorkerAnswerException("source " + source + " has more than one verdict");
            }
            WorkerArtifacts.Verdict verdict = WorkerArtifacts.Verdict.fromWireName(
                    WorkerAnswerException.text(object, "verdict"));
            if (verdict == null) {
                throw new WorkerAnswerException("verdict of " + source + " must be one of "
                        + WorkerArtifacts.Verdict.wireNames());
            }
            String section = knownSection(WorkerAnswerException.optionalText(object, "section"), sections);
            verdicts.put(source, new SourceVerdict(source, verdict, section,
                    oneLine(WorkerAnswerException.optionalText(object, "claim")),
                    oneLine(WorkerAnswerException.optionalText(object, "reason"))));
        }
        Set<String> missing = new LinkedHashSet<String>(sourceIds);
        missing.removeAll(verdicts.keySet());
        if (!missing.isEmpty()) {
            throw new WorkerAnswerException("no verdict for " + String.join(", ", missing));
        }
        List<Contradiction> contradictions = new ArrayList<Contradiction>();
        JsonElement contradictionsElement = root.get("contradictions");
        if (contradictionsElement != null && !contradictionsElement.isJsonNull()) {
            if (!contradictionsElement.isJsonArray()) {
                throw new WorkerAnswerException("\"contradictions\" must be an array");
            }
            for (JsonElement item : contradictionsElement.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    throw new WorkerAnswerException("every contradiction must be an object");
                }
                JsonObject object = item.getAsJsonObject();
                Set<String> involved = new LinkedHashSet<String>();
                JsonElement sources = object.get("sources");
                if (sources == null || !sources.isJsonArray()) {
                    throw new WorkerAnswerException("a contradiction needs \"sources\"");
                }
                for (JsonElement id : sources.getAsJsonArray()) {
                    String value = id.isJsonPrimitive() ? id.getAsString().trim() : "";
                    if (!sourceIds.contains(value)) {
                        throw new WorkerAnswerException("contradiction names unknown source \"" + value + "\"");
                    }
                    involved.add(value);
                }
                if (involved.size() < 2) {
                    throw new WorkerAnswerException("a contradiction needs two different sources");
                }
                contradictions.add(new Contradiction(new ArrayList<String>(involved),
                        knownSection(WorkerAnswerException.optionalText(object, "section"), sections),
                        oneLine(WorkerAnswerException.text(object, "description"))));
            }
        }
        return new Judgement(new ArrayList<SourceVerdict>(verdicts.values()), contradictions);
    }

    private static String knownSection(String section, List<String> sections) throws WorkerAnswerException {
        if (section.isEmpty() || sections.isEmpty() || sections.contains(section)) {
            return section;
        }
        throw new WorkerAnswerException("unknown section \"" + section + "\"; use one of " + sections + " or \"\"");
    }

    private static String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 400 ? flat : flat.substring(0, 400) + "…";
    }

    static final class Judgement {
        final List<SourceVerdict> verdicts;
        final List<Contradiction> contradictions;

        Judgement(List<SourceVerdict> verdicts, List<Contradiction> contradictions) {
            this.verdicts = verdicts;
            this.contradictions = contradictions;
        }
    }

    static final class SourceVerdict {
        final String sourceId;
        final WorkerArtifacts.Verdict verdict;
        final String section;
        final String claim;
        final String reason;

        SourceVerdict(String sourceId, WorkerArtifacts.Verdict verdict, String section, String claim, String reason) {
            this.sourceId = sourceId;
            this.verdict = verdict;
            this.section = section;
            this.claim = claim;
            this.reason = reason;
        }
    }

    static final class Contradiction {
        final List<String> sources;
        final String section;
        final String description;

        Contradiction(List<String> sources, String section, String description) {
            this.sources = sources;
            this.section = section;
            this.description = description;
        }
    }
}
