package com.aresstack.knowledge4j.policy;

import com.aresstack.knowledge4j.source.Classification;

/**
 * The host's binding rule for every transfer out of the libraries. Research4j and Writing4j do not start
 * without one; there is no silent allow-all. The libraries never call it from domain code: the guarded ports
 * ({@link GuardedSourceSearchPort}, {@link GuardedContentFetchPort} and the guarded model ports of
 * knowledge-model) call it on every exit, so no caller can forget the check.
 *
 * <p>Implementations must not block for a user dialog; see {@link TransferDecision}.</p>
 */
@FunctionalInterface
public interface TransferPolicy {

    TransferDecision decide(TransferRequest request);

    /**
     * The restrictive baseline: anything goes to internal targets; to external targets only content whose whole
     * provenance is {@link Classification#PUBLIC}. A host builds its own rules on top, never looser for content
     * it has not released.
     */
    static TransferPolicy internalStaysInternal() {
        return request -> {
            if (request.target().exposure() == Exposure.INTERNAL) {
                return TransferDecision.allow();
            }
            Classification level = request.provenance().classification();
            return level == Classification.PUBLIC
                    ? TransferDecision.allow()
                    : TransferDecision.forbid(level + " content to external target " + request.target().id());
        };
    }
}
