package com.aresstack.askai.research.domain.scope;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A scoping turn proposes CHANGES, never a replacement scope. These are the three characterisation
 * scenarios of the slice: the area stays broad, a later refinement loses nothing, and "I don't know" is a
 * legitimate result.
 */
public class ScopePatchTest {

    /** 1. Two directions the user finds interesting BOTH stay open — no funnel to one question. */
    @Test
    public void aTurnMayKeepSeveralDirectionsOpenAtOnce() {
        // "Wearables im Baugewerbe interessieren mich. Sicherheit und Gesundheit finde ich beide spannend."
        ScopePatch patch = new ScopePatch(Arrays.asList(
                ScopePatchOperations.setMission("Wearables im Baugewerbe"),
                ScopePatchOperations.addDomain("Wearables"),
                ScopePatchOperations.addContext("Baugewerbe"),
                ScopePatchOperations.addFacet("worker-safety", "Arbeitssicherheit", "vom Nutzer genannt"),
                ScopePatchOperations.addFacet("occupational-health", "Gesundheit", "vom Nutzer genannt")));

        ResearchScopeDraft draft = patch.applyTo(ResearchScopeDraft.empty());

        assertEquals("both directions stay in scope", 2, draft.includedFacets().size());
        assertEquals(ScopeFacet.Status.PROVISIONAL, draft.facet("worker-safety").getStatus());
        assertEquals(ScopeFacet.Status.PROVISIONAL, draft.facet("occupational-health").getStatus());
        assertEquals("Wearables im Baugewerbe", draft.getMission());
        assertEquals(Arrays.asList("Baugewerbe"), draft.getContexts());
        assertEquals(1L, draft.getRevision());
    }

    /** 2. A later refinement changes exactly what it says and nothing else. */
    @Test
    public void aLaterRefinementDowngradesWithoutLosingAnythingElse() {
        ResearchScopeDraft afterTurnOne = new ScopePatch(Arrays.asList(
                ScopePatchOperations.setMission("Wearables"),
                ScopePatchOperations.addFacet("ar", "AR-Brillen", "finde ich auch interessant"),
                ScopePatchOperations.addFacet("rings", "Smart Rings", ""),
                ScopePatchOperations.addTerminology("HRV")))
                .applyTo(ResearchScopeDraft.empty());

        // Turn 4: "AR ruhig drinlassen, aber nur am Rand."
        ResearchScopeDraft afterTurnFour = new ScopePatch(Arrays.asList(
                ScopePatchOperations.confirmFacet("ar", "bleibt drin, aber am Rand"),
                ScopePatchOperations.setFacetEmphasis("ar", CoverageEmphasis.Importance.LOW,
                        CoverageEmphasis.ResearchDepth.OVERVIEW, CoverageEmphasis.NO_SHARE_HINT)))
                .applyTo(afterTurnOne);

        assertEquals(ScopeFacet.Status.CONFIRMED, afterTurnFour.facet("ar").getStatus());
        assertEquals(CoverageEmphasis.Importance.LOW, afterTurnFour.emphasisOf("ar").getImportance());
        assertEquals(CoverageEmphasis.ResearchDepth.OVERVIEW,
                afterTurnFour.emphasisOf("ar").getResearchDepth());
        assertFalse("AR is still IN scope, just marginal", afterTurnFour.facet("ar").isExcluded());
        // Everything else is untouched — that is what a patch buys over a replacement object.
        assertEquals("Wearables", afterTurnFour.getMission());
        assertEquals(2, afterTurnFour.getFacets().size());
        assertEquals(ScopeFacet.Status.PROVISIONAL, afterTurnFour.facet("rings").getStatus());
        assertEquals(Arrays.asList("HRV"), afterTurnFour.getTerminology());
        assertEquals(2L, afterTurnFour.getRevision());
    }

    /** 3. The assistant lacks the map: it records the uncertainty instead of guessing a narrow question. */
    @Test
    public void notKnowingIsRecordedAsAnIssueRatherThanGuessed() {
        // "Truthahnragout finde ich interessant, aber ich weiß gar nicht, was daran interessant sein könnte."
        ScopePatch patch = new ScopePatch(Arrays.asList(
                ScopePatchOperations.setMission("Truthahnragout"),
                ScopePatchOperations.addUnresolvedIssue(new UnresolvedScopeIssue("taxonomy",
                        "Unklar, welche Aspekte an dem Gericht überhaupt untersuchbar sind",
                        null, UnresolvedScopeIssue.Significance.CRITICAL))));

        ResearchScopeDraft draft = patch.applyTo(ResearchScopeDraft.empty());

        assertEquals("no invented facets", 0, draft.getFacets().size());
        assertEquals(1, draft.getUnresolvedIssues().size());
        assertTrue(draft.getUnresolvedIssues().get(0).isCritical());
        assertEquals("Truthahnragout", draft.getMission());
    }

    @Test
    public void anEmptyPatchLeavesTheDraftAndItsRevisionUntouched() {
        ResearchScopeDraft draft = new ScopePatch(Arrays.asList(
                ScopePatchOperations.setMission("Wearables"))).applyTo(ResearchScopeDraft.empty());

        ResearchScopeDraft afterChat = ScopePatch.empty().applyTo(draft);

        assertTrue("a purely conversational turn is not a scope change", afterChat == draft);
        assertEquals(1L, afterChat.getRevision());
    }

    @Test
    public void anExclusionKeepsTheFacetOnRecordAndTheReasonWithIt() {
        ResearchScopeDraft draft = new ScopePatch(Arrays.asList(
                ScopePatchOperations.addFacet("buying", "Kaufberatung", "erst mal offen"),
                ScopePatchOperations.excludeFacet("buying", "will keine Produktempfehlungen"),
                ScopePatchOperations.addExclusion("Kaufberatung")))
                .applyTo(ResearchScopeDraft.empty());

        assertEquals(1, draft.getFacets().size());
        assertTrue(draft.facet("buying").isExcluded());
        assertEquals("the reason given WITH the decision explains the current status",
                "will keine Produktempfehlungen", draft.facet("buying").getRationale());
        assertEquals(0, draft.includedFacets().size());
        assertEquals(Arrays.asList("Kaufberatung"), draft.getExclusions());
    }

    @Test
    public void aResolvedIssueDisappearsWhileTheRestOfTheScopeStands() {
        ResearchScopeDraft open = new ScopePatch(Arrays.asList(
                ScopePatchOperations.addFacet("validation", "Validierungsstudien", ""),
                ScopePatchOperations.addUnresolvedIssue(new UnresolvedScopeIssue("geo",
                        "EU oder USA?", Arrays.asList("validation"),
                        UnresolvedScopeIssue.Significance.SIGNIFICANT))))
                .applyTo(ResearchScopeDraft.empty());

        ResearchScopeDraft answered = new ScopePatch(Arrays.asList(
                ScopePatchOperations.setGeographicScope("EU und USA"),
                ScopePatchOperations.resolveIssue("geo"))).applyTo(open);

        assertEquals(0, answered.getUnresolvedIssues().size());
        assertEquals("EU und USA", answered.getGeographicScope());
        assertEquals(1, answered.getFacets().size());
    }

    @Test
    public void everyOperationDescribesItselfForTheAudit() {
        ScopePatch patch = new ScopePatch(Arrays.asList(
                ScopePatchOperations.addFacet("ar", "AR", "genannt"),
                ScopePatchOperations.setFacetEmphasis("ar", CoverageEmphasis.Importance.LOW,
                        CoverageEmphasis.ResearchDepth.OVERVIEW, CoverageEmphasis.NO_SHARE_HINT),
                ScopePatchOperations.setDeliverable(new ResearchDeliverable(20, 30,
                        ResearchDeliverable.LengthUnit.PAGES, SynthesisPolicy.defaults()))));

        assertEquals(Arrays.asList("addFacet ar (genannt)", "emphasis ar = LOW/OVERVIEW",
                        "deliverable = 20-30 PAGES"),
                patch.describeOperations());
    }
}
