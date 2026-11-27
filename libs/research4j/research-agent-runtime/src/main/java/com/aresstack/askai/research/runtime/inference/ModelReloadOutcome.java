package com.aresstack.askai.research.runtime.inference;

/**
 * The visible result of a central-model descriptor hot-reload attempt.
 *
 * <ul>
 *   <li>{@link #RELOADED} — a newer, valid descriptor was applied (the client bundle was rebuilt).</li>
 *   <li>{@link #RELOAD_PENDING_UNTIL_IDLE} — a change was detected but a turn/tool-call is in progress, so
 *       the switch is deferred to the next turn boundary (never mid-request).</li>
 *   <li>{@link #RELOAD_FAILED_LAST_GOOD_RETAINED} — the changed descriptor was missing/invalid; the last
 *       good configuration stays active.</li>
 * </ul>
 */
public enum ModelReloadOutcome {
    RELOADED,
    RELOAD_PENDING_UNTIL_IDLE,
    RELOAD_FAILED_LAST_GOOD_RETAINED
}
