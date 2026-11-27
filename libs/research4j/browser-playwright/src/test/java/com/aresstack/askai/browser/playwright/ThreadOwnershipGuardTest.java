package com.aresstack.askai.browser.playwright;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ThreadOwnershipGuardTest {

    @Test
    public void ownerThreadPasses() {
        new ThreadOwnershipGuard().check(); // same thread — must not throw
    }

    @Test
    public void foreignThreadFailsWithBothThreadNames() throws Exception {
        final ThreadOwnershipGuard guard = new ThreadOwnershipGuard();
        final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    guard.check();
                } catch (Throwable ex) {
                    thrown.set(ex);
                }
            }
        }, "mcp-http-worker-7");
        worker.start();
        worker.join(5000);
        if (thrown.get() == null) {
            fail("a foreign thread must be rejected");
        }
        assertTrue(thrown.get() instanceof IllegalStateException);
        String message = thrown.get().getMessage();
        assertTrue("message must name the offending thread: " + message,
                message.contains("mcp-http-worker-7"));
        assertTrue("message must name the owner thread: " + message,
                message.contains(Thread.currentThread().getName()));
    }

    @Test
    public void ownerIsTheCreatingThread() throws Exception {
        final AtomicReference<ThreadOwnershipGuard> guard = new AtomicReference<ThreadOwnershipGuard>();
        final AtomicReference<Throwable> onCreator = new AtomicReference<Throwable>();
        Thread creator = new Thread(new Runnable() {
            public void run() {
                guard.set(new ThreadOwnershipGuard());
                try {
                    guard.get().check();
                } catch (Throwable ex) {
                    onCreator.set(ex);
                }
            }
        }, "creator");
        creator.start();
        creator.join(5000);
        assertNull(onCreator.get());
        try {
            guard.get().check();
            fail("the test thread is not the creator and must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("creator"));
        }
    }
}
