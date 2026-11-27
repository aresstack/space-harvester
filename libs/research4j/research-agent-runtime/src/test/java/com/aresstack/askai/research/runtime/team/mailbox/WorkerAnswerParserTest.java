package com.aresstack.askai.research.runtime.team.mailbox;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plan T3: the sample answer of section 3 and malformed variants become messages or a typed failure. */
public class WorkerAnswerParserTest {

    private final WorkerAnswerParser parser = new WorkerAnswerParser(2, 5);

    @Test
    public void theSampleAnswerOfSectionThreeParses() {
        WorkerAnswerParser.Result r = parser.parse("SUMMARY: Die Studie misst Hitzestress bei Bauarbeitern mit "
                + "Smartwatches über 12 Wochen.\nUSEFUL: yes — peer-reviewed field study\nFOLLOW: 2, 4\n"
                + "TO ORCHESTRATOR: For Datenschutz we still need scientific studies, not vendor pages.\n"
                + "TO WORKER 4: Link 4 may answer your question about sensor accuracy.");
        assertTrue(r.isOk());
        WorkerAnswer a = r.getAnswer();
        assertTrue(a.getSummary().startsWith("Die Studie misst"));
        assertEquals(WorkerAnswer.Usefulness.YES, a.getUsefulness());
        assertEquals("peer-reviewed field study", a.getUsefulReason());
        assertEquals(Arrays.asList(2, 4), a.getFollow());
        assertEquals(2, a.getMessages().size());
        assertEquals("orchestrator", a.getMessages().get(0).getRecipient());
        assertEquals("worker 4", a.getMessages().get(1).getRecipient());
        assertFalse(r.isRepaired());
    }

    @Test
    public void searchLinesBecomeAtMostTheAllowedNewQueries() {
        WorkerAnswer a = new WorkerAnswerParser(2, 5, 2).parse("SUMMARY: s\nUSEFUL: yes\nFOLLOW: none\n"
                + "SEARCH: DGUV Regel 112-198; \"ISO 7243 Hitzestress\"\nSEARCH: iso 7243 hitzestress\n"
                + "SEARCH: WBGT Messung Baustelle").getAnswer();
        assertEquals(Arrays.asList("DGUV Regel 112-198", "ISO 7243 Hitzestress"), a.getSearches());
        assertTrue(new WorkerAnswerParser(2, 5, 2).parse("SUMMARY: s\nSEARCH: none").getAnswer().getSearches()
                .isEmpty());
        assertTrue(parser.parse("SUMMARY: s\nSEARCH: x y z").getAnswer().getSearches().isEmpty()); // limit 0
    }

    @Test
    public void markdownChatterContinuationLinesAndOmitPlaceholdersAreTolerated() {
        WorkerAnswerParser.Result r = parser.parse("Sure! Here is my answer.\n\n**Summary:** The page is a shop.\n"
                + "It sells helmets with sensors.\n- **Useful**: No, vendor advertising only\n"
                + "* Follow: none\nTo orchestrator: omit the line\nto worker#3: nothing new");
        assertTrue(r.isOk());
        WorkerAnswer a = r.getAnswer();
        assertEquals("The page is a shop. It sells helmets with sensors.", a.getSummary());
        assertEquals(WorkerAnswer.Usefulness.NO, a.getUsefulness());
        assertEquals("vendor advertising only", a.getUsefulReason());
        assertTrue(a.getFollow().isEmpty());
        assertEquals(1, a.getMessages().size());
        assertEquals("worker#3", a.getMessages().get(0).getRecipient());
    }

    @Test
    public void followNumbersAreBoundedDeduplicatedAndInRange() {
        WorkerAnswer a = parser.parse("SUMMARY: s\nUSEFUL: partially - only one chapter\nFOLLOW: 9, 3, 3, 1, 2")
                .getAnswer();
        assertEquals(Arrays.asList(3, 1), a.getFollow());
        assertEquals(WorkerAnswer.Usefulness.PARTLY, a.getUsefulness());
        WorkerAnswer noVerdict = parser.parse("SUMMARY: s").getAnswer();
        assertEquals(WorkerAnswer.Usefulness.UNSTATED, noVerdict.getUsefulness());
    }

    @Test
    public void aMissingSummaryGetsOneRepairThenATypedFailure() {
        final int[] calls = {0};
        WorkerAnswerParser.Repair repairOk = new WorkerAnswerParser.Repair() {
            public String retry(String instruction) {
                calls[0]++;
                assertTrue(instruction.contains("SUMMARY"));
                return "SUMMARY: fixed\nUSEFUL: yes";
            }
        };
        WorkerAnswerParser.Result repaired = parser.parse("USEFUL: yes\nFOLLOW: 1", repairOk);
        assertTrue(repaired.isOk());
        assertTrue(repaired.isRepaired());
        assertEquals(1, calls[0]);

        WorkerAnswerParser.Repair repairBad = new WorkerAnswerParser.Repair() {
            public String retry(String instruction) {
                calls[0]++;
                return "I could not read the page.";
            }
        };
        WorkerAnswerParser.Result failed = parser.parse("The page was about helmets.", repairBad);
        assertFalse(failed.isOk());
        assertEquals(WorkerAnswerParser.Failure.MISSING_SUMMARY, failed.getFailure());
        assertEquals(2, calls[0]);

        assertEquals(WorkerAnswerParser.Failure.EMPTY_ANSWER, parser.parse("  \n ").getFailure());
        assertEquals(WorkerAnswerParser.Failure.MISSING_SUMMARY, parser.parse("SUMMARY:\nUSEFUL: no").getFailure());
    }

    @Test
    public void theLabelledAnswerEndsInTheSameMailboxCallsAsTheTools() throws IOException {
        AgentMailbox mailbox = AgentMailbox.open("run", new InMemoryMailboxStore());
        mailbox.registerWorker("t1");
        mailbox.registerWorker("t2");
        WorkerAnswer a = parser.parse("SUMMARY: Useful survey.\nUSEFUL: yes — survey\n"
                + "TO ORCHESTRATOR: search for 2023 follow-up\nTO WORKER 2: see link 1").getAnswer();
        List<String> replies = a.deliver(mailbox, "t1");
        assertEquals(Arrays.asList("Sent to the orchestrator.", "Sent to worker 2.",
                "Task finished. Your summary went to the orchestrator."), replies);
        List<AgentMessage> review = mailbox.drainOrchestrator(10);
        assertEquals("search for 2023 follow-up", review.get(0).getText());
        assertEquals("USEFUL: yes — survey. Useful survey.", review.get(1).getText());
        assertEquals(Collections.singletonList("see link 1"), texts(mailbox.takeInbox("t2")));
    }

    private static List<String> texts(List<AgentMessage> ms) {
        String[] out = new String[ms.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ms.get(i).getText();
        }
        return Arrays.asList(out);
    }
}
