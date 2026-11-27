package com.aresstack.askai.research.runtime.team.mailbox;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mailbox of one team run (plan T3, section 2.4): the two operations {@code send_message(recipient, message)}
 * and {@code finish_task(summary)}, and everything a model never has to know about them: sender, task ids, time,
 * routing by display number, permissions, persistence, caps and TTL.
 *
 * <p>Nothing here ever waits. Every method changes a few in-memory entries, appends one journal line and returns;
 * a message to a worker is shown at its next turn ({@link #takeInbox}), a message to the orchestrator at its next
 * review point ({@link #drainOrchestrator}). A recipient that never reads only fills its own bounded inbox.
 *
 * <p>Rules: a worker may write to the orchestrator and to live workers of the same run, not to itself; an unknown
 * worker number is refused and the refusal shows up in the sender's next inbox; a message to a worker that has
 * finished (or finishes before it reads it) goes to the orchestrator with a note; a worker inbox keeps at most
 * {@link MailboxLimits#getPendingPerRecipient()} pending messages and drops messages older than the TTL. The
 * orchestrator's queue has no TTL: worker reports are never lost, and the per-task send cap bounds it.
 */
public final class AgentMailbox {

    /** Called (outside the lock) when the orchestrator has new mail, so the coordinator can wake up. */
    public interface Listener {
        void orchestratorMail(AgentMessage message);
    }

    private static final String SYSTEM_NOTE = "system";
    private static final String FINISH_NOTE = "finish_task";
    private static final String REDIRECT_PREFIX = "redirected from ";

    private final String runId;
    private final MailboxStore store;
    private final MailboxLimits limits;
    private final Clock clock;
    private final MessageRouter router = new MessageRouter();
    private final Map<String, AgentMessage> messages = new LinkedHashMap<String, AgentMessage>();
    private final Map<String, Integer> sendsByTask = new LinkedHashMap<String, Integer>();
    private final java.util.Set<String> inFlight = new java.util.HashSet<String>();
    private volatile Listener listener;
    private long sequence;

    /** Time source, injectable for TTL tests. */
    public interface Clock {
        long millis();
    }

    private AgentMailbox(String runId, MailboxStore store, MailboxLimits limits, Clock clock) {
        this.runId = runId;
        this.store = store;
        this.limits = limits;
        this.clock = clock;
    }

    /** Opens the mailbox of {@code runId} and replays its journal: registrations, counts and undelivered mail. */
    public static AgentMailbox open(String runId, MailboxStore store, MailboxLimits limits, Clock clock)
            throws IOException {
        if (runId == null || runId.trim().isEmpty() || store == null || limits == null || clock == null) {
            throw new IllegalArgumentException("runId, store, limits and clock are required");
        }
        AgentMailbox mailbox = new AgentMailbox(runId, store, limits, clock);
        mailbox.replay();
        return mailbox;
    }

    public static AgentMailbox open(String runId, MailboxStore store) throws IOException {
        return open(runId, store, MailboxLimits.defaults(), new Clock() {
            public long millis() {
                return System.currentTimeMillis();
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public String getRunId() {
        return runId;
    }

    public MailboxLimits getLimits() {
        return limits;
    }

    private void replay() throws IOException {
        for (String line : store.readLines()) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonObject o;
            try {
                o = MailboxJournalCodec.parse(line);
            } catch (RuntimeException unreadable) {
                throw new IOException("unreadable mailbox journal line of run " + runId);
            }
            String kind = o.has("kind") ? o.get("kind").getAsString() : "";
            if ("worker".equals(kind)) {
                router.restore(o.get("number").getAsInt(), o.get("taskId").getAsString(), o.get("live").getAsBoolean());
            } else if ("message".equals(kind)) {
                AgentMessage m = MailboxJournalCodec.decodeMessage(o);
                if (!messages.containsKey(m.getId()) && isOwnSend(m)) {
                    count(m.getSenderTaskId());
                }
                messages.put(m.getId(), m);
                sequence = Math.max(sequence, sequenceOf(m.getId()));
            }
        }
    }

    /** A message the sender's model wrote itself (counts against its cap); not a note, report or redirect copy. */
    private static boolean isOwnSend(AgentMessage m) {
        String note = m.getNote();
        return m.getSenderTaskId() != null && !SYSTEM_NOTE.equals(note) && !FINISH_NOTE.equals(note)
                && (note == null || !note.startsWith(REDIRECT_PREFIX));
    }

    private long sequenceOf(String id) {
        int dash = id.lastIndexOf("-m");
        try {
            return dash < 0 ? 0 : Long.parseLong(id.substring(dash + 2));
        } catch (NumberFormatException other) {
            return 0;
        }
    }

    /**
     * Registers a worker task for this run (or re-registers it after a restart) and returns its display number.
     * Pending messages of a re-queued task stay in its inbox.
     */
    public synchronized int registerWorker(String taskId) {
        requireTask(taskId);
        int number = router.register(taskId);
        for (AgentMessage m : pendingFor(taskId)) {
            inFlight.remove(m.getId()); // a re-queued task sees what its crashed turn never processed
        }
        persistLine(MailboxJournalCodec.encodeWorker(number, taskId, true));
        return number;
    }

    /** The display number of {@code taskId}, or {@code null} if it is not part of this run. */
    public synchronized Integer workerNumber(String taskId) {
        return router.numberOf(taskId);
    }

    /**
     * {@code send_message(recipient, message)}. {@code senderTaskId} is the calling worker's task, {@code null}
     * for the orchestrator. Never blocks and never waits for an answer.
     */
    public SendReceipt send(String senderTaskId, String recipient, String text) {
        SendReceipt receipt;
        synchronized (this) {
            receipt = sendLocked(senderTaskId, recipient, text);
        }
        notifyOrchestrator(receipt.getMessage());
        return receipt;
    }

    private SendReceipt sendLocked(String senderTaskId, String recipientText, String text) {
        expireStale();
        AgentAddress sender = AgentAddress.ORCHESTRATOR;
        if (senderTaskId != null) {
            Integer number = router.numberOf(senderTaskId);
            if (number == null) {
                return new SendReceipt(false, null, "Not sent: your task is not part of this research run.");
            }
            if (!router.isLive(number)) {
                return new SendReceipt(false, null, "Not sent: your task has already finished.");
            }
            sender = AgentAddress.worker(number);
        }
        String body = cut(text);
        if (body.isEmpty()) {
            return new SendReceipt(false, null, "Not sent: the message is empty.");
        }
        AgentAddress recipient = AgentAddress.parse(recipientText);
        if (recipient == null) {
            return refuse(sender, senderTaskId, "Not sent: \"" + shortText(recipientText)
                    + "\" is not a recipient. Write to orchestrator or to worker <number>.");
        }
        if (recipient.equals(sender)) {
            return new SendReceipt(false, null, "Not sent: you cannot write to yourself.");
        }
        if (!recipient.isOrchestrator() && !router.isKnown(recipient.getWorkerNumber())) {
            return refuse(sender, senderTaskId, "Not sent: there is no " + recipient.key() + " in this run.");
        }
        if (senderTaskId != null && sendsOf(senderTaskId) >= limits.getSendsPerTask()) {
            return new SendReceipt(false, null, "Not sent: you have used all " + limits.getSendsPerTask()
                    + " messages of this task. Put the rest into your summary.");
        }
        if (senderTaskId != null) {
            count(senderTaskId);
        }
        if (recipient.isOrchestrator()) {
            AgentMessage m = store(newMessage(sender, senderTaskId, recipient, null, body, DeliveryState.PENDING, null));
            return new SendReceipt(true, m, "Sent to the orchestrator.");
        }
        String recipientTask = router.taskOf(recipient.getWorkerNumber());
        if (!router.isLive(recipient.getWorkerNumber())) {
            if (sender.isOrchestrator()) {
                AgentMessage m = store(newMessage(sender, null, recipient, recipientTask, body, DeliveryState.EXPIRED,
                        recipient.key() + " has finished"));
                return new SendReceipt(false, m, "Not sent: " + recipient.key() + " has finished.");
            }
            AgentMessage original = store(newMessage(sender, senderTaskId, recipient, recipientTask, body,
                    DeliveryState.REDIRECTED, recipient.key() + " has finished"));
            AgentMessage copy = redirectCopy(original);
            return new SendReceipt(true, copy, recipient.key() + " has finished; your message went to the orchestrator.");
        }
        AgentMessage m = store(newMessage(sender, senderTaskId, recipient, recipientTask, body, DeliveryState.PENDING,
                null));
        trimInbox(recipientTask);
        return new SendReceipt(true, m, "Sent to " + recipient.key() + ".");
    }

    /**
     * {@code finish_task(summary)}: ends the worker's part in the mailbox, hands the summary to the orchestrator and
     * redirects the worker's unread messages there with a note. Returns the line for the model. Task state, leases
     * and artifacts are the executor's business (T4), not the mailbox's.
     */
    public String finishTask(String taskId, String summary) {
        AgentMessage report;
        List<AgentMessage> copies = new ArrayList<AgentMessage>();
        synchronized (this) {
            Integer number = router.numberOf(taskId);
            if (number == null) {
                return "Not finished: your task is not part of this research run.";
            }
            if (!router.isLive(number)) {
                return "Your task has already finished.";
            }
            // report and redirects are written first and the worker record last, so a failed write or a crash
            // in between leaves the worker live and a retry of finish_task completes the job
            // every write here has a fixed id, so a retry after a crash or failed write re-uses what was already
            // written instead of producing a second report or a second redirect copy
            String reportId = runId + "-finish-" + number;
            report = messages.get(reportId);
            if (report == null) {
                String body = cut(summary);
                report = store(new AgentMessage(reportId, runId, AgentAddress.worker(number), taskId,
                        AgentAddress.ORCHESTRATOR, null, body.isEmpty() ? "(finished without a summary)" : body,
                        clock.millis(), DeliveryState.PENDING, FINISH_NOTE));
            }
            for (AgentMessage pending : pendingFor(taskId)) {
                if (!pending.getSender().isOrchestrator() && !SYSTEM_NOTE.equals(pending.getNote())) {
                    copies.add(redirectCopy(pending)); // the copy first: a crash now loses nothing
                }
                store(pending.withState(DeliveryState.REDIRECTED,
                        pending.getRecipient().key() + " finished before reading it"));
                inFlight.remove(pending.getId());
            }
            persistLine(MailboxJournalCodec.encodeWorker(number, taskId, false));
            router.finish(taskId);
        }
        notifyOrchestrator(report);
        for (AgentMessage copy : copies) {
            notifyOrchestrator(copy);
        }
        return "Task finished. Your summary went to the orchestrator.";
    }

    /**
     * The worker's inbox for its next turn: the last {@link MailboxLimits#getInboxShown()} pending messages, oldest
     * first. They are handed out, not yet delivered: the caller calls {@link #acknowledge} once the turn that saw
     * them has been processed and saved. Until then they are not handed out again; after a restart, or when the
     * task is registered again, they come back. Older pending messages beyond the limit are dropped as expired.
     */
    public synchronized List<AgentMessage> takeInbox(String taskId) {
        expireStale();
        List<AgentMessage> pending = new ArrayList<AgentMessage>();
        for (AgentMessage m : pendingFor(taskId)) {
            if (!inFlight.contains(m.getId())) {
                pending.add(m);
            }
        }
        int skip = Math.max(0, pending.size() - limits.getInboxShown());
        List<AgentMessage> shown = new ArrayList<AgentMessage>();
        for (int i = 0; i < pending.size(); i++) {
            AgentMessage m = pending.get(i);
            if (i < skip) {
                if (!m.isHandedOut()) { // mail handed out before stays for a later turn instead of expiring
                    store(m.withState(DeliveryState.EXPIRED, "not shown: inbox limit"));
                }
            } else {
                inFlight.add(m.getId());
                shown.add(m.isHandedOut() ? m : store(m.handedOut()));
            }
        }
        return shown;
    }

    /**
     * The orchestrator's pending mail, oldest first, at most {@code max}; handed out like {@link #takeInbox} and
     * delivered only by {@link #acknowledge}, so a crash during the review loses no worker report.
     */
    public synchronized List<AgentMessage> drainOrchestrator(int max) {
        List<AgentMessage> out = new ArrayList<AgentMessage>();
        for (AgentMessage m : new ArrayList<AgentMessage>(messages.values())) {
            if (out.size() >= max) {
                break;
            }
            if (m.getRecipient().isOrchestrator() && m.getState() == DeliveryState.PENDING
                    && !inFlight.contains(m.getId())) {
                inFlight.add(m.getId());
                out.add(m.isHandedOut() ? m : store(m.handedOut()));
            }
        }
        return out;
    }

    /**
     * As {@link #drainOrchestrator}, but only the mail written by {@code senderTaskId}: a worker relays its own
     * reports in its own thread, so they always reach the host before the end of its task does.
     */
    public synchronized List<AgentMessage> drainOrchestratorFrom(String senderTaskId, int max) {
        List<AgentMessage> out = new ArrayList<AgentMessage>();
        for (AgentMessage m : new ArrayList<AgentMessage>(messages.values())) {
            if (out.size() >= max) {
                break;
            }
            if (m.getRecipient().isOrchestrator() && m.getState() == DeliveryState.PENDING
                    && !inFlight.contains(m.getId()) && senderTaskId != null
                    && senderTaskId.equals(m.getSenderTaskId())) {
                inFlight.add(m.getId());
                out.add(m.isHandedOut() ? m : store(m.handedOut()));
            }
        }
        return out;
    }

    /** Marks handed-out messages as delivered: their recipient has processed them. Unknown or settled ids are ignored. */
    public synchronized void acknowledge(List<AgentMessage> processed) {
        for (AgentMessage m : processed) {
            AgentMessage current = messages.get(m.getId());
            if (current != null && current.getState() == DeliveryState.PENDING) {
                store(current.withState(DeliveryState.DELIVERED, null));
            }
            inFlight.remove(m.getId());
        }
    }

    /** Gives handed-out messages back unprocessed (the turn failed); they are handed out again next time. */
    public synchronized void release(List<AgentMessage> unprocessed) {
        for (AgentMessage m : unprocessed) {
            inFlight.remove(m.getId());
        }
    }

    /** All messages of the run in order of creation (latest state each). */
    public synchronized List<AgentMessage> messages() {
        return Collections.unmodifiableList(new ArrayList<AgentMessage>(messages.values()));
    }

    // ------------------------------------------------------------------------------------------------------------

    private SendReceipt refuse(AgentAddress sender, String senderTaskId, String reason) {
        if (senderTaskId == null) {
            return new SendReceipt(false, null, reason);
        }
        AgentMessage note = store(newMessage(AgentAddress.ORCHESTRATOR, null, sender, senderTaskId, reason,
                DeliveryState.PENDING, SYSTEM_NOTE));
        trimInbox(senderTaskId);
        return new SendReceipt(false, note, reason);
    }

    private AgentMessage redirectCopy(AgentMessage original) {
        String copyId = original.getId() + "-r";
        AgentMessage existing = messages.get(copyId);
        if (existing != null) {
            return existing;
        }
        String text = "(meant for " + original.getRecipient().key() + ", who has finished) " + original.getText();
        return store(new AgentMessage(copyId, runId, original.getSender(), original.getSenderTaskId(),
                AgentAddress.ORCHESTRATOR, null, cut(text), clock.millis(), DeliveryState.PENDING,
                REDIRECT_PREFIX + original.getRecipient().key()));
    }

    private List<AgentMessage> pendingFor(String taskId) {
        List<AgentMessage> out = new ArrayList<AgentMessage>();
        for (AgentMessage m : messages.values()) {
            if (m.getState() == DeliveryState.PENDING && !m.getRecipient().isOrchestrator()
                    && taskId.equals(m.getRecipientTaskId())) {
                out.add(m);
            }
        }
        return out;
    }

    private void trimInbox(String taskId) {
        List<AgentMessage> pending = pendingFor(taskId);
        List<AgentMessage> evictable = new ArrayList<AgentMessage>();
        for (AgentMessage m : pending) {
            if (!inFlight.contains(m.getId()) && !m.isHandedOut()) {
                evictable.add(m); // mail awaiting processing is never evicted by new traffic
            }
        }
        int excess = pending.size() - limits.getPendingPerRecipient();
        for (int i = 0; i < excess && i < evictable.size(); i++) {
            store(evictable.get(i).withState(DeliveryState.EXPIRED, "inbox full"));
        }
    }

    private void expireStale() {
        long now = clock.millis();
        for (AgentMessage m : new ArrayList<AgentMessage>(messages.values())) {
            if (m.getState() == DeliveryState.PENDING && !m.getRecipient().isOrchestrator()
                    && !inFlight.contains(m.getId()) && !m.isHandedOut() && now - m.getCreatedAtMillis() > limits.getTtlMillis()) {
                store(m.withState(DeliveryState.EXPIRED, "not read within the time limit"));
            }
        }
    }

    private AgentMessage newMessage(AgentAddress sender, String senderTaskId, AgentAddress recipient,
                                    String recipientTaskId, String text, DeliveryState state, String note) {
        sequence++;
        return new AgentMessage(runId + "-m" + sequence, runId, sender, senderTaskId, recipient, recipientTaskId,
                text, clock.millis(), state, note);
    }

    private AgentMessage store(AgentMessage m) {
        persistLine(MailboxJournalCodec.encode(m));
        messages.put(m.getId(), m);
        return m;
    }

    private void persistLine(String line) {
        try {
            store.append(line);
        } catch (IOException failed) {
            throw new UncheckedIOException("mailbox journal of run " + runId + " not writable", failed);
        }
    }

    private void notifyOrchestrator(AgentMessage m) {
        Listener l = listener;
        if (l != null && m != null && m.getRecipient().isOrchestrator() && m.getState() == DeliveryState.PENDING) {
            l.orchestratorMail(m);
        }
    }

    private int sendsOf(String taskId) {
        Integer n = sendsByTask.get(taskId);
        return n == null ? 0 : n;
    }

    private void count(String taskId) {
        sendsByTask.put(taskId, sendsOf(taskId) + 1);
    }

    private String cut(String text) {
        String t = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        int max = limits.getMessageChars();
        return t.length() <= max ? t : t.substring(0, max - 1).trim() + "…";
    }

    private static String shortText(String text) {
        String t = text == null ? "" : text.trim();
        return t.length() <= 40 ? t : t.substring(0, 39) + "…";
    }

    private static void requireTask(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("taskId is required");
        }
    }
}
