package com.aresstack.askai.research.runtime.scope;

import com.aresstack.askai.agent.model.reranker.MiniJson;
import com.aresstack.askai.research.domain.scope.ScopeAdviceCandidate;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;

import java.util.Arrays;
import java.util.Map;

/**
 * Z4b: the productive {@link ScopeAdviceChooser} — EXACTLY ONE {@link MainModelChat#complete}
 * call over the finished offers. The model's authority is deliberately tiny: pick AT MOST ONE of
 * the OFFERED candidate ids (or NONE) and phrase one natural question in the conversation's
 * language. It never sees the raw sweep, never invents candidates (an unknown id is a typed
 * INVALID_RESPONSE, not a repair loop), never turns a drift guard into a positive question (the
 * guards carry no selectable id at all), and this call produces no scope patch, no workflow
 * action, no submit — the user's ANSWER later changes the draft through the normal scoping turn.
 * NONE is a legitimate answer meaning "no sensible next question this round", never "scope
 * complete".
 */
public final class MainModelScopeAdviceChooser implements ScopeAdviceChooser {

    /**
     * The role (ADR 0001, 4.1): the scope slot, no tools, a schema built per request (the allowed candidate
     * ids are the request's), strictly validated afterwards.
     */
    public static final ResearchRole ROLE = ResearchRole.builder("scope-advice-chooser", ResearchModelSlots.SCOPE)
            .purpose("chooses at most one prepared clarification candidate for the scoping dialogue")
            .systemPrompt(systemPrompt())
            .output(RoleOutput.schemaPerRequest("MainModelScopeAdviceChooser.validate"))
            .build();


    /** All behavior-limiting knobs explicit — wired from settings by the host, never constants. */
    public static final class ChooserSettings {
        public final double temperature;
        public final int maxOutputTokens;

        public ChooserSettings(double temperature, int maxOutputTokens) {
            this.temperature = temperature;
            this.maxOutputTokens = Math.max(1, maxOutputTokens);
        }
    }

    private final MainModelChat model;
    private final ChooserSettings settings;

    public MainModelScopeAdviceChooser(MainModelChat model, ChooserSettings settings) {
        if (model == null || settings == null) {
            throw new IllegalArgumentException("model and settings are required");
        }
        this.model = model;
        this.settings = settings;
    }

    @Override
    public ChoiceResult choose(ChoiceRequest request) {
        MainModelChatResult call = model.completeJson(
                Arrays.asList(ChatMessage.system(ROLE.getSystemPrompt()),
                        ChatMessage.user(userPrompt(request))),
                settings.temperature, settings.maxOutputTokens, responseSchema(request));
        if (!call.isOk()) {
            return ChoiceResult.failure(statusOf(call.getStatus()), call.getDetail());
        }
        return validate(call.getText(), request);
    }

    private static String systemPrompt() {
        return "Du bist Berater in einer Scoping-Unterhaltung. Du bekommst wenige bereits"
                + " aufbereitete Klärungskandidaten und wählst HÖCHSTENS EINEN davon aus — den,"
                + " dessen Klärung den Themenzuschnitt jetzt am stärksten verbessert — oder"
                + " ehrlich keinen. Du erfindest NIEMALS eigene Kandidaten, du triffst keine"
                + " Scope-Entscheidungen und du bietest niemals einen Drift-Hinweis als neue"
                + " positive Erweiterung an. Du lieferst AUSSCHLIESSLICH ein einzelnes"
                + " JSON-Objekt, ohne Erklärtext davor oder danach.";
    }

    private static String userPrompt(ChoiceRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Auftrag:\n").append(request.getMission()).append('\n');
        prompt.append("\nKlärungskandidaten (wähle höchstens EINEN per candidateId):\n");
        for (CandidateOffer offer : request.getCandidates()) {
            prompt.append("- ").append(offer.getCandidateId())
                    .append(" | ").append(reasonLabel(offer.getReason()))
                    .append(" | ").append(offer.getTopicText());
            if (!offer.getContextNote().isEmpty()) {
                prompt.append(" (").append(offer.getContextNote()).append(')');
            }
            prompt.append('\n');
        }
        if (!request.getDriftGuardNotes().isEmpty()) {
            prompt.append("\nDrift-Hinweise (NICHT wählbar, niemals als Erweiterung anbieten — ")
                    .append("diese Bereiche bleiben bewusst ausgeschlossen):\n");
            for (String note : request.getDriftGuardNotes()) {
                prompt.append("- ").append(note).append('\n');
            }
        }
        prompt.append("\nFormuliere für den gewählten Kandidaten GENAU EINE natürliche, ")
                .append("konkrete Frage an den Nutzer, in derselben Sprache wie der Auftrag. ")
                // Live finding: without this, the model asks "Möchten Sie mehr erfahren?" —
                // an information offer instead of the scope decision the fence needs.
                .append("Die Frage muss eine SCOPE-Entscheidung herbeiführen — soll das Thema ")
                .append("in den Themenzuschnitt aufgenommen, ausgeschlossen oder präzisiert ")
                .append("werden? Niemals Informationsangebote wie „Möchten Sie mehr ")
                .append("erfahren“. ")
                .append("Wenn kein Kandidat eine sinnvolle nächste Frage ergibt, antworte mit ")
                .append("decision NONE und einem kurzen ehrlichen Satz (das bedeutet NICHT, dass ")
                .append("der Themenzuschnitt vollständig ist).\n")
                .append("\nAntworte NUR mit diesem JSON-Format:\n")
                .append("{\"decision\":\"ASK\",\"candidateId\":\"...\",")
                .append("\"assistantMessage\":\"...\"}\n")
                .append("oder\n")
                .append("{\"decision\":\"NONE\",\"candidateId\":\"\",")
                .append("\"assistantMessage\":\"...\"}");
        return prompt.toString();
    }

    /**
     * The structured-output schema for THIS offer set: decision is a two-value enum, and the
     * candidateId enum makes an INVENTED id unrepresentable at generation time (the drift guards
     * have no id, so they cannot be chosen by construction). Validation downstream stays.
     */
    private static String responseSchema(ChoiceRequest request) {
        // candidateId is REQUIRED (live: gemma answered ASK and simply omitted the optional
        // field) — the empty string in the enum is the grammar's NONE case; the validator still
        // rejects ASK with an empty id, typed.
        StringBuilder schema = new StringBuilder("{\"type\":\"object\",\"properties\":{")
                .append("\"decision\":{\"type\":\"string\",\"enum\":[\"ASK\",\"NONE\"]},")
                .append("\"candidateId\":{\"type\":\"string\",\"enum\":[\"\"");
        for (CandidateOffer offer : request.getCandidates()) {
            schema.append(',').append(jsonString(offer.getCandidateId()));
        }
        schema.append("]},\"assistantMessage\":{\"type\":\"string\"}},")
                .append("\"required\":[\"decision\",\"candidateId\",")
                .append("\"assistantMessage\"]}");
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

    /** German labels for the four conversational situations — the model needs the meaning. */
    private static String reasonLabel(ScopeAdviceCandidate.Reason reason) {
        switch (reason) {
            case RESOLVE_PENDING:
                return "bereits angesprochen, noch unentschieden";
            case CLARIFY_BOUNDARY:
                return "echte Grenzfrage zwischen aufgenommen und ausgeschlossen";
            case CHECK_IN_EXTENSION:
                return "Rand eines bereits aufgenommenen Bereichs";
            default:
                return "mögliche neue, noch nicht angesprochene Insel";
        }
    }

    private static ChoiceResult.Status statusOf(MainModelChatResult.Status status) {
        switch (status) {
            case TIMEOUT:
                return ChoiceResult.Status.TIMEOUT;
            case INVALID_RESPONSE:
                return ChoiceResult.Status.INVALID_RESPONSE;
            default:
                return ChoiceResult.Status.PROVIDER_FAILURE;
        }
    }

    /**
     * Strict, deterministic validation: a structural breach or an INVENTED candidate id is a
     * typed INVALID_RESPONSE — never re-bound, never re-asked, never silently degraded to NONE
     * ("model broke" must stay distinguishable from "nothing worth asking").
     */
    private static ChoiceResult validate(String answer, ChoiceRequest request) {
        Object parsed;
        try {
            parsed = MiniJson.parse(unfence(answer));
        } catch (MiniJson.JsonParseException malformed) {
            String trimmed = answer == null ? "" : answer.trim().replaceAll("\\s+", " ");
            return ChoiceResult.failure(ChoiceResult.Status.INVALID_RESPONSE,
                    "not JSON: " + malformed.getMessage() + (trimmed.isEmpty() ? " (empty answer)"
                            : " (answer starts: '" + (trimmed.length() <= 80 ? trimmed
                                    : trimmed.substring(0, 80) + "…") + "')"));
        }
        if (!(parsed instanceof Map)) {
            return ChoiceResult.failure(ChoiceResult.Status.INVALID_RESPONSE,
                    "top level is not an object");
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        Object decisionRaw = root.get("decision");
        Object messageRaw = root.get("assistantMessage");
        String message = messageRaw instanceof String ? ((String) messageRaw).trim() : "";
        if ("NONE".equals(decisionRaw)) {
            return ChoiceResult.ok(AdviceDecision.none(message));
        }
        if (!"ASK".equals(decisionRaw)) {
            return ChoiceResult.failure(ChoiceResult.Status.INVALID_RESPONSE,
                    "decision must be ASK or NONE, was: " + decisionRaw);
        }
        Object candidateRaw = root.get("candidateId");
        if (!(candidateRaw instanceof String) || ((String) candidateRaw).trim().isEmpty()) {
            return ChoiceResult.failure(ChoiceResult.Status.INVALID_RESPONSE,
                    "ASK without candidateId");
        }
        String candidateId = ((String) candidateRaw).trim();
        if (!request.offersCandidate(candidateId)) {
            // Also covers every attempt to "choose" a drift guard — guards have no selectable id.
            return ChoiceResult.failure(ChoiceResult.Status.INVALID_RESPONSE,
                    "model chose an id that was never offered: " + candidateId);
        }
        if (message.isEmpty()) {
            return ChoiceResult.failure(ChoiceResult.Status.INVALID_RESPONSE,
                    "ASK without a phrased question");
        }
        return ChoiceResult.ok(AdviceDecision.ask(candidateId, message));
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
}
