package com.aresstack.askai.research.domain;

/** An identified evidence gap of one section — the input of the detail-research feedback loop. */
public final class ResearchGap {

    private final String gapId;
    private final String sectionId;
    private final String description;
    private final Lifecycle status;

    public ResearchGap(String gapId, String sectionId, String description, Lifecycle status) {
        this.gapId = gapId == null ? "" : gapId;
        this.sectionId = sectionId == null ? "" : sectionId;
        this.description = description == null ? "" : description;
        this.status = status == null ? Lifecycle.PROPOSED : status;
    }

    public ResearchGap withStatus(Lifecycle newStatus) {
        return new ResearchGap(gapId, sectionId, description, newStatus);
    }

    public String getGapId() {
        return gapId;
    }

    public String getSectionId() {
        return sectionId;
    }

    public String getDescription() {
        return description;
    }

    public Lifecycle getStatus() {
        return status;
    }
}
