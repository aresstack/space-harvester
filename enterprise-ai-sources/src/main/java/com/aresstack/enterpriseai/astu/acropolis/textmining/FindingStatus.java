package com.aresstack.enterpriseai.astu.acropolis.textmining;

/** How well a finding is backed. Scores of statistical models never raise a finding above {@link #CANDIDATE}. */
public enum FindingStatus {
    /** Found by its form only; its meaning is unknown. */
    CANDIDATE,
    /** The same document states it explicitly (e.g. a long form next to the acronym). */
    EVIDENCED
}
