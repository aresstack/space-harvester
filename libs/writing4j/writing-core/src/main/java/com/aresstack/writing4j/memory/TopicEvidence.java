package com.aresstack.writing4j.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Component 7: what the memory holds for one topic, over all its questions: the counts the outline (component 9)
 * weighs a topic by. The bare number of passages is no weight, so it is not offered. Immutable.
 */
public final class TopicEvidence {

    private final ResearchTopic topic;
    private final List<String> questionIds;
    private final int directCount;
    private final int leadCount;
    private final int independentSources;
    private final int distinctDomains;
    private final int findings;

    TopicEvidence(ResearchTopic topic, List<String> questionIds, int directCount, int leadCount,
                  int independentSources, int distinctDomains, int findings) {
        this.topic = topic;
        this.questionIds = Collections.unmodifiableList(new ArrayList<String>(questionIds));
        this.directCount = directCount;
        this.leadCount = leadCount;
        this.independentSources = independentSources;
        this.distinctDomains = distinctDomains;
        this.findings = findings;
    }

    public ResearchTopic getTopic() {
        return topic;
    }

    /** The questions of the topic and of the topics merged into it, merged-away questions left out. */
    public List<String> getQuestionIds() {
        return questionIds;
    }

    /** Distinct texts of direct evidence over all questions of the topic. */
    public int getDirectCount() {
        return directCount;
    }

    /** Distinct texts of leads over all questions of the topic that are not direct evidence. */
    public int getLeadCount() {
        return leadCount;
    }

    /** Independent sources among the direct evidence. */
    public int getIndependentSources() {
        return independentSources;
    }

    /** Distinct domains among the direct evidence. */
    public int getDistinctDomains() {
        return distinctDomains;
    }

    /** Places the topic was found in (Fundstellen), the merged topics' included. */
    public int getFindings() {
        return findings;
    }
}
