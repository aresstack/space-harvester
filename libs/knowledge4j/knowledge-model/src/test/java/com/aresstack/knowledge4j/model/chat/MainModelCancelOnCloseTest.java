package com.aresstack.knowledge4j.model.chat;

import com.aresstack.knowledge4j.model.testing.FakeModelPort;
import com.aresstack.knowledge4j.model.TestSlots;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            port.enqueueRaw("this answer must never be waited for");

            // The call's timeout is 120s; the abort must be far faster than that.
            final PortMainModelChat client =
                    new PortMainModelChat(port, TestSlots.CONVERSATION, Duration.ofSeconds(120));

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

            assertFalse(caller.isAlive(), "the blocked call returned after the abort");
            assertTrue(elapsed < 3000, "aborted well within the 120s model timeout (was " + elapsed + "ms)");
            assertNotNull(result.get());
            assertFalse(result.get().isOk(), "an aborted call is an honest non-OK result, never a fabricated answer");
            assertTrue(result.get().getDetail().contains("CANCELLED"));
        } finally {
            port.close();
        }
    }
}
