package com.aresstack.askai.agent.model.port;

import com.aresstack.askai.agent.model.port.resource.ResourceSnapshot;
import com.aresstack.askai.agent.model.port.resource.SlotRequest;
import com.aresstack.askai.agent.model.port.resource.SlotReservation;
import com.aresstack.askai.agent.model.port.resource.SlotUnavailableException;

import java.util.concurrent.CompletableFuture;

/**
 * MODEL PORT v2: the platform's single model access for in-process plugins (ADR 0001, 3.2 and 4.5). It
 * replaces the plugin's own Ollama HTTP clients; the narrow
 * {@link com.aresstack.askai.agent.model.inference.AgentInferencePort} stays for its existing callers.
 *
 * <p><b>What a caller sees.</b> Model slots ({@link ModelSlot}), messages, tool definitions and tool calls,
 * output formats, a stream of thinking and content chunks, a cancel handle, and an opaque view of the
 * resources. <b>What it never sees.</b> Ollama, Jackson, HTTP, model names, endpoint URLs or provider
 * options. The platform implements the port on its one Ollama client (ollama4j) and its central model
 * configuration.</p>
 *
 * <p><b>Calls.</b> {@link #chat} covers every call shape the research roles use: plain chat, chat with
 * tools (the model requests tool calls, the CALLER executes them and sends tool-result messages back in the
 * next call), structured output with a JSON schema, and streaming with thinking chunks. The port never
 * executes a tool and never loops on its own; one call is one model turn. Every call is cancellable.</p>
 *
 * <p><b>Resource scheduling.</b> The platform owns a scheduler over an endpoint pool. Each model on each
 * endpoint has a capacity (concurrent calls; a user setting, default 1, mirroring the endpoint's own
 * parallelism). A call needs a place of that capacity: either explicitly reserved beforehand with
 * {@link #reserve} (the task executor's path, so it knows a worker can run before dispatching it), or
 * reserved implicitly by {@link #chat(ChatRequest, ChatListener)} for the duration of one call. The
 * scheduler never admits more calls than the capacity, so nothing waits invisibly in the provider's queue.
 * With capacity 1 on one GPU everything runs one after another; with capacity 4 four independent workers
 * run at once; with several endpoints the same caller code spreads over them.</p>
 *
 * <p>Residency (which models are loaded) is advisory: the platform keeps the active model warm, groups
 * waiting work by model and plans a model switch as a deliberate step, but the provider's unload behaviour
 * is not contractual. Callers must never rely on a model staying loaded; {@link #resources()} is for
 * planning and diagnostics, not for correctness.</p>
 *
 * <p><b>Threading.</b> No method blocks the calling thread. Listener callbacks and future completions run
 * on platform threads.</p>
 */
public interface ModelPort {

    /**
     * Reserves one place of the capacity of the slot's model on its endpoint. The future completes with
     * the reservation once a place is free, or exceptionally with a {@link SlotUnavailableException} (no
     * binding, endpoint unavailable, context too large, {@code maxWait} elapsed, port closed). Cancelling
     * the future withdraws a waiting request; a reservation granted concurrently with the cancel is closed
     * by the port, never leaked.
     *
     * <p>Waiting requests are served in the scheduler's order, which may group requests by model to avoid
     * needless model switches; it is fair within one model.</p>
     */
    CompletableFuture<SlotReservation> reserve(SlotRequest request);

    /**
     * Starts one chat call under an explicit reservation, without further queueing. The request's slot must
     * equal the reservation's slot, its context size must not exceed the reservation's, and no other call
     * may be running under the reservation; otherwise the call ends with
     * {@link ModelFailure.Kind#RESERVATION_INVALID}. Never blocks; every outcome, validation failures
     * included, arrives through the listener.
     */
    ModelCall chat(SlotReservation reservation, ChatRequest request, ChatListener listener);

    /**
     * Starts one chat call with an implicit reservation for its duration: the call waits in the scheduler
     * up to the platform's configured default wait, then runs, then releases its place. Never blocks; an
     * unavailable slot ends the call with the matching {@link ModelFailure.Kind}.
     */
    ModelCall chat(ChatRequest request, ChatListener listener);

    /**
     * Loads the slot's model with its context, so the first call on it does not pay the load (the host's
     * warm-up of a phase model). Completes when the model is loaded, exceptionally with a
     * {@link SlotUnavailableException} when the slot has no model or the endpoint is unavailable. Never blocks.
     */
    CompletableFuture<Void> warm(ModelSlot slot);

    /** A current view of endpoints, slot bindings, capacities and residency. Never blocks for a probe. */
    ResourceSnapshot resources();
}
