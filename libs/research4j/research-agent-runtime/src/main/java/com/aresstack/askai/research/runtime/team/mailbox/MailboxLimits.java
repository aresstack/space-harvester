package com.aresstack.askai.research.runtime.team.mailbox;

/** Bounds of the mailbox (plan T3: caps and TTL). Immutable; {@link #defaults()} are the plan's recommendations. */
public final class MailboxLimits {

    private final int inboxShown;
    private final int messageChars;
    private final int sendsPerTask;
    private final long ttlMillis;
    private final int pendingPerRecipient;

    public MailboxLimits(int inboxShown, int messageChars, int sendsPerTask, long ttlMillis, int pendingPerRecipient) {
        if (inboxShown < 1 || messageChars < 40 || sendsPerTask < 1 || ttlMillis < 1 || pendingPerRecipient < 1) {
            throw new IllegalArgumentException("mailbox limits must be positive (message length at least 40)");
        }
        this.inboxShown = inboxShown;
        this.messageChars = messageChars;
        this.sendsPerTask = sendsPerTask;
        this.ttlMillis = ttlMillis;
        this.pendingPerRecipient = pendingPerRecipient;
    }

    /** 5 messages shown per turn, 600 characters each, 6 sends per task, 15 minutes TTL, 50 pending per inbox. */
    public static MailboxLimits defaults() {
        return new MailboxLimits(5, 600, 6, 15L * 60L * 1000L, 50);
    }

    /** The last m messages a worker sees in its {@code MESSAGES FOR YOU} block. */
    public int getInboxShown() {
        return inboxShown;
    }

    /** A message longer than this is cut (with an ellipsis) before it is stored. */
    public int getMessageChars() {
        return messageChars;
    }

    /** How many messages one task may send; more are refused. */
    public int getSendsPerTask() {
        return sendsPerTask;
    }

    /** A pending message older than this expires unread. */
    public long getTtlMillis() {
        return ttlMillis;
    }

    /** A recipient that never reads holds at most this many pending messages; the oldest expire first. */
    public int getPendingPerRecipient() {
        return pendingPerRecipient;
    }
}
