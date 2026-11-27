package com.aresstack.knowledge4j.policy;

/** Where a target sits, as the host classifies it. Unknown targets count as {@link #EXTERNAL}. */
public enum Exposure {
    /** Inside the organisation (intranet service, local model, the host's own index). */
    INTERNAL,
    /** Outside the organisation (internet search, cloud model, public web page). */
    EXTERNAL
}
