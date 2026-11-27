package com.aresstack.askai.research.runtime.inference;

import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.ReasoningEffort;
import com.aresstack.askai.browser.search.inference.StructuredInferenceRequest;
import com.aresstack.askai.browser.search.inference.StructuredInferenceResult;
import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;
import com.aresstack.askai.research.runtime.team.FakeModelPort;

import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The SERP layout repair over the model port keeps the former client's call shape (system + user, thinking
 * OFF, the request's temperature and output budget) and its honest typed statuses.
 */
public class PortStructuredInferenceClientTest {

    private static StructuredInferenceRequest request(CancellationSignal signal) {
        return new StructuredInferenceRequest("profile", "system prompt", "user prompt", 128, 0.1,
                ReasoningEffort.DEFAULT, 1, signal);
    }

    @Test
    public void sendsSystemAndUserWithThinkingOffAndReturnsTheRawAnswer() {
        FakeModelPort port = new FakeModelPort();
        port.enqueueRaw("{\"layout\":\"ok\"}");
        StructuredInferenceResult result = new PortStructuredInferenceClient(port,
                ResearchModelSlots.LAYOUT_REPAIR, Duration.ofSeconds(30)).execute(request(null));

        assertTrue(result.isSuccess());
        assertEquals("{\"layout\":\"ok\"}", result.rawText);
        ChatRequest sent = port.requests().get(0);
        assertEquals(ResearchModelSlots.LAYOUT_REPAIR, sent.getSlot());
        assertEquals(ThinkingMode.OFF, sent.getThinking());
        assertEquals(128, sent.getMaxOutputTokens());
        assertEquals(Double.valueOf(0.1), sent.getTemperature());
        assertEquals(Duration.ofSeconds(30), sent.getTimeout());
        assertEquals(2, sent.getMessages().size());
        assertEquals(ModelMessage.Role.SYSTEM, sent.getMessages().get(0).getRole());
        assertEquals("user prompt", sent.getMessages().get(1).getContent());
    }

    @Test
    public void aSignalledCancelBeforeTheCallNeverReachesThePort() {
        FakeModelPort port = new FakeModelPort();
        StructuredInferenceResult result = new PortStructuredInferenceClient(port,
                ResearchModelSlots.LAYOUT_REPAIR, null).execute(request(new CancellationSignal() {
                    public boolean isCancelled() {
                        return true;
                    }
                }));
        assertEquals(StructuredInferenceStatus.CANCELLED, result.status);
        assertTrue(port.requests().isEmpty());
    }

    @Test
    public void aSignalledCancelDuringTheCallAbortsItPromptly() throws Exception {
        FakeModelPort port = new FakeModelPort();
        port.blockResponses(new CountDownLatch(1));
        final AtomicBoolean cancel = new AtomicBoolean();
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                cancel.set(true);
            }
        }).start();
        long start = System.currentTimeMillis();
        StructuredInferenceResult result = new PortStructuredInferenceClient(port,
                ResearchModelSlots.LAYOUT_REPAIR, null).execute(request(new CancellationSignal() {
                    public boolean isCancelled() {
                        return cancel.get();
                    }
                }));
        assertEquals(StructuredInferenceStatus.CANCELLED, result.status);
        assertTrue("aborted promptly", System.currentTimeMillis() - start < 5000);
        port.close();
    }
}
