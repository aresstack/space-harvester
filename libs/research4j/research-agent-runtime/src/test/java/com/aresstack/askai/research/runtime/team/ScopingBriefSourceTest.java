package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.research.store.FileResearchBriefStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Phase isolation: only a scoping output yields a research brief to persist; other phases yield nothing. */
public class ScopingBriefSourceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static ScopingAssistantOutput scoping(String brief, String ignoredMapArg) {
        return new ScopingAssistantOutput("msg", brief,
                Collections.singletonList(new SearchSuggestion("x", "", 1)), PhaseAdvice.neutral());
    }

    @Test
    public void onlyAScopingOutputYieldsBriefMarkdown() {
        assertEquals("# Brief\nX", ScopingBriefSource.briefMarkdown(scoping("# Brief\nX", "")));
        // A generic (non-scoping) phase output structurally has no research brief to write.
        assertNull(ScopingBriefSource.briefMarkdown(TeamAgentTurn.message("hello")));
    }

    @Test
    public void aScopingBriefFlowsIntoTheWorkingCopyWhileANonScopingOutputCannotWriteIt() {
        File dir;
        try {
            dir = folder.newFolder("proj");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        FileResearchBriefStore store = new FileResearchBriefStore(new File(dir, "brief"));

        String scopingBrief = ScopingBriefSource.briefMarkdown(scoping("# Brief\nWearables", "mindmap"));
        assertTrue(store.updateWorkingCopy(scopingBrief, 1L));
        assertEquals("# Brief\nWearables", store.effectiveContent());

        // A non-scoping output produces no brief markdown, so there is nothing to persist (test K).
        String nonScoping = ScopingBriefSource.briefMarkdown(TeamAgentTurn.message("outline reply"));
        assertNull(nonScoping);
        assertEquals("the research brief is untouched by a non-scoping turn",
                "# Brief\nWearables", store.effectiveContent());
    }

    @Test
    public void changingOnlyTheExplorationMapDoesNotReviseTheBrief() {
        File dir;
        try {
            dir = folder.newFolder("proj2");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        FileResearchBriefStore store = new FileResearchBriefStore(new File(dir, "brief"));

        assertTrue(store.updateWorkingCopy(
                ScopingBriefSource.briefMarkdown(scoping("# Brief\nWearables", "mindmap A")), 1L));
        // Same brief, different exploration map -> the brief field is unchanged, so nothing is written.
        assertFalse(store.updateWorkingCopy(
                ScopingBriefSource.briefMarkdown(scoping("# Brief\nWearables", "mindmap B")), 2L));
    }
}
