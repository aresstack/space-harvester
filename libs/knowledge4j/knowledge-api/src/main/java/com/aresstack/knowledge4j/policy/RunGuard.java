package com.aresstack.knowledge4j.policy;

import com.aresstack.knowledge4j.source.Classification;
import com.aresstack.knowledge4j.source.Provenance;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The transfer guard of one run. It keeps the run's high-water mark: the merged provenance of the task and of
 * everything the run has read. The mark only grows; every outgoing payload is judged with its own provenance
 * merged with the mark, so a caller that forgets to carry provenance can never lower the level.
 *
 * <p>A run starts at the classification the host gives the task; without an explicit release that is
 * {@link Classification#INTERNAL}.</p>
 */
public final class RunGuard {

    private final TransferPolicy policy;
    private final AtomicReference<Provenance> mark;

    public RunGuard(TransferPolicy policy, Provenance task) {
        this.policy = Objects.requireNonNull(policy, "a run needs a transfer policy");
        this.mark = new AtomicReference<>(task == null ? Provenance.unknown() : task);
    }

    /** Raises the mark by content the run has read (hits, fetched content, passages, model answers). */
    public void sawContent(Provenance read) {
        if (read != null) {
            mark.accumulateAndGet(read, Provenance::merge);
        }
    }

    public Provenance mark() {
        return mark.get();
    }

    /** Judges one transfer; the payload's provenance is merged with the mark first. */
    public TransferDecision check(TransferTarget target, PayloadKind kind, Provenance payload, long payloadChars) {
        Provenance effective = mark.get().merge(payload);
        TransferDecision decision = policy.decide(new TransferRequest(target, kind, effective, payloadChars));
        return decision == null ? TransferDecision.forbid("the policy gave no decision") : decision;
    }

    /** Like {@link #check}, but throws when the transfer is not allowed. */
    public void require(TransferTarget target, PayloadKind kind, Provenance payload, long payloadChars)
            throws TransferDeniedException {
        TransferDecision decision = check(target, kind, payload, payloadChars);
        if (!decision.allowed()) {
            throw new TransferDeniedException(target, kind, decision);
        }
    }
}
