package com.aresstack.askai.research.runtime.scope;

import com.aresstack.askai.agent.model.reranker.MiniJson;
import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeCalibrationProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Z3b-2: the productive {@link ScopeProbeGenerator} — EXACTLY ONE {@link MainModelChat#complete}
 * call per generation (the centrally selected AskAI main model via the host-published descriptor;
 * no second Ollama client, no SERP-repair reuse), followed by strict DETERMINISTIC validation.
 * There is no model repair loop and no synthetic fallback: a malformed answer is a typed
 * {@code INVALID_RESPONSE} and the sweep simply does not run — "the model broke" must never look
 * like "nothing found". A missing control for a legitimate anchor is NOT an error here: the
 * calibration's complete-coverage rule turns it into WEAK downstream, which is the honest answer.
 * <p>
 * The generator produces MATERIAL only. It computes no coverage, ranks nothing and judges nothing
 * — measuring is the embeddings' job, the scale is the calibrator's, the relations are the sweep's
 * and redundancy is the selector's.
 */
public final class MainModelScopeProbeGenerator implements ScopeProbeGenerator {

    /**
     * The role (ADR 0001, 4.1): the scope slot, no tools, a schema built per request (its list lengths and
     * anchor ids depend on the request), strictly validated afterwards.
     */
    public static final ResearchRole ROLE = ResearchRole.builder("scope-probe-generator", ResearchModelSlots.SCOPE)
            .purpose("generates broad probes and neighbourhood controls for the scope sweep")
            .systemPrompt(systemPrompt())
            .output(RoleOutput.schemaPerRequest("MainModelScopeProbeGenerator.validate"))
            .build();

    /** All behavior-limiting knobs explicit — wired from settings by the host, never constants. */
    public static final class GeneratorSettings {
        public final double temperature;
        public final int maxOutputTokens;
        /** How many neighborhood controls to ask for per NEGOTIATED anchor (and the accept cap). */
        public final int controlsPerAnchor;

        public GeneratorSettings(double temperature, int maxOutputTokens, int controlsPerAnchor) {
            this.temperature = temperature;
            this.maxOutputTokens = Math.max(1, maxOutputTokens);
            this.controlsPerAnchor = Math.max(1, controlsPerAnchor);
        }
    }

    private final MainModelChat model;
    private final GeneratorSettings settings;

    public MainModelScopeProbeGenerator(MainModelChat model, GeneratorSettings settings) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (settings == null) {
            throw new IllegalArgumentException("settings must not be null");
        }
        this.model = model;
        this.settings = settings;
    }

    @Override
    public ProbeGenerationResult generate(ProbeGenerationRequest request) {
        List<ScopeAnchor> negotiated = negotiatedAnchorsOf(request);
        MainModelChatResult call = model.completeJson(
                Arrays.asList(ChatMessage.system(ROLE.getSystemPrompt()),
                        ChatMessage.user(userPrompt(request, negotiated))),
                settings.temperature, settings.maxOutputTokens,
                responseSchema(request, negotiated));
        if (!call.isOk()) {
            // The chat seam's typed failure survives 1:1 — never flattened into an empty sweep.
            return ProbeGenerationResult.failure(statusOf(call.getStatus()), call.getDetail());
        }
        return validate(call.getText(), request, negotiated);
    }

    /**
     * Only NEGOTIATED (IN/OUT) posts are offered as CALIBRATION anchors — a hypothesis must not
     * calibrate the stick. This does NOT mean a provisional facet is invisible to the generator:
     * it may (and should) appear among the request's knownFacetLabels so the broad probes do not
     * re-paraphrase the open hypothesis seventeen times. The two roles are different.
     */
    private static List<ScopeAnchor> negotiatedAnchorsOf(ProbeGenerationRequest request) {
        List<ScopeAnchor> negotiated = new ArrayList<ScopeAnchor>();
        for (ScopeAnchor anchor : request.getAnchors()) {
            if (anchor.getMembership() != ScopeAnchor.Membership.PROVISIONAL) {
                negotiated.add(anchor);
            }
        }
        return negotiated;
    }

    private static String systemPrompt() {
        return "Du bist ein Themen-Kartograf für eine Recherche-Vorbereitung. Du lieferst"
                + " AUSSCHLIESSLICH ein einzelnes JSON-Objekt, ohne Erklärtext davor oder danach."
                + " Du bewertest nichts, priorisierst nichts und triffst keine Aussagen über"
                + " Vollständigkeit — du lieferst nur Material.";
    }

    private String userPrompt(ProbeGenerationRequest request, List<ScopeAnchor> negotiated) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Auftrag:\n").append(request.getMission()).append('\n');
        if (!request.getDomains().isEmpty()) {
            prompt.append("\nDomains:\n");
            for (String domain : request.getDomains()) {
                prompt.append("- ").append(domain).append('\n');
            }
        }
        if (!request.getContexts().isEmpty()) {
            prompt.append("\nKontexte:\n");
            for (String context : request.getContexts()) {
                prompt.append("- ").append(context).append('\n');
            }
        }
        if (!request.getKnownFacetLabels().isEmpty()) {
            prompt.append("\nBereits bekannte Facetten (NICHT paraphrasieren):\n");
            for (String label : request.getKnownFacetLabels()) {
                prompt.append("- ").append(label).append('\n');
            }
        }
        prompt.append("\nVerhandelte Kalibrierungs-Anker:\n");
        for (ScopeAnchor anchor : negotiated) {
            prompt.append("- ").append(anchor.getAnchorId()).append(" | ")
                    .append(anchor.getMembership().name()).append(" | ")
                    .append(anchor.getSemanticText()).append('\n');
        }
        // Live-gate finding: a model answering in ENGLISH to a German mission wrecks the whole
        // geometry — cross-lingual cosines sink mission relevance AND anchor similarity, so known
        // regions read as unexplained. The texts must live in the mission's language.
        prompt.append("\nWICHTIG: Schreibe ALLE Texte in derselben Sprache wie der Auftrag oben ")
                .append("(niemals übersetzen) — die Texte werden mit den Ankertexten geometrisch ")
                .append("verglichen.\n");
        prompt.append("\nErzeuge zwei Listen:\n")
                .append("1. broadProbes: GENAU ").append(request.getTargetCount())
                .append(" möglichst BREITE, untereinander VERSCHIEDENE konkrete Themen/")
                .append("Technologien/Akteure/Anwendungsfälle, die für den Auftrag plausibel ")
                .append("relevant sein könnten. Keine bloßen Paraphrasen der bekannten Facetten; ")
                .append("angrenzende und noch nicht genannte Aspekte sind ausdrücklich erwünscht. ")
                // Live finding (gemma4:e2b): without the explicit stop rule a small model loses
                // count, repeats itself and runs straight into the token limit (truncated JSON).
                .append("Nicht mehr als ").append(request.getTargetCount())
                .append(" — zähle mit und höre dann auf.\n")
                .append("2. calibrationProbes: für JEDEN oben gelisteten Anker GENAU ")
                .append(settings.controlsPerAnchor)
                .append(" konkrete lokale Beispiele, die klar noch unter DENSELBEN Anker fallen ")
                .append("(parentAnchorId = exakt die Anker-Id oben). Verschiedene konkrete ")
                .append("Beispiele derselben Region — keine sprachlichen Paraphrasen des ")
                .append("Ankertextes. WICHTIG: auch OUT-Anker brauchen ihre Beispiele — sie ")
                .append("beschreiben bewusst AUSGESCHLOSSENE Regionen, und deren Vermessung ")
                .append("braucht dieselben lokalen Beispiele wie die eingeschlossenen.\n")
                .append("\nAntworte NUR mit diesem JSON-Format:\n")
                .append("{\"broadProbes\":[{\"text\":\"...\"}],")
                .append("\"calibrationProbes\":[{\"parentAnchorId\":\"...\",\"text\":\"...\"}]}");
        return prompt.toString();
    }

    private static ProbeGenerationResult.Status statusOf(MainModelChatResult.Status status) {
        switch (status) {
            case TIMEOUT:
                return ProbeGenerationResult.Status.TIMEOUT;
            case INVALID_RESPONSE:
                return ProbeGenerationResult.Status.INVALID_RESPONSE;
            default:
                return ProbeGenerationResult.Status.PROVIDER_FAILURE;
        }
    }

    /**
     * Strict, deterministic, model-free validation. STRUCTURAL breaches (no JSON, wrong shape,
     * entries without text/parentAnchorId, zero usable broad probes) are INVALID_RESPONSE;
     * SEMANTIC breaches (unknown or provisional parent, duplicate normalized texts within a role,
     * over-cap entries) drop the entry and are DIAGNOSED in the message — never silently repaired,
     * never re-asked. Identity is OURS, not the model's: probe ids are assigned locally in order
     * (the model contributes semantic content only). The dedupe ROLES are separate — broad probes
     * dedupe among themselves, controls per (parentAnchorId, text): the same wording as a broad
     * probe and as a control (or for two different posts) carries DIFFERENT logical relations, and
     * dropping the second would silently un-cover an anchor and fake a WEAK calibration.
     */
    private ProbeGenerationResult validate(String answer, ProbeGenerationRequest request,
                                           List<ScopeAnchor> negotiated) {
        Object parsed;
        try {
            parsed = MiniJson.parse(unfence(answer));
        } catch (MiniJson.JsonParseException malformed) {
            return ProbeGenerationResult.failure(ProbeGenerationResult.Status.INVALID_RESPONSE,
                    "not JSON: " + malformed.getMessage() + answerSnippet(answer));
        }
        if (!(parsed instanceof Map)) {
            return ProbeGenerationResult.failure(ProbeGenerationResult.Status.INVALID_RESPONSE,
                    "top level is not an object");
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        Object broadRaw = root.get("broadProbes");
        Object controlsRaw = root.get("calibrationProbes");
        if (!(broadRaw instanceof List) || !(controlsRaw instanceof List)) {
            return ProbeGenerationResult.failure(ProbeGenerationResult.Status.INVALID_RESPONSE,
                    "broadProbes/calibrationProbes missing or not arrays");
        }

        List<String> diagnostics = new ArrayList<String>();
        Set<String> seenBroadTexts = new LinkedHashSet<String>();
        List<ScopeProbe> broadProbes = new ArrayList<ScopeProbe>();
        for (Object entry : (List<?>) broadRaw) {
            // Deterministic tolerance for small models (live: gemma4:e2b): a broad probe may be a
            // bare string OR the contracted {"text": …} object — both are unambiguous. Controls
            // stay object-only (they NEED parentAnchorId).
            String text = entry instanceof String && !((String) entry).trim().isEmpty()
                    ? ((String) entry).trim() : fieldOf(entry, "text");
            if (text == null) {
                return ProbeGenerationResult.failure(ProbeGenerationResult.Status.INVALID_RESPONSE,
                        "broad probe without text");
            }
            if (!seenBroadTexts.add(normalize(text))) {
                diagnostics.add("duplicate broad text dropped: " + snippet(text));
                continue;
            }
            if (broadProbes.size() >= request.getTargetCount()) {
                diagnostics.add("over target count, dropped: " + snippet(text));
                continue;
            }
            broadProbes.add(new ScopeProbe(
                    String.format(Locale.ROOT, "probe-%04d", broadProbes.size() + 1), text));
        }
        if (broadProbes.isEmpty()) {
            // Zero material is a FAILED generation — it must not masquerade as a clean sweep.
            return ProbeGenerationResult.failure(ProbeGenerationResult.Status.INVALID_RESPONSE,
                    "model produced no usable broad probes");
        }

        Set<String> negotiatedIds = new LinkedHashSet<String>();
        for (ScopeAnchor anchor : negotiated) {
            negotiatedIds.add(anchor.getAnchorId());
        }
        Set<String> provisionalIds = new LinkedHashSet<String>();
        for (ScopeAnchor anchor : request.getAnchors()) {
            if (anchor.getMembership() == ScopeAnchor.Membership.PROVISIONAL) {
                provisionalIds.add(anchor.getAnchorId());
            }
        }
        Set<String> seenControlKeys = new LinkedHashSet<String>();
        Map<String, Integer> perParent = new LinkedHashMap<String, Integer>();
        List<ScopeCalibrationProbe> calibrationProbes = new ArrayList<ScopeCalibrationProbe>();
        for (Object entry : (List<?>) controlsRaw) {
            String parent = fieldOf(entry, "parentAnchorId");
            String text = fieldOf(entry, "text");
            if (parent == null || text == null) {
                return ProbeGenerationResult.failure(ProbeGenerationResult.Status.INVALID_RESPONSE,
                        "calibration probe without parentAnchorId/text");
            }
            if (provisionalIds.contains(parent)) {
                diagnostics.add("control for PROVISIONAL post dropped: " + parent);
                continue;
            }
            if (!negotiatedIds.contains(parent)) {
                // Never invent a new referent for a made-up anchor id.
                diagnostics.add("control for unknown post dropped: " + parent);
                continue;
            }
            if (!seenControlKeys.add(parent + " " + normalize(text))) {
                diagnostics.add("duplicate control dropped: " + parent + " " + snippet(text));
                continue;
            }
            Integer sofar = perParent.get(parent);
            int count = sofar == null ? 0 : sofar;
            if (count >= settings.controlsPerAnchor) {
                diagnostics.add("over per-anchor control cap, dropped: " + parent);
                continue;
            }
            perParent.put(parent, count + 1);
            calibrationProbes.add(new ScopeCalibrationProbe(
                    String.format(Locale.ROOT, "control-%04d", calibrationProbes.size() + 1),
                    parent, text));
        }
        // Anchors WITHOUT any control are deliberately left alone: the calibration's
        // complete-coverage rule reads that as WEAK — the honest downstream answer.
        return ProbeGenerationResult.ok(
                new ProbeGeneration(broadProbes, calibrationProbes, request.getTargetCount()),
                join(diagnostics));
    }

    /**
     * The Ollama structured-output schema for THIS request — the deterministic cure for the two
     * measured small-model failure modes: {@code maxItems} stops a model that loses count (live:
     * gemma4:e2b spiralled past 50 into the token limit even when told to stop), and the
     * {@code parentAnchorId} enum makes an invented anchor id UNREPRESENTABLE at generation time.
     * The strict validator downstream stays — the schema narrows what can arrive, it does not
     * replace checking it.
     */
    private static String responseSchema(ProbeGenerationRequest request,
                                         List<ScopeAnchor> negotiated) {
        StringBuilder schema = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        // minItems == maxItems: the grammar itself enforces the requested count — a small model
        // that would stop early (live: gemma at 17/20) must keep producing topics instead.
        schema.append("\"broadProbes\":{\"type\":\"array\",\"minItems\":")
                .append(request.getTargetCount()).append(",\"maxItems\":")
                .append(request.getTargetCount())
                .append(",\"items\":{\"type\":\"object\",\"properties\":{")
                .append("\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}},");
        schema.append("\"calibrationProbes\":{\"type\":\"array\",\"maxItems\":");
        // The cap the validator enforces anyway — the grammar just stops the list early.
        schema.append(Math.max(1, negotiated.size()) * 8); // headroom; per-anchor cap trims later
        schema.append(",\"items\":{\"type\":\"object\",\"properties\":{")
                .append("\"parentAnchorId\":{\"type\":\"string\"");
        if (!negotiated.isEmpty()) {
            schema.append(",\"enum\":[");
            for (int index = 0; index < negotiated.size(); index++) {
                if (index > 0) {
                    schema.append(',');
                }
                schema.append(jsonString(negotiated.get(index).getAnchorId()));
            }
            schema.append(']');
        }
        schema.append("},\"text\":{\"type\":\"string\"}},")
                .append("\"required\":[\"parentAnchorId\",\"text\"]}}},")
                .append("\"required\":[\"broadProbes\",\"calibrationProbes\"]}");
        return schema.toString();
    }

    private static String jsonString(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '"' || c == '\\') {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        return quoted.append('\"').toString();
    }

    /** The field diagnosis that saves a debug session: WHAT did the model actually start with? */
    private static String answerSnippet(String answer) {
        String trimmed = answer == null ? "" : answer.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) {
            return " (empty answer)";
        }
        return " (answer starts: '"
                + (trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80) + "…") + "')";
    }

    private static String snippet(String text) {
        String trimmed = text.trim();
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 40) + "…";
    }

    /**
     * Deterministic LOCAL extraction, never a re-ask: accept a bare JSON object, ONE
     * markdown-fenced block, or an object embedded in prose — live finding: gemma4:e2b prefixes
     * the JSON with explanatory text despite the instruction, so after unfencing we cut from the
     * first '{' to the last '}'. Anything that still fails the strict parse stays a typed
     * INVALID_RESPONSE.
     */
    private static String unfence(String answer) {
        String trimmed = answer == null ? "" : answer.trim();
        if (trimmed.startsWith("```")) {
            int firstBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                trimmed = trimmed.substring(firstBreak + 1, lastFence).trim();
            }
        }
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            int open = trimmed.indexOf('{');
            int close = trimmed.lastIndexOf('}');
            if (open >= 0 && close > open) {
                trimmed = trimmed.substring(open, close + 1).trim();
            }
        }
        return trimmed;
    }

    private static String fieldOf(Object entry, String key) {
        if (!(entry instanceof Map)) {
            return null;
        }
        Object value = ((Map<?, ?>) entry).get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            return null;
        }
        return ((String) value).trim();
    }

    private static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String join(List<String> diagnostics) {
        StringBuilder message = new StringBuilder();
        for (String line : diagnostics) {
            if (message.length() > 0) {
                message.append("; ");
            }
            message.append(line);
        }
        return message.toString();
    }
}
