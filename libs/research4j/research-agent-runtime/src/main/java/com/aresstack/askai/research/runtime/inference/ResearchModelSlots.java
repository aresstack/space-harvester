package com.aresstack.askai.research.runtime.inference;

import com.aresstack.askai.agent.model.port.ModelSlot;

/**
 * The model slots the research runtime's model roles call (ADR 0001, 3.2; plan 5.1). A slot is a role-level
 * name; the platform resolves it to a model on an endpoint: the exact slot, then its parent (the last
 * {@code .segment} stripped), then {@link #DEFAULT}. The research runtime never falls back to the chat model.
 *
 * <p>Each research phase has its own slot ({@code phase.<phase id>}), shown as Concept, Sources, Outline and
 * Document; role sub-slots ({@code phase.research.planner}, {@code phase.draft.writer}, ...) inherit the model
 * and the thinking mode of their phase. The helper slots {@link #SCOPE}, {@link #JUDGE} and
 * {@link #LAYOUT_REPAIR} stay app-wide. The plugin passes slot ids only, never model names.</p>
 */
public final class ResearchModelSlots {

    /** Closes every resolution chain (preset {@code gemma4:e2b} on the host). */
    public static final ModelSlot DEFAULT = ModelSlot.of("default");
    /** Concept (phase id {@code scoping}): the chat turns, the opening greeting and the concept moves. */
    public static final ModelSlot PHASE_SCOPING = ModelSlot.of("phase.scoping");
    /** Sources (phase id {@code research}). */
    public static final ModelSlot PHASE_RESEARCH = ModelSlot.of("phase.research");
    /**
     * The topic finder of the Sources phase (component 13): reads a loaded source and proposes topics the book does
     * not know yet, sources in parallel. Inherits the Sources model until the user gives it its own; the build plan
     * expects gemma4:e2b to suffice, the stock test decides (gemma4:12b is the reference).
     */
    public static final ModelSlot PHASE_RESEARCH_TOPICS = ModelSlot.of("phase.research.topics");
    /**
     * The topic merge of the Sources phase (component 15): decides only the doubtful cases of the grouping, whether two
     * topics are the same, one a subtopic of the other, or different, and whether two questions ask the same. A
     * sibling of the topic finder, so it inherits the Sources model, not the finder's; the build plan names
     * gemma4:12b.
     */
    public static final ModelSlot PHASE_RESEARCH_MERGE = ModelSlot.of("phase.research.merge");
    /**
     * The fence check of the Sources phase (component 14): judges one merged topic against the mission and the cards
     * of the mind map, INNEN, AUSSEN or UNKLAR with a reason. A sibling of the topic finder, so it inherits the Sources
     * model; the build plan names gemma4:12b.
     */
    public static final ModelSlot PHASE_RESEARCH_FENCE = ModelSlot.of("phase.research.fence");
    /** Outline (phase id {@code outline}). */
    public static final ModelSlot PHASE_OUTLINE = ModelSlot.of("phase.outline");
    /** Document (phase id {@code draft}). */
    public static final ModelSlot PHASE_DRAFT = ModelSlot.of("phase.draft");
    /**
     * The evidence judge of the Sources phase (component 6): one call per question and passage, many in parallel.
     * Inherits the Sources model until the user gives it its own; gemma4:12b is the reference.
     */
    public static final ModelSlot PHASE_RESEARCH_EVIDENCE = ModelSlot.of("phase.research.evidence");
    /** The book pipeline's search planner in the Sources phase. */
    public static final ModelSlot PHASE_RESEARCH_PLANNER = ModelSlot.of("phase.research.planner");
    /**
     * The contradiction check of the Sources phase (component 17): compares two pieces of direct evidence on the same
     * question or topic from different source groups and says whether they contradict each other; it resolves
     * nothing. A few calls per round; inherits the Sources model until the user gives it its own; the build plan
     * names gemma4:12b.
     */
    public static final ModelSlot PHASE_RESEARCH_CONTRADICTIONS = ModelSlot.of("phase.research.contradictions");
    /**
     * The page-noise check of the Sources phase, before the topic finder: one small call per passage that the prose
     * rules let through; the model names the kind of text (a statement on the matter, advertising, table of contents,
     * imprint, ...) and the code derives content or page leftover; it never judges the subject. Many short calls;
     * inherits the Sources model until the user gives it its own. Meant for gemma4:12b: on a fresh label set version 2
     * caught 82 % of the leftovers with it and 71 % with gemma4:e2b.
     */
    public static final ModelSlot PHASE_RESEARCH_NOISE = ModelSlot.of("phase.research.noise");
    /**
     * The research team's page workers in the Sources phase (plan T4): many parallel calls, one per opened page.
     * Inherits the Sources model until the user gives it its own.
     */
    public static final ModelSlot PHASE_RESEARCH_WORKER = ModelSlot.of("phase.research.worker");
    /**
     * The source rating of the Sources phase (component 16): one call per source group, several in parallel.
     * Inherits the Sources model until the user gives it its own. Meant for {@code gemma4:12b}: in the stock test of
     * 22.11.2026 it met all six thresholds, {@code gemma4:e2b} missed the source kind (53 % instead of 85 %).
     */
    public static final ModelSlot PHASE_RESEARCH_SOURCES = ModelSlot.of("phase.research.sources");
    /**
     * The research lead of the Sources phase (component 8): judges the situation after each round and decides the
     * next step (follow a question on, change the strategy, take in new questions, set a question aside, end). One call
     * per round with thinking off (with it, gemma4:12b thought without end in the stock test); inherits the Sources
     * model until the user gives it its own; the build plan names gemma4:12b.
     */
    public static final ModelSlot PHASE_RESEARCH_LEAD = ModelSlot.of("phase.research.lead");
    /** The outline's chapter labels. */
    public static final ModelSlot PHASE_OUTLINE_LABELS = ModelSlot.of("phase.outline.labels");
    /** The outline's chapter briefs. */
    public static final ModelSlot PHASE_OUTLINE_BRIEFS = ModelSlot.of("phase.outline.briefs");
    /**
     * The section plan (component 9): names the section a topic without a section of its own fits best. Few calls,
     * thinking off. Inherits the Outline model until the user gives it its own.
     */
    public static final ModelSlot PHASE_OUTLINE_PLAN = ModelSlot.of("phase.outline.plan");
    /** The book writer in the Document phase. */
    public static final ModelSlot PHASE_DRAFT_WRITER = ModelSlot.of("phase.draft.writer");
    /**
     * The repeat judge of the finished book (component 12): one call per candidate pair of sentences, several in
     * parallel. Inherits the Document model until the user gives it its own; meant for {@code gemma4:12b} (component
     * test of 21.11.2026 on fresh pairs: {@code gemma4:e2b} found 4 of 9 GLEICH, {@code gemma4:12b} 7 of 9 with no
     * false GLEICH).
     */
    public static final ModelSlot PHASE_DRAFT_REPEAT = ModelSlot.of("phase.draft.repeat");
    /**
     * The sentence check of the Document phase (component 11): one call per sentence and cited passage, many in
     * parallel, thinking off. Inherits the Document model until the user gives it its own. Meant for
     * {@code gemma4:12b}: in the stock test of 22.11.2026 its own verdicts met all five criteria, those of
     * {@code gemma4:e2b} found only 28 of 47 covered pairs.
     */
    public static final ModelSlot PHASE_DRAFT_CHECK = ModelSlot.of("phase.draft.check");

    /**
     * The slot of a live phase id: {@code phase.<id>}; an empty id (before the host reports one, the opening
     * greeting) is Concept, and the legacy {@code finalization} phase works on the Document.
     */
    public static ModelSlot phase(String phaseId) {
        String id = phaseId == null ? "" : phaseId.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.isEmpty()) {
            return PHASE_SCOPING;
        }
        if ("finalization".equals(id)) {
            return PHASE_DRAFT;
        }
        return ModelSlot.of("phase." + id);
    }

    /** The summary sub-slot of a phase ({@code phase.<id>.summary}). */
    public static ModelSlot summary(String phaseId) {
        return ModelSlot.of(phase(phaseId).getName() + ".summary");
    }

    /** The scope sweep: probe generator and advice chooser. */
    public static final ModelSlot SCOPE = ModelSlot.of("scope");
    /** The page readiness judge of the acquisition runner. */
    public static final ModelSlot JUDGE = ModelSlot.of("judge");
    /** The model-backed SERP layout repair. */
    public static final ModelSlot LAYOUT_REPAIR = ModelSlot.of("layout-repair");
    /** The research orchestrator: plans, evaluates and re-plans an orchestrated run (ADR 0001, 4.2). */
    public static final ModelSlot ORCHESTRATOR = ModelSlot.of("orchestrator");
    /** The search planner: turns one aspect into distinct search queries (ADR 0001, 4.1, lane G). */
    public static final ModelSlot PLANNER = ModelSlot.of("planner");
    /** The source verifier: judges accepted sources against the brief, finds contradictions (lane G). */
    public static final ModelSlot VERIFIER = ModelSlot.of("verifier");
    /** The writer: drafts cited section text from verified sources (ADR 3.2 names it "synthesis"; lane G). */
    public static final ModelSlot SYNTHESIS = ModelSlot.of("synthesis");

    private ResearchModelSlots() {
    }
}
