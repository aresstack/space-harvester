package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelCall;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
            port.enqueueMessage("must not be delivered");
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
            ModelCall call = port.chat(ChatRequest.builder(ResearchModelSlots.PHASE_SCOPING)
                    .message(ModelMessage.user("hello")).build(), new ChatListener() {
                public void onCompleted(ChatResult result) {
                    terminals.add("COMPLETED");
                }

                public void onFailed(ModelFailure failure) {
                    terminals.add(failure.getKind().name());
                }
            });
            call.cancel();

            assertTrue("the woken worker ran to its end before the cancel continued", workerExitedInTime[0]);
            assertTrue(call.isDone());
            assertEquals("exactly one terminal callback, and it is the cancel", 
                    java.util.Collections.singletonList(ModelFailure.Kind.CANCELLED.name()), terminals);
        } finally {
            port.close();
        }
    }
}
