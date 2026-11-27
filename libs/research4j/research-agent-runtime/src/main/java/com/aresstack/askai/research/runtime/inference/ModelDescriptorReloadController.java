package com.aresstack.askai.research.runtime.inference;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The hot-reload state machine for the central model descriptors, kept separate from the filesystem
 * {@link ModelDescriptorWatcher} so the switch policy is unit-testable without real watch timing.
 *
 * <p>A watch event only SIGNALS a pending change ({@link #signalChange()}); the actual re-read + rebuild is
 * applied through {@link #poll(boolean)} and ONLY when idle (between two agent turns), never mid-request or
 * mid-tool-call. On a re-read failure the last good configuration is retained. The reload itself (re-read the
 * whole descriptor file, validate, rebuild the client bundle) is the injected {@link Reload} callback — the
 * first KISS behaviour simply rebuilds the affected client.</p>
 */
public final class ModelDescriptorReloadController {

    /** Re-read the whole descriptor, validate, and rebuild the client bundle. */
    public interface Reload {
        /** @return true when a newer valid configuration was applied; false to keep the last good one. */
        boolean reloadNow();
    }

    private final Reload reload;
    private final AtomicBoolean pending = new AtomicBoolean(false);

    public ModelDescriptorReloadController(Reload reload) {
        if (reload == null) {
            throw new IllegalArgumentException("reload must not be null");
        }
        this.reload = reload;
    }

    /** Flag that a watched descriptor changed. Safe to call from the watch thread. */
    public void signalChange() {
        pending.set(true);
    }

    public boolean hasPendingChange() {
        return pending.get();
    }

    /**
     * Apply a pending reload.
     *
     * @param idle whether the agent is at a safe point (between turns). When a change is pending but the
     *             agent is NOT idle the switch is deferred ({@link ModelReloadOutcome#RELOAD_PENDING_UNTIL_IDLE}).
     * @return the outcome, or {@code null} when there was no pending change
     */
    public ModelReloadOutcome poll(boolean idle) {
        if (!pending.get()) {
            return null;
        }
        if (!idle) {
            return ModelReloadOutcome.RELOAD_PENDING_UNTIL_IDLE;
        }
        // Clear BEFORE reloading so a change arriving during the reload is not lost (it re-signals).
        pending.set(false);
        return reload.reloadNow()
                ? ModelReloadOutcome.RELOADED
                : ModelReloadOutcome.RELOAD_FAILED_LAST_GOOD_RETAINED;
    }
}
