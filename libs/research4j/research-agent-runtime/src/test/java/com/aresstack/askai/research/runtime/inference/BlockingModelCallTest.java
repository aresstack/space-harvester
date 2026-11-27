package com.aresstack.askai.research.runtime.inference;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ModelCall;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.agent.model.port.resource.ResourceSnapshot;
import com.aresstack.askai.agent.model.port.resource.SlotRequest;
import com.aresstack.askai.agent.model.port.resource.SlotReservation;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A cancel must never be lost, also not in the window between the start and the registration of a call. */
public class BlockingModelCallTest {

    @Test
    public void aCancelBetweenStartAndRegistrationStillCancelsTheCall() {
        final AtomicReference<BlockingModelCall> blocking = new AtomicReference<BlockingModelCall>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        ModelPort port = new ModelPort() {
            public CompletableFuture<SlotReservation> reserve(SlotRequest request) {
                throw new UnsupportedOperationException();
            }

            public ModelCall chat(SlotReservation reservation, ChatRequest request, ChatListener listener) {
                throw new UnsupportedOperationException();
            }

            public ModelCall chat(ChatRequest request, final ChatListener listener) {
                // the call is running at the provider; the user presses Stop before run() registers it
                blocking.get().cancelAll();
                return new ModelCall() {
                    public void cancel() {
                        if (cancelled.compareAndSet(false, true)) {
                            listener.onFailed(new ModelFailure(ModelFailure.Kind.CANCELLED, "cancelled"));
                        }
                    }

                    public boolean isDone() {
                        return cancelled.get();
                    }
                };
            }

            public java.util.concurrent.CompletableFuture<Void> warm(com.aresstack.askai.agent.model.port.ModelSlot slot) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            public ResourceSnapshot resources() {
                throw new UnsupportedOperationException();
            }
        };
        blocking.set(new BlockingModelCall(port));

        BlockingModelCall.Outcome outcome = blocking.get().run(ChatRequest.builder(ResearchModelSlots.PHASE_SCOPING)
                .message(ModelMessage.user("hello")).build());

        assertTrue("the late-registered call was cancelled", cancelled.get());
        assertFalse(outcome.isOk());
        assertEquals(ModelFailure.Kind.CANCELLED, outcome.failure.getKind());
    }
}
