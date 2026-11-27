package com.aresstack.knowledge4j.model.support;

import com.aresstack.knowledge4j.model.ChatListener;
import com.aresstack.knowledge4j.model.ChatRequest;
import com.aresstack.knowledge4j.model.ModelCall;
import com.aresstack.knowledge4j.model.resource.SlotBinding;

import java.util.concurrent.CompletableFuture;

/**
 * The narrow backend a host gives the {@link CapacityPlanner}: one streamed, cancellable call on a bound model,
 * and a warm-up as far as the backend can do one. The backend must carry everything the request holds: output
 * format (free, JSON, JSON schema), temperature, maximum output tokens, thinking mode, tools, the context size of
 * the binding, and report the finish reason (above all LENGTH) and token metrics in the result. Callbacks arrive
 * on the backend's own threads, exactly one terminal callback per call.
 */
public interface ModelBackend {

    ModelCall stream(SlotBinding binding, ChatRequest request, ChatListener listener);

    /** What {@link #warm} really does; reported by the planner, never silently upgraded. */
    WarmUp warmUp();

    /** Warms the bound model as far as {@link #warmUp()} says; the default does nothing. */
    default CompletableFuture<Void> warm(SlotBinding binding) {
        return CompletableFuture.completedFuture(null);
    }
}
