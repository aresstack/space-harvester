package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelCall;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.TokenUsage;
import com.aresstack.askai.agent.model.port.resource.ModelHandle;
import com.aresstack.askai.agent.model.port.resource.ResourceSnapshot;
import com.aresstack.askai.agent.model.port.resource.SlotRequest;
import com.aresstack.askai.agent.model.port.resource.SlotReservation;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A reusable fake of the host's {@link ModelPort} (ADR 0001, 3.2: the former {@code MockMainModelServer}
 * becomes a port fake). It drives the model-backed roles end to end without a model: it records every
 * request (slot, messages, output format), answers scripted structured JSON turns in order, and can BLOCK a
 * response on a latch so a cancel-on-close test can prove the close does not wait for the model timeout.
 * It honours the port's contract: callbacks on a worker thread, exactly one terminal callback, a cancelled
 * call ends with {@link ModelFailure.Kind#CANCELLED}.
 */
public final class FakeModelPort implements ModelPort, AutoCloseable {

    private final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<ChatRequest>());
    private final ConcurrentLinkedDeque<String> scripted = new ConcurrentLinkedDeque<String>();
    private final AtomicInteger reservations = new AtomicInteger();
    /** When non-null, each call awaits this latch before answering (a blocking/slow model). */
    private volatile CountDownLatch gate;
    /**
     * Test seam: runs inside {@link ModelCall#cancel()} right after the worker has been woken and before the
     * cancel reports its terminal callback. It receives a latch that opens once the worker thread has exited,
     * so a regression test can deterministically let the worker run first.
     */
    volatile CancelSignalHook cancelSignalHook;

    /** See {@link #cancelSignalHook}. */
    interface CancelSignalHook {
        void afterWorkerWoken(CountDownLatch workerExited);
    }

    /** Test seam: the usage, finish reason, model label and thinking every later answer reports. */
    volatile ChatResult telemetry;

    /** Every later answer reports this usage, finish reason, model label and reasoning text. */
    public void reportTelemetry(int promptTokens, int outputTokens, ChatResult.FinishReason finishReason,
                                String modelLabel, String thinking) {
        telemetry = new ChatResult("x", thinking, null, finishReason, new TokenUsage(promptTokens, outputTokens),
                "fake", Duration.ofMillis(1500), modelLabel, Duration.ofMillis(200), Duration.ofMillis(700));
    }

    /** Queue one raw model answer — the {@code content} the TeamAgentTurnParser will parse (a JSON turn). */
    public void enqueueRaw(String rawContent) {
        scripted.add(rawContent == null ? "" : rawContent);
    }

    /** Queue a minimal assistant-only turn. */
    public void enqueueMessage(String assistantMessage) {
        enqueueRaw("{\"assistantMessage\":\"" + jsonEscape(assistantMessage) + "\"}");
    }

    /** Queue a turn with an assistant message, a proposed command and a legacy scope object. */
    public void enqueueCommandProposal(String assistantMessage, String command, String question,
                                     List<String> aspects) {
        StringBuilder sb = new StringBuilder("{\"assistantMessage\":\"")
                .append(jsonEscape(assistantMessage)).append("\",\"proposedCommand\":\"")
                .append(jsonEscape(command)).append("\",\"scope\":{\"question\":\"")
                .append(jsonEscape(question)).append("\",\"aspects\":[");
        for (int i = 0; i < aspects.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(jsonEscape(aspects.get(i))).append('"');
        }
        sb.append("]}}");
        enqueueRaw(sb.toString());
    }

    /** Install a gate that blocks every subsequent response until the latch is counted down. */
    public void blockResponses(CountDownLatch latch) {
        this.gate = latch;
    }

    public List<ChatRequest> requests() {
        synchronized (requests) {
            return new ArrayList<ChatRequest>(requests);
        }
    }

    public boolean sawSlot(ModelSlot slot) {
        for (ChatRequest request : requests()) {
            if (request.getSlot().equals(slot)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the recorded request's message history contains this substring anywhere. */
    public static boolean historyContains(ChatRequest request, String needle) {
        for (ModelMessage message : request.getMessages()) {
            if (message.getContent().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Write an {@code inference-config.json} the way AskAI publishes it: it tells the session that a main
     * model is selected and carries the per-call timeout. The endpoint in it is never called any more.
     */
    public File writeInferenceConfig(File file, String model) throws IOException {
        String json = "{\"formatVersion\":1,\"configurationRevision\":1,\"model\":\"" + jsonEscape(model)
                + "\",\"baseUrl\":\"http://127.0.0.1:9\",\"chatPath\":\"/api/chat\",\"timeoutMillis\":120000}";
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Override
    public CompletableFuture<SlotReservation> reserve(final SlotRequest request) {
        final String id = "fake-" + reservations.incrementAndGet();
        final AtomicBoolean active = new AtomicBoolean(true);
        return CompletableFuture.<SlotReservation>completedFuture(new SlotReservation() {
            public String getId() {
                return id;
            }

            public ModelSlot getSlot() {
                return request.getSlot();
            }

            public String getEndpointId() {
                return "fake";
            }

            public ModelHandle getModel() {
                return ModelHandle.of("fake-model");
            }

            public int getContextTokens() {
                return request.getContextTokens();
            }

            public boolean isActive() {
                return active.get();
            }

            public void close() {
                active.set(false);
            }
        });
    }

    @Override
    public ModelCall chat(SlotReservation reservation, ChatRequest request, ChatListener listener) {
        return chat(request, listener);
    }

    @Override
    public ModelCall chat(final ChatRequest request, final ChatListener listener) {
        requests.add(request);
        final AtomicBoolean terminal = new AtomicBoolean();
        final CountDownLatch cancelled = new CountDownLatch(1);
        final CountDownLatch workerExited = new CountDownLatch(1);
        final Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    answer();
                } finally {
                    workerExited.countDown();
                }
            }

            private void answer() {
                CountDownLatch latch = gate;
                if (latch != null) {
                    try {
                        // Wake on whichever comes first: the gate opens or the call is cancelled.
                        long deadline = System.currentTimeMillis() + 30000L;
                        while (latch.getCount() > 0 && cancelled.getCount() > 0
                                && System.currentTimeMillis() < deadline) {
                            cancelled.await(20, TimeUnit.MILLISECONDS);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
                String content = scripted.isEmpty() ? "{\"assistantMessage\":\"(no scripted turn)\"}"
                        : scripted.poll();
                listener.onContentDelta(content);
                ChatResult shape = telemetry;
                listener.onCompleted(shape == null
                        ? new ChatResult(content, "", null, ChatResult.FinishReason.STOP,
                                TokenUsage.unknown(), "fake", Duration.ZERO)
                        : new ChatResult(content, shape.getThinking(), null, shape.getFinishReason(),
                                shape.getUsage(), "fake", shape.getDuration(), shape.getModelLabel(),
                                shape.getLoadDuration(), shape.getPromptEvalDuration()));
            }
        }, "fake-model-port");
        worker.setDaemon(true);
        worker.start();
        return new ModelCall() {
            public void cancel() {
                // Claim the terminal state BEFORE waking the worker: otherwise the woken worker can claim it
                // first and report onCompleted for a call the caller has already cancelled.
                boolean claimed = terminal.compareAndSet(false, true);
                cancelled.countDown();
                CancelSignalHook hook = cancelSignalHook;
                if (hook != null) {
                    hook.afterWorkerWoken(workerExited);
                }
                if (claimed) {
                    listener.onFailed(new ModelFailure(ModelFailure.Kind.CANCELLED, "cancelled by the caller"));
                }
            }

            public boolean isDone() {
                return terminal.get();
            }
        };
    }

    @Override
    public java.util.concurrent.CompletableFuture<Void> warm(com.aresstack.askai.agent.model.port.ModelSlot slot) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    @Override
    public ResourceSnapshot resources() {
        return new ResourceSnapshot(Instant.now(), null, null, null, null, false);
    }

    @Override
    public void close() {
        CountDownLatch latch = gate;
        if (latch != null) {
            latch.countDown();
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
