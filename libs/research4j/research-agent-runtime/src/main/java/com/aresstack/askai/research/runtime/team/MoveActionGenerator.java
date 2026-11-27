package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The DEDICATED move generation strategy (move-gate ruling): inside the universal flat action
 * grammar the optional {@code source}/{@code parent} fields STARVED — gemma emitted
 * {@code type="move"} three repair rounds long without ever filling them. For a turn the
 * machine already classified as an explicit move order, this generator runs ONE tiny
 * inference whose schema knows nothing but the two REQUIRED fields:
 *
 * <pre>{"source": ["Scheduling"], "parent": ["FreeRTOS"]}</pre>
 *
 * The result becomes the EXISTING internal move operation — same parser, same validator, same
 * {@code concept_move_leaf} funnel, same branch/ambiguity/collision/receipt rules host-side.
 * When the user's words do not name both parts clearly, the model answers with the UNCLEAR
 * marker instead of guessing, and no mutation happens.
 */
public final class MoveActionGenerator {

    /** The whole delivered schema: exactly two fields, BOTH required, no action kinds. */
    static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"source\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":6,\"items\":"
            + "{\"type\":\"string\",\"minLength\":1}},"
            + "\"parent\":{\"type\":\"array\",\"maxItems\":6,\"items\":"
            + "{\"type\":\"string\",\"minLength\":1}}},"
            + "\"required\":[\"source\",\"parent\"]}";

    /** The honest no-guess escape: an impossible card name, never a real source. */
    static final String UNCLEAR_MARKER = "?";

    private MoveActionGenerator() {
    }

    /** The exact request handed to the model provider — exposed for the contract tests. */
    static List<ChatMessage> messages(String userOrder, String conceptContext) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                "ONE task only: extract the card MOVE the user ordered.\n"
                        + "Reply with ONLY {\"source\": [...], \"parent\": [...]}.\n"
                        + "- source: the ONE card to move — its exact name from the concept "
                        + "below (a single unique name is enough; segments only for "
                        + "duplicates).\n"
                        + "- parent: the target parent card; [] means the top level.\n"
                        + "- Copy names EXACTLY as they appear in the concept.\n"
                        + "- If the user's words do not name BOTH clearly, reply "
                        + "{\"source\": [\"" + UNCLEAR_MARKER + "\"], \"parent\": []} — "
                        + "NEVER guess.\n"
                        + "Example: \"Verschiebe <child card> unter <new parent>.\" -> "
                        + "{\"source\": [\"<child card>\"], \"parent\": [\"<new parent>\"]}"));
        messages.add(new ChatMessage(ChatMessage.Role.USER,
                "CURRENT CONCEPT:\n" + (conceptContext == null ? "" : conceptContext)
                        + "\n\nUSER ORDER:\n" + userOrder));
        return messages;
    }

    /**
     * Run the dedicated inference and hand back the internal move action, or {@code null} when
     * the model declared the order unclear, answered garbage, or the transport failed — the
     * caller then closes the turn honestly instead of guessing a mutation.
     */
    public static ConceptAction generate(MainModelChat chat, String userOrder,
                                         String conceptContext) {
        MainModelChatResult result = chat.completeJson(messages(userOrder, conceptContext),
                0.0, 220, SCHEMA);
        if (result == null || !result.isOk()) {
            return null;
        }
        return parseReply(result.getText());
    }

    /** Strict reply mapping into the EXISTING action contract (parser + validator reused). */
    static ConceptAction parseReply(String replyText) {
        Object parsed;
        try {
            parsed = com.aresstack.askai.agent.model.reranker.MiniJson.parse(
                    replyText == null ? "" : replyText.trim());
        } catch (RuntimeException broken) {
            return null;
        }
        if (!(parsed instanceof Map)) {
            return null;
        }
        Object source = ((Map<?, ?>) parsed).get("source");
        if (source instanceof List && ((List<?>) source).size() == 1
                && UNCLEAR_MARKER.equals(String.valueOf(((List<?>) source).get(0)).trim())) {
            return null; // the model honestly declined to guess
        }
        Map<String, Object> action = new LinkedHashMap<String, Object>();
        action.put("type", "move");
        action.put("source", source);
        action.put("parent", ((Map<?, ?>) parsed).get("parent"));
        ConceptAction.Parsed viaContract = ConceptAction.parse(action);
        return viaContract.getAction();
    }
}
