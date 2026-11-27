package com.aresstack.askai.research.runtime.team;

/**
 * Maps a {@link TeamAgentResult} to the single user-visible line the runtime sends as a plain agent
 * message. An OK turn shows the model's own {@code assistantMessage}; every failure shows an HONEST, typed
 * line (never a fabricated answer, never the withheld message of a rejected command) that invites a retry.
 * <p>
 * A failure line is as user-visible as any answer, so it follows the SESSION LANGUAGE. Hard-coding it in
 * English meant a German session answered "I could not form a clear answer just now" — every single time
 * something went wrong, which is exactly when a user should not also have to switch languages.
 */
public final class TeamAgentReply {

    private TeamAgentReply() {
    }

    /** English fallback, kept for callers without a session language (tests, legacy paths). */
    public static String visible(TeamAgentResult result) {
        return visible(result, "en");
    }

    public static String visible(TeamAgentResult result, String languageCode) {
        boolean german = "de".equalsIgnoreCase(languageCode == null ? "" : languageCode.trim());
        switch (result.getStatus()) {
            case OK:
                return result.getOutput().getAssistantMessage();
            case MODEL_UNAVAILABLE:
                return german
                        ? "Ich erreiche das Modell des Recherche-Assistenten gerade nicht"
                                + detailSuffix(result.getDetail()) + ". Bitte gleich noch einmal versuchen."
                        : "I cannot reach the research assistant model right now"
                                + detailSuffix(result.getDetail()) + ". Please try again in a moment.";
            case UNUSABLE_ANSWER:
                return german
                        ? "Ich konnte gerade keine klare Antwort bilden. Formulierst du es anders, oder "
                                + "versuchen wir es noch einmal?"
                        : "I could not form a clear answer just now. Could you rephrase, or try again?";
            case CONTEXT_TOO_LARGE:
                return german
                        ? "Die Anfrage passt nicht in den Kontext des Modells, es wurde nichts gesendet"
                                + detailSuffix(result.getDetail()) + ". Bitte kürze die Nachricht."
                        : "The request does not fit into the model's context and nothing was sent"
                                + detailSuffix(result.getDetail()) + ". Please shorten your message.";
            case COMMAND_REJECTED:
                return german
                        ? "Dieser Schritt ist gerade nicht möglich. Machen wir von hier aus weiter."
                        : "That step is not available right now. Let's continue from where we are.";
            default:
                return "";
        }
    }

    private static String detailSuffix(String detail) {
        return detail == null || detail.trim().isEmpty() ? "" : " (" + detail.trim() + ")";
    }
}
