package com.aresstack.askai.research.runtime.inference;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The hot-reload state machine: signal → deferred-until-idle → applied-or-kept-last-good, all outcomes. */
public class ModelDescriptorReloadControllerTest {

    @Test
    public void noPendingChangePollsToNull() {
        ModelDescriptorReloadController controller =
                new ModelDescriptorReloadController(alwaysReload(new AtomicInteger()));
        assertNull(controller.poll(true));
    }

    @Test
    public void aPendingChangeIsDeferredWhileNotIdleAndDoesNotReload() {
        AtomicInteger reloads = new AtomicInteger();
        ModelDescriptorReloadController controller =
                new ModelDescriptorReloadController(alwaysReload(reloads));
        controller.signalChange();
        assertEquals(ModelReloadOutcome.RELOAD_PENDING_UNTIL_IDLE, controller.poll(false));
        assertTrue("still pending — never switched mid-turn", controller.hasPendingChange());
        assertEquals("reload was NOT invoked while not idle", 0, reloads.get());
    }

    @Test
    public void aPendingChangeAppliedWhenIdleReloadsExactlyOnceAndClears() {
        AtomicInteger reloads = new AtomicInteger();
        ModelDescriptorReloadController controller =
                new ModelDescriptorReloadController(alwaysReload(reloads));
        controller.signalChange();
        assertEquals(ModelReloadOutcome.RELOADED, controller.poll(true));
        assertEquals(1, reloads.get());
        assertFalse(controller.hasPendingChange());
        assertNull("no further pending change after applying", controller.poll(true));
    }

    @Test
    public void aFailedReloadKeepsTheLastGoodConfigAndClearsPending() {
        ModelDescriptorReloadController controller =
                new ModelDescriptorReloadController(new ModelDescriptorReloadController.Reload() {
                    public boolean reloadNow() {
                        return false; // descriptor missing/invalid → keep last good
                    }
                });
        controller.signalChange();
        assertEquals(ModelReloadOutcome.RELOAD_FAILED_LAST_GOOD_RETAINED, controller.poll(true));
        assertFalse(controller.hasPendingChange());
    }

    private static ModelDescriptorReloadController.Reload alwaysReload(final AtomicInteger counter) {
        return new ModelDescriptorReloadController.Reload() {
            public boolean reloadNow() {
                counter.incrementAndGet();
                return true;
            }
        };
    }
}
