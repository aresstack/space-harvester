package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.reranker.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the scoping model's raw text into a validated {@link ScopingAssistantOutput}. Like the generic
 * parser it tolerantly extracts the first balanced JSON object (prose/code-fence resilient), then enforces the
 * scoping contract: ONLY a non-blank {@code assistantMessage} is required. {@code researchBriefMarkdown},
 * {@code searchSuggestions} and the scope block are optional — a turn that just asks a good question is a
 * complete turn, and requiring a brief or a suggestion would make the assistant produce them for the parser
 * rather than for the user. A suggestion with a blank query is still rejected; {@code advice} defaults to
 * NEUTRAL. A malformed answer is a typed failure the caller answers with ONE bounded repair, then an honest
 * error — never a fabricated turn.
 */
public final class ScopingAssistantOutputParser {

    /** A generous guard against pathological payloads (idea maps / briefs are prose, not documents). */
    private static final int MAX_FIELD_CHARS = 20000;

    public static final class Result {
        private final ScopingAssistantOutput output;
        private final String error;

        private Result(ScopingAssistantOutput output, String error) {
            this.output = output;
            this.error = error;
        }

        public boolean isOk() {
            return output != null;
        }

        public ScopingAssistantOutput getOutput() {
            return output;
        }

        public String getError() {
            return error;
        }
    }

    private ScopingAssistantOutputParser() {
    }

    @SuppressWarnings("unchecked")
    public static Result parse(String rawModelText) {
        String json = TeamAgentTurnParser.extractJsonObject(rawModelText);
        if (json == null) {
            return fail("no JSON object found in the model answer");
        }
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (MiniJson.JsonParseException malformed) {
            return fail("model answer was not valid JSON: " + malformed.getMessage());
        }
        if (!(root instanceof Map)) {
            return fail("model answer is not a JSON object");
        }
        Map<String, Object> object = (Map<String, Object>) root;

        String assistantMessage = asString(object.get("assistantMessage"));
        if (isBlank(assistantMessage)) {
            return fail("scoping answer has no assistantMessage");
        }
        // OPTIONAL: the structured scope is the truth; a brief is a projection and may be absent this turn.
        String brief = asString(object.get("researchBriefMarkdown"));
        if (brief != null && brief.length() > MAX_FIELD_CHARS) {
            return fail("researchBriefMarkdown exceeds the size limit");
        }
        // Suggestions are optional; a malformed one is still rejected (a blank query is not a suggestion).
        List<SearchSuggestion> suggestions = new ArrayList<SearchSuggestion>();
        Object rawSuggestions = object.get("searchSuggestions");
        if (rawSuggestions instanceof List) {
            for (Object element : (List<Object>) rawSuggestions) {
                if (!(element instanceof Map)) {
                    continue;
                }
                Map<String, Object> suggestion = (Map<String, Object>) element;
                String query = asString(suggestion.get("query"));
                if (isBlank(query)) {
                    return fail("a search suggestion has a blank query");
                }
                int priority = asPositiveInt(suggestion.get("priority"), 1);
                if (priority <= 0) {
                    return fail("a search suggestion has a non-positive priority");
                }
                suggestions.add(new SearchSuggestion(query, asString(suggestion.get("purpose")), priority));
            }
        }
        // OPTIONAL: zero suggestions is a normal turn. Requiring one would make the assistant produce
        // searches nobody needs, which is precisely the behaviour we are trying to get rid of.

        PhaseAdvice advice = PhaseAdvice.neutral();
        Object rawAdvice = object.get("advice");
        if (rawAdvice instanceof Map) {
            Map<String, Object> adviceMap = (Map<String, Object>) rawAdvice;
            advice = new PhaseAdvice(
                    PhaseAdviceRecommendation.fromToken(asString(adviceMap.get("recommendation"))),
                    asString(adviceMap.get("reason")));
        }

        // The scope changes this turn proposes. A malformed element invalidates the WHOLE scope update —
        // never a part of it — but it does NOT kill the conversation: the scope block is optional, so
        // failing the turn over it costs the user their answer AND (because a failed turn is not committed
        // to history) the message they just wrote. The invalid document travels on, is never applied, and
        // the host reports it as a visible problem.
        ScopeUpdateDocument scopeUpdate = ScopeUpdateDocument.from(object.get("scopePatch"),
                object.get("unresolvedIssues"), object.get("orientationSuggestions"));
        // The optional ONE concept tool step. A malformed action does NOT fail the turn (that
        // would cost the user their message); the reason travels in the output and goes back to
        // the model as a rejection through the tool-round loop.
        ConceptAction.Parsed conceptAction = ConceptAction.parse(object.get("conceptAction"));
        return new Result(new ScopingAssistantOutput(assistantMessage, brief, suggestions, advice,
                scopeUpdate.isEmpty() && scopeUpdate.isValid() ? null : scopeUpdate,
                conceptAction), null);
    }

    private static Result fail(String error) {
        return new Result(null, error);
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** MiniJson yields numbers as Double; accept an integral value, else the default. */
    private static int asPositiveInt(Object value, int fallback) {
        if (value instanceof Number) {
            return (int) Math.round(((Number) value).doubleValue());
        }
        if (value instanceof String) {
            try {
                return (int) Math.round(Double.parseDouble(((String) value).trim()));
            } catch (NumberFormatException notANumber) {
                return fallback;
            }
        }
        return fallback;
    }
}
