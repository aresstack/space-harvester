package com.aresstack.askai.research.runtime.team.mailbox;

import java.util.List;

/**
 * The {@code MESSAGES FOR YOU} block of a worker's page turn (plan section 3): one line per message,
 * {@code - orchestrator: ...} or {@code - worker 4: ...}. The mailbox already bounded count and length.
 */
public final class MessagesForYouBlock {

    public static final String HEADER = "MESSAGES FOR YOU:";

    private MessagesForYouBlock() {
    }

    /** The block, or an empty string when there are no messages (the prompt then omits it). */
    public static String render(List<AgentMessage> inbox) {
        if (inbox == null || inbox.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(HEADER);
        for (AgentMessage m : inbox) {
            b.append("\n- ").append(m.getSender().key()).append(": ").append(m.getText());
        }
        return b.toString();
    }
}
