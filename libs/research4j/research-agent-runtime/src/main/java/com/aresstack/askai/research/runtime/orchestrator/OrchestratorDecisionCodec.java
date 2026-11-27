package com.aresstack.askai.research.runtime.orchestrator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The schemas the orchestrator answers in and the STRICT readers of its answers (ADR 0001, 4.2: every
 * orchestrator decision is a structured output; code never interprets free text). The schemas are built per
 * request, because their enums are the run's facts: the roles the run may delegate to, the outline's section
 * ids, the ids of the packages under evaluation, and the remaining package count as {@code maxItems}. The
 * model port enforces them at generation time; the readers check them again, plus what a schema cannot say
 * (unique ids, known dependencies, no cycles).
 *
 * <p>A reader never repairs an answer silently: it returns the decision or throws
 * {@link InvalidDecisionException} with the reason, which the orchestrator sends back to the model once
 * (a setting) before the run fails visibly.</p>
 */
public final class OrchestratorDecisionCodec {

    /** Package ids: short, lower-case, stable inside the run (they become task ids). */
    private static final Pattern PACKAGE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    private OrchestratorDecisionCodec() {
    }

    /** What a decision may name: the run's facts at the time of the call. */
    public static final class Constraints {
        final List<String> roles;
        final List<String> sections;
        final Set<String> knownPackageIds;
        final List<String> evaluatedPackageIds;
        final int maxTasks;

        /**
         * @param roles               the worker roles the run may delegate to
         * @param sections            the outline's section ids
         * @param knownPackageIds     the package ids the run already has (dependencies may name them, new
         *                            packages must not reuse them)
         * @param evaluatedPackageIds the packages the round decision evaluates (empty for a plan)
         * @param maxTasks            how many packages the answer may propose (at least 1)
         */
        public Constraints(Collection<String> roles, Collection<String> sections,
                           Collection<String> knownPackageIds, Collection<String> evaluatedPackageIds,
                           int maxTasks) {
            this.roles = new ArrayList<String>(new LinkedHashSet<String>(roles));
            this.sections = new ArrayList<String>(new LinkedHashSet<String>(sections));
            this.knownPackageIds = knownPackageIds == null ? new HashSet<String>()
                    : new HashSet<String>(knownPackageIds);
            this.evaluatedPackageIds = evaluatedPackageIds == null ? new ArrayList<String>()
                    : new ArrayList<String>(new LinkedHashSet<String>(evaluatedPackageIds));
            this.maxTasks = Math.max(0, maxTasks);
            if (this.roles.isEmpty()) {
                throw new IllegalArgumentException("a decision needs at least one delegable role");
            }
            if (this.sections.isEmpty()) {
                throw new IllegalArgumentException("a decision needs at least one outline section");
            }
        }
    }

    /** The answer violates the schema or the run's facts; the message says how, for the repair turn. */
    public static final class InvalidDecisionException extends Exception {
        public InvalidDecisionException(String message) {
            super(message);
        }
    }

    // ---- schemas -----------------------------------------------------------------------------------------

    /** The plan schema: a rationale and 1..maxTasks packages. */
    public static String planSchema(Constraints c) {
        JsonObject root = object();
        JsonObject props = new JsonObject();
        props.add("rationale", type("string"));
        JsonObject tasks = type("array");
        tasks.add("items", taskSchema(c));
        tasks.addProperty("minItems", 1);
        tasks.addProperty("maxItems", Math.max(1, c.maxTasks));
        props.add("tasks", tasks);
        root.add("properties", props);
        root.add("required", strings("rationale", "tasks"));
        return root.toString();
    }

    /** The round schema: evaluations, gaps, contradictions, quality check, action, question, follow-ups. */
    public static String roundSchema(Constraints c) {
        JsonObject root = object();
        JsonObject props = new JsonObject();

        JsonObject evaluation = object();
        JsonObject evaluationProps = new JsonObject();
        evaluationProps.add("taskId", c.evaluatedPackageIds.isEmpty() ? type("string")
                : enumOf(c.evaluatedPackageIds));
        evaluationProps.add("verdict", enumOf(wireNames(RoundDecision.Verdict.values())));
        evaluationProps.add("note", type("string"));
        evaluation.add("properties", evaluationProps);
        evaluation.add("required", strings("taskId", "verdict", "note"));
        JsonObject evaluations = type("array");
        evaluations.add("items", evaluation);
        props.add("evaluations", evaluations);

        props.add("gaps", findingsSchema(c));
        props.add("contradictions", findingsSchema(c));

        JsonObject quality = object();
        JsonObject qualityProps = new JsonObject();
        qualityProps.add("sufficient", type("boolean"));
        qualityProps.add("note", type("string"));
        quality.add("properties", qualityProps);
        quality.add("required", strings("sufficient", "note"));
        props.add("quality", quality);

        props.add("action", enumOf(wireNames(RoundDecision.Action.values())));
        props.add("question", type("string"));

        JsonObject followUps = type("array");
        followUps.add("items", taskSchema(c));
        followUps.addProperty("maxItems", c.maxTasks);
        props.add("followUps", followUps);

        root.add("properties", props);
        root.add("required", strings("evaluations", "gaps", "contradictions", "quality", "action", "question",
                "followUps"));
        return root.toString();
    }

    private static JsonObject taskSchema(Constraints c) {
        JsonObject task = object();
        JsonObject props = new JsonObject();
        props.add("id", type("string"));
        props.add("role", enumOf(c.roles));
        props.add("query", type("string"));
        props.add("brief", type("string"));
        JsonObject sections = type("array");
        sections.add("items", enumOf(c.sections));
        sections.addProperty("minItems", 1);
        props.add("sections", sections);
        JsonObject dependsOn = type("array");
        dependsOn.add("items", type("string"));
        props.add("dependsOn", dependsOn);
        task.add("properties", props);
        task.add("required", strings("id", "role", "query", "brief", "sections", "dependsOn"));
        return task;
    }

    private static JsonObject findingsSchema(Constraints c) {
        JsonObject finding = object();
        JsonObject props = new JsonObject();
        props.add("section", enumOf(c.sections));
        props.add("description", type("string"));
        finding.add("properties", props);
        finding.add("required", strings("section", "description"));
        JsonObject array = type("array");
        array.add("items", finding);
        return array;
    }

    // ---- readers -----------------------------------------------------------------------------------------

    public static RunPlan readPlan(String answer, Constraints c) throws InvalidDecisionException {
        JsonObject root = parse(answer);
        String rationale = optionalText(root, "rationale");
        List<PlannedTask> tasks = readTasks(requireArray(root, "tasks"), c, "tasks");
        if (tasks.isEmpty()) {
            throw new InvalidDecisionException("tasks: the plan proposes no work package");
        }
        return new RunPlan(rationale, tasks);
    }

    public static RoundDecision readRound(String answer, Constraints c) throws InvalidDecisionException {
        JsonObject root = parse(answer);
        RoundDecision.Action action;
        try {
            action = RoundDecision.Action.fromWireName(requireText(root, "action"));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidDecisionException("action: " + unknown.getMessage());
        }
        List<RoundDecision.Evaluation> evaluations = new ArrayList<RoundDecision.Evaluation>();
        for (JsonElement element : optionalArray(root, "evaluations")) {
            JsonObject e = asObject(element, "evaluations");
            String taskId = requireText(e, "taskId");
            // An evaluation of a package that is not under evaluation is noise, not a decision: dropped.
            if (!c.evaluatedPackageIds.isEmpty() && !c.evaluatedPackageIds.contains(taskId)) {
                continue;
            }
            try {
                evaluations.add(new RoundDecision.Evaluation(taskId,
                        RoundDecision.Verdict.fromWireName(requireText(e, "verdict")), optionalText(e, "note")));
            } catch (IllegalArgumentException unknown) {
                throw new InvalidDecisionException("evaluations: " + unknown.getMessage());
            }
        }
        List<RoundDecision.Finding> gaps = readFindings(optionalArray(root, "gaps"), c, "gaps");
        List<RoundDecision.Finding> contradictions = readFindings(optionalArray(root, "contradictions"), c,
                "contradictions");
        boolean sufficient = false;
        String qualityNote = "";
        JsonElement quality = root.get("quality");
        if (quality != null && quality.isJsonObject()) {
            JsonElement flag = quality.getAsJsonObject().get("sufficient");
            sufficient = flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean()
                    && flag.getAsBoolean();
            qualityNote = optionalText(quality.getAsJsonObject(), "note");
        }
        String question = optionalText(root, "question");
        if (action == RoundDecision.Action.ASK_USER && question.isEmpty()) {
            throw new InvalidDecisionException("question: ask-user needs the question for the user");
        }
        List<PlannedTask> followUps = action == RoundDecision.Action.CONTINUE
                ? readTasks(optionalArray(root, "followUps"), c, "followUps")
                : new ArrayList<PlannedTask>();
        return new RoundDecision(evaluations, gaps, contradictions, sufficient, qualityNote, action,
                action == RoundDecision.Action.ASK_USER ? question : "", followUps);
    }

    private static List<PlannedTask> readTasks(JsonArray array, Constraints c, String field)
            throws InvalidDecisionException {
        if (array.size() > c.maxTasks) {
            throw new InvalidDecisionException(field + ": " + array.size() + " packages, at most " + c.maxTasks
                    + " allowed");
        }
        List<PlannedTask> tasks = new ArrayList<PlannedTask>();
        Set<String> ids = new HashSet<String>();
        for (JsonElement element : array) {
            JsonObject t = asObject(element, field);
            String id = requireText(t, "id").toLowerCase(java.util.Locale.ROOT);
            if (!PACKAGE_ID.matcher(id).matches()) {
                throw new InvalidDecisionException(field + ": package id '" + id
                        + "' must be lower-case letters, digits and '-' (at most 32)");
            }
            if (!ids.add(id) || c.knownPackageIds.contains(id)) {
                throw new InvalidDecisionException(field + ": package id '" + id + "' is used twice");
            }
            String role = requireText(t, "role");
            if (!c.roles.contains(role)) {
                throw new InvalidDecisionException(field + ": role '" + role + "' is not one of " + c.roles);
            }
            String query = requireText(t, "query");
            List<String> sections = new ArrayList<String>();
            for (JsonElement s : requireArray(t, "sections")) {
                String section = s.isJsonPrimitive() ? s.getAsString().trim() : "";
                if (!c.sections.contains(section)) {
                    throw new InvalidDecisionException(field + ": package '" + id + "' names the unknown section '"
                            + section + "'");
                }
                sections.add(section);
            }
            if (sections.isEmpty()) {
                throw new InvalidDecisionException(field + ": package '" + id + "' serves no section");
            }
            List<String> dependsOn = new ArrayList<String>();
            for (JsonElement d : optionalArray(t, "dependsOn")) {
                if (d.isJsonPrimitive() && !d.getAsString().trim().isEmpty()) {
                    dependsOn.add(d.getAsString().trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
            tasks.add(new PlannedTask(id, role, query, optionalText(t, "brief"), sections, dependsOn));
        }
        Map<String, List<String>> graph = new HashMap<String, List<String>>();
        for (PlannedTask task : tasks) {
            for (String dependency : task.getDependsOn()) {
                if (dependency.equals(task.getId())) {
                    throw new InvalidDecisionException(field + ": package '" + task.getId() + "' depends on itself");
                }
                if (!ids.contains(dependency) && !c.knownPackageIds.contains(dependency)) {
                    throw new InvalidDecisionException(field + ": package '" + task.getId()
                            + "' depends on the unknown package '" + dependency + "'");
                }
            }
            graph.put(task.getId(), task.getDependsOn());
        }
        for (PlannedTask task : tasks) {
            if (reaches(graph, task.getId(), task.getId(), new HashSet<String>())) {
                throw new InvalidDecisionException(field + ": the dependencies of '" + task.getId()
                        + "' form a cycle");
            }
        }
        return tasks;
    }

    private static boolean reaches(Map<String, List<String>> graph, String from, String target, Set<String> seen) {
        List<String> next = graph.get(from);
        if (next == null) {
            return false;
        }
        for (String node : next) {
            if (node.equals(target)) {
                return true;
            }
            if (seen.add(node) && reaches(graph, node, target, seen)) {
                return true;
            }
        }
        return false;
    }

    private static List<RoundDecision.Finding> readFindings(JsonArray array, Constraints c, String field)
            throws InvalidDecisionException {
        List<RoundDecision.Finding> findings = new ArrayList<RoundDecision.Finding>();
        for (JsonElement element : array) {
            JsonObject f = asObject(element, field);
            String section = requireText(f, "section");
            if (!c.sections.contains(section)) {
                throw new InvalidDecisionException(field + ": unknown section '" + section + "'");
            }
            findings.add(new RoundDecision.Finding(section, optionalText(f, "description")));
        }
        return findings;
    }

    // ---- JSON helpers ------------------------------------------------------------------------------------

    private static JsonObject parse(String answer) throws InvalidDecisionException {
        String text = answer == null ? "" : answer.trim();
        if (text.startsWith("```")) {
            int open = text.indexOf('\n');
            int close = text.lastIndexOf("```");
            if (open > 0 && close > open) {
                text = text.substring(open + 1, close).trim();
            }
        }
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                throw new InvalidDecisionException("the answer is not a JSON object");
            }
            return root.getAsJsonObject();
        } catch (RuntimeException notJson) {
            throw new InvalidDecisionException("the answer is not valid JSON: " + notJson.getMessage());
        }
    }

    private static JsonObject asObject(JsonElement element, String field) throws InvalidDecisionException {
        if (element == null || !element.isJsonObject()) {
            throw new InvalidDecisionException(field + ": every entry must be an object");
        }
        return element.getAsJsonObject();
    }

    private static String requireText(JsonObject o, String key) throws InvalidDecisionException {
        String value = optionalText(o, key);
        if (value.isEmpty()) {
            throw new InvalidDecisionException(key + ": missing or empty");
        }
        return value;
    }

    private static String optionalText(JsonObject o, String key) {
        JsonElement value = o.get(key);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString().trim();
    }

    private static JsonArray requireArray(JsonObject o, String key) throws InvalidDecisionException {
        JsonElement value = o.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new InvalidDecisionException(key + ": missing array");
        }
        return value.getAsJsonArray();
    }

    private static JsonArray optionalArray(JsonObject o, String key) {
        JsonElement value = o.get(key);
        return value == null || !value.isJsonArray() ? new JsonArray() : value.getAsJsonArray();
    }

    private static JsonObject object() {
        JsonObject o = type("object");
        o.addProperty("additionalProperties", false);
        return o;
    }

    private static JsonObject type(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    private static JsonObject enumOf(Collection<String> values) {
        JsonObject o = type("string");
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        o.add("enum", array);
        return o;
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<String> wireNames(Enum<?>[] values) {
        List<String> names = new ArrayList<String>();
        for (Enum<?> value : values) {
            names.add(value instanceof RoundDecision.Action ? ((RoundDecision.Action) value).wireName()
                    : ((RoundDecision.Verdict) value).wireName());
        }
        return names;
    }
}
