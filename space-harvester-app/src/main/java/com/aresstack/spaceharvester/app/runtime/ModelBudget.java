package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeTokenCounter;

/**
 * Tokenbudget einer Modellanfrage (#1486, docs/grenzen.md „Modellkontext“): die Kontextlänge des Modells aus den
 * KI-Einstellungen abzüglich der Reserve für die Antwort (das eingestellte Ausgabelimit). Textbudgets für KI-Aufrufe
 * werden daraus abgeleitet statt als feste Zeichenzahl im Code zu stehen; was nicht in eine Anfrage passt, geht in
 * mehrere, nie abgeschnitten.
 *
 * @param contextTokens Kontextlänge des Modells in Tokens
 * @param answerTokens  Tokens, die für die Antwort frei bleiben
 */
public record ModelBudget(int contextTokens, int answerTokens) {

    /**
     * Zeichen je Token, die die Schätzung höchstens annimmt. Ein Tokenizer des eingestellten Modells fehlt hier, darum
     * nimmt {@link #tokens(String)} das Größere aus der Wort-und-Zeichen-Zählung der Zerlegung
     * ({@link KnowledgeTokenCounter#wordsAndSymbols()}) und der Textlänge geteilt durch diesen Wert. Übliche
     * BPE-Tokenizer liefern für Code und deutsche Prosa deutlich mehr als drei Zeichen je Token; die Schätzung irrt
     * also zu kleineren Anfragen hin.
     */
    public static final int CHARACTERS_PER_TOKEN = 3;
    private static final KnowledgeTokenCounter WORDS = KnowledgeTokenCounter.wordsAndSymbols();

    public ModelBudget {
        if (contextTokens < 1) {
            throw new IllegalArgumentException("Kontextlänge des Chat-Modells fehlt.");
        }
        if (answerTokens < 1) {
            throw new IllegalArgumentException("Ausgabelimit des Chat-Modells fehlt.");
        }
    }

    /** Aus den KI-Einstellungen; nennt die fehlende Einstellung, statt einen Wert zu raten. */
    public static ModelBudget of(Integer contextTokens, Integer answerTokens) {
        if (contextTokens == null || contextTokens < 1) {
            throw new IllegalStateException("Kontextlänge des Chat-Modells unter Einstellungen › KI-Dienst angeben.");
        }
        if (answerTokens == null || answerTokens < 1) {
            throw new IllegalStateException("Ausgabelimit des Chat-Modells unter Einstellungen › KI-Dienst angeben;"
                    + " es ist die Reserve für die Antwort.");
        }
        return new ModelBudget(contextTokens, answerTokens);
    }

    /** Wie {@link #of}, aber {@code null}, wenn Kontextlänge oder Ausgabelimit fehlen (dann gilt keine Grenze). */
    public static ModelBudget known(Integer contextTokens, Integer answerTokens) {
        return contextTokens == null || contextTokens < 1 || answerTokens == null || answerTokens < 1 ? null
                : new ModelBudget(contextTokens, answerTokens);
    }

    /**
     * Das Budget des Chat-Modells: Kontextlänge aus den Einstellungen ({@code ai.context-tokens}), sonst wie der
     * KI-Dienst sie in seinem Modellkatalog meldet; Antwortreserve das Ausgabelimit ({@code ai.chat-max-tokens}), sonst
     * dessen Vorgabe im Core, mit der auch gesendet wird. {@code null}, wenn keine Kontextlänge bekannt ist.
     */
    public static ModelBudget chat(Integer configuredContext, Integer catalogContext, Integer chatMaxTokens) {
        Integer context = configuredContext != null && configuredContext > 0 ? configuredContext : catalogContext;
        int answer = chatMaxTokens != null && chatMaxTokens > 0 ? chatMaxTokens
                : com.aresstack.spaceharvester.ai.AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS;
        return known(context, answer);
    }

    /**
     * Vorsichtige Schätzung der Tokens eines Anfrageteils, siehe {@link #CHARACTERS_PER_TOKEN}: die Länge in
     * {@link #units Zeicheneinheiten} geteilt durch diesen Wert, mindestens die Wort-und-Zeichen-Zählung.
     */
    public static int tokens(String text) {
        long units = units(text, 0, text.length());
        long byLength = (units + CHARACTERS_PER_TOKEN - 1) / CHARACTERS_PER_TOKEN;
        return (int) Math.max(WORDS.count(text), Math.min(Integer.MAX_VALUE, byLength));
    }

    /**
     * Länge eines Textausschnitts in Zeicheneinheiten, der Währung aller Zeichenbudgets des Chats
     * ({@code ChatRoom}): Druckbares ASCII samt Zeilenumbruch und Tabulator zählt eins (übliche Tokenizer fassen
     * davon mehrere zu einem Token zusammen); jedes andere Zeichen – Umlaute, Steuerzeichen, als Text gelesene
     * Binärdaten – zählt so viele Token, wie es UTF-8-Bytes hat (der ungünstigste Fall eines Byte-Tokenizers), also
     * {@link #CHARACTERS_PER_TOKEN} Einheiten je Byte. Ohne diese Gewichtung passte Binärsalat nach Zeichen in die
     * Anfrage und sprengte sie in Tokens.
     */
    public static long units(CharSequence text, int from, int to) {
        long units = 0;
        for (int i = from; i < to; ) {
            int codePoint = Character.codePointAt(text, i);
            units += units(codePoint);
            i += Character.charCount(codePoint);
        }
        return units;
    }

    /**
     * Das Ende des längsten Ausschnitts ab {@code from}, der höchstens {@code units} Zeicheneinheiten lang ist; nie
     * mitten in einem Ersatzpaar.
     */
    public static int fit(CharSequence text, int from, long units) {
        long used = 0;
        int i = Math.max(0, from);
        while (i < text.length()) {
            int codePoint = Character.codePointAt(text, i);
            used += units(codePoint);
            if (used > units) {
                break;
            }
            i += Character.charCount(codePoint);
        }
        return i;
    }

    private static int units(int codePoint) {
        if (codePoint >= 0x20 && codePoint < 0x7F || codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
            return 1;
        }
        int bytes = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
        return bytes * CHARACTERS_PER_TOKEN;
    }

    /** Tokens, die nach festem Teil (Systemanweisung, Rahmen) und Antwortreserve für den Inhalt frei sind. */
    public int left(int fixedTokens) {
        return contextTokens - answerTokens - fixedTokens;
    }
}
