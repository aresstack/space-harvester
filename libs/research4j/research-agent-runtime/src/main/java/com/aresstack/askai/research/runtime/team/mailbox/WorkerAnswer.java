package com.aresstack.askai.research.runtime.team.mailbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A page worker's labelled answer after parsing (plan section 2.4): summary, verdict, follow links, messages. */
public final class WorkerAnswer {

    /** The {@code USEFUL} verdict; {@link #UNSTATED} when the model left the line out (Java then does not accept). */
    public enum Usefulness { YES, PARTLY, NO, UNSTATED }

    /** One {@code TO ORCHESTRATOR:} or {@code TO WORKER n:} line: the recipient as written and the text. */
    public static final class Outgoing {
        private final String recipient;
        private final String text;

        public Outgoing(String recipient, String text) {
            this.recipient = recipient;
            this.text = text;
        }

        public String getRecipient() {
            return recipient;
        }

        public String getText() {
            return text;
        }

        @Override
        public String toString() {
            return recipient + ": " + text;
        }
    }

    private final String summary;
    private final Usefulness usefulness;
    private final String usefulReason;
    private final List<Integer> follow;
    private final List<Outgoing> messages;
    private final List<String> searches;

    public WorkerAnswer(String summary, Usefulness usefulness, String usefulReason, List<Integer> follow,
                        List<Outgoing> messages) {
        this(summary, usefulness, usefulReason, follow, messages, Collections.<String>emptyList());
    }

    /**
     * @param searches new search queries the page suggests (its {@code SEARCH} lines): the research continues from
     *                 what it has just read, as a person would
     */
    public WorkerAnswer(String summary, Usefulness usefulness, String usefulReason, List<Integer> follow,
                        List<Outgoing> messages, List<String> searches) {
        this.searches = Collections.unmodifiableList(new ArrayList<String>(searches));
        this.summary = summary;
        this.usefulness = usefulness;
        this.usefulReason = usefulReason == null ? "" : usefulReason;
        this.follow = Collections.unmodifiableList(new ArrayList<Integer>(follow));
        this.messages = Collections.unmodifiableList(new ArrayList<Outgoing>(messages));
    }

    /** The new search queries this page suggests, at most the parser's limit; empty when none. */
    public List<String> getSearches() {
        return searches;
    }

    public String getSummary() {
        return summary;
    }

    public Usefulness getUsefulness() {
        return usefulness;
    }

    public String getUsefulReason() {
        return usefulReason;
    }

    /** 1-based numbers of the numbered link list, in the order written, at most k, without duplicates. */
    public List<Integer> getFollow() {
        return follow;
    }

    public List<Outgoing> getMessages() {
        return messages;
    }

    /**
     * Hands the answer to the mailbox the way the two tools would: every message line becomes a
     * {@code send_message}, then {@code finish_task} with the summary and the verdict. Returns the receipts' lines.
     */
    public List<String> deliver(AgentMailbox mailbox, String taskId) {
        List<String> replies = new ArrayList<String>();
        for (Outgoing o : messages) {
            replies.add(mailbox.send(taskId, o.getRecipient(), o.getText()).getReply());
        }
        String verdict = usefulness == Usefulness.UNSTATED ? "" : "USEFUL: " + usefulness.name().toLowerCase()
                + (usefulReason.isEmpty() ? "" : " — " + usefulReason) + ". ";
        replies.add(mailbox.finishTask(taskId, verdict + summary));
        return replies;
    }
}
