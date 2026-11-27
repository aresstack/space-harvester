package com.aresstack.askai.research.domain.scope;

/**
 * HOW the result should be put together — captured as a contract, not as prompt prose, because later phases
 * (search planning, coverage, clustering, writing) all have to honour it.
 * <p>
 * The decisive rule is {@link #isCategoryFirst()} together with {@link RepetitiveEntityPolicy#GROUP}:
 * 500 functionally similar products are not 500 units of reporting. They form a CATEGORY, and individual
 * products appear only as representative examples, exceptions or evidence.
 * <p>
 * Discovering the actual categories is explicitly NOT part of scoping — they can only emerge from the
 * research. This policy only records that the result must be organised that way.
 */
public final class SynthesisPolicy {

    /** What to do with many near-identical entities. */
    public enum RepetitiveEntityPolicy {
        /** Group them into categories and name representatives (the default for a research report). */
        GROUP,
        /** List them individually — only when the user explicitly wants an enumeration. */
        ENUMERATE
    }

    /** How concrete examples may be used. */
    public enum ExamplePolicy {
        /** A few representative examples per category. */
        REPRESENTATIVE,
        /** Examples only where they carry evidence (exceptions, contradictions). */
        EVIDENCE_ONLY,
        /** No product/entity examples at all. */
        NONE
    }

    private final boolean categoryFirst;
    private final boolean contrastRequired;
    private final RepetitiveEntityPolicy repetitiveEntityPolicy;
    private final ExamplePolicy examplePolicy;

    /** The default for a research report: categories first, contrasts required, representatives named. */
    public static SynthesisPolicy defaults() {
        return new SynthesisPolicy(true, true, RepetitiveEntityPolicy.GROUP, ExamplePolicy.REPRESENTATIVE);
    }

    public SynthesisPolicy(boolean categoryFirst, boolean contrastRequired,
                           RepetitiveEntityPolicy repetitiveEntityPolicy, ExamplePolicy examplePolicy) {
        this.categoryFirst = categoryFirst;
        this.contrastRequired = contrastRequired;
        this.repetitiveEntityPolicy = repetitiveEntityPolicy == null
                ? RepetitiveEntityPolicy.GROUP : repetitiveEntityPolicy;
        this.examplePolicy = examplePolicy == null ? ExamplePolicy.REPRESENTATIVE : examplePolicy;
    }

    /** Organise by category/dimension rather than by individual entity. */
    public boolean isCategoryFirst() {
        return categoryFirst;
    }

    /** Categories must be set against each other, not merely described one after the other. */
    public boolean isContrastRequired() {
        return contrastRequired;
    }

    public RepetitiveEntityPolicy getRepetitiveEntityPolicy() {
        return repetitiveEntityPolicy;
    }

    public ExamplePolicy getExamplePolicy() {
        return examplePolicy;
    }
}
