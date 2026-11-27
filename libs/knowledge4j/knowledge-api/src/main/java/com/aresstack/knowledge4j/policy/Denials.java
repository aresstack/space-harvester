package com.aresstack.knowledge4j.policy;

import com.aresstack.knowledge4j.access.SourceAccessException;

/** Maps a denied transfer to the access exception of the source ports. */
final class Denials {

    private Denials() {
    }

    static SourceAccessException toAccess(TransferTarget target, PayloadKind kind, TransferDecision decision) {
        SourceAccessException.Reason reason = decision.reason() == TransferDecision.Reason.APPROVAL_REQUIRED
                ? SourceAccessException.Reason.APPROVAL_REQUIRED
                : SourceAccessException.Reason.DENIED_BY_POLICY;
        return new SourceAccessException(reason, new TransferDeniedException(target, kind, decision).getMessage());
    }
}
