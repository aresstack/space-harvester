package com.aresstack.askai.research.runtime.team.mailbox;

/**
 * One immutable mailbox message. The model wrote only {@link #getText()}; everything else (sender, recipient,
 * task ids, run id, time, delivery state) is filled in by Java (plan T3, section 2.4).
 */
public final class AgentMessage {

    private final String id;
    private final String runId;
    private final AgentAddress sender;
    private final String senderTaskId;
    private final AgentAddress recipient;
    private final String recipientTaskId;
    private final String text;
    private final long createdAtMillis;
    private final DeliveryState state;
    private final String note;
    private final boolean handedOut;

    public AgentMessage(String id, String runId, AgentAddress sender, String senderTaskId, AgentAddress recipient,
                        String recipientTaskId, String text, long createdAtMillis, DeliveryState state, String note) {
        this(id, runId, sender, senderTaskId, recipient, recipientTaskId, text, createdAtMillis, state, note, false);
    }

    public AgentMessage(String id, String runId, AgentAddress sender, String senderTaskId, AgentAddress recipient,
                        String recipientTaskId, String text, long createdAtMillis, DeliveryState state, String note,
                        boolean handedOut) {
        if (id == null || runId == null || sender == null || recipient == null || text == null || state == null) {
            throw new IllegalArgumentException("id, runId, sender, recipient, text and state are required");
        }
        this.id = id;
        this.runId = runId;
        this.sender = sender;
        this.senderTaskId = senderTaskId;
        this.recipient = recipient;
        this.recipientTaskId = recipientTaskId;
        this.text = text;
        this.createdAtMillis = createdAtMillis;
        this.state = state;
        this.note = note;
        this.handedOut = handedOut;
    }

    public AgentMessage withState(DeliveryState newState, String newNote) {
        return new AgentMessage(id, runId, sender, senderTaskId, recipient, recipientTaskId, text, createdAtMillis,
                newState, newNote == null ? note : newNote, handedOut);
    }

    /** The same message, recorded as handed out to its recipient at least once (exempt from TTL from now on). */
    public AgentMessage handedOut() {
        return new AgentMessage(id, runId, sender, senderTaskId, recipient, recipientTaskId, text, createdAtMillis,
                state, note, true);
    }

    /** {@code true} once the message was handed out; it then never expires, even after a crash of the turn. */
    public boolean isHandedOut() {
        return handedOut;
    }

    public String getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public AgentAddress getSender() {
        return sender;
    }

    /** The sender's task, {@code null} for the orchestrator and for system notes. */
    public String getSenderTaskId() {
        return senderTaskId;
    }

    public AgentAddress getRecipient() {
        return recipient;
    }

    /** The recipient's task, {@code null} for the orchestrator. */
    public String getRecipientTaskId() {
        return recipientTaskId;
    }

    public String getText() {
        return text;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public DeliveryState getState() {
        return state;
    }

    /** Why a message was redirected, refused or expired; {@code null} otherwise. */
    public String getNote() {
        return note;
    }

    @Override
    public String toString() {
        return id + " " + sender + " -> " + recipient + " [" + state + "] " + text;
    }
}
