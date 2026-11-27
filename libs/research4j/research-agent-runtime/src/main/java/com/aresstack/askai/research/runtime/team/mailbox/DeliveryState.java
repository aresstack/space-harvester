package com.aresstack.askai.research.runtime.team.mailbox;

/** Delivery state of one mailbox message; only {@link #PENDING} messages are still waiting for a reader. */
public enum DeliveryState {
    /** Queued for the recipient's next turn (worker) or next review point (orchestrator). */
    PENDING,
    /** Shown to the recipient. */
    DELIVERED,
    /** The recipient had no further turn; a copy went to the orchestrator with a note. */
    REDIRECTED,
    /** Not delivered in time (TTL) or pushed out of a full inbox. */
    EXPIRED,
    /** Not accepted (unknown recipient, cap, permission); the sender sees the refusal in its next inbox. */
    REFUSED
}
