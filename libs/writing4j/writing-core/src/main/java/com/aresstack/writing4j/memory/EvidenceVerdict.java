package com.aresstack.writing4j.memory;

/**
 * Component 7, record "Belegurteil" of the shared data model: the evidence judge's verdict (component 6) on one
 * passage for one question, after the program checked the quote. Both are kept: what the model said
 * ({@link #getJudgedKind}) and what holds after the check ({@link #getKind}). A BELEGT whose quote does not stand
 * verbatim in the passage, or whose reason names a number the quote does not hold, holds as HINWEIS. A failed call
 * is a FEHLER, never a KEIN. Immutable.
 * <p>
 * Field names of the data model: frageId, passageId, quelleId, Urteil des Modells, Urteil nach Zitatprüfung,
 * Zitat, Grund, Modell, Zeit. Added for the counts: the source's address (for "same domain"), the text key (for
 * "same text") and the research round.
 */
public final class EvidenceVerdict {

    /** How the passage relates to the question. */
    public enum Kind {
        /** The passage answers the question, and the quote shows where. */
        BELEGT,
        /** The passage leads further (a part, a neighbouring fact, a pointer) but does not carry the answer. */
        HINWEIS,
        /** The passage does not answer the question. */
        KEIN,
        /** The judge could not give a verdict (call failed, answer unreadable). The passage is offered again. */
        FEHLER
    }

    private final String questionId;
    private final String passageId;
    private final String sourceId;
    private final String sourceUrl;
    private final String textKey;
    private final Kind judgedKind;
    private final Kind kind;
    private final String quote;
    private final String reason;
    private final String model;
    private final int round;
    private final long timeMillis;

    EvidenceVerdict(String questionId, String passageId, String sourceId, String sourceUrl, String textKey,
                    Kind judgedKind, Kind kind, String quote, String reason, String model, int round,
                    long timeMillis) {
        this.questionId = Texts.clean(questionId);
        this.passageId = Texts.clean(passageId);
        String source = Texts.clean(sourceId);
        this.sourceId = source.isEmpty() ? "passage:" + this.passageId : source;
        this.sourceUrl = Texts.clean(sourceUrl);
        String key = Texts.clean(textKey);
        this.textKey = key.isEmpty() ? "passage:" + this.passageId : key;
        this.judgedKind = judgedKind == null ? Kind.FEHLER : judgedKind;
        this.kind = kind == null ? this.judgedKind : kind;
        this.quote = Texts.clean(quote);
        this.reason = Texts.clean(reason);
        this.model = Texts.clean(model);
        this.round = Math.max(1, round);
        this.timeMillis = timeMillis;
        if (this.questionId.isEmpty() || this.passageId.isEmpty()) {
            throw new IllegalArgumentException("a verdict needs a question id and a passage id");
        }
    }

    /**
     * The judge's answer, checked against the passage it read: a BELEGT holds only when the quote stands verbatim in
     * the passage ({@link PassageText#holdsQuote}) and every number of the reason stands in the quote
     * ({@link PassageText#numbersHeld}); otherwise it holds as HINWEIS.
     *
     * @param passageText the passage the judge read; the quote is looked up in it and the text key is taken from it
     * @param judged      the judge's class
     * @param quote       the words of the passage the judge names as the answer
     * @param reason      what the passage says to the question, in one sentence
     * @param round       the research round the verdict was given in
     */
    public static EvidenceVerdict judged(String questionId, String passageId, String sourceId, String sourceUrl,
                                         String passageText, Kind judged, String quote, String reason, String model,
                                         int round, long timeMillis) {
        Kind said = judged == null ? Kind.FEHLER : judged;
        boolean carries = PassageText.holdsQuote(passageText, quote) && PassageText.numbersHeld(reason, quote);
        Kind holds = said == Kind.BELEGT && !carries ? Kind.HINWEIS : said;
        return new EvidenceVerdict(questionId, passageId, sourceId, sourceUrl, PassageText.key(passageText), said,
                holds, quote, reason, model, round, timeMillis);
    }

    /** The judge failed on this passage; {@code error} says why. The passage is offered again later. */
    public static EvidenceVerdict failed(String questionId, String passageId, String sourceId, String sourceUrl,
                                         String passageText, String error, String model, int round,
                                         long timeMillis) {
        return new EvidenceVerdict(questionId, passageId, sourceId, sourceUrl, PassageText.key(passageText),
                Kind.FEHLER, Kind.FEHLER, "", error, model, round, timeMillis);
    }

    public String getQuestionId() {
        return questionId;
    }

    public String getPassageId() {
        return passageId;
    }

    /** The source the passage comes from; {@code passage:<id>} when the caller named none. */
    public String getSourceId() {
        return sourceId;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    /** The source's registrable domain ({@link SourceDomain#of}), {@code ""} when the address names none. */
    public String getSourceDomain() {
        return SourceDomain.of(sourceUrl);
    }

    /** Two verdicts with the same key are about the same text ({@link PassageText#key}). */
    public String getTextKey() {
        return textKey;
    }

    /** What the model said (Urteil des Modells). */
    public Kind getJudgedKind() {
        return judgedKind;
    }

    /** What holds after the quote check (Urteil nach Zitatprüfung). */
    public Kind getKind() {
        return kind;
    }

    /** {@code true} when the model said BELEGT and the check lowered it (quote not in the passage, or a number). */
    public boolean isDowngraded() {
        return judgedKind != kind;
    }

    public String getQuote() {
        return quote;
    }

    /** The judge's reason in one sentence, or for a FEHLER what went wrong. */
    public String getReason() {
        return reason;
    }

    public String getModel() {
        return model;
    }

    public int getRound() {
        return round;
    }

    public long getTimeMillis() {
        return timeMillis;
    }
}
