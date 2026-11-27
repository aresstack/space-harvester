package com.aresstack.knowledge4j.model.guard;

import com.aresstack.knowledge4j.model.ChatListener;
import com.aresstack.knowledge4j.model.ChatRequest;
import com.aresstack.knowledge4j.model.ChatResult;
import com.aresstack.knowledge4j.model.ModelFailure;
import com.aresstack.knowledge4j.model.ModelMessage;
import com.aresstack.knowledge4j.model.TestSlots;
import com.aresstack.knowledge4j.model.testing.FakeModelPort;
import com.aresstack.knowledge4j.policy.RunGuard;
import com.aresstack.knowledge4j.policy.TransferPolicy;
import com.aresstack.knowledge4j.policy.TransferTarget;
import com.aresstack.knowledge4j.source.Classification;
import com.aresstack.knowledge4j.source.Provenance;
import com.aresstack.knowledge4j.source.SourceKind;
import com.aresstack.knowledge4j.source.SourceRef;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A conversation that has seen internal content never reaches an external model endpoint. */
class GuardedModelPortTest {

    @Test
    void aPromptAfterInternalContentNeverReachesAnExternalSlot() {
        RunGuard guard = new RunGuard(TransferPolicy.internalStaysInternal(),
                Provenance.classified(Classification.PUBLIC));
        guard.sawContent(Provenance.of(Classification.INTERNAL,
                SourceRef.of(SourceKind.INTERNAL_DOCUMENT, "doc-7")));
        FakeModelPort cloud = new FakeModelPort();
        cloud.enqueueRaw("never");
        GuardedModelPort port = new GuardedModelPort(cloud, slot -> TransferTarget.external("cloud"), guard);
        AtomicReference<ModelFailure> failure = new AtomicReference<>();

        port.chat(ChatRequest.builder(TestSlots.SCOPE).messages(Collections.singletonList(
                ModelMessage.user("Fasse Dokument 7 zusammen"))).build(), new ChatListener() {
            @Override
            public void onCompleted(ChatResult result) {
            }

            @Override
            public void onFailed(ModelFailure f) {
                failure.set(f);
            }
        });

        assertEquals(ModelFailure.Kind.TRANSFER_DENIED, failure.get().getKind());
        assertTrue(cloud.requests().isEmpty(), "nothing was sent to the external endpoint");
    }
}
