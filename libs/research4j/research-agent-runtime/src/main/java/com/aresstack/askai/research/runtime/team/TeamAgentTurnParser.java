package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.reranker.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the main model's raw text into a validated {@link TeamAgentTurn}. The contract asks the model for a
 * single JSON object, but a chat model may still wrap it in prose or ```json fences — so the parser tolerantly
 * extracts the first balanced object before parsing. The ONLY hard requirement is a non-empty
 * {@code assistantMessage}; everything else is optional. A malformed or message-less answer is a typed failure
 * the caller answers with ONE bounded repair attempt, then an honest error — never a fabricated turn.
 */
public final class TeamAgentTurnParser {

    /** The outcome of a parse: either a valid turn, or a reason the raw text could not become one. */
    public static final class Result {
        private final TeamAgentTurn turn;
        private final String error;

        private Result(TeamAgentTurn turn, String error) {
            this.turn = turn;
            this.error = error;
        }

        public boolean isOk() {
            return turn != null;
        }

        public TeamAgentTurn getTurn() {
            return turn;
        }

        public String getError() {
            return error;
        }
    }

    private TeamAgentTurnParser() {
    }

    @SuppressWarnings("unchecked")
    public static Result parse(String rawModelText) {
        String json = extractJsonObject(rawModelText);
        if (json == null) {
            return new Result(null, "no JSON object found in the model answer");
        }
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (MiniJson.JsonParseException malformed) {
            return new Result(null, "model answer was not valid JSON: " + malformed.getMessage());
        }
        if (!(root instanceof Map)) {
            return new Result(null, "model answer is not a JSON object");
        }
        Map<String, Object> object = (Map<String, Object>) root;
        String assistantMessage = asString(object.get("assistantMessage"));
        if (assistantMessage == null || assistantMessage.trim().isEmpty()) {
            return new Result(null, "model answer has no assistantMessage");
        }
        String proposedCommand = asString(object.get("proposedCommand"));

        String question = null;
        List<String> aspects = new ArrayList<String>();
        Object scope = object.get("scope");
        if (scope instanceof Map) {
            Map<String, Object> scopeMap = (Map<String, Object>) scope;
            question = asString(scopeMap.get("question"));
            aspects = asStringList(scopeMap.get("aspects"));
        }

        boolean approvalRequested = false;
        String approvalSubject = null;
        Object approval = object.get("approval");
        if (approval instanceof Map) {
            Map<String, Object> approvalMap = (Map<String, Object>) approval;
            approvalRequested = asBoolean(approvalMap.get("requested"));
            approvalSubject = asString(approvalMap.get("subject"));
        }

        List<String> searchQueries = asStringList(object.get("searchQueries"));
        List<String> understoodFacts = asStringList(object.get("understoodFacts"));
        List<String> suggestedFacts = asStringList(object.get("suggestedFacts"));
        List<String> openQuestions = asStringList(object.get("openQuestions"));

        TeamAgentTurn.Action action = null;
        Object actionValue = object.get("action");
        if (actionValue instanceof Map) {
            Map<String, Object> actionMap = (Map<String, Object>) actionValue;
            action = new TeamAgentTurn.Action(asString(actionMap.get("type")), asString(actionMap.get("query")),
                    asString(actionMap.get("node")), asString(actionMap.get("entry")));
        }

        return new Result(new TeamAgentTurn(assistantMessage, proposedCommand, question, aspects,
                approvalRequested, approvalSubject, searchQueries, understoodFacts, suggestedFacts,
                openQuestions, action), null);
    }

    /** Extract the first balanced top-level {@code {...}} object, ignoring surrounding prose or code fences. */
    static String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null; // unbalanced — treat as malformed
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        List<String> out = new ArrayList<String>();
        if (value instanceof List) {
            for (Object element : (List<Object>) value) {
                if (element instanceof String && !((String) element).trim().isEmpty()) {
                    out.add(((String) element).trim());
                }
            }
        }
        return out;
    }
}
