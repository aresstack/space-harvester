package com.aresstack.askai.research.runtime.team;

import java.util.List;

/**
 * Serializes a {@link TeamAgentTurn} back into the SAME canonical JSON object shape the model emits, so the
 * assistant history holds exactly one structured turn per assistant message — never a third, invented
 * representation (the old {@code [understood: …] / [still open: …]} note). The user still only ever sees
 * {@link TeamAgentTurn#getAssistantMessage()}; the model, on the other hand, gets its own structured
 * understanding back verbatim when the context is rebuilt, and {@link TeamAgentTurnParser#parse(String)} can
 * read this output back losslessly.
 *
 * <p>{@code MiniJson} is a reader only, so this is a small hand-rolled writer with proper string escaping.</p>
 */
public final class TeamAgentTurnCodec {

    private TeamAgentTurnCodec() {
    }

    /** The canonical JSON for one assistant turn — round-trippable through {@link TeamAgentTurnParser}. */
    public static String toJson(TeamAgentTurn turn) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        appendString(sb, "assistantMessage", turn.getAssistantMessage(), true);
        appendArray(sb, "understoodFacts", turn.getUnderstoodFacts());
        appendArray(sb, "suggestedFacts", turn.getSuggestedFacts());
        appendArray(sb, "openQuestions", turn.getOpenQuestions());
        if (turn.getQuestion() != null || !turn.getAspects().isEmpty()) {
            sb.append(",\"scope\":{");
            appendString(sb, "question", turn.getQuestion() == null ? "" : turn.getQuestion(), true);
            appendArray(sb, "aspects", turn.getAspects());
            sb.append('}');
        }
        TeamAgentTurn.Action action = turn.getAction();
        if (action != null) {
            sb.append(",\"action\":{");
            appendString(sb, "type", action.getType(), true);
            if (!action.getQuery().isEmpty()) {
                appendString(sb, "query", action.getQuery(), false);
            }
            if (!action.getNode().isEmpty()) {
                appendString(sb, "node", action.getNode(), false);
            }
            if (!action.getEntry().isEmpty()) {
                appendString(sb, "entry", action.getEntry(), false);
            }
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String key, String value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(key).append("\":");
        writeString(sb, value == null ? "" : value);
    }

    private static void appendArray(StringBuilder sb, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sb.append(",\"").append(key).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeString(sb, values.get(i));
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
