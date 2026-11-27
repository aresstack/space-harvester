package com.aresstack.askai.research.domain.scope;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertTrue;

/**
 * The fence view is what the assistant reads INSTEAD of reconstructing the scope from the chat history.
 * It therefore has to carry every decision that must not be re-litigated — including the stable ids the
 * assistant needs to refer to, and the things that were ruled out.
 */
public class ResearchScopeFenceViewTest {

    private static ResearchScopeDraft draft() {
        return ResearchScopeDraft.builder()
                .revision(7)
                .mission("Wearables im Baugewerbe")
                .putFacet(new ScopeFacet("worker-safety", "Arbeitssicherheit",
                        ScopeFacet.Status.CONFIRMED, ""))
                .putFacet(new ScopeFacet("occupational-health", "Gesundheit",
                        ScopeFacet.Status.PROVISIONAL, ""))
                .putFacet(new ScopeFacet("buying", "Kaufberatung", ScopeFacet.Status.EXCLUDED,
                        "will keine Produktempfehlungen"))
                .putCoverageEmphasis(new CoverageEmphasis("occupational-health",
                        CoverageEmphasis.Importance.HIGH, CoverageEmphasis.ResearchDepth.DEEP))
                .putCrossCuttingEmphasis(new CrossCuttingEmphasis("Regulierung",
                        CoverageEmphasis.Importance.HIGH))
                .deliverable(new ResearchDeliverable(20, 30, ResearchDeliverable.LengthUnit.PAGES,
                        SynthesisPolicy.defaults()))
                .addUnresolvedIssue("Welche Zielgruppen im Baugewerbe?")
                .terminology(Arrays.asList("PSA"))
                .build();
    }

    @Test
    public void itCarriesTheRevisionMissionFacetsAndTheirStableIds() {
        String view = ResearchScopeFenceView.render(draft());

        assertTrue(view, view.contains("CURRENT RESEARCH SCOPE — revision 7"));
        assertTrue(view, view.contains("Wearables im Baugewerbe"));
        assertTrue("the assistant must be able to REFER to a facet",
                view.contains("id=worker-safety"));
        assertTrue(view, view.contains("Arbeitssicherheit"));
        assertTrue(view, view.contains("CONFIRMED"));
        assertTrue(view, view.contains("PROVISIONAL"));
    }

    @Test
    public void ruledOutAspectsAreShownSoTheyAreNotOfferedAgain() {
        String view = ResearchScopeFenceView.render(draft());

        assertTrue(view, view.contains("RULED OUT"));
        assertTrue(view, view.contains("Kaufberatung"));
        assertTrue(view, view.contains("will keine Produktempfehlungen"));
    }

    @Test
    public void emphasisDeliverableAndOpenIssuesAreVisible() {
        String view = ResearchScopeFenceView.render(draft());

        assertTrue(view, view.contains("HIGH, DEEP"));
        assertTrue(view, view.contains("Regulierung"));
        assertTrue(view, view.contains("20-30 pages"));
        assertTrue(view, view.contains("category-first"));
        assertTrue(view, view.contains("contrast required"));
        assertTrue(view, view.contains("OPEN ISSUES"));
        assertTrue(view, view.contains("Welche Zielgruppen im Baugewerbe?"));
        assertTrue("open issues must not read like blockers",
                view.contains("they do NOT block anything"));
        assertTrue(view, view.contains("PSA"));
    }

    /** Facets without a mission: the gap is SAID, not silently omitted — the runtime keys on it. */
    @Test
    public void aMissingMissionIsStatedExplicitly() {
        ResearchScopeDraft missionless = ResearchScopeDraft.builder()
                .putFacet(new ScopeFacet("rtos-grundlagen", "RTOS-Grundlagen",
                        ScopeFacet.Status.PROVISIONAL, ""))
                .build();
        String view = ResearchScopeFenceView.render(missionless);
        assertTrue(view, view.contains("(none yet — record the user's goal with setMission)"));
    }

    @Test
    public void anEmptyScopeSaysSoInsteadOfRenderingEmptyHeadings() {
        String view = ResearchScopeFenceView.render(ResearchScopeDraft.empty());

        assertTrue(view, view.contains("revision 0"));
        assertTrue(view, view.contains("nothing has been scoped yet"));
        assertTrue("no misleading empty sections", !view.contains("IN SCOPE"));
    }
}
