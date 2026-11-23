package com.aresstack.enterpriseai.proasteion.application.browser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebExplorationRunTest {

    @Test
    public void countsDistinctCanonicalAddressesPerState() {
        WebExplorationRun run = new WebExplorationRun();
        run.begin(null);
        run.started();
        run.opened("https://Intranet.example:443/", "Start", false);
        Map<String, String> shown = new LinkedHashMap<String, String>();
        shown.put("link-1", "https://intranet.example/reise/");
        shown.put("link-2", "https://intranet.example/ki#top");
        run.linksListed(shown, 5, null);
        run.opened("https://intranet.example/reise", "Reisekosten", false);
        assertTrue(run.markCurrent(true));
        run.skipped("https://blocked.example/x");
        run.failed("https://intranet.example/ki", "Timeout");
        run.finished();

        WebExplorationProgress progress = run.progress();
        assertTrue(progress.isActive());
        assertFalse(progress.isRunning());
        assertEquals(2, progress.visited());
        assertEquals(2, progress.examined());
        assertEquals(5, progress.discovered());
        assertEquals(1, progress.relevant());
        assertEquals(1, progress.skipped());
        assertEquals(1, progress.failed());
        assertTrue(run.wasVisited("https://intranet.example/reise/"));
    }

    @Test
    public void beginForgetsThePreviousTurnAndReportsChanges() {
        WebExplorationRun run = new WebExplorationRun();
        run.begin(null);
        run.opened("https://a.example/", "A", false);
        final List<WebExplorationProgress> seen = new ArrayList<WebExplorationProgress>();

        assertEquals(0, run.begin(seen::add).visited());
        run.opened("https://b.example/", "B", false);
        WebExplorationProgress last = run.end();
        run.opened("https://c.example/", "C", false);

        assertEquals(1, seen.size());
        assertEquals(1, last.visited());
        assertEquals(WebExplorationProgress.StepKind.OPENED, seen.get(0).steps().get(0).kind());
        assertEquals("B", seen.get(0).steps().get(0).label());
    }

    @Test
    public void markWithoutOpenPageFailsAndLinkTargetsBelongToTheLastList() {
        WebExplorationRun run = new WebExplorationRun();
        assertFalse(run.markCurrent(true));
        Map<String, String> shown = new LinkedHashMap<String, String>();
        shown.put("link-1", "https://a.example/x");
        run.linksListed(shown, 1, "x");
        assertEquals("https://a.example/x", run.linkTarget("link-1"));
        run.opened("https://a.example/x", "X", false);
        assertNull(run.linkTarget("link-1"));
    }

    @Test
    public void keepsOnlyTheLatestSteps() {
        WebExplorationRun run = new WebExplorationRun();
        for (int i = 0; i < 20; i++) {
            run.opened("https://a.example/" + i, "P" + i, false);
        }
        List<WebExplorationProgress.Step> steps = run.progress().steps();
        assertEquals(WebExplorationRun.MAX_STEPS, steps.size());
        assertEquals("P19", steps.get(steps.size() - 1).label());
    }

    @Test
    public void canonicalAddress() {
        assertEquals("https://a.example/p?q=1", WebExplorationRun.canonical("HTTPS://A.Example:443/p/?q=1#x"));
        assertEquals("http://a.example:8080/", WebExplorationRun.canonical("http://a.example:8080"));
        assertNull(WebExplorationRun.canonical("mailto:x@a.example"));
    }

    @Test
    public void aNewTurnForgetsTheOpenPageOfThePreviousTurn() {
        WebExplorationRun run = new WebExplorationRun();
        run.opened("https://a.example/", "A", false);
        run.begin(null);

        assertFalse(run.markCurrent(true));
        assertEquals(0, run.progress().relevant());
    }
}
