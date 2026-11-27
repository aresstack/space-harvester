package com.aresstack.askai.research.runtime.team.mailbox;

/** What {@link AgentMailbox#send} did: the stored message (if any) and one plain line for the model. */
public final class SendReceipt {

    private final boolean accepted;
    private final AgentMessage message;
    private final String reply;

    SendReceipt(boolean accepted, AgentMessage message, String reply) {
        this.accepted = accepted;
        this.message = message;
        this.reply = reply;
    }

    /** {@code true} when the message is queued or was redirected to the orchestrator. */
    public boolean isAccepted() {
        return accepted;
    }

    /** The stored message, {@code null} when nothing was stored. */
    public AgentMessage getMessage() {
        return message;
    }

    /** One sentence for the model, e.g. {@code Sent to worker 4.} or {@code Not sent: ...}. */
    public String getReply() {
        return reply;
    }
}
