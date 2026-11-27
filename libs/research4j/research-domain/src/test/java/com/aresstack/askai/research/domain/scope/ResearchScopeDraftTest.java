package com.aresstack.askai.research.domain.scope;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The scope draft is the fenced-in INVESTIGATION AREA, not a research question: it must be able to hold
 * several directions at once, to refine a decision without forgetting the earlier one, and to keep
 * importance and research depth apart.
 */
public class ResearchScopeDraftTest {

    @Test
    public void aFacetIsRefinedRatherThanDuplicatedOrForgotten() {
        // The live scenario: AR is interesting at first and a side note three turns later.
        ResearchScopeDraft first = ResearchScopeDraft.builder()
                .putFacet(new ScopeFacet("ar", "AR-Brillen", ScopeFacet.Status.PROVISIONAL,
                        "klingt spannend"))
                .build();

        ResearchScopeDraft later = first.toBuilder()
                .putFacet(new ScopeFacet("ar", "AR-Brillen", ScopeFacet.Status.CONFIRMED, ""))
                .putCoverageEmphasis(new CoverageEmphasis("ar", CoverageEmphasis.Importance.LOW,
                        CoverageEmphasis.ResearchDepth.OVERVIEW))
                .nextRevision()
                .build();

        assertEquals("no duplicate for the same aspect", 1, later.getFacets().size());
        assertEquals(ScopeFacet.Status.CONFIRMED, later.facet("ar").getStatus());
        assertEquals("the original reasoning is not thrown away by an update",
                "klingt spannend", later.facet("ar").getRationale());
        assertEquals(CoverageEmphasis.Importance.LOW, later.emphasisOf("ar").getImportance());
        assertEquals(1L, later.getRevision());
        assertEquals("the earlier revision is untouched", 0L, first.getRevision());
        assertNull("the earlier revision knows no emphasis yet", first.emphasisOf("ar"));
    }

    @Test
    public void anExcludedFacetStaysOnRecordInsteadOfBeingDeleted() {
        ResearchScopeDraft draft = ResearchScopeDraft.builder()
                .putFacet(new ScopeFacet("buying", "Kaufberatung", ScopeFacet.Status.EXCLUDED,
                        "will keine Produktempfehlungen"))
                .putFacet(new ScopeFacet("validation", "Validierungsstudien",
                        ScopeFacet.Status.CONFIRMED, ""))
                .build();

        assertEquals("both are kept", 2, draft.getFacets().size());
        assertEquals("only one is in scope", 1, draft.includedFacets().size());
        assertEquals("validation", draft.includedFacets().get(0).getFacetId());
        assertTrue(draft.facet("buying").isExcluded());
        // The exclusions UI projects exactly these — a persisted excludeFacet must be visible.
        assertEquals(1, draft.excludedFacets().size());
        assertEquals("buying", draft.excludedFacets().get(0).getFacetId());
    }

    /**
     * Live-gate 3: excludeFacet/confirmFacet of a NEW facet used to persist the raw id as the
     * visible label ("esp-idf" chips in the sky). The emergency label at least reads like words;
     * an existing facet's real label still wins through putFacet's refine semantics.
     */
    @Test
    public void aFacetCreatedWithoutALabelGetsAHumanizedIdInsteadOfTheRawId() {
        ResearchScopeDraft.Builder builder = ResearchScopeDraft.builder();
        ScopePatchOperations.excludeFacet("esp-idf", "explicit user exclusion").applyTo(builder);
        ResearchScopeDraft draft = builder.build();
        assertEquals("Esp Idf", draft.facet("esp-idf").getLabel());
        assertTrue(draft.facet("esp-idf").isExcluded());

        ResearchScopeDraft.Builder refine = draft.toBuilder();
        ScopePatchOperations.addFacet("esp-idf", "ESP-IDF", "").applyTo(refine);
        assertEquals("a real label replaces the emergency one",
                "ESP-IDF", refine.build().facet("esp-idf").getLabel());
    }

    @Test
    public void importanceAndResearchDepthAreIndependentDimensions() {
        // "Wichtig" and "bitte sehr tief recherchieren" are not the same statement: a marginal aspect can
        // still need deep digging, and a central one may only need an overview.
        CoverageEmphasis marginalButDeep = new CoverageEmphasis("sensors",
                CoverageEmphasis.Importance.LOW, CoverageEmphasis.ResearchDepth.EXHAUSTIVE);
        CoverageEmphasis centralButShallow = new CoverageEmphasis("market",
                CoverageEmphasis.Importance.HIGH, CoverageEmphasis.ResearchDepth.OVERVIEW);

        assertEquals(CoverageEmphasis.Importance.LOW, marginalButDeep.getImportance());
        assertEquals(CoverageEmphasis.ResearchDepth.EXHAUSTIVE, marginalButDeep.getResearchDepth());
        assertEquals(CoverageEmphasis.Importance.HIGH, centralButShallow.getImportance());
        assertEquals(CoverageEmphasis.ResearchDepth.OVERVIEW, centralButShallow.getResearchDepth());
        assertFalse("a share hint is optional and NOT a chapter weighting",
                marginalButDeep.hasShareHint());
    }

    @Test
    public void theDeliverableRecordsSizeAndSynthesisContractWithoutAnyOutline() {
        ResearchScopeDraft draft = ResearchScopeDraft.builder()
                .mission("Was können Wearables wirklich messen?")
                .deliverable(new ResearchDeliverable(20, 30, ResearchDeliverable.LengthUnit.PAGES,
                        new SynthesisPolicy(true, true,
                                SynthesisPolicy.RepetitiveEntityPolicy.GROUP,
                                SynthesisPolicy.ExamplePolicy.REPRESENTATIVE)))
                .build();

        ResearchDeliverable deliverable = draft.getDeliverable();
        assertTrue(deliverable.hasTargetLength());
        assertEquals(20, deliverable.getTargetLengthMin());
        assertEquals(30, deliverable.getTargetLengthMax());
        assertTrue("500 similar products are a category, not 500 units of reporting",
                deliverable.getSynthesisPolicy().isCategoryFirst());
        assertEquals(SynthesisPolicy.RepetitiveEntityPolicy.GROUP,
                deliverable.getSynthesisPolicy().getRepetitiveEntityPolicy());
    }

    @Test
    public void aLooselyStatedRangeIsUnderstoodAndAnAbsentSizeStaysUnspecified() {
        ResearchDeliverable reversed = new ResearchDeliverable(30, 20,
                ResearchDeliverable.LengthUnit.PAGES, null);
        assertEquals(20, reversed.getTargetLengthMin());
        assertEquals(30, reversed.getTargetLengthMax());
        assertTrue("the standard synthesis contract applies even without an explicit policy",
                reversed.getSynthesisPolicy().isContrastRequired());

        ResearchDeliverable none = ResearchDeliverable.unspecified();
        assertFalse(none.hasTargetLength());
        assertEquals(ResearchDeliverable.LengthUnit.UNSPECIFIED, none.getLengthUnit());
        assertEquals(ResearchDeliverable.NO_LENGTH, none.getTargetLengthMin());
    }

    @Test
    public void theDraftHoldsSeveralOpenDirectionsAtOnceAndCarriesOpenQuestionsForward() {
        ResearchScopeDraft draft = ResearchScopeDraft.builder()
                .mission("Grenzen der Messgenauigkeit von Wearables")
                .domains(Arrays.asList("Wearables", "Medizintechnik"))
                .contexts(Arrays.asList("klinische Validierung", "Alltagsnutzung"))
                .putFacet(new ScopeFacet("rings", "Smart Rings", ScopeFacet.Status.CONFIRMED, ""))
                .putFacet(new ScopeFacet("earbuds", "Earbuds", ScopeFacet.Status.CONFIRMED, ""))
                .putFacet(new ScopeFacet("watches", "Smartwatches", ScopeFacet.Status.CONFIRMED, ""))
                .putCrossCuttingEmphasis(new CrossCuttingEmphasis("Regulierung EU/USA",
                        CoverageEmphasis.Importance.HIGH))
                .addExclusion("Kaufberatung")
                .addUnresolvedIssue("Reichen Herstellerangaben als Quelle?")
                .addTerminology("HRV")
                .build();

        assertEquals("scoping keeps directions OPEN instead of narrowing to one", 3,
                draft.includedFacets().size());
        assertEquals(1, draft.getCrossCuttingEmphasis().size());
        assertEquals("Kaufberatung", draft.getExclusions().get(0));
        assertEquals("an open point is carried, not guessed away", 1,
                draft.getUnresolvedIssues().size());
        assertFalse(draft.isEmpty());
        assertTrue(ResearchScopeDraft.empty().isEmpty());
    }

    @Test
    public void repeatedStatementsAboutTheSameEmphasisOrDimensionReplaceRatherThanAccumulate() {
        ResearchScopeDraft draft = ResearchScopeDraft.builder()
                .putCoverageEmphasis(new CoverageEmphasis("health",
                        CoverageEmphasis.Importance.MEDIUM, CoverageEmphasis.ResearchDepth.STANDARD))
                .putCoverageEmphasis(new CoverageEmphasis("health",
                        CoverageEmphasis.Importance.HIGH, CoverageEmphasis.ResearchDepth.EXHAUSTIVE))
                .putCrossCuttingEmphasis(new CrossCuttingEmphasis("Datenschutz",
                        CoverageEmphasis.Importance.HIGH))
                .putCrossCuttingEmphasis(new CrossCuttingEmphasis("Datenschutz",
                        CoverageEmphasis.Importance.LOW))
                .addExclusion("Kaufberatung")
                .addExclusion("Kaufberatung")
                .build();

        assertEquals(1, draft.getCoverageEmphasis().size());
        assertEquals(CoverageEmphasis.Importance.HIGH, draft.emphasisOf("health").getImportance());
        assertEquals(1, draft.getCrossCuttingEmphasis().size());
        assertEquals(CoverageEmphasis.Importance.LOW,
                draft.getCrossCuttingEmphasis().get(0).getImportance());
        assertEquals(1, draft.getExclusions().size());
    }
}
