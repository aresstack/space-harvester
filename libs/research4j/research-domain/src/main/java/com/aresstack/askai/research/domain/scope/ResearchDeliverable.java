package com.aresstack.askai.research.domain.scope;

/**
 * WHAT the user wants to hold at the end: a rough size plus the {@link SynthesisPolicy} the result must
 * follow. "20-30 Seiten, Kategorien statt Produktlisten, Vergleich gefordert" is a decision the user makes
 * during scoping — it is recorded here, WITHOUT pre-empting any chapter structure.
 */
public final class ResearchDeliverable {

    /** The unit the size refers to; UNSPECIFIED when the user gave no size at all. */
    public enum LengthUnit { UNSPECIFIED, PAGES, WORDS }

    /** No size given. */
    public static final int NO_LENGTH = -1;

    private final int targetLengthMin;
    private final int targetLengthMax;
    private final LengthUnit lengthUnit;
    private final SynthesisPolicy synthesisPolicy;

    /** No size stated yet, but the standard synthesis contract already applies. */
    public static ResearchDeliverable unspecified() {
        return new ResearchDeliverable(NO_LENGTH, NO_LENGTH, LengthUnit.UNSPECIFIED,
                SynthesisPolicy.defaults());
    }

    public ResearchDeliverable(int targetLengthMin, int targetLengthMax, LengthUnit lengthUnit,
                               SynthesisPolicy synthesisPolicy) {
        boolean sized = lengthUnit != null && lengthUnit != LengthUnit.UNSPECIFIED
                && targetLengthMin > 0 && targetLengthMax > 0;
        // A reversed range is the user's intent stated loosely ("30 bis 20 Seiten"), not an error.
        this.targetLengthMin = sized ? Math.min(targetLengthMin, targetLengthMax) : NO_LENGTH;
        this.targetLengthMax = sized ? Math.max(targetLengthMin, targetLengthMax) : NO_LENGTH;
        this.lengthUnit = sized ? lengthUnit : LengthUnit.UNSPECIFIED;
        this.synthesisPolicy = synthesisPolicy == null ? SynthesisPolicy.defaults() : synthesisPolicy;
    }

    public int getTargetLengthMin() {
        return targetLengthMin;
    }

    public int getTargetLengthMax() {
        return targetLengthMax;
    }

    public LengthUnit getLengthUnit() {
        return lengthUnit;
    }

    public boolean hasTargetLength() {
        return lengthUnit != LengthUnit.UNSPECIFIED;
    }

    public SynthesisPolicy getSynthesisPolicy() {
        return synthesisPolicy;
    }
}
