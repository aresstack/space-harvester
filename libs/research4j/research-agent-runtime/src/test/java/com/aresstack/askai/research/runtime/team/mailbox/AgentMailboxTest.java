package com.aresstack.askai.research.runtime.team.mailbox;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolResult;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plan T3 "done when": routing, redirect, caps, TTL, replay, never blocking, the two tools. */
public class AgentMailboxTest {

    private final long[] now = {1000L};
    private final AgentMailbox.Clock clock = new AgentMailbox.Clock() {
        public long millis() {
            return now[0];
        }
    };
    private InMemoryMailboxStore store;
    private AgentMailbox mailbox;

    @Before
    public void setUp() throws Exception {
        store = new InMemoryMailboxStore();
        mailbox = AgentMailbox.open("run1", store, new MailboxLimits(3, 80, 4, 60000L, 10), clock);
    }

    @Test
    public void aWorkerMessageReachesTheOrchestratorAndAnotherWorkersNextTurn() {
        final List<AgentMessage> woken = new ArrayList<AgentMessage>();
        mailbox.setListener(new AgentMailbox.Listener() {
            public void orchestratorMail(AgentMessage message) {
                woken.add(message);
            }
        });
        assertEquals(1, mailbox.registerWorker("t-a"));
        assertEquals(2, mailbox.registerWorker("t-b"));

        assertTrue(mailbox.send("t-a", "Orchestrator", "Only vendor pages here. Please search for studies.").isAccepted());
        assertTrue(mailbox.send("t-a", "worker 2", "Check source X, it may answer your question.").isAccepted());

        assertEquals(1, woken.size());
        List<AgentMessage> review = mailbox.drainOrchestrator(10);
        assertEquals(1, review.size());
        assertEquals("worker 1", review.get(0).getSender().key());
        assertEquals("t-a", review.get(0).getSenderTaskId());
        assertEquals(1000L, review.get(0).getCreatedAtMillis());
        assertTrue(mailbox.drainOrchestrator(10).isEmpty());

        List<AgentMessage> inbox = mailbox.takeInbox("t-b");
        assertEquals(1, inbox.size());
        assertEquals("MESSAGES FOR YOU:\n- worker 1: Check source X, it may answer your question.",
                MessagesForYouBlock.render(inbox));
        assertTrue(mailbox.takeInbox("t-b").isEmpty());
        assertEquals("", MessagesForYouBlock.render(mailbox.takeInbox("t-b")));
    }

    @Test
    public void aMessageToAFinishedWorkerIsRedirectedWithANote() {
        mailbox.registerWorker("t-a");
        mailbox.registerWorker("t-b");
        mailbox.finishTask("t-b", "Done.");
        mailbox.drainOrchestrator(10);

        SendReceipt receipt = mailbox.send("t-a", "worker 2", "Look at the 2021 study.");
        assertTrue(receipt.isAccepted());
        assertTrue(receipt.getReply().contains("went to the orchestrator"));
        List<AgentMessage> review = mailbox.drainOrchestrator(10);
        assertEquals(1, review.size());
        assertTrue(review.get(0).getText().startsWith("(meant for worker 2, who has finished)"));
        assertEquals("redirected from worker 2", review.get(0).getNote());
    }

    @Test
    public void unreadMessagesOfAWorkerThatFinishesGoToTheOrchestrator() {
        mailbox.registerWorker("t-a");
        mailbox.registerWorker("t-b");
        mailbox.send("t-a", "worker 2", "Please compare with source Y.");
        assertEquals("Task finished. Your summary went to the orchestrator.", mailbox.finishTask("t-b", "Page useless."));

        List<AgentMessage> review = mailbox.drainOrchestrator(10);
        assertEquals(2, review.size());
        assertEquals("Page useless.", review.get(0).getText());
        assertEquals("finish_task", review.get(0).getNote());
        assertTrue(review.get(1).getText().contains("Please compare with source Y."));
        assertEquals("Your task has already finished.", mailbox.finishTask("t-b", "again"));
        assertFalse(mailbox.send("t-b", "orchestrator", "late").isAccepted());
    }

    @Test
    public void unknownRecipientsAndSelfAreRefusedAndTheRefusalShowsInTheNextInbox() {
        mailbox.registerWorker("t-a");
        SendReceipt unknown = mailbox.send("t-a", "worker 9", "hello");
        assertFalse(unknown.isAccepted());
        assertFalse(mailbox.send("t-a", "the moon", "hello").isAccepted());
        assertFalse(mailbox.send("t-a", "worker 1", "hello me").isAccepted());
        assertFalse(mailbox.send("t-a", "orchestrator", "   ").isAccepted());

        List<AgentMessage> inbox = mailbox.takeInbox("t-a");
        assertEquals(2, inbox.size());
        assertEquals("Not sent: there is no worker 9 in this run.", inbox.get(0).getText());
        assertTrue(inbox.get(1).getText().contains("is not a recipient"));
        assertTrue(mailbox.drainOrchestrator(10).isEmpty());
        // refusals do not use up the cap
        for (int i = 0; i < 4; i++) {
            assertTrue(mailbox.send("t-a", "orchestrator", "note " + i).isAccepted());
        }
    }

    @Test
    public void handedOutMailIsOnlyDeliveredAfterAcknowledgeAndComesBackAfterACrash() throws Exception {
        mailbox.registerWorker("t-a");
        mailbox.registerWorker("t-b");
        mailbox.send("t-a", "worker 2", "hint for b");
        mailbox.send("t-a", "orchestrator", "report for the lead");

        List<AgentMessage> inbox = mailbox.takeInbox("t-b");
        List<AgentMessage> review = mailbox.drainOrchestrator(10);
        assertEquals(1, inbox.size());
        assertEquals(1, review.size());
        assertTrue(mailbox.takeInbox("t-b").isEmpty()); // not handed out twice in one process
        assertTrue(mailbox.drainOrchestrator(10).isEmpty());

        // crash before either recipient processed its mail: both come back
        AgentMailbox restarted = AgentMailbox.open("run1", store, new MailboxLimits(3, 80, 4, 60000L, 10), clock);
        assertEquals(1, restarted.takeInbox("t-b").size());
        List<AgentMessage> again = restarted.drainOrchestrator(10);
        assertEquals("report for the lead", again.get(0).getText());
        restarted.acknowledge(again);
        assertEquals(DeliveryState.DELIVERED, last(restarted.messages(), "report for the lead").getState());

        // a failed turn gives its mail back; a re-registered (re-queued) task sees it again too
        List<AgentMessage> failedTurn = restarted.drainOrchestrator(10);
        assertTrue(failedTurn.isEmpty());
        restarted.registerWorker("t-b");
        List<AgentMessage> retry = restarted.takeInbox("t-b");
        assertEquals(1, retry.size());
        restarted.release(retry);
        assertEquals(1, restarted.takeInbox("t-b").size());
    }

    @Test
    public void aFinishRetriedAfterACrashProducesNoSecondReportOrCopy() throws Exception {
        final boolean[] fail = {false};
        final InMemoryMailboxStore inner = new InMemoryMailboxStore();
        MailboxStore flaky = new MailboxStore() {
            public void append(String line) throws java.io.IOException {
                if (fail[0] && line.contains("\"kind\":\"worker\"")) {
                    throw new java.io.IOException("crash");
                }
                inner.append(line);
            }

            public List<String> readLines() {
                return inner.readLines();
            }
        };
        AgentMailbox box = AgentMailbox.open("run4", flaky, MailboxLimits.defaults(), clock);
        box.registerWorker("t-a");
        box.registerWorker("t-b");
        box.send("t-a", "worker 2", "unread hint");
        fail[0] = true;
        try {
            box.finishTask("t-b", "first summary");
        } catch (java.io.UncheckedIOException expected) {
            // crash before the worker record
        }
        fail[0] = false;
        AgentMailbox replayed = AgentMailbox.open("run4", inner, MailboxLimits.defaults(), clock);
        replayed.registerWorker("t-b");
        assertEquals("Task finished. Your summary went to the orchestrator.", replayed.finishTask("t-b", "second"));
        List<AgentMessage> review = replayed.drainOrchestrator(10);
        assertEquals(2, review.size());
        assertEquals("first summary", review.get(0).getText());
        assertTrue(review.get(1).getText().contains("unread hint"));
    }

    @Test
    public void mailAwaitingProcessingIsNeitherEvictedNorExpired() throws Exception {
        mailbox.registerWorker("t-b");
        mailbox.send(null, "worker 1", "important hint");
        List<AgentMessage> turn = mailbox.takeInbox("t-b");
        for (int i = 0; i < 30; i++) {
            mailbox.send(null, "worker 1", "noise " + i); // fills the inbox while the turn runs
        }
        now[0] += 120000L; // the turn outlives the TTL, then crashes
        AgentMailbox restarted = AgentMailbox.open("run1", store, new MailboxLimits(3, 80, 4, 60000L, 10), clock);
        List<AgentMessage> again = restarted.takeInbox("t-b");
        assertEquals(1, again.size());
        assertEquals("important hint", again.get(0).getText());
        assertEquals(turn.get(0).getId(), again.get(0).getId());
        restarted.acknowledge(again);
        assertEquals(DeliveryState.DELIVERED, last(restarted.messages(), "important hint").getState());
    }

    @Test
    public void refusalNotesKeepTheInboxBound() {
        mailbox.registerWorker("t-a");
        for (int i = 0; i < 30; i++) {
            mailbox.send("t-a", "worker 99", "hello");
        }
        int pending = 0;
        for (AgentMessage m : mailbox.messages()) {
            if (m.getState() == DeliveryState.PENDING) {
                pending++;
            }
        }
        assertEquals(10, pending);
    }

    @Test
    public void aFailedWriteDuringFinishLeavesTheTaskRetryable() throws Exception {
        final boolean[] fail = {false};
        final InMemoryMailboxStore inner = new InMemoryMailboxStore();
        MailboxStore flaky = new MailboxStore() {
            public void append(String line) throws java.io.IOException {
                if (fail[0] && line.contains("\"kind\":\"worker\"")) {
                    throw new java.io.IOException("disk full");
                }
                inner.append(line);
            }

            public List<String> readLines() {
                return inner.readLines();
            }
        };
        AgentMailbox box = AgentMailbox.open("run3", flaky, MailboxLimits.defaults(), clock);
        box.registerWorker("t-a");
        fail[0] = true;
        try {
            box.finishTask("t-a", "summary");
        } catch (java.io.UncheckedIOException expected) {
            // the worker record was not written
        }
        fail[0] = false;
        assertEquals("Task finished. Your summary went to the orchestrator.", box.finishTask("t-a", "summary"));
        AgentMailbox replayed = AgentMailbox.open("run3", inner, MailboxLimits.defaults(), clock);
        assertEquals("Your task has already finished.", replayed.finishTask("t-a", "again"));
    }

    @Test
    public void capsAndLengthsHold() {
        mailbox.registerWorker("t-a");
        for (int i = 0; i < 4; i++) {
            assertTrue(mailbox.send("t-a", "orchestrator", "message " + i).isAccepted());
        }
        SendReceipt over = mailbox.send("t-a", "orchestrator", "one too many");
        assertFalse(over.isAccepted());
        assertTrue(over.getReply().contains("all 4 messages"));

        mailbox.registerWorker("t-b");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longText.append("word ");
        }
        AgentMessage m = mailbox.send("t-b", "orchestrator", longText.toString()).getMessage();
        assertEquals(80, m.getText().length());
        assertTrue(m.getText().endsWith("…"));
    }

    @Test
    public void theInboxShowsTheLastMessagesOnlyAndPendingMailExpiresAfterTheTtl() {
        mailbox.registerWorker("t-a");
        for (int i = 0; i < 5; i++) {
            mailbox.send(null, "worker 1", "hint " + i);
        }
        List<AgentMessage> inbox = mailbox.takeInbox("t-a");
        assertEquals(3, inbox.size());
        assertEquals("hint 2", inbox.get(0).getText());
        assertEquals("hint 4", inbox.get(2).getText());

        mailbox.send(null, "worker 1", "stale hint");
        now[0] += 60001L;
        assertTrue(mailbox.takeInbox("t-a").isEmpty());
        assertEquals(DeliveryState.EXPIRED, last(mailbox.messages(), "stale hint").getState());
    }

    @Test
    public void aRecipientThatNeverReadsBlocksNobody() throws Exception {
        mailbox = AgentMailbox.open("run2", store, new MailboxLimits(3, 80, 100000, 60000L, 10), clock);
        mailbox.registerWorker("t-reader-never");
        mailbox.registerWorker("t-writer");
        long start = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            mailbox.send("t-writer", "worker 1", "ping " + i);
            mailbox.send(null, "worker 1", "orchestrator ping " + i);
        }
        assertTrue("sending must never wait", System.nanoTime() - start < 20_000_000_000L);
        int pending = 0;
        for (AgentMessage m : mailbox.messages()) {
            if (m.getState() == DeliveryState.PENDING) {
                pending++;
            }
        }
        assertEquals(10, pending);
    }

    @Test
    public void aRestartReplaysRegistrationsCountsAndUndeliveredMessages() throws Exception {
        mailbox.registerWorker("t-a");
        mailbox.registerWorker("t-b");
        mailbox.send("t-a", "worker 2", "unread before the crash");
        mailbox.send("t-a", "orchestrator", "report before the crash");
        mailbox.send("t-a", "orchestrator", "second report");

        AgentMailbox restarted = AgentMailbox.open("run1", store, new MailboxLimits(3, 80, 4, 60000L, 10), clock);
        assertEquals(Integer.valueOf(2), restarted.workerNumber("t-b"));
        assertEquals(2, restarted.registerWorker("t-b")); // re-queued task keeps its number
        assertEquals(3, restarted.registerWorker("t-c"));
        List<AgentMessage> inbox = restarted.takeInbox("t-b");
        assertEquals(1, inbox.size());
        assertEquals("unread before the crash", inbox.get(0).getText());
        assertEquals(2, restarted.drainOrchestrator(10).size());
        // three sends were used before the restart, one is left
        assertTrue(restarted.send("t-a", "orchestrator", "fourth").isAccepted());
        assertFalse(restarted.send("t-a", "orchestrator", "fifth").isAccepted());
        // message ids keep increasing
        AgentMessage newest = restarted.messages().get(restarted.messages().size() - 1);
        assertTrue(newest.getId().compareTo("run1-m0") > 0);
        assertNull(restarted.workerNumber("t-unknown"));
    }

    @Test
    public void theTwoToolsBindTheCallerAndAnswerInOneLine() {
        mailbox.registerWorker("t-a");
        mailbox.registerWorker("t-b");
        List<McpToolContribution> tools = MailboxTools.forTask(mailbox, "t-a");
        assertEquals("send_message", tools.get(0).getName());
        assertEquals("finish_task", tools.get(1).getName());

        McpToolResult sent = tools.get(0).getHandler().invoke(call("send_message", "recipient", "Worker 2",
                "message", "I found a study for you."));
        assertFalse(sent.isError());
        assertEquals("Sent to worker 2.", sent.getText());
        assertTrue(tools.get(0).getHandler().invoke(call("send_message", "message", "x")).isError());

        McpToolResult finished = tools.get(1).getHandler().invoke(call("finish_task", "summary", "Done, nothing open."));
        assertFalse(finished.isError());
        assertTrue(tools.get(1).getHandler().invoke(call("finish_task", "summary", "again")).getText()
                .contains("already finished"));
        assertEquals("worker 1", mailbox.drainOrchestrator(10).get(0).getSender().key());
    }

    @Test
    public void addressesAreReadTheWayASmallModelWritesThem() {
        assertEquals(AgentAddress.ORCHESTRATOR, AgentAddress.parse(" The Orchestrator: "));
        assertEquals(AgentAddress.worker(4), AgentAddress.parse("Worker 4"));
        assertEquals(AgentAddress.worker(4), AgentAddress.parse("worker4"));
        assertEquals(AgentAddress.worker(4), AgentAddress.parse("W4"));
        assertEquals(AgentAddress.worker(4), AgentAddress.parse("#4"));
        assertEquals(AgentAddress.worker(12), AgentAddress.parse("worker #12"));
        assertNull(AgentAddress.parse("worker 0"));
        assertNull(AgentAddress.parse("everyone"));
        assertNull(AgentAddress.parse(null));
    }

    private static AgentMessage last(List<AgentMessage> all, String text) {
        AgentMessage found = null;
        for (AgentMessage m : all) {
            if (m.getText().equals(text)) {
                found = m;
            }
        }
        return found;
    }

    private static McpToolCall call(String tool, String... kv) {
        Map<String, Object> args = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            args.put(kv[i], kv[i + 1]);
        }
        return new McpToolCall(tool, args);
    }
}
