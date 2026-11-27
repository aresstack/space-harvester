package com.aresstack.askai.research.domain.scope;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The Z1 invariants of the fence posts, pinned: one anchor per facet with a STABLE derived id;
 * confirm/exclude change only the membership (the semantic text — and with it the vector — stays
 * valid); a richer declared text survives status changes; metadata like rationale never touches the
 * anchor; orphaned anchors disappear with their facet.
 */
public class ScopeAnchorLifecycleTest {

    private static ResearchScopeDraft apply(ResearchScopeDraft draft, ScopePatchOperation... operations) {
        ResearchScopeDraft.Builder builder = draft.toBuilder().nextRevision();
        for (ScopePatchOperation operation : operations) {
            operation.applyTo(builder);
        }
        return builder.build();
    }

    @Test
    public void addConfirmExcludeRefineOneStablePost() {
        ResearchScopeDraft draft = apply(ResearchScopeDraft.empty(),
                ScopePatchOperations.addFacet("exoskelette", "Exoskelette", "vom Agenten vorgeschlagen"));
        ScopeAnchor added = draft.anchorOf("exoskelette");
        assertEquals("anchor-exoskelette", added.getAnchorId());
        assertEquals("Exoskelette", added.getSemanticText());
        assertEquals(ScopeAnchor.Membership.PROVISIONAL, added.getMembership());

        draft = apply(draft, ScopePatchOperations.confirmFacet("exoskelette", "User: ja, gehört dazu"));
        ScopeAnchor confirmed = draft.anchorOf("exoskelette");
        assertEquals("the SAME post — never a second, contradictory one",
                "anchor-exoskelette", confirmed.getAnchorId());
        assertEquals(ScopeAnchor.Membership.IN, confirmed.getMembership());
        assertEquals("a membership change keeps the text (no re-embedding)",
                "Exoskelette", confirmed.getSemanticText());

        draft = apply(draft, ScopePatchOperations.excludeFacet("exoskelette", "doch nicht"));
        ScopeAnchor excluded = draft.anchorOf("exoskelette");
        assertEquals("anchor-exoskelette", excluded.getAnchorId());
        assertEquals(ScopeAnchor.Membership.OUT, excluded.getMembership());
        assertEquals("Exoskelette", excluded.getSemanticText());
        assertEquals("exactly one post for the facet", 1, draft.getAnchors().size());
    }

    @Test
    public void aRicherDeclaredTextSurvivesStatusChanges() {
        // A future turn gave the post a richer positive description (Z1 schema carries it).
        ResearchScopeDraft draft = apply(ResearchScopeDraft.empty(),
                ScopePatchOperations.addFacet("exo", "Exoskelette", ""));
        draft = draft.toBuilder().nextRevision()
                .putAnchor(draft.anchorOf("exo").withSemanticText(
                        "industrielle Exoskelette zur ergonomischen Entlastung"))
                .build();
        assertEquals("industrielle Exoskelette zur ergonomischen Entlastung",
                draft.anchorOf("exo").getSemanticText());

        draft = apply(draft, ScopePatchOperations.confirmFacet("exo", ""));
        assertEquals("the richer MEANING is kept; only the membership followed the facet",
                "industrielle Exoskelette zur ergonomischen Entlastung",
                draft.anchorOf("exo").getSemanticText());
        assertEquals(ScopeAnchor.Membership.IN, draft.anchorOf("exo").getMembership());
    }

    @Test
    public void rationaleAndEmphasisNeverTouchTheAnchor() {
        ResearchScopeDraft draft = apply(ResearchScopeDraft.empty(),
                ScopePatchOperations.addFacet("helme", "Sensorhelme", ""));
        ScopeAnchor before = draft.anchorOf("helme");

        draft = apply(draft,
                ScopePatchOperations.confirmFacet("helme", "der User will private Nutzung NICHT behandeln"),
                ScopePatchOperations.setFacetEmphasis("helme", CoverageEmphasis.Importance.HIGH,
                        CoverageEmphasis.ResearchDepth.DEEP, 40));

        ScopeAnchor after = draft.anchorOf("helme");
        assertEquals("decisions and weights are metadata — the semantic text never absorbs them",
                before.getSemanticText(), after.getSemanticText());
        assertEquals(ScopeAnchor.Membership.IN, after.getMembership());
    }

    @Test
    public void anAnchorWithoutItsFacetIsDropped() {
        ResearchScopeDraft draft = ResearchScopeDraft.builder()
                .putAnchor(new ScopeAnchor("anchor-ghost", "ghost", "verwaister Pfosten",
                        ScopeAnchor.Membership.IN))
                .build();
        assertNull(draft.anchorOf("ghost"));
        assertEquals(0, draft.getAnchors().size());
    }
}
