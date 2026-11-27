package com.aresstack.askai.research.domain;

/** One recorded orientation-research run: which brief revision it served and its user-approved start. */
public final class OrientationResearchRun {

    private final String runId;
    private final long briefRevision;
    private final Approval startApproval;

    public OrientationResearchRun(String runId, long briefRevision, Approval startApproval) {
        this.runId = runId == null ? "" : runId;
        this.briefRevision = briefRevision;
        this.startApproval = startApproval;
    }

    public String getRunId() {
        return runId;
    }

    public long getBriefRevision() {
        return briefRevision;
    }

    public Approval getStartApproval() {
        return startApproval;
    }
}
