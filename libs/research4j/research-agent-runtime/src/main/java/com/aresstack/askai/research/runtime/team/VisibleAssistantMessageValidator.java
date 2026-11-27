package com.aresstack.askai.research.runtime.team;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A hard gate that keeps CODEC/transport concerns out of the chat. A format-repair round (see
 * {@link TeamAgentPlaybook#repairNudge()}) can provoke the model into an apology or a meta-explanation whose
 * {@code assistantMessage} — "I apologize if my previous response was not formatted correctly…" — would
 * otherwise be shown verbatim. A repaired turn whose visible message fails this check is treated as an
 * UNUSABLE_ANSWER instead of being surfaced, so a repair can never become a visible business turn.
 *
 * <p>Only genuinely meta phrases/tokens are rejected. Bare everyday words that legitimately occur in warm
 * scoping prose (e.g. "command of the subject", "formatting your report") are deliberately NOT banned to
 * avoid suppressing a real answer.</p>
 */
public final class VisibleAssistantMessageValidator {

    /** Meta phrases that only appear when the model is talking about the wire, not the research. */
    private static final String[] FORBIDDEN_PHRASES = {
            "could not be parsed", "valid json", "json object", "output format", "required format",
            "structured response", "structured research", "state machine", "type the command",
            "i apologize", "apologize if", "my previous response", "previous answer", "formatted correctly"
    };

    /** Bare tokens rare enough in a warm scoping reply that their mere presence signals codec talk. */
    private static final String[] FORBIDDEN_TOKENS = {
            "json", "schema", "protocol", "parsed", "parsing"
    };

    /**
     * An action keyword at the start of a line followed by a colon ("add_cards:", "- offer:"): the shape a
     * small model writes when it narrates its concept action instead of putting it into conceptAction.
     */
    private static final Pattern ACTION_LINE = Pattern.compile(
            "(?im)^[\\s\\-*\u2022>]*(conceptaction|add_cards|offer|read|rename|exclude|resolve|probe|"
                    + "searchsuggestions|scopepatch|assistantmessage)\\s*:");
    /** A JSON fragment of the action channel inside the visible text ({@code "type":"add_cards"}). */
    private static final Pattern ACTION_JSON = Pattern.compile(
            "(?i)\"(type|conceptAction)\"\\s*:\\s*[\"{]");
    /** Wire tokens no business sentence contains. */
    private static final Pattern WIRE_TOKEN = Pattern.compile("(?i)\\b(add_cards|conceptaction)\\b");
    /** The whole visible message is nothing but an action type ("none", "offer."). */
    private static final Pattern BARE_ACTION = Pattern.compile(
            "(?i)^[\\s\"'`]*(none|read|add_cards|offer|exclude|resolve|rename|probe)[\\s\"'`.!]*$");

    private VisibleAssistantMessageValidator() {
    }

    /**
     * The concept action the visible message spells out as TEXT, or {@code null} when it carries none. The
     * live regression: gemma answered "add_cards: - Haltung … offer: …" in assistantMessage while its
     * conceptAction said "none" — parseable JSON, nothing executed, internal instructions shown to the user.
     * Only the action channel's own vocabulary is matched (line-leading keywords with a colon, JSON fragments,
     * snake_case wire tokens, a bare action type), never ordinary words in a sentence.
     */
    public static String actionLeakIn(String assistantMessage) {
        if (assistantMessage == null || assistantMessage.trim().isEmpty()) {
            return null;
        }
        Matcher bare = BARE_ACTION.matcher(assistantMessage);
        if (bare.matches()) {
            return bare.group(1).toLowerCase(Locale.ROOT);
        }
        Matcher line = ACTION_LINE.matcher(assistantMessage);
        if (line.find()) {
            return line.group(1).toLowerCase(Locale.ROOT);
        }
        Matcher json = ACTION_JSON.matcher(assistantMessage);
        if (json.find()) {
            return json.group(1).toLowerCase(Locale.ROOT);
        }
        Matcher token = WIRE_TOKEN.matcher(assistantMessage);
        if (token.find()) {
            return token.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** True when {@code assistantMessage} is a real, non-empty reply free of any codec/transport meta-talk. */
    public static boolean isCleanBusinessMessage(String assistantMessage) {
        if (assistantMessage == null || assistantMessage.trim().isEmpty()) {
            return false;
        }
        String lower = assistantMessage.toLowerCase(Locale.ROOT);
        for (String phrase : FORBIDDEN_PHRASES) {
            if (lower.contains(phrase)) {
                return false;
            }
        }
        for (String token : FORBIDDEN_TOKENS) {
            if (lower.contains(token)) {
                return false;
            }
        }
        return true;
    }
}
