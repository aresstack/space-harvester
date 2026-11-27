package com.aresstack.askai.research.domain;

/** One explicit user approval: who, when (millis provided by the caller — the domain owns no clock), why. */
public final class Approval {

    private final String approvedBy;
    private final long approvedAtMillis;
    private final String note;

    public Approval(String approvedBy, long approvedAtMillis, String note) {
        this.approvedBy = approvedBy == null ? "" : approvedBy;
        this.approvedAtMillis = approvedAtMillis;
        this.note = note == null ? "" : note;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public long getApprovedAtMillis() {
        return approvedAtMillis;
    }

    public String getNote() {
        return note;
    }
}
