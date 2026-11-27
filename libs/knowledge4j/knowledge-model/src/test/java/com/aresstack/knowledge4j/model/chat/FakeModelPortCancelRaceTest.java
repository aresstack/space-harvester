package com.aresstack.knowledge4j.model.chat;

import com.aresstack.knowledge4j.model.testing.FakeModelPort;
import com.aresstack.knowledge4j.model.ChatListener;
import com.aresstack.knowledge4j.model.ChatRequest;
import com.aresstack.knowledge4j.model.ChatResult;
import com.aresstack.knowledge4j.model.ModelCall;
import com.aresstack.knowledge4j.model.ModelFailure;
import com.aresstack.knowledge4j.model.ModelMessage;
import com.aresstack.knowledge4j.model.TestSlots;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the cancel race in {@link FakeModelPort}: waking the blocked worker before the cancel
 * claimed the terminal state let the worker report {@code onCompleted} for a cancelled call. The hook lets
 * the worker run to its end before the cancel continues, so the old ordering fails every time.
 */
public class FakeModelPortCancelRaceTest {

    @Test
    public void cancelWinsEvenWhenTheWokenWorkerRunsFirst() throws Exception {
        FakeModelPort port = new FakeModelPort();
        try {
            final CountDownLatch gate = new CountDownLatch(1);
            port.blockResponses(gate);
            port.enqueueRaw("must not be delivered");
            final boolean[] workerExitedInTime = new boolean[1];
            port.cancelSignalHook = new FakeModelPort.CancelSignalHook() {
                public void afterWorkerWoken(CountDownLatch workerExited) {
                    try {
                        workerExitedInTime[0] = workerExited.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };

            final List<String> terminals = new CopyOnWriteArrayList<String>();
            ModelCall call = port.chat(ChatRequest.builder(TestSlots.CONVERSATION)
                    .message(ModelMessage.user("hello")).build(), new ChatListener() {
                public void onCompleted(ChatResult result) {
                    terminals.add("COMPLETED");
                }

                public void onFailed(ModelFailure failure) {
                    terminals.add(failure.getKind().name());
                }
            });
            call.cancel();

            assertTrue(workerExitedInTime[0], "the woken worker ran to its end before the cancel continued");
            assertTrue(call.isDone());
            assertEquals(java.util.Collections.singletonList(ModelFailure.Kind.CANCELLED.name()), terminals, "exactly one terminal callback, and it is the cancel");
        } finally {
            port.close();
        }
    }
}
