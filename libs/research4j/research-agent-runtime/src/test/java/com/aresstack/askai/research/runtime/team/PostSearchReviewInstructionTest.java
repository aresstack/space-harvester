package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The live frustration: three searches, three near-identical answers — an abstract overview, the same
 * three theme clusters, one open question — and never a concrete legal fact, although the sources carried
 * them. The model followed its instruction to the letter; the TEMPLATE was the bug. This pins the
 * corrected contract: substance first, no re-proposed structures, the abstraction mandate gone.
 */
public class PostSearchReviewInstructionTest {

    @Test
    public void theScopingReviewDemandsSubstanceNotATemplate() {
        String instruction = TeamAgentPlaybook.sourceReviewInstruction();
        assertTrue("concrete findings lead", instruction.contains("LEAD WITH THE CONCRETE FINDINGS"));
        assertTrue("describing instead of relaying is called out",
                instruction.contains("NAME the differences"));
        assertTrue("a structure the conversation already has is not re-proposed",
                instruction.contains("Do NOT re-propose a structure"));
        assertFalse("the abstraction template is gone",
                instruction.contains("HIGHER LEVEL OF ABSTRACTION"));
        assertFalse("clusters are no longer mandatory boilerplate",
                instruction.contains("Then name the THEME CLUSTERS"));
        assertTrue("grounding stays", instruction.contains("Never infer content from a title"));
        assertTrue("suggestions still refresh toward gaps", instruction.contains("REFRESH"));
    }

    @Test
    public void theSummaryVariantFollowsTheSameContract() {
        String instruction = TeamAgentPlaybook.sourceSummaryInstruction();
        assertTrue(instruction.contains("LEAD WITH THE CONCRETE FINDINGS"));
        assertTrue(instruction.contains("Do NOT re-propose a structure"));
        assertFalse(instruction.contains("HIGHER LEVEL OF ABSTRACTION"));
    }
}
