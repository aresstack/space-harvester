package com.aresstack.writing4j.engine;

import com.aresstack.knowledge4j.model.ModelSlot;

/**
 * The model slots of the writing. The host binds each to a model and its capacity (how many calls it takes at once);
 * the strings are those of Ask AI, so a host's settings keep working.
 */
public final class WritingSlots {

    /** The evidence judge: one call per question and bundle of passages, many in parallel when the model allows. */
    public static final ModelSlot PHASE_RESEARCH_EVIDENCE = ModelSlot.of("phase.research.evidence");
    /** The source profiles: one call per group of sources, several in parallel when the model allows. */
    public static final ModelSlot PHASE_RESEARCH_SOURCES = ModelSlot.of("phase.research.sources");
    /** The book writer; the sections are written one after another with the writing memory. */
    public static final ModelSlot PHASE_DRAFT_WRITER = ModelSlot.of("phase.draft.writer");
    /** The sentence check: one call per bundle of sentences, many in parallel when the model allows. */
    public static final ModelSlot PHASE_DRAFT_CHECK = ModelSlot.of("phase.draft.check");

    private WritingSlots() {
    }
}
