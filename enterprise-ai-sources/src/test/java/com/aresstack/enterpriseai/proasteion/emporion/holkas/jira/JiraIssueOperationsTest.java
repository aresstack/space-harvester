package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Die Operationen der Jira-Werkzeuge an einer Jira-Quelle: JQL-Suche, Übergänge, Kommentar, Übergang. */
public class JiraIssueOperationsTest {

    private FakeJira jira;
    private JiraKnowledgeSource source;

    @Before
    public void setUp() {
        jira = JiraKnowledgeSourceContractTest.sample();
        jira.transitionOffers.add(new String[] {"21", "In Arbeit nehmen", "In Progress"});
        jira.transitionOffers.add(new String[] {"31", "Erledigen", "Done"});
        source = new JiraKnowledgeSource(SourceAddress.of("jira-dc"), JiraConfig.builder(FakeJira.BASE)
                .credentialRef(RecordingSecretProvider.REF).jqlFilter("resolution = Unresolved").build(), jira,
                new RecordingSecretProvider());
    }

    @Test
    public void issueKeysComeFromKeysAddressesAndResourceIds() {
        assertEquals("DEMO-1", source.issueKey("demo-1"));
        assertEquals("DEMO-1", source.issueKey(FakeJira.BASE + "/browse/DEMO-1"));
        assertEquals("DEMO-1", source.issueKey("jira:jira.example.org/jira/issue/DEMO-1"));
        assertNull(source.issueKey("https://other.example.org/browse/DEMO-1"));
        assertNull(source.issueKey("kein Schlüssel"));
    }

    @Test
    public void theJqlSearchKeepsTheRestrictionOfTheSource() throws Exception {
        JiraIssueOperations.IssueSearch found = source.searchIssues("project = \"OPS\"", 10);

        assertEquals(1, found.issues().size());
        JiraIssueOperations.IssueSummary issue = found.issues().get(0);
        assertEquals("OPS-7", issue.key());
        assertEquals("Server neu starten", issue.summary());
        assertEquals("Open", issue.status());
        assertEquals(FakeJira.BASE + "/browse/OPS-7", issue.url().toString());
        assertTrue(jira.jqls.toString(), jira.jqls.contains("(resolution = Unresolved) AND (project = \"OPS\")"));
        assertTrue(jira.posts.isEmpty());
    }

    @Test
    public void anOrInTheQueryDoesNotLiftTheRestrictionAndTheSortStaysAtTheEnd() throws Exception {
        source.searchIssues("project = DEMO OR project = OPS order by updated DESC", 10);
        source.searchIssues("ORDER BY key ASC", 10);

        assertEquals("(resolution = Unresolved) AND (project = DEMO OR project = OPS) order by updated DESC",
                jira.jqls.get(jira.jqls.size() - 2));
        assertEquals("resolution = Unresolved ORDER BY key ASC", jira.jqls.get(jira.jqls.size() - 1));
    }

    @Test
    public void transitionsListWhatTheAccountMayDo() throws Exception {
        List<JiraIssueOperations.Transition> transitions = source.transitions("DEMO-2");

        assertEquals(2, transitions.size());
        assertEquals("31", transitions.get(1).id());
        assertEquals("Erledigen", transitions.get(1).name());
        assertEquals("Done", transitions.get(1).toStatus());
        assertTrue(jira.posts.isEmpty());
    }

    @Test
    public void aCommentIsPostedOnceAsJson() throws Exception {
        String id = source.addComment("DEMO-2", "Zertifikat ist beantragt.");

        assertEquals("10001", id);
        assertEquals(1, jira.posts.size());
        assertEquals("/rest/api/2/issue/DEMO-2/comment", jira.posts.get(0)[0]);
        assertEquals("{\"body\":\"Zertifikat ist beantragt.\"}", jira.posts.get(0)[1]);
        assertTrue(jira.requestHeaders.get(jira.requestHeaders.size() - 1).containsKey("Authorization"));
    }

    @Test
    public void aTransitionChangesTheStatus() throws Exception {
        source.transition("DEMO-2", "31");

        assertEquals("/rest/api/2/issue/DEMO-2/transitions", jira.posts.get(0)[0]);
        assertEquals("{\"transition\":{\"id\":\"31\"}}", jira.posts.get(0)[1]);
        assertEquals("Done", source.issueSummary("DEMO-2").status());
    }

    @Test
    public void aRefusedWriteNamesTheReasonOfJiraAndIsNotRepeated() {
        jira.forcedPostResponse = FakeJira.json(400, "{\"errorMessages\":[],\"errors\":{\"comment\":\"Kommentar "
                + "zu lang\"}}");
        try {
            source.addComment("DEMO-2", "Text");
            fail("Jira hat abgelehnt");
        } catch (KnowledgeSourceException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("comment: Kommentar zu lang"));
        }
        assertEquals(1, jira.posts.size());
    }

    @Test
    public void anUnknownTransitionIsRefusedByJira() {
        try {
            source.transition("DEMO-2", "99");
            fail("unbekannter Übergang");
        } catch (KnowledgeSourceException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not valid"));
        }
    }

    @Test
    public void invalidArgumentsNeverReachJira() {
        for (Runnable call : new Runnable[] {
                () -> attempt(() -> source.addComment("kein Schlüssel", "Text")),
                () -> attempt(() -> source.addComment("DEMO-2", "  ")),
                () -> attempt(() -> source.transition("DEMO-2", "31; drop")),
        }) {
            call.run();
        }
        assertTrue(jira.posts.isEmpty());
    }

    private interface Call {
        void run() throws KnowledgeSourceException;
    }

    private static void attempt(Call call) {
        try {
            call.run();
            fail("ungültige Angabe angenommen");
        } catch (KnowledgeSourceException e) {
            assertEquals(KnowledgeSourceException.Kind.UNSUPPORTED, e.kind());
        }
    }
}
