package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.team.mailbox.AgentMailbox;
import com.aresstack.askai.research.runtime.team.mailbox.AgentMessage;
import com.aresstack.askai.research.runtime.team.mailbox.InMemoryMailboxStore;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plan T4: one model call per page, labelled lines in, a verdict and mailbox messages out. */
public class PageWorkerTest {

    private AgentMailbox mailbox;
    private final List<List<PageWorker.Turn>> calls = new ArrayList<List<PageWorker.Turn>>();
    private final List<String> answers = new ArrayList<String>();

    private final PageWorker.Model model = new PageWorker.Model() {
        public String complete(List<PageWorker.Turn> messages) {
            calls.add(new ArrayList<PageWorker.Turn>(messages));
            return answers.isEmpty() ? null : answers.remove(0);
        }
    };

    @Before
    public void setUp() throws Exception {
        mailbox = AgentMailbox.open("run", new InMemoryMailboxStore());
        mailbox.registerWorker("q1");
        mailbox.registerWorker("q2");
    }

    private PageWorker worker(int maxFollow, int pageCharacters) {
        return new PageWorker(model, mailbox, "q1", "Hitze", "Wearables auf Baustellen", maxFollow, pageCharacters,
                null);
    }

    private static PageJudge.Page page(String text, String... links) {
        return new PageJudge.Page("Hitzestress Bau", "https://a.example/studie", "Studie", text, Arrays.asList(links));
    }

    @Test
    public void aUsefulPageGivesItsVerdictPicksLinksAndWritesToTheOrchestrator() {
        answers.add("SUMMARY: Feldstudie zu Hitzestress mit Smartwatches.\nUSEFUL: yes - peer-reviewed\n"
                + "FOLLOW: 2\nTO ORCHESTRATOR: For Hitze we still need official guidelines.\n"
                + "TO WORKER 2: Link 2 covers sensor accuracy.");
        PageWorker worker = worker(2, 1000);
        PageJudge.Verdict verdict = worker.judge(page("Text der Studie", "https://a.example/1",
                "https://b.example/2"));
        assertTrue(verdict.isUseful());
        assertEquals(Collections.singletonList("https://b.example/2"),
                verdict.select(Arrays.asList("https://a.example/1", "https://b.example/2")));
        assertEquals(1, calls.size()); // one model call per page
        List<AgentMessage> toOrchestrator = mailbox.drainOrchestrator(10);
        assertEquals(1, toOrchestrator.size());
        assertEquals("For Hitze we still need official guidelines.", toOrchestrator.get(0).getText());
        assertEquals(1, mailbox.takeInbox("q2").size());
        worker.finish();
        List<AgentMessage> report = mailbox.drainOrchestrator(10);
        assertEquals(1, report.size());
        assertTrue(report.get(0).getText().startsWith("1 of 1 page useful for \"Hitze\"."));
    }

    @Test
    public void theUserTextCarriesTheBriefThePageCutAndTheNumberedLinks() {
        String prompt = worker(1, 5).prompt(page("0123456789", "https://a.example/1"),
                Collections.<AgentMessage>emptyList());
        assertTrue(prompt.contains("Book: Wearables auf Baustellen."));
        assertTrue(prompt.contains("Chapter node: Hitze."));
        assertTrue(prompt.contains("Query that found the page: Hitzestress Bau"));
        assertTrue(prompt.contains("You are worker 1."));
        assertTrue(prompt.contains("10 characters, cut):\n01234\n"));
        assertTrue(prompt.contains("1. https://a.example/1"));
        assertTrue(prompt.contains("FOLLOW (at most 1)"));
    }

    @Test
    public void aNotUsefulPageIsSaidSoAndAMalformedAnswerGetsOneRepair() {
        answers.add("I think the page is fine.");
        answers.add("SUMMARY: A shop page.\nUSEFUL: no - advertising\nFOLLOW: none");
        PageJudge.Verdict verdict = worker(2, 1000).judge(page("Kaufen Sie jetzt"));
        assertFalse(verdict.isUseful());
        assertEquals(2, calls.size());
        List<PageWorker.Turn> repair = calls.get(1);
        assertEquals("assistant", repair.get(2).getRole());
        assertEquals("user", repair.get(3).getRole());
    }

    @Test
    public void aWorkerRelaysOnlyItsOwnReports() {
        mailbox.send("q1", "orchestrator", "from q1");
        mailbox.send("q2", "orchestrator", "from q2");
        List<AgentMessage> own = mailbox.drainOrchestratorFrom("q1", 10);
        assertEquals(1, own.size());
        assertEquals("from q1", own.get(0).getText());
        assertEquals("from q2", mailbox.drainOrchestratorFrom("q2", 10).get(0).getText());
    }

    @Test
    public void theWorkerSeesTheWeidezaunAndHandsOnItsNewQueries() {
        PageWorker fenced = new PageWorker(model, mailbox, "q1", "Hitze", "Wearables", 2, 1000, 1,
                "IN: Hitzeschutz am Bau\nOUT: Freizeitsport", null);
        String prompt = fenced.prompt(page("text"), Collections.<AgentMessage>emptyList());
        assertTrue(prompt.contains("AREA OF THE BOOK"));
        assertTrue(prompt.contains("OUT: Freizeitsport"));
        assertTrue(prompt.contains("SEARCH (at most 1"));
        answers.add("SUMMARY: s\nUSEFUL: yes\nFOLLOW: none\nSEARCH: ISO 7243\nSEARCH: WBGT");
        assertEquals(Collections.singletonList("ISO 7243"), fenced.judge(page("text")).getSearches());
    }

    @Test
    public void noUsableAnswerMeansNoJudgementAndTheInboxComesBack() {
        mailbox.send("q2", "worker 1", "Look at the DGUV rules.");
        PageJudge.Verdict verdict = worker(2, 1000).judge(page("text"));
        assertNull(verdict); // the funnel's own decision stands
        assertEquals(1, mailbox.takeInbox("q1").size());
    }
}
