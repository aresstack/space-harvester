package com.aresstack.askai.research.runtime.writer;

/**
 * One finding of the citation check (plan D8, stages 1 and 2). Findings are flags on the stored revision; the text they
 * point to is never removed. Immutable.
 */
public final class ClaimFlag {

    public enum Kind {
        /** A sentence without any {@code [n]} marker. */
        UNCITED_SENTENCE("uncited sentence"),
        /** A paragraph none of whose sentences cites a passage. */
        UNCITED_PARAGRAPH("uncited paragraph"),
        /** A number, year or percentage that no passage cited by the sentence or its paragraph contains. */
        UNSUPPORTED_NUMBER("unsupported number"),
        /** A quotation that no passage cited by the sentence or its paragraph contains. */
        UNSUPPORTED_QUOTE("unsupported quote"),
        /** A sentence no cited passage carries: the passage is no prose or shares too few of its words (stage 2). */
        UNCARRIED_CLAIM("citation does not carry the claim"),
        /** A sentence that speaks as "we": the voice of a passage's author, never the book's ({@link VoiceCheck}). */
        FIRST_PERSON_VOICE("first-person voice of a source");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Kind kind;
    private final int paragraph;
    private final String sentence;
    private final String detail;

    public ClaimFlag(Kind kind, int paragraph, String sentence, String detail) {
        this.kind = kind;
        this.paragraph = paragraph;
        this.sentence = sentence == null ? "" : sentence;
        this.detail = detail == null ? "" : detail;
    }

    public Kind getKind() {
        return kind;
    }

    /** The 0-based paragraph the finding is in. */
    public int getParagraph() {
        return paragraph;
    }

    public String getSentence() {
        return sentence;
    }

    /** The number or quotation that was not found; empty for uncited findings. */
    public String getDetail() {
        return detail;
    }

    /** {@code unsupported number "15 %": <sentence>} — the line of the repair's error list. */
    public String describe() {
        String what = detail.isEmpty() ? kind.label() : kind.label() + " \"" + detail + "\"";
        return what + ": " + sentence;
    }

    /**
     * The line stored in {@code SectionRevision.claimFlags} (PR 26): {@code unsupported number: 15 %}, or the
     * sentence for uncited findings ({@code uncited sentence: <sentence>}).
     */
    public String revisionLine() {
        if (kind == Kind.UNCARRIED_CLAIM || kind == Kind.FIRST_PERSON_VOICE) {
            return kind.label() + ": " + sentence + " (" + detail + ")";
        }
        return kind.label() + ": " + (detail.isEmpty() ? sentence : detail);
    }

    @Override
    public String toString() {
        return describe();
    }
}
