package com.aresstack.askai.research.runtime.orchestrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The DETERMINISTIC measurements of a run (ADR 0001, 4.2: "gap detection and sufficiency are partly
 * deterministic evidence the orchestrator judges ON, not INSTEAD of"): coverage of each outline section by
 * accepted sources, open questions per section, and the run-budget sufficiency rule (at least N sources on at
 * least M hosts). The orchestrator reads it as input; the run closes only when {@link #isSufficient()} holds,
 * whatever the model's verdict says.
 */
public final class EvidenceSnapshot {

    private final List<SectionCoverage> sections;
    private final int distinctSources;
    private final int distinctHosts;
    private final int minimumSources;
    private final int minimumHosts;
    private final int minimumSourcesPerSection;
    private final int openQuestions;

    public EvidenceSnapshot(List<SectionCoverage> sections, int distinctSources, int distinctHosts,
                            int minimumSources, int minimumHosts, int minimumSourcesPerSection,
                            int openQuestions) {
        this.sections = sections == null ? Collections.<SectionCoverage>emptyList()
                : Collections.unmodifiableList(new ArrayList<SectionCoverage>(sections));
        this.distinctSources = distinctSources;
        this.distinctHosts = distinctHosts;
        this.minimumSources = minimumSources;
        this.minimumHosts = minimumHosts;
        this.minimumSourcesPerSection = minimumSourcesPerSection;
        this.openQuestions = openQuestions;
    }

    public List<SectionCoverage> getSections() {
        return sections;
    }

    public int getDistinctSources() {
        return distinctSources;
    }

    public int getDistinctHosts() {
        return distinctHosts;
    }

    public int getMinimumSources() {
        return minimumSources;
    }

    public int getMinimumHosts() {
        return minimumHosts;
    }

    public int getMinimumSourcesPerSection() {
        return minimumSourcesPerSection;
    }

    /** Tasks waiting for the user's answer. */
    public int getOpenQuestions() {
        return openQuestions;
    }

    /** The run-budget rule: enough sources on enough hosts. */
    public boolean meetsRunRule() {
        return distinctSources >= minimumSources && distinctHosts >= minimumHosts;
    }

    /** The sections below the per-section minimum, in outline order. */
    public List<SectionCoverage> uncoveredSections() {
        List<SectionCoverage> uncovered = new ArrayList<SectionCoverage>();
        for (SectionCoverage section : sections) {
            if (section.getSources() < minimumSourcesPerSection) {
                uncovered.add(section);
            }
        }
        return uncovered;
    }

    /** Run rule, every section covered, no question open. */
    public boolean isSufficient() {
        return meetsRunRule() && uncoveredSections().isEmpty() && openQuestions == 0;
    }

    /** Why the measurements are not sufficient, in one English line; empty when they are. */
    public String insufficiencyReason() {
        List<String> reasons = new ArrayList<String>();
        if (distinctSources < minimumSources) {
            reasons.add(distinctSources + " of " + minimumSources + " required sources");
        }
        if (distinctHosts < minimumHosts) {
            reasons.add(distinctHosts + " of " + minimumHosts + " required hosts");
        }
        List<SectionCoverage> uncovered = uncoveredSections();
        if (!uncovered.isEmpty()) {
            List<String> titles = new ArrayList<String>();
            for (SectionCoverage section : uncovered) {
                titles.add(section.getTitle());
            }
            reasons.add("sections without enough sources: " + String.join(", ", titles));
        }
        if (openQuestions > 0) {
            reasons.add(openQuestions + " open question(s) for the user");
        }
        return String.join("; ", reasons);
    }

    /** Coverage of one outline section. */
    public static final class SectionCoverage {
        private final String id;
        private final String title;
        private final int sources;
        private final int hosts;
        private final int openTasks;
        private final int failedTasks;

        public SectionCoverage(String id, String title, int sources, int hosts, int openTasks, int failedTasks) {
            this.id = id;
            this.title = title;
            this.sources = sources;
            this.hosts = hosts;
            this.openTasks = openTasks;
            this.failedTasks = failedTasks;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        /** Distinct accepted sources found by tasks serving this section. */
        public int getSources() {
            return sources;
        }

        public int getHosts() {
            return hosts;
        }

        /** Tasks for this section that have not finished. */
        public int getOpenTasks() {
            return openTasks;
        }

        /** Tasks for this section that failed, were canceled or rejected. */
        public int getFailedTasks() {
            return failedTasks;
        }
    }
}
