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
 * The WRITER (ADR 0001, 4.1 "writer/synthesis", lane G): drafts the text of the package's outline sections
 * from the sources its upstream packages accepted, every paragraph citing the sources it rests on. Sources a
 * verifier rejected are left out; claims a verifier recorded travel with their source as notes. It reads the
 * captured content through the read-only {@code source_review_context} tool and fetches nothing.
 *
 * <p>Code checks the citations, not the model: a paragraph that cites no source or an unknown one makes the
 * answer invalid, and the reason goes back once (a setting). The draft is a {@code TEXT} artifact per section
 * on the blackboard ({@link WorkerArtifacts}). The writer never touches the project document: writing the
 * document is the DRAFT phase, and phase transitions are the user's (RA-P6).</p>
 */
public final class WriterWorker implements TaskWorker {

    static final String SYSTEM =
            "You are the writer of a research assistant. You draft the text of outline sections from the "
                    + "sources you are given, and from nothing else.\n"
                    + "Rules:\n"
                    + "- Answer with the JSON object the schema asks for, nothing else.\n"
                    + "- Write one entry per section id listed under SECTIONS, with short factual paragraphs.\n"
                    + "- Every paragraph lists in sources the ids of the sources it rests on; use only ids from "
                    + "SOURCES. A statement no source supports is not written.\n"
                    + "- Keep figures, names and dates exactly as the sources state them. When sources disagree, "
                    + "say so and cite both.\n"
                    + "- When the sources do not cover a section or part of it, do not fill the hole: list it "
                    + "under gaps.\n"
                    + "- Write in the language of the BRIEF.";

    /** The role: the synthesis slot, read-only source content, a per-request schema over sections and sources. */
    public static final ResearchRole ROLE = ResearchRole.builder("writer", ResearchModelSlots.SYNTHESIS)
            .purpose("drafts cited section text from the sources of the packages it dependsOn (after research "
                    + "and verification); names the gaps the sources leave; never changes the document")
            .systemPrompt(SYSTEM)
            .tools(RoleToolCatalog.of(SourceMaterial.TOOL))
            .output(RoleOutput.schemaPerRequest("WriterWorker.schema"))
            .build();

    /** The section id a package without sections writes (the outline's question-only section). */
    static final String WHOLE_QUESTION = ResearchOutline.QUESTION_SECTION;
    private static final double TEMPERATURE = 0.3;

    private final ToolInvoker research;
    private final int maxSources;
    private final int repairAttempts;
    private final int maxOutputTokens;

    /**
     * @param research        the session's research-control lane (restricted per task to the role's tools)
     * @param maxSources      sources one package writes from (a setting)
     * @param repairAttempts  how often an invalid answer is sent back (a setting)
     * @param maxOutputTokens the output budget of one answer (a setting; 0 = the model's own)
     */
    public WriterWorker(ToolInvoker research, int maxSources, int repairAttempts, int maxOutputTokens) {
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
        Set<String> rejected = WorkerArtifacts.rejectedSources(runTasks);
        List<WorkerArtifacts.SourceRef> usable = new ArrayList<WorkerArtifacts.SourceRef>();
        for (WorkerArtifacts.SourceRef source : WorkerArtifacts.upstreamSources(task, runTasks)) {
            if (!rejected.contains(source.getSourceId())) {
                usable.add(source);
            }
        }
        if (usable.isEmpty()) {
            return WorkerResult.failed("no accepted, unrejected source to write from");
        }
        // Verified-supporting sources first, so the limit cuts the weakest material.
        final Map<String, WorkerArtifacts.Verdict> verdicts = WorkerArtifacts.verdicts(runTasks);
        usable.sort((a, b) -> Integer.compare(rank(verdicts.get(a.getSourceId())), rank(verdicts.get(b.getSourceId()))));
        List<WorkerArtifacts.SourceRef> chosen = usable.size() > maxSources ? usable.subList(0, maxSources) : usable;
        List<String> ids = new ArrayList<String>();
        for (WorkerArtifacts.SourceRef source : chosen) {
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
        Map<String, String> readable = new LinkedHashMap<String, String>();
        for (String id : ids) {
            if (SourceMaterial.hasText(material.get(id))) {
                readable.put(id, material.get(id));
            }
        }
        if (readable.isEmpty()) {
            return WorkerResult.failed("none of the " + ids.size() + " source(s) has readable text");
        }
        List<String> sections = TaskBriefs.sectionsOf(task.getInputs());
        if (sections.isEmpty()) {
            sections = java.util.Collections.singletonList(WHOLE_QUESTION);
        }
        Map<String, List<String>> claims = WorkerArtifacts.claims(runTasks);
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(SYSTEM));
        messages.add(ChatMessage.user(prompt(context.query(), context.brief(), sections, readable, claims)));
        String schema = schema(sections, readable.keySet());
        for (int attempt = 0; ; attempt++) {
            if (context.isCancelled()) {
                return WorkerResult.canceled("run stopped");
            }
            MainModelChatResult result = context.chat().completeJson(messages, TEMPERATURE, maxOutputTokens, schema);
            if (!result.isOk()) {
                return WorkerResult.retryable("writer model call failed (" + result.getStatus() + "): "
                        + result.getDetail());
            }
            try {
                Draft draft = read(result.getText(), sections, readable.keySet());
                return record(draft, context);
            } catch (WorkerAnswerException invalid) {
                if (attempt >= repairAttempts || context.isBudgetExhausted()) {
                    return WorkerResult.failed("invalid draft: " + invalid.getMessage());
                }
                messages.add(ChatMessage.assistant(result.getText() == null ? "" : result.getText()));
                messages.add(ChatMessage.user("Your answer was rejected: " + invalid.getMessage()
                        + "\nAnswer again with the corrected JSON object only."));
            }
        }
    }

    private static int rank(WorkerArtifacts.Verdict verdict) {
        if (verdict == WorkerArtifacts.Verdict.SUPPORTS) {
            return 0;
        }
        if (verdict == null) {
            return 1;
        }
        return verdict == WorkerArtifacts.Verdict.PARTIAL ? 2 : 3;
    }

    private static WorkerResult record(Draft draft, WorkerContext context) {
        int paragraphs = 0;
        Set<String> cited = new LinkedHashSet<String>();
        for (Map.Entry<String, List<Paragraph>> section : draft.sections.entrySet()) {
            StringBuilder text = new StringBuilder();
            for (Paragraph paragraph : section.getValue()) {
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(paragraph.text).append(" [").append(String.join(", ", paragraph.sources)).append(']');
                cited.addAll(paragraph.sources);
                paragraphs++;
            }
            if (text.length() > 0) {
                context.artifact(TaskArtifact.Kind.TEXT, text.toString(), WorkerArtifacts.DRAFT_PREFIX + section.getKey());
            }
        }
        for (Map.Entry<String, String> gap : draft.gaps.entrySet()) {
            context.artifact(TaskArtifact.Kind.VERDICT, "gap: " + gap.getValue(), WorkerArtifacts.GAP_PREFIX + gap.getKey());
        }
        String summary = "drafted " + draft.sections.size() + " section(s), " + paragraphs + " paragraph(s) citing "
                + cited.size() + " source(s); " + draft.gaps.size() + " gap(s) named";
        context.log(summary);
        return WorkerResult.completed(summary);
    }

    static String prompt(String query, String brief, List<String> sections, Map<String, String> material,
                         Map<String, List<String>> claims) {
        StringBuilder sb = new StringBuilder("BRIEF\n").append(query.isEmpty() ? "(none)" : query).append('\n');
        if (!brief.isEmpty()) {
            sb.append(brief).append('\n');
        }
        sb.append("\nSECTIONS\n").append(String.join(", ", sections)).append('\n');
        sb.append("\nSOURCES\n");
        for (Map.Entry<String, String> source : material.entrySet()) {
            sb.append("--- ").append(source.getKey()).append('\n');
            List<String> verified = claims.get(source.getKey());
            if (verified != null) {
                for (String claim : verified) {
                    sb.append("verified claim: ").append(claim).append('\n');
                }
            }
            sb.append(source.getValue()).append('\n');
        }
        return sb.append("\nTASK: draft the sections from these sources.").toString();
    }

    static String schema(List<String> sections, Collection<String> sourceIds) {
        JsonObject paragraph = new JsonObject();
        paragraph.addProperty("type", "object");
        JsonObject paragraphProps = new JsonObject();
        JsonObject text = WorkerSchemas.string();
        text.addProperty("minLength", 1);
        paragraphProps.add("text", text);
        JsonObject sources = WorkerSchemas.arrayOf(WorkerSchemas.enumOf(sourceIds));
        sources.addProperty("minItems", 1);
        paragraphProps.add("sources", sources);
        paragraph.add("properties", paragraphProps);
        paragraph.add("required", WorkerSchemas.strings("text", "sources"));
        paragraph.addProperty("additionalProperties", false);

        JsonObject section = new JsonObject();
        section.addProperty("type", "object");
        JsonObject sectionProps = new JsonObject();
        sectionProps.add("section", WorkerSchemas.enumOf(sections));
        sectionProps.add("paragraphs", WorkerSchemas.arrayOf(paragraph));
        section.add("properties", sectionProps);
        section.add("required", WorkerSchemas.strings("section", "paragraphs"));
        section.addProperty("additionalProperties", false);

        JsonObject gap = new JsonObject();
        gap.addProperty("type", "object");
        JsonObject gapProps = new JsonObject();
        gapProps.add("section", WorkerSchemas.enumOf(sections));
        gapProps.add("description", WorkerSchemas.string());
        gap.add("properties", gapProps);
        gap.add("required", WorkerSchemas.strings("section", "description"));
        gap.addProperty("additionalProperties", false);

        JsonObject sectionArray = WorkerSchemas.arrayOf(section);
        sectionArray.addProperty("minItems", 1);
        sectionArray.addProperty("maxItems", sections.size());
        JsonObject props = new JsonObject();
        props.add("sections", sectionArray);
        props.add("gaps", WorkerSchemas.arrayOf(gap));
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.add("properties", props);
        root.add("required", WorkerSchemas.strings("sections", "gaps"));
        root.addProperty("additionalProperties", false);
        return root.toString();
    }

    /** The strict reader: known sections, every paragraph with at least one known source. */
    static Draft read(String answer, List<String> sections, Set<String> sourceIds) throws WorkerAnswerException {
        JsonObject root = WorkerAnswerException.object(answer);
        JsonElement sectionsElement = root.get("sections");
        if (sectionsElement == null || !sectionsElement.isJsonArray() || sectionsElement.getAsJsonArray().size() == 0) {
            throw new WorkerAnswerException("\"sections\" must be a non-empty array");
        }
        Map<String, List<Paragraph>> drafted = new LinkedHashMap<String, List<Paragraph>>();
        for (JsonElement item : sectionsElement.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                throw new WorkerAnswerException("every section must be an object");
            }
            JsonObject object = item.getAsJsonObject();
            String section = WorkerAnswerException.text(object, "section");
            if (!sections.contains(section)) {
                throw new WorkerAnswerException("unknown section \"" + section + "\"; use one of " + sections);
            }
            if (drafted.containsKey(section)) {
                throw new WorkerAnswerException("section " + section + " appears twice");
            }
            JsonElement paragraphs = object.get("paragraphs");
            if (paragraphs == null || !paragraphs.isJsonArray()) {
                throw new WorkerAnswerException("section " + section + " needs \"paragraphs\"");
            }
            List<Paragraph> list = new ArrayList<Paragraph>();
            int number = 0;
            for (JsonElement p : paragraphs.getAsJsonArray()) {
                number++;
                if (!p.isJsonObject()) {
                    throw new WorkerAnswerException("every paragraph must be an object");
                }
                JsonObject paragraph = p.getAsJsonObject();
                String text = WorkerAnswerException.text(paragraph, "text");
                JsonElement cited = paragraph.get("sources");
                Set<String> ids = new LinkedHashSet<String>();
                if (cited != null && cited.isJsonArray()) {
                    for (JsonElement id : cited.getAsJsonArray()) {
                        String value = id.isJsonPrimitive() ? id.getAsString().trim() : "";
                        if (!sourceIds.contains(value)) {
                            throw new WorkerAnswerException("paragraph " + number + " of " + section
                                    + " cites unknown source \"" + value + "\"; cite only ids under SOURCES");
                        }
                        ids.add(value);
                    }
                }
                if (ids.isEmpty()) {
                    throw new WorkerAnswerException("paragraph " + number + " of " + section
                            + " cites no source; drop it or cite the sources it rests on");
                }
                list.add(new Paragraph(text, new ArrayList<String>(ids)));
            }
            drafted.put(section, list);
        }
        Map<String, String> gaps = new LinkedHashMap<String, String>();
        JsonElement gapsElement = root.get("gaps");
        if (gapsElement != null && gapsElement.isJsonArray()) {
            for (JsonElement item : gapsElement.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    throw new WorkerAnswerException("every gap must be an object");
                }
                JsonObject object = item.getAsJsonObject();
                String section = WorkerAnswerException.text(object, "section");
                if (!sections.contains(section)) {
                    throw new WorkerAnswerException("gap names unknown section \"" + section + "\"");
                }
                String description = WorkerAnswerException.text(object, "description");
                gaps.merge(section, description, (a, b) -> a + "; " + b);
            }
        }
        // Every requested section is accounted for: drafted, or named as a gap. A silently dropped or empty
        // section would read as "covered" downstream.
        for (String section : sections) {
            List<Paragraph> paragraphs = drafted.get(section);
            if ((paragraphs == null || paragraphs.isEmpty()) && !gaps.containsKey(section)) {
                throw new WorkerAnswerException("section " + section + (paragraphs == null ? " is missing" : " has no "
                        + "paragraphs") + "; draft it from the sources or name it under \"gaps\"");
            }
        }
        return new Draft(drafted, gaps);
    }

    static final class Draft {
        final Map<String, List<Paragraph>> sections;
        final Map<String, String> gaps;

        Draft(Map<String, List<Paragraph>> sections, Map<String, String> gaps) {
            this.sections = sections;
            this.gaps = gaps;
        }
    }

    static final class Paragraph {
        final String text;
        final List<String> sources;

        Paragraph(String text, List<String> sources) {
            this.text = text;
            this.sources = sources;
        }
    }
}
