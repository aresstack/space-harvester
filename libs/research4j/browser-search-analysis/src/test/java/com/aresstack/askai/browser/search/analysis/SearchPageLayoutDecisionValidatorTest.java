package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionDecision;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutValidationResult;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutValidationViolation.Kind;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A4c: strict, purely-structural validation. The hard invariant — an unknown container id is never
 * accepted — plus snapshot binding, duplicates, contradictions, the at-least-one-organic rule, count
 * limits, root/full-page rejection and the block-in-region rule.
 */
public class SearchPageLayoutDecisionValidatorTest {

    private final SearchPageLayoutDecisionValidator validator =
            new SearchPageLayoutDecisionValidator(LegacyBrowserSearchDefaults.create().extraction);

    // region container graph: root(no parent), col(parent=root), b1/b2(parent=col)
    private SearchPageAnalysisArtifact artifact() {
        return LayoutTestSupport.artifactOf("snap-1-test",
                LayoutTestSupport.candidate("container-root", ""),
                LayoutTestSupport.candidate("container-col", "container-root"),
                LayoutTestSupport.candidate("container-b1", "container-col"),
                LayoutTestSupport.candidate("container-b2", "container-col"));
    }

    private SearchPageLayoutResolutionDecision decision(String snapshotId, List<String> organic,
                                                        List<String> blocks, List<String> excluded,
                                                        double confidence) {
        return new SearchPageLayoutResolutionDecision("analysis-" + snapshotId + "-1", snapshotId,
                organic, blocks, excluded, confidence, "reason");
    }

    @Test
    public void acceptsAWellFormedDecisionAndProducesValidatedResult() {
        SearchPageAnalysisArtifact artifact = artifact();
        SearchPageLayoutValidationResult result = validator.validate(
                decision("snap-1-test", Arrays.asList("container-col"),
                        Arrays.asList("container-b1", "container-b2"),
                        Collections.<String>emptyList(), 0.9), artifact);
        assertTrue(result.messages().toString(), result.valid);

        ValidatedSearchPageLayoutDecision validated = validator.toValidatedDecision(
                decision("snap-1-test", Arrays.asList("container-col"),
                        Collections.<String>emptyList(), Collections.<String>emptyList(), 0.9),
                artifact);
        assertEquals("container-col", validated.primaryOrganicContainerId);
        assertEquals("snap-1-test", validated.snapshotId);
    }

    @Test
    public void unknownContainerIdIsNeverAccepted() {
        SearchPageLayoutValidationResult result = validator.validate(
                decision("snap-1-test", Arrays.asList("container-9999"),
                        Collections.<String>emptyList(), Collections.<String>emptyList(), 0.9),
                artifact());
        assertFalse(result.valid);
        assertTrue(result.hasUnknownContainerId());
        assertTrue(result.hasKind(Kind.UNKNOWN_CONTAINER_ID));
    }

    @Test
    public void analysisMismatchIsHardRejected() {
        SearchPageLayoutResolutionDecision d = new SearchPageLayoutResolutionDecision(
                "analysis-OTHER", "snap-1-test", Arrays.asList("container-col"),
                Collections.<String>emptyList(), Collections.<String>emptyList(), 0.9, "reason");
        SearchPageLayoutValidationResult result = validator.validate(d, artifact());
        assertFalse(result.valid);
        assertTrue(result.hasKind(Kind.ANALYSIS_MISMATCH));
    }

    @Test
    public void snapshotMismatchIsHardRejected() {
        SearchPageLayoutValidationResult result = validator.validate(
                decision("snap-OTHER", Arrays.asList("container-col"),
                        Collections.<String>emptyList(), Collections.<String>emptyList(), 0.9),
                artifact());
        assertFalse(result.valid);
        assertTrue(result.hasKind(Kind.UNKNOWN_SNAPSHOT));
    }

    @Test
    public void detectsDuplicatesContradictionsAndMissingOrganic() {
        SearchPageAnalysisArtifact artifact = artifact();
        assertTrue(validator.validate(decision("snap-1-test",
                Arrays.asList("container-col", "container-col"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), 0.9), artifact).hasKind(Kind.DUPLICATE_ID));
        assertTrue(validator.validate(decision("snap-1-test", Arrays.asList("container-col"),
                Collections.<String>emptyList(), Arrays.asList("container-col"), 0.9), artifact)
                .hasKind(Kind.CONTRADICTORY_CLASSIFICATION));
        assertTrue(validator.validate(decision("snap-1-test", Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), 0.9), artifact)
                .hasKind(Kind.NO_ORGANIC_CONTAINER));
    }

    @Test
    public void rejectsRootSelectionAndOutOfRangeConfidence() {
        SearchPageAnalysisArtifact artifact = artifact();
        assertTrue("a root/full-page container is never a result region",
                validator.validate(decision("snap-1-test", Arrays.asList("container-root"),
                        Collections.<String>emptyList(), Collections.<String>emptyList(), 0.9),
                        artifact).hasKind(Kind.FULL_PAGE_SELECTION));
        assertTrue(validator.validate(decision("snap-1-test", Arrays.asList("container-col"),
                Collections.<String>emptyList(), Collections.<String>emptyList(), 1.4), artifact)
                .hasKind(Kind.INVALID_CONFIDENCE));
    }

    /**
     * A named result block no longer owes a region relation. Which container a card sits in is
     * provenance the artifact already states; whether the card IS a result is decided where it can be
     * decided — on the card, by its primary link, in the extraction.
     */
    @Test
    public void acceptsAResultBlockThatIsNotADirectChildOfTheChosenRegion() {
        SearchPageAnalysisArtifact artifact = artifact();
        SearchPageLayoutValidationResult result = validator.validate(
                decision("snap-1-test", Arrays.asList("container-b1"),
                        Arrays.asList("container-col"), Collections.<String>emptyList(), 0.9),
                artifact);
        assertTrue(result.messages().toString(), result.valid);
    }

    /** Naming a card AND excluding it is a contradiction the model has to answer for. */
    @Test
    public void rejectsAResultBlockTheDecisionAlsoExcludes() {
        SearchPageAnalysisArtifact artifact = artifact();
        SearchPageLayoutValidationResult result = validator.validate(
                decision("snap-1-test", Arrays.asList("container-col"),
                        Arrays.asList("container-b1"), Arrays.asList("container-b1"), 0.9),
                artifact);
        assertTrue(result.hasKind(Kind.CONTRADICTORY_CLASSIFICATION));
    }

    /** Naming nothing at all is still no answer. */
    @Test
    public void rejectsADecisionThatNamesNeitherARegionNorABlock() {
        SearchPageAnalysisArtifact artifact = artifact();
        SearchPageLayoutValidationResult result = validator.validate(
                decision("snap-1-test", Collections.<String>emptyList(),
                        Collections.<String>emptyList(), Collections.<String>emptyList(), 0.9),
                artifact);
        assertTrue(result.hasKind(Kind.NO_ORGANIC_CONTAINER));
    }
}
