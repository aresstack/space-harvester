package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JiraIdsTest {

    private static final String LOCATOR = "jira.example.org/jira";

    @Test
    public void issueIdsRoundTrip() {
        KnowledgeResourceId id = JiraIds.issue(LOCATOR, "DEMO-12");
        assertEquals("jira:jira.example.org/jira/issue/DEMO-12", id.value());
        assertEquals("DEMO-12", JiraIds.issueKey(LOCATOR, id));
        assertNull(JiraIds.issueKey("other/jira", id));
        assertNull(JiraIds.issueKey(LOCATOR, KnowledgeResourceId.of("jira", LOCATOR + "/issue/nope")));
        assertNull(JiraIds.issueKey(LOCATOR, KnowledgeResourceId.of("confluence", LOCATOR + "/issue/DEMO-1")));
    }

    @Test
    public void keysFollowJiraRules() {
        assertTrue(JiraIds.isIssueKey("A1_B-7"));
        assertFalse(JiraIds.isIssueKey("demo-1"));
        assertFalse(JiraIds.isIssueKey("1DEMO-1"));
        assertTrue(JiraIds.isProjectKey("OPS"));
        assertFalse(JiraIds.isProjectKey("ops"));
        assertFalse(JiraIds.isProjectKey("OPS-1"));
    }

    @Test
    public void startPointsAreRecognised() {
        assertTrue(JiraIds.startPoint(LOCATOR, "project:*").allProjects);
        assertEquals("DEMO", JiraIds.startPoint(LOCATOR, "project:demo").projectKey);
        assertEquals("DEMO-1", JiraIds.startPoint(LOCATOR, "demo-1").issueKey);
        assertEquals("DEMO-1", JiraIds.startPoint(LOCATOR, "issue:DEMO-1").issueKey);
        assertEquals("DEMO-1", JiraIds.startPoint(LOCATOR, "jira:" + LOCATOR + "/issue/DEMO-1").issueKey);
        assertEquals("project = A", JiraIds.startPoint(LOCATOR, "jql: project = A ").jql);
        assertNull(JiraIds.startPoint(LOCATOR, "jira:other/issue/DEMO-1"));
        assertNull(JiraIds.startPoint(LOCATOR, "irgendwas"));
        assertNull(JiraIds.startPoint(LOCATOR, "jql:"));
    }

    @Test
    public void startPointListKeepsCommasInsideJqlAndQuotes() {
        assertEquals(java.util.Arrays.asList("project:DEMO", "jql:project in (DEMO, OPS) AND summary ~ \"a, b\"", "OPS-7"),
                JiraIds.splitStartPoints("project:DEMO, jql:project in (DEMO, OPS) AND summary ~ \"a, b\"\nOPS-7;"));
        assertTrue(JiraIds.splitStartPoints(null).isEmpty());
        assertTrue(JiraIds.splitStartPoints(" , ").isEmpty());
    }
}
