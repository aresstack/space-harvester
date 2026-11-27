package com.aresstack.knowledge4j.model.guard;

import com.aresstack.knowledge4j.model.embedding.EmbeddingPort;
import com.aresstack.knowledge4j.policy.PayloadKind;
import com.aresstack.knowledge4j.policy.RunGuard;
import com.aresstack.knowledge4j.policy.TransferBlockedException;
import com.aresstack.knowledge4j.policy.TransferDecision;
import com.aresstack.knowledge4j.policy.TransferDeniedException;
import com.aresstack.knowledge4j.policy.TransferTarget;

import java.util.List;
import java.util.Objects;

/**
 * An embedding port behind the run's transfer guard. Query embeddings of a section brief or a concept path are
 * derived from the task and the passages read, so they are judged with the run's mark like a prompt.
 */
public final class GuardedEmbeddingPort implements EmbeddingPort {

    private final EmbeddingPort delegate;
    private final TransferTarget target;
    private final RunGuard guard;

    public GuardedEmbeddingPort(EmbeddingPort delegate, TransferTarget target, RunGuard guard) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.target = Objects.requireNonNull(target, "target");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public List<EmbeddingVector> embed(Role role, List<String> texts) {
        long chars = 0;
        for (String text : texts) {
            chars += text == null ? 0 : text.length();
        }
        TransferDecision decision = guard.check(target, PayloadKind.EMBEDDING, null, chars);
        if (!decision.allowed()) {
            throw new TransferBlockedException(new TransferDeniedException(target, PayloadKind.EMBEDDING, decision));
        }
        return delegate.embed(role, texts);
    }
}
