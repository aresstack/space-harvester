package com.aresstack.spaceharvester.application.enrichment.statements;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@link StatementVerifier} über das Chat-Modell (Prompt unverändert aus dem Enterprise AI Client): Satz und Aussage
 * hinein, ein Wort heraus (JA, NEIN oder UNSICHER). Unabhängig von der Extraktionsanfrage, damit das Modell die Aussage
 * ohne sein früheres Vorgehen beurteilt.
 */
public final class ChatStatementVerifier implements StatementVerifier {

    /** Version der Frage; Teil von {@link #version()}. */
    static final String PROMPT_VERSION = "1";

    static final String INSTRUCTION = "Du prüfst, ob ein Satz eine Aussage ausdrücklich trifft. Maßgeblich ist nur "
            + "der Satz, nicht dein Wissen. Achte auf Rollen: Wer handelt, worauf richtet sich die Handlung? Eine "
            + "Aussage, die der Satz nur verneint, nur als Möglichkeit oder nur unter einer Bedingung trifft, gilt "
            + "nur mit genau dieser Einschränkung. Antworte mit genau einem Wort: JA, NEIN oder UNSICHER.";

    private final Supplier<StatementChat> chat;
    private final BooleanSupplier enabled;

    /**
     * @param chat    das eingestellte Chat-Modell oder {@code null}: keine Prüfung
     * @param enabled ob geprüft wird (je Aufruf gelesen)
     */
    public ChatStatementVerifier(Supplier<StatementChat> chat, BooleanSupplier enabled) {
        this.chat = Objects.requireNonNull(chat, "chat");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    @Override
    public boolean isEnabled() {
        return enabled.getAsBoolean() && ChatStatementExtractor.model(chat.get()) != null;
    }

    @Override
    public String version() {
        return isEnabled() ? "chat-yes-no/" + PROMPT_VERSION + "/" + ChatStatementExtractor.model(chat.get())
                : "none";
    }

    @Override
    public Verdict verify(String sentence, String claim) {
        StatementChat model = chat.get();
        if (!enabled.getAsBoolean() || ChatStatementExtractor.model(model) == null) {
            return Verdict.UNAVAILABLE;
        }
        String answer;
        try {
            answer = model.complete(List.of(StatementChat.Message.system(INSTRUCTION),
                    StatementChat.Message.user("Satz: " + sentence + "\nAussage: " + claim)));
        } catch (RuntimeException unavailable) {
            return Verdict.UNAVAILABLE;
        }
        return parse(answer);
    }

    static Verdict parse(String answer) {
        String word = answer == null ? "" : answer.trim().toUpperCase(Locale.ROOT).replaceAll("^[^A-ZÄÖÜ]+", "");
        if (word.startsWith("JA") || word.startsWith("YES")) {
            return Verdict.YES;
        }
        if (word.startsWith("NEIN") || word.startsWith("NO")) {
            return Verdict.NO;
        }
        return Verdict.UNSURE;
    }
}
