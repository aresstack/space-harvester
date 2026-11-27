package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Cancel-on-close for the main-model transport: a blocked model call aborts promptly when the client is
 * cancelled (a session/tab close, or a pause/cancel) instead of waiting out the full model timeout, and
 * surfaces an honest non-OK result — never a fabricated answer. The host then drops any late delivery via
 * its {@code disposed} guard.
 */
public class MainModelCancelOnCloseTest {

    @Test
    public void aBlockedModelCallAbortsWellWithinTheTimeoutWhenCancelled() throws Exception {
        FakeModelPort port = new FakeModelPort();
        try {
            CountDownLatch gate = new CountDownLatch(1);
            port.blockResponses(gate); // the port holds the answer back (a slow/blocking model)
            port.enqueueMessage("this answer must never be waited for");

            // The call's timeout is 120s; the abort must be far faster than that. Cancelled through the
            // reloadable wrapper the session holds, as a close does.
            final ReloadableMainModelChat client = new ReloadableMainModelChat(
                    new PortMainModelChat(port, ResearchModelSlots.PHASE_SCOPING, Duration.ofSeconds(120)));

            final List<ChatMessage> messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("s"));
            messages.add(ChatMessage.user("hello"));
            final AtomicReference<MainModelChatResult> result = new AtomicReference<MainModelChatResult>();
            Thread caller = new Thread(new Runnable() {
                public void run() {
                    result.set(client.complete(messages, 0.4, 256));
                }
            }, "model-call");
            caller.start();
            Thread.sleep(400); // let the call reach and block on the port

            long start = System.currentTimeMillis();
            client.cancelInFlight(); // a close/cancel aborts the in-flight request
            caller.join(5000);
            long elapsed = System.currentTimeMillis() - start;

            assertFalse("the blocked call returned after the abort", caller.isAlive());
            assertTrue("aborted well within the 120s model timeout (was " + elapsed + "ms)", elapsed < 3000);
            assertNotNull(result.get());
            assertFalse("an aborted call is an honest non-OK result, never a fabricated answer",
                    result.get().isOk());
            assertTrue(result.get().getDetail().contains("CANCELLED"));
        } finally {
            port.close();
        }
    }
}
