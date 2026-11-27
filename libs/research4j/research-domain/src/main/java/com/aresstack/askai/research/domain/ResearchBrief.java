package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/**
 * The user-confirmed RESEARCH BRIEF (pre-search): what to find out, for whom, within which frame. This is
 * the research PLAN — deliberately not the document outline, which can only exist after orientation
 * research. A change produces a new revision; older revisions stay (no delete).
 */
public final class ResearchBrief {

    private final String briefId;
    private final long revision;
    private final String researchQuestion;
    private final String goal;
    private final String audience;
    private final List<String> scope;
    private final List<String> outOfScope;
    private final String expectedResult;
    private final List<String> qualityRequirements;
    private final List<String> sourceGuidelines;
    private final List<String> initialQuestions;

    public ResearchBrief(String briefId, long revision, String researchQuestion, String goal,
                         String audience, List<String> scope, List<String> outOfScope,
                         String expectedResult, List<String> qualityRequirements,
                         List<String> sourceGuidelines, List<String> initialQuestions) {
        this.briefId = safe(briefId);
        this.revision = revision;
        this.researchQuestion = safe(researchQuestion);
        this.goal = safe(goal);
        this.audience = safe(audience);
        this.scope = copy(scope);
        this.outOfScope = copy(outOfScope);
        this.expectedResult = safe(expectedResult);
        this.qualityRequirements = copy(qualityRequirements);
        this.sourceGuidelines = copy(sourceGuidelines);
        this.initialQuestions = copy(initialQuestions);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static List<String> copy(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(values));
    }

    public String getBriefId() {
        return briefId;
    }

    public long getRevision() {
        return revision;
    }

    public String getResearchQuestion() {
        return researchQuestion;
    }

    public String getGoal() {
        return goal;
    }

    public String getAudience() {
        return audience;
    }

    public List<String> getScope() {
        return scope;
    }

    public List<String> getOutOfScope() {
        return outOfScope;
    }

    public String getExpectedResult() {
        return expectedResult;
    }

    public List<String> getQualityRequirements() {
        return qualityRequirements;
    }

    public List<String> getSourceGuidelines() {
        return sourceGuidelines;
    }

    public List<String> getInitialQuestions() {
        return initialQuestions;
    }
}
