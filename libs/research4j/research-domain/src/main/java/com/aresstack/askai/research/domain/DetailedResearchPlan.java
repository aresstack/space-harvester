package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/**
 * The plan of one detail-research round for one section: which gaps it addresses and which search queries
 * are proposed. The actual web search stays behind the existing SearchStrategy — this object is the
 * FACHLICHE order, not the execution.
 */
public final class DetailedResearchPlan {

    public static final class SearchQueryProposal {
        private final String query;
        private final String rationale;

        public SearchQueryProposal(String query, String rationale) {
            this.query = query == null ? "" : query;
            this.rationale = rationale == null ? "" : rationale;
        }

        public String getQuery() {
            return query;
        }

        public String getRationale() {
            return rationale;
        }
    }

    private final String planId;
    private final String sectionId;
    private final List<String> gapIds;
    private final List<SearchQueryProposal> queries;

    public DetailedResearchPlan(String planId, String sectionId, List<String> gapIds,
                                List<SearchQueryProposal> queries) {
        this.planId = planId == null ? "" : planId;
        this.sectionId = sectionId == null ? "" : sectionId;
        this.gapIds = gapIds == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(gapIds));
        this.queries = queries == null ? Collections.<SearchQueryProposal>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<SearchQueryProposal>(queries));
    }

    public String getPlanId() {
        return planId;
    }

    public String getSectionId() {
        return sectionId;
    }

    public List<String> getGapIds() {
        return gapIds;
    }

    public List<SearchQueryProposal> getQueries() {
        return queries;
    }
}
