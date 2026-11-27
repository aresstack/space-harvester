package com.aresstack.knowledge4j.model.chat;

import com.aresstack.knowledge4j.model.TestSlots;
import com.aresstack.knowledge4j.model.ChatListener;
import com.aresstack.knowledge4j.model.ChatRequest;
import com.aresstack.knowledge4j.model.ModelCall;
import com.aresstack.knowledge4j.model.ModelFailure;
import com.aresstack.knowledge4j.model.ModelMessage;
import com.aresstack.knowledge4j.model.ModelPort;
import com.aresstack.knowledge4j.model.resource.ResourceSnapshot;
import com.aresstack.knowledge4j.model.resource.SlotRequest;
import com.aresstack.knowledge4j.model.resource.SlotReservation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

            public java.util.concurrent.CompletableFuture<Void> warm(com.aresstack.knowledge4j.model.ModelSlot slot) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            public ResourceSnapshot resources() {
                throw new UnsupportedOperationException();
            }
        };
        blocking.set(new BlockingModelCall(port));

        BlockingModelCall.Outcome outcome = blocking.get().run(ChatRequest.builder(TestSlots.CONVERSATION)
                .message(ModelMessage.user("hello")).build());

        assertTrue(cancelled.get(), "the late-registered call was cancelled");
        assertFalse(outcome.isOk());
        assertEquals(ModelFailure.Kind.CANCELLED, outcome.failure.getKind());
    }
}
