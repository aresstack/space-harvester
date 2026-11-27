package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/**
 * The concept paper: produced by the AGENT after orientation research, editable by the user and
 * approval-gated. It is PROJECT context for planner/analyzer/drafting — never part of the static system
 * prompt. Revisions supersede, they never overwrite.
 */
public final class ConceptPaper {

    private final String conceptId;
    private final long revision;
    private final String startingSituation;
    private final String goal;
    private final List<String> keyQuestions;
    private final List<String> recognizedTopicIds;
    private final String argumentationLine;
    private final List<String> evidenceRequirements;
    private final List<String> openQuestions;
    private final List<String> knownLimitations;
    private final Lifecycle status;

    public ConceptPaper(String conceptId, long revision, String startingSituation, String goal,
                        List<String> keyQuestions, List<String> recognizedTopicIds,
                        String argumentationLine, List<String> evidenceRequirements,
                        List<String> openQuestions, List<String> knownLimitations, Lifecycle status) {
        this.conceptId = conceptId == null ? "" : conceptId;
        this.revision = revision;
        this.startingSituation = startingSituation == null ? "" : startingSituation;
        this.goal = goal == null ? "" : goal;
        this.keyQuestions = copy(keyQuestions);
        this.recognizedTopicIds = copy(recognizedTopicIds);
        this.argumentationLine = argumentationLine == null ? "" : argumentationLine;
        this.evidenceRequirements = copy(evidenceRequirements);
        this.openQuestions = copy(openQuestions);
        this.knownLimitations = copy(knownLimitations);
        this.status = status == null ? Lifecycle.PROPOSED : status;
    }

    private static List<String> copy(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(values));
    }

    public ConceptPaper withStatus(Lifecycle newStatus) {
        return new ConceptPaper(conceptId, revision, startingSituation, goal, keyQuestions,
                recognizedTopicIds, argumentationLine, evidenceRequirements, openQuestions,
                knownLimitations, newStatus);
    }

    public String getConceptId() {
        return conceptId;
    }

    public long getRevision() {
        return revision;
    }

    public String getStartingSituation() {
        return startingSituation;
    }

    public String getGoal() {
        return goal;
    }

    public List<String> getKeyQuestions() {
        return keyQuestions;
    }

    public List<String> getRecognizedTopicIds() {
        return recognizedTopicIds;
    }

    public String getArgumentationLine() {
        return argumentationLine;
    }

    public List<String> getEvidenceRequirements() {
        return evidenceRequirements;
    }

    public List<String> getOpenQuestions() {
        return openQuestions;
    }

    public List<String> getKnownLimitations() {
        return knownLimitations;
    }

    public Lifecycle getStatus() {
        return status;
    }
}
